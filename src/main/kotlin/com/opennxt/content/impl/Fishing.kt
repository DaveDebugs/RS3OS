package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.NpcContext
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * FISHING - the first NPC-target gathering loop this server has, built.
 *
 * [Skilling] gathers from a LOC ("Chop down" on a tree). A fishing spot is an NPC, it moves, and
 * the click always involves a walk, so this is a sibling of [Skilling] rather than a branch of it:
 * same seams, same 4-tick cadence, same per-player anti-dupe rule, its own arrival model. Nothing
 * in [Skilling] was edited to make this work.
 */
object Fishing {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON; the OPNPC route in front of it defaults OFF. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.fishing") != "false"

    // ================================================================
    // THE METHOD TABLE - one row per fishing method
    // ================================================================

    /**
     * One way of fishing: which npc, which cache option, which tool, which fish.
     *
     * Everything a method needs is here so that adding nets and rods is adding ROWS, not editing
     * [tick] or [cycle]. [chanceKey] and [wikiItem] are the names the two wiki seeds use, so a new
     * row inherits the wiki's level, xp and chance without any further code.
     */
    data class Method(
        /** Short name, used in log lines and by [methodOf]. */
        val key: String,
        val npcId: Int,
        /** The CACHE's own option text for that npc - the string ContentRegistry validates. */
        val action: String,
        /** The tool item id, and whether every account carries it on the tool belt. */
        val toolItemId: Int,
        val toolOnBelt: Boolean,
        /** The item the fish arrives as. */
        val itemId: Int,
        val itemName: String,
        /** All four PLAYER_INFO animation slots carry this id, delay 0. */
        val animation: Int,
        /** The reference client's line on the ARRIVAL tick, once per click. */
        val startMessage: String,
        /** The reference client's line on every paying cycle. */
        val yieldMessage: String,
        /** Ticks from arrival to the first cycle, when no cadence is already running. */
        val firstCycleTicks: Int,
        /** Where the numbers came from, printed with every gather. */
        val provenance: String
    )

    /**
     * CRAYFISH end to end See the class doc
     * for the per-field evidence. npc 14907's `actions_0` is `Cage` in `rs3.sqlite`, which is why
     * the option string is not typed from memory.
     */
    val CRAYFISH = Method(
        key = "crayfish",
        npcId = 14907,
        action = "Cage",
        toolItemId = 13431,          // Crayfish cage
        toolOnBelt = true, // the reference client's backpack held none and it has no equipSlotId
        itemId = 13435, // Raw crayfish - the id the reference client's UPDATE_INV_PARTIAL carried
        itemName = "Raw crayfish",
        animation = 24931,
        startMessage = "You attempt to catch a crayfish.",
        yieldMessage = "You catch a crayfish.",
        firstCycleTicks = 3,
        provenance = "(6 catches, 50 cycles)"
    )

    /**
     * Every method this server knows.
     *
     * ONE ROW. Net ("Net"), lure/bait ("Lure"/"Bait") and cage-for-lobster spots all exist in this
     * cache - `SELECT id, name, actions_0 FROM npcs WHERE name = 'Fishing spot'` lists 233..14907 -
     * and NONE of them is in here, because nothing has been caught on one on camera. Binding
     * "Cage" as a CATEGORY would hand crayfish to npc 312, which is a lobster spot; so [install]
     * binds per npc id, and a new method is a new row plus its own observation.
     */
    val METHODS: List<Method> = listOf(CRAYFISH)

    fun methodOf(npcId: Int, action: String): Method? =
        METHODS.firstOrNull { it.npcId == npcId && it.action == action }

    /**
     * The tiles the reference client's own SET_MAP_FLAG named for the three crayfish clicks in observed:
     * (2899,3467), (2900,3469) and (2899,3467) on plane 0, at the lake south of Burthorpe. These
     * are the tiles the SERVER chose to walk the player to, i.e. next to the spot, not the spot's
     * own tile. `map_keyed` places npc 14907 zero times in this cache, so until something spawns
     * one - `WorldNpcs.spawnAt(14907, TileLocation(x, y, 0))` - nothing here can ever fire.
     * Recorded so the spawn is placed from a measurement rather than from a guess.
     */
    val SPOT_FLAG_TILES: List<TileLocation> = listOf(
        TileLocation(2899, 3467, 0),
        TileLocation(2900, 3469, 0),
        TileLocation(2899, 3467, 0)
    )

