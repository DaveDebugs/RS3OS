package com.opennxt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.api.stat.Stat
import com.opennxt.config.RsaConfig
import com.opennxt.content.impl.Dialogue
import com.opennxt.content.impl.DialogueWiring
import com.opennxt.content.impl.Banks
import com.opennxt.content.impl.BanksWiring
import com.opennxt.content.impl.Doors
import com.opennxt.model.map.LocClipping
import com.opennxt.content.impl.Ladders
import com.opennxt.content.impl.Shops
import com.opennxt.content.impl.Skilling
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.content.impl.Stairs
import com.opennxt.content.impl.Gatherables
import com.opennxt.content.impl.Searchables
import com.opennxt.config.ServerConfig
import com.opennxt.config.TomlConfig
import com.opennxt.filesystem.ChecksumTable
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.filesystem.prefetches.PrefetchTable
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.login.LoginThread
import com.opennxt.model.commands.CommandRepository
import com.opennxt.model.lobby.Lobby
import com.opennxt.model.tick.TickEngine
import com.opennxt.model.world.World
import com.opennxt.net.RSChannelInitializer
import com.opennxt.net.game.protocol.ProtocolInformation
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.http.HttpServer
import com.opennxt.net.proxy.ProxyConfig
import com.opennxt.net.proxy.ProxyConnectionFactory
import com.opennxt.net.proxy.ProxyConnectionHandler
import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.sqlite.RsDatabase
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import mu.KotlinLogging
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.system.exitProcess
import com.opennxt.model.map.CollisionMap

object OpenNXT : CliktCommand(name = "run-server", help = "Launches the OpenNXT server)") {
    val skipHttpFileVerification by option(help = "Skips file verification when http server starts").flag(default = false)
    val enableProxySupport by option(help = "Enables proxy support. Disable this on live or when you won't use it.").flag(
        default = false
    )

    private val logger = KotlinLogging.logger {}

    lateinit var config: ServerConfig
    lateinit var rsaConfig: RsaConfig
    lateinit var proxyConfig: ProxyConfig

    lateinit var http: HttpServer

    lateinit var filesystem: Filesystem
    lateinit var resources: FilesystemResources
    lateinit var prefetches: PrefetchTable
    lateinit var checksumTable: ByteArray
    lateinit var httpChecksumTable: ByteArray

    lateinit var proxyConnectionFactory: ProxyConnectionFactory
    lateinit var proxyConnectionHandler: ProxyConnectionHandler
    lateinit var protocol: ProtocolInformation
    lateinit var tickEngine: TickEngine

    lateinit var world: World
    lateinit var lobby: Lobby

    lateinit var commands: CommandRepository

    private val bootstrap = ServerBootstrap()

    private fun loadConfigurations() {
        logger.info { "Loading configuration files from ${Constants.CONFIG_PATH}" }
        config = TomlConfig.load(Constants.CONFIG_PATH.resolve("server.toml"))

        // Refuse to run on any build but 949, before anything reads config.build.
        // The default used to be 918, so a missing server.toml -- or one that
        // simply lost its `build` key -- brought the server up speaking 918 to a
        // 949 client, silently. Every symptom of that appears somewhere else.
        config.requireSupportedBuild(Constants.CONFIG_PATH.resolve("server.toml").toString())

        // The borrow hatch loads ANOTHER build's protocol tables on purpose. It
        // is a deliberate diagnostic, so it stays allowed -- but it is exactly
        // the mismatch this guard exists to catch, so it announces itself rather
        // than hiding in a line nobody reads.
        System.getProperty("opennxt.prot.borrowBuild")?.let { borrowed ->
            logger.warn { "================= BUILD MISMATCH (REQUESTED) =================" }
            logger.warn { " -Dopennxt.prot.borrowBuild=$borrowed is set." }
            logger.warn { " server.toml says build ${config.build}, but protocol tables" }
            logger.warn { " will be read from build $borrowed instead." }
            logger.warn { " Packets get framed against tables this client never shipped." }
            logger.warn { " Remove the flag for a normal run." }
            logger.warn { "==============================================================" }
        }

        rsaConfig = try {
            TomlConfig.load(RsaConfig.DEFAULT_PATH, mustExist = true)
        } catch (e: FileNotFoundException) {
            logger.info { "Could not find RSA config: $e. Please run `run-tool rsa-key-generator`" }
            exitProcess(1)
        }
        proxyConfig = TomlConfig.load(Constants.CONFIG_PATH.resolve("proxy.toml"))
    }

