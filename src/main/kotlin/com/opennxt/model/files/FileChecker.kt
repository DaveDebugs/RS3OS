package com.opennxt.model.files

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.config.RsaConfig
import com.opennxt.tools.impl.ClientPatcher
import lzma.sdk.lzma.Decoder
import lzma.streams.LzmaInputStream
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.zip.CRC32

object FileChecker {
    private val logger = KotlinLogging.logger { }

    fun latestBuild(): Int {
        var build = -1
        Files.list(Constants.CLIENTS_PATH).forEach {
            try {
                val thisBuild = it.fileName.toString().toInt()
                if (build < thisBuild) build = thisBuild
            } catch (e: NumberFormatException) {
            }
        }
        return build
    }

    fun getFile(
        folder: String = "compressed",
        type: BinaryType = BinaryType.WIN64,
        build: Int = OpenNXT.config.build,
        file: String,
        crc: Long
    ): ByteArray? {
        val path = Constants.CLIENTS_PATH.resolve(build.toString()).resolve(type.name.toLowerCase()).resolve(folder)
            .resolve(file)

        val config = getConfig(folder, type, build) ?: return null
        val info = config.getFiles().firstOrNull { it.name == file } ?: return null
        if (info.crc != crc) return null

        if (!Files.exists(path)) {
            logger.error { "$file not found in $path (it should exist though)" }
            return null
        }

        return Files.readAllBytes(path)
    }

    fun getConfig(
        folder: String = "compressed",
        type: BinaryType = BinaryType.WIN64,
        build: Int = OpenNXT.config.build
    ): ClientConfig? {
        val path = Constants.CLIENTS_PATH.resolve(build.toString()).resolve(type.name.toLowerCase()).resolve(folder)
            .resolve("jav_config.ws")
        if (!Files.exists(path)) {
            logger.error { "jav_config.ws not found in $path (it should exist though)" }
            return null
        }

        return ClientConfig.load(path)
    }

    fun checkFiles(type: String = "compressed", rsaConfig: RsaConfig = OpenNXT.rsaConfig) {
        logger.info { "Checking client files from ${Constants.CLIENTS_PATH}, type '$type'" }
        if (!Files.exists(Constants.CLIENTS_PATH))
            throw FileNotFoundException("${Constants.CLIENTS_PATH} not found. Please `run-tool client-downloader`.")

        // Verify the build this server will actually SERVE, not the highest one
        // that happens to be staged.
        //
        // This called latestBuild(), while getConfig() and getFile() - the
        // functions that answer real HTTP requests - both default to
        // OpenNXT.config.build. The two disagreeing is not hypothetical: with
        // server.toml build = 947 and only data/clients/919 staged, boot
        // verification checked 919, printed "Client files OK for WIN64", bound
        // both ports and looked completely healthy. The first client request
        // then died with
        //
        //     NullPointerException: Can't get config for type WIN64
        //     at JavConfigWsEndpoint.handle(JavConfigWsEndpoint.kt:18)
        //
        // seventeen frames deep in a Netty pipeline, naming neither the build
        // nor the missing directory. A check that passes on files the server
        // will never serve is worse than no check, because it actively asserts
        // that the thing it did not look at is fine.
        if (latestBuild() == -1)
            throw FileNotFoundException("Could not find clients/files. Please run `run-tool client-downloader`.")

        val latest = OpenNXT.config.build
        val buildPath = Constants.CLIENTS_PATH.resolve(latest.toString())
        if (!Files.exists(buildPath)) {
            val staged = Files.list(Constants.CLIENTS_PATH).use { s ->
                s.map { it.fileName.toString() }.filter { it.toIntOrNull() != null }.sorted().toList()
            }
            throw FileNotFoundException(
                "No client files for build $latest (server.toml says build = $latest).\n" +
                    "  Looked in : $buildPath\n" +
                    "  Staged    : ${if (staged.isEmpty()) "(none)" else staged.joinToString(", ")}\n" +
                    "  The HTTP endpoint serves client files for the build named in server.toml,\n" +
                    "  so without this directory the client can never fetch its config. Either\n" +
                    "  copy an existing staging directory to '$latest', or run\n" +
                    "  `run-tool client-downloader`."
            )
        }

        // A partial client set is the normal case for anyone who did not run
        // `client-downloader`: you hold the client you actually own, not all
        // seven binary types. This used to throw on the FIRST absent type, so
        // boot verification was all-or-nothing and --skip-http-file-verification
        // was the only way through - which skipped checking the files that WERE
        // present too.
        //
        // Verification strength is unchanged for anything present: existence,
        // CRC and launcher-key hash must all match. What changed is that absent
        // types are named and reported rather than aborting the run, and a run
        // that verified NOTHING is still a failure - "no files checked" must
        // never read as "files are OK".
        val crc = CRC32()
        val verified = ArrayList<String>()
        val absent = ArrayList<String>()
        BinaryType.values().forEach { binaryType ->
            val typePath = Constants.CLIENTS_PATH.resolve(latest.toString()).resolve(binaryType.name.toLowerCase())
            val basePath = typePath.resolve(type)
            if (!Files.exists(typePath) || !Files.exists(basePath) ||
                !Files.exists(basePath.resolve("jav_config.ws"))
            ) {
                absent += binaryType.name
                return@forEach
            }
            verified += binaryType.name

            val config = ClientConfig.load(basePath.resolve("jav_config.ws"))

            config.getFiles().forEach { downloadInformation ->
                val downloadPath = basePath.resolve(downloadInformation.name)
                if (!Files.exists(downloadPath))
                    throw FileNotFoundException("$downloadPath")

                val decompressed = decompressLZMA(Files.readAllBytes(downloadPath))

                crc.reset()
                crc.update(decompressed)
                if (crc.value != downloadInformation.crc)
                    throw IllegalStateException("CRC mismatch in binary $binaryType file ${downloadInformation.name}")

                val expectedHash = ClientPatcher.generateFileHash(
                    decompressed,
                    rsaConfig.launcher.modulus,
                    rsaConfig.launcher.exponent
                )
                if (expectedHash != downloadInformation.hash)
                    throw IllegalStateException("Hash mismatch in binary $binaryType file ${downloadInformation.name}")
            }
        }

        if (verified.isEmpty())
            throw FileNotFoundException(
                "No binary type under ${Constants.CLIENTS_PATH.resolve(latest.toString())} has $type/jav_config.ws - " +
                        "nothing could be verified. Place a client under <type>/original/ and run `run-tool client-patcher`."
            )

        logger.info(
            "Client files OK for ${verified.joinToString(", ")} (existence, crc and hash all checked)" +
                    if (absent.isEmpty()) "" else " - NOT PRESENT, so NOT verified: ${absent.joinToString(", ")}"
        )
    }

    private fun decompressLZMA(raw: ByteArray): ByteArray =
        LzmaInputStream(ByteArrayInputStream(raw), Decoder()).use { it.readBytes() }
}