    // ================================================================
    // THE CADENCE
    // ================================================================

    /**
     * 24931 animation ticks are exactly 4 (the other three are the two arrival->
     * first-cycle 3s and the 11-tick pause between two clicks), and the wiki's own fishing
     * calculator rolls every `delay or 4` game ticks. Overridable for a comparison.
     */
    val CYCLE_TICKS: Int = System.getProperty("opennxt.fishing.cycleTicks")?.toIntOrNull() ?: 4

    /**
     * INVENTED. Chebyshev tiles from the spot at which the player counts as having ARRIVED, i.e.
     * the tick the start line and the start animation ride. See the class doc for why 2.
     */
    val ARRIVE_RANGE: Int = System.getProperty("opennxt.fishing.arriveRange")?.toIntOrNull() ?: 2

    /**
     * INVENTED, and a BOUND rather than a rule (kit rule 14). Ticks a registered action may spend
     * walking before it is dropped. The reference client's three walks took 4, 4 and 3 ticks; 20 is five times
     * the longest and stops an unreachable spot from holding an action open for ever.
     */
    val ARRIVE_TIMEOUT: Int = System.getProperty("opennxt.fishing.arriveTimeout")?.toIntOrNull() ?: 20

    /** The four-slot animation block, the shape the reference client sends (all four ints the same id, delay 0). */
    fun animationFor(method: Method): IntArray =
        intArrayOf(method.animation, method.animation, method.animation, method.animation)

    // ================================================================
    // LEVEL AND XP
    // ================================================================

    /**
     * Fishing xp off this build's own wire, in TENTHS, keyed by the fish item name.
     */
    val MEASURED_XP_TENTHS: Map<String, Int> = mapOf("Raw crayfish" to 100)

    /**
     * INVENTED floor for a fish neither the wire nor the wiki names. Deliberately the same shape
     * as [Skilling.DEFAULT_XP_TENTHS] and reported as DEFAULT in the log line.
     */
    val DEFAULT_XP_TENTHS: Int = System.getProperty("opennxt.fishing.defaultXpTenths")?.toIntOrNull() ?: 100
    val DEFAULT_LEVEL: Int = System.getProperty("opennxt.fishing.defaultLevel")?.toIntOrNull() ?: 1

    data class Requirement(val level: Int, val xpTenths: Int, val levelSource: String, val xpSource: String)

    /**
     * The level and xp for a fish, each half resolved separately and each labelled:
     *
     *     level:  WIKI (`skill_xp_wiki.json`, Fishing) > AUTHORED-DEFAULT
     * xp:
     *
     * The same order [Skilling.requirementFor] uses, minus the two cache layers - no cache field
     * states a Fishing level or rate for a spot npc (npc 14907 carries `combat 0`, no params).
     */
    fun requirementFor(method: Method): Requirement {
        val wiki = if (SkillXpWiki.enabled) SkillXpWiki.rows("Fishing", method.itemName).firstOrNull() else null
        val measured = MEASURED_XP_TENTHS[method.itemName]
        val (level, levelSource) = when {
            wiki?.level != null -> wiki.level to "WIKI"
            else -> DEFAULT_LEVEL to "AUTHORED-DEFAULT"
        }
        val (xp, xpSource) = when {
            measured != null -> measured to ""
            wiki != null -> wiki.xpTenths to "WIKI"
            else -> DEFAULT_XP_TENTHS to "DEFAULT"
        }
        return Requirement(level, xp, levelSource, xpSource)
    }

    // ================================================================
    // THE SUCCESS ROLL
    // ================================================================

