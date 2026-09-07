package com.opennxt.content.impl

import com.opennxt.content.ActionSlot
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

/**
 * A TELEPORT, as the reference client sends it - and how to tell one from an ordinary map crossing.
 */
object Teleports {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.teleports") != "false"

    /**
     * The reference order of the four packets that make a teleport, as names. Kept as
     * data so the check compares against ONE list instead of restating it, and so the day a
     * observation disagrees there is a single place to correct.
     */
    val REFERENCE_ORDER: List<String> =
        listOf("REBUILD_NORMAL", "PLAYER_INFO", "UPDATE_ZONE_FULL_FOLLOWS", "CAM_FORCEANGLE")

    /** Teleport events measured; the crossing controls; and the zone-count separation. */
    const val MEASURED_TELEPORTS = 14
    const val MEASURED_CROSSINGS = 3
    const val MIN_TELEPORT_ZONES = 159
    const val MAX_CROSSING_ZONES = 52

    const val CORPUS_TELEPORTS = 20
    const val CORPUS_CROSSINGS = 6

    /**
     * The camera packets that separate a teleport from a map crossing: **20 of 20 teleports carry
     * exactly one of these and 0 of 6 crossings carry either.** Two names, not one, because the
     * 09-06 sessions use `CAM_FORCEANGLE` and the 09-04 and 09-07 ones use `CAM_RESET` - see §1b
     * for why a check phrased on the first name alone would have gone vacuous rather than red.
     */
    val CAMERA_PACKETS: List<String> = listOf("CAM_FORCEANGLE", "CAM_RESET")

    /**
     * Teleports carrying `SET_MAP_FLAG(255,255)` on the tick or the tick before, over the whole
     * corpus. **13 of [CORPUS_TELEPORTS]** - a majority, not a test, and DOWN from 12 of 14 on the
     * 09-06 subset: none of the members observation's five teleports carries a clear. This module
     * still sends it (§3) and no longer claims it identifies one.
     */
    const val MAP_FLAG_TELEPORTS = 13

    /** The whole sequence lands inside one tick. 14/14. */
    const val SEQUENCE_TICKS = 1

    @Volatile
    var teleports: Int = 0
        private set

    @Volatile
    var refused: Int = 0
        private set

    internal fun resetCounters() { teleports = 0; refused = 0; uncancelledActions = 0 }

    /**
     * Move [player] to [destination] with the measured sequence.
     *
     * Returns false and sends nothing when the module is off or the destination is not a
     * legal tile - a teleport into a plane the format does not have is a caller bug, and
     * half-sending it would leave the client's scene somewhere the server is not.
     */
    fun teleport(player: WorldPlayer, destination: TileLocation, reason: String = "teleport"): Boolean {
        if (!enabled) { refused++; return false }
        if (destination.plane !in 0..3) {
            refused++
            logger.warn { "teleport: refusing $reason for ${player.name} to $destination - plane is not 0..3" }
            return false
        }

        val cancelled = cancelActions(player)
        if (!cancelled) uncancelledActions++

        // (1) the map flag. The reference client clears it the tick BEFORE the rebuild in 12 of 14; content
        //     runs inside the tick, so this is the same tick. See the class note.
        MapFlag.clear(player)

        // (2) the scene. Forced now rather than left to Viewport.rebuildIfNeeded, which only
        //     fires once the player is 96 tiles off the base - i.e. on a SHORT teleport it
        //     would never fire at all and the client would keep rendering the old scene.
        player.viewport.moveToRegion(destination, player.viewport.mapSize, sendUpdate = true)

        // (3) the move. Movement.process applies it in the movement phase and PLAYER_INFO
        //     encodes it in the update phase, both later in this same tick.
        player.entity.movement.teleport(destination)

        teleports++
        logger.info {
            "teleport: ${player.name} -> (${destination.x},${destination.y},plane ${destination.plane}) " +
                "for '$reason'. SET_MAP_FLAG(clear) + REBUILD_NORMAL(chunk ${destination.x / 8}," +
                "${destination.y / 8}) sent; PLAYER_INFO follows this tick. CAM_FORCEANGLE is NOT " +
                "sent (14/14 in the reference client, no codec and a non-constant payload - see Teleports' KDoc)."
        }
        return true
    }

