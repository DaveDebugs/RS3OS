package com.opennxt.content.impl

import com.google.gson.JsonParser
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * Every number in this file was read off observed build-949 traffic, or out of
 * `data/rs3.sqlite`'s own recipe params.
 */
object Smithing {

    private val logger = KotlinLogging.logger { }

    /** `-Dopennxt.experiment.smithing=false` removes the module; the clicks then only log. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.smithing") != "false"

    // ================================================================
    // THE CACHE'S OWN ACTION STRINGS, AND THE THREE LOCS
    // ================================================================

    /** `locs.actions_0` of the Furnace. 44 loc ids declare it. */
    const val SMELT_ACTION = "Smelt"

    /** `locs.actions_0` of the Forge. 14 loc ids declare it. */
    const val HEAT_ACTION = "Heat"

    /** `locs.actions_0` of the Anvil. 61 loc ids declare it. */
    const val SMITH_ACTION = "Smith"

    const val MEASURED_FURNACE_LOC = 67465

    const val MEASURED_FORGE_LOC = 113259

    const val MEASURED_ANVIL_LOC = 113258

    const val PANEL_INTERFACE = 37
    const val PANEL_BEGIN_COMPONENT = 163
    const val PANEL_RECIPE_COMPONENT = 103
    const val PANEL_PARENT = 1477
    const val PANEL_PARENT_COMPONENT = 726

    /**
     * The METAL BANK, opened at tick 996 of the same observation (`IF_OPENSUB` if=487). It is a
     * container this server does not model at all, like the ore box ("Your ore box contains:
     */
    const val METAL_BANK_INTERFACE = 487

    // ================================================================
    // CADENCE
    // ================================================================

    /**
     * 428/432/436/440/444, four gaps of exactly 4, which is [Skilling.CYCLE_TICKS], the same
     * clock mining, woodcutting, fishing and cooking all run on.
     */
    val SMELT_CYCLE_TICKS: Int = System.getProperty("opennxt.smithing.smeltCycle")?.toIntOrNull() ?: 4

    /**
     * click is at tick 424 and the first bar at 428. Here the action starts on the "Smelt" loc
     * click instead (see the class doc), so this is measured relative to THAT.
     */
    val SMELT_FIRST_CYCLE_TICKS: Int =
        System.getProperty("opennxt.smithing.smeltFirstCycle")?.toIntOrNull() ?: 4

    val SMITH_CYCLE_TICKS: Int = System.getProperty("opennxt.smithing.smithCycle")?.toIntOrNull() ?: 2

    /**
     * (614->617, 734->737, 1093->1096, 1211->1214, 1344->1347). The other two are 4 (510->514,
     * 1379->1383) and in both the player was still walking to the anvil, which this module does
     * not model - it starts the action where the player stands.
     */
    val SMITH_FIRST_CYCLE_TICKS: Int =
        System.getProperty("opennxt.smithing.smithFirstCycle")?.toIntOrNull() ?: 3

    // ================================================================
    // ANIMATIONS - all four slots, delay 0, as every other measured skill sends them
    // ================================================================

    /**
     * The furnace animation. Ticks 424/428/432/436/440 of the first smelt and 1042/1049 of the
     * second, i.e. once per cycle on the tick the cycle STARTS ([SMELT_ANIMATION_LEAD_TICKS] =
     * the whole cycle), and on no other tick. Tick 424's record carries the
     * local-player head bit with a single extended-info record, so it is the local player's.
     */
    val SMELT_ANIMATION: IntArray = intArrayOf(32626, 32626, 32626, 32626)

    /** The smelt animation leads its bar by a whole cycle. See [SMELT_ANIMATION]. */
    val SMELT_ANIMATION_LEAD_TICKS: Int get() = SMELT_CYCLE_TICKS

    val HEAT_ANIMATION: IntArray = intArrayOf(32627, 32627, 32627, 32627)

    val SMITH_ANIMATION: IntArray = intArrayOf(32622, 32622, 32622, 32622)

    /**
     * The stop block, sent when the work ends: `(-1,-1,-1,-1)` at ticks 509, 534, 1213 and 1383,
     * each immediately after a run of [HEAT_ANIMATION] or [SMITH_ANIMATION] stopped. Same shape
     * 1383 carry 0, so the delay is NOT settled and 0 is used).
     */
    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    // ================================================================
    // THE UNFINISHED ITEM AND ITS HEAT
    // ================================================================

    /**
     * `items.name` for 47068 is literally **"Unfinished smithing item"**, and 47068 is what every
     * `UPDATE_INV_PARTIAL` between "You create an unfinished item" and "You finish smithing"
     */
    const val UNFINISHED_ITEM = 47068

    /**
     * 604, 719, 1079, 1192, 1336, 1372 all show `3=6/10` in the first frame after the create.
     */
    const val CREATE_HEAT = 10

    const val HEAT_PER_SWING = 10

    /**
     * The maximum heat, as a function of the player's Smithing level. Measured at three levels,
     * exact, and it is what makes [heatStage]'s thresholds fractions rather than constants:
     */
    fun heatMax(level: Int): Int = 312 + 3 * level.coerceAtLeast(1)

    /**
     * Heat gained per tick at a forge.
     *
     * level 1 -> +55/tick 10, 65, 120, 175, 230, 285, 315 (helm 495-502, platebody 604-611)
     * level 2 -> +53/tick 10, 63, 116, 169, 222, 275, 318 (ore box 719-726, platelegs 1079-1086)
     * level 3 -> +81/tick 0, 81, 162, 243, 321 (2h sword 1204-1207, boots 1338-1341)
     *
     * Every transition coincides EXACTLY with a Smithing level-up (level 2 at tick 651, level 3
     * at 1114) and with nothing else: the same forge loc 113259, the same account, the same
     * metal. So the rate is a function of the player's state and the level is the only part of
     * that state which moved. But it is **not monotone** (55 at level 1, 53 at level 2), so it is
     * not `level` alone either, and no expression tried reproduces all three. It is therefore
     * NOT modelled as a function: the default is the level-1 measurement, which is the level a
     * fresh account on this server starts at, and `-Dopennxt.smithing.heatGain=N` changes it.
     */
    val HEAT_GAIN_PER_TICK: Int = System.getProperty("opennxt.smithing.heatGain")?.toIntOrNull() ?: 55