    /** What one cycle's roll knows. */
    data class SuccessContext(val method: Method, val itemName: String, val level: Int, val toolId: Int)

    /**
     * The per-cycle success chance off the wire, by fish item name.
     */
    val MEASURED_CHANCE: Map<String, Double> = mapOf("Raw crayfish" to 6.0 / 50.0)

    /**
     * `-Dopennxt.fishing.chance` = `off` (every cycle pays, the shape
     * [Skilling.DEFAULT_SUCCESS_CHANCE] offers) / `wiki` (the wiki formula, ignoring the wire) /
     * unset.
     */
    val DEFAULT_SUCCESS_CHANCE: (SuccessContext) -> Double = { c ->
        when (System.getProperty("opennxt.fishing.chance")) {
            "off" -> 1.0
            "wiki" -> SkillXpWiki.fishingChance(c.itemName, c.level) ?: 1.0
            else -> MEASURED_CHANCE[c.itemName] ?: SkillXpWiki.fishingChance(c.itemName, c.level) ?: 1.0
        }
    }
    var successChance: (SuccessContext) -> Double = DEFAULT_SUCCESS_CHANCE

    /** Injectable so a check can pin a seeded stream. "FISH". */
    var random: java.util.Random = java.util.Random(0x46495348L)

    // ================================================================
    // SEAMS - the same five [Skilling] has, plus the two a moving npc needs
    // ================================================================

    /** Where a caught fish goes. Default the ContentPlayer's own inventory (headless). */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /** The WORN container, or null headless. A Crayfish cage cannot be worn; a harpoon can. */
    @Volatile
    var wornSupplier: (ContentPlayer) -> ItemContainer? = { null }

    /**
     * THE TOOL BELT. The observation's account fished
     * with no Crayfish cage in the backpack and none worn - a Crayfish cage has no `equipSlotId`
     */
    val DEFAULT_TOOLBELT: Boolean = System.getProperty("opennxt.fishing.toolbelt") != "off"

    @Volatile
    var toolbelt: Boolean = DEFAULT_TOOLBELT

    /** The player's level in a stat. Default 1; the live server supplies the real one. */
    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    /** Where xp goes on top of [xpAwarded]. Default a no-op; nothing here sends a packet. */
    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    /** Tells the client its backpack changed. Returns whether anything was sent. */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    /**
     * Is the npc at that NPC_INFO index still there?
     *
     * Default `true` - the degradation [SkillingWiring]'s doc argues for: headless there is no
     * world, so a spot that can never leave is exactly the behaviour the check drives.
     * [FishingWiring] points it at the live population, and the check drives BOTH states.
     */
    @Volatile
    var spotAlive: (Int) -> Boolean = { true }

    /**
     * Where the npc at that index is NOW, as `[x, z, plane]`, or null when the caller cannot say.
     * A fishing spot moves, and the arrival test has to be against where it is rather than where
     * it was when the click was decoded. Null falls back to the click's own coordinates.
     */
    @Volatile
    var spotTile: (Int) -> IntArray? = { _ -> null }

    private var animationsSent = 0
    private var messagesSent = 0
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent

    // ================================================================
    // WHAT A CYCLE DID
    // ================================================================

    enum class Outcome {
        /** The click registered the action and paid nothing. The reference client pays nothing on the click tick either. */
        STARTED,

        /** A click while this player's action is already running and its cycle is not due. Pays nothing. */
        REPEATING,

        /** A fish went into the container. */
        CAUGHT,

        /** The cycle's roll failed. Nothing paid, the action continues. */
        MISSED,

        /** No such tool in the backpack, worn, or on the belt. */
        NO_TOOL,

        /** Fishing level below the fish's requirement. */
        LEVEL_TOO_LOW,

        /** Backpack full. Checked BEFORE the roll, so a missed cycle cannot keep a doomed action alive. */
        NO_SPACE,

        /** No rs3.sqlite. */
        NO_DATABASE,

        /** [METHODS] has no row for this (npc, action) pair. */
        NO_METHOD,

        /** The spot npc is gone from this server's population - it moved, or it was despawned. */
        SPOT_GONE,

