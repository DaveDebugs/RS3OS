package com.opennxt.net.game.pipeline

import com.opennxt.OpenNXT
import com.opennxt.ext.isBigOpcode
import com.opennxt.ext.readOpcode
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import com.opennxt.util.ISAACCipher
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import it.unimi.dsi.fastutil.ints.Int2IntMap
import mu.KotlinLogging

class GamePacketFraming : ByteToMessageDecoder() {

    private val logger = KotlinLogging.logger { }

    private val protocol = OpenNXT.protocol

    private var inited = false
    private lateinit var isaac: ISAACCipher
    private lateinit var mapping: Int2IntMap
    private lateinit var side: Side

    private var state = State.READ_OPCODE
    private var opcode = -1
    private var size = -1

    private fun init(channel: Channel) {
        // `inited = true` used to be the FIRST line of this method, which turned
        // any failure here into a permanent, mislabelled one. The three
        // attributes come back as platform types; assigning null to a non-null
        // `lateinit var` throws immediately - and the flag was already true, so
        // every later decode() skipped init and died on
        // UninitializedPropertyAccessException instead, with the actual cause
        // (an attribute that was never set) never named again.
        //
        // Read into locals, then publish, then set the flag last: a throw now
        // leaves the framer uninitialised, so the next call retries and reports
        // the original fault. The explicit null checks exist so the message says
        // WHICH attribute was missing rather than which field was uninitialised.
        val incomingIsaac = channel.attr(RSChannelAttributes.INCOMING_ISAAC).get()
            ?: throw IllegalStateException(
                "INCOMING_ISAAC is not set on ${channel.remoteAddress()}: the game framer was installed before " +
                    "the login handler seeded the cipher"
            )
        val channelSide = channel.attr(RSChannelAttributes.SIDE).get()
            ?: throw IllegalStateException("SIDE is not set on ${channel.remoteAddress()}")

        isaac = incomingIsaac
        side = channelSide
        mapping = if (side == Side.CLIENT) protocol.clientProtSizes.values else protocol.serverProtSizes.values

        inited = true
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        try {
            if (!inited) init(ctx.channel())

            while (buf.isReadable) {
                if (state == State.READ_OPCODE) {
                    if (!buf.isReadable) return

                    // ClientProt opcodes on build 949 are ONE BYTE. ServerProt
                    // still uses the >=128 two-byte escape.
                    //
 // Measured, across every run of this server:
                    //
                    //   client opcodes framed OK : 0 4 48 55 89 98 105 113 114 125
                    //                              - every one below 128
                    //   framing failures         : 4643, 4754, 4770 - and all
                    //                              three decode to a FIRST BYTE
                    //                              OF 146, second byte varying
                    //                              (35, 146, 162)
                    //
                    // A constant first byte with a varying second is not noise.
                    // The client writes opcode 146 as a single byte; the escape
                    // reader sees >=128, consumes the following byte - the
                    // packet's own length/payload - and produces (146-128)<<8|b.
                    // From there the stream is unrecoverable, which is why the
                    // world session always died about a second in.
                    //
                    // Note the escape WOULD have round-tripped 146 correctly if
                    // the client used it (it would write 128,146 and we would
                    // read 146). We saw 146 as the first byte, so it does not.
                    //
                    // ServerProt is genuinely different and is left alone: we
                    // write UPDATE_STAT as the two bytes 128,184 and the 949
                    // client demonstrably dispatched it to its opcode-184
                    // handler - it crashed inside that handler, which no other
                    // opcode would have entered. So the escape is real outbound
                    // and absent inbound. Asymmetric, but measured in both
                    // directions.
                    //
                    // -Dopennxt.prot.clientBigOpcode=true restores the old
                    // behaviour for a deliberate comparison.
                    val useEscape = side != Side.CLIENT ||
                        System.getProperty("opennxt.prot.clientBigOpcode") == "true"

                    if (useEscape) {
                        if (buf.readableBytes() < 2 && buf.isBigOpcode(isaac)) {
                            logger.info { "is big opcode:  true, readable is 1, need to wait!" }
                            return
                        }
                        opcode = buf.readOpcode(isaac)
                    } else {
                        opcode = buf.readByte().toInt() - isaac.nextValue and 0xff
                    }
                    if (!mapping.containsKey(opcode)) {
                        // Census first: this frame dies here and never reaches
                        // DynamicPacketHandler, so without this the per-session
                        // summary would report the silence with no cause.
                        InboundCensus.unframeable(ctx.channel(), side, opcode)
                        logger.error { "No opcode->size mapping for opcode $opcode (side=$side)" }
                        buf.skipBytes(buf.readableBytes())
                        ctx.channel().close()
                        return
                    }

                    size = mapping[opcode]
                    state = if (size >= 0) State.READ_BODY else State.READ_SIZE
                }

                if (state == State.READ_SIZE && size < 0) {
                    if (buf.readableBytes() < -size) return

                    size = if (size == -1) buf.readUnsignedByte().toInt() else buf.readUnsignedShort()

                    state = State.READ_BODY
                }

                if (state == State.READ_BODY) {
                    if (buf.readableBytes() < size) return

                    val payload = buf.readBytes(size)
                    // TODO if proxy and side is client -> dump ?
//                    logger.info { "Received side=$side opcode=$opcode size=$size name=${if (side == Side.SERVER) OpenNXT.protocol.serverProtNames.reversedValues()[opcode] ?: "null" else "null"}" }

                    out.add(OpcodeWithBuffer(opcode, payload))

                    state = State.READ_OPCODE
                }
            }
        } catch (e: Exception) {
            // Was `e.printStackTrace()` and nothing else, and that is the worst
            // swallow in the tree: it did not close the channel, did not record
            // a diagnostic reason and did not rethrow, so after one throw the
            // connection stayed open and EVERY BYTE THE CLIENT SENT FROM THEN ON
            // WAS DISCARDED, FOREVER. From the client's side that is an
            // established, perfectly quiet socket.
            //
            // A framing fault is not recoverable in any case: the ISAAC opcode
            // stream and the frame boundary are positional, so there is no point
            // downstream at which reading can safely resume. Say what happened
            // with the connection's context, record it for the diagnostic run,
            // then close - a reset is diagnosable, a silently deaf socket is not.
            logger.error(e) {
                "Game framing failed on ${ctx.channel().remoteAddress()} " +
                    "(state=$state opcode=$opcode size=$size side=${if (inited) side.toString() else "?"}) " +
                    "- closing; the frame stream cannot be resynchronised"
            }
            DiagnosticLog.reason(
                ctx.channel(),
                "game framing exception in state=$state opcode=$opcode size=$size: " +
                    "${e.javaClass.name}: ${e.message}"
            )
            InboundCensus.framingFault(
                ctx.channel(),
                "framing exception in state=$state opcode=$opcode size=$size: ${e.javaClass.simpleName}"
            )
            buf.skipBytes(buf.readableBytes())
            ctx.close()
        }
    }

    private enum class State {
        READ_OPCODE,
        READ_SIZE,
        READ_BODY
    }
}