    /**
     * Stop whatever the player was doing. A SEAM, not an implementation: the modules that own
     * an action ([Skilling], [Fishing], [Cooking], [Firemaking], [PlayerCombat]) are being
     * edited by other work, and reaching into them from here would be the broad refactor rule
     * 10 forbids. A caller installs this; until one does, [uncancelledActions] counts every
     * teleport that left an action running.
     *
     * Returns true when something was actually cancelled or when the seam is installed and
     * had nothing to cancel; false means NO SEAM, which is the state that must be visible.
     */
    @Volatile
    var actionCanceller: ((WorldPlayer) -> Boolean)? = null

    /** Teleports taken with no [actionCanceller] installed - see question 4 above. */
    @Volatile
    var uncancelledActions: Int = 0
        internal set

    private fun cancelActions(player: WorldPlayer): Boolean = actionCanceller?.invoke(player) ?: false

    // =====================================================================================
    // THE DELAY - a teleport is REQUESTED on one tick and LANDS on a later one
    // =====================================================================================
    //
    // Every teleport in the corpus is instantaneous ONCE IT STARTS (§2: one tick, 14 of 14).
    // What is new here is the gap between the CLICK and that tick, and it is not the same for
 // the two sources measured on ``:
    //
    // | source | click | rebuild | ticks | ms | ms / 600 |
    // |---|---|---|---|---|---|
    // | lodestone `IF_BUTTON1 1612:11 arg2=14` | t176 | t196 | 20 | 12,215 | 20.36 |
    // | lodestone `IF_BUTTON1 1612:11 arg2=12` | t395 | t415 | 20 | 12,156 | 20.26 |
    // | ring `IF_BUTTON3 1464:15 mid=39812 arg2=12` | t72 | t77 | 5 | 3,240 | **5.40** |
    // | ring `IF_BUTTON3 1464:15 mid=39812 arg2=12` | t286 | t290 | **4** | 2,907 | **4.84** |
    //
    // **Read the milliseconds, not the tick indices.** The reader attributes a c2s frame to the
    // tick it arrived in, so a click that lands near a tick boundary reads one tick early - which
    // is exactly what the fourth row is. The clock says 5.40 and 4.84 ticks from the client's SEND
    // to the rebuild, and the send-to-server leg is inside that, so both rings are **5 server
 // ticks**. Tier for 20 (2/2, both 20.3) and for 5 (2/2 by the clock,
    // 1/2 by tick index, and the disagreement is explained rather than averaged).
    //
    // Nothing is sent during the wait: over both 20-tick lodestone windows the only server frames
    // are the map's own close on the tick after the click, and over both 5-tick ring windows only
    // `UPDATE_RUNENERGY`. So the wait is genuinely empty and this module sends nothing into it.

    /** A teleport that has been accepted and is counting down. */
    private data class Pending(
        val destination: TileLocation,
        val reason: String,
        var ticksLeft: Int
    )