    fun reloadContent() {
        Stat.reload()

        // Content modules validate every binding against the definition
        // database at registration time, so with no rs3.sqlite there is nothing
        // to bind against and installing would throw; the server without the
        // database keeps its documented behaviour of running with content
        // lookups degraded rather than crashing at boot.
        // `available` only means a database FILE opened - not that it carries
        // definitions. A collision-only rs3.sqlite is a legitimate setup (12 MB
        // of map_square + map_blocked instead of 1.5 GB of everything), and with
        // one of those the old guard let the content modules install against an
        // empty locs table, where Doors.install() hit
        //
        //   IllegalArgumentException: no loc in the definitions declares 'Open'
        //
        // and killed the server during boot. The client then never launched at
        // all, which reads as "the script did nothing" from the outside.
        //
        // So gate on definitions actually being PRESENT, not on a file handle.
        val locCount = RsDatabase.maxId("locs")
        if (RsDatabase.available && locCount > 0) {
            // The loc clip-type index, built HERE and not on the first world tick.
            // It is 137,079 rows and about 30 ms; behind a `by lazy` it landed on
            // whichever tick first had a player in it and pushed a full world tick
            // past its 60 ms budget. Boot is where a one-time 30 ms belongs.
            if (LocClipping.enabled) {
                val warmed = LocClipping.warm()
                logger.info {
                    "loc clipping: warmed $warmed loc clip definitions at boot " +
                        "(walls come from map_loc per square; map_blocked is terrain only)"
                }
            } else {
                logger.warn {
                    "loc clipping is DISABLED (-Dopennxt.experiment.locClipping=false) - walls, fences " +
                        "and gates will not block movement, because map_blocked carries terrain only"
                }
            }
            Doors.install()

            // Bridge plane remapping: reads settings bit 0x2 from the cache to
            // shift plane-1 bridge collision down to plane 0. Without this, the
            // pathfinder sees water where a bridge is.
            com.opennxt.model.map.BridgeFlags.init(filesystem as SqliteFilesystem)
            logger.info { "bridge flags: initialized, loaded squares will be cached on demand" }

            Ladders.install()
            Stairs.install()
            Banks.install()
            com.opennxt.content.impl.Obstacles.install()
            Searchables.install()
            Gatherables.install()
            // Points Banks' wire seam at the live server. A no-op that says so
            // unless -Dopennxt.experiment.banks.ui=true; the bank MODEL and its
            // npc/loc bindings install either way.
            BanksWiring.install()
            Shops.install()
            // Woodcutting and mining. Installed LAST of the six because it is
            // the newest and the only one that awards xp, and CONTAINED, which
            // the other five are not.
            //
            // The containment is not decoration. `locCount > 0` above proves
            // the locs table has rows; it does not prove any row declares
            // 'Chop down', and ContentRegistry.onLocAction throws
            // IllegalArgumentException when nothing declares the action it is
            // given. That is exactly the shape of failure the comment above
            // this block records killing the server at boot once already (an
            // empty locs table and Doors.install()). Doors/Ladders/Stairs are
            // load-bearing enough that dying loudly is arguably right for them;
            // a server that cannot chop trees is still a server, so this one
            // degrades to "no skilling" and says so.
            //
            // SkillingWiring.install() plants the live seams (backpack / level
            // / xp) so nothing in content/ has to know what a WorldPlayer is.
            // See Skilling's class doc for how little of this is grounded and
            // where every invented number is labelled.
            // Talking to an NPC. Contained for the same reason skilling is:
            // ContentRegistry.onNpcAction throws when nothing in the definitions
            // declares the action it is given, and a server that cannot hold a
            // conversation is still a server. It binds 8,772 npc ids on this
            // cache; DialogueWiring.install() plants the sink so nothing in
            // content/ has to know what a WorldPlayer is.
            //
            // This is the first thing on this server that puts IF_SETTEXT
            // (opcode 34) or IF_SETHIDE (124) on the wire. See Dialogue's class
            // doc for why both are on by default and what turns them off.
            try {
                val talkers = Dialogue.install()
                DialogueWiring.install()
                logger.info { "content: dialogue installed across $talkers npc ids" }
            } catch (t: Throwable) {
                logger.error(t) {
                    "content: dialogue FAILED to install - 'Talk to' will report NoHandler and npcs " +
                        "stay silent. Nothing else is affected; every other module bound before this."
                }
            }

            try {
                val skilling = Skilling.install()
                SkillingWiring.install()
                logger.info { "content: skilling installed - $skilling" }
            } catch (t: Throwable) {
                logger.error(t) {
                    "content: skilling FAILED to install - trees and rocks will report NoHandler. " +
                        "Doors, ladders, stairs, banks and shops are unaffected; they bound before this."
                }
            }

 //fishing, firemaking and cooking, built from the reference client play sessions
 //. Each is installed on its own so one failing
            // module cannot take the other two down, exactly as skilling above. Ordering: after
            // SkillingWiring, because Firemaking reads its owner through SkillingWiring.ownerOf.
            try {
                val fishing = com.opennxt.content.impl.Fishing.install()
                com.opennxt.content.impl.FishingWiring.install()
                logger.info { "content: fishing installed - $fishing spot action(s)" }
            } catch (t: Throwable) {
                logger.error(t) { "content: fishing FAILED to install - fishing spots will report NoHandler." }
            }
            try {
                com.opennxt.content.impl.FiremakingWiring.install()
                logger.info { "content: firemaking installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: firemaking FAILED to install - 'Light' on logs stays unimplemented." }
            }
            try {
                val cooking = com.opennxt.content.impl.CookingWiring.install()
                logger.info { "content: cooking installed - $cooking fire loc(s)" }
            } catch (t: Throwable) {
                logger.error(t) { "content: cooking FAILED to install - raw food on a fire will do nothing." }
            }
            try {
                com.opennxt.content.impl.SmithingWiring.install()
                logger.info { "content: smithing installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: smithing FAILED to install - furnaces, forges and anvils will do nothing." }
            }
 //: the two backpack menu rows the operator hit in a live session and could
            // not use - "Bury" (nothing in the tree carried the string) and "Add to tool belt"
            // (menu row 4, which this database has no column for; see ItemActions). Each installs
            // on its own, like the four above, so one failure cannot take the other down.
            try {
                com.opennxt.content.impl.FletchingWiring.install()
                logger.info { "content: fletching installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: fletching FAILED to install - 'Craft' on logs stays unimplemented." }
            }
            try {
                com.opennxt.content.impl.BuryWiring.install()
                logger.info { "content: burying installed" }
            } catch (t: Throwable) {
                logger.error(t) { "content: burying FAILED to install - 'Bury' on bones stays unimplemented." }
            }
            try {
                com.opennxt.content.impl.ToolBeltWiring.install()
                logger.info { "content: tool belt installed" }
                com.opennxt.content.impl.RunToggle.install()
                logger.info { com.opennxt.content.impl.MakeXPanel.describe() }
                logger.info { com.opennxt.content.impl.Lodestones.describe() }
                logger.info { com.opennxt.content.impl.Teleports.describe() }
                logger.info { com.opennxt.content.impl.ToolBeltPanel.describe() }
            } catch (t: Throwable) {
                logger.error(t) { "content: tool belt FAILED to install - 'Add to tool belt' stays unimplemented." }
            }
        } else if (RsDatabase.available) {
            logger.warn {
                "Definition database has no loc definitions (maxId=0) - skipping content modules " +
                    "(doors, ladders, stairs, banks, shops, skilling). Collision and pathfinding are " +
                    "unaffected. This is expected with a collision-only rs3.sqlite."
            }
        }
    }

