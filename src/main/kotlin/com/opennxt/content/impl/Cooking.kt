package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap

/**
 * Every number below was read off observed build-949 traffic. What each session
 * settles is named at the constant that uses it.
 */
object Cooking {

    private val logger = KotlinLogging.logger { }

    /** `-Dopennxt.experiment.cooking=false` removes the module; the OPLOCT click then only logs. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.cooking") != "false"

    // ================================================================
    // THE NUMBERS OFF THE WIRE
    // ================================================================

    /**
     * The Firemaking fire twice over.
     */
    val FIRE_LOCS: List<Int> =
        System.getProperty("opennxt.cooking.fireLocs")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
            ?: (70755..70771).toList()

    const val MEASURED_FIRE_LOC = 70755

    /**
     * The cache's own gate on "you may use an item on this". Loc 70755 declares `actions_4 =
     * "Use"` (278 locs in the corpus do), which is what [com.opennxt.content.ContentRegistry
     * .dispatchItemOnLoc] validates against, exactly as `dispatchLoc` validates a menu row.
     */
    const val USE_ACTION = "Use"

    /**
     * 582/586/590/594/598/602, 13-37-34 at 807/811/815/819/823/827, 13-18-55 at 141/145/149,
     * 13-03-01 at 318/322 and 446/450/454/458. Every single gap is 4. It is the same 4-tick
     * skilling clock [Skilling.CYCLE_TICKS] measured for mining, woodcutting and fishing - but
     * unlike gathering, cooking has no failed-roll skip: a burn is a RESULT and lands on the beat.
     */
    val CYCLE_TICKS: Int = System.getProperty("opennxt.cooking.cycleTicks")?.toIntOrNull() ?: 4

    /**
     * ARRIVAL-relative in the only sense the reference client offers: S is the tick the server answers the
     * make-X confirmation (`IF_CLOSESUB` 1370), and the first result is always S+1.
     */
    val FIRST_CYCLE_TICKS: Int = System.getProperty("opennxt.cooking.firstCycle")?.toIntOrNull() ?: 1

    /**
     * The cooking animation, all four slots, delay 0. : id 897 in the local player's
     * PLAYER_INFO ANIMATION block, on the tick BEFORE each result, in every session that carries
     * an attributable record (13-23-03 ticks 585/593/597/601 for results 586/594/598/602;
     */
    val ANIMATION: IntArray = intArrayOf(897, 897, 897, 897)

    const val ANIMATION_LEAD_TICKS = 1

    /**
     * The xp the wire paid, in TENTHS, keyed by raw item id. **and it DISAGREES with the
     * wiki - the wire wins**, the same way `SkillingRates` outranks [SkillXpWiki] for Tin and
     * mackerel.
     */
    val MEASURED_XP_TENTHS: Map<Int, Int> = mapOf(13435 to 330, 3226 to 330)

    /**
     * The fire's xp bonus in percent, applied to a food with no [MEASURED_XP_TENTHS] row. 110 =
     * own bonfire column agreeing on the one it states). `-Dopennxt.cooking.fireXpPercent=100`
     * pays the cache/wiki number flat.
     */
    val FIRE_XP_BONUS_PERCENT: Int =
        System.getProperty("opennxt.cooking.fireXpPercent")?.toIntOrNull() ?: 110

    /**
     * Where a burnt raw food goes, keyed by raw item id. and it REFUTES the obvious
     * naming rule.
     */
    val MEASURED_BURNT: Map<Int, Int> = mapOf(3226 to 2146)

    /** Whether the name-derived burnt item is allowed. See [MEASURED_BURNT]. */
    val derivedBurnt: Boolean = System.getProperty("opennxt.cooking.derivedBurnt") != "off"

    // ================================================================
    // MESSAGES
    // ================================================================

    /**
     * The reference client's line on a success. TWO templates, both and which one is used depends on
     * the food's family:
     *
     * - Fish: `You successfully cook some crayfish.` - 6 of 6, `13-23-03-843Z` ticks 582..602.
     * - Meat: `You cook the rabbit.` - 7 of 7 across `13-03-01-053Z` and `13-37-34-128Z`.
     */
    fun successMessage(category: String?, rawName: String): String =
        if (category == "Fish") "You successfully cook some ${noun(rawName)}."
        else "You cook the ${noun(rawName)}."