    fun heatStage(heat: Int, max: Int): Int = when {
        heat <= 0 -> 3
        heat * 3 > max * 2 -> 0
        heat * 3 > max -> 1
        else -> 2
    }

    /**
     * helm, platebody, platelegs, 2h sword, armoured boots, gauntlets, ore box) and four Smithing
     * levels: every stage-0 swing moves the progress counter by exactly 20 and
     * every product's total ([SmithRecipe.progressTotal], cache param 7801) is a multiple of it.
     */
    const val BASE_PROGRESS_PER_SWING = 20

    /**
     * What each stage of cooling multiplies both the progress and the xp by. : one
     * factor reproduces all EIGHT measured numbers, four progress and four xp:
     */
    val HEAT_DECAY_FACTOR: Double =
        System.getProperty("opennxt.smithing.heatDecayFactor")?.toDoubleOrNull() ?: 0.8

    /** [BASE_PROGRESS_PER_SWING] decayed by [HEAT_DECAY_FACTOR] ^ [stage]: 20, 16, 13, 10. */
    fun progressPerSwing(stage: Int): Int =
        Math.round(BASE_PROGRESS_PER_SWING * Math.pow(HEAT_DECAY_FACTOR, stage.toDouble())).toInt()

    /**
     * The xp one full-heat swing pays, in tenths, for [recipe].
     *
     * Derived, never typed: a product's cache params give the whole xp (2697, in tenths) and the
     * whole progress (7801), and a full-heat swing is [BASE_PROGRESS_PER_SWING] of that progress,
     * so it is worth that share of the xp. For every bronze product the ratio 2697/7801 is
     * exactly 1.5, so this returns 30 - which is what all 106 stage-0 swings paid.
     * The ratio is NOT constant across the 801-row table (2.5, 1.5, 3.0, 5.0, ... appear), which
     * is why this is computed per recipe rather than fixed at 30.
     */
    fun baseXpTenthsPerSwing(recipe: SmithRecipe): Int =
        Math.round(BASE_PROGRESS_PER_SWING.toDouble() * recipe.xpTenths / recipe.progressTotal).toInt()

    /** [baseXpTenthsPerSwing] decayed by [HEAT_DECAY_FACTOR] ^ [stage]: 30, 24, 19, 15 for bronze. */
    fun xpTenthsPerSwing(recipe: SmithRecipe, stage: Int): Int =
        Math.round(baseXpTenthsPerSwing(recipe) * Math.pow(HEAT_DECAY_FACTOR, stage.toDouble())).toInt()

    // ================================================================
    // MESSAGES - every one copied byte for byte off the wire
    // ================================================================

    fun createAtForgeMessage(name: String) = "You create an unfinished item and begin to heat it: $name."

    fun createAtAnvilMessage(name: String) = "You create an unfinished item and begin to work it: $name."

    fun fullHeatMessage(name: String) = "Your unfinished item is at full heat: $name. Use the anvil."

    const val COOLED_SLIGHTLY =
        "<col=FF0000>Your item has cooled down slightly. It will be slightly harder to work.</col>"

    const val COOLED_SIGNIFICANTLY =
        "<col=FF0000>Your item has cooled down significantly. It's a lot slower to work.</col>"

    const val RAN_OUT_OF_HEAT =
        "<col=EB2F2F>Your item has run out of heat. It's very slow to work. Heat it at a forge."

    fun finishMessage(name: String) = "You finish smithing: $name."

    /**
     * Sent on the LAST bar of a smelt run: tick 444 (the 5th of 5) and tick 1056 (the 4th of 4).
     */
    const val DEPOSIT_BARS =
        "You should deposit your bars in your metal bank (via a furnace, forge or anvil)."

    // ================================================================
    // THE RECIPE TABLES - DERIVED FROM THE CACHE, WITH NO SEED FILE
    // ================================================================
    //
    // WHY NO SEED. [Cooking] needed `data/seed/cooking_wiki.json` because the burn levels are
    // nowhere in the cache. Smithing needs nothing the cache does not already hold: the level,
    // the xp, the total progress, the tool and the material list are all params on the PRODUCT
    // item, and the material quantities are a struct hop away. A seed would be a second copy of
    // a table this file can read in full, and a second copy is a thing that drifts. The one
    // number this module cannot get from the cache - the heat model - is measured off the wire
    // and lives in the constants above.
    //
    // THE PARAMS, found by reading items_attr.extra for the eight products the protocol made:
    //
    //     2640 = 14      the production category. 14 is Smithing (1,491 items); 16 is Cooking,
    //                    which is the same shape [Cooking.cacheRecipes] already reads.
    //     2645           the Smithing level required
    //     2697           the xp in TENTHS  (Bronze bar 10 = 1.0, Bronze full helm 300 = 30.0)
    //     7801           the total progress. PRESENT on the 989 anvil products, ABSENT on the
    //                    bars - which is exactly what separates a smelt from a smith.
    //     2650           the tool item (2347 "Hammer" on 798 of the 801 usable smith recipes)
    //     2675/2676/2677 STRUCT ids, one per material. The struct carries 2656 (or 7763) = the
    //                    item id, 2665/2666 = how many, and 7762 = its display name.
    //
    // GROUNDED, not looked up. Bronze bar 2349 -> structs 43233 (Copper ore x1) and 43234 (Tin
    // ore x1), and the wire consumed exactly one of each per bar. Bronze full helm 1155 ->
    // struct 43273 (Bronze bar x2), and the wire's backpack went 5 bars -> 3. Bronze platebody
    // 1117 -> 43303 (x5), and the backpack went 10 -> 5.

    /** One material line of a recipe: an item and how many of it. */
    data class Material(val itemId: Int, val name: String?, val count: Int)

    /** A furnace recipe: category 14 with materials and NO total progress. 27 of them. */
    data class SmeltRecipe(
        val barId: Int,
        val barName: String,
        val level: Int,
        val xpTenths: Int,
        val toolId: Int?,
        val materials: List<Material>
    )

    /** An anvil recipe: category 14 WITH a total progress (param 7801). 801 of them. */
    data class SmithRecipe(
        val productId: Int,
        val productName: String,
        val level: Int,
        val xpTenths: Int,
        val progressTotal: Int,
        val toolId: Int?,
        val materials: List<Material>
    )

