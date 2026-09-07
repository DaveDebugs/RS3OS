package com.opennxt.net.http

import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.endpoints.JavConfigWsEndpoint
import com.opennxt.net.http.endpoints.ClientFileEndpoint
import com.opennxt.net.http.endpoints.Js5MsEndpoint
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import mu.KotlinLogging

/**
 * Routes the three HTTP endpoints the launcher path uses.
 *
 * ## Why this class logs at all now
 *
 * It used to declare a logger and never call it, and nothing on the HTTP path
 * touched [DiagnosticLog] either. That produced a genuinely misleading blind
 * spot on the first live client run: the server log showed no requests, and that
 * was read as "the client never connected" - when in truth the server had no way
 * to report either outcome. An absence claim resting on an instrument that never
 * records the event is worth nothing, which is a rule this server applies
 * everywhere except, as it turned out, here.
 *
 * Every request is now logged AND recorded to the diagnostic file, including the
 * ones that 404 and including malformed ones. "No requests" becomes a
 * measurement instead of a guess.
 */
@ChannelHandler.Sharable
class HttpRequestHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
    private val logger = KotlinLogging.logger { }

    private fun note(ctx: ChannelHandlerContext, line: String) {
        val peer = ctx.channel().remoteAddress()?.toString() ?: "?"
        logger.info { "HTTP $peer $line" }
        DiagnosticLog.http(ctx.channel(), line)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        if (!msg.decoderResult().isSuccess) {
            note(ctx, "MALFORMED REQUEST -> 400 (${msg.decoderResult()})")
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        if (msg.method() != HttpMethod.GET) {
            note(ctx, "${msg.method()} ${msg.uri()} -> 405 (only GET is served)")
            ctx.sendHttpError(HttpResponseStatus.METHOD_NOT_ALLOWED)
            return
        }
        val uri = msg.uri()
        val query = QueryStringDecoder(uri)

        // Recorded BEFORE dispatch on purpose: an endpoint that throws, hangs or
        // kills the client still leaves proof that the request arrived, which is
        // exactly the case that went unrecorded before.
        note(ctx, "GET $uri  [headers: ${msg.headers().names().joinToString(",")}]")

        when {
            query.path() == "/jav_config.ws" -> JavConfigWsEndpoint.handle(ctx, msg, query)
            query.path() == "/client" -> ClientFileEndpoint.handle(ctx, msg, query)
            query.path() == "/ms" -> Js5MsEndpoint.handle(ctx, msg, query)
            else -> {
                note(ctx, "  ... no endpoint for ${query.path()} -> 404")
                ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        note(ctx, "EXCEPTION on the http path: ${cause::class.java.name}: ${cause.message}")
        logger.error(cause) { "Exception handling an HTTP request" }
        if (ctx.channel().isActive) {
            ctx.sendHttpError(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }
}
