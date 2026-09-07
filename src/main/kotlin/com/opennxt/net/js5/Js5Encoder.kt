package com.opennxt.net.js5

import com.opennxt.filesystem.Container
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.packet.Js5Packet
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.ByteBuffer
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import mu.KotlinLogging
import kotlin.math.min

class Js5Encoder(val session: Js5Session) : MessageToByteEncoder<Js5Packet>() {
    private val logger = KotlinLogging.logger { }

    override fun encode(ctx: ChannelHandlerContext, msg: Js5Packet, out: ByteBuf) {
        when (msg) {
            is Js5Packet.HandshakeResponse -> out.writeByte(msg.code)
            is Js5Packet.Prefetches -> for (value in msg.prefetches) out.writeInt(value)
            is Js5Packet.RequestFileResponse -> {
                val xor = session.channel.attr(Js5Session.XOR_KEY).get()

                // Two container envelopes exist in this cache. The classic form
                // carries [compression u8][length u32] and the wire length is
                // derived from that header. The 2026 "ZLB" form REPLACES that
                // header - magic 'ZLB\u0001', inflated-size u32, then a zlib
                // stream running to the end of the stored blob - so the
                // container's total size IS the blob's size and there is
                // nothing to derive. Reading the 'Z' (0x5A) as a compression id
                // used to compute a ~1.28 GB length here, overrun the real data
                // that.
                //
                // This comment used to end by saying whether a real NXT client
                // accepts a ZLB blob framed this way "is a question only the
                // client experiment can answer". It is answered, and the answer
                // is NO. The 947 client's block reader rejects any container
                // whose compression id exceeds 3 -
                //     if (((int)length < 0) || (3 < compression))
                // - and then flushes and DROPS THE CONNECTION. Every ZLB file
                // would kill the JS5 session on first sight.
                //
                // So ZLB blobs are inflated and re-emitted as a classic
                // container with compression 0, which the client accepts.
                // Deliberately NOT re-compressed: that would add a second thing
                // that can be wrong, for a bandwidth saving that does not
                // matter over loopback.
                val data = reframeForClient(msg.data)

                // Everything from here to the loop is a bounds check that did
                // not exist, and its absence made a malformed container fail
                // COMPLETELY SILENTLY.
                //
                // `length` below is taken from the container's OWN header. On a
                // truncated blob, an unrecognised envelope or a wrong
                // compression id it can exceed the bytes actually stored - the
                // ZLB comment above describes computing ~1.28 GB from one.
                // `data.readByte()` then threw IndexOutOfBoundsException inside
                // the copy loop, BEFORE the `remaining != 0` report below could
                // run. MessageToByteEncoder released the half-built `out` and
                // rethrew as EncoderException; Netty's invokeWrite0 turned that
                // into a FAILED PROMISE rather than an exceptionCaught; and
                // Js5Session.process discarded the future. Net effect: nothing
                // written, nothing logged, the client waiting forever for a file
                // it will never be told about, and the diagnostic SUMMARY
                // reporting it under js5Unserved with no cause.
                //
                // Two guards, both reporting the archive coordinates, because
                // "which file" is the first question anyone asks:
                //   - a container too short to even hold the 5-byte classic
                //     header (getByte(1..4) would throw), and
                //   - a header length that overruns the stored bytes.
                // Both refuse to write, which is the SAME thing that reaches the
                // wire today - the difference is that it now says so.
                if (data.readableBytes() < 5) {
                    logger.error {
                        "js5 container for [${msg.index}, ${msg.archive}] is only ${data.readableBytes()} " +
                            "byte(s) - too short to hold a container header; serving nothing"
                    }
                    DiagnosticLog.note(
                        session.channel, DiagnosticLog.Stage.JS5,
                        "MALFORMED CONTAINER index=${msg.index} archive=${msg.archive} " +
                            "size=${data.readableBytes()} (< 5-byte header) - NOT SERVED"
                    )
                    return
                }

                var length: Int
                if (data.getByte(0).toInt() == 0x5A && data.getByte(1).toInt() == 0x4C &&
                    data.getByte(2).toInt() == 0x42 && data.getByte(3).toInt() == 0x01
                ) {
                    length = data.readableBytes()
                } else {
                    length = ((data.getByte(1).toInt() and 0xff) shl 24) + ((data.getByte(2)
                        .toInt() and 0xff) shl 16) + ((data.getByte(3).toInt() and 0xff) shl 8) + (data.getByte(4)
                        .toInt() and 0xff) + 5
                    if (data.getByte(0).toInt() != 0) length += 4
                }

                if (length < 0 || length > data.readableBytes()) {
                    logger.error {
                        "js5 container header for [${msg.index}, ${msg.archive}] declares $length byte(s) but only " +
                            "${data.readableBytes()} are stored (compression id ${data.getByte(0).toInt() and 0xff}) " +
                            "- refusing to serve a container that would overrun mid-encode"
                    }
                    DiagnosticLog.note(
                        session.channel, DiagnosticLog.Stage.JS5,
                        "MALFORMED CONTAINER index=${msg.index} archive=${msg.archive} declaredLength=$length " +
                            "stored=${data.readableBytes()} compression=${data.getByte(0).toInt() and 0xff} - NOT SERVED"
                    )
                    return
                }

                var remaining = length
                while (data.isReadable && remaining > 0) {
                    out.writeByte(msg.index xor xor)

                    val size = if (msg.priority) msg.archive else (msg.archive or -0x80000000)
                    out.writeByte((size shr 24) xor xor)
                    out.writeByte((size shr 16) xor xor)
                    out.writeByte((size shr 8) xor xor)
                    out.writeByte((size) xor xor)

                    for (i in 0 until min(102400 - 5, remaining)) { // - 5 due to header written above
                        out.writeByte(data.readByte().toInt() xor xor)
                        remaining--
                    }
                }

                if (remaining != 0) {
                    logger.error { "remaining != 0! ${remaining}, ${data.readableBytes()} in [${msg.index}, ${msg.archive}]" }
                }
            }
            else -> logger.warn { "I don't know how to encode $msg!" }
        }
    }

