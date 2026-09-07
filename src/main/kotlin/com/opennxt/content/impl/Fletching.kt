package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.ItemContainer
import com.opennxt.resources.sqlite.CacheEnums
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.util.Collections
import java.util.WeakHashMap

/**
 * FLETCHING - the `Craft` backpack row on a log, built.
 */
object Fletching {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON, like [Bury.enabled]. */
    val enabled: Boolean get() = System.getProperty("opennxt.experiment.fletching") != "false"

    // ================================================================
    // THE CONSTANTS
    // ================================================================

    const val CRAFT_ACTION = "Craft"

    /** The item the measured run cut. `items.name` 1511 = "Logs". */
    const val LOGS_ITEM = 1511

    /** What it produced. `items.name` 52 = "Shaft", and `stackable_1` is set, which is why 15 of them landed in one slot. */
    const val SHAFT_ITEM = 52

    /**
     * `items.name` 53 = "Headless shaft" - the product of feathering a [SHAFT_ITEM] and the
     * material of every ordinary arrow. Its own `widget_actions_0` is `Tip`, and the wire measured
     * both: `IF_BUTTON1 1473:5 item 52` answered "You attach feathers to 5 shafts." and
     * `IF_BUTTON1 1473:5 item 53` answered with five `882 Bronze arrow`
     */
    const val HEADLESS_SHAFT_ITEM = 53

    /** How many per log. 6/6 (x15, x30, x45, x60, x75, x90 in one slot) and stated by the line itself. */
    const val SHAFT_COUNT = 15

    /** Fletching xp for one `Logs`, in TENTHS. UPDATE_STAT stat 9 moved 216 -> 241 in six steps of 5. */
    const val LOGS_XP_TENTHS = 50

    /** The skill. `Stat.FLETCHING` is id 9, which is the stat id the wire's UPDATE_STAT carried. */
    val STAT: Stat = Stat.FLETCHING

    const val MEASURED_MESSAGE = "You carefully cut the wood into 15 shafts."

    /**
     * The `MESSAGE_GAME` type the reference client stamps on it: **109**, not the 0 the rest of this repository passes.
     */
    const val MESSAGE_TYPE = 109

    /**
     * THREE, not the 4 of [Skilling.CYCLE_TICKS]. Gathering and cooking run on a 4-tick clock;
     * this is a production action and it runs on a 3-tick one, which is also what the wiki's own
     * `ticks` field says for this recipe - two independent sources agreeing on the one row the wire
     * could check.
     */
    val CYCLE_TICKS: Int = System.getProperty("opennxt.fletching.cycleTicks")?.toIntOrNull()?.coerceIn(1, 100) ?: 3

    /**
     * Ticks from the START of the action to the FIRST cut. : the server answered the
     * make-X confirmation on 683 and the first cut landed on 686. Here the start is the `Craft`
     * click tick (see the class doc on the panel this module does not build).
     */
    val FIRST_CYCLE_TICKS: Int = System.getProperty("opennxt.fletching.firstCycle")?.toIntOrNull()?.coerceIn(1, 100) ?: 3

    /**
     * All four animation slots, delay 0, on the tick each cycle starts. See the class doc for the
     * control: 24938 occurs on exactly six ticks in the whole 2,334-tick observation, and they are this
     * action's six cycle starts.
     */
    val CUT_ANIMATION: IntArray = intArrayOf(24938, 24938, 24938, 24938)

    /** The delay the reference client sent with it. 0. */
    const val CUT_ANIMATION_DELAY = 0

    /** The block the reference client sent on the LAST cut tick (701), ending the action. */
    val STOP_ANIMATION: IntArray = intArrayOf(-1, -1, -1, -1)

    /**
     * The delay on that stop block: **20**, not the 0 every other module in this repository stops with.
     * once (tick 701), so it is carried through the seam rather than flattened - a module
     * that dropped it would be sending a block the reference client did not send.
     */
    const val STOP_ANIMATION_DELAY = 20

    /** The wiki module this table is read out of. */
    const val WIKI_SKILL = "Fletching"

    // ================================================================
    // THE TABLE
    // ================================================================

    /**
     * ONE ingredient of ONE batch, from the wiki row's own `material` array.
     *
     * [perBatch] is the quantity the wiki states for a batch of [Product.count] outputs: the
     * `Headless shaft` row is `[15, "Shaft", 15, "Feather"]` with multiplier 15, so 15 shafts and
     * 15 feathers make 15 headless shafts. A PARTIAL batch of `n` consumes `ceil(perBatch * n /
     * count)` of each - which is what the wire measured: 5 feathers made exactly 5 headless shafts
     * (09-07T05-17-54 t1418, `5:52x85 6:53x5 8:-`), and 5 headless shafts made exactly 5 bronze
     * arrows out of a stack of arrowheads that fell by 5 (t1454, `4:39x90 6:882x5`).
     */
    data class Input(val itemId: Int, val name: String, val perBatch: Int)

    /**
     * One product of one material.
     *
     * [ticks] is the wiki's own cadence for the recipe; it AGREES with the measured 3 on the row
     * the wire covers ([MEASURED_RECIPE]) and is the only cadence source for the rest.
     */
    data class Product(
        val category: String,
        val name: String,
        val itemId: Int,
        val level: Int,
        val xpTenths: Int,
        val count: Int,
        val ticks: Int,
        val source: String,
        val inputs: List<Input> = emptyList(),
        val gridSlot: Int = -1,
        val categoryIndex: Int = -1
    )

    /**
     * One material and everything the wiki says can be made from one of it.
     *
     * [default] is what this module makes, because the product panel is not built: the product
     * whose item id is [SHAFT_ITEM], else a shaft-family product, else null. A null [default] means
     * the material is in the table for the record but a `Craft` click on it is refused - see
     * [Outcome.NO_PRODUCT] - rather than being silently given a product nobody chose.
     */
    data class Recipe(
        val logId: Int,
        val logName: String,
        val products: List<Product>,
        val default: Product?
    )

    /**
     * The ONE row the wire measured, kept separate from the wiki so it survives
     * `-Dopennxt.seed.skillxp=off` and so the check can compare the two.
     */
    val MEASURED_RECIPE = Product(
        category = "Unfinished projectiles",
        name = "Shaft",
        itemId = SHAFT_ITEM,
        level = 1,
        xpTenths = LOGS_XP_TENTHS,
        count = SHAFT_COUNT,
        ticks = CYCLE_TICKS,
        source = "(the reference client 949 wire)"
    )

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("skill_xp_wiki.json")

    /** lowercase item name -> the lowest id carrying it. One pass over `items`, memoised. */
    private val namesToIds: Map<String, Int> by lazy {
        if (!RsDatabase.available) emptyMap()
        else {
            val out = HashMap<String, Int>()
            RsDatabase.queryAll("SELECT id, name FROM items WHERE name IS NOT NULL") {
                it.getInt(1) to it.getString(2)
            }.forEach { (id, name) ->
                val key = name.lowercase()
                val prev = out[key]
                if (prev == null || id < prev) out[key] = id
            }
            out
        }
    }

    internal fun itemIdOf(name: String): Int? = namesToIds[name.lowercase()]

    /** Every item id this cache gives a "[CRAFT_ACTION]" menu row, lowest first. DERIVED. */
    fun craftableItemIds(): List<Int> = ItemActions.idsWithAction(CRAFT_ACTION)

