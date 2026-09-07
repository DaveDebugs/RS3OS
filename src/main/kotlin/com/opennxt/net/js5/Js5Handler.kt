package com.opennxt.net.js5

import com.opennxt.Js5Thread
import com.opennxt.OpenNXT
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.packet.Js5Packet
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import mu.KotlinLogging

class Js5Handler(val session: Js5Session): SimpleChannelInboundHandler<Js5Packet>() {
    private val logger = KotlinLogging.logger {  }

    var handledHandshake = false

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Js5Packet) {
        when(msg) {
            is Js5Packet.Handshake -> {
                if (handledHandshake)
                    throw IllegalStateException("Already handled handshake")
                handledHandshake = true

                logger.warn { "Sending OK - TODO: Check build & js5 token prior to accepting this connection" }

                // Diagnostic only (opt-in): the js5 handshake is the FIRST place the
                // client states which build it is. The server still accepts the
                // connection either way (behaviour unchanged, see the TODO above);
                // we only record what was announced, and shout if it disagrees with
                // the build whose packet tables this server loaded.
                if (DiagnosticLog.enabled) {
                    DiagnosticLog.note(
                        ctx.channel(), DiagnosticLog.Stage.JS5,
                        "HANDSHAKE major=${msg.major} minor=${msg.minor} token='${msg.token}' language=${msg.language}"
                    )
                    DiagnosticLog.buildAnnounced(
                        ctx.channel(), DiagnosticLog.Stage.JS5, msg.major,
                        "js5 handshake, minor=${msg.minor}, token='${msg.token}'"
                    )
                }

                ctx.channel().write(Js5Packet.HandshakeResponse(0))

                // The prefetch table is NOT sent by default any more, and this
                // is the most consequential line in the JS5 path.
                //
                // The client's handshake-reply handler reads exactly one byte,
                // then reads `js5conn+0x2d8 * 4` bytes of prefetch values before
                // it sends anything else. That field is ZEROED by the connection
                // constructor and written in exactly one place - a virtual that
                // parses a config blob the login-screen connection path never
                // supplies. So for a client connecting the way ours does, the
                // count is 0 and it expects NO prefetch bytes at all.
                //
                // Sending 31 ints anyway put 124 unexpected bytes into a stream
                // where the client had already moved on to reading 5-byte block
                // headers. It does not error - it misparses them as headers and
                // the cache stream desynchronises silently. Observed exactly
                // that: a real 947 client completed the handshake, entered the
                // JS5 stage, and then never sent another byte.
                //
                // Overridable because the count is a runtime property of the
                // client rather than a constant: `-Dopennxt.js5.prefetches=31`
                // restores the old behaviour if a client ever does ask.
                val prefetchCount = System.getProperty("opennxt.js5.prefetches")?.toIntOrNull() ?: 0
                if (prefetchCount > 0) {
                    val available = OpenNXT.prefetches.entries
                    ctx.channel().write(Js5Packet.Prefetches(available.copyOf(minOf(prefetchCount, available.size))))
                }
                DiagnosticLog.note(
                    ctx.channel(), DiagnosticLog.Stage.JS5,
                    "SENT handshake response 0; prefetch ints sent = $prefetchCount " +
                        "(the client's expected count lives at js5conn+0x2d8 and defaults to 0; " +
                        "sending more desynchronises its block reader with no error)"
                )
                ctx.channel().flush()
            }
            else -> {
                // Behaviour unchanged: this still throws NotImplementedError. It just
                // says what it choked on first.
                DiagnosticLog.note(
                    ctx.channel(), DiagnosticLog.Stage.JS5,
                    "UNHANDLED JS5 PACKET reached the handler: $msg (server throws next)"
                )
                TODO("Encode $msg")
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.warn(cause) { "Caught exception, closing js5 connection from ${ctx.channel().remoteAddress()}" }
        DiagnosticLog.reason(ctx.channel(), "js5 handler exception: ${cause.javaClass.name}: ${cause.message}")

        // This used to log and return, leaving the socket open and half-dead.
        // A decode fault upstream of here is not recoverable - the JS5 request
        // stream has no resynchronisation point - so the connection can only
        // sit there. That is exactly how the pipelined-handshake defect
        // presented: the client got neither the handshake reply nor a FIN and
        // waited indefinitely (measured: bytesIn=22 bytesOut=0, socket still
        // open at the probe's 3 s timeout). Closing turns an indefinite hang
        // into an immediate, diagnosable reset; the SUMMARY line recorded above
        // still says why it happened.
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // remove session if the client connection drops and doesn't send the termination packet
        Js5Thread.removeSession(session)

        // Was absent, so inactivity stopped dead at this handler and nothing
        // below it in the pipeline ever learned the channel had gone. Harmless
        // with today's pipeline; a latent break for anything added later.
        ctx.fireChannelInactive()
    }
}