    /**
     * Returns a buffer the client will accept: classic containers pass through
     * untouched, ZLB envelopes are inflated and re-wrapped as `[0][len][data]`.
     *
     * The re-wrap allocates; that is accepted because the alternative is a
     * dropped connection. [Container.unwrapZlbOrNull] returns null for anything
     * that is not a ZLB envelope, which is the pass-through case.
     */
    private fun reframeForClient(data: ByteBuf): ByteBuf {
        if (data.readableBytes() < 4) return data
        if (!(data.getByte(0).toInt() == 0x5A && data.getByte(1).toInt() == 0x4C &&
                data.getByte(2).toInt() == 0x42 && data.getByte(3).toInt() == 0x01)
        ) return data

        val raw = ByteArray(data.readableBytes())
        data.getBytes(data.readerIndex(), raw)
        val inflated = Container.unwrapZlbOrNull(ByteBuffer.wrap(raw))
            ?: return data                       // magic matched but not inflatable; serve as-is
        val body = ByteArray(inflated.remaining())
        inflated.get(body)

        bump()
        val out = Unpooled.buffer(5 + body.size)
        out.writeByte(0)                          // compression 0 - no further header
        out.writeInt(body.size)
        out.writeBytes(body)
        return out
    }

    companion object {
        /**
         * How many ZLB containers this process has had to re-frame. Non-zero
         * proves the path is live; zero on a cache that is known to contain ZLB
         * blobs would mean the detection stopped matching.
         */
        @Volatile
        @JvmStatic
        var zlbReframed: Int = 0
            private set

        private fun bump() { zlbReframed++ }
    }
}