    fun burnMessage(rawName: String): String = "You accidentally burn the ${noun(rawName)}."

    /** "Raw crayfish" -> "crayfish". The noun both the reference client templates use. */
    internal fun noun(rawName: String): String = rawName.removePrefix("Raw ").lowercase()

    // ================================================================
    // THE RECIPE TABLE: CACHE > WIKI
    // ================================================================

    /**
     * One fire recipe.
     *
     * [levelSource] / [xpSource] are "CACHE" or "WIKI", the same two-source precedence
     * [Skilling.requirementFor] uses, so a log line says where a number came from.
     */
    data class Recipe(
        val rawId: Int,
        val rawName: String,
        val cookedId: Int,
        val cookedName: String,
        val burntId: Int,
        val burntName: String?,
        val level: Int,
        val xpTenths: Int,
        val stopBurnLevel: Int?,
        val category: String?,
        val levelSource: String,
        val xpSource: String,
        val burntSource: String
    )

    /**
     * The cache's cooking recipes, GROUNDED.
     *
     * `data/rs3.sqlite` holds the whole thing on the COOKED item, and this was found by reading
     * `items_attr.extra` for items 13433 and 3228 rather than by looking it up anywhere:
     *
     *     param 2640 = 16     the cooking-recipe marker (constant across all 407)
     *     param 2655 = the RAW item id       (13433 -> 13435, 3228 -> 3226)
     *     param 2645 = the Cooking level     (Lobster 40, Swordfish 45, Monkfish 62, Shark 80)
     *     param 2697 = the xp in TENTHS      (Crayfish 300, Lobster 1200, Shark 2100)
     *     param 963  = the heal in lifepoints (not used here)
     *
     * 407 cooked items carry 2640=16 with a raw link; 351 of them also carry the xp. Every value
     * spot-checked against the wiki agreed (Lobster 40/120, Shark 80/210, Trout 15/70, Minnow
     * 1/15). This is a better source than the wiki for level and xp, and it is the reason the
     * wiki seed is consulted second rather than first.
     */
    private fun cacheRecipes(): Map<Int, Triple<Int, Int?, Int?>> {
        if (!RsDatabase.available) return emptyMap()
        val out = HashMap<Int, Triple<Int, Int?, Int?>>()   // cookedId -> (rawId, level, xpTenths)
        RsDatabase.queryAll(
            "SELECT id, value FROM items_attr WHERE field = 'extra' AND value LIKE '%\"prop\":2640,\"intvalue\":16%'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }.forEach { (id, json) ->
            var raw: Int? = null; var level: Int? = null; var xp: Int? = null
            runCatching {
                for (el in JsonParser().parse(json).asJsonArray) {
                    val o = el.asJsonObject
                    if (o.get("intvalue").isJsonNull) continue
                    when (o.get("prop").asInt) {
                        2655 -> raw = o.get("intvalue").asInt
                        2645 -> level = o.get("intvalue").asInt
                        2697 -> xp = o.get("intvalue").asInt
                    }
                }
            }
            val r = raw ?: return@forEach
            out[id] = Triple(r, level, xp)
        }
        return out
    }

    /** `data/seed/cooking_wiki.json`, built by `tools/build_wiki_cooking.py`. */
    class WikiSeed(val byRaw: Map<Int, JsonObject>, val foods: Int, val withStopFire: Int)

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("cooking_wiki.json")

    val wiki: WikiSeed by lazy { loadWiki() }

