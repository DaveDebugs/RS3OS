package com.opennxt.net.http.endpoints

import com.opennxt.OpenNXT
import com.opennxt.model.files.BinaryType
import com.opennxt.model.files.ClientConfig
import com.opennxt.model.files.ClientParams
import com.opennxt.model.files.FileChecker
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.sendHttpError
import com.opennxt.net.http.sendHttpText
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder

object JavConfigWsEndpoint {
    private val logger = mu.KotlinLogging.logger { }

    fun handle(ctx: ChannelHandlerContext, msg: FullHttpRequest, query: QueryStringDecoder) {
        // Validate before indexing. `BinaryType.values()[n]` on an unvalidated n
        // threw ArrayIndexOutOfBoundsException (and toInt() threw
        // NumberFormatException), both of which reached the operator as a 500 on
        // the LAUNCHER path - that is, as "the client won't start", attached to
        // a stack trace naming a Kotlin stdlib function instead of the bad
        // parameter.
        val typeRaw = query.parameters()["binaryType"]?.firstOrNull() ?: "2"
        val typeIndex = typeRaw.toIntOrNull()
        if (typeIndex == null || typeIndex !in BinaryType.values().indices) {
            logger.warn {
                "jav_config request from ${ctx.channel().remoteAddress()} has binaryType='$typeRaw'; " +
                    "expected 0..${BinaryType.values().size - 1}"
            }
            ctx.sendHttpError(HttpResponseStatus.BAD_REQUEST)
            return
        }
        val type = BinaryType.values()[typeIndex]

        // Was `?: throw NullPointerException(...)`, i.e. a 500 for a condition
        // the server knows precisely: this build has no staged config of that
        // type. 404 says that; a 500 with an NPE trace does not.
        val config = FileChecker.getConfig("compressed", type)
        if (config == null) {
            logger.warn { "No staged jav_config for binary type $type - answering 404" }
            ctx.sendHttpError(HttpResponseStatus.NOT_FOUND)
            return
        }
        if (!OpenNXT.enableProxySupport) {
            // Overlay the params the CLIENT reads. The staged file carries
            // launcher-shaped keys (codebase, download_name_*) that this client
            // ignores entirely - they appear nowhere in its binary - plus
            // exactly one param, which is not among the six it requires.
            // Serving the staged file alone is what made a 947 client fetch its
            // config and then die on a null dereference. See ClientParams.
            val params = ClientParams.build(OpenNXT.config)
            for ((index, value) in params) config["param=$index"] = value

            // `server_version` is NOT a param - it is a top-level key, and the
            // client turns it into the flag that selects which form of a packet
            // to speak. Roughly fifteen packets have a legacy and a >=949 form,
            // and the client picks between them on this value alone. Absent, it
            // stays 0 and every one of them takes the LEGACY arm.
            //
            // Two consequences are worth naming, because both look like
            // unrelated bugs:
            //
            //   * an interface click leaves the client as a different opcode
            //     than this server registers, so it is dropped in the framer
            //     and panels appear dead;
            //   * UPDATE_INV_FULL is read with a 2-byte object id where this
            //     server writes a 3-byte one, so a backpack holding one Logs
            //     renders as two entirely different items with invented counts.
            //
            // Set here as well as in ClientPatcher so that an install whose
            // staged jav_config.ws predates that fix is still served a correct
            // one, and so the value can never disagree with the build this
            // server is actually speaking.
            config["server_version"] = OpenNXT.config.build.toString()

            DiagnosticLog.http(
                ctx.channel(),
                "serving jav_config for $type: ${config.entries.size} entries, " +
                    "${params.size} generated params (mandatory ${ClientParams.MANDATORY.sorted()}), " +
                    "server_version=${OpenNXT.config.build}"
            )
            // Newline-terminated. `ClientConfig.toString()` sorts its keys and
            // `server_version` sorts after `codebase`, `download_*` and every
            // `param=*`, so on a minimal staged config it is the LAST line. A
            // line-oriented parser that discards an unterminated tail would drop
            // precisely the key this block exists to deliver. Whether the client
            // does that is UNVERIFIED; terminating the body costs one byte and
            // removes the question.
            ctx.sendHttpText((config.toString() + "\n").toByteArray(Charsets.ISO_8859_1))
            return
        }

        val liveConfig = ClientConfig.download("https://world5.runescape.com/jav_config.ws", type)

        var download = 0
        while (config.entries.containsKey("download_name_$download")) {
            liveConfig.entries["download_name_$download"] = config.entries.getValue("download_name_$download")
            liveConfig.entries["download_crc_$download"] = config.entries.getValue("download_crc_$download")
            liveConfig.entries["download_hash_$download"] = config.entries.getValue("download_hash_$download")
            download++
        }

        liveConfig["codebase"] = "http://${OpenNXT.config.hostname}/"

        for (i in 0..liveConfig.highestParam) {
            val value = liveConfig.getParam(i) ?: continue

            if (value.contains("runescape.com") || value.contains("jagex.com")) {
                liveConfig["param=$i"] = OpenNXT.config.hostname
            }
        }

        ctx.sendHttpText(liveConfig.toString().toByteArray(Charsets.ISO_8859_1))
    }
}