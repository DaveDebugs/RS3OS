package com.opennxt.tools.impl.cachedownloader

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.handshake.HandshakeType
import com.opennxt.net.js5.packet.Js5Packet
import com.opennxt.net.js5.packet.Js5PacketCodec
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.traffic.GlobalTrafficShapingHandler
import mu.KotlinLogging
import java.util.concurrent.Executors

object Js5ClientPipeline {
    private val executor = Executors.newScheduledThreadPool(4)
    private val trafficCounter = GlobalTrafficShapingHandler(executor, 250)

    /**
     * How many prefetch ints to expect between the handshake reply and the
     * first file. **Zero**, because the NXT JS5 protocol does not have a
     * prefetch block.
     *
     * This tool used to hard-wire a wait for 30 of them, and that wait is what
     * made a repair run against live Jagex fail with nothing in the log but a
     * timeout: TCP connected, the handshake went out, the server replied 0, the
     * state machine moved to PREFETCHES - and then sat there needing 120 bytes
     * that were never coming, because [Js5Client.notifyConnected] is only
     * called from the prefetch branch. Thirty seconds later `awaitConnected`
     * gave up with "Took more than 30 seconds to successfully connect", which
     * points at the connection rather than at the decoder that stalled.
     *
     * The count is 0 on measured evidence from the other side of the same
     * protocol: the client's expected count lives at `js5conn+0x2d8`, is zeroed
     * by the connection constructor, and is only ever written by a virtual that
     * the login-screen connection path never reaches. Our own server was fixed
     * to send none for exactly that reason, and a real 947 client then
     * completed the handshake and went straight to requesting files. This end
     * is the mirror image: we must not WAIT for what the server does not send.
     *
     * Overridable with `-Dopennxt.js5.client.prefetches=N` because this is a
     * runtime property of the peer, not a constant. If a server ever does send
     * a prefetch block, setting N restores the wait.
     */
    val EXPECTED_PREFETCHES: Int =
        System.getProperty("opennxt.js5.client.prefetches")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun getReadThroughput(): Long {
        return trafficCounter.trafficCounter().lastReadThroughput()
    }

    class Js5ClientChannelInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            val credentials = Js5Credentials.download()
            val client = Js5Client(credentials.version, credentials.token)
            ch.attr(Js5Client.ATTR_KEY).set(client)

