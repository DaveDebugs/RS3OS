package com.opennxt.net.http.endpoints

import com.opennxt.model.files.BinaryType
import com.opennxt.model.files.FileChecker
import com.opennxt.net.http.sendHttpError
import com.opennxt.net.http.sendHttpFile
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder

object ClientFileEndpoint {
    private val logger = mu.KotlinLogging.logger { }

    fun handle(ctx: ChannelHandlerContext, msg: FullHttpRequest, query: QueryStringDecoder) {
        // Every parameter is checked BEFORE it is indexed or parsed.
        //
        // `crc` had no presence check at all (NoSuchElementException),
        // `BinaryType.values()[n]` indexed an unvalidated n
        // (ArrayIndexOutOfBoundsException), and both toInt()/toLong() could
        // throw NumberFormatException. All three landed in
        // HttpRequestHandler.exceptionCaught as a 500 plus a connection close.
        //
        // Low severity - the handler does answer and does log - but this is the
        // LAUNCHER path, so a 500 here presents to the operator as "the client
        // won't start", attached to a stack trace naming a Kotlin stdlib
        // function rather than the parameter that was missing or malformed.
        // Answering 400 and naming the parameter is the whole improvement.
        val typeRaw = query.parameters()["binaryType"]?.firstOrNull()
        val filename = query.parameters()["fileName"]?.firstOrNull()
        val crcRaw = query.parameters()["crc"]?.firstOrNull()
        if (typeRaw == null || filename == null || crcRaw == null) {
            logger.warn {
                "clientFile request from ${ctx.channel().remoteAddress()} is missing a parameter " +
                    "(binaryType=$typeRaw fileName=$filename crc=$crcRaw)"
            }
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }

        val typeIndex = typeRaw.toIntOrNull()
        val crc = crcRaw.toLongOrNull()
        if (typeIndex == null || typeIndex !in BinaryType.values().indices || crc == null) {
            logger.warn {
                "clientFile request from ${ctx.channel().remoteAddress()} has a bad parameter " +
                    "(binaryType='$typeRaw' must be 0..${BinaryType.values().size - 1}, crc='$crcRaw' must be numeric)"
            }
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }
        val binaryType = BinaryType.values()[typeIndex]

        val data = FileChecker.getFile("compressed", binaryType, file = filename, crc = crc)
        if (data == null) {
            ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            return
        }

        ctx.sendHttpFile(data, filename)
    }
}