        /** The action spent [ARRIVE_TIMEOUT] ticks without getting within [ARRIVE_RANGE] of the spot. */
        NEVER_ARRIVED
    }

    /** One fishing event, fully described. Every refusal carries the numbers behind it. */
    data class Catch(
        val outcome: Outcome,
        val npcId: Int,
        val npcName: String?,
        val method: Method?,
        val itemId: Int? = null,
        val itemName: String? = null,
        val amount: Int = 0,
        val xpTenths: Int = 0,
        val chance: Double? = null,
        val levelSource: String? = null,
        val xpSource: String? = null,
        val levelRequired: Int = 1,
        val playerLevel: Int = 1,
        val toolId: Int? = null,
        val toolSource: String? = null,
        val inventorySent: Boolean = false,
        val detail: String = ""
    ) {
        val caught: Boolean get() = outcome == Outcome.CAUGHT
        override fun toString() = buildString {
            append("Catch(").append(outcome).append(" npc ").append(npcId).append(" '").append(npcName).append("'")
            if (method != null) append(" via ").append(method.key)
            if (itemId != null) append(" -> ").append(amount).append("x ").append(itemId).append(" '").append(itemName).append("'")
            if (xpTenths > 0) append(" +").append(xpTenths / 10.0).append(" Fishing xp")
            append(" level ").append(playerLevel).append("/").append(levelRequired)
            append(" [level ").append(levelSource ?: "?").append("]")
            if (xpSource != null) append(" [xp ").append(xpSource).append("]")
            if (chance != null) append(" [chance %.3f]".format(chance))
            if (toolSource != null) append(" [tool ").append(toolSource).append("]")
            if (detail.isNotEmpty()) append(" ").append(detail)
            append(")")
        }
    }

    // ================================================================
    // THE RUNNING ACTION
    // ================================================================

    /**
     * A registered fishing action.
     *
     * [startTile] is null until the player ARRIVES. That is the whole difference from
     * [Skilling.Active]: a loc click is handled with the player still standing where they clicked
     * from, so [Skilling] can latch the tile immediately, while an npc click is always followed by
     * a walk (4, 4 and 3 ticks) and latching the click tile would make the very
     * first step cancel the action.
     *
     * [carriedCadence] records that this action REPLACED one that had already arrived. The reference client's
     * third click is the evidence: click at 476 while the action from 420 was running with its
     * last cycle at 475, arrival at 479, and the next cycle at **483** - which is 475 + 8, the old
     * clock continuing, and NOT 479 + 3. The two clicks made from a standing start (270 and 420)
     */
    data class Active(
        val ctx: NpcContext,
        val method: Method,
        val spotIndex: Int,
        val clickedAtTick: Long,
        val carriedCadence: Boolean,
        var startTile: TileLocation? = null,
        var due: Long = -1L,
        var cycles: Int = 0
    )

