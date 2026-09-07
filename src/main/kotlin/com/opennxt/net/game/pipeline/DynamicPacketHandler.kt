package com.opennxt.net.game.pipeline

import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import mu.KotlinLogging
import java.util.*

class DynamicPacketHandler : SimpleChannelInboundHandler<OpcodeWithBuffer>() {
    private val logger = KotlinLogging.logger { }

    /**
     * Open the inbound census for this connection.
     */
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        InboundCensus.begin(ctx.channel())
        super.handlerAdded(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: OpcodeWithBuffer) {
        try {
            // BEFORE receive(), which decodes the buffer and then releases it.
            // The census reads through absolute getters and moves nothing.
            InboundCensus.frame(
                ctx.channel(), ctx.channel().attr(RSChannelAttributes.SIDE).get(), msg.opcode, msg.buf
            )
            ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get().receive(msg)
        } catch (e: Exception) {
            // Was e.printStackTrace(): bare stderr, no peer, no side, no opcode.
            // The most likely cause here is a null CONNECTED_CLIENT - this
            // handler is installed by the pipeline swap while the attribute is
            // set at channel init - so say whether it was present, rather than
            // leaving an anonymous NPE for someone to guess at.
            logger.error(e) {
                "Failed to hand opcode ${msg.opcode} to the connected client on " +
                    "${ctx.channel().remoteAddress()} (side=${ctx.channel().attr(RSChannelAttributes.SIDE).get()}, " +
                    "connectedClient present=${ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get() != null})"
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) { "Exception caught in packet handler" }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // The one line that answers "did the client send anything, and what".
        // On by default; one line per session is not a volume problem, and its
 // absence for a whole session is what cost the run.
        InboundCensus.summarise(ctx.channel(), ctx.channel().attr(RSChannelAttributes.SIDE).get())

        logger.info { "Channel on side ${ctx.channel().attr(RSChannelAttributes.SIDE).get()} went inactive" }

        val passthrough = ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get()
        if (passthrough != null && passthrough.isOpen) {
            passthrough.close()
        }
    }
}