    /** One row of `struct_param`, as read. */
    private data class StructProp(val structId: Int, val prop: Int, val intValue: Int?, val stringValue: String?)

    /** Every material-struct field this module reads, in one query. */
    const val STRUCT_ITEM = 2656
    const val STRUCT_ITEM_ALT = 7763
    const val STRUCT_COUNT = 2665
    const val STRUCT_COUNT_ALT = 2666
    const val STRUCT_NAME = 7762

    /**
     * The material structs, `struct_param` keyed by struct id.
     */
    internal fun materialStructs(): Map<Int, Material> {
        if (!RsDatabase.available) return emptyMap()
        val rows = RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue, stringvalue FROM struct_param WHERE prop IN " +
                "($STRUCT_ITEM, $STRUCT_ITEM_ALT, $STRUCT_COUNT, $STRUCT_COUNT_ALT, $STRUCT_NAME)"
        ) { rs ->
            val v = rs.getInt("intvalue")
            StructProp(rs.getInt("struct_id"), rs.getInt("prop"), if (rs.wasNull()) null else v,
                rs.getString("stringvalue"))
        }
        val item = HashMap<Int, Int>()
        val itemAlt = HashMap<Int, Int>()
        val count = HashMap<Int, Int>()
        val countAlt = HashMap<Int, Int>()
        val name = HashMap<Int, String>()
        for (r in rows) when (r.prop) {
            STRUCT_ITEM -> r.intValue?.let { item[r.structId] = it }
            STRUCT_ITEM_ALT -> r.intValue?.let { itemAlt[r.structId] = it }
            STRUCT_COUNT -> r.intValue?.let { count[r.structId] = it }
            STRUCT_COUNT_ALT -> r.intValue?.let { countAlt[r.structId] = it }
            STRUCT_NAME -> r.stringValue?.let { name[r.structId] = it }
        }
        val out = HashMap<Int, Material>()
        for (id in (item.keys + itemAlt.keys)) {
            val i = item[id] ?: itemAlt[id] ?: continue
            val n = count[id] ?: countAlt[id] ?: continue
            if (i <= 0 || n <= 0) continue
            out[id] = Material(i, name[id] ?: Skilling.itemNameOf(i), n)
        }
        return out
    }

    /** (structs carrying both count props, structs where the two disagree). Pinned by the check. */
    internal fun countPropAgreement(): Pair<Int, Int> {
        if (!RsDatabase.available) return 0 to 0
        val a = HashMap<Int, Int>()
        val b = HashMap<Int, Int>()
        RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue FROM struct_param WHERE prop IN ($STRUCT_COUNT, $STRUCT_COUNT_ALT)"
        ) { rs -> Triple(rs.getInt("struct_id"), rs.getInt("prop"), rs.getInt("intvalue")) }
            .forEach { (id, prop, v) -> if (prop == STRUCT_COUNT) a[id] = v else b[id] = v }
        val both = a.keys.intersect(b.keys)
        return both.size to both.count { a[it] != b[it] }
    }

    /** `items_attr.extra` rows for production category 14, as prop -> int value. */
    private fun categoryRows(): Map<Int, Map<Int, Int>> {
        if (!RsDatabase.available) return emptyMap()
        val out = LinkedHashMap<Int, Map<Int, Int>>()
        RsDatabase.queryAll(
            "SELECT id, value FROM items_attr WHERE field = 'extra' AND value LIKE '%\"prop\":2640,\"intvalue\":14%'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }.forEach { (id, json) ->
            val props = HashMap<Int, Int>()
            runCatching {
                for (el in JsonParser().parse(json).asJsonArray) {
                    val o = el.asJsonObject
                    if (o.get("intvalue").isJsonNull) continue
                    props[o.get("prop").asInt] = o.get("intvalue").asInt
                }
            }
            if (props[PROP_CATEGORY] == SMITHING_CATEGORY) out[id] = props
        }
        return out
    }

    const val PROP_CATEGORY = 2640
    const val SMITHING_CATEGORY = 14
    const val PROP_LEVEL = 2645
    const val PROP_XP_TENTHS = 2697
    const val PROP_PROGRESS = 7801
    const val PROP_TOOL = 2650
    val PROP_MATERIALS = intArrayOf(2675, 2676, 2677)

    private fun materialsOf(props: Map<Int, Int>, structs: Map<Int, Material>): List<Material>? {
        val out = ArrayList<Material>(3)
        for (p in PROP_MATERIALS) {
            val structId = props[p] ?: continue
            out += structs[structId] ?: return null      // a named struct that does not resolve
        }
        return if (out.isEmpty()) null else out
    }

    /** bar item id -> the furnace recipe. */
    val smeltRecipes: Map<Int, SmeltRecipe> by lazy { buildSmelt() }

    /** product item id -> the anvil recipe. */
    val smithRecipes: Map<Int, SmithRecipe> by lazy { buildSmith() }

    private fun buildSmelt(): Map<Int, SmeltRecipe> {
        val structs = materialStructs()
        val out = LinkedHashMap<Int, SmeltRecipe>()
        for ((id, props) in categoryRows()) {
            if (props.containsKey(PROP_PROGRESS)) continue          // that is an anvil recipe
            val level = props[PROP_LEVEL] ?: continue
            val xp = props[PROP_XP_TENTHS] ?: continue
            val mats = materialsOf(props, structs) ?: continue
            val name = Skilling.itemNameOf(id) ?: continue
            out[id] = SmeltRecipe(id, name, level, xp, props[PROP_TOOL], mats)
        }
        logger.info { "smithing: ${out.size} smelt recipes from the cache" }
        return out
    }

    private fun buildSmith(): Map<Int, SmithRecipe> {
        val structs = materialStructs()
        val out = LinkedHashMap<Int, SmithRecipe>()
        for ((id, props) in categoryRows()) {
            val progress = props[PROP_PROGRESS] ?: continue
            if (progress <= 0) continue
            val level = props[PROP_LEVEL] ?: continue
            val xp = props[PROP_XP_TENTHS] ?: continue
            val mats = materialsOf(props, structs) ?: continue
            val name = Skilling.itemNameOf(id) ?: continue
            out[id] = SmithRecipe(id, name, level, xp, progress, props[PROP_TOOL], mats)
        }
        logger.info { "smithing: ${out.size} smith recipes from the cache" }
        return out
    }

    // ================================================================
    // TOOLS
    // ================================================================

    /**
     * Tools this server treats as always held, the way [Skilling.TOOLBELT_BASE] does for the
     * bronze hatchet and pickaxe. The Hammer 2347 is on it because the protocol's player smithed
     * eight items without one in the backpack: the `UPDATE_INV_FULL` at tick 474 is `0:2349x5`
     */
    val TOOLBELT: Set<Int> = setOf(2347)

    val toolbelt: Boolean = System.getProperty("opennxt.smithing.toolbelt") != "off"

    fun hasTool(container: ItemContainer, toolId: Int?): Boolean =
        toolId == null || (toolbelt && toolId in TOOLBELT) || container.contains(toolId)

    // ================================================================
    // WHICH RECIPE - the one invented rule in this file
    // ================================================================

    fun canAfford(container: ItemContainer, materials: List<Material>): Boolean =
        materials.all { container.count(it.itemId) >= it.count.toLong() }

    /**
     * Which bar a "Smelt" click makes when nothing was chosen through the panel.
     */
    val DEFAULT_SMELT_CHOICE: (ItemContainer, Int) -> SmeltRecipe? = { container, level ->
        smeltRecipes.values
            .filter { it.level <= level && hasTool(container, it.toolId) && canAfford(container, it.materials) }
            .maxWithOrNull(compareBy({ it.level }, { it.xpTenths }, { -it.barId }))
    }

    /** Same rule, same reason, for the product an unselected "Heat" click starts. */
    val DEFAULT_SMITH_CHOICE: (ItemContainer, Int) -> SmithRecipe? = { container, level ->
        smithRecipes.values
            .filter { it.level <= level && hasTool(container, it.toolId) && canAfford(container, it.materials) }
            .maxWithOrNull(compareBy({ it.level }, { it.xpTenths }, { -it.productId }))
    }

    val autoSelect: Boolean = System.getProperty("opennxt.smithing.autoSelect") != "off"

    @Volatile var smeltChoice: (ItemContainer, Int) -> SmeltRecipe? = DEFAULT_SMELT_CHOICE
    @Volatile var smithChoice: (ItemContainer, Int) -> SmithRecipe? = DEFAULT_SMITH_CHOICE

    private val selected: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Chooses the recipe the next "Smelt" or "Heat" click will start, by PRODUCT item id.
     *
     * This is the seam the make-X panel (F29) will call when it exists: the reference client's own choice
     * arrives as `IF_BUTTON1` on 37:103 carrying that same item id in `mid`. Returns false when
     * the id names neither a smelt nor a smith recipe.
     */
    fun select(player: ContentPlayer, itemId: Int): Boolean {
        if (!smeltRecipes.containsKey(itemId) && !smithRecipes.containsKey(itemId)) return false
        selected[player] = itemId
        return true
    }

    /** What [select] last chose for this player, or null. */
    fun selectionOf(player: ContentPlayer): Int? = selected[player]

    /** Forgets the choice. Called when a project finishes, so the next click chooses afresh. */
    fun clearSelection(player: ContentPlayer) { selected.remove(player) }

    // ================================================================
    // SEAMS - the same set [Cooking] uses, for the same reasons
    // ================================================================

    @Volatile var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }
    @Volatile var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }
    @Volatile var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }
    @Volatile var inventoryResend: (ContentPlayer) -> Boolean = { false }
    @Volatile var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    @Volatile var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }
    @Volatile var onlineCheck: (ContentPlayer) -> Boolean = { true }
    @Volatile var locationSupplier: (ContentPlayer) -> TileLocation = { it.location }

    val stopOnMove: Boolean = System.getProperty("opennxt.smithing.stopOnMove") != "off"

    // ================================================================
    // THE RUNNING ACTION
    // ================================================================

    enum class Mode { SMELT, HEAT, SMITH }

    enum class Outcome {
        STARTED, SMELTED, HEATED, FULL_HEAT, SWUNG, FINISHED,
        DISABLED, NO_RECIPE, NEED_LEVEL, NEED_TOOL, NEED_MATERIALS, NEED_PROJECT, NO_ROOM, STOPPED
    }

    data class Result(
        val outcome: Outcome,
        val itemId: Int? = null,
        val xpTenths: Int = 0,
        val message: String? = null,
        val detail: String = ""
    ) {
        val ok: Boolean get() = outcome !in REFUSALS

        companion object {
            /** Every outcome that means "nothing happened"; the rest all changed something. */
            val REFUSALS: Set<Outcome> = java.util.EnumSet.of(
                Outcome.DISABLED, Outcome.NO_RECIPE, Outcome.NEED_LEVEL, Outcome.NEED_TOOL,
                Outcome.NEED_MATERIALS, Outcome.NEED_PROJECT, Outcome.NO_ROOM
            )
        }
    }

    /** The unfinished item a player is carrying, and the three numbers the reference client keeps in its frame. */
    data class Project(
        val recipe: SmithRecipe,
        var progress: Int = 0,
        var xpPaidTenths: Int = 0,
        var heat: Int = CREATE_HEAT,
        var stage: Int = 0
    ) {
        val progressLeft: Int get() = recipe.progressTotal - progress
        val xpLeftTenths: Int get() = recipe.xpTenths - xpPaidTenths
    }

    /**
     * A [Project] flattened to five plain numbers, for the account layer to store.
     *
     * ## Why this type exists at all
     *
     * [Project] holds a [SmithRecipe], which is a cache-derived object graph; a save must hold an
     * IDENTITY instead, and [SmithRecipe.productId] is the natural one because [smithRecipes] is
     * keyed by it. So the persisted shape is these five ints.
     *
     * ## Why it is declared HERE and not in the save
     *
     * This module must not depend on the account layer - [com.opennxt.model.account.PlayerSave] is
     * `model`, this is `content`, and the arrow between them points the other way (PlayerSave
     * imports Bank; nothing in the account layer imports a content module). PlayerSave declares its
     * own `SavedProject` with the same five fields and validates them for storage; the two are
     * converted at the ONE seam that legitimately knows both,
     * `com.opennxt.model.world.WorldPlayer`. That is the same two-types-one-seam shape
     * [com.opennxt.model.bank.Bank.BankedItem] and `PlayerSave.SavedItem` already have.
     *
     * A named type rather than five loose Ints on both sides because five same-typed parameters in
     * a row is a transposition waiting to happen, and a transposed `heat`/`stage` would silently
     * pay the wrong xp rate.
     */
    data class ProjectState(
        val productId: Int,
        val progress: Int,
        val xpPaidTenths: Int,
        val heat: Int,
        val stage: Int,
    )

    /**
     * The live project of [player] as five numbers, or null if there is none.
     *
     * Called by `WorldPlayer.toSave` on every autosave and on the leave cull. Null is REAL NEWS
     * there and must clear a stored project - see the reasoning at that call site.
     */
    fun projectSnapshot(player: ContentPlayer): ProjectState? = projects[player]?.let {
        ProjectState(it.recipe.productId, it.progress, it.xpPaidTenths, it.heat, it.stage)
    }

    /**
     * Puts a stored project back, at login. Returns false and changes NOTHING when it cannot.
     *
     * ## What it refuses, and why it never throws
     *
     * [smithRecipes] is derived from `data/rs3.sqlite` at run time, and a save outlives the
     * database it was written against: a re-projected cache can drop or renumber a product. So an
     * unknown [productId] is a NORMAL condition, not a corrupt save - it is logged at WARN and
     * refused. Throwing here would fail a login over a recipe table change, which is the worst
     * possible shape for that: it looks like the account is gone.
     *
     * ## What it clamps
     *
     * [progress] and [xpPaidTenths] are stored; the TOTALS they are measured against are not (see
     * `PlayerSave.smithing`). If the database's `progressTotal` or `xpTenths` shrank under the
     * save, the stored values are clamped to the new totals and the clamp is logged. Unclamped,
     * `Project.progressLeft` would go negative and `smithCycle`'s `coerceAtMost(progressLeft)`
     * would run the swing BACKWARDS.
     *
     * ## What it does NOT check
     *
     * Whether the backpack still holds [UNFINISHED_ITEM]. It cannot: this runs from
     * `WorldPlayer`'s constructor, before the backpack is wired to this module's
     * [containerSupplier]. It does not need to either - [smith] and [smithCycle] both already
     * refuse and drop the project when the item is not held (`NEED_PROJECT`, "the unfinished item
     * is no longer held"), so a project restored over a dropped item self-heals on the first
     * anvil click rather than becoming a phantom.
     *
     * Idempotent for identical input: it REPLACES whatever was in the map, and it moves no items,
     * so a double restore (a relogin racing an autosave, two calls in one tick) duplicates
     * nothing.
     */
    fun restoreProject(
        player: ContentPlayer,
        productId: Int,
        progress: Int,
        xpPaidTenths: Int,
        heat: Int,
        stage: Int
    ): Boolean {
        val recipe = smithRecipes[productId]
        if (recipe == null) {
            logger.warn {
                "smithing: ${player.name} has a stored project for product $productId, which this " +
                    "build's recipe table (${smithRecipes.size} rows from data/rs3.sqlite) does not " +
                    "contain - the project is NOT restored. The unfinished item stays in the backpack."
            }
            return false
        }
        val clampedProgress = progress.coerceIn(0, recipe.progressTotal)
        val clampedXp = xpPaidTenths.coerceIn(0, recipe.xpTenths)
        if (clampedProgress != progress || clampedXp != xpPaidTenths) {
            logger.warn {
                "smithing: ${player.name}'s stored ${recipe.productName} project was written against " +
                    "different totals (progress $progress/${recipe.progressTotal}, xp ${xpPaidTenths}/" +
                    "${recipe.xpTenths} tenths) - clamped to $clampedProgress and $clampedXp."
            }
        }
        projects[player] = Project(
            recipe = recipe,
            progress = clampedProgress,
            xpPaidTenths = clampedXp,
            heat = heat.coerceAtLeast(0),
            stage = stage.coerceAtLeast(0)
        )
        logger.info {
            "smithing: restored ${player.name}'s unfinished ${recipe.productName} " +
                "($clampedProgress/${recipe.progressTotal} progress, heat $heat)"
        }
        return true
    }

    data class Active(
        val mode: Mode,
        val locId: Int,
        val startTile: TileLocation,
        var nextTick: Long,
        val smelt: SmeltRecipe? = null,
        var cycles: Int = 0
    )

    private val active: MutableMap<ContentPlayer, Active> = Collections.synchronizedMap(WeakHashMap())
    private val projects: MutableMap<ContentPlayer, Project> = Collections.synchronizedMap(WeakHashMap())

    /**
     * The tick of each player's last CYCLE, per PLAYER not per action - the cadence
     * [Skilling.lastCycle] and [Cooking] both document. A second click inside the window
     * re-targets the action and still pays at the old cycle's due tick, so no click pattern pays
     * twice inside one cycle.
     */
    private val lastCycle: MutableMap<ContentPlayer, Long> = Collections.synchronizedMap(WeakHashMap())

    private var tickCount: Long = 0
    private var cyclesPaid = 0
    private var actionsStarted = 0
    private var actionsStopped = 0
    private var animationsSent = 0
    private var messagesSent = 0
    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun projectFor(player: ContentPlayer): Project? = projects[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun xpTenthsAwarded(player: ContentPlayer): Int = awarded[player] ?: 0

    /** Per-player throws contained by [tick]'s own try/catch. Never silent; see WorldNpcs.kt:485-491. */
    @Volatile private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    /** Test seam, and the reset a check needs between sections. */
    internal fun clearActions() {
        active.clear(); projects.clear(); lastCycle.clear(); selected.clear(); awarded.clear()
        tickCount = 0; cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0
        animationsSent = 0; messagesSent = 0; containedFailures = 0
    }

    /**
     * This module's seat in the one-action-per-player slot.
     */
    internal object SLOT : ActionSlot.Owner {
        override val actionName = "smithing"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        // Released unconditionally; the identity test inside makes that safe. See Skilling.stop.
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            animate(player, STOP_ANIMATION)
            logger.info { "smithing: ${player.name} stopped ${was.mode} after ${was.cycles} cycles - $why" }
        }
    }

    /**
     * Drops the player's whole smithing state. `World.cullDisconnected` calls this beside the
     * that call. [onlineCheck] is the belt-and-braces second line, not the only one.
     */
    fun cull(player: ContentPlayer, why: String) {
        stopFor(player, why)
        selected.remove(player)
    }

    private fun animate(player: ContentPlayer, ids: IntArray) {
        runCatching { animationSink(player, ids) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, message) }; messagesSent++
    }

    private fun payXp(player: ContentPlayer, tenths: Int) {
        if (tenths <= 0) return
 // Recorded only AFTER the sink returns. (code review, MINOR): the ledger used to
        // be written unconditionally after a `runCatching` whose failure was discarded, so
        // `xpTenthsAwarded` counted xp that never reached a stat container and nothing said so.
        val paid = runCatching { xpSink(player, Stat.SMITHING, tenths / 10.0) }
            .onFailure { logger.warn(it) { "smithing: the xp sink threw for ${player.name} (Smithing, ${tenths / 10.0} xp) - NOT recorded" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + tenths }
    }

    /** The cadence rule shared by all three modes: never pay twice inside one cycle. */
    private fun dueTick(player: ContentPlayer, cycle: Int, first: Int): Long {
        val last = lastCycle[player]
        return if (last != null && tickCount - last < cycle) last + cycle else tickCount + first
    }

    // ---------------------------------------------------------------- SMELT

    /**
     * "Smelt" on a furnace. Starts (or re-targets) the smelt cycle.
     *
     * The nine questions:
     *  - **this tick / next tick**: nothing is smelted here, the first bar lands
     *    [SMELT_FIRST_CYCLE_TICKS] ticks later in [tick]. Only the animation goes out now,
     *    because [tick] has already run for this tick by the time a packet is handled.
     *  - **the actor moves**: [tick] compares the live tile with [Active.startTile] every tick.
     *  - **the request repeats**: [dueTick] keeps the old cadence.
     */
    fun smelt(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        val container = containerSupplier(player)
        val level = levelSupplier(player, Stat.SMITHING)
        val chosen = selectionOf(player)?.let { smeltRecipes[it] }
            ?: (if (autoSelect) smeltChoice(container, level) else null)
            ?: return Result(Outcome.NO_RECIPE, detail = "no smelt recipe chosen and none is affordable")
        if (level < chosen.level)
            return Result(Outcome.NEED_LEVEL, chosen.barId, detail = "Smithing $level < ${chosen.level}")
        if (!hasTool(container, chosen.toolId))
            return Result(Outcome.NEED_TOOL, chosen.barId, detail = "no ${chosen.toolId}")
        if (!canAfford(container, chosen.materials))
            return Result(Outcome.NEED_MATERIALS, chosen.barId, detail = chosen.materials.toString())

        val due = dueTick(player, SMELT_CYCLE_TICKS, SMELT_FIRST_CYCLE_TICKS)
        active[player] = Active(Mode.SMELT, locId, tile, due, smelt = chosen)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        if (due == tickCount + SMELT_FIRST_CYCLE_TICKS &&
            SMELT_FIRST_CYCLE_TICKS == SMELT_ANIMATION_LEAD_TICKS
        ) animate(player, SMELT_ANIMATION)
        logger.info {
            "smithing: ${player.name} smelting ${chosen.barName} at loc $locId " +
                "(level ${chosen.level}, ${chosen.xpTenths / 10.0} xp, ${chosen.materials}); first bar at tick $due"
        }
        return Result(Outcome.STARTED, chosen.barId, detail = "first bar at tick $due")
    }

    /**
     * ONE bar. Materials out first, bar in second - the removal always frees at least as many
     * slots as the add needs, and if the add fails anyway the materials go BACK, so no path
     * through here destroys an ore without producing a bar.
     */
    private fun smeltCycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.smelt ?: return Result(Outcome.STOPPED, detail = "no recipe")
        val container = containerSupplier(player)
        if (!canAfford(container, recipe.materials))
            return Result(Outcome.NEED_MATERIALS, recipe.barId, detail = "out of materials")

        val taken = ArrayList<Material>(recipe.materials.size)
        for (m in recipe.materials) {
            val r = container.remove(m.itemId, m.count)
            taken += Material(m.itemId, m.name, r.removed)
            if (!r.complete) {
                for (t in taken) container.add(t.itemId, t.count)
                return Result(Outcome.NEED_MATERIALS, recipe.barId, detail = "${m.name} short by ${r.shortfall}")
            }
        }
        val add = container.add(recipe.barId, 1)
        if (add.added < 1) {
            for (t in taken) container.add(t.itemId, t.count)
            return Result(Outcome.NO_ROOM, recipe.barId, detail = "no room for ${recipe.barName}")
        }
        inventoryResend(player)
        payXp(player, recipe.xpTenths)
        return Result(Outcome.SMELTED, recipe.barId, recipe.xpTenths)
    }

    // ---------------------------------------------------------------- HEAT

    /**
     * "Heat" on a forge. Creates the unfinished item if the player is not already carrying one,
     * then heats it one step per tick until [heatMax].
     */
    fun heat(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        val existing = projects[player]
        if (existing == null) {
            val created = createProject(player, atAnvil = false)
            if (!created.ok) return created
        }
        active[player] = Active(Mode.HEAT, locId, tile, tickCount + 1)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        return Result(Outcome.STARTED, projects[player]?.recipe?.productId, detail = "heating at loc $locId")
    }

    /**
     * Consumes the materials and puts item [UNFINISHED_ITEM] in the backpack at [CREATE_HEAT].
     *
     * Order is the reference client's: the materials leave the backpack and the unfinished item arrives in the
     * SAME tick as the message (`UPDATE_INV_FULL` and `MESSAGE_GAME` both on tick 495 / 604 /
     * 719 / 1079 / 1336 / 1372).
     */
    private fun createProject(player: ContentPlayer, atAnvil: Boolean): Result {
        val container = containerSupplier(player)
        val level = levelSupplier(player, Stat.SMITHING)
        val recipe = selectionOf(player)?.let { smithRecipes[it] }
            ?: (if (autoSelect) smithChoice(container, level) else null)
            ?: return Result(Outcome.NO_RECIPE, detail = "no smith recipe chosen and none is affordable")
        if (level < recipe.level)
            return Result(Outcome.NEED_LEVEL, recipe.productId, detail = "Smithing $level < ${recipe.level}")
        if (!hasTool(container, recipe.toolId))
            return Result(Outcome.NEED_TOOL, recipe.productId, detail = "no ${recipe.toolId}")
        if (!canAfford(container, recipe.materials))
            return Result(Outcome.NEED_MATERIALS, recipe.productId, detail = recipe.materials.toString())

        val taken = ArrayList<Material>(recipe.materials.size)
        for (m in recipe.materials) {
            val r = container.remove(m.itemId, m.count)
            taken += Material(m.itemId, m.name, r.removed)
            if (!r.complete) {
                for (t in taken) container.add(t.itemId, t.count)
                return Result(Outcome.NEED_MATERIALS, recipe.productId, detail = "${m.name} short by ${r.shortfall}")
            }
        }
        if (container.add(UNFINISHED_ITEM, 1).added < 1) {
            for (t in taken) container.add(t.itemId, t.count)
            return Result(Outcome.NO_ROOM, recipe.productId, detail = "no room for the unfinished item")
        }
        projects[player] = Project(recipe)
        inventoryResend(player)
        val msg = if (atAnvil) createAtAnvilMessage(recipe.productName) else createAtForgeMessage(recipe.productName)
        say(player, msg)
        logger.info {
            "smithing: ${player.name} created an unfinished ${recipe.productName} " +
                "(${recipe.progressTotal} progress, ${recipe.xpTenths / 10.0} xp, ${recipe.materials})"
        }
        return Result(Outcome.STARTED, recipe.productId, message = msg)
    }

    /** ONE heating tick: heat up by [HEAT_GAIN_PER_TICK], clamped, and stop at full. */
    private fun heatCycle(player: ContentPlayer): Result {
        val project = projects[player] ?: return Result(Outcome.NEED_PROJECT)
        val max = heatMax(levelSupplier(player, Stat.SMITHING))
        if (project.heat >= max) return Result(Outcome.FULL_HEAT, project.recipe.productId)
        project.heat = (project.heat + HEAT_GAIN_PER_TICK).coerceAtMost(max)
        animate(player, HEAT_ANIMATION)
        if (project.heat >= max) {
            val msg = fullHeatMessage(project.recipe.productName)
            say(player, msg)
            return Result(Outcome.FULL_HEAT, project.recipe.productId, message = msg)
        }
        return Result(Outcome.HEATED, project.recipe.productId)
    }

    // ---------------------------------------------------------------- SMITH

    /** "Smith" on an anvil. Works the unfinished item the player is carrying. */
    fun smith(player: ContentPlayer, locId: Int, tile: TileLocation): Result {
        if (!enabled) return Result(Outcome.DISABLED, detail = "-Dopennxt.experiment.smithing=false")
        if (projects[player] == null) {
            // The reference client's tick 1192: an anvil click with no project creates one and works it cold,
            // which is why createAtAnvilMessage exists at all.
            val created = createProject(player, atAnvil = true)
            if (!created.ok) return created
        }
        val container = containerSupplier(player)
        if (!container.contains(UNFINISHED_ITEM)) {
            projects.remove(player)
            return Result(Outcome.NEED_PROJECT, detail = "the unfinished item is no longer held")
        }
        val due = dueTick(player, SMITH_CYCLE_TICKS, SMITH_FIRST_CYCLE_TICKS)
        active[player] = Active(Mode.SMITH, locId, tile, due)
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        logger.info { "smithing: ${player.name} working ${projects[player]?.recipe?.productName} at loc $locId; first swing at tick $due" }
        return Result(Outcome.STARTED, projects[player]?.recipe?.productId, detail = "first swing at tick $due")
    }

    /**
     * ONE swing.
     *
     * Order is the reference client's, at every swing tick: the animation and the `UPDATE_INV_PARTIAL`
     * carrying the new progress/xp-remaining/heat, then `UPDATE_STAT`; and on the finishing swing
     * the `MESSAGE_GAME` too.
     *
     * The last swing is CLAMPED to what is left, and that is measured twice over. Bronze
     * platebody: 486 progress of 500 and 22 xp tenths of 750 remained before the swing at tick
     * 687, and the swing added 14 progress (not 20) and paid 22 tenths (not 30). Bronze 2h sword:
     */
    private fun smithCycle(player: ContentPlayer, a: Active): Result {
        val project = projects[player] ?: return Result(Outcome.NEED_PROJECT)
        val container = containerSupplier(player)
        if (!container.contains(UNFINISHED_ITEM)) {
            projects.remove(player)
            return Result(Outcome.NEED_PROJECT, detail = "the unfinished item is no longer held")
        }
        val recipe = project.recipe
        val max = heatMax(levelSupplier(player, Stat.SMITHING))
        val stage = heatStage(project.heat, max)
        project.stage = stage

        val step = progressPerSwing(stage).coerceAtMost(project.progressLeft)
        project.progress += step
        val finishing = project.progress >= recipe.progressTotal
        val xp = if (finishing) project.xpLeftTenths
        else xpTenthsPerSwing(recipe, stage).coerceAtMost(project.xpLeftTenths)
        project.xpPaidTenths += xp
        val heatBefore = project.heat
        project.heat = (project.heat - HEAT_PER_SWING).coerceAtLeast(0)

        animate(player, SMITH_ANIMATION)

        if (finishing) {
            val slot = container.slotOf(UNFINISHED_ITEM)
            if (slot >= 0) container.removeSlot(slot)
            val add = container.add(recipe.productId, 1)
            if (add.added < 1) {
                // Cannot happen with a 28-slot backpack - the line above just freed the slot the
                // product goes into - but if it ever does, the project stays and nothing is lost.
                container.add(UNFINISHED_ITEM, 1)
                project.progress -= step
                project.xpPaidTenths -= xp
                project.heat = heatBefore
                return Result(Outcome.NO_ROOM, recipe.productId, detail = "no room for ${recipe.productName}")
            }
            projects.remove(player)
            clearSelection(player)
            inventoryResend(player)
            payXp(player, xp)
            val msg = finishMessage(recipe.productName)
            say(player, msg)
            return Result(Outcome.FINISHED, recipe.productId, xp, msg)
        }

        inventoryResend(player)
        payXp(player, xp)

        // The cooling messages fire on the heat AFTER the swing, which is why they are here and
        val stageAfter = heatStage(project.heat, max)
        val msg = if (stageAfter > stage) when (stageAfter) {
            1 -> COOLED_SLIGHTLY
            2 -> COOLED_SIGNIFICANTLY
            else -> RAN_OUT_OF_HEAT
        } else null
        if (msg != null) say(player, msg)
        return Result(Outcome.SWUNG, recipe.productId, xp, msg)
    }

    // ---------------------------------------------------------------- the tick

    /**
     * One world tick. The integrator calls this from `World.tick()` beside `Skilling.tick()`
     * (World.kt:299); it returns how many cycles paid so a caller can assert on a number.
     */
    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        // Snapshot INSIDE the wrapper's own monitor.
        //
        // "written from the packet thread while the tick thread walks it" race. **There is no such
        // race in this repository**, and saying there was would have sent the next author looking for a
        // second thread that does not exist. The confinement is structural and is written out in
        // full at `WorldPlayer.kt:91-112`: Netty's event loop only ever enqueues onto a
        // ConcurrentLinkedQueue; `WorldPlayer.handleIncomingPackets` (WorldPlayer.kt:925) drains it
        // and is called only from `World.tick` / `Lobby.tick`; and `TickEngine`'s executor is
        // `newScheduledThreadPool(1)`. [smelt], [heat] and [smith] therefore run on the SAME thread
        // as this loop, never concurrently with it.
        //
        // The snapshot stays, for a reason that is real and local: [stopFor] REMOVES from `active`,
        // and this loop calls it - iterating the live map would be a ConcurrentModificationException
        // on the very first refusal. The `synchronized` block is what makes the copy itself atomic
        // (Collections.synchronizedMap locks every method but not iteration), which costs nothing
        // and is correct if the confinement above is ever broken.
        val snapshot = synchronized(active) { ArrayList(active.entries).map { it.key to it.value } }
        for ((player, a) in snapshot) {
            // exactly this). Without it one player's throw stopped every player after them in the
            // iteration order, AND - because `a.nextTick` only advances after a cycle RETURNS - the
            // throwing action retried on every tick for the life of the process. The `finally`
            // advances the cadence whatever the body did.
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                val here = locationSupplier(player)
                if (stopOnMove &&
                    (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane)
                ) {
                    stopFor(player, "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})")
                    continue
                }
                when (a.mode) {
                    Mode.HEAT -> {
                        val r = heatCycle(player)
                        a.cycles++
                        advanced = true      // HEAT has no nextTick clock: it pays on every tick by design
                        when (r.outcome) {
                            Outcome.HEATED -> paid++
                            Outcome.FULL_HEAT -> { paid++; stopFor(player, "at full heat") }
                            else -> stopFor(player, "heat refused: ${r.outcome} ${r.detail}")
                        }
                    }
                    Mode.SMELT -> {
                        if (tickCount < a.nextTick) continue
                        val r = smeltCycle(player, a)
                        lastCycle[player] = tickCount
                        a.cycles++
                        if (r.outcome == Outcome.SMELTED) {
                            paid++; cyclesPaid++
                            a.nextTick = tickCount + SMELT_CYCLE_TICKS
                            advanced = true
                            if (!canAfford(containerSupplier(player), a.smelt!!.materials)) {
                                say(player, DEPOSIT_BARS)
                                stopFor(player, "out of materials for ${a.smelt.barName}")
                            } else {
                                // The animation leads its bar by a WHOLE cycle, so the one for the
                                // next bar rides the tick this one paid: the reference client's animation ticks are
                                // 424/428/432/436/440 against bars at 428/432/436/440/444 - the start
                                // tick and then every paying tick but the last.
                                animate(player, SMELT_ANIMATION)
                            }
                        } else { stopFor(player, "smelt refused: ${r.outcome} ${r.detail}"); advanced = true }
                    }
                    Mode.SMITH -> {
                        if (tickCount < a.nextTick) continue
                        val r = smithCycle(player, a)
                        lastCycle[player] = tickCount
                        a.cycles++
                        advanced = true
                        when (r.outcome) {
                            Outcome.SWUNG -> { paid++; cyclesPaid++; a.nextTick = tickCount + SMITH_CYCLE_TICKS }
                            Outcome.FINISHED -> { paid++; cyclesPaid++; stopFor(player, "finished ${r.itemId}") }
                            else -> stopFor(player, "swing refused: ${r.outcome} ${r.detail}")
                        }
                    }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "smithing: ${player.name}'s ${a.mode} threw in the smithing phase; contained, the cadence " +
                        "was advanced so it cannot retry every tick, and the other players still ticked."
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) {
                    a.nextTick = tickCount + when (a.mode) {
                        Mode.SMELT -> SMELT_CYCLE_TICKS
                        Mode.SMITH -> SMITH_CYCLE_TICKS
                        Mode.HEAT -> SMITH_CYCLE_TICKS
                    }
                }
            }
        }
        return paid
    }

    // ---------------------------------------------------------------- registration

    /**
     * Binds the three cache action strings. Returns how many loc ids that is in total.
     *
     * Bound by ACTION, not by id, so every furnace, forge and anvil in the corpus works and not
     * only the three the protocol visited: 44 + 14 + 61 = 119 loc ids as the database stands.
     */
    fun install(): Int {
        if (!enabled) {
            logger.warn { "smithing: disabled by -Dopennxt.experiment.smithing=false; nothing is bound" }
            return 0
        }
        var bound = 0
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(SMELT_ACTION) { ctx ->
                smelt(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$SMELT_ACTION' is not bindable" }; 0 }
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(HEAT_ACTION) { ctx ->
                heat(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$HEAT_ACTION' is not bindable" }; 0 }
        bound += runCatching {
            com.opennxt.content.ContentRegistry.onLocAction(SMITH_ACTION) { ctx ->
                smith(ctx.player, ctx.locId, locationSupplier(ctx.player))
            }
        }.getOrElse { logger.warn(it) { "smithing: '$SMITH_ACTION' is not bindable" }; 0 }
        logger.info {
            "smithing: bound $bound loc id(s) across '$SMELT_ACTION'/'$HEAT_ACTION'/'$SMITH_ACTION'; " +
                "${smeltRecipes.size} smelt recipes, ${smithRecipes.size} smith recipes"
        }
        return bound
    }
}
