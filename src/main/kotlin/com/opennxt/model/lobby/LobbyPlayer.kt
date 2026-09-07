package com.opennxt.model.lobby

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.content.impl.Banks
import com.opennxt.impl.stat.PlayerStatContainer
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.InterfaceHash
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.worldlist.WorldList
import com.opennxt.net.ConnectedClient
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.clientprot.EventAppletFocus
import com.opennxt.net.game.clientprot.EventCameraPosition
import com.opennxt.net.game.clientprot.EventMouseClick
import com.opennxt.net.game.clientprot.ClientCheat
import com.opennxt.net.game.clientprot.ChatSetMode
import com.opennxt.net.game.clientprot.MessagePrivate
import com.opennxt.net.game.clientprot.ObservedClientPacket
import com.opennxt.net.game.clientprot.WindowStatus
import com.opennxt.net.game.clientprot.WorldlistFetch
import com.opennxt.net.game.handlers.EventAppletFocusHandler
import com.opennxt.net.game.handlers.EventCameraPositionHandler
import com.opennxt.net.game.handlers.EventMouseClickHandler
import com.opennxt.net.game.handlers.ClientCheatHandler
import com.opennxt.net.game.handlers.ChatSetModeHandler
import com.opennxt.net.game.handlers.MessagePrivateHandler
import com.opennxt.net.game.handlers.NoTimeoutHandler
import com.opennxt.net.game.handlers.ObservedClientPacketHandler
import com.opennxt.net.game.handlers.WindowStatusHandler
import com.opennxt.net.game.handlers.WorldlistFetchHandler
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.net.game.serverprot.*
import com.opennxt.net.game.serverprot.ifaces.IfOpenSub
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import com.opennxt.net.game.serverprot.variables.ResetClientVarcache
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import com.opennxt.net.game.pipeline.InboundCensus
import mu.KotlinLogging
import kotlin.reflect.KClass

/**
 * @param loadedSave the account's persisted state, when the caller already has
 *   it. Mirrors [com.opennxt.model.world.WorldPlayer]'s parameter of the same
 *   name and exists for the same reason: it is what lets a check drive this
 *   class against a scratch database instead of the live one. Omitted, it is
 *   loaded from [AccountStore.instance] exactly as before.
 */
