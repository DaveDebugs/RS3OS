package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * LIGHTING LOGS - the whole of it.
 */
object Firemaking {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON, like [Skilling.enabled]. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.firemaking") != "false"

    // ================================================================
    // THE CONSTANTS
    // ================================================================

    const val LIGHT_ACTION = "Light"

    /** `locs.name` 70755 = "Fire". 7/7 (LOC_ADD_CHANGE on every catch tick). */
    const val FIRE_LOC = 70755

    /** The shape half of LOC_ADD_CHANGE's `shapeRotation`. 0xA9 - 0x80 = 41; (41 >> 2) & 0x1f = 10. 7/7. */
    const val FIRE_SHAPE = 10

    /** The rotation half: 41 & 3 = 1. 7/7, on seven different tiles. */
    const val FIRE_ROTATION = 1

    /** Firemaking xp for one `Logs`, in TENTHS. UPDATE_STAT stat 11 moved 240 -> 280 -> 320 -> 360. */
    const val LOGS_XP_TENTHS = 400

    /** The item the seven measured fires were lit with. `items.name` 1511 = "Logs". */
    const val LOGS_ITEM = 1511

    /** All four animation slots, delay 0, on the attempt tick. (5 of 7 attempt ticks). */
    val LIGHT_ANIMATION: IntArray = intArrayOf(25600, 25600, 25600, 25600)

    /** The block the reference client sends on the tick before the catch. Same four-slot -1 shape [Skilling] stops with. */
    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    /**
     * **THERE IS NO FINISH ANIMATION.** How many PLAYER_INFO ANIMATION blocks the reference client sends on the
     * catch tick: **zero**, 7 of 7.
     */
    const val CATCH_TICK_ANIMATIONS = 0

    /** How many ticks after the catch were swept for a finish animation, and found empty. */
    const val CATCH_TAIL_TICKS_SWEPT = 7

    /** How many catches the sweep covered. All seven recorded fires. */
    const val CATCH_TAIL_OBSERVATIONS = 7

    const val FIRE_LOC_ANIMATION = 16704

    /**
     * The gap from the attempt tick to the FIRST repeat of [LIGHT_ANIMATION], then the gap between
     * every later repeat. 99,102,106,110,114,118 / 282,285,289,293,297 / 775,778 - the
     * first gap is 3 in 3 of 3 runs and every later gap is 4. The 4 is the same cadence
     * [Skilling.CYCLE_TICKS] measured for gathering, from an independent skill.
     */
    const val FIRST_ANIMATION_GAP = 3
    const val ANIMATION_GAP = 4

    /**
     * The per-tick chance the fire catches, in percent. FITTED, not measured - see the class doc.
     * A geometric draw with p = 0.104 is the maximum-likelihood fit to {1,2,4,4,8,20,28}.
     */
    val CATCH_PERCENT: Int =
        System.getProperty("opennxt.firemaking.catchPercent")?.toIntOrNull()?.coerceIn(1, 100) ?: 10

    /**
     * A hard bound on the wait, so an unlucky draw cannot leave a pending light in the map forever
     * (the `InboundDrainLimit` rule: bound everything). At [CATCH_PERCENT] = 10 the chance of
     * reaching it is 0.9^100 = 2.6e-5, so it is a safety net rather than part of the model.
     * INVENTED.
     */
    val MAX_WAIT_TICKS: Int =
        System.getProperty("opennxt.firemaking.maxWaitTicks")?.toIntOrNull()?.coerceIn(1, 10_000) ?: 100

    /**
     * How long the fire stands. **INVENTED.** The only measured fact is the lower bound: 13-03-01's
     * fire was still there 175 ticks after it caught, when the protocol ended.
     */
    val FIRE_TICKS: Int =
        System.getProperty("opennxt.firemaking.fireTicks")?.toIntOrNull()?.coerceIn(1, 100_000) ?: 200

    /** The measured lower bound on the reference client's fire lifetime; [FIRE_TICKS] may not go under it. */
    const val MEASURED_MIN_FIRE_TICKS = 175

    /** Whether an expired fire is removed from the scene at all. See the class doc on the -1 form. */
    val expireEnabled: Boolean get() = System.getProperty("opennxt.firemaking.expire") != "off"

    /** The two lines the reference client sends, byte for byte off the wire. */
    const val ATTEMPT_MESSAGE = "You attempt to light the logs."
    const val CATCH_MESSAGE = "The fire catches and the logs begin to burn."