            ch.pipeline().addLast("traffic-counter", trafficCounter)
            ch.pipeline().addLast("encoder", Js5ClientEncoder())
            ch.pipeline().addLast("decoder", Js5ClientDecoder(client))
            ch.pipeline().addLast("handler", Js5ClientHandler(client))
        }
    }

    class Js5ClientDecoder(private val client: Js5Client) : ByteToMessageDecoder() {
        override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
            client.lastRead = System.currentTimeMillis()

            if (!ctx.channel().isOpen || !client.state.canRead) {
                buf.skipBytes(buf.readableBytes())
                return
            }

            if (client.state == Js5ClientState.HANDSHAKE) {
                if (buf.readableBytes() < 1) return
                out.add(Js5PacketCodec.HandshakeResponse.decode(GamePacketReader(buf)))
                return
            }

            if (client.state == Js5ClientState.PREFETCHES) {
                // Read the ints here rather than via Js5PacketCodec.Prefetches.
                // The guard used to require `30 * 4` bytes and then call a codec
                // that decodes `IntArray(31)` - so it admitted 120 bytes and
                // read 124, consuming four bytes belonging to the next message.
                // Guard and read now come from one number, and the shared codec
                // (which the server side also uses, for encoding) is untouched.
                if (buf.readableBytes() < EXPECTED_PREFETCHES * 4) return
                out.add(Js5Packet.Prefetches(IntArray(EXPECTED_PREFETCHES) { buf.readInt() }))
                return
            }

            if (client.state == Js5ClientState.ACTIVE) {
                var request = client.current

                if (request == null) {
                    if (buf.readableBytes() < 5) return

                    val index = buf.readUnsignedByte().toInt().toLong()

                    val hash = buf.readInt().toLong()
                    val archive = hash and 0x7fffffff
                    val priority = (hash and -0x80000000L) == 0L

                    request = client.getRequest(priority, index.toInt(), archive.toInt())
                        ?: throw IllegalStateException("server sent file we were not requesting: [$priority, $index, $archive]")

                    client.current = request
                    request.offset = 5
                } else if (request.buffer == null) {
                    if (buf.readableBytes() < 5) return

                    val compression = buf.readUnsignedByte().toInt()
                    val fileSize = buf.readInt()

                    val size = fileSize + (if (compression == 0) 5 else 9) + (if (request.index == 255) 0 else 2)
                    val buffer = request.allocateBuffer(size)
                    buffer.put(compression.toByte())
                    buffer.putInt(fileSize)

                    request.offset = 10
                } else {
                    val buffer = request.buffer ?: throw IllegalStateException("buffer not initialized")
                    val totalSize = buffer.capacity() - (if (request.index == 255) 0 else 2)
                    var blockSize = 102400 - request.offset
                    val remaining = totalSize - buffer.position()

                    if (remaining < blockSize)
                        blockSize = remaining
                    if (buf.readableBytes() < blockSize)
                        blockSize = buf.readableBytes()

                    val block = ByteArray(blockSize)
                    buf.readBytes(block)
                    buffer.put(block)

                    request.offset += blockSize
                    if (buffer.position() == totalSize) {
                        buffer.flip()

                        if (!client.removeRequest(request))
                            throw IllegalStateException("failed to remove completed download from client request list")

                        request.notifyCompleted()

                        client.current = null
                    } else if (request.offset == 102400) {
                        request.offset = 0
                        client.current = null
                    }
                }
            }
        }
    }

    class Js5ClientEncoder : MessageToByteEncoder<Js5Packet>() {
        private val logger = KotlinLogging.logger {  }
        override fun encode(ctx: ChannelHandlerContext, msg: Js5Packet, out: ByteBuf) {
            when (msg) {
                is Js5Packet.Handshake -> {
                    out.writeByte(HandshakeType.JS_5.id)
                    Js5PacketCodec.Handshake.encode(msg, GamePacketBuilder(out))
                }
                is Js5Packet.RequestFile -> {
                    val opcode = if (msg.nxt) {
                        if (msg.priority) 33 else 32
                    } else {
                        if (msg.priority) 1 else 0
                    }

                    out.writeByte(opcode)
                    Js5PacketCodec.RequestFile.encode(msg, GamePacketBuilder(out))
                }
                is Js5Packet.ConnectionInitialized -> {
                    out.writeByte(6)
                    Js5PacketCodec.ConnectionInitialized.encode(msg, GamePacketBuilder(out))
                }
                is Js5Packet.LoggedIn -> {
                    out.writeByte(2)
                    Js5PacketCodec.LoggedIn.encode(msg, GamePacketBuilder(out))
                }
                is Js5Packet.LoggedOut -> {
                    out.writeByte(3)
                    Js5PacketCodec.LoggedOut.encode(msg, GamePacketBuilder(out))
                }
                else -> throw RuntimeException("idk what to do: $msg")
            }
        }
    }

    class Js5ClientHandler(private val client: Js5Client) : SimpleChannelInboundHandler<Js5Packet>() {
        private val logger = KotlinLogging.logger { }

        /**
         * Announce ourselves and release everything blocked on [Js5Client.awaitConnected].
         * Reached either straight off the handshake reply (the normal NXT case)
         * or after a prefetch block, if a peer ever sends one.
         */
        private fun goActive(ctx: ChannelHandlerContext) {
            client.state = Js5ClientState.ACTIVE
            ctx.channel().write(Js5Packet.ConnectionInitialized(5, client.version))
            ctx.channel().write(Js5Packet.LoggedOut(client.version))
            ctx.channel().flush()
            client.notifyConnected()
            logger.info { "js5 connection active - sent ConnectionInitialized + LoggedOut for build ${client.version}." }
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: Js5Packet) {
            when (msg) {
                is Js5Packet.HandshakeResponse -> {
                    if (msg.code != 0) {
                        // Say what the code MEANS. A bare number here cost a
                        // debugging session once already.
                        val meaning = when (msg.code) {
                            6 -> "server says our build is out of date - the version came from " +
                                "live jav_config's server_version, so this means the handshake shape is wrong"
                            7 -> "server is full"
                            9 -> "server rejected the js5 token"
                            else -> "see the client's handshake reply table"
                        }
                        logger.error { "js5 handshake rejected with code ${msg.code} ($meaning). Closing." }
                        client.state = Js5ClientState.CRASHED
                        ctx.channel().close().sync()
                        return
                    }

                    logger.info { "js5 handshake accepted (code 0)." }

                    if (EXPECTED_PREFETCHES == 0) {
                        // Straight to ACTIVE. Waiting here is what hung the tool
                        // - see EXPECTED_PREFETCHES for why the count is zero.
                        goActive(ctx)
                    } else {
                        logger.info { "Waiting for $EXPECTED_PREFETCHES prefetch ints before going active." }
                        client.state = Js5ClientState.PREFETCHES
                    }
                }
                is Js5Packet.Prefetches -> goActive(ctx)
                else -> throw RuntimeException("idk how to handle: $msg")
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            client.state = Js5ClientState.CRASHED
            logger.warn { "Client handler crashed! Stack trace is below." }
            cause.printStackTrace()
            ctx.channel().close().sync()
        }
    }
}