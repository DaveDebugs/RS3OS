package com.opennxt.net.handshake

import com.opennxt.net.DiagnosticLog
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging

class HandshakeDecoder: ByteToMessageDecoder() {
    val logger = KotlinLogging.logger {  }

    init {
        isSingleDecode = true
    }

    // Diagnostic only (opt-in, -Dopennxt.diag=true): starts recording for this
    // connection and installs the pass-through byte tap at the head of the
    // pipeline. handlerAdded runs at channel registration, before a single byte
    // can arrive, so the recorded byte counts are complete. No-op when off.
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        DiagnosticLog.attach(ctx)
        super.handlerAdded(ctx)
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        val id = buf.readUnsignedByte().toInt()
        val type = HandshakeType.fromId(id)

        if (type == null) {
            logger.warn { "Client from ${ctx.channel().remoteAddress()} attempted to handshake with unknown id: $id" }
            if (DiagnosticLog.enabled) {
                DiagnosticLog.bytes(
                    ctx.channel(), DiagnosticLog.Stage.HANDSHAKE,
                    "UNKNOWN HANDSHAKE ID $id (0x${Integer.toHexString(id)}) - closing; trailing bytes follow",
                    DiagnosticLog.snapshot(buf), buf.readableBytes()
                )
                DiagnosticLog.reason(ctx.channel(), "unknown handshake id $id")
            }
            ctx.close()
            buf.skipBytes(buf.readableBytes())
            return
        }

        logger.info { "Received handshake from ${ctx.channel().remoteAddress()} with type $type" }
        if (DiagnosticLog.enabled) {
            DiagnosticLog.note(
                ctx.channel(), DiagnosticLog.Stage.HANDSHAKE,
                "HANDSHAKE BYTE $id (0x${Integer.toHexString(id)}) = $type, ${buf.readableBytes()} byte(s) already buffered behind it"
            )
        }
        out.add(HandshakeRequest(type))
    }
}