    override fun run() {
        logger.info { "Starting OpenNXT" }
        loadConfigurations()

        if (enableProxySupport) {
            logger.warn { "---------------- WARNING ----------------" }
            logger.warn { " You are running in proxy-enabled mode." }
            logger.warn { " Disable this in production environments" }
            logger.warn { " or when you are not going to use this." }
            logger.warn { "" }
            logger.warn { " Remove flag '--enable-proxy-support'." }
            logger.warn { " to disable." }
            logger.warn { "---------------- WARNING ----------------" }

            logger.info { "Setting up proxy connection factory" }
            proxyConnectionFactory = ProxyConnectionFactory()
            proxyConnectionHandler = ProxyConnectionHandler()
        }

        val protPath = Constants.PROT_PATH.resolve(config.build.toString())
        // ProtocolInformation.load() handles the missing-directory case itself,
        // including the explicit -Dopennxt.prot.borrowBuild escape hatch. This
        // check ran first and exited before any of that could happen, so the
        // escape hatch was unreachable and the message named neither what data/
        // prot DOES hold nor that an option existed.
        if (!Files.exists(protPath) && System.getProperty("opennxt.prot.borrowBuild") == null) {
            logger.error { "Protocol information not found for build ${config.build}." }
            logger.error { " Looked in: $protPath" }
            logger.error {
                " data/prot holds: " +
                    (Constants.PROT_PATH.toFile().list()?.sorted()?.joinToString(", ") ?: "(unreadable)")
            }
            logger.error {
                " To borrow another build's tables for a loading-only test - enough to reach a" +
                    " login screen, with in-game opcodes WRONG - start with" +
                    " -Dopennxt.prot.borrowBuild=<build>."
            }
            exitProcess(1)
        }

        protocol = ProtocolInformation(protPath)
        protocol.load()

        logger.info { "Setting up HTTP server" }
        http = HttpServer(config)
        http.init(skipHttpFileVerification)
        // NOTE: http.bind() is deliberately NOT here. See the end of this
        // function - the config it serves advertises the game port, so binding
        // it before the game port exists hands clients a dead address.

        logger.info { "Opening filesystem from ${Constants.CACHE_PATH}" }
        filesystem = SqliteFilesystem(Constants.CACHE_PATH)

        // A cache-integrity report, printed BEFORE anything depends on the
        // cache being complete.
        //
        // This exists because a real run degraded from 2 missing indices to 23
        // between two attempts, and the first symptom was a NullPointerException
        // inside skill loading - a stack trace that named neither the cache nor
        // the missing index. An index that the filesystem DISCOVERS but cannot
        // produce a reference table for is the dangerous case: it looks present
        // and behaves absent.
        run {
            val discovered = (0..254).filter { filesystem.exists(it, 0) || runCatching { filesystem.readReferenceTable(it) != null }.getOrDefault(false) }
            val unreadable = (0..254).filter { i ->
                // "discovered" here means the js5-<i>.jcache file exists at all;
                // SqliteFilesystem logs that list at construction. What matters
                // is whether a reference table can actually be produced.
                runCatching { filesystem.readReferenceTable(i) }.getOrNull() == null &&
                    runCatching { filesystem.exists(i, 0) }.getOrDefault(false)
            }
            logger.info { "Cache check: ${discovered.size} indices discovered, ${unreadable.size} unreadable" }
            if (unreadable.isNotEmpty()) {
                logger.warn { "-------------------------------------------------------------" }
                logger.warn { " CACHE INCOMPLETE. These indices exist but have no readable" }
                logger.warn { " reference table, so JS5 cannot serve them: $unreadable" }
                logger.warn { "" }
                logger.warn { " A client asking for one of them gets nothing back and will" }
                logger.warn { " sit retrying. If this list is GROWING between runs, the cache" }
                logger.warn { " is being modified underneath the server - point -Dopennxt.cache" }
                logger.warn { " at a private COPY rather than at a live client's cache." }
                logger.warn { "-------------------------------------------------------------" }
            }
        }

        logger.info { "Generating prefetch table" }
        prefetches = PrefetchTable.of(filesystem)

        logger.info { "Generating & encoding checksum tables" }
        checksumTable = Container.wrap(
            ChecksumTable.create(filesystem, false)
                .encode(rsaConfig.js5.modulus, rsaConfig.js5.exponent)
        ).array()
        httpChecksumTable = Container.wrap(
            ChecksumTable.create(filesystem, true)
                .encode(rsaConfig.js5.modulus, rsaConfig.js5.exponent)
        ).array()

        logger.info { "Setting up filesystem resource manager" }
        resources = FilesystemResources(filesystem, Constants.RESOURCE_PATH)

        logger.info { "Loading Interface Slots" }
        val slotReload = com.opennxt.content.interfaces.InterfaceSlot.reload()
        logger.info { "Loaded ${slotReload.mapped} Interface Slots, ${slotReload.unmapped.size} unmapped" }

        logger.info { "Loading sequences from cache" }
        com.opennxt.model.definitions.SeqDefinitions.load(filesystem)

        logger.info { "Setting up command repository" }
        commands = CommandRepository()

        logger.info { "Starting js5 thread" }
        Js5Thread.start()

        logger.info { "Starting login thread" }
        LoginThread.start()

        logger.info { "Starting tick engine" }
        tickEngine = TickEngine()

        logger.info { "Instantiating game world" }
        world = World()
        // NOT submitted to the tick engine here. It used to be, and that is what
        // threw ConcurrentModificationException out of WorldNpcs.tick() on the
        // 20260817-093031 run: the world ticked every 600 ms while populate()
        // below spent seconds building the npc population. The submit now
        // happens AFTER populate(), so the first tick sees a finished world.
        // WorldNpcs also publishes atomically now, so this ordering is a second
        // line of defence rather than the only one.

        logger.info { "Instantiating lobby" }
        lobby = Lobby()
        tickEngine.submitTickable(lobby)

        if (enableProxySupport) {
            logger.info { "Registering proxy connection handler to tick engine" }
            tickEngine.submitTickable(proxyConnectionHandler)
        }

        logger.info { "Reloading content-related things" }
        reloadContent()

        // Load tile collision up front rather than on first use. It is read from
        // map_blocked in rs3.sqlite - 20,312 baked bitmaps, one bit per tile -
        // and taking that cost at boot means the first player to move does not
        // pay for it mid-tick.
        //
        // Absence is reported, not fatal: without the database the server still
        // runs, it simply cannot answer walkability, and saying so at startup is
        // far cheaper to diagnose than a pathfinder that silently refuses every
        // route later on.
        logger.info { "Loading collision map" }
        if (CollisionMap.available) {
            logger.info { "Collision ready - ${CollisionMap.loadedSquares()} map squares" }
        } else {
            logger.warn { "No collision data (rs3.sqlite absent?) - pathfinding will refuse all routes" }
        }

        // Populate the world's npcs AFTER collision and the spawn layer are
        // loadable, guarded on RsDatabase.available the same way reloadContent
        // guards the content modules: the spawn records and combat definitions
        // both live in rs3.sqlite, so without it there is nothing to populate
        // from and the server keeps its degraded-but-running behaviour.
        if (RsDatabase.available) {
            val populated = world.npcs.populate()
 //: THE FISHING SPOTS ARE SPAWNED HERE, NOT BY populate.
            //
            // `map_keyed` places npc 14907 ("Fishing spot") ZERO times in this cache - asserted by
            // fishing module is dead code. The tiles are not invented: they are the destinations
            // the reference client's own SET_MAP_FLAG named when the operator clicked the spots in
 // (Fishing.SPOT_FLAG_TILES, distinct tiles only).
            //
            // spawnAt() rather than a row inside populate() deliberately: populate() asserts that
            // population is exactly `NpcSpawnData.spawns().size + combatDemoSpawnCount`. Appending
            // here leaves both invariants - and the check that guards them - untouched.
            // `-Dopennxt.fishing.spawn=off` places none.
            if (System.getProperty("opennxt.fishing.spawn") != "off") {
                val tiles = com.opennxt.content.impl.Fishing.SPOT_FLAG_TILES.distinct()
                var spots = 0
                for (tile in tiles) {
                    runCatching { spots += world.npcs.spawnAt(com.opennxt.content.impl.Fishing.CRAYFISH.npcId, tile) }
                        .onFailure { logger.warn(it) { "could not spawn a fishing spot at $tile" } }
                }
                logger.info {
                    "content: $spots fishing spot(s) spawned at ${tiles.joinToString()} - INVENTED PLACEMENT " +
                        "from the reference client's SET_MAP_FLAG destinations; " +
                        "the cache places none. -Dopennxt.fishing.spawn=off disables."
                }
            }
            // The seed files a fight would otherwise reach lazily - animation observations and
            // the drop tables (first kill) - are parsed HERE, at boot, not on the tick thread
            // inside the first fight. A MISSING drops_documented.json fails the boot, while a
            // missing observations file only warns - the optional layer degrades to "no row" by
            // design and says so in its own load log.
            com.opennxt.model.combat.NpcAnimObservations.load()
            com.opennxt.model.drops.DropData.load()
            logger.info {
                "Combat seed layers preloaded: observed anims ${com.opennxt.model.combat.NpcAnimObservations.observedNpcIds().size} ids, " +
                    "drop tables ${com.opennxt.model.drops.DropData.wikiMonsters().size} wiki monsters"
            }
            logger.info {
                // Read the spawn count ONLY when spawns are actually enabled.
                // Evaluating it unconditionally printed "populated with 0 npcs
                // from 1387 cache spawn records" whenever
                // -Dopennxt.experiment.npcs.spawns=false, which reads as a bug in
                // the populate path rather than as the flag doing its job - and
                // it opened the database to say it.
                if (com.opennxt.model.world.WorldNpcs.spawnsEnabled) {
                    "World populated with $populated npcs from " +
                        "${com.opennxt.model.world.NpcSpawnData.spawns().size} cache spawn records " +
                        "(lifepoints provenance: ${world.npcs.lifepointProvenanceBreakdown()}, " +
                        "precedence layers: ${world.npcs.lifepointLayerBreakdown()})"
                } else {
                    "Cache-driven npc spawns are DISABLED " +
                        "(-Dopennxt.experiment.npcs.spawns=false); world has $populated npc(s)"
                }
            }
 // Said OUT LOUD at boot, every boot, because this server's rule is
            // that an invented value cannot pass itself off as measured and a
            // KDoc is invisible at runtime. It is ONE tile, it is the tile the
            // synthetic decoration used to stand on, and it is the only npc a
            // player can reach from the login tile - the nearest CACHE spawn is
            // 42 tiles away, three times NPC_INFO's 14-tile reach.
            world.npcs.combatDemoNpc()?.let { demo ->
                logger.info {
                    "Combat demo npc placed: ${demo.name ?: "npc${demo.gameId}"} (${demo.gameId}) at " +
                        "(${demo.location.x},${demo.location.y},plane ${demo.location.plane}) slot " +
                        "${demo.infoIndex}, lp ${demo.currentLifepoints}/${demo.lifepoints?.value} " +
                        "(${demo.lifepoints?.provenance}), wander " +
                        "${if (com.opennxt.model.world.WorldNpcs.combatDemoWander) "ON" else "OFF"}. " +
                        "Disable with -Dopennxt.experiment.combat.demospawn=off. " +
                        "${com.opennxt.model.world.WorldNpcs.COMBAT_DEMO_PROVENANCE}"
                }
            }

            // The VARIANT layer keeps Provenance.DOCUMENTED by design, so the
            // provenance breakdown above cannot show it and the 280 ids it adds
            // were invisible at runtime. lifepointLayerBreakdown() splits them
            // out; this line says what the layer is and what fired against it.
            logger.info { com.opennxt.model.combat.SeedData.VARIANT_PROVENANCE }

            // Path clipping is ON by default and changes where a click lands.
            // Said out loud so the setting is never inferred from behaviour.
            logger.info { com.opennxt.model.entity.movement.Movement.PROVENANCE }

            // The combat provenance, ONCE, here at boot. Per-event lines carry
            // PlayerCombat.PROVENANCE_SHORT instead: the full string is ~1,500
            // characters, and appending it to every engagement, xp award and
            // retaliation death buried the facts that actually DIFFER between
            // events under byte-identical prose. Printed unconditionally rather
            // than only when combat is enabled, so the invented numbers stay on
            // the record even in a run that never fights.
            com.opennxt.model.combat.PlayerCombat.logProvenanceOnce()
        } else {
            logger.warn { "No rs3.sqlite - world starts with no npcs" }
        }

        // NOW the world is allowed to tick. Both branches above have finished,
        // so the first tick reads a fully built population. This is deliberately
        // the last thing before the network binds: nothing can connect yet, so
        // no client can observe a world that has not ticked.
        tickEngine.submitTickable(world)

        logger.info { "Starting network" }
        bootstrap.group(NioEventLoopGroup())
            .channel(NioServerSocketChannel::class.java)
            .childHandler(RSChannelInitializer())
            .childOption(ChannelOption.SO_REUSEADDR, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)

        logger.info { "Binding game server to 0.0.0.0:${config.ports.game}" }
        // `await()`, not `sync()`. sync() RETHROWS the failure cause rather than
        // returning a failed future, so the branch below was unreachable and
        // this message - the only line that would name the port that could not
        // be bound - never printed. Still fatal, as intended; it now says what
        // failed before exiting, instead of leaving a bare BindException trace.
        val result = bootstrap.bind("0.0.0.0", config.ports.game).await()
        if (!result.isSuccess) {
            logger.error(result.cause()) { "Failed to bind to 0.0.0.0:${config.ports.game}" }
            exitProcess(1)
        }
        logger.info { "Game server bound to 0.0.0.0:${config.ports.game}" }

        // Only NOW serve jav_config.ws.
        //
        // The HTTP server used to bind immediately after its file check, about
        // fifty seconds before the game port on a cold cache. Everything it
        // serves is an advertisement for the game port, so in that window a
        // client could - and did - fetch a perfectly valid config, dial the game
        // port, get ECONNREFUSED, and drop into its 1,000 ms retry loop. The
        // visible symptom on the client side is the screen flickering as it
        // restarts the connection over and over; on the server side it looks
        // like nothing at all, because no game socket was ever accepted.
        //
        // Binding it here means the config is only obtainable once the address
        // in it works. Nothing else depends on the HTTP server being up early;
        // http.init() does the file verification and has already run.
        http.bind()

        // Open the diagnostic file now rather than on the first recorded event.
        // A missing file used to mean either "nothing ever connected" or "the
        // recorder is broken", with nothing to separate them - see
        // DiagnosticLog.arm for what that ambiguity cost.
        DiagnosticLog.arm()
        DiagnosticLog.currentFile()?.let { path ->
            logger.info { "Diagnostic recorder ARMED - writing to $path" }
        }
    }
}