    /**
     * The order the step off the fire's tile is tried in.
     *
     * WEST first because that is what the reference client did in **5 of 5** fires where the step carried a
     * visible delta (13-18-55 t126, 13-23-03 t565, 13-37-34 t782 / t786 / t798, all `(-1, 0)`).
     * The remaining seven directions are INVENTED fallbacks for when west is blocked - the reference client was
     * never seen with west blocked, so nothing is known about what it does then.
     */
    val STEP_ORDER: List<Pair<Int, Int>> =
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)

    // ================================================================
    // THE LOG TABLE
    // ================================================================

    /**
     * Level and xp for one log, resolved by ITEM NAME.
     *
     * `Logs` is (40 xp, seven times, off UPDATE_STAT). Everything else comes from the
     * WIKI layer [SkillXpWiki] already in this repository - `Module:Skill calc/Firemaking/data`, whose
     * "All" category's first `Logs` row says level 1 / 40 xp, i.e. the wiki AGREES with the wire on
     * the one row the wire can check. That agreement is what earns the other 38 rows their place,
     * the same argument [Skilling.WOODCUTTING_TABLE] makes for Willow and below.
     */
    data class Requirement(val level: Int, val xpTenths: Int, val levelSource: String, val xpSource: String)

    /** INVENTED fallback for a "Light" item the wiki does not name. Reported in the log line, every time. */
    val DEFAULT_REQUIREMENT = Requirement(1, LOGS_XP_TENTHS, "INVENTED (default)", "INVENTED (default)")

    private val requirementMemo = java.util.concurrent.ConcurrentHashMap<String, Requirement>()

    /** Test seam: forget the resolved table so a property flip is visible. */
    internal fun clearRequirementMemo() = requirementMemo.clear()

    fun requirementFor(itemName: String): Requirement = requirementMemo.computeIfAbsent(itemName) { name ->
        if (name == "Logs") {
            // The wire beats the wiki wherever both speak, exactly as SkillingRates beats it for xp.
            Requirement(1, LOGS_XP_TENTHS, "AUTHORED (level 1, the wiki agrees)", "x7")
        } else {
            val row = if (SkillXpWiki.enabled) SkillXpWiki.rows("Firemaking", name).firstOrNull() else null
            if (row == null) DEFAULT_REQUIREMENT
            else Requirement(row.level ?: 1, row.xpTenths, "WIKI ${row.source}", "WIKI ${row.source}")
        }
    }

    // ================================================================
    // THE SEAMS  (same shape as Skilling's, same reason - see SkillingWiring)
    // ================================================================

    /** Where the logs are taken from. Default the ContentPlayer's own inventory, so a check needs no World. */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /** The player's Firemaking level. Default 1. */
    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    /** Where the xp goes on top of [xpAwarded]. Default a no-op. */
    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    /** Chat. Default a no-op; [messagesSent] counts regardless. */
    @Volatile
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    /** The four-slot animation block. Default a no-op; [animationsSent] counts regardless. */
    @Volatile
    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }

    /** Push the backpack to the client. Returns whether anything was sent. */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    /** Where the player actually is. Default the ContentPlayer's own tile. */
    @Volatile
    var tileSupplier: (ContentPlayer) -> TileLocation = { it.location }

    /** Whether a step of (dx,dz) off [from] is legal. Default "yes", so a headless check steps west. */
    @Volatile
    var canStep: (TileLocation, Int, Int) -> Boolean = { _, _, _ -> true }

    /** Move the player onto [to]. Returns whether it happened. Default a no-op that records nothing. */
    @Volatile
    var stepSink: (ContentPlayer, TileLocation) -> Boolean = { _, _ -> false }

    /**
     * The ground logs the reference client drops on the attempt tick (OBJ_ADD id 1511 x1) and removes on the
     * catch tick (OBJ_DEL, same coord). Returns an opaque handle the remove side is given back, or
     * null when there is no ground-item world - a headless check has none.
     */
    @Volatile
    var groundAdd: (ContentPlayer, Int, String, TileLocation) -> Any? = { _, _, _, _ -> null }

    @Volatile
    var groundRemove: (Any) -> Boolean = { false }

    /**
     * "Turn to look at the fire you just lit." The angle only, so a headless check can assert the
     * number the way it asserts the four animation ids.
     */
    @Volatile
    var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }

    // ================================================================
    // STATE
    // ================================================================

    /**
     * One light in flight.
     *
     * [catchTick] is drawn at the attempt (see the class doc), which is what lets the stop
     * animation and the step off the tile land on [catchTick] - 1 the way the reference client's do.
     */
    data class Pending(
        val player: ContentPlayer,
        val itemId: Int,
        val itemName: String,
        val slot: Int,
        val tile: TileLocation,
        val requirement: Requirement,
        val startTick: Long,
        val catchTick: Long,
        val ground: Any?,
        var nextAnimationTick: Long
    ) {
        val delay: Int get() = (catchTick - startTick).toInt()
    }

    /** A fire that is standing, and the tick it goes out on. */
    data class Fire(
        val key: LocChanges.Key,
        val tile: TileLocation,
        val litByTick: Long,
        val expiresAtTick: Long
    )

    private val pending = Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Pending>())
    private val fires = LinkedHashMap<LocChanges.Key, Fire>()

    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount
    fun pendingCount(): Int = pending.size
    fun pendingFor(player: ContentPlayer): Pending? = pending[player]
    fun fireCount(): Int = synchronized(fires) { fires.size }
    fun fireAt(plane: Int, x: Int, z: Int): Fire? =
        synchronized(fires) { fires[LocChanges.Key(plane, x, z, FIRE_SHAPE)] }

    /**
     * Public stop, for the same reason [Skilling.stopFor] is public: a culled player must not leave
     * a pending light behind that fires on an object nobody owns any more.
     */
    /**
     * This module's seat in the one-action-per-player slot.
     */
    internal object SLOT : ActionSlot.Owner {
        override val actionName = "firemaking"
        override fun cancelSlot(player: ContentPlayer, why: String) { cancelFor(player, why) }
    }

    fun cancelFor(player: ContentPlayer, why: String): Boolean {
        ActionSlot.release(player, SLOT)
        val was = pending.remove(player) ?: return false
        cancelled++
        // `was.ground?.let { runCatching { groundRemove(it) } }` and nothing else, so the ONE call
        // site that matters - `World.cullDisconnected` (World.kt:488), i.e. every disconnect during
        // the 1..100-tick wait - deleted the ground copy AND left the backpack short. The logs
        // simply ceased to exist. The cull ordering already supports the repair: World.kt:488 runs
        // before `player.toSave()` at :505, so a restore here is in the blob that gets persisted.
        //
        // ORDER, and why it is this way round: add to the backpack FIRST, then take the ground copy
        // away, and UNDO the add if that fails. The other order duplicates the logs the moment the
        // ground remove succeeds and the add does not. A full backpack leaves the ground item
        // standing rather than deleting it - the player can walk back and pick it up, and it is
        // owned by them for the owner window.
        var restored = false
        var groundLeft = false
        val ground = was.ground
        if (ground != null) {
            val container = runCatching { containerSupplier(player) }.getOrNull()
            val add = if (container == null) null else runCatching { container.add(was.itemId, 1) }.getOrNull()
            if (container != null && add != null && add.added >= 1) {
                if (runCatching { groundRemove(ground) }.getOrDefault(false)) {
                    restored = true
                    runCatching { inventoryResend(player) }
                } else {
                    // Could not retire the escrow: undo the restore rather than mint a second lot.
                    container.remove(was.itemId, add.added)
                    groundLeft = true
                }
            } else {
                groundLeft = true
            }
        }
        runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++
        if (restored) cancelsRestored++ else if (groundLeft) cancelsLeftOnGround++
        logger.info {
            "firemaking: ${player.name}'s light cancelled - $why; " + when {
                restored -> "the ${was.itemName} went back into the backpack and the ground copy was retired"
                groundLeft -> "the backpack could not take the ${was.itemName} back, so the ground copy is LEFT STANDING at " +
                    "(${was.tile.x},${was.tile.y},${was.tile.plane}) for them to pick up"
                else -> "there was no ground escrow to give back (headless, or no ground-item world)"
            } + " (the reference client was never seen cancelling a light)"
        }
        return true
    }

    // ---- counters, so a headless check asserts on numbers rather than side effects ----
    @Volatile private var messagesSent = 0
    @Volatile private var animationsSent = 0
    @Volatile private var attempts = 0
    @Volatile private var catches = 0
    @Volatile private var cancelled = 0
    @Volatile private var stepsTaken = 0
    @Volatile private var stepsRefused = 0
    @Volatile private var expired = 0
    @Volatile private var locChangeAttempts = 0
    @Volatile private var locChangeApplied = 0
    @Volatile private var locChangeFailures = 0
    @Volatile private var locChangeWarned = false

    /** Catches abandoned because the escrowed ground logs were gone by the catch tick. */
    @Volatile private var escrowLost = 0

    /** Cancels that put the logs back in the backpack, and cancels that had to leave them standing. */
    @Volatile private var cancelsRestored = 0
    @Volatile private var cancelsLeftOnGround = 0

    /** Per-player throws contained by [tick]'s own try/catch. Never silent; see WorldNpcs.kt:485-491. */
    @Volatile private var containedFailures = 0

    /** How many times [faceSink] was offered an angle. Counts regardless of what the sink does. */
    @Volatile private var facesSent = 0

    fun messagesSent(): Int = messagesSent
    fun animationsSent(): Int = animationsSent
    fun facesSent(): Int = facesSent
    fun attempts(): Int = attempts
    fun catches(): Int = catches
    fun cancelled(): Int = cancelled
    fun stepsTaken(): Int = stepsTaken
    fun stepsRefused(): Int = stepsRefused
    fun expired(): Int = expired
    fun locChangeAttempts(): Int = locChangeAttempts
    fun locChangeApplied(): Int = locChangeApplied
    fun locChangeFailures(): Int = locChangeFailures
    fun escrowLost(): Int = escrowLost
    fun cancelsRestored(): Int = cancelsRestored
    fun cancelsLeftOnGround(): Int = cancelsLeftOnGround
    fun containedFailures(): Int = containedFailures

    /** Every tenth of xp this module has awarded, per player. Weakly keyed, like [Skilling]'s. */
    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    /** Test seam: back to a clean module. */
    internal fun reset() {
        pending.clear()
        synchronized(fires) { fires.clear() }
        synchronized(awarded) { awarded.clear() }
        tickCount = 0
        messagesSent = 0; animationsSent = 0; attempts = 0; catches = 0; cancelled = 0
        stepsTaken = 0; stepsRefused = 0; expired = 0
        locChangeAttempts = 0; locChangeApplied = 0; locChangeFailures = 0; locChangeWarned = false
        escrowLost = 0; cancelsRestored = 0; cancelsLeftOnGround = 0; containedFailures = 0
        facesSent = 0
        random = java.util.Random(SEED)
    }

    /** Seeded so a check gets the same seven delays every run. "FIRE". */
    private const val SEED = 0x46495245L

    @Volatile
    var random: java.util.Random = java.util.Random(SEED)

    /**
     * The number of ticks from the attempt to the catch. See the class doc: drawn once, at the
     * attempt, because the reference client's stop block and step-off land the tick before it.
     */
    fun rollCatchDelay(): Int {
        var d = 1
        while (d < MAX_WAIT_TICKS && random.nextInt(100) >= CATCH_PERCENT) d++
        return d
    }

    // ================================================================
    // THE CLICK
    // ================================================================

    /** Everything a "Light" click can come to. Typed, so a refusal is never "nothing happened". */
    enum class Outcome {
        /** The logs left the backpack and the fire is pending. */
        LIT,
        /** Not a light: the action string was something else, or firemaking is off. */
        NOT_MINE,
        /** The client's slot/item disagreed with the server's container. */
        STALE_CLICK,
        /** The player already has a light in flight. */
        ALREADY_LIGHTING,
        /** A fire is already standing on this tile. */
        TILE_OCCUPIED,
        /** The player's Firemaking level is below the log's requirement. */
        LEVEL,

        /**
         * The escrowed logs could not be put on the ground, so the light is refused and the unit
         * goes back into the backpack.
         */
        NO_GROUND
    }

    data class Result(val outcome: Outcome, val detail: String, val pending: Pending? = null)

    /**
     * A backpack "Light" click.
     *
     * [action] is the CACHE's option string for the row the client sent, which is what [ItemOps]
     * already resolved from `items.widget_actions_<op-1>`; this module never decides that row 2
     * means light.
     */
    fun light(player: ContentPlayer, itemId: Int, itemName: String, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, "firemaking is switched off")
        if (!action.equals(LIGHT_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, "action '$action' is not $LIGHT_ACTION")

        if (pending.containsKey(player)) {
            // Question 7 (the request repeats): the reference client was never seen double-clicking inside a
            // light, so the refusal is INVENTED - but it must exist, because the alternative is a
            // second set of logs leaving the backpack for one fire.
            logger.info { "firemaking: ${player.name} clicked Light while a light was already in flight - refused." }
            return Result(Outcome.ALREADY_LIGHTING, "a light is already in flight")
        }

        val container = containerSupplier(player)
        val held = container[slot]
        if (held == null || held.id != itemId) {
            // The same stale-click guard ItemOps uses, for the same reason: the client's view can
            // lag a container change by a tick and must not consume whatever moved into the slot.
            logger.info {
                "firemaking: ${player.name}'s Light refused - the client says item $itemId is in slot $slot, " +
                    "the server has ${held?.id ?: "nothing"} there."
            }
            return Result(Outcome.STALE_CLICK, "slot $slot holds ${held?.id ?: "nothing"}, not $itemId")
        }

        val tile = tileSupplier(player)
        // The tile is claimed at the ATTEMPT, not at the catch. Question 9 (a second actor doing the
        // same thing to the same thing in the same tick): without the second clause two players
        // standing on one tile both get a Pending, both pay, and the second catch OVERWRITES the
        // first in `fires` - one fire loc, two lots of xp, and one of the two expiries lost forever.
        val pendingHere = synchronized(pending) {
            pending.values.any { it.player !== player && it.tile.x == tile.x && it.tile.y == tile.y && it.tile.plane == tile.plane }
        }
        if (fireAt(tile.plane, tile.x, tile.y) != null || pendingHere) {
            messageSink(player, "You can't light a fire here."); messagesSent++
            logger.info { "firemaking: ${player.name} refused - a fire is already standing on (${tile.x},${tile.y},${tile.plane})." }
            return Result(Outcome.TILE_OCCUPIED, "a fire is already on this tile")
        }

        val requirement = requirementFor(itemName)
        val level = levelSupplier(player, Stat.FIREMAKING)
        if (level < requirement.level) {
            messageSink(player, "You need a Firemaking level of ${requirement.level} to light this."); messagesSent++
            logger.info {
                "firemaking: ${player.name} refused - Firemaking $level < ${requirement.level} for $itemName " +
                    "(level source ${requirement.levelSource}). The refusal LINE is INVENTED; the reference client's was not recorded."
            }
            return Result(Outcome.LEVEL, "level $level < ${requirement.level}")
        }

        // --- the attempt tick, in the reference client's own order --------------------------------------------
        // `container.removeSlot(slot)`, which takes the WHOLE slot, while `groundAdd` below grounds
        // exactly 1 and `catchFire` burns exactly 1. 12 of the 38 items whose `widget_actions_1` is
        // 'Light' are stackable (`SELECT id,name FROM items WHERE LOWER(widget_actions_1)='light'
        // AND stackable_1=1`, id 34528 Protean logs among them), so a stack of 500 protean logs
        // lit one fire and destroyed the other 499. Taken from the CLICKED slot rather than through
        // `remove(id, 1)`, which scans from slot 0 and would consume a different stack of the same
        // id than the one the client named.
        val consumed = held.minus(1)
        if (consumed == null) container.removeSlot(slot) else container[slot] = consumed
        runCatching { inventoryResend(player) }
        // The ground copy is the ESCROW (see Outcome.NO_GROUND): without it there is nothing to
        // give back on a cancel and nothing that can be stolen out from under the catch, so a null
        // handle is a refusal rather than something to swallow. The unit goes straight back.
        //
        // The escrow cannot expire under a light: `GroundItems.DESPAWN_TICKS` is 200 and
        // [MAX_WAIT_TICKS] is 100, so the longest possible wait is half the item's life. That is a
        // raising MAX_WAIT_TICKS past the despawn would silently turn slow fires into lost logs.
        val ground = runCatching { groundAdd(player, itemId, itemName, tile) }.getOrNull()
        if (ground == null) {
            val back = container.add(itemId, 1)
            if (back.added < 1) {
                // Cannot happen from here - the unit above freed the room - but a container whose
                // add refuses anyway must not be left having eaten the logs silently.
                logger.error {
                    "firemaking: ${player.name}'s logs ($itemId) could not be grounded AND could not be " +
                        "put back (add returned $back). ONE $itemName IS LOST."
                }
            }
            runCatching { inventoryResend(player) }
            logger.warn {
                "firemaking: ${player.name}'s Light refused - the ground-item escrow could not be created " +
                    "at (${tile.x},${tile.y},${tile.plane}) (no ground-item world, or the spawn failed). " +
                    "The $itemName went back to the backpack; no fire, no xp."
            }
            return Result(Outcome.NO_GROUND, "the ground escrow could not be created")
        }
        messageSink(player, ATTEMPT_MESSAGE); messagesSent++

        val delay = rollCatchDelay()
        val catchTick = tickCount + delay
        // The reference client's delay-1 fire carried the STOP block on its attempt tick and no 25600 at all,
        // because "catch - 1" WAS the attempt tick. Reproduced rather than special-cased: the start
        // block only goes out when there is at least one tick of waiting to animate.
        if (delay >= 2) { runCatching { animationSink(player, LIGHT_ANIMATION) }; animationsSent++ }

        val p = Pending(
            player = player, itemId = itemId, itemName = itemName, slot = slot, tile = tile,
            requirement = requirement, startTick = tickCount, catchTick = catchTick, ground = ground,
            nextAnimationTick = tickCount + FIRST_ANIMATION_GAP
        )
        pending[player] = p
        // Takes the player's one action slot, stopping whatever else they had running.
        ActionSlot.claim(player, SLOT)
        attempts++
        // A one-tick catch has no "catch - 1" tick of its own, so the reference client did both on the attempt
        // tick: 13-37-34 t786 carries the STOP block and the step west and no 25600 at all, and
        // t787 is the fire. Reproduced here rather than left as an edge the player can see.
        if (delay == 1) {
            runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++
            stepOff(p)
        }
        logger.info {
            "firemaking: ${player.name} lit $itemName ($itemId) from slot $slot at (${tile.x},${tile.y},${tile.plane}); " +
                "catch in $delay tick(s) (${CATCH_PERCENT}%/tick, FITTED); xp ${requirement.xpTenths / 10.0} " +
                "(${requirement.xpSource}); level ${requirement.level} (${requirement.levelSource})"
        }
        return Result(Outcome.LIT, "catch in $delay tick(s)", p)
    }

    // ================================================================
    // THE TICK
    // ================================================================

    /**
     * One world tick. Returns how many fires caught on it, so the caller - and a check - assert on
     * a number rather than on a side effect.
     *
     * Order inside the tick matters and is the reference client's: the tick BEFORE a catch carries the stop
     * animation and the step off the tile; the catch tick carries the message, the loc, the xp and
     * the removal of the ground logs.
     */
    fun tick(): Int {
        tickCount++
        var caught = 0

        if (pending.isNotEmpty()) {
            for (p in ArrayList(pending.values)) {
 // shape is `WorldNpcs.tick`'s (WorldNpcs.kt:485-491, added by the
                // after it in the iteration order from ticking. Firemaking's own hot-loop is the
                // `else ->` arm below - a Pending whose catch threw stays in the map with
                // `tickCount > catchTick`, so the next tick forces the catch again, for ever. There
                // is no per-cycle cadence to fall back to here (the catch is a one-shot), so the
                // containment DROPS the pending: the logs stay on the ground where the player can
                // pick them up, which is the same conservative half the escrow rule takes.
                try {
                    when {
                        tickCount == p.catchTick -> { if (catchFire(p)) caught++ }
                        tickCount == p.catchTick - 1 -> {
                            runCatching { animationSink(p.player, STOP_ANIMATION) }; animationsSent++
                            stepOff(p)
                        }
                        tickCount < p.catchTick -> {
                            if (tickCount >= p.nextAnimationTick) {
                                runCatching { animationSink(p.player, LIGHT_ANIMATION) }; animationsSent++
                                p.nextAnimationTick = tickCount + ANIMATION_GAP
                            }
                        }
                        else -> {
                            // Cannot happen: catchTick is always >= tickCount + 1 when the Pending is
                            // made, and nothing else advances it. Not swallowed if it ever does.
                            logger.error { "firemaking: ${p.player.name}'s light overshot its catch tick (${p.catchTick} < $tickCount) - forced." }
                            if (catchFire(p)) caught++
                        }
                    }
                } catch (t: Throwable) {
                    containedFailures++
                    pending.remove(p.player)
                    ActionSlot.release(p.player, SLOT)
                    logger.error(t) {
                        "firemaking: ${p.player.name}'s light threw in the firemaking phase; contained, " +
                            "the pending light is DROPPED so it cannot retry every tick, and the other " +
                            "players still ticked. The escrowed ${p.itemName} is left on the ground at " +
                            "(${p.tile.x},${p.tile.y},${p.tile.plane})."
                    }
                }
            }
        }

        val due = synchronized(fires) {
            fires.values.filter { it.expiresAtTick <= tickCount }.also { list -> list.forEach { fires.remove(it.key) } }
        }
        for (f in due) {
            expired++
            if (expireEnabled) {
                sendLocChange(f.key, FIRE_LOC, -1, "burnt out")
                logger.info {
                    "firemaking: the fire at (${f.tile.x},${f.tile.y},${f.tile.plane}) went out after $FIRE_TICKS ticks " +
                        "(INVENTED; the reference client's own fire was still standing 175 ticks after it caught, which is all that is known). " +
                        "Removed with LOC_ADD_CHANGE loc=-1 - never observed; -Dopennxt.firemaking.expire=off to keep it."
                }
            } else {
                logger.info { "firemaking: the fire at (${f.tile.x},${f.tile.y}) expired but -Dopennxt.firemaking.expire=off, so it stays on screen." }
            }
        }
        return caught
    }

    /** Returns whether a fire actually caught. False means the escrow was gone and nothing was paid. */
    private fun catchFire(p: Pending): Boolean {
        pending.remove(p.player)
        // The wait is over either way - caught or escrow gone - so the slot goes back. Without this
        // a finished light would hold the slot until the player started something else, and the
        // next action would spend its claim displacing a light that no longer exists.
        ActionSlot.release(p.player, SLOT)
        // three, CRITICAL): this was `p.ground?.let { runCatching { groundRemove(it) } }` with the
        // boolean discarded. The logs taken out of the backpack at the attempt are a real, OWNER-
        // TAKEABLE GroundItem - `GroundItems.pickup` lets the owner take them back inside the
        // 100-tick owner window at PICKUP_RANGE 1, and the lighter is standing on or beside the
        // tile the whole wait. So the player picked the logs up, the fire caught anyway, and they
        // ended the tick with the logs AND a fire AND 40 xp. A removal that returns false means the
        // logs are no longer in escrow: the light is cancelled instead - no fire, no xp, no catch
        // line.
        val escrow = p.ground
        val stillEscrowed = escrow == null || runCatching { groundRemove(escrow) }.getOrDefault(false)
        if (!stillEscrowed) {
            escrowLost++
            runCatching { animationSink(p.player, STOP_ANIMATION) }; animationsSent++
            logger.warn {
                "firemaking: ${p.player.name}'s ${p.itemName} did NOT catch at " +
                    "(${p.tile.x},${p.tile.y},${p.tile.plane}) - the escrowed logs were gone from the ground " +
                    "by the catch tick (picked back up, or despawned). No fire, no xp: the player has the logs."
            }
            return false
        }
        catches++
        messageSink(p.player, CATCH_MESSAGE); messagesSent++

        val key = LocChanges.Key(p.tile.plane, p.tile.x, p.tile.y, FIRE_SHAPE)
        synchronized(fires) {
            fires[key] = Fire(key, p.tile, tickCount, tickCount + FIRE_TICKS)
        }
        sendLocChange(key, FIRE_LOC, FIRE_LOC, "lit")

 // TURN TO FACE THE FIRE. The step off the tile rode the
        // previous tick; on THIS tick the reference client's local player record carries FACE_DIRECTION, and in
        // all three fires whose step carried a visible delta the angle was 12288 - due east, the
        // fire being one tile east of a player who has just stepped west. Computed from where the
        // player actually is now rather than from the step direction, so a blocked west (which
        // the reference client was never seen with, see STEP_ORDER) still faces the fire instead of asserting a
        // direction nobody measured. `Fire` is 1x1 - `locs` gives it no width or length, and it is
        // placed by LOC_ADD_CHANGE with no map_loc row at all.
        faceFire(p)

        val tenths = p.requirement.xpTenths
 // The ledger records what the SINK took, not what this module intended. (code
        // review, MINOR): the two lines used to be the other way round inside a `runCatching` whose
        // failure was discarded, so `xpAwarded` counted xp that never reached a stat container and
        // no line said so. Order and the WARN are the fix; the ledger is now a record of deliveries.
        val paid = runCatching { xpSink(p.player, Stat.FIREMAKING, tenths / 10.0) }
            .onFailure { logger.warn(it) { "firemaking: the xp sink threw for ${p.player.name} (Firemaking, ${tenths / 10.0} xp) - NOT recorded" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[p.player] = (awarded[p.player] ?: 0) + tenths }
        logger.info {
            "firemaking: ${p.player.name}'s ${p.itemName} caught after ${p.delay} tick(s) at " +
                "(${p.tile.x},${p.tile.y},${p.tile.plane}); loc $FIRE_LOC shape $FIRE_SHAPE rot $FIRE_ROTATION " +
                "; +${tenths / 10.0} Firemaking xp (${p.requirement.xpSource})"
        }
        return true
    }

    /**
     * Moves the player one tile off the fire, the tick before it appears.
     *
     * WEST first ([STEP_ORDER]) because that is what the reference client did every time the step carried a
     * visible delta. A blocked west is INVENTED territory - the reference client was never seen with one.
     */
    /**
     * Turns the lighter to look at the fire on the tick it catches. Returns the angle sent, or
     * null when the player is standing ON the fire's own tile, where there is no direction to
     * send - which happens only when [stepOff] could not move them (`stepsRefused`), a position
     * the reference client was never observed in.
     */
    private fun faceFire(p: Pending): Int? {
        val from = runCatching { tileSupplier(p.player) }.getOrNull() ?: return null
        if (from.x == p.tile.x && from.y == p.tile.y) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, p.tile.x, p.tile.y, 1, 1)
        runCatching { faceSink(p.player, angle) }
        facesSent++
        return angle
    }

    private fun stepOff(p: Pending) {
        val from = tileSupplier(p.player)
        if (from.x != p.tile.x || from.y != p.tile.y || from.plane != p.tile.plane) {
            // Already off the tile - the player walked away while it burned. The reference client's own player
            // never did, so nothing is known about whether that CANCELS the light; this module
            return
        }
        for ((dx, dz) in STEP_ORDER) {
            if (!runCatching { canStep(from, dx, dz) }.getOrDefault(false)) continue
            val to = TileLocation(from.x + dx, from.y + dz, from.plane)
            if (runCatching { stepSink(p.player, to) }.getOrDefault(false)) {
                stepsTaken++
                logger.info { "firemaking: ${p.player.name} stepped ($dx,$dz) off (${from.x},${from.y}) one tick before the fire " }
                return
            }
        }
        stepsRefused++
        logger.warn {
            "firemaking: ${p.player.name} could not be stepped off (${from.x},${from.y}) - every one of " +
                "${STEP_ORDER.size} directions was blocked or the sink refused. The fire still catches; " +
                "the player will be standing in it. The reference client was never observed in this position."
        }
    }

    /**
     * Asks [LocChanges] to place or remove the fire, and COUNTS what happened - the same three
     * counters, for the same reason, as [Skilling]'s own sendLocChange: `LocChanges.change`
     * consults `OpenNXT.protocol`, a `lateinit` only a running server initialises, so in a headless
     * check the call throws before it can send anything. [locChangeApplied] is therefore ZERO
     * headless and the check asserts zero rather than hiding it; the ARGUMENTS stay assertable off
     * [Fire] and [FIRE_SHAPE] / [FIRE_ROTATION], which are exactly what LOC_ADD_CHANGE carries.
     */
    private fun sendLocChange(key: LocChanges.Key, originalId: Int, newId: Int, why: String) {
        locChangeAttempts++
        runCatching {
            LocChanges.change(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = FIRE_ROTATION, originalId = originalId, newId = newId
            )
        }.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "firemaking: LOC_ADD_CHANGE ($why) could not be built for the fire at $key - " +
                        "${it::class.simpleName}: ${it.message}. The server-side fire state is still correct; " +
                        "only the client was not told. EXPECTED with no running protocol table. (Warned once.)"
                }
            }
        }.onSuccess { change -> if (change != null) locChangeApplied++ }
    }

    // ================================================================
    // INSTALL
    // ================================================================

    /**
     * There is no [com.opennxt.content.ContentRegistry] registration for firemaking: the light is
     * an INVENTORY option, not a loc or npc option, so it arrives through [ItemOps] rather than
     * through `dispatchLoc`. [FiremakingWiring.install] is what adds the hook.
     */

    /** How many items in this cache declare "Light" on the row IF_BUTTON2 carries. Derived, never typed. */
    fun lightableItemCount(): Int =
        com.opennxt.resources.sqlite.RsDatabase.queryAll(
            "SELECT COUNT(*) FROM items WHERE LOWER(widget_actions_1) = 'light'"
        ) { it.getInt(1) }.firstOrNull() ?: 0

    /** The ids of those items, lowest first. Derived, never typed; the check pins 1511 among them. */
    fun lightableItemIds(): List<Int> =
        com.opennxt.resources.sqlite.RsDatabase.queryAll(
            "SELECT id FROM items WHERE LOWER(widget_actions_1) = 'light' ORDER BY id"
        ) { it.getInt(1) }
}