    private val active = Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Active>())

    /**
     * The tick of each player's last CYCLE (a pay OR a miss), whatever spot it was on.
     *
     * Per PLAYER, for the reason [Skilling.lastCycleOf]'s own comment gives: a per-node cadence
     * lets a client alternate two spots and be paid once per packet. Weakly keyed so a logged-out
     * ContentPlayer is collectable.
     */
    private val lastCycle: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]

    /** Public stop, for a cull or a logout - the seam `World.cullDisconnected` uses for [Skilling]. */
    fun stopFor(player: ContentPlayer, why: String) = stop(player, why)

    internal fun clearActions() {
        active.clear(); lastCycle.clear(); tickCount = 0; containedFailures = 0
    }

    private var cyclesPaid = 0
    private var cyclesMissed = 0
    private var actionsStopped = 0

    /** Per-player throws contained by [tick]'s own try/catch. Never silent; see WorldNpcs.kt:485-491. */
    @Volatile private var containedFailures = 0

    fun cyclesPaid(): Int = cyclesPaid
    fun cyclesMissed(): Int = cyclesMissed
    fun actionsStopped(): Int = actionsStopped
    fun containedFailures(): Int = containedFailures

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "fishing"
        override fun cancelSlot(player: ContentPlayer, why: String) { stop(player, why) }
    }

    private fun stop(player: ContentPlayer, why: String) {
        // Released unconditionally; see Skilling.stop for why the identity test makes that safe.
        ActionSlot.release(player, SLOT)
        if (active.remove(player) != null) {
            actionsStopped++
            logger.info { "fishing: ${player.name}'s action stopped - $why" }
        }
    }

    /** Where the player is now: the bound WorldPlayer's entity when there is one, else the content location. */
    private fun whereIs(player: ContentPlayer): TileLocation =
        SkillingWiring.ownerOf(player)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: player.location

    /**
     * **Has the player stopped walking?** Arrival is an EVENT, not a distance.
     */
    @Volatile
    var standingStill: (ContentPlayer) -> Boolean = { true }

    /**
     * This module's own tick counter, advanced by [tick].
     *
     * Not `World.ticks()`, for the reason [Skilling]'s own counter is not: a headless check has no
     * World, and a cadence whose clock only exists inside a running server is a cadence no check
     * can exercise. The live server calls [tick] once per world tick, so the two are one clock.
     */
    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount

    /** Every tenth of xp this module has awarded, per player. Weakly keyed. */
    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    internal fun clearAwards() = synchronized(awarded) { awarded.clear() }

    // ================================================================
    // THE CLICK
    // ================================================================

    /**
     * One OPNPC click on a fishing spot. Pays NOTHING - the reference client's click tick pays nothing either
     * (the walk has not finished; the start line is on the arrival tick, 3/3).
     */
    fun click(ctx: NpcContext): Catch {
        val npcId = ctx.npcId
        val npcName = ctx.definition.name
        if (!RsDatabase.available) return Catch(Outcome.NO_DATABASE, npcId, npcName, null, detail = "no rs3.sqlite")

        val method = methodOf(npcId, ctx.action)
            ?: return Catch(
                Outcome.NO_METHOD, npcId, npcName, null,
                detail = "no Fishing.METHODS row for (npc $npcId, '${ctx.action}')"
            )

        // ---- 0. a re-click on the SAME spot inside the cycle changes nothing and pays nothing ----
        val running = active[ctx.player]
        val wasArrived = running?.startTile != null
        if (running != null && running.spotIndex == ctx.npcIndex && (running.due < 0 || tickCount < running.due)) {
            // `due < 0` is the action still WALKING to this same spot: clicking it again changes
            // nothing, and must not restart the arrival clock either (a client that re-clicks every
            // tick would otherwise never arrive).
            return Catch(
                Outcome.REPEATING, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                detail = if (running.due < 0) "already walking to this spot"
                else "already fishing here; next cycle in ${running.due - tickCount} tick(s)"
            )
        }

        val container = containerSupplier(ctx.player)
        val level = levelSupplier(ctx.player, Stat.FISHING)
        val requirement = requirementFor(method)

        val tool = toolFor(container, wornSupplier(ctx.player), method)
        if (tool == null) {
            return Catch(
                Outcome.NO_TOOL, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                detail = "no '${Skilling.itemNameOf(method.toolItemId) ?: method.toolItemId}' in the backpack or worn" +
                    (if (toolbelt) " (and the tool belt carries none for this method)" else " (tool belt OFF)")
            )
        }
        if (level < requirement.level) {
            return Catch(
                Outcome.LEVEL_TOO_LOW, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "requirement is ${requirement.levelSource}"
            )
        }
        if (noRoomFor(container, method)) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        // an action that had already ARRIVED hands its clock on; one that was still walking, or
        // none at all, gets the full arrival + firstCycleTicks wait. See [Active.carriedCadence].
        if (running != null) stop(ctx.player, "re-clicked (a different spot, or the cycle was due)")
        active[ctx.player] = Active(ctx, method, ctx.npcIndex, tickCount, wasArrived)
        // Takes the player's one action slot, stopping whatever else they had running.
        ActionSlot.claim(ctx.player, SLOT)
        return Catch(
            Outcome.STARTED, npcId, npcName, method,
            itemId = method.itemId, itemName = method.itemName,
            levelRequired = requirement.level, playerLevel = level,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            toolId = tool.first, toolSource = tool.second,
            detail = "walking to the spot; the first cycle is ${method.firstCycleTicks} tick(s) after arrival" +
                (if (wasArrived) ", or the running cadence if that is later" else "")
        )
    }

    /** Handler for the cache option a [Method] names. Registered per npc id by [install]. */
    fun onFish(ctx: NpcContext): Any = click(ctx)

    // ================================================================
    // THE TICK
    // ================================================================

    /**
     * One world tick: arrive, cancel, or pay a cycle. Returns how many cycles paid a fish, so a
     * caller - and a check - can assert on a number rather than on a side effect.
     *
     * The order inside a tick is deliberate and pinned: the spot is tested FIRST (a spot that left
     * ends the action whether or not the player moved), then arrival, then movement, then the due
     * cycle. Exactly one animation block leaves per tick per player - the arrival block, or the
     * cycle's, never both.
     */
    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            // exactly this). Without it one player's throw stopped every player after them in the
            // iteration order, AND - because `a.due` only advances after a cycle RETURNS - the
            // throwing action retried on every tick for the life of the process. The `finally`
            // advances the cadence whatever the body did, so the worst case is one throw per
            // CYCLE_TICKS instead of one per tick.
            var advanced = false
            try {
                if (!spotAlive(a.spotIndex)) {
                    stop(player, "the spot (npc ${a.ctx.npcId}, index ${a.spotIndex}) is no longer there")
                    continue
                }
                val here = whereIs(player)
                if (a.startTile == null) {
                    // BOTH clauses, and the movement one is the load-bearing half - see [standingStill].
                    // In range is not arrived: the walk OpNpcHandler queued goes to distance 1 and
                    // ARRIVE_RANGE is 2, so a player who is merely in range still has a step to take.
                    if (runCatching { standingStill(player) }.getOrDefault(true) && withinRange(here, a)) {
                        a.startTile = here
                        val last = lastCycle[player]
                        // never sooner than one whole cycle after the player's last one (the anti-dupe
                        // floor), and when the click REPLACED a live arrived action the old 4-tick
                        // clock is kept rather than restarted - 3/3; see [Active].
                        val floor = maxOf(tickCount + a.method.firstCycleTicks, (last ?: (tickCount - CYCLE_TICKS)) + CYCLE_TICKS)
                        a.due = if (a.carriedCadence && last != null) nextOnCadence(last, floor) else floor
                        // the start line rides the ARRIVAL tick, once per click
                        runCatching { messageSink(player, a.method.startMessage) }; messagesSent++
                        // ...and so does the animation, unless this very tick is also a cycle, in which
                        // case the cycle below sends it - the reference client never doubles a block on one tick.
                        if (tickCount < a.due) {
                            runCatching { animationSink(player, animationFor(a.method)) }; animationsSent++
                        }
                    } else if (tickCount - a.clickedAtTick >= ARRIVE_TIMEOUT) {
                        stop(player, "never arrived within $ARRIVE_TIMEOUT tick(s) of the click")
                    }
                    if (a.startTile == null || tickCount < a.due) continue
                } else {
                    val start = a.startTile!!
                    if (here.x != start.x || here.y != start.y || here.plane != start.plane) {
                        stop(player, "moved from (${start.x},${start.y}) to (${here.x},${here.y})")
                        continue
                    }
                    if (tickCount < a.due) continue
                }
                // the animation rides the cycle tick itself - every catch shares its tick
                // with a 24931 block, offset 0, and every non-paying cycle carries one too
                runCatching { animationSink(player, animationFor(a.method)) }; animationsSent++
                val result = cycle(player, a)
                when (result.outcome) {
                    Outcome.CAUGHT -> { paid++; a.due = tickCount + CYCLE_TICKS; a.cycles++; advanced = true }
                    Outcome.MISSED -> { a.due = tickCount + CYCLE_TICKS; a.cycles++; advanced = true }
                    else -> { stop(player, "cycle refused: ${result.outcome} ${result.detail}"); advanced = true }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "fishing: ${player.name}'s action threw in the fishing phase; contained, the cadence was " +
                        "advanced so it cannot retry every tick, and the other players still ticked."
                }
            } finally {
                if (!advanced && a.startTile != null && a.due >= 0 && tickCount >= a.due) a.due = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }

    /** True when [here] is within [ARRIVE_RANGE] Chebyshev tiles of the spot, on its plane. */
    private fun withinRange(here: TileLocation, a: Active): Boolean {
        val tile = spotTile(a.spotIndex)
        val x = tile?.getOrNull(0) ?: a.ctx.x
        val z = tile?.getOrNull(1) ?: a.ctx.z
        val plane = tile?.getOrNull(2) ?: a.ctx.plane
        if (here.plane != plane) return false
        return maxOf(Math.abs(here.x - x), Math.abs(here.y - z)) <= ARRIVE_RANGE
    }

    /** The first tick at or after [from] that lies on the 4-tick clock anchored at [last]. */
    private fun nextOnCadence(last: Long, from: Long): Long {
        var due = last + CYCLE_TICKS
        while (due < from) due += CYCLE_TICKS
        return due
    }

    /**
     * One cycle: the gates again (a backpack can fill and a tool can leave between cycles), then
     * the roll, then the fish and the xp.
     *
     * The space test is BEFORE the roll for the reason [Skilling]'s is: a missed cycle on a full
     * backpack would otherwise keep the action alive for ever, because MISSED never reaches the
     * `add`.
     */
    private fun cycle(player: ContentPlayer, a: Active): Catch {
        val method = a.method
        val npcId = a.ctx.npcId
        val npcName = a.ctx.definition.name
        val container = containerSupplier(player)
        val level = levelSupplier(player, Stat.FISHING)
        val requirement = requirementFor(method)

        val tool = toolFor(container, wornSupplier(player), method)
            ?: return Catch(
                Outcome.NO_TOOL, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                detail = "the tool is gone"
            )
        if (level < requirement.level) {
            return Catch(
                Outcome.LEVEL_TOO_LOW, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second
            )
        }
        if (noRoomFor(container, method)) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        lastCycle[player] = tickCount
        val chance = successChance(SuccessContext(method, method.itemName, level, tool.first)).coerceIn(0.0, 1.0)
        if (random.nextDouble() >= chance) {
            cyclesMissed++
            return Catch(
                Outcome.MISSED, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName, chance = chance,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "cycle missed at chance %.3f".format(chance)
            )
        }

        val add = container.add(method.itemId, 1)
        if (add.added == 0) {
            return Catch(
                Outcome.NO_SPACE, npcId, npcName, method,
                itemId = method.itemId, itemName = method.itemName,
                levelRequired = requirement.level, playerLevel = level,
                levelSource = requirement.levelSource, xpSource = requirement.xpSource,
                toolId = tool.first, toolSource = tool.second,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }
 // Recorded only AFTER the sink returns. (code review, MINOR): the ledger used to
        // be written first and the old WARN said out loud that "the award was still recorded" - a
        // ledger that counts undelivered xp is a ledger that cannot be used to audit deliveries.
        // This module was the only one of the four that logged the failure at all; now all four do,
        // and none of them records an award the sink refused.
        val delivered = runCatching { xpSink(player, Stat.FISHING, requirement.xpTenths / 10.0) }
            .onFailure {
                logger.warn(it) {
                    "fishing: the xp sink threw for ${player.name} (Fishing, ${requirement.xpTenths / 10.0} xp) - NOT recorded"
                }
            }
            .isSuccess
        if (delivered) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + requirement.xpTenths }
        val sent = runCatching { inventoryResend(player) }.getOrDefault(false)
        runCatching { messageSink(player, method.yieldMessage) }; messagesSent++
        cyclesPaid++
        val result = Catch(
            Outcome.CAUGHT, npcId, npcName, method,
            itemId = method.itemId, itemName = method.itemName, amount = add.added,
            xpTenths = requirement.xpTenths, chance = chance,
            levelRequired = requirement.level, playerLevel = level,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            toolId = tool.first, toolSource = tool.second, inventorySent = sent
        )
        logger.info { "fishing: $result. ${method.provenance}" }
        return result
    }

    // ================================================================
    // TOOLS
    // ================================================================

    /**
     * The tool for [method] and WHERE it was found, or null.
     *
     * Matched on the cache's item NAME rather than on the id alone, so a second id carrying the
     * same name (this cache has 63,331 items and duplicate names are the norm - `Raw crayfish` is
     * itself 13435 and 43853) is still a cage. The belt is the last source consulted, so a real
     * held tool always wins and the log line can say which it was.
     */
    fun toolFor(container: ItemContainer, worn: ItemContainer?, method: Method): Pair<Int, String>? {
        val wanted = Skilling.itemNameOf(method.toolItemId) ?: return if (toolbelt && method.toolOnBelt) {
            method.toolItemId to "TOOLBELT (no name in the database to match on)"
        } else null
        for (item in container.items()) if (Skilling.itemNameOf(item.id) == wanted) return item.id to "backpack"
        for (item in worn?.items() ?: emptyList()) if (Skilling.itemNameOf(item.id) == wanted) return item.id to "worn"
        if (toolbelt && method.toolOnBelt) return method.toolItemId to "TOOLBELT"
        return null
    }

    /** True when nothing more of this fish will fit. Stackable fish still fit onto their own stack. */
    private fun noRoomFor(container: ItemContainer, method: Method): Boolean =
        container.isFull() && !(container.stacks(method.itemId) && container.count(method.itemId) > 0)

    // ================================================================
    // REGISTRATION
    // ================================================================

    /**
     * Binds one handler per [METHODS] row, BY NPC ID.
     *
     * Not `onNpcAction("Cage", ...)`: 'Cage' is declared by lobster spots too (npcs 312, 321, 324,
     * 1332, ...) and a category binding would pay them crayfish. [ContentRegistry.onNpc] also
     * re-validates the option against the definition at registration, so a row naming an npc that
     * does not declare its action fails at boot rather than being silently inert.
     *
     * Returns how many rows bound. A row whose npc id is absent from this cache is SKIPPED with a
     * line rather than throwing, so a cache revision cannot stop the server booting.
     */
    fun install(): Int {
        if (!enabled) {
            logger.warn { "fishing is DISABLED (-Dopennxt.experiment.fishing=false) - no fishing spot will act" }
            return 0
        }
        var bound = 0
        for (m in METHODS) {
            try {
                ContentRegistry.onNpc(m.npcId, m.action, ::onFish)
                bound++
                val r = requirementFor(m)
                logger.info {
                    "fishing: bound '${m.action}' on npc ${m.npcId} -> ${m.itemName} (${m.itemId}), " +
                        "level ${r.level} [${r.levelSource}], ${r.xpTenths / 10.0} xp [${r.xpSource}], " +
                        "animation ${m.animation}, cycle $CYCLE_TICKS ticks, first cycle arrival+${m.firstCycleTicks}. " +
                        "${m.provenance}"
                }
            } catch (e: IllegalArgumentException) {
                logger.warn { "fishing: method '${m.key}' did not bind - ${e.message}" }
            }
        }
        logger.info {
            "fishing: $bound of ${METHODS.size} method(s) bound. The OPNPC route in front of this is " +
                "-D$NPC_DISPATCH_SWITCH=true and DEFAULTS OFF, and " +
                "map_keyed places npc ${CRAYFISH.npcId} zero times in this cache - see Fishing.SPOT_FLAG_TILES."
        }
        return bound
    }

    /**
     * The switch that has to be ON for an OPNPC click to reach [ContentRegistry] at all.
     */
    const val NPC_DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"
}
