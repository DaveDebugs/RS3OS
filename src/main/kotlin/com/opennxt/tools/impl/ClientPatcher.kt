package com.opennxt.tools.impl

import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.common.io.ByteStreams
import com.opennxt.Constants
import com.opennxt.config.RsaConfig
import com.opennxt.config.ServerConfig
import com.opennxt.config.TomlConfig
import com.opennxt.ext.replaceFirst
import com.opennxt.model.files.BinaryType
import com.opennxt.model.files.ClientConfig
import com.opennxt.tools.Tool
import com.opennxt.util.RSAUtil
import com.opennxt.util.Whirlpool
import lzma.sdk.lzma.Encoder
import lzma.streams.LzmaEncoderWrapper
import lzma.streams.LzmaOutputStream
import org.cservenak.streams.Coder
import org.cservenak.streams.CoderOutputStream
import java.io.*
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.CRC32
import kotlin.system.exitProcess


class ClientPatcher :
    Tool("client-patcher", "Patches all clients and configs files. Uses most recent revision by default") {

    private val RUNESCAPE_REGEX = "^https?://[a-z0-9\\-]*\\.?runescape.com(:[0-9]+)?/\u0000"
    private val RUNESCAPE_CONFIG_URL = "http://www.runescape.com/k=5/l=$(Language:0)/jav_config.ws\u0000"
    private val ASCII = Charsets.US_ASCII

    private val PATCHED_REGEX = "^.*"

    private val version by option(help = "The version of the client to patch")
        .int()
        .defaultLazy {
            val path = Constants.CLIENTS_PATH
            if (!Files.exists(path)) return@defaultLazy -1

            var version = -1
            Files.list(path).forEach {
                try {
                    val thisVersion = it.fileName.toString().toInt()
                    if (version < thisVersion) version = thisVersion
                } catch (e: NumberFormatException) {
                }
            }

            version
        }

    var oldJs5: ByteArray? = null
    var oldLogin: ByteArray? = null
    var oldLauncher: ByteArray? = null

    lateinit var rsaConfig: RsaConfig
    lateinit var serverConfig: ServerConfig

    override fun runTool() {
        logger.info { "Patching clients for version $version" }

        val path = Constants.CLIENTS_PATH.resolve(version.toString())
        if (!Files.exists(path)) throw FileNotFoundException("$path: do clients with version $version exist? did you run `run-tool client-downloader` yet?")

        logger.info { "Patching clients in $path" }
        rsaConfig = try {
            TomlConfig.load(RsaConfig.DEFAULT_PATH, mustExist = true)
        } catch (e: FileNotFoundException) {
            logger.info { "Could not find RSA config: $e. Please run `run-tool rsa-key-generator`" }
            exitProcess(1)
        }
        logger.info { "Using RSA config from ${RsaConfig.DEFAULT_PATH}" }

        serverConfig = TomlConfig.load(ServerConfig.DEFAULT_PATH)
        logger.info { "Using server config from ${ServerConfig.DEFAULT_PATH}" }

        if (serverConfig.configUrl.length >= RUNESCAPE_CONFIG_URL.length) {
            logger.error { "Server config URL length is greater than RuneScape config URL" }
            exitProcess(1)
        }

        // TWO FIXES HERE, both invisible to anyone who ran `client-downloader`
        // and fetched the full set on Windows:
        //
        //  1. DIRECTORY CASE. This tool used `type.name` (WIN64) while
        //     FileChecker/JavConfigWsEndpoint resolve `type.name.toLowerCase()`
        //     (win64). On a case-insensitive filesystem they are the same
        //     directory; on Linux the patcher writes files the server can never
        //     find, and the only symptom is a 500 from /jav_config.ws.
        //  2. PARTIAL CLIENT SETS. The loop assumed every BinaryType had been
        //     downloaded and `ClientConfig.load` threw FileNotFoundException on
        //     the first missing one - WINXP - so a user holding only the client
        //     they actually own could never reach their own type. Absent types
        //     are now skipped with a log line naming them, and what IS present
        //     is patched.
        //  3. ONE BAD TYPE NO LONGER KILLS THE REST. Build 949 ships an 82 KB
        //     WIN32 stub with no RSA keys in it, and patchFile used to
        //     exitProcess(1) on that - before WIN64 was ever reached. A type
        //     that cannot be patched is now recorded and stepped over.
        val patched = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        BinaryType.values().forEach { type ->
            val typeDir = Constants.CLIENTS_PATH.resolve(version.toString()).resolve(type.name.toLowerCase())
            val fromDirectory = typeDir.resolve("original")
            if (!Files.exists(fromDirectory.resolve("jav_config.ws"))) {
                skipped += type.name
                return@forEach
            }
            val toDirectory = typeDir.resolve("patched")
            if (!Files.exists(toDirectory)) Files.createDirectories(toDirectory)
            val compressedDirectory = typeDir.resolve("compressed")
            if (!Files.exists(compressedDirectory)) Files.createDirectories(compressedDirectory)

            logger.info { "Patching type $type" }
            try {
                val config = ClientConfig.load(fromDirectory.resolve("jav_config.ws"))

                config.getFiles().forEach { file ->
                    logger.info { "Patching file ${file.name}" }
                    val isClient = file.name.contains("rs2client")
                    Files.deleteIfExists(toDirectory.resolve(file.name))

                    patchFile(type, fromDirectory.resolve(file.name), toDirectory.resolve(file.name), isClient)
                }

                logger.info { "Patching client config" }
                patchConfig(type, config, toDirectory)

                Files.deleteIfExists(compressedDirectory.resolve("jav_config.ws"))
                Files.copy(toDirectory.resolve("jav_config.ws"), compressedDirectory.resolve("jav_config.ws"))
                config.getFiles().forEach { file ->
                    logger.info { "Compressing ${file.name}" }
                    val compressed = RSLZMAOutputStream.compress(Files.readAllBytes(toDirectory.resolve(file.name)))
                    Files.write(compressedDirectory.resolve(file.name), compressed)
                }
                // Recorded only once the type is completely through, so a
                // half-patched directory is never reported as patched.
                patched += type.name
            } catch (e: Exception) {
                failed[type.name] = e.message ?: e.toString()
                logger.warn { "Could not patch $type: ${e.message} - continuing with the remaining types" }
            }
        }

        logger.info { "Patched binary types: ${if (patched.isEmpty()) "NONE" else patched.joinToString(", ")}" }
        if (skipped.isNotEmpty())
            logger.warn { "Skipped (no original/jav_config.ws present, nothing to patch): ${skipped.joinToString(", ")}" }
        failed.forEach { (type, why) -> logger.warn { "FAILED to patch $type: $why" } }
        if (patched.isEmpty()) {
            logger.error {
                "Nothing was patched" +
                    (if (failed.isEmpty()) " - place a client and its jav_config.ws under <build>/<type>/original/ first"
                     else " - every type present failed: ${failed.keys.joinToString(", ")}")
            }
            exitProcess(1)
        }

        logger.info { "Patching launchers from ${Constants.LAUNCHERS_PATH}" }
        if (!Files.exists(Constants.LAUNCHERS_PATH) || Files.list(Constants.LAUNCHERS_PATH).count() == 0L) {
            logger.warn { "No launchers found in ${Constants.LAUNCHERS_PATH}" }
            logger.warn { "Unable to patch launchers" }
            logger.warn { "Please place the un-patched Windows launcher in ${Constants.LAUNCHERS_PATH.resolve("win").resolve(
                "original.exe"
            )}" }
            return
        }

        Files.list(Constants.LAUNCHERS_PATH).forEach { type ->
            logger.info { "Patching launcher ${type.fileName}" }

            val from = type.resolve("original.exe")
            val to = type.resolve("patched.exe")

            if (!Files.exists(from))
                throw FileNotFoundException("original (un-patched) launcher at $from")

            logger.info { "Patching launcher $from to $to" }
            patchLauncher(from, to)
        }
    }

    private fun patchConfig(type: BinaryType, config: ClientConfig, filesPath: Path) {
        config["codebase"] = "http://${serverConfig.hostname}/"

        // The client reads this key once at startup and uses it to choose
        // between a legacy and a >=949 form for roughly fifteen packets. With
        // no `server_version` it stays legacy for the whole session: interface
        // clicks go out as opcodes this server does not register, and inventory
        // object ids are read two bytes wide where the server writes three.
        //
        // An `original/jav_config.ws` staged by hand usually carries no launcher
        // keys at all, so this is written rather than copied across. `version`
        // is the client build directory being patched, which is by construction
        // the build the client is.
        config["server_version"] = version.toString()

        for (i in 0..config.highestParam) {
            val value = config.getParam(i) ?: continue

            if (value.contains("runescape.com") || value.contains("jagex.com")) {
                config["param=$i"] = serverConfig.hostname
            }
        }

        config.getFiles().forEach { file ->
            val data = Files.readAllBytes(filesPath.resolve(file.name))
            val id = file.id

            config["download_hash_$id"] = generateFileHash(data, rsaConfig.launcher.modulus, rsaConfig.launcher.exponent)
            config["download_crc_$id"] = crc32(data).toString()
        }

        ClientConfig.save(config, filesPath.resolve("jav_config.ws"))
    }

    private fun patchFile(type: BinaryType, from: Path, to: Path, isClient: Boolean) {
        val raw = Files.readAllBytes(from)

        // nothing to patch in non-client files
        if (!isClient) {
            Files.write(to, raw)
            return
        }

        // These two used to call exitProcess(1). That killed the whole run on
        // the FIRST binary type with no findable key - and WIN32's
        // "rs2client.exe" is an 82 KB stub with no keys in it at all, sorted
        // before WIN64 by BinaryType.values(). So patching build 949 aborted on
        // a stub nobody wants, never reached the only client that matters, and
        // said "can't patch!" in a way that read like the download had failed.
        //
        // Throwing instead lets runTool() record that type as failed and carry
        // on. The run still fails if nothing usable came out of it: "no client
        // was patched" must never exit 0.
        if (oldJs5 == null) {
            val key = RSAUtil.findRSAKey(raw, 4096)
                ?: throw IllegalStateException("no 4096-bit js5 RSA key found in $from")
            oldJs5 = key.toString(16).toByteArray(ASCII)
            logger.info { "Jagex public js5 key: ${key.toString(16)}" }
        }

        if (oldLogin == null) {
            val key = RSAUtil.findRSAKey(raw, 1024)
                ?: throw IllegalStateException("no 1024-bit login RSA key found in $from")
            oldLogin = key.toString(16).toByteArray(ASCII)
            logger.info { "Jagex public login key: ${key.toString(16)}" }
        }

        if (!raw.replaceFirst(oldJs5!!, rsaConfig.js5.modulus.toString(16).toByteArray()))
            throw RuntimeException("Failed to patch js5 key in ${type.name}")

        if (!raw.replaceFirst(oldLogin!!, rsaConfig.login.modulus.toString(16).toByteArray()))
            throw RuntimeException("Failed to patch login key in ${type.name}")

        Files.write(to, raw)
    }

    private fun patchLauncher(from: Path, to: Path) {
        val raw = Files.readAllBytes(from)

        if (oldLauncher == null) {
            val key = RSAUtil.findRSAKey(raw, 4096)
            if (key == null) {
                logger.error { "Failed to find launcher RSA key in $from - can't patch launcher" }
                exitProcess(1)
            }
            oldLauncher = key.toString(16).toByteArray(ASCII)
        }

        if (!raw.replaceFirst(oldLauncher!!, rsaConfig.launcher.modulus.toString(16).toByteArray()))
            throw RuntimeException("Failed to patch launcher rsa key in $from")

        if (!raw.replaceFirst(RUNESCAPE_REGEX.toByteArray(ASCII), "${PATCHED_REGEX}\u0000".toByteArray(ASCII)))
            throw RuntimeException("Failed to patch launcher regex in $from")

        if (!raw.replaceFirst(
                RUNESCAPE_CONFIG_URL.toByteArray(ASCII),
                "${serverConfig.configUrl}\u0000".toByteArray(ASCII)
            )
        )
            throw RuntimeException("Failed to patch launcher config url in $from")

        Files.write(to, raw)
    }

    private fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data, 0, data.size)
        return crc.value
    }

    companion object {
        fun generateFileHash(data: ByteArray, modulus: BigInteger, exponent: BigInteger): String {
            val hash = ByteArray(65)
            hash[0] = 10
            Whirlpool.getHash(data, 0, data.size).copyInto(hash, 1)

            val rsa = BigInteger(hash).modPow(exponent, modulus).toByteArray()

            return Base64.getEncoder().encodeToString(rsa)
                .replace("\\+".toRegex(), "\\*")
                .replace("/".toRegex(), "\\-")
                .replace("=".toRegex(), "")
        }
    }

    class RSLZMAEncoderWrapper(
        private val encoder: Encoder,
        private val length: Int
    ) : Coder {
        override fun code(`in`: InputStream, out: OutputStream) {
            encoder.writeCoderProperties(out)
            for (i in 0..7) {
                out.write((length.toLong() ushr 8 * i).toInt() and 0xFF)
            }
            encoder.code(`in`, out, -1, -1, null)
        }
    }

    class RSLZMAOutputStream : CoderOutputStream {
        constructor(out: OutputStream, lzmaEncoder: Encoder, length: Int) : super(
            out,
            RSLZMAEncoderWrapper(lzmaEncoder, length)
        )

        constructor(out: OutputStream, wrapper: LzmaEncoderWrapper, length: Int) : super(out, wrapper)

        companion object {
            fun create(out: OutputStream, encoder: Encoder, length: Int): RSLZMAOutputStream {
                encoder.setDictionarySize(1 shl 23)
                encoder.setEndMarkerMode(true)
                encoder.setMatchFinder(1)
                encoder.setNumFastBytes(0x20)
                return RSLZMAOutputStream(out, encoder, length)
            }

            fun compress(data: ByteArray): ByteArray {
                val baos = ByteArrayOutputStream()
                val out = create(baos, Encoder(), data.size)
                out.write(data)
                out.flush()
                out.close()
                return baos.toByteArray()
            }
        }
    }

}