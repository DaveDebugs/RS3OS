package com.opennxt.net.js5

import com.opennxt.ext.readBuild
import com.opennxt.ext.readString
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.js5.packet.Js5Packet
import com.opennxt.net.js5.packet.Js5PacketCodec
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging

class Js5Decoder(val session: Js5Session) : ByteToMessageDecoder() {
    private val logger = KotlinLogging.logger { }

    var handshakeDecoded = false

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (!handshakeDecoded) {
            buf.markReaderIndex()
            val size = buf.readUnsignedByte().toInt()

            // `size` counts the bytes AFTER itself: major u32, minor u32, the
            // token, a NUL and the language byte. So size == 10 exactly means a
            // ZERO-LENGTH token, which is legal, and is precisely what a client
            // with no token configured sends.
            //
            // This guard used to read `size <= 10` and silently closed the
            // connection on that entirely valid frame. A real 947 client sent
            //     0a 00 00 03 b3 00 00 00 01 00 00
            // - size 10, build 947-1, empty token - and the server hung up
            // without a word. It cost a full round trip to find precisely
            // because the rejection produced no diagnostic: the log just
            // stopped after "STAGE ENTERED". Hence the recording below.
            if (size < 10) {
                val why = "js5 handshake size byte is $size, below the 10-byte minimum " +
                    "(major u32 + minor u32 + NUL + language)"
                logger.warn { "$why, from ${ctx.channel().remoteAddress()}" }
                DiagnosticLog.note(ctx.channel(), DiagnosticLog.Stage.JS5, "REJECTED: $why")
                DiagnosticLog.reason(ctx.channel(), why)
                buf.skipBytes(buf.readableBytes())
                ctx.channel().close()
                return
            }

            if (buf.readableBytes() < size) {
                buf.resetReaderIndex()
                return
            }

            val build = buf.readBuild()
            val token = buf.readString()
            val language = buf.readUnsignedByte().toInt()

            // There used to be a `check(!buf.isReadable)` here, and it wedged
            // connections shut.
            //
            // This decoder is not `isSingleDecode`, so its cumulation buffer
            // legitimately holds whatever else arrived in the same TCP segment.
            // A peer that coalesces the handshake with its first request - which
            // nothing on the wire forbids, and which TCP is free to do whenever
            // a client writes the two close together - made the check throw
            // BEFORE `out.add` ran. The handshake message therefore never
            // reached Js5Handler, the one-byte handshake response was never
            // written, and Js5Handler.exceptionCaught only logged, so the socket
            // was left open. Measured against this repository with
            // /tmp/probe/js5probe.py:
            //
            //   control   len=1  reply=00   (handshake in its own write)
            //   pipereq   len=0  TIMEOUT - nothing sent, socket STILL OPEN
            //
            // and the diagnostic SUMMARY for the hung connection read
            // `bytesIn=22 bytesOut=0 durationMs=3002`. The client waits forever
            // for a byte that is never coming.
            //
            // Removing the check changes nothing on the wire for a client that
            // does not coalesce: `handshakeDecoded` is set below and this call
            // returns, and ByteToMessageDecoder then re-enters `decode` with the
            // remaining bytes, which take the request path.
            out.add(Js5Packet.Handshake(build.major, build.minor, token, language))

            handshakeDecoded = true
            return
        }

        if (buf.readableBytes() < 10) return
        when (val opcode = buf.readUnsignedByte().toInt()) {
            Js5PacketCodec.RequestFile.opcodeLow,
            Js5PacketCodec.RequestFile.opcodeHigh,
            Js5PacketCodec.RequestFile.opcodeNxtLow,
            Js5PacketCodec.RequestFile.opcodeNxtHigh1,
            Js5PacketCodec.RequestFile.opcodeNxtHigh2 -> {
                val request = Js5PacketCodec.RequestFile.decode(GamePacketReader(buf))
                // The second comparison tested opcodeNxtLow twice; the legacy low-priority
                // opcode (0) was therefore classified as priority. Harmless for an NXT
                // client (32 low / 33+17 high), wrong for the legacy one - and this flag
                // feeds the response bit the client uses to route the file.
                request.priority = opcode != Js5PacketCodec.RequestFile.opcodeNxtLow && opcode != Js5PacketCodec.RequestFile.opcodeLow
//                logger.info { "Requested file ${request.index}, ${request.archive}. Priority: ${request.priority}" }

                if (request.priority) session.highPriorityRequests.add(request)
                else session.lowPriorityRequests.add(request)
            }

            Js5PacketCodec.ConnectionInitialized.opcode -> {
                logger.info { "Connection initialized" }
                Js5PacketCodec.ConnectionInitialized.decode(GamePacketReader(buf))
                ctx.channel().attr(Js5Session.ATTR_KEY).get().initialize()
            }

            Js5PacketCodec.RequestTermination.opcode -> {
                logger.info { "Request termination" }
                Js5PacketCodec.RequestTermination.decode(GamePacketReader(buf))
                ctx.channel().attr(Js5Session.ATTR_KEY).get().close()
            }

            Js5PacketCodec.XorRequest.opcode -> {
                val packet = Js5PacketCodec.XorRequest.decode(GamePacketReader(buf))
                logger.info { "Set XOR: ${packet.xor}" }
                ctx.channel().attr(Js5Session.XOR_KEY).set(packet.xor)
            }

            Js5PacketCodec.LoggedIn.opcode -> {
                logger.info { "Logged in" }
                Js5PacketCodec.LoggedIn.decode(GamePacketReader(buf))
                ctx.channel().attr(Js5Session.LOGGED_IN).set(true)
            }

            Js5PacketCodec.LoggedOut.opcode -> {
                logger.info { "Logged out" }
                Js5PacketCodec.LoggedOut.decode(GamePacketReader(buf))
                // Was `set(true)` - copy-paste from the LoggedIn branch above,
                // so the flag could only ever go up. Inert today (nothing reads
                // LOGGED_IN; the only other writes are the LoggedIn branch and
                // the initialiser in Js5Session), which is exactly why it is
                // worth correcting now rather than after something starts using
                // it to prioritise or account JS5 traffic. No wire effect.
                ctx.channel().attr(Js5Session.LOGGED_IN).set(false)
            }

            else -> {
                logger.warn { "Unknown js5 opcode $opcode from ${ctx.channel().remoteAddress()}. Skipping request" }
                buf.skipBytes(9)
            }
        }
    }
}