    /** How many there are. Observable so a check asserts a number rather than a list. */
    fun craftableItemCount(): Int = craftableItemIds().size

    /** True when [itemId] declares the craft row at all - the server's own eligibility test. */
    fun isCraftable(itemId: Int): Boolean = ItemActions.slotsWithAction(itemId, CRAFT_ACTION).isNotEmpty()

    /**
     * material item id -> [Recipe]. Built on first use, from the wiki seed joined to the cache's own
     * `Craft` census; see the class doc for the join rule and why it excludes gems.
     *
     * A memo field rather than a `by lazy` for the reason [ItemActions.invalidate] gives: a lazy
     * cannot be re-derived, and [invalidateRecipes] has to mean something when a check flips
     * `-Dopennxt.seed.skillxp`.
     */
    @Volatile
    private var recipeTable: Map<Int, Recipe>? = null

    val recipes: Map<Int, Recipe>
        get() = recipeTable ?: buildRecipes().also { recipeTable = it }

    /** Test seam: forget the table so a property flip is visible. */
    internal fun invalidateRecipes() {
        recipeTable = null
        joinStats = JoinStats(0, 0, 0, 0, 0)
        panelTable = null
        panelRecipeMemo.clear()
        panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)
    }

    /** How many wiki rows were rejected by each clause of the join, so the check can pin the filter. */
    class JoinStats(
        val singleMaterialRows: Int,
        val rejectedNotCraftable: Int,
        val rejectedNoTicks: Int,
        val rejectedUnknownItem: Int,
        val accepted: Int
    )

    @Volatile
    private var joinStats: JoinStats = JoinStats(0, 0, 0, 0, 0)

    fun joinStats(): JoinStats { recipes; return joinStats }

    private fun buildRecipes(): Map<Int, Recipe> {
        if (!SkillXpWiki.enabled) {
            logger.warn {
                "fletching: -Dopennxt.seed.skillxp=off, so the wiki table is not read; only the " +
                    "Logs -> Shaft row is available"
            }
            return measuredOnlyTable()
        }
        if (!Files.exists(seedPath)) {
            logger.warn { "fletching: no $seedPath - only the Logs -> Shaft row is available" }
            return measuredOnlyTable()
        }
        val root = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        }.getOrElse {
            logger.error(it) { "fletching: $seedPath is unreadable - refusing to substitute defaults" }
            return measuredOnlyTable()
        }
        val categories = runCatching {
            root.getAsJsonObject("skills").getAsJsonObject(WIKI_SKILL).getAsJsonObject("categories")
        }.getOrNull()
        if (categories == null) {
            logger.error { "fletching: $seedPath holds no $WIKI_SKILL categories - only the row is available" }
            return measuredOnlyTable()
        }

        var single = 0
        var notCraftable = 0
        var noTicks = 0
        var unknown = 0
        val byMaterial = LinkedHashMap<Int, MutableList<Product>>()
        val materialNames = HashMap<Int, String>()

        for ((category, arr) in categories.entrySet()) {
            for (el in arr.asJsonArray) {
                val row = el as? JsonObject ?: continue
                val material = row.getAsJsonArray("material") ?: continue
                // ONE material, taken ONE at a time. A two-material recipe (shaft + feather) is a
                // different click - an item-on-item, not a backpack menu row - and is not this
                // module's action at all.
                if (material.size() != 2) continue
                val quantity = runCatching { material[0].asDouble }.getOrNull() ?: continue
                if (quantity != 1.0) continue
                val materialName = runCatching { material[1].asString }.getOrNull() ?: continue
                single++
                val materialId = itemIdOf(materialName)
                if (materialId == null || !isCraftable(materialId)) { notCraftable++; continue }
                val ticks = intOrNull(row, "ticks")
                if (ticks == null) { noTicks++; continue }
                val productName = runCatching { row.get("name").asString }.getOrNull()
                val productId = productName?.let { itemIdOf(it) }
                if (productName == null || productId == null) { unknown++; continue }
                val level = intOrNull(row, "level") ?: 1
                val xpTenths = Math.round((runCatching { row.get("xp").asDouble }.getOrNull() ?: 0.0) * 10.0).toInt()
                val count = intOrNull(row, "multiplier") ?: 1
                byMaterial.getOrPut(materialId) { ArrayList() }.add(
                    Product(category, productName, productId, level, xpTenths, count, ticks,
                        "WIKI (Module:Skill calc/$WIKI_SKILL/data, $category)")
                )
                materialNames[materialId] = materialName
            }
        }

        val out = LinkedHashMap<Int, Recipe>()
        for ((materialId, products) in byMaterial.entries.sortedBy { it.key }) {
            val sorted = products.sortedWith(compareBy({ it.level }, { it.itemId }))
            val name = Skilling.itemNameOf(materialId) ?: materialNames[materialId] ?: "item $materialId"
            out[materialId] = Recipe(materialId, name, sorted, defaultProductOf(materialId, sorted))
        }
        // The wire outranks the wiki wherever both speak, exactly as SkillingRates outranks it for
        // xp. They agree on all four numbers here, so this substitution changes nothing today; it
        // is what keeps the label on the row and what would make a wiki edit visible
        // rather than silent.
        out[LOGS_ITEM]?.let { logs ->
            val replaced = logs.products.map { if (it.itemId == SHAFT_ITEM) MEASURED_RECIPE else it }
            out[LOGS_ITEM] = Recipe(logs.logId, logs.logName, replaced, defaultProductOf(LOGS_ITEM, replaced))
        }
        joinStats = JoinStats(single, notCraftable, noTicks, unknown, out.values.sumOf { it.products.size })
        logger.info {
            "fletching: ${out.size} material(s) with ${out.values.sumOf { it.products.size }} product row(s); " +
                "${out.values.count { it.default != null }} have a shaft-family default and are clickable today; " +
                "the wiki join rejected $notCraftable non-Craft, $noTicks with no tick count, $unknown unresolvable " +
                "of $single single-material rows"
        }
        return out
    }

    /** The table with the wiki removed: the one row the wire measured. */
    private fun measuredOnlyTable(): Map<Int, Recipe> {
        joinStats = JoinStats(0, 0, 0, 0, 1)
        val name = Skilling.itemNameOf(LOGS_ITEM) ?: "Logs"
        return mapOf(LOGS_ITEM to Recipe(LOGS_ITEM, name, listOf(MEASURED_RECIPE), MEASURED_RECIPE))
    }

    /**
     * Which product a `Craft` click makes, with no panel to ask.
     */
    internal fun defaultProductOf(materialId: Int, products: List<Product>): Product? {
        products.filter { it.itemId == SHAFT_ITEM }.minByOrNull { it.level }?.let { return it }
        return products.filter { it.name.lowercase().contains("shaft") }
            .minWithOrNull(compareBy({ it.level }, { it.itemId }))
            ?: run {
                logger.debug { "fletching: material $materialId has no shaft-family product; a Craft click on it is refused" }
                null
            }
    }

    private fun intOrNull(o: JsonObject?, key: String): Int? =
        o?.get(key)?.takeIf { !it.isJsonNull }?.asInt

    // ================================================================
    // THE PANEL TABLE - the make-X dropdown, the product grid, and the two-ingredient recipes
    // ================================================================
    //
    // Added after the report that (a) "the multiple types of wood don't populate
    // when you go to them - it just stays as normal wood" and (b) "fletching arrows doesn't work,
    // same with the bow". Both are the same hole: this module knew ONE product of ONE material.
    //
    // (Derived; no bytes
    // are copied into this repository):
    //
    //  * varp 1168 is an ENUM ID whose entries are themselves enum ids - one per dropdown row -
    //    and varp 7881 is the parallel enum of that dropdown's NAMES. For fletching wood they are
    //    6939 and 6940, and 6940 reads exactly what the operator saw: "Normal Wood", "Achey",
    //    "Oak", "Willow", "Teak", "Maple", "Acadia", "Mahogany", "Yew", "Magic", "Blisterwood",
 // "Elder", "Eternal Magic", "Clockwork", "Shavings", "Other". (cache).
    //  * varp 1169 is `enum(1168)[categoryIndex]` - the product list the CLIENT then draws.
    //    of the cooking panel (1168 = 6794) and the server answered varp 1169 = 6800, 6802, 7739
    //    and 6796 - which are entries 2, 7, 9 and 0 of enum 6794, in that order.
    // * the dropdown is TWO frames. `IF_BUTTON1 1371:28` opens it and reference answers with
    //    **nothing at all** (4 of 4); the chosen row arrives as `IF_BUTTON1 1477:896 arg2=<index>`
    // on the gameframe's own dropdown host, which the reference client arms `0.1000 mask 2` at login
    //    and two skills: cooking slots 1/5/9 answered with enum 6796 entries 0/1/2, and fletching
    //    slots 1/5 answered with enum 6947 entries 0 (54895) and 1 (52).
    //  * the grid is armed `0 .. 4 * entryCount`: 6 products -> 24, 34 -> 136, 4 -> 16.
    //    which is that database being a projection of an OLDER build than `data/cache`
    //    nothing.
    //
    // THE THREE PANELS, and why there are three rather than a rule. There is no cache
    // table anywhere that maps a material to its (1168, 7881) pair - searched: 6943 and 6945 are
    // the VALUE of no enum entry in the whole database. That mapping lives in the reference client's own scripts,
    // so it is measured or it is invented, and these three are measured.
    /**
     * material item id -> (the varp-1168 category enum, the varp-7881 name enum).
     */
    val MEASURED_PANELS: Map<Int, Pair<Int, Int>> = linkedMapOf(
        LOGS_ITEM to (6939 to 6940),
        SHAFT_ITEM to (6943 to 6944),
        HEADLESS_SHAFT_ITEM to (6945 to 6946)
    )

    /** One dropdown row: a name, the product enum behind it, and the material that owns it. */
    data class PanelCategory(
        val index: Int,
        val name: String,
        val productEnum: Int,
        /** How many entries the product enum has; the grid is armed `0 .. STRIDE * this`. */
        val entryCount: Int,
        /** The material this category is made FROM, or -1 when the join could not name one. */
        val materialId: Int,
        val materialName: String,
        val products: List<Product>
    )

    /**
     * One material's make-X panel: the category enum, the name enum, which row it opens on, and
     * every category the dropdown offers.
     */
    data class Panel(
        val materialId: Int,
        val materialName: String,
        /** The backpack option string this panel answers to - the material's OWN row 0. */
        val action: String,
        val categoryEnum: Int,
        val nameEnum: Int,
        val defaultIndex: Int,
        val categories: List<PanelCategory>,
        /** Whether [MEASURED_PANELS] names this material, or it inherited the pair from one that does. */
        val measured: Boolean
    ) {
        val products: List<Product> get() = categories.flatMap { it.products }
        fun categoryAt(index: Int): PanelCategory? = categories.getOrNull(index)
    }

    /**
     * Whether the wood family inherits the Logs panel's (6939, 6940) pair. Default ON.
     */
    val panelInheritance: Boolean get() = System.getProperty("opennxt.fletching.panelInherit") != "false"

    /** The grid stride. 5/5: product index i sits at grid slot `4 * i + 1`. */
    const val GRID_STRIDE = 4

    fun gridSlotOf(productIndex: Int): Int = GRID_STRIDE * productIndex + 1

    /** The inverse, or -1 when the slot is not on the stride - which is a refusal, not a guess. */
    fun productIndexOf(gridSlot: Int): Int =
        if (gridSlot >= 1 && (gridSlot - 1) % GRID_STRIDE == 0) (gridSlot - 1) / GRID_STRIDE else -1

    @Volatile
    private var panelTable: Map<Int, Panel>? = null

    val panels: Map<Int, Panel>
        get() = panelTable ?: buildPanels().also { panelTable = it }

    /** How the panel join went, so a check pins numbers rather than a shape. */
    class PanelStats(
        val panels: Int,
        val measuredPanels: Int,
        val inheritedPanels: Int,
        val categories: Int,
        val categoriesWithOwner: Int,
        val products: Int
    )

    @Volatile
    private var panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)

    fun panelStats(): PanelStats { panels; return panelStatsValue }

    /** The option string a click on [itemId] carries: the item's OWN row 0, out of this cache. */
    fun actionFor(itemId: Int): String? = ItemActions.actionAt(itemId, 0)

    /**
     * The backpack option string THIS MODULE answers to for [itemId].
     *
     * A material with a derived make-X panel answers to that panel's own row-0 option - `Feather`
     */
    fun actionOf(itemId: Int): String = panelFor(itemId)?.action ?: CRAFT_ACTION

    /** Whether a `(action, item)` backpack click is this module's. The [FletchingWiring] hook's test. */
    fun claims(action: String, itemId: Int): Boolean = action.equals(actionOf(itemId), ignoreCase = true)

    /**
     * One wiki row per (product name, material) pair, or null when the pair is ambiguous or absent.
     *
     * Only rows with at most two ingredients and a tick count qualify - the same two clauses
     * [buildRecipes] uses, for the same reasons: a three-ingredient recipe is a different click
     * shape this module does not model, and a row with no tick count would need an invented cadence.
     */
    private class WikiRow(
        val category: String,
        val name: String,
        val level: Int,
        val xpTenths: Int,
        val count: Int,
        val ticks: Int,
        val inputs: List<Input>
    )

    private fun readWikiRows(): List<WikiRow> {
        if (!SkillXpWiki.enabled || !Files.exists(seedPath)) return emptyList()
        val categories = runCatching {
            JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
                .getAsJsonObject("skills").getAsJsonObject(WIKI_SKILL).getAsJsonObject("categories")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<WikiRow>()
        for ((category, arr) in categories.entrySet()) {
            for (el in arr.asJsonArray) {
                val row = el as? JsonObject ?: continue
                val material = row.getAsJsonArray("material") ?: continue
                if (material.size() < 2 || material.size() % 2 != 0) continue
                // AT MOST TWO ingredients. Three is a different click (the wiki's "- Scratch"
                // variants build from a bar), and nothing in the corpus shows one on this panel.
                if (material.size() > 4) continue
                val ticks = intOrNull(row, "ticks") ?: continue
                val name = runCatching { row.get("name").asString }.getOrNull() ?: continue
                val count = intOrNull(row, "multiplier") ?: 1
                val inputs = ArrayList<Input>()
                var bad = false
                var i = 0
                while (i + 1 < material.size()) {
                    val qty = runCatching { material[i].asDouble }.getOrNull()
                    val mName = runCatching { material[i + 1].asString }.getOrNull()
                    val mId = mName?.let { itemIdOf(it) }
                    // A fractional quantity is a bar-per-batch figure the backpack cannot express.
                    if (qty == null || mName == null || mId == null || qty < 1.0 || qty != Math.floor(qty)) {
                        bad = true; break
                    }
                    inputs.add(Input(mId, mName, qty.toInt()))
                    i += 2
                }
                if (bad || inputs.isEmpty()) continue
                val xpTenths = Math.round((runCatching { row.get("xp").asDouble }.getOrNull() ?: 0.0) * 10.0).toInt()
                out.add(WikiRow(category, name, intOrNull(row, "level") ?: 1, xpTenths, count, ticks, inputs))
            }
        }
        return out
    }

    /**
     * The panel table: the cache's own enums, joined to the wiki's rows by PRODUCT NAME, with the
     * clicked material required to be one of the row's ingredients.
     *
     * The join, and the control inside each clause:
     *
     * 1. every category of every measured panel is walked; its products come from the cache enum,
     *     never from a list;
     *  2. the category's OWNING MATERIAL is the item id that (a) appears as an ingredient of the
     *     wiki rows named after that category's products, (b) declares the SAME row-0 backpack
     * option as the panel's measured material, and (c) is strictly the most frequent such id.
     *     Clause (b) is what stops `Feather` (which has no backpack row at all) from being read as
     * the material of "Feather Arrows" when the measured click was on the `Shaft`; clause (c)
     *     rejects a tie rather than picking one, which is why Teak, Mahogany, Clockwork, Shavings
     *     and Other end up with no owner and no panel;
     *  3. a product binds only when EXACTLY ONE wiki row is named after it AND lists the owning
     *     material as an ingredient. Everything else stays drawn by the client (it builds the grid
     *     from varp 1169 and this server cannot stop it) and is REFUSED on click.
     */
    private fun buildPanels(): Map<Int, Panel> {
        val wiki = readWikiRows()
        if (wiki.isEmpty()) {
            logger.warn { "fletching: no wiki rows, so no make-X panel is derived; the panel-less table is all there is" }
            panelStatsValue = PanelStats(0, 0, 0, 0, 0, 0)
            return emptyMap()
        }
        val byName = HashMap<String, MutableList<WikiRow>>()
        for (r in wiki) byName.getOrPut(r.name.lowercase()) { ArrayList() }.add(r)

        val out = LinkedHashMap<Int, Panel>()
        var categories = 0
        var withOwner = 0
        var products = 0

        for ((measuredMaterial, pair) in MEASURED_PANELS) {
            val action = actionFor(measuredMaterial) ?: continue
            val (categoryEnum, nameEnum) = pair
            val catValues = CacheEnums.entries(categoryEnum)
            val catNames = CacheEnums.entries(nameEnum)
            if (catValues.isEmpty()) {
                logger.warn { "fletching: category enum $categoryEnum is empty in this cache - no panel for item $measuredMaterial" }
                continue
            }
            val built = ArrayList<PanelCategory>()
            for ((position, entry) in catValues.withIndex()) {
                val index = entry.key.toIntOrNull() ?: position
                val productEnum = entry.value.toIntOrNull() ?: continue
                val name = catNames.getOrNull(position)?.value?.trim('"') ?: "category $index"
                val enumProducts = CacheEnums.entries(productEnum)
                categories++

                // (2) who owns this category?
                val votes = HashMap<Int, Int>()
                for (p in enumProducts) {
                    val pid = p.value.toIntOrNull() ?: continue
                    val pName = Skilling.itemNameOf(pid) ?: continue
                    for (row in byName[pName.lowercase()].orEmpty()) {
                        for (input in row.inputs) {
                            if (actionFor(input.itemId)?.equals(action, ignoreCase = true) != true) continue
                            votes[input.itemId] = (votes[input.itemId] ?: 0) + 1
                        }
                    }
                }
                val top = votes.values.maxOrNull()
                val winners = votes.filterValues { it == top }.keys
                val owner = if (top != null && winners.size == 1) winners.first() else -1

                // (3) bind the products of an owned category
                val bound = ArrayList<Product>()
                if (owner > 0) {
                    withOwner++
                    for ((slotIndex, p) in enumProducts.withIndex()) {
                        val pid = p.value.toIntOrNull() ?: continue
                        val pName = Skilling.itemNameOf(pid) ?: continue
                        val hits = byName[pName.lowercase()].orEmpty()
                            .filter { row -> row.inputs.any { it.itemId == owner } }
                        if (hits.size != 1) continue
                        val row = hits.first()
                        bound.add(
                            Product(
                                category = name,
                                name = pName,
                                itemId = pid,
                                level = row.level,
                                xpTenths = row.xpTenths,
                                count = row.count,
                                ticks = row.ticks,
                                source = "WIKI x CACHE (enum $productEnum slot $slotIndex, Module:Skill calc/$WIKI_SKILL/data, ${row.category})",
                                inputs = row.inputs,
                                gridSlot = gridSlotOf(p.key.toIntOrNull() ?: slotIndex),
                                categoryIndex = index
                            )
                        )
                    }
                }
                products += bound.size
                built.add(
                    PanelCategory(
                        index = index,
                        name = name,
                        productEnum = productEnum,
                        entryCount = enumProducts.size,
                        materialId = owner,
                        materialName = if (owner > 0) (Skilling.itemNameOf(owner) ?: "item $owner") else "",
                        products = bound
                    )
                )
            }

            // Every owning material with at least one bound product gets a panel of its own, opened
            // on ITS row of the dropdown. The measured material keeps the measured flag; the rest
            // inherit the pair and say so.
            for (category in built) {
                if (category.materialId <= 0 || category.products.isEmpty()) continue
                val isMeasured = category.materialId == measuredMaterial
                if (!isMeasured && !panelInheritance) continue
                if (out.containsKey(category.materialId)) continue
                val ownAction = actionFor(category.materialId) ?: continue
                out[category.materialId] = Panel(
                    materialId = category.materialId,
                    materialName = category.materialName,
                    action = ownAction,
                    categoryEnum = categoryEnum,
                    nameEnum = nameEnum,
                    defaultIndex = category.index,
                    categories = built,
                    measured = isMeasured
                )
            }
        }

        panelStatsValue = PanelStats(
            panels = out.size,
            measuredPanels = out.values.count { it.measured },
            inheritedPanels = out.values.count { !it.measured },
            categories = categories,
            categoriesWithOwner = withOwner,
            products = products
        )
        logger.info {
            "fletching: ${out.size} make-X panel(s) derived - ${out.values.count { it.measured }} with a " +
                "varp (1168,7881) pair and ${out.values.count { !it.measured }} inheriting one; $categories " +
                "dropdown categories over ${MEASURED_PANELS.size} enums, $withOwner of them with an owning " +
                "material, $products product rows bound to a wiki recipe. The rest are drawn by the client " +
                "(varp 1169 builds the grid) and refused on click."
        }
        return out
    }

    /** The panel for [materialId], or null when this server has not derived one. */
    fun panelFor(materialId: Int): Panel? = panels[materialId]

    /**
     * The [Recipe] a running action carries for [materialId]: the panel-less wiki table first (so
     * every pre-panel assertion still sees exactly what it saw), then a synthetic one over the
     * panel's products.
     */
    fun recipeFor(materialId: Int): Recipe? = recipes[materialId] ?: panelRecipe(materialId)

    private val panelRecipeMemo = java.util.concurrent.ConcurrentHashMap<Int, Recipe>()

    private fun panelRecipe(materialId: Int): Recipe? {
        val panel = panels[materialId] ?: return null
        return panelRecipeMemo.getOrPut(materialId) {
            val all = panel.products
            Recipe(materialId, panel.materialName, all, defaultPanelProductOf(panel))
        }
    }

    /**
     * What a freshly-opened panel selects.
     *
     * 2 of 2: the Logs panel opened with varp 1170 = 52 (`Shaft`, grid slot 5 - NOT the
     * grid's slot-1 `Wood box`, which this server cannot make), and the Shaft panel opened with
     * varp 1170 = 53 (`Headless shaft`, grid slot 1). Both are the LOWEST-slot product of the
     * default category that this server has bound to a recipe.
     */
    fun defaultPanelProductOf(panel: Panel): Product? =
        panel.categoryAt(panel.defaultIndex)?.products?.minByOrNull { it.gridSlot }
            ?: panel.products.minByOrNull { it.gridSlot }

    /**
     * The line for one cut.
     *
     * exactly for `15 shafts`; the noun is the product's own cache name lowercased and
     * pluralised, which reproduces the measured string from the table rather than hard-coding it.
     */
    fun messageFor(product: Product, count: Int): String {
        val noun = product.name.lowercase()
        val plural = if (count == 1 || noun.endsWith("s")) noun else "${noun}s"
        return "You carefully cut the wood into $count $plural."
    }

    // ================================================================
    // THE SEAMS - the same shape [Bury] and [Cooking] use, for the same reason
    // ================================================================

    /** Where the logs are taken from. Default the ContentPlayer's own inventory, so a check needs no World. */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /** The player's Fletching level. Default 1. */
    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    /** Where the xp goes on top of [xpTenthsAwarded]. Default a no-op. */
    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    /** `(player, type, message)` - the type is [MESSAGE_TYPE], not 0. */
    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    /** `(player, ids, delay)` - the delay is carried because the measured stop block has one. */
    @Volatile
    var animationSink: (ContentPlayer, IntArray, Int) -> Unit = { _, _, _ -> }

    /** Push the backpack to the client. Returns whether anything was sent. */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    /**
     * Whether the player is still in the world. Default true (a headless check has no World).
     * [FletchingWiring] points it at `World.isOnline`, so a disconnect stops the action on the next
     * tick even if the cull path is ever removed. The same belt and braces [Cooking.onlineCheck] has.
     */
    @Volatile
    var onlineCheck: (ContentPlayer) -> Boolean = { true }

    /**
     * Opens the MAKE-X PANEL for a `Craft` click that has passed every guard. Returns true when
     * the panel took the click, in which case [craft] returns [Outcome.PANEL] and starts nothing.
     */
    @Volatile
    var panelOpener: (ContentPlayer, Recipe, Product, Int) -> Boolean = { _, _, _, _ -> false }

    /** Puts every seam back the way a check needs them. */
    fun resetSeams() {
        containerSupplier = { it.inventory }
        levelSupplier = { _, _ -> 1 }
        xpSink = { _, _, _ -> }
        messageSink = { _, _, _ -> }
        animationSink = { _, _, _ -> }
        inventoryResend = { false }
        onlineCheck = { true }
        panelOpener = { _, _, _, _ -> false }
    }

    // ================================================================
    // THE RUNNING ACTION
    // ================================================================

    enum class Outcome {
        /** The action is armed; the first cut lands [FIRST_CYCLE_TICKS] ticks later. */
        STARTED,

        /** One log cut into its product. */
        MADE,

        /** Not a craft: the action string was something else, or fletching is off. [ItemOps] keeps the click. */
        NOT_MINE,

        /** The item declares no `Craft` row in this cache. Server-authoritative refusal. */
        NOT_CRAFTABLE,

        /** The item declares `Craft` but this module has no fletching recipe for it. */
        NO_RECIPE,

        /**
         * Everything a [STARTED] needs was true and the MAKE-X PANEL was opened instead - which is
         * what the reference client does (`IF_OPENSUB` 1370/1371, 09-07T01-06-17 t368). No log is consumed and no
         * cadence is armed until [MakeXPanel]'s confirm calls [startFromPanel].
         *
         * Reachable only when [panelOpener] is bound, which [FletchingWiring] does and a headless
         * check does not - so every pre-panel assertion still sees [STARTED].
         */
        PANEL,

        /** There is a recipe but no shaft-family product, so nothing can be chosen without the panel. */
        NO_PRODUCT,

        /** The clicked slot no longer holds the clicked item - a stale, forged or duplicated click. */
        STALE_CLICK,

        /** The player's Fletching level is below the product's requirement. */
        NEED_LEVEL,

        /** No log of that id is left in the backpack. */
        OUT_OF_LOGS,

        /** The product does not fit; the log is put back exactly as it was and the action stops. */
        FULL,
    }

    data class Result(
        val outcome: Outcome,
        val recipe: Recipe? = null,
        val product: Product? = null,
        val count: Int = 0,
        val xpTenths: Int = 0,
        val message: String? = null,
        val detail: String = ""
    )

    data class Active(
        val recipe: Recipe,
        val product: Product,
        /** The slot the click named. The reference client took the clicked slot's log first, then the next one. */
        val startSlot: Int,
        var nextTick: Long,
        var cycles: Int = 0,
        var made: Int = 0,
        /**
         * How many cuts this action may pay before it stops itself, from the make-X panel's count
         * (varp 8846). [Int.MAX_VALUE] is the pre-panel behaviour - cut until the logs run out -
         * and is what every path that does not go through [MakeXPanel] still gets.
         */
        val limit: Int = Int.MAX_VALUE
    )

    /**
     * Weakly keyed, like [Cooking.active]: a player who logs out mid-cut must be collectable from
     * here even if nothing ever calls [stopFor]. ContentPlayer does not override equals/hashCode,
     * so a WeakHashMap is an identity map anyway.
     */
    private val active: MutableMap<ContentPlayer, Active> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Active>())

    /**
     * The tick of each player's last CYCLE, per PLAYER not per action - the cadence
     * [Cooking.lastCycle] documents and [Skilling.lastCycle] measured. A second `Craft` click
     * inside the 3-tick window re-targets the action and still pays at the OLD cycle's due tick, so
     * no click pattern can pay twice in three ticks.
     */
    private val lastCycle: MutableMap<ContentPlayer, Long> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())

    private val awarded: MutableMap<ContentPlayer, Int> = Collections.synchronizedMap(WeakHashMap())

    @Volatile private var tickCount: Long = 0
    @Volatile private var cyclesPaid = 0
    @Volatile private var actionsStarted = 0
    @Volatile private var actionsStopped = 0
    @Volatile private var animationsSent = 0
    @Volatile private var messagesSent = 0

    /** Per-player throws contained by [tick]'s own try/catch. Never silent; see WorldNpcs.kt:485-491. */
    @Volatile private var containedFailures = 0

    fun ticks(): Long = tickCount
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]
    fun cyclesPaid(): Int = cyclesPaid
    fun actionsStarted(): Int = actionsStarted
    fun actionsStopped(): Int = actionsStopped
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun containedFailures(): Int = containedFailures
    fun xpTenthsAwarded(player: ContentPlayer): Int = synchronized(awarded) { awarded[player] ?: 0 }

    /** Test seam, and the reset a check needs between sections. */
    internal fun clearActions() {
        active.clear(); lastCycle.clear(); synchronized(awarded) { awarded.clear() }
        tickCount = 0
        cyclesPaid = 0; actionsStarted = 0; actionsStopped = 0
        animationsSent = 0; messagesSent = 0; containedFailures = 0
    }

    internal object SLOT : ActionSlot.Owner {
        override val actionName = "fletching"
        override fun cancelSlot(player: ContentPlayer, why: String) = stopFor(player, why)
    }

    /**
     * Public stop, for the reason [Cooking.stopFor] is public: a culled player must not leave an
     * action behind that cuts logs out of a backpack nobody is holding.
     */
    fun stopFor(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        // Released unconditionally; the identity test inside ActionSlot.release is what makes that
        // safe when this is reached re-entrantly from ActionSlot.claim.
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info { "fletching: ${player.name} stopped after ${was.cycles} cycle(s), ${was.made} cut - $why" }
        }
    }

    private fun animate(player: ContentPlayer, ids: IntArray, delay: Int) {
        runCatching { animationSink(player, ids, delay) }; animationsSent++
    }

    private fun say(player: ContentPlayer, message: String) {
        runCatching { messageSink(player, MESSAGE_TYPE, message) }; messagesSent++
    }

    /**
     * A backpack `Craft` click.
     *
     * [action] is the CACHE's option string for the row the client sent, which is what [ItemOps]
     * already resolved from the item's own `widget_actions_*`; this module never decides that row 1
     * means craft.
     *
     * SERVER-AUTHORITATIVE, in this order, because the order is what makes the failure paths safe:
     *
     *  1. the row string must be [CRAFT_ACTION];
     *  2. the item must declare that row in THIS cache - a hand-built packet naming a cabbage is
     *     refused here, not trusted because the client said so;
     *  3. there must be a recipe, and a product the panel-less path may choose;
     *  4. the clicked slot must still hold the clicked item;
     *  5. the level must be enough.
     *
     * Nothing is consumed here. The first log is cut [FIRST_CYCLE_TICKS] ticks later, in [tick],
     * which is the reference client's 683 -> 686.
     */
    fun craft(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, detail = "-Dopennxt.experiment.fletching=false")
 // THE ACTION IS THE MATERIAL'S OWN, DERIVED. Until today this module answered
        // only to "Craft", which is why the operator's arrows and bows did nothing: the cache gives
        // `Shaft` (52) the backpack row `Feather` and `Headless shaft` (53) the row `Tip`, and both
        // arrive as IF_BUTTON1 on 1473:5 exactly as `Craft` does. on both -
        // `IF_BUTTON1 1473:5 item 52 slot 5` answered "You attach feathers to 5 shafts." and
        // `IF_BUTTON1 1473:5 item 53 slot 6` answered with five `882 Bronze arrow`
        // (09-07T05-17-54 t1418 and t1454).
        //
        // [actionOf] is a CACHE LOOKUP, not a list. A material with a make-X panel answers to that
        // panel's own row; everything else keeps [CRAFT_ACTION], so every pre-panel refusal fires
        // exactly as it did.
        val expected = actionOf(itemId)
        if (!action.equals(expected, ignoreCase = true))
            return Result(Outcome.NOT_MINE, detail = "action '$action' is not item $itemId's fletching row ($expected)")
        if (ItemActions.slotsWithAction(itemId, action).isEmpty()) {
            logger.info { "fletching: ${player.name}'s item $itemId declares no '$action' row in this cache - refused." }
            return Result(Outcome.NOT_CRAFTABLE, detail = "item $itemId has no $action row")
        }

 // THE PANEL LAYER. The panel-less wiki table is consulted FIRST, so every
        // material that had a recipe before today still resolves to exactly the row it resolved to
        // (which is how `Logs -> 15 x Shaft` keeps its label). A material with only a
        // panel - `Shaft` and `Headless shaft`, whose wiki rows take TWO ingredients and were
        // therefore invisible to [buildRecipes] - resolves through [panelRecipe].
        val panel = panelFor(itemId)
        val recipe = recipeFor(itemId) ?: run {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${itemName ?: "item $itemId"} - it is one of the " +
                    "${runCatching { ItemActions.countWithAction(action) }.getOrDefault(-1)} items offering that " +
                    "row but this module has no fletching recipe and no make-X panel for it."
            }
            return Result(Outcome.NO_RECIPE, detail = "no fletching recipe for item $itemId")
        }
        val product = recipe.default ?: panel?.let { defaultPanelProductOf(it) } ?: run {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${recipe.logName} - it has " +
                    "${recipe.products.size} product(s) but none this server may choose without a panel. Refused."
            }
            return Result(Outcome.NO_PRODUCT, recipe, detail = "no default product for ${recipe.logName}")
        }

        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "fletching: ${player.name}: slot $slot is outside the backpack - refused." }
            return Result(Outcome.STALE_CLICK, recipe, product, detail = "slot $slot is outside the backpack")
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            // The same stale-click guard ItemOps uses, for the same reason: the client's view can
            // lag a container change by a tick and must not start an action on whatever moved in.
            logger.info {
                "fletching: ${player.name}'s '$action' refused - the client says item $itemId is in slot $slot, " +
                    "the server has ${held?.id ?: "nothing"} there."
            }
            return Result(Outcome.STALE_CLICK, recipe, product, detail = "slot $slot holds ${held?.id ?: "nothing"}")
        }

        val level = levelSupplier(player, STAT)
        if (level < product.level) {
            say(player, "You need a Fletching level of ${product.level} to make ${product.name.lowercase()}.")
            logger.info {
                "fletching: ${player.name} refused - Fletching $level < ${product.level} for ${product.name} " +
                    "(${product.source}). The refusal LINE is INVENTED; the reference client's was not recorded."
            }
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "level $level < ${product.level}")
        }

 // THE MAKE-X PANEL. The reference client does not start anything on the Craft click: it opens
        // 1370/1371 and waits for RESUME_PAUSEBUTTON 1370:30 (09-07T01-06-17 t363 -> t368 -> t378).
        // LAST of the guards, so every refusal above still fires exactly as it did before the panel
        // existed - the panel is only ever offered for a click that WOULD have started an action.
        if (runCatching { panelOpener(player, recipe, product, slot) }
                .onFailure { logger.error(it) { "fletching: the make-X panel opener threw for ${player.name}; falling back to the immediate cut" } }
                .getOrDefault(false)
        ) {
            logger.info {
                "fletching: ${player.name} clicked '$action' on ${recipe.logName} - the make-X panel is open " +
                    "and NOTHING has started. The action begins on the confirm (RESUME_PAUSEBUTTON 1370:30)."
            }
            return Result(Outcome.PANEL, recipe, product, product.count, detail = "make-X panel opened")
        }

        return start(player, recipe, product, slot, Int.MAX_VALUE, "the make-X panel is not bound")
    }

    /**
     * The make-X panel's confirm: start the action for exactly [count] cuts of [productItemId].
     *
     * Everything is RE-VALIDATED here rather than carried from the open, because the panel can sit
     * open for any number of ticks: the recipe, the product's presence in it, the level, and the
     * material still being in the backpack. A [count] larger than the material held is not an
     * error - the action simply runs out of logs first and [tick] stops it, which is what the reference client's
     * own progress dialogue does.
     */
    fun startFromPanel(
        player: ContentPlayer,
        materialId: Int,
        productItemId: Int,
        slot: Int,
        count: Int
    ): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, detail = "-Dopennxt.experiment.fletching=false")
        val recipe = recipeFor(materialId)
            ?: return Result(Outcome.NO_RECIPE, detail = "no fletching recipe for item $materialId")
        // The panel-less table FIRST, so `Logs -> Shaft` keeps its row when both tables
        // hold it; then the panel's own products, which is where a bow or an arrow comes from.
        val product = recipe.products.firstOrNull { it.itemId == productItemId }
            ?: recipe.default?.takeIf { it.itemId == productItemId }
            ?: panelFor(materialId)?.products?.firstOrNull { it.itemId == productItemId }
            ?: return Result(
                Outcome.NO_PRODUCT, recipe,
                detail = "item $productItemId is not a product of ${recipe.logName}"
            )
        val container = containerSupplier(player)
        // Every ingredient, not just the clicked material - a two-ingredient recipe with no
        // feathers left must refuse here rather than start an action that stops on its first cycle.
        for (input in inputsOf(recipe, product)) {
            if (container.count(input.itemId) <= 0L)
                return Result(
                    Outcome.OUT_OF_LOGS, recipe, product,
                    detail = "${input.name} (item ${input.itemId}) is gone"
                )
        }
        val level = levelSupplier(player, STAT)
        if (level < product.level) {
            say(player, "You need a Fletching level of ${product.level} to make ${product.name.lowercase()}.")
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "level $level < ${product.level}")
        }
        // The panel's slot is where the material WAS; if it moved, cycle() falls back to slotOf.
        val startSlot = slot.takeIf { it in 0 until container.size } ?: container.slotOf(materialId)
        return start(player, recipe, product, startSlot, count.coerceAtLeast(1), "make-X confirm")
    }

    /**
     * Arms the action. The one place that writes [active], so the cadence rule and the first
     * animation cannot drift between the panel path and the panel-less one.
     */
    private fun start(
        player: ContentPlayer,
        recipe: Recipe,
        product: Product,
        slot: Int,
        limit: Int,
        why: String
    ): Result {
        // The cadence is the PLAYER's, not the action's: a second click inside the window keeps the
        // old due tick and only re-targets. Without this, alternating two log types would cut one
        // log per packet instead of one per CYCLE_TICKS. Same rule as [Cooking.start].
        val last = lastCycle[player]
        val due = if (last != null && tickCount - last < CYCLE_TICKS) last + CYCLE_TICKS
        else tickCount + FIRST_CYCLE_TICKS
        active[player] = Active(recipe, product, slot, due, limit = limit)
        // Takes the player's one action slot, stopping whatever else they had running.
        ActionSlot.claim(player, SLOT)
        actionsStarted++
        // The reference client's first animation rides the tick the action starts (683), which cannot come from
        // tick(): World.tick runs the skilling phase BEFORE handleIncomingPackets, so this tick's
        // tick() has already run by the time the click is decoded. Sent here for exactly that
        // reason, and only when this click actually armed a fresh cycle.
        if (due == tickCount + FIRST_CYCLE_TICKS) animate(player, CUT_ANIMATION, CUT_ANIMATION_DELAY)
        logger.info {
            "fletching: ${player.name} started ${recipe.logName} -> ${product.count} x ${product.name} " +
                "(level ${product.level}, ${product.xpTenths / 10.0} xp, ${product.source}); first cut at tick $due; " +
                "$why; limit " +
                (if (limit == Int.MAX_VALUE) "NONE - cuts until the logs run out" else "$limit cut(s), the make-X count")
        }
        return Result(Outcome.STARTED, recipe, product, product.count, detail = "first cut at tick $due")
    }

    /**
     * ONE cycle: consume one log, produce [Product.count] of the product, pay the xp.
     *
     * ORDER IS THE REFERENCE CLIENT'S: on all six cut ticks: the chat line, then the inventory change,
     * then the xp write.
     *
     * OWNERSHIP INVARIANT. The log is removed first and the product added second, so the add can
     * always use the slot the removal freed; when the add still cannot place the whole batch, every
     * unit it did place is taken back out and the ORIGINAL stack is written back into its slot, so
     * no path through here destroys or duplicates anything. That last case is reachable only with a
     * stackable material in a full backpack - which is why it is written rather than argued away.
     */
    private fun cycle(player: ContentPlayer, a: Active): Result {
        val recipe = a.recipe
        val product = a.product
        val container = containerSupplier(player)
        val inputs = inputsOf(recipe, product)

        val level = levelSupplier(player, STAT)
        if (level < product.level)
            return Result(Outcome.NEED_LEVEL, recipe, product, detail = "Fletching $level < ${product.level}")

        // HOW MANY THIS CYCLE MAKES. A full batch is [Product.count]; a short ingredient shrinks it,
        // which is exactly what the wire measured twice on 09-07T05-17-54: five feathers made five
        // headless shafts out of a batch of fifteen (t1418), and five headless shafts made five
        // bronze arrows (t1454). See [Input.perBatch].
        val made = batchSizeFor(container, product, inputs)
        if (made <= 0) {
            val short = inputs.firstOrNull { container.count(it.itemId) <= 0L }
            return Result(
                Outcome.OUT_OF_LOGS, recipe, product,
                detail = "${short?.name ?: recipe.logName} is gone"
            )
        }

        // THE ROLLBACK SNAPSHOT. With two ingredients there is no single "put the stack back", so
        // the whole container is snapshotted before the first write and restored wholesale if the
        // product does not fit. `toArray` copies the backing array and `Item` is immutable, so this
        // is a true before-image and the restore cannot half-apply.
        val before = container.toArray()

        // The reference client emptied the CLICKED slot first and then the next slots in order, so the clicked
        // slot is preferred for the material the click named; `slotOf` is the fallback.
        for (input in inputs) {
            var need = ceilDiv(input.perBatch * made, product.count)
            if (input.itemId == recipe.logId) {
                val clicked = a.startSlot.takeIf {
                    it in 0 until container.size && container[it]?.id == input.itemId
                }
                if (clicked != null) {
                    val held = container[clicked]!!
                    // ONE UNIT AT A TIME, never `removeSlot`: that would take a whole stack of a
 // stackable material and pay for one of it - the defect the economy
                    // audit found in Firemaking and Cooking.
                    val take = minOf(need, held.amount)
                    val remainder = held.minus(take)
                    if (remainder == null) container.removeSlot(clicked) else container[clicked] = remainder
                    need -= take
                }
            }
            if (need > 0) {
                val removed = container.remove(input.itemId, need)
                if (removed.removed < need) {
                    for (i in 0 until container.size) container[i] = before[i]
                    return Result(
                        Outcome.OUT_OF_LOGS, recipe, product,
                        detail = "only ${removed.removed} of ${input.name} could be taken; nothing was consumed"
                    )
                }
            }
        }

        val add = container.add(product.itemId, made)
        if (add.added < made) {
            // Put everything back exactly as it was. Restoring the SNAPSHOT rather than undoing
            // each step is what makes this correct with two ingredients: no ordering, no arithmetic,
            // and nothing that can be created or destroyed on the way.
            for (i in 0 until container.size) container[i] = before[i]
            return Result(
                Outcome.FULL, recipe, product,
                detail = "no room for $made x ${product.itemId}; every ingredient was restored"
            )
        }

        lineFor(product, made)?.let { say(player, it) }
        runCatching { inventoryResend(player) }
        // Recorded only AFTER the sink returns, so the ledger is a record of deliveries rather than
 // of intentions - the code-review point that Cooking and Firemaking both carry.
        // A PARTIAL BATCH PAYS A PARTIAL SHARE. five headless shafts out of a fifteen
        // batch paid 5 of the row's 15 xp (UPDATE_STAT stat 9 241 -> 246, 09-07T05-17-54 t1418),
        // and five bronze arrows out of a fifteen batch paid 6.5 of the row's 19.5 (246 -> 253,
        // t1454 - the wire's integer total rounds 252.5 up). A full batch is unchanged, which is
        // why the six measured Logs cuts still pay exactly 5.0 each.
        val awardTenths =
            if (made >= product.count) product.xpTenths
            else Math.round(product.xpTenths.toDouble() * made / product.count).toInt()
        val paid = runCatching { xpSink(player, STAT, awardTenths / 10.0) }
            .onFailure { logger.warn(it) { "fletching: the xp sink threw for ${player.name} (Fletching, ${awardTenths / 10.0} xp) - NOT recorded" } }
            .isSuccess
        if (paid) synchronized(awarded) { awarded[player] = (awarded[player] ?: 0) + awardTenths }
        a.made++
        return Result(Outcome.MADE, recipe, product, made, awardTenths, lineFor(product, made))
    }

    /** Integer ceiling division; both arguments are positive here. */
    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) 0 else (a + b - 1) / b

    /**
     * The ingredients of one batch.
     */
    fun inputsOf(recipe: Recipe, product: Product): List<Input> =
        if (product.inputs.isNotEmpty()) product.inputs
        else listOf(Input(recipe.logId, recipe.logName, 1))

    /**
     * How many outputs one cycle makes with what the player is holding.
     *
     * `n = min(count, min_i(have_i * count / perBatch_i))`, integer throughout. on three
     * independent cases and it reproduces all three:
     *
     *  - one `Logs`, count 15, `perBatch` 1 -> `1 * 15 / 1 = 15`, capped at 15 -> **15 shafts for
     *    one log** (09-07T01-06-17 t686 and five more);
     *  - 90 `Shaft` and 5 `Feather`, count 15, `perBatch` 15 each -> `min(90, 5) = 5` -> **5
     *    headless shafts**, and the wire's inventory shows exactly `52 x85` and `53 x5`
     *    (09-07T05-17-54 t1418);
     *  - 95 `Bronze arrowheads` and 5 `Headless shaft` -> **5 bronze arrows**, arrowheads down to
     *    90 (t1454).
     */
    fun batchSizeFor(
        container: ItemContainer,
        product: Product,
        inputs: List<Input>
    ): Int {
        var n = product.count
        for (input in inputs) {
            if (input.perBatch <= 0) continue
            val have = container.count(input.itemId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val possible = (have.toLong() * product.count / input.perBatch).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (possible < n) n = possible
        }
        return n.coerceAtLeast(0)
    }

    /**
     * How many WHOLE-OR-PARTIAL cycles the player can pay for - the reference client's varp 8846.
     *
     * A partial cycle counts as one, because the wire showed a partial cycle actually running (5
     * of a 15 batch, twice). Zero means the panel opens with `varc 2223 = 0` and the confirm does
     * nothing, which is the state the reference client was in at 09-07T01-06-17 t47.
     */
    fun cyclesAvailable(container: ItemContainer, recipe: Recipe, product: Product): Int {
        val inputs = inputsOf(recipe, product)
        var total = Int.MAX_VALUE
        for (input in inputs) {
            if (input.perBatch <= 0) continue
            val have = container.count(input.itemId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val possible = (have.toLong() * product.count / input.perBatch).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (possible < total) total = possible
        }
        if (total == Int.MAX_VALUE || total <= 0) return 0
        return ceilDiv(total, product.count.coerceAtLeast(1))
    }

    /**
     * The chat line for one cycle, or **null** when the reference client sent none.
     *
     * Two lines are and nothing else is invented:
     * - a shaft-family product out of a log: `You carefully cut the wood into N shafts.`
     */
    fun lineFor(product: Product, count: Int): String? = when {
        product.itemId == HEADLESS_SHAFT_ITEM -> "You attach feathers to $count shafts."
        product.itemId == SHAFT_ITEM || product.name.lowercase().contains("shaft") ->
            messageFor(product, count)
        else -> null
    }

    /**
     * One world tick. Returns how many cuts were paid on it, so the caller - and a check - assert on
     * a number rather than on a side effect.
     *
     * The integrator calls this from `World.tick()` beside `Cooking.tick()`.
     */
    fun tick(): Int {
        tickCount++
        if (active.isEmpty()) return 0
        var paid = 0
        for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
            // PER-PLAYER CONTAINMENT, the shape of `WorldNpcs.tick` (WorldNpcs.kt:485-491) that the
            // every player after them in the iteration order from ticking. The `finally` closes the
            // hot loop - without it a throwing cycle would retry on EVERY tick for the life of the
            // process instead of once every CYCLE_TICKS, cutting (and destroying) logs at 1/tick.
            var advanced = false
            try {
                if (!onlineCheck(player)) { stopFor(player, "no longer online"); continue }
                if (tickCount < a.nextTick) continue
                val r = cycle(player, a)
                lastCycle[player] = tickCount
                a.cycles++
                when (r.outcome) {
                    Outcome.MADE -> {
                        paid++; cyclesPaid++
                        a.nextTick = tickCount + CYCLE_TICKS
                        advanced = true
                        val container = runCatching { containerSupplier(player) }.getOrNull()
                        if (a.made >= a.limit) {
                            // THE MAKE-X COUNT, reached. Same stop block as running out of logs -
                            // the reference client's progress dialogue closes and the (-1,-1,-1,-1) delay-20
                            // animation lands on the tick of the last cut either way.
                            animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                            stopFor(player, "the make-X count of ${a.limit} is made")
                        } else if (container == null ||
                            batchSizeFor(container, a.product, inputsOf(a.recipe, a.product)) <= 0
                        ) {
                            // The last log: the reference client's progress interface closes three ticks later but
                            // no further cut ever lands, and the tick of the last cut is the one that
                            // carried the (-1,-1,-1,-1) delay 20 stop block.
                            animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                            stopFor(
                                player,
                                "out of ${inputsOf(a.recipe, a.product).firstOrNull { i ->
                                    container == null || container.count(i.itemId) <= 0L
                                }?.name ?: a.recipe.logName} after ${a.made} cycle(s)"
                            )
                        } else {
                            // The next cycle starts on this same tick, which is why the reference client's cut
                            // ticks 686..698 each carry an animation and 701 does not.
                            animate(player, CUT_ANIMATION, CUT_ANIMATION_DELAY)
                        }
                    }
                    else -> {
                        advanced = true
                        animate(player, STOP_ANIMATION, STOP_ANIMATION_DELAY)
                        stopFor(player, "cycle refused: ${r.outcome} ${r.detail}")
                    }
                }
            } catch (t: Throwable) {
                containedFailures++
                logger.error(t) {
                    "fletching: ${player.name}'s cut threw in the fletching phase; contained, the cadence was " +
                        "advanced so it cannot retry every tick, and the other players still ticked."
                }
            } finally {
                if (!advanced && tickCount >= a.nextTick) a.nextTick = tickCount + CYCLE_TICKS
            }
        }
        return paid
    }
}
