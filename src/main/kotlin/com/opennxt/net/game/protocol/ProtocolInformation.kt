package com.opennxt.net.game.protocol

import com.opennxt.OpenNXT
import com.opennxt.config.TomlConfig
import com.opennxt.net.game.PacketRegistry
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class ProtocolInformation(val path: Path) {
    private val logger = KotlinLogging.logger {  }
    lateinit var clientProtSizes: Opcode2SizeConfig
    lateinit var serverProtSizes: Opcode2SizeConfig
    lateinit var clientProtNames: Name2OpcodeConfig
    lateinit var serverProtNames: Name2OpcodeConfig

    /**
     * The build whose protocol tables are actually in use.
     *
     * Normally [OpenNXT.config.build]. It differs only when the operator has
     * explicitly asked to borrow another build's tables (see [load]), and every
     * consumer that reports coverage should say which build it read.
     */
    var effectiveBuild: Int = -1
        private set

    fun load() {
        logger.info { "Loading protocol information from $path" }

        // Every table below is loaded with saveAfterLoad = false, and that is
        // load-bearing rather than tidiness.
        //
        // TomlConfig.load defaults to writing the file back after reading it.
        // For runtime config that is harmless; for these four it is not. They
        // are RECOVERED DATA - the 949 tables took a full session of binary
        // analysis to produce - and a write-back round trip does two damaging
        // things. It strips comments, which is how a serverProtNames.toml
        // shipped with 1,161 bytes of provenance came back as 45 bytes of bare
        // values after one run. And toml4j serialises an EMPTY table as a
        // ZERO-BYTE file: data/prot/949/clientProtNames.toml is 0 bytes right
        // now for exactly that reason, which makes "never recovered" and
        // "destroyed on boot" indistinguishable after the fact.
        //
        // Nothing in the server has any business editing these, so it doesn't.

        // Moving the client to 949 left data/prot/ holding 918, 919 and the
        // partially-recovered 947 and nothing for 949, so the server could not
        // boot at all - which blocks even testing whether the CS2 crash is gone,
        // and that crash happens during LOADING, long before a single game
        // packet is exchanged.
        //
        // Hence an explicit borrow. Three properties matter, because this
        // codebase has already produced two build split-brain bugs (FileChecker
        // verifying a build the server never serves; PacketRegistry declaring
        // fields from a build whose opcodes it was not using) and both were
        // silent:
        //
        //   1. It is OPT-IN. Nothing falls back by accident; without the flag
        //      the server still refuses to start, with the same message as
        //      before.
        //   2. It is LOUD, on every boot, and names both builds.
        //   3. It does NOT copy files into data/prot/949. Borrowed tables that
        //      sit on disk under the wrong build number stop looking borrowed
        //      about ten minutes later.
        //
        //      -Dopennxt.prot.borrowBuild=947
        //
        // What this is good for and what it is not: the login handshake and JS5
        // do not use these tables, so a borrowed table is enough to reach a
        // login screen. The in-game opcodes will be WRONG - 947's numbering is
        // no more valid for 949 than 947's clientscript numbering was - so
        // anything past login is not to be trusted until the real tables are
        // recovered.
        val configured = OpenNXT.config.build
        val borrow = System.getProperty("opennxt.prot.borrowBuild")?.toIntOrNull()
        var readFrom = path
        effectiveBuild = configured

        if (!Files.exists(path.resolve("serverProtNames.toml")) && borrow != null) {
            val alt = path.resolveSibling(borrow.toString())
            if (!Files.exists(alt.resolve("serverProtNames.toml"))) {
                logger.error { "-Dopennxt.prot.borrowBuild=$borrow, but $alt has no serverProtNames.toml" }
                exitProcess(1)
            }
            readFrom = alt
            effectiveBuild = borrow
            repeat(3) {
                logger.warn {
                    "BORROWING build $borrow's protocol tables for build $configured. " +
                        "In-game packet opcodes WILL be wrong; this is enough to reach a login " +
                        "screen and nothing more. Recover data/prot/$configured to remove this."
                }
            }
        }

        fun read(file: String): Any = try {
            TomlConfig.load<Opcode2SizeConfig>(readFrom.resolve(file), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "Protocol information not found for build $configured." }
            logger.error { " Looked in: ${readFrom.resolve(file)}" }
            if (borrow == null)
                logger.error {
                    " data/prot/ holds: " +
                        (path.parent?.toFile()?.list()?.sorted()?.joinToString(", ") ?: "(unreadable)") +
                        ". To borrow another build's tables for a loading-only test, start with " +
                        "-Dopennxt.prot.borrowBuild=<build>."
                }
            exitProcess(1)
        }

        @Suppress("UNCHECKED_CAST")
        run {
            clientProtSizes = read("clientProtSizes.toml") as Opcode2SizeConfig
            serverProtSizes = read("serverProtSizes.toml") as Opcode2SizeConfig
        }
        clientProtNames = try {
            TomlConfig.load(readFrom.resolve("clientProtNames.toml"), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "clientProtNames.toml missing at ${readFrom.resolve("clientProtNames.toml")}" }
            exitProcess(1)
        }
        serverProtNames = try {
            TomlConfig.load(readFrom.resolve("serverProtNames.toml"), saveAfterLoad = false, mustExist = true)
        } catch (e: Exception) {
            logger.error(e) { "serverProtNames.toml missing at ${readFrom.resolve("serverProtNames.toml")}" }
            exitProcess(1)
        }

        logger.info { "Protocol tables in use: build $effectiveBuild (server build $configured)" }
        refreshPacketCodecs()
    }

    fun refreshPacketCodecs() {
        logger.info { "Refreshing packet codecs" }

        if (!Files.exists(path.resolve("clientProt")))
            Files.createDirectories(path.resolve("clientProt"))

        if (!Files.exists(path.resolve("serverProt")))
            Files.createDirectories(path.resolve("serverProt"))

        PacketRegistry.reload()
    }
}