class LobbyPlayer(
    client: ConnectedClient,
    name: String,
    loadedSave: PlayerSave? = null
) : BasePlayer(client, name) {

    companion object {
        /**
         * Number of varp packets [DefaultVariables.sendDefaultVarps]
         * replays: 833 VARP_SMALL + 552 VARP_LARGE, counted from its source.
         */
        const val REPLAYED_LOGIN_VARP_COUNT = 1385
    }

    private val handlers =
        Object2ObjectOpenHashMap<KClass<out GamePacket>, GamePacketHandler<in BasePlayer, out GamePacket>>()
    private val logger = KotlinLogging.logger { }

    /**
     * The player's persisted state, loaded through [AccountStore] by the
     * username this connection authenticated as. A missing save is
     * [PlayerSave.fromNew] - the same level-1 fresh-account state the old
     * hardcoded defaults produced, but flowing through the real xp curve,
     * and persisted for the first time when the player leaves the lobby
     * (see [Lobby.tick]'s cull).
     */
    val save: PlayerSave = loadedSave ?: AccountStore.instance.loadSave(name) ?: PlayerSave.fromNew(name)

    override val interfaces: InterfaceManager = InterfaceManager(this)

    /** Stats seeded from the save's xp; levels derived through the verified curve. */
    override val stats: StatContainer = PlayerStatContainer(this, save.xp)

    init {
        // The saved bank goes back into the live bank HERE - at construction,
        // before anything can read or write it, and before [toSave] can be
        // reached by the leave cull. Skipping this would not merely lose a
        // login: toSave below sessions the LIVE bank, so a player whose bank
        // was never restored would leave and store an EMPTY one over their real
        // save. Restore-on-login is what makes observation-on-leave safe.
        // [Banks.restoreBank] owns the relogin ordering rule.
        Banks.restoreBank(name, save.bankItems())
    }

    /**
     * Current state as a persistable save: xp read back from the live stat
     * container (the stored truth is xp, never levels); the BANK read back from
     * the live [Bank] the content layer hands this account (same identity the
     * [Banks] supplier keys on - the account name); position, backpack and varp
     * overrides carried unchanged from the loaded [save], which the lobby does
     * not modify.
     */
    fun toSave(): PlayerSave {
        val xp = LinkedHashMap<Stat, Double>()
        Stat.values().forEach { stat -> xp[stat] = stats.get(stat).experience }
        return save.copy(xp = xp, bank = PlayerSave.bankContents(Banks.bankForAccount(name)))
    }

    val worldList = WorldList(WorldList.demoEntries())

    init {
        handlers[NoTimeout::class] = NoTimeoutHandler
        handlers[ClientCheat::class] = ClientCheatHandler
        // WORLDLIST_FETCH. The cast this line used to carry is gone because the
        // handler is now typed on BasePlayer rather than on LobbyPlayer: the
        // WORLD stage answers this packet too, and it was
        // being dropped there. The lobby's own long-lived [worldList] is still
        // what serves a lobby request, so nothing about this path changed.
        handlers[WorldlistFetch::class] = WorldlistFetchHandler

        // The client reports its window before it has a world, so the lobby
        // needs the same handler the world uses - the fields it writes live on
        // BasePlayer for that reason.
        handlers[WindowStatus::class] = WindowStatusHandler

        // VARC_TRANSMIT (949 opcode 105) - the client's own interface state. Bound in the LOBBY
        // as well as the world for the same reason WindowStatus is: the client starts pushing it
        // as soon as it has a UI, and the protocol's first four transmits land at 45.2-48.2s,
        // i.e. across the world handover. See VarcTransmitHandler.
        handlers[com.opennxt.net.game.clientprot.VarcTransmit::class] =
            com.opennxt.net.game.handlers.VarcTransmitHandler

        // EVENT_MOUSE_CLICK (949 opcode 2), for the same REGRESSION-GUARD
        // reason the focus/camera pair below is here: naming an opcode takes it
        // off ObservedClientPacket.OPCODES, which silently removes the rate
        // limiting the lobby was getting from ObservedClientPacketHandler. The
        // lobby is where the operator reads the login diagnostics, and a
        // pointer report is not rare there.
        handlers[EventMouseClick::class] = EventMouseClickHandler

        // ------------------------------------------------------------------
        // IF_BUTTON1..10 - EVERY BUTTON ON THE LOBBY SCREEN.
        //
        // These were registered on WorldPlayer and NOT here, so a click in the
        // lobby framed, decoded, reached handleIncomingPackets, matched nothing
        // and was dropped. The comment on the noHandler branch below already
 // recorded the session hitting this for real on
        // IF_BUTTON1(interface=906) and IF_BUTTON1(interface=907); the
 // session hit it again on IF_BUTTON1(interface=906,
        // component=81). 906 is the lobby top interface THIS CLASS OPENS ITSELF
        // (`interfaces.openTop(id = 906)` in [added]), so the one interface the
        // lobby is guaranteed to put on screen was the one whose clicks went
        // nowhere.
        //
        // Same nine-opcodes-one-handler shape as WorldPlayer: the packets differ
        // only in which menu row was chosen and each carries that itself. The
        // handler is typed on BasePlayer precisely so both sides of the login
        // boundary can share it, so no cast is needed here.
        //
        // This makes the clicks ARRIVE and be logged. It does not make a lobby
        // button DO anything - nothing answers them yet - but a dropped frame
        // and an unanswered one look identical from the outside, and only one of
        // the two can be worked on.
        handlers[com.opennxt.net.game.clientprot.IfButton1::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton2::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton3::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton4::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton5::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton6::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton7::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton8::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton9::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler
        handlers[com.opennxt.net.game.clientprot.IfButton10::class] =
            com.opennxt.net.game.handlers.IfButtonNHandler

        // Same reasoning for the unidentified opcodes: opcode 105 was observed
        // once "at login", and without an entry here it would land on the
        // unrate-limited "TODO: Handle incoming" line.
        handlers[ObservedClientPacket::class] = ObservedClientPacketHandler

 //: every GENERATED client packet class (OPLOCT, IF_BUTTONT,
        // IF_BUTTOND, the T-family, SEND_PING_REPLY...) logs its decoded fields
        // until a real handler claims it. installClientHandlers never overrides
        // an entry that is already in this map.
        com.opennxt.net.game.GeneratedRegistrations.installClientHandlers(handlers)

        // Focus (135) and camera (12) for the same reason, and this pair is a
        // REGRESSION GUARD rather than a nicety. Both used to be unnamed, so
        // they landed on ObservedClientPacketHandler above and were rate
        // limited. Naming them - which is a win - quietly removed that limiting
        // here, because the lobby keeps its own handler map and only the world's
        // was updated. Camera events are built in the per-frame event pump, so
        // the failure mode is an unbounded "TODO: Handle incoming" line per
        // frame in the lobby, which is where the operator reads the login
        // diagnostics.
        //
        // Honest caveat: this rate is INFERRED from where the builder sits, not
        // counted. It has never been measured before the world stage.
        handlers[EventAppletFocus::class] = EventAppletFocusHandler
        handlers[EventCameraPosition::class] = EventCameraPositionHandler

        // CHAT_SETMODE (21) and MESSAGE_PRIVATE (71) - the two chat packets that
        // are typed on BasePlayer, registered here for the SAME regression reason
        // as the pair above: naming an opcode removes it from the observed set,
        // and an opcode named in the world but absent from this map lands on the
        // unrate-limited "TODO: Handle incoming" line below, in the stage where
        // the operator reads the login diagnostics.
        //
        // MESSAGE_PUBLIC (35) is deliberately NOT here. Its handler needs a
        // viewport to decide who can see the speaker, and a lobby player has no
        // entity and no viewport; registering it would mean inventing a lobby
        // notion of "who can see you". A public line typed in the lobby therefore
        // still logs "TODO", which is the honest outcome.
        //
        // Whether the client sends any of the three before the world stage has
        // NEVER BEEN - data/diag/ holds no frame of 21, 35 or 71 at all.
        handlers[ChatSetMode::class] = ChatSetModeHandler
        handlers[MessagePrivate::class] = MessagePrivateHandler
    }

    fun handleIncomingPackets() {
        val queue = client.incomingQueue
 // CAPPED. This was `while (true) { queue.poll ?: return }` - an unbounded
        // loop running inside World.tick, fed by Netty's event loop with no setAutoRead(false)
        // anywhere, so one client sending flat out stretched a tick for the whole world.
        // See [com.opennxt.net.InboundDrainLimit] for where 128 comes from (a real client's worst
        // measured tick, across six the protocol, is 37) and for why the surplus is KEPT
        // rather than dropped.
        var handled = 0
        while (handled < com.opennxt.net.InboundDrainLimit.MAX_PER_TICK) {
            val packet = queue.poll() ?: return
            handled++

            val handler = handlers[packet::class] as? GamePacketHandler<in BasePlayer, GamePacket>
            if (handler != null) {
                handler.handle(this, packet)
            } else {
 //. This was `logger.info { "TODO: Handle incoming
                // $packet" }` - the identical line that was replaced on the
                // WorldPlayer side, left behind here because LobbyPlayer
                // belonged to a front that never ran (it died on an API
                // 529 mid-audit).
                //
 // It is not hypothetical: the operator's session
                // fired it for real, on IF_BUTTON1(interface=906) and
                // IF_BUTTON1(interface=907). Those were the ONLY inbound button
                // frames in the whole session, which made them the most
                // interesting lines in a 1 MB log - and they were phrased as a
                // developer's note, logged at INFO, ungreppable, and unbounded
                // at one line per frame for the life of the connection.
                //
                // InboundCensus.noHandler names the class, says out loud that
                // the frame was RECEIVED and then dropped, and rate limits per
                // class. Same treatment both sides of the login boundary.
                InboundCensus.noHandler(packet)
            }
        }
        // Reached only by exhausting the cap - the `poll() ?: return` above is the normal exit.
        // Anything still queued runs next tick.
        val remaining = queue.size
        if (remaining > 0) com.opennxt.net.InboundDrainLimit.reportBacklog(name, handled, remaining)
    }

    fun added() {
        // stats.init() USED to be here, and it is what killed every lobby
        // session for six runs straight.
        //
        // It pushes 28 UPDATE_STATs, one per skill. Build 949's handler for
        // opcode 184 indexes an array of 0x68-byte per-skill
        // records and calls straight into, which searches a
        // vector inside that record. In the LOBBY those records do not exist
        // yet - the container has a NULL begin pointer with a stale non-null
        // end, so the element count computes to ~5.8e11 and the first compare
        // dereferences address 0. Access violation, process gone, and from
        // the server all you see is `Connection reset` about six seconds
        // after login, which reads exactly like a timeout and is not one.
        //
        // The client fails here with an exception
        // record = read of NULL at rip, with sitting
        // on the stack - the instruction after the at
        //, inside the opcode 184 handler. Of all 230 build-949
        // ServerProt handlers only nine can reach that helper, and 184 is the
        // only one this server has ever sent.
        //
        // CORRECTION (supersedes the paragraph above and the two conclusions in
        // it). The crash is real and the attribution to opcode 184 is right, but
        // "those records are per-skill" and "UPDATE_STAT is simply a GAME-stage
        // build at all: its container is capped at FIVE 0x68-byte records
        //, it is empty in the world stage too,
        // and 28 stat ids into it read uninitialised heap - which is exactly the
        // NULL begin with the stale end. The real UPDATE_STAT is opcode 4
        // writing 24-byte records at [[client+0x198e0]+0x7618]+0x10. See
        // data/prot/949/serverProt/UPDATE_STAT.txt for the evidence and the
        // controls, and the retraction block in serverProtNames.toml.
        //
        // The container is still fully seeded from save.xp by the constructor
        // above, so nothing is lost: only the wire push moves. It stays withheld
        // here (and gated at PlayerStatContainer.refresh) until one run proves
        // the client has built that record vector by the time the burst lands.
        client.write(ResetClientVarcache)

        // ---------------------------------------------------------------
        // TWO OPT-IN EXPERIMENTS, both aimed at one measured fact:
        //
        //   interfaces only (22 packets)  -> the 949 client RENDERS the lobby
        //                                    and survives 50s+
        //   + 1,385 varps (1,435 packets) -> it RESETS 5.4s after the burst
        //                                    and never reaches the game stage
        //
        // The varps themselves have been cleared of the obvious charges:
        // every id exists in 949's 13,267 varplayer definitions, no string /
        // long / coordfine varps, no out-of-range VarpSmall values, and the
        // varp store path is byte-identical between 947 (where this observation
        //
        //   -Dopennxt.experiment.interfacesFirst=true
        //     Open the lobby interfaces BEFORE replaying the varps.
        //     Today the order is varps-then-interfaces, so all 1,385 land
        //     while NO interface exists - packet #1414 of 1435 is IF_OPENTOP.
        //     A varp write can drive interface scripts, and 5.4 seconds is
        //     about 9 game ticks, which is the shape of something failing
        //     downstream rather than at parse time.
        //
        //   -Dopennxt.experiment.varpLimit=N
        //     Replay only the first N varps. Halving N across runs bisects
        //     to a minimal triggering subset; N=0 sends none.
        // ---------------------------------------------------------------
        val interfacesFirst = System.getProperty("opennxt.experiment.interfacesFirst")?.toBoolean() ?: false
        val varpLimit = System.getProperty("opennxt.experiment.varpLimit")?.toIntOrNull()

        // Spacing the replay out turns an 11-run bisection into ONE run.
        //
        // The failure is: interfaces alone -> the lobby renders and the client
        // lives 50s; add the 1,385 varps -> it resets ~6s later having never
        // sent a single packet of its own. Ordering has now been tested and
        // makes no difference, so the question is WHICH varp, or whether it is
        // the rate at all - 1,385 packets land in 143ms today.
        //
        // With a delay, whatever the client chokes on is simply the last SEND
        // line before the reset. If it instead survives all 1,385 slowly, the
        // burst rate is the problem and no individual varp is.
        //
        //     -Dopennxt.experiment.varpDelayMs=10
        //
        // Runs on its own thread: sleeping here would be sleeping on the tick
        // engine, and a 14-second stall of the world to debug the lobby would
        // introduce a second bug to chase.
        val varpDelay = System.getProperty("opennxt.experiment.varpDelayMs")?.toLongOrNull() ?: 0L

        // Hold the varps until the client has sent us anything at all.
        //
        //     -Dopennxt.experiment.varpsAfterReady=true
        //
        // The timing says this is the shape of the problem. The client answers
        // 0.45s after login when it receives only interfaces, and never answers
        // at all when the varp replay is running - dying on a ~6s deadline that
        // does not move whether it got 516 varps or 1,385. So the replay is
        // occupying whatever the client needs to do before it can speak.
        //
        // If it speaks and then takes the varps, this is the fix and the varps
        // were never malformed. If it still never speaks, the replay is not
        // what is blocking it and the ~6s timeout has another cause entirely.
        val varpsAfterReady = System.getProperty("opennxt.experiment.varpsAfterReady")?.toBoolean() ?: false

        fun replayVarps() {
            // A REPLAYED LOGIN, not understood state: sendDefaultVarps is a
            // varp dump recorded from a real session. It is what makes a real
            // client render the lobby, and none of its 1,385 varps have been
            // identified from here - so it is replayed as-is and labelled as
            // such, never presented as known values.
            if (varpsAfterReady) {
                logger.warn { "EXPERIMENT: holding the varp replay until the client sends its first packet" }
                val t = Thread({
                    val deadline = System.currentTimeMillis() + 30_000
                    while (!client.clientHasSpoken && client.channel.isActive && System.currentTimeMillis() < deadline)
                        Thread.sleep(20)
                    if (!client.channel.isActive) {
                        logger.warn { "EXPERIMENT: client disconnected before it ever spoke - varps never sent" }
                        return@Thread
                    }
                    if (!client.clientHasSpoken) {
                        logger.warn { "EXPERIMENT: 30s and the client never spoke - varps never sent" }
                        return@Thread
                    }
                    logger.warn { "EXPERIMENT: client spoke - releasing the varp replay now" }
                    try {
                        val sent = DefaultVariables.sendDefaultVarps(client, varpLimit, varpDelay)
                        logger.warn { "EXPERIMENT: sent $sent varps after readiness - the client survived all of them" }
                    } catch (e: Exception) {
                        logger.warn { "EXPERIMENT: varp replay stopped: ${e.message}" }
                    }
                }, "varp-replay-gated")
                t.isDaemon = true
                t.start()
                return
            }

            if (varpDelay > 0) {
                logger.warn {
                    "EXPERIMENT: replaying varps on a background thread at ${varpDelay}ms apart " +
                        "- the last SEND line before a disconnect names the culprit"
                }
                val t = Thread({
                    try {
                        val sent = DefaultVariables.sendDefaultVarps(client, varpLimit, varpDelay)
                        logger.warn { "EXPERIMENT: finished replaying $sent varps at ${varpDelay}ms apart - the client survived all of them" }
                    } catch (e: Exception) {
                        logger.warn { "EXPERIMENT: varp replay stopped: ${e.message}" }
                    }
                }, "varp-replay")
                t.isDaemon = true
                t.start()
                return
            }
            val sent = DefaultVariables.sendDefaultVarps(client, varpLimit)
            logger.info { "sent $sent replayed observation varps (unidentified)" +
                if (varpLimit != null) " - LIMITED to $varpLimit by -Dopennxt.experiment.varpLimit" else "" }
        }

        if (!interfacesFirst) replayVarps()
        else logger.warn { "EXPERIMENT: opening interfaces BEFORE the varps (normal order is varps first)" }

        interfaces.openTop(id = 906)

        interfaces.open(id = 907, parent = 906, component = 65, walkable = true)
        interfaces.open(id = 910, parent = 906, component = 66, walkable = true)
        interfaces.open(id = 909, parent = 906, component = 67, walkable = true)
        interfaces.open(id = 912, parent = 906, component = 69, walkable = true)
        interfaces.open(id = 589, parent = 906, component = 68, walkable = true)
        interfaces.open(id = 911, parent = 906, component = 70, walkable = true)
        interfaces.open(id = 914, parent = 906, component = 128, walkable = true)
        interfaces.open(id = 915, parent = 906, component = 129, walkable = true)
        interfaces.open(id = 913, parent = 906, component = 130, walkable = true)
        interfaces.open(id = 815, parent = 906, component = 137, walkable = true)
        interfaces.open(id = 803, parent = 906, component = 132, walkable = true)
        interfaces.open(id = 822, parent = 906, component = 133, walkable = true)
        interfaces.open(id = 825, parent = 906, component = 115, walkable = true)
        interfaces.open(id = 821, parent = 906, component = 116, walkable = true)
        interfaces.open(id = 808, parent = 906, component = 114, walkable = true)
        interfaces.open(id = 820, parent = 906, component = 134, walkable = true)
        interfaces.open(id = 811, parent = 906, component = 131, walkable = true)
        interfaces.open(id = 826, parent = 906, component = 82, walkable = true)
        interfaces.open(id = 801, parent = 906, component = 36, walkable = true)

//        client.write(ClientSetvarcLarge(2771, 55004971))
//        client.write(ClientSetvarcSmall(3496, 0))
//        client.write(ClientSetvarcstrSmall(2508, ""))
//        client.write(ClientSetvarcSmall(1027, 1))
//        client.write(ClientSetvarcSmall(1034, 2))
//        client.write(ClientSetvarcLarge(3699, 4096))
//
//        client.write(RunClientScript(script = 7486, args = arrayOf(27002876, 52494341)))
//        client.write(RunClientScript(script = 7486, args = arrayOf(27002876, 59637768)))

        interfaces.open(id = 1322, parent = 906, component = 151, walkable = true)
        interfaces.open(id = 814, parent = 906, component = 37, walkable = true)

        // AFTER every open, not after the first nineteen. The previous
        // placement sat above two further interfaces.open calls, so a run with
        // -Dopennxt.experiment.interfacesFirst=true still sent 2 of the 21
        // IF_OPENSUBs behind all 1,385 varps - measured, #1434 and #1435 of
        // 1435. The experiment was 19/21 true, which is not what it claimed.
        if (interfacesFirst) replayVarps()

//        client.write(ClientSetvarcSmall(id = 4659, value = 0))
//        client.write(ClientSetvarcLarge(id = 4660, value = 500))
//        client.write(ClientSetvarcSmall(id = 1800, value = 0))
//        client.write(ClientSetvarcLarge(id = 1648, value = 500))
//        client.write(ClientSetvarcSmall(id = 4968, value = 0))
//        client.write(ClientSetvarcSmall(id = 4969, value = 0))
//
//        client.write(ClientSetvarcSmall(id = 3905, value = 0))
//        client.write(ClientSetvarcSmall(id = 4266, value = 1))
//        client.write(ClientSetvarcSmall(id = 4267, value = 110))
//        client.write(ClientSetvarcLarge(id = 4660, value = 500))
//        client.write(ClientSetvarcSmall(id = 4659, value = 0))
//
//        // TODO http image
//        client.write(ClientSetvarcSmall(id = 4263, value = -1))
//        // TODO http image
//        client.write(ClientSetvarcSmall(id = 4264, value = -1))
//        // TODO http image
//        client.write(ClientSetvarcSmall(id = 4265, value = -1))
//
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    0,
//                    16302,
//                    1,
//                    -1,
//                    "This Week In RuneScape: Double XP LIVE & Improved Divination Training",
//                    "This Week In RuneScape we're bringing you the new and improved Divination skill! Why not test it out during Double XP LIVE?",
//                    "this-week-in-runescape-double-xp-live--improved-divination-training",
//                    "04-May-2021",
//                    1
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    1,
//                    16301,
//                    12,
//                    -1,
//                    "New & Improved Divination",
//                    "A major update is coming to Divination next week. Click here to learn all about it!",
//                    "new--improved-divination",
//                    "29-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    2,
//                    16293,
//                    1,
//                    -1,
//                    "This Week In RuneScape: Dailies & Distractions & Diversions Week Begins!",
//                    "It�s Dailies & Distractions & Diversions Week!",
//                    "this-week-in-runescape-dailies--distractions--diversions-week-begins",
//                    "26-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    3,
//                    16282,
//                    12,
//                    -1,
//                    "Double XP LIVE Returns Soon!",
//                    "Double XP LIVE is coming again soon!",
//                    "double-xp-live-returns-soon",
//                    "23-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    4,
//                    16291,
//                    7,
//                    -1,
//                    "RuneScape On Mobile This Summer - A Message From Mod Warden",
//                    "RuneScape is coming to mobile this Summer, and you can register today for free rewards!",
//                    "runescape-on-mobile-this-summer---a-message-from-mod-warden",
//                    "22-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    5,
//                    16275,
//                    1,
//                    -1,
//                    "This Week In RuneScape: Rex Matriarchs & Combat Week!",
//                    "This week the Rex Matriarchs come roaring into the game with a new combat challenge for experienced fighters. Which is fitting, because it�s also Combat Week!",
//                    "this-week-in-runescape-rex-matriarchs--combat-week",
//                    "19-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    6,
//                    16270,
//                    1,
//                    -1,
//                    "This Week In RuneScape: Skilling Week Begins",
//                    "This Week In RuneScape Awesome April begins, bringing with it Skilling Week!",
//                    "this-week-in-runescape-skilling-week-begins",
//                    "12-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    7,
//                    16257,
//                    3,
//                    -1,
//                    "Lockout Account Returns - Updates",
//                    "Welcoming back The Returned.",
//                    "lockout-account-returns---updates",
//                    "08-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    8,
//                    16258,
//                    1,
//                    -1,
//                    "This Week In RuneScape: The RS20 mini-quest series continues!",
//                    "This Week In RuneScape the Ninja Team returns for Strike 21. We've also got the next part of the RS20: Once Upon a Time miniquest series!",
//                    "this-week-in-runescape-the-rs20-mini-quest-series-continues",
//                    "05-Apr-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    9,
//                    16243,
//                    1,
//                    -1,
//                    "This Week In RuneScape: The Spring Festival Begins!",
//                    "This Week In RuneScape marks the beginning of the Spring Festival!",
//                    "this-week-in-runescape-the-spring-festival-begins",
//                    "29-Mar-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    10,
//                    16248,
//                    3,
//                    -1,
//                    "Account Returning Begins & Making Things Right",
//                    "An update on the Login Lockout situation, including the first details on the return of accounts and more.",
//                    "account-returning-begins--making-things-right",
//                    "26-Mar-2021",
//                    0
//                )
//            )
//        )
//        client.write(
//            RunClientScript(
//                script = 10931,
//                args = arrayOf(
//                    11,
//                    16219,
//                    3,
//                    -1,
//                    "Login Lockout Daily Updates",
//                    "This page is where we'll post the most recent news on the Login Lockout situation. Check back regularly for updates.",
//                    "login-lockout-daily-updates",
//                    "26-Mar-2021",
//                    0
//                )
//            )
//        )
//        client.write(RunClientScript(script = 10936, args = emptyArray()))
//
//        client.write(ChatFilterSettingsPrivatechat(0))
//        client.write(FriendlistLoaded)
    }

    override fun tick() {
        // TODO Do lobby players even need to be ticked?
    }
}