    private val pending = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Pending>()
    )

    @Volatile
    var scheduled: Int = 0
        private set

    @Volatile
    var scheduleRefused: Int = 0
        private set

    @Volatile
    var droppedOnDisconnect: Int = 0
        private set

    /**
     * The last `World.currentTick` [tick] ran on, so the phase is idempotent within a world tick.
     *
     * This exists because [tick] is currently driven from [Lodestones.tick] rather than from its
     * own phase in `World.tick()` (the piggyback
     * says so in both places). When somebody gives it a proper phase and forgets to remove the
     * piggyback, a pending teleport would count down twice per tick and land ten ticks early -
     * silently. This makes that impossible instead of relying on the removal.
     *
     * `-1` with no world (every check runs headless), which disables the guard - a headless caller
     * drives one tick per call by construction.
     */
    @Volatile
    var lastWorldTick: Long = -1L
        private set

    /** How many ticks [player] has left on a pending teleport, or null when none is armed. */
    fun pendingTicks(player: WorldPlayer): Int? = pending[player]?.ticksLeft

    /** Where [player]'s pending teleport is going, or null. */
    fun pendingDestination(player: WorldPlayer): TileLocation? = pending[player]?.destination

    fun pendingCount(): Int = synchronized(pending) { pending.size }

    internal fun resetPending() {
        synchronized(pending) { pending.clear() }
        scheduled = 0; scheduleRefused = 0; droppedOnDisconnect = 0; lastWorldTick = -1L
    }

    /**
     * Arm a teleport for [delayTicks] ticks' time.
     *
     * Returns false and changes nothing when the module is off, the destination is not a legal
     * tile, or **a different teleport is already armed for this player** - question 7 of the nine.
     * Re-arming the SAME destination is accepted and resets the clock rather than queueing a
     * second, so a double-click on the world map's confirm cannot teleport twice.
     *
     * `delayTicks <= 0` teleports immediately, which is what makes [teleport] and this one the
     * same code path for a caller that has no measured delay.
     */
    fun schedule(
        player: WorldPlayer,
        destination: TileLocation,
        delayTicks: Int,
        reason: String
    ): Boolean {
        if (!enabled) { refused++; return false }
        if (destination.plane !in 0..3) {
            refused++
            logger.warn { "teleport: refusing to schedule $reason for ${player.name} to $destination - plane is not 0..3" }
            return false
        }
        if (delayTicks <= 0) return teleport(player, destination, reason)
        val already = synchronized(pending) { pending[player] }
        if (already != null && already.destination != destination) {
            scheduleRefused++
            logger.info {
                "teleport: ${player.name} asked for '$reason' while '${already.reason}' was still " +
                    "${already.ticksLeft} tick(s) from landing - REFUSED, one at a time."
            }
            return false
        }
        synchronized(pending) { pending[player] = Pending(destination, reason, delayTicks) }
        scheduled++
        logger.info {
            "teleport: ${player.name} armed '$reason' -> (${destination.x},${destination.y}," +
                "plane ${destination.plane}) in $delayTicks tick(s); nothing is sent until it lands."
        }
        return true
    }

    /**
     * One world tick of pending teleports. Returns how many landed.
     *
     * A player whose channel has closed is DROPPED rather than teleported: the tile would be
     * written into a save nobody asked to move, and question 6's answer for a *pending* teleport
     * is not the same as for one already taken (that one has already moved the entity).
     */
    fun tick(): Int {
        val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
        val now = world?.currentTick ?: -1L
        if (now >= 0) {
            if (now == lastWorldTick) return 0
            lastWorldTick = now
        }
        if (!enabled) return 0
        val ready = ArrayList<Pair<WorldPlayer, Pending>>()
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (player, p) = it.next()
                if (!player.client.channel.isActive) {
                    droppedOnDisconnect++
                    it.remove()
                    continue
                }
                if (--p.ticksLeft > 0) continue
                ready.add(player to p)
                it.remove()
            }
        }
        var done = 0
        for ((player, p) in ready) if (teleport(player, p.destination, p.reason)) done++
        return done
    }

    // =====================================================================================
    // THE RING - worn slot 12, and the one row of it that is measured
    // =====================================================================================
    //
 // twice on ``:
    //
    // ```
    //   t72   c2s IF_BUTTON3 1464:15  mid=39812  arg2=12     (hex 05b8000f 009b84 000c)
    //   t77   s2c REBUILD_NORMAL chunk(395,433) + CAM_RESET + MINIMAP_TOGGLE
    // ```
    // and t286 -> t290, same item, same slot, same chunk.
    //
    // **The item is 39812, "Luck of the Dwarves"** - `items.equipSlotId = 12`, which is the ring
    // slot the frame's `arg2` names. Its `items_attr.extra` carries four string params:
    // `528 "Miscellania"`, `529 "Grand Exchange"`, `530 "Keldagrim"`, `1211 "Dwarven Outpost"`.
    //
    // **The op is NOT the row, and it is resolved rather than assumed.** The the reference client server armed
    // `IF_SETEVENTS 1464:15 [0..18] mask 10749950` at t0 (twice, and the second REPLACES the
    // first, which is the mask in force). `0xa407fe` enables ops 1..10, so
    // [ItemOps.rowForOp] puts op 3 at **index 2** - the third enabled op.
    //
    // **The destination.** `REBUILD_NORMAL`'s chunk is `destination / 8`, so chunk (395,433)
    // means x in 3160..3167 and y in 3464..3471. The exact tile comes from `PLAYER_INFO`'s FAR
    // (30-bit) jump on the first of the two - anchored 9 ticks
    // earlier on a clean minimap walk and reads **(3163, 3464)**, which is inside that chunk, so
 // two independent readings agree on the top bits and one gives the low ones. Tier
    // (one far-jump decode, one chunk agreement, and the second teleport carries no far jump at
    // all because the scene base moved with the player).
    //
    // **What is NOT claimed.** That index 2 is param 529 is the pair (the tile is the
    // Grand Exchange and 529 is the string "Grand Exchange"); the general rule that a worn item's
    // rows 2..6 are params 528/529/530/531/1211 - with row 1 being the client's own "Remove" - is
 // on one wire sample, corroborated only by the cache (clientscripts 3290 and
    // 8471 push exactly `528,529,530,531,1211` in that order, and item 1706, the amulet of glory,
    // carries `528 Edgeville / 529 Karamja / 530 Draynor Village / 531 Al Kharid` in its in-game
    // menu order). So this module answers ONLY index 2 on item 39812 and refuses every other row
    // with a log that names the protocol that would settle it.

    /** `Luck of the Dwarves`, the only worn teleport measured. */
    const val RING_ITEM = 39812

    /** The worn container slot the frame carried, and `items.equipSlotId` for [RING_ITEM]. */
    const val RING_WORN_SLOT = 12

    /** The 0-based row [ItemOps.rowForOp] resolves the measured op 3 to under the reference client mask. */
    const val RING_ROW = 2

    /** The the reference client worn mask, `IF_SETEVENTS 1464:15 [0.18] mask 10749950` at t0. Ops 1.10. */
    const val RING_REFERENCE_WORN_MASK = 10749950

    /** The param whose string is the destination [RING_ROW] gives. as the pair. */
    const val RING_PARAM = 529

    /** Where it lands. See the block above for how the tile is separated from the chunk. */
    val RING_DESTINATION = TileLocation(3163, 3464, 0)

    val RING_TICKS: Int
        get() = System.getProperty("opennxt.teleport.ring.ticks")?.toIntOrNull()?.takeIf { it in 0..200 } ?: 5

    /** `-Dopennxt.experiment.teleports.ring=false` turns the worn teleport off on its own. */
    val ringEnabled: Boolean get() = System.getProperty("opennxt.experiment.teleports.ring") != "false"

    /**
     * The worn item's option strings as the CACHE states them, in the order clientscripts 3290
     * and 8471 push the params. Index 0 is the item's own row 1; the client's "Remove" is not in
     * here because it is not an item param.
     */
    val WORN_OPTION_PARAMS = listOf(528, 529, 530, 531, 1211)

    /** `items_attr.extra` string params for one item, by prop. Derived, never typed. */
    fun wornOptionsOf(itemId: Int): Map<Int, String> {
        if (!RsDatabase.available) return emptyMap()
        val blob = RsDatabase.queryAll(
            "SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", itemId
        ) { it.getString("value") }.firstOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Int, String>()
        // The blob is DbBuilder's own fixed shape: [{"prop":N,"intvalue":..,"stringvalue":".."},..].
        // Read with a regex for the same reason Lodestones' morph reader is one - a JSON dependency
        // for two fields, on a shape this repository writes itself.
        Regex("\\{\"prop\":(\\d+),\"intvalue\":(?:null|-?\\d+),\"stringvalue\":(?:null|\"([^\"]*)\")\\}")
            .findAll(blob).forEach { m ->
                val s = m.groupValues[2]
                if (s.isNotEmpty()) out[m.groupValues[1].toInt()] = s
            }
        return out
    }

    @Volatile
    var ringAccepted: Int = 0
        private set

    @Volatile
    var ringRefusedRow: Int = 0
        private set

    internal fun resetRingCounters() { ringAccepted = 0; ringRefusedRow = 0 }

    /**
     * A click on the worn-equipment grid, `1464:15`.
     *
     * Returns true when this object has dealt with the frame. **It must be consulted BEFORE
     * [ItemOps.handleButton]**, which claims every `1464:15` click and unequips on ANY op - so a
     * teleport row routed after it takes the ring off instead of teleporting. The wiring line is
     * in this function's own KDoc in the report and in `Teleports.describe()`.
     *
     * It claims a frame only when the item id, the worn slot and the resolved row all match the
     * measurement, so every other worn click still falls through to `ItemOps` unchanged - except
     * a ring row this module knows about but cannot serve, which is consumed with a message rather
     * than allowed to unequip the ring.
     *
     * ## The nine questions
     *  1. **this tick** - the row is resolved and a [Pending] is armed. Nothing is sent, which is
     * what the reference client's 5-tick window contains.
     *  2. **next tick** - [tick] counts down; the teleport lands on click + [RING_TICKS].
     *  3. **the actor moves** - allowed; the pending teleport does not care where the player walks
     *     to, and the landing cancels the walk.
     *  4. **the target disappears** - the ring can: `worn[12]` is re-read at the CLICK only, so a
     *     ring removed during the 5 ticks still teleports. UNMEASURED and stated; the alternative
     * (re-validating on the landing tick) is a rule no observation supports.
     *  5. **the actor dies** - not modelled, as for the lodestone.
     *  6. **logout / disconnect** - [tick] drops a pending entry whose channel closed.
     *  7. **the request repeats** - two clicks re-arm the same destination; a click for a DIFFERENT
     *     destination while one is armed is refused by [schedule].
     *  8. **persistence fails** - `PlayerSave`'s contract, unchanged.
     *  9. **two players, one destination** - both arrive.
     */
    fun handleWornButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled || !ringEnabled) return false
        if (packet.interfaceId != ItemOps.WORN_IFACE || packet.component != ItemOps.WORN_ITEM_LAYER) return false
        if (packet.mid != RING_ITEM) return false

        val row = ItemOps.rowForOp(player, ItemOps.WORN_IFACE, ItemOps.WORN_ITEM_LAYER, packet.buttonOp)
        val options = wornOptionsOf(RING_ITEM)
        val param = if (row != null && row in 1..WORN_OPTION_PARAMS.size) WORN_OPTION_PARAMS[row - 1] else null
        if (row != RING_ROW) {
            ringRefusedRow++
            logger.info {
                "teleport: ${player.name} used ${SqliteItemCodec.load(RING_ITEM)?.name ?: RING_ITEM} " +
                    "op ${packet.buttonOp}, which resolves to row index $row" +
                    (if (param != null) " = param $param '${options[param] ?: "?"}'" else "") +
                    ". Only row index $RING_ROW (param $RING_PARAM '${options[RING_PARAM] ?: "?"}') is " +
                    "measured, so this is REFUSED. The next observation needs one IF_BUTTON<n> on " +
                    "1464:15 with THIS op and the REBUILD_NORMAL chunk that follows it."
            }
            player.client.write(
                com.opennxt.net.game.serverprot.MessageGame(
                    0, "Nothing interesting happens."
                )
            )
            return true
        }

        val held = runCatching { player.worn[packet.arg2] }.getOrNull()
        if (held == null || held.id != RING_ITEM) {
            logger.info {
                "teleport: refused a ring teleport for ${player.name} - the client says item " +
                    "$RING_ITEM is in worn slot ${packet.arg2}, the server has ${held?.id ?: "nothing"}."
            }
            return true
        }
        val armed = schedule(
            player, RING_DESTINATION, RING_TICKS,
            "${SqliteItemCodec.load(RING_ITEM)?.name ?: "ring"} '${options[RING_PARAM] ?: "row $RING_ROW"}'"
        )
        if (armed) ringAccepted++
        return true
    }

    // =====================================================================================
    // THE ACTION CANCELLER, INSTALLED
    // =====================================================================================

    /**
     * Installs [actionCanceller] on top of [ActionSlot], which is the seam this repository already has
     * for "one action per player".
     *
     * Nothing is reached into: [ActionSlot.holderOf] names the module that owns the player's
     * action and [ActionSlot.Owner.cancelSlot] is that module's OWN stop path - the same one a
     * displacement or a cull uses - so `Skilling` sends its stop animation and `Firemaking` gives
     * the logs back exactly as they would if another action had taken the slot. That is the whole
     * reason the seam was left as a lambda rather than a hard-coded list of five modules.
     *
     * **What this does NOT cancel, stated because it is the gap:** `PlayerCombat` registers no
     * [ActionSlot.Owner], so a teleport out of a fight still leaves the npc's retaliation target
     * and its damage ledger alone. The ledger is the loot record and should stay; the chase is a
     * defect, and question 5 of [teleport]'s nine already says so.
     */
    fun installActionSlotCanceller() {
        if (System.getProperty("opennxt.teleport.cancelactions") == "false") {
            actionCanceller = null
            return
        }
        actionCanceller = { player ->
            val content = player.contentPlayer
            val owner = ActionSlot.holderOf(content)
            if (owner != null) {
                runCatching { owner.cancelSlot(content, "teleported away") }.onFailure { t ->
                    logger.error(t) {
                        "teleport: ${owner.actionName}'s cancel threw while ${player.name} teleported " +
                            "- CONTAINED, the teleport still happens"
                    }
                }
                ActionSlot.clearFor(content)
                logger.info { "teleport: ${player.name}'s ${owner.actionName} was stopped by the teleport" }
            }
            true
        }
    }

    init {
        // AFTER [actionCanceller]'s declaration, deliberately: an object's initialisers run in
        // declaration order, so an init block above the property would be overwritten by the
        // property's own `= null`.
        installActionSlotCanceller()
    }

    fun describe(): String =
        "teleports: the measured sequence is ${REFERENCE_ORDER.joinToString(" -> ")} inside " +
            "$SEQUENCE_TICKS tick, with SET_MAP_FLAG(255,255) on the tick before " +
            "($MEASURED_TELEPORTS teleports vs $MEASURED_CROSSINGS map crossings over the " +
            "the protocol; zone counts >= $MIN_TELEPORT_ZONES vs <= $MAX_CROSSING_ZONES, " +
            "disjoint). THIS SERVER SENDS THREE OF THE FOUR: CAM_FORCEANGLE is not sent (no codec, " +
            "no field declaration, and five distinct payloads across 19 frames), and no teleport " +
            "animation or graphic is sent because none was observed. " +
            "actionCanceller=${if (actionCanceller == null) "NOT INSTALLED - a teleport will not stop a gather or a fight" else "installed on ActionSlot (combat is NOT covered - PlayerCombat registers no Owner)"}. " +
            "DELAYS: lodestone ${Lodestones.TELEPORT_TICKS} tick(s) (, 20.36 and 20.26 " +
            "by the clock), ring $RING_TICKS tick(s). RING: item " +
            "$RING_ITEM worn slot $RING_WORN_SLOT row index $RING_ROW = param $RING_PARAM " +
            "'${wornOptionsOf(RING_ITEM)[RING_PARAM] ?: "?"}' -> (${RING_DESTINATION.x}," +
            "${RING_DESTINATION.y}) - every other row of that ring is REFUSED, not guessed. " +
            "The queue is driven from Lodestones.tick(), NOT from its own World.tick() phase " +
            "(see Teleports.lastWorldTick)."
}