    private fun loadWiki(): WikiSeed {
        if (!Files.exists(seedPath)) {
            logger.warn { "cooking: no $seedPath - burn levels are unavailable and nothing will burn" }
            return WikiSeed(emptyMap(), 0, 0)
        }
        val root = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        }.getOrElse {
            logger.error(it) { "cooking: $seedPath is unreadable - refusing to substitute defaults" }
            return WikiSeed(emptyMap(), 0, 0)
        }
        val byRaw = LinkedHashMap<Int, JsonObject>()
        var withStop = 0
        var n = 0
        for (el in root.getAsJsonArray("foods")) {
            val o = el.asJsonObject
            n++
            if (o.get("raw_id").isJsonNull) continue
            val rawId = o.get("raw_id").asInt
            val level = if (o.get("level").isJsonNull) Int.MAX_VALUE else o.get("level").asInt
            // ONE recipe per raw item: the LOWEST-level one, which is the fire's. Raw rabbit has
            // two ("Cooked rabbit" level 1, "Roast rabbit" level 16 on a spit) and the wire cooked
            // it into Cooked rabbit, so lowest-level is the rule the wire confirms.
            val prev = byRaw[rawId]
            val prevLevel = prev?.get("level")?.takeIf { !it.isJsonNull }?.asInt ?: Int.MAX_VALUE
            if (prev == null || level < prevLevel) byRaw[rawId] = o
        }
        for (o in byRaw.values) if (!o.get("stop_burn_fire").isJsonNull) withStop++
        logger.info { "cooking: wiki seed holds $n rows, ${byRaw.size} raw foods, $withStop with a fire stop level" }
        return WikiSeed(byRaw, n, withStop)
    }

    private fun intOrNull(o: JsonObject?, key: String): Int? =
        o?.get(key)?.takeIf { !it.isJsonNull }?.asInt

    /** The burnt item for a raw food: then the seed's name derivation, else null. */
    internal fun burntItemOf(rawId: Int, wikiRow: JsonObject?): Pair<Int?, String> {
        MEASURED_BURNT[rawId]?.let { return it to "" }
        if (!derivedBurnt) return null to "NONE (derivedBurnt=off)"
        val cands = wikiRow?.getAsJsonArray("burnt_candidates") ?: return null to "NONE"
        if (cands.size() == 0) return null to "NONE"
        return cands[0].asJsonObject.get("id").asInt to "DERIVED"
    }

    /**
     * raw item id -> the fire recipe. Cache first for level and xp, wiki for the burn level and
     * the family, [MEASURED_XP_TENTHS] over both.
     */
    val recipes: Map<Int, Recipe> by lazy { buildRecipes() }

    private fun buildRecipes(): Map<Int, Recipe> {
        val out = LinkedHashMap<Int, Recipe>()
        val cache = cacheRecipes()
        val seed = wiki.byRaw
        // one row per RAW item: the lowest-level cooked product, the same rule the seed uses
        val byRaw = LinkedHashMap<Int, Pair<Int, Triple<Int, Int?, Int?>>>()
        for ((cookedId, t) in cache) {
            val prev = byRaw[t.first]
            val prevLevel = prev?.second?.second ?: Int.MAX_VALUE
            if (prev == null || (t.second ?: Int.MAX_VALUE) < prevLevel) byRaw[t.first] = cookedId to t
        }
        val rawIds = LinkedHashSet<Int>().apply { addAll(byRaw.keys); addAll(seed.keys) }
        for (rawId in rawIds) {
            val cacheRow = byRaw[rawId]
            val wikiRow = seed[rawId]
            val rawName = Skilling.itemNameOf(rawId) ?: continue
            val cookedId = cacheRow?.first ?: intOrNull(wikiRow, "product_id") ?: continue
            val cookedName = Skilling.itemNameOf(cookedId) ?: continue
            val level = cacheRow?.second?.second
            val xpFromCache = cacheRow?.second?.third
            val wikiLevel = intOrNull(wikiRow, "level")
            val wikiXp = intOrNull(wikiRow, "xp_tenths")
            val resolvedLevel = level ?: wikiLevel ?: continue
            val baseXp = xpFromCache ?: wikiXp ?: continue
            val measured = MEASURED_XP_TENTHS[rawId]
            val xpTenths = measured ?: (baseXp * FIRE_XP_BONUS_PERCENT / 100)
            val (burntId, burntSource) = burntItemOf(rawId, wikiRow)
            if (burntId == null) continue    // no burnt item known: not registered, see MEASURED_BURNT
            out[rawId] = Recipe(
                rawId = rawId, rawName = rawName,
                cookedId = cookedId, cookedName = cookedName,
                burntId = burntId, burntName = Skilling.itemNameOf(burntId),
                level = resolvedLevel,
                xpTenths = xpTenths,
                stopBurnLevel = intOrNull(wikiRow, "stop_burn_fire"),
                category = wikiRow?.get("category")?.takeIf { !it.isJsonNull }?.asString,
                levelSource = if (level != null) "CACHE" else "WIKI",
                xpSource = if (measured != null) "" else if (xpFromCache != null) "CACHE" else "WIKI",
                burntSource = burntSource
            )
        }
        logger.info {
            "cooking: ${out.size} fire recipes (cache ${cache.size} cooked items, wiki ${seed.size} raw foods); " +
                "${out.count { it.value.stopBurnLevel != null }} carry a stop-burning level"
        }
        return out
    }

    // ================================================================
    // THE BURN ROLL
    // ================================================================

    /**
     * The chance one cycle burns, at [level], for [recipe].
     */
    val BURN_AT_REQUIREMENT: Double =
        System.getProperty("opennxt.cooking.burnAtRequirement")?.toDoubleOrNull() ?: 0.4018

    val DEFAULT_BURN_CHANCE: (Recipe, Int) -> Double = { recipe, level ->
        val stop = recipe.stopBurnLevel
        when {
            stop == null -> BURN_AT_REQUIREMENT               // "Never" stops burning, or unknown
            level >= stop -> 0.0
            level <= recipe.level -> BURN_AT_REQUIREMENT
            else -> BURN_AT_REQUIREMENT * (stop - level).toDouble() / (stop - recipe.level).toDouble()
        }
    }

    /** Seam: a check replaces this to make a cycle deterministic without touching [random]. */
    var burnChance: (Recipe, Int) -> Double = DEFAULT_BURN_CHANCE

    val stopOnMove: Boolean = System.getProperty("opennxt.cooking.stopOnMove") != "off"

    /** Injectable so a check pins a seeded stream, exactly as [Skilling.random] is. */
    var random: java.util.Random = java.util.Random(0x434f4f4bL)   // "COOK"

    // ================================================================
    // SEAMS - the same five [Skilling] uses, for the same reason
    // ================================================================

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    /**
     * "Turn to face the fire." The angle only, the same shape as [animationSink], so a headless
     * check can assert the number without a wire.
     */
    @Volatile var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }

    /**
     * Whether the player is still in the world. Default true (a headless check has no World).
     * [CookingWiring] points it at `World.isOnline`, so a disconnect stops the action on the next
     * tick even if the cull path is ever removed.
     */
    @Volatile var onlineCheck: (ContentPlayer) -> Boolean = { true }

    /** Where the player is now; [CookingWiring] points it at the live entity, as [Skilling] does. */
    @Volatile var locationSupplier: (ContentPlayer) -> TileLocation = { it.location }

    @Volatile var firePresent: (Int, Int, Int, Int) -> Boolean = { locId, x, z, plane ->
        defaultFirePresent(locId, x, z, plane)
    }

    /** The strict reading of [firePresent]. Named so a check can assert on it directly. */
    fun defaultFirePresent(locId: Int, x: Int, z: Int, plane: Int): Boolean {
        if (runCatching { Firemaking.fireAt(plane, x, z) }.getOrNull() != null) return true
        return runCatching {
            LocInteraction.placementOf(locId, x, z, plane) != null ||
                LocInteraction.placementsCovering(x, z, plane).any { it.locId == locId }
        }.getOrDefault(false)
    }

    // ================================================================
    // THE RUNNING ACTION
    // ================================================================

    enum class Outcome {
        STARTED, COOKED, BURNT, NO_RECIPE, NEED_LEVEL, NOT_HELD, OUT_OF_RAW, DISABLED, FULL,

        /**
         * There is no fire at the tile the click named - or there is no longer one there. See
         * [firePresent]; the sibling of [Skilling.Outcome.NO_PLACEMENT], for the same reason.
         */
        NO_FIRE
    }

    data class Result(
        val outcome: Outcome,
        val recipe: Recipe? = null,
        val itemId: Int? = null,
        val xpTenths: Int = 0,
        val message: String? = null,
        val chance: Double = 0.0,
        val detail: String = ""
    ) {
        val produced: Boolean get() = outcome == Outcome.COOKED || outcome == Outcome.BURNT
    }

    data class Active(
        val recipe: Recipe,
        val locId: Int,
        val startTile: TileLocation,
        /**
         * The tile the FIRE is on - not [startTile], which is the player's. Kept so [tick] can ask
         * [firePresent] again every cycle: a fire that burns out ([Firemaking.FIRE_TICKS]) under a
         * running cook must end it.
         */
        val fireTile: TileLocation,
        var nextTick: Long,
        var cycles: Int = 0,
        var cooked: Int = 0,
        var burnt: Int = 0
    )

    /**
     * Weakly keyed, unlike [Skilling.active]'s IdentityHashMap: a player who logs out mid-cook
     * must be collectable from here even if nothing ever calls [stopFor]. ContentPlayer does not
     * override equals/hashCode, so a WeakHashMap is an identity map anyway.
     */
    private val active: MutableMap<ContentPlayer, Active> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Active>())

    /**
     * The tick of each player's last CYCLE, per PLAYER not per action - the cadence
     * [Skilling.lastCycle] documents. A second OPLOCT inside the 4-tick window switches the food
     * and pays at the OLD cycle's due tick; it never pays immediately. Without this, alternating
     * two raw foods would cook one item per packet.
     */
    private val lastCycle: MutableMap<ContentPlayer, Long> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    private var tickCount: Long = 0
    private var cyclesPaid = 0
    private var actionsStarted = 0
    private var actionsStopped = 0
    private var animationsSent = 0
    private var messagesSent = 0
    private var facesSent = 0

    /** Per-player throws contained by [tick]'s own try/catch. Never silent; see WorldNpcs.kt:485-491. */
    @Volatile private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun facesSent(): Int = facesSent

    /** Test seam, and the reset a check needs between sections. */
    internal fun clearActions() {
        active.clear(); lastCycle.clear(); tickCount = 0
        cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0; animationsSent = 0; messagesSent = 0
        facesSent = 0
        containedFailures = 0
        awarded.clear()
    }

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())
    fun xpTenthsAwarded(player: ContentPlayer): Int = awarded[player] ?: 0

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "cooking"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        // Released unconditionally; see Skilling.stop for why the identity test makes that safe.
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info {
                "cooking: ${player.name} stopped after ${was.cycles} cycles " +
                    "(${was.cooked} cooked, ${was.burnt} burnt) - $why"
            }
        }
    }

    private fun animate(player: ContentPlayer) {
        runCatching { animationSink(player, ANIMATION) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, message) }; messagesSent++
    }

    /**
     * Turns [player], standing on [from], to face the fire or range at [fireTile]. Returns the
     * angle sent, or null when the player is standing on the target's own centre and there is no
     * direction to send.
     *
     * The footprint comes from `LocInteraction.placementOf`, whose `dx`/`dz` are already ROTATED -
     * a 2x3 furnace placed at rotation 1 occupies 3x2, and the centre moves with it. A loc with no
     * `map_loc` row (every runtime fire is one: [Firemaking] puts it there with LOC_ADD_CHANGE and
     * never writes the database) falls back to 1x1 at the clicked tile, which is exactly right for
     * a fire.
     */
    private fun face(player: ContentPlayer, from: TileLocation, fireTile: TileLocation, locId: Int): Int? {
        val placed = runCatching {
            LocInteraction.placementOf(locId, fireTile.x, fireTile.y, fireTile.plane)
        }.getOrNull()
        val ox = placed?.originX ?: fireTile.x
        val oz = placed?.originZ ?: fireTile.y
        val sx = placed?.dx ?: 1
        val sz = placed?.dz ?: 1
        if (2 * from.x + 1 == 2 * ox + sx && 2 * from.y + 1 == 2 * oz + sz) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, ox, oz, sx, sz)
        runCatching { faceSink(player, angle) }
        facesSent++
        return angle
    }

    /**
     * Starts (or re-targets) a cook. Called by
     * [com.opennxt.net.game.handlers.OpLocTHandler] through
     * [com.opennxt.content.ContentRegistry.dispatchItemOnLoc], never directly by a packet.
     *
     * The nine questions, answered where they are answered:
     *  - **this tick / next tick**: nothing is cooked here. The action is armed and the first
     * result lands [FIRST_CYCLE_TICKS] ticks later in [tick], which is the reference client's S+1.
     *  - **the actor moves**: [tick] compares the live tile with [Active.startTile] every tick.
     *  - **the request repeats**: [lastCycle] is per player, so a second click inside the window
     *    re-targets the action and still waits for the old cycle's due tick.
     */
    fun start(player: ContentPlayer, rawId: Int, locId: Int, tile: TileLocation, fireTile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.cooking=false")
        val recipe = recipes[rawId]
            ?: return Result(Outcome.NO_RECIPE, detail = "item $rawId is not a fire recipe")
        // THE FIRE HAS TO BE THERE. See [firePresent]: the packet says which loc it clicked and
        // OpLocTHandler does not enforce placement, so this is the only server-side answer to
        // "is there actually a fire at (x,z,plane)". Placed before anything is consumed or paid.
        if (!runCatching { firePresent(locId, fireTile.x, fireTile.y, fireTile.plane) }.getOrDefault(false)) {
            logger.info {
                "cooking: ${player.name}'s click on loc $locId at (${fireTile.x},${fireTile.y},${fireTile.plane}) " +
                    "is refused - no runtime fire and no map placement there. Nothing consumed, nothing paid."
            }
            return Result(Outcome.NO_FIRE, recipe, detail = "no fire at (${fireTile.x},${fireTile.y},${fireTile.plane})")
        }
        val container = containerSupplier(player)
        if (!container.contains(rawId))
            return Result(Outcome.NOT_HELD, recipe, detail = "${recipe.rawName} is not in the backpack")
        val level = levelSupplier(player, Stat.COOKING)
        if (level < recipe.level)
            return Result(Outcome.NEED_LEVEL, recipe, detail = "Cooking $level < ${recipe.level}")

 // TURN TO FACE THE FIRE. After every gate that can refuse - a
        // player who cannot cook this does not turn - and on the click only, because the reference client's
        // angle changes once per interaction, on the tick the player reaches the loc. This
        // server answers the "use on loc" on that tick, so no scheduling is needed. See
        // [faceSink].
        face(player, tile, fireTile, locId)

        // The first cycle is due FIRST_CYCLE_TICKS from now, UNLESS this player paid a cycle
        // inside the last CYCLE_TICKS ticks - then the old cadence is kept and only the food
        // changes.
        //
        // The condition is on [lastCycle] ALONE and deliberately not on "an action is already
        // running". An earlier draft also required `active[player] != null`, and that let a player
        // carrying one raw item of several foods click each one as the previous finished: the
        // action stops on the tick its last raw is consumed, so the next click saw no running
        // action, took the +1 branch, and paid a result every two ticks instead of every four.
        // Same class of defect as the one [Skilling.lastCycle]'s doc records for two rocks.
        val last = lastCycle[player]
        val due = if (last != null && tickCount - last < CYCLE_TICKS) last + CYCLE_TICKS
        else tickCount + FIRST_CYCLE_TICKS
        active[player] = Active(recipe, locId, tile, fireTile, due)
        // Takes the player's one action slot, stopping whatever else they had running.
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        // The reference client's first animation rides the tick the action starts - the same tick the server
        // answers the confirmation - so it cannot come from tick(), which has already run for this
        // tick by the time a packet is handled (World.tick calls the skilling phase before
        // handleIncomingPackets). Sent here for exactly that reason.
        if (due == tickCount + FIRST_CYCLE_TICKS && FIRST_CYCLE_TICKS == ANIMATION_LEAD_TICKS) animate(player)
        logger.info {
            "cooking: ${player.name} started ${recipe.rawName} -> ${recipe.cookedName} on loc $locId " +
                "(level ${recipe.level}/${recipe.levelSource}, ${recipe.xpTenths / 10.0} xp/${recipe.xpSource}, " +
                "burnt ${recipe.burntName}/${recipe.burntSource}, stop ${recipe.stopBurnLevel}); first result at tick $due"
        }
        return Result(Outcome.STARTED, recipe, detail = "first result at tick $due")
    }

    /**
     * ONE cycle: consume one raw, produce the cooked or the burnt item, pay xp on a success only.
     *
     * ORDER IS THE REFERENCE CLIENT'S: the chat line, then the inventory change, then the xp write.
     * `13-37-34-128Z` tick 811 sends MESSAGE_GAME, UPDATE_INV_PARTIAL, UPDATE_STAT in that order,
     * and a burn (tick 807) sends the first two and no UPDATE_STAT at all.
     */
    private fun cycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.recipe
        // RE-VALIDATED EVERY CYCLE, not just at the click. A fire burns out under a running cook
        // (Firemaking.FIRE_TICKS, default 200), and a loc can change. Same rule as the movement
        // test above it: the world is asked again, the packet is not re-trusted.
        if (!runCatching { firePresent(a.locId, a.fireTile.x, a.fireTile.y, a.fireTile.plane) }.getOrDefault(false)) {
            return Result(
                Outcome.NO_FIRE, recipe,
                detail = "the fire at (${a.fireTile.x},${a.fireTile.y},${a.fireTile.plane}) is gone"
            )
        }
        val container = containerSupplier(player)
        val slot = container.slotOf(recipe.rawId)
        if (slot < 0) return Result(Outcome.OUT_OF_RAW, recipe)

        val level = levelSupplier(player, Stat.COOKING)
        val chance = burnChance(recipe, level).coerceIn(0.0, 1.0)
        val burns = random.nextDouble() < chance
        val product = if (burns) recipe.burntId else recipe.cookedId

        // OWNERSHIP INVARIANT: remove first, then add. The removal always frees a slot, so the add
        // into the same container cannot fail for want of space; if it fails anyway the raw goes
        // BACK and the action stops, so no path through here destroys an item without producing
        // one. (Skilling's gather adds without removing and does not need this.)
        //
        // `container.removeSlot(slot)`, which takes the WHOLE slot, while the `add` below produces
        // exactly 1. Ten of the registered recipes have a STACKABLE raw input (17797 Raw heim crab
        // at Cooking 1, through 17815), so one cook of a stack of 200 crab produced one cooked crab
        // and destroyed 199. Taken from the CLICKED slot rather than through `remove(id, 1)`, which
        // scans from slot 0 and could consume a different stack of the same id.
        val held = container[slot]
        if (held == null || held.id != recipe.rawId) {
            return Result(Outcome.OUT_OF_RAW, recipe, detail = "slot $slot no longer held ${recipe.rawName}")
        }
        val remainder = held.minus(1)
        if (remainder == null) container.removeSlot(slot) else container[slot] = remainder
        val add = container.add(product, 1)
        if (add.added < 1) {
            // Puts the slot back exactly as it was: `held` is the ORIGINAL stack, one unit of which
            // this cycle took above, so writing it back restores that one unit and nothing else.
            // (Reachable only when `remainder != null`, i.e. no slot was freed - a stack whose
            // cooked product does not fit a full backpack.)
            container[slot] = held
            return Result(Outcome.FULL, recipe, detail = "no room for $product; one ${recipe.rawName} restored")
        }

        val message = if (burns) burnMessage(recipe.rawName) else successMessage(recipe.category, recipe.rawName)
        say(player, message)
        inventoryResend(player)
        val xp = if (burns) 0 else recipe.xpTenths
        if (xp > 0) {
 // Recorded only AFTER the sink returns. (code review, MINOR): the two lines
            // were the other way round inside a `runCatching` whose failure was discarded, so
            // `xpTenthsAwarded` counted xp that never reached a stat container and nothing said so.
            val paid = runCatching { xpSink(player, Stat.COOKING, xp / 10.0) }
                .onFailure { logger.warn(it) { "cooking: the xp sink threw for ${player.name} (Cooking, ${xp / 10.0} xp) - NOT recorded" } }
                .isSuccess
            if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + xp }
        }
        if (burns) a.burnt++ else a.cooked++
        return Result(
            if (burns) Outcome.BURNT else Outcome.COOKED,
            recipe, itemId = product, xpTenths = xp, message = message, chance = chance
        )
    }

    /**
     * One world tick. The integrator calls this from `World.tick()` beside `Skilling.tick()`
     * (World.kt:299); it returns how many cycles paid so a caller can assert on a number.
     */
    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            // exactly this). Without it, one player's throw stopped every player after them in the
            // iteration order from ticking at all, AND - because `a.nextTick` only advances after a
            // SUCCESSFUL cycle - the throwing action retried on every single tick for the life of
            // the process, cooking (and destroying) at 1/tick instead of 1/4 ticks. The `finally`
            // is what closes the hot loop: the cadence advances whatever the body did.
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                val here = locationSupplier(player)
                if (stopOnMove &&
                    (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane)) {
                    stopFor(player, "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})")
                    continue
                }
                if (tickCount == a.nextTick - ANIMATION_LEAD_TICKS) animate(player)
                if (tickCount < a.nextTick) continue
                val r = cycle(player, a)
                lastCycle[player] = tickCount
                a.cycles++
                when (r.outcome) {
                    Outcome.COOKED, Outcome.BURNT -> {
                        paid++; cyclesPaid++
                        a.nextTick = tickCount + CYCLE_TICKS
                        advanced = true
                        // The last raw item: the reference client's progress interface closes a few ticks after the
                        // final result (13-18-55 result 149, IF_CLOSESUB 152), but no further result
                        // ever lands, so the action itself is over here.
                        if (containerSupplier(player).slotOf(a.recipe.rawId) < 0)
                            stopFor(player, "out of ${a.recipe.rawName} after ${a.cooked} cooked, ${a.burnt} burnt")
                    }
                    else -> { stopFor(player, "cycle refused: ${r.outcome} ${r.detail}"); advanced = true }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "cooking: ${player.name}'s cook threw in the cooking phase; contained, the cadence was " +
                        "advanced so it cannot retry every tick, and the other players still ticked."
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) a.nextTick = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }

    /**
     * Registers the fire recipes with the content registry. Called by [CookingWiring.install].
     * Returns how many loc ids were bound.
     */
    fun install(): Int {
        if (!enabled) {
            logger.warn { "cooking: disabled by -Dopennxt.experiment.cooking=false; no fire is bound" }
            return 0
        }
        var bound = 0
        for (loc in FIRE_LOCS) {
            val ok = runCatching {
                com.opennxt.content.ContentRegistry.onItemOnLoc(itemId = null, locId = loc, action = USE_ACTION) { ctx ->
                    // The live tile, not ContentPlayer.location: the movement check in [tick]
                    // compares against the same seam, so a stale copy here would stop the action
                    // on its own first tick. The FIRE's tile is the context's (x,z,plane) - the
                    // coordinates the client named for the loc - and it is what [firePresent] and
                    // every later cycle are asked about.
                    start(
                        ctx.player, ctx.itemId, ctx.locId, locationSupplier(ctx.player),
                        TileLocation(ctx.x, ctx.z, ctx.plane)
                    )
                }
            }.isSuccess
            if (ok) bound++ else logger.warn { "cooking: loc $loc is not bindable (no definition, or it does not declare '$USE_ACTION')" }
        }
        logger.info { "cooking: bound $bound fire loc(s) for '$USE_ACTION'; ${recipes.size} recipes" }
        return bound
    }
}
