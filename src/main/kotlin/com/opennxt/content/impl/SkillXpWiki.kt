package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files

/**
 * The WIKI layer for skilling xp and levels.
 */
object SkillXpWiki {

    private val logger = KotlinLogging.logger {}

    /** Read per call so a check can flip it; the seeds themselves load once. */
    val enabled: Boolean get() = System.getProperty("opennxt.seed.skillxp") != "off"

    data class Row(
        val skill: String,
        val category: String,
        val name: String,
        val level: Int?,
        val xpTenths: Int,
        val source: String
    )

    class Seed(val skills: Map<String, Map<String, List<Row>>>, val revisions: Map<String, Int>) {
        val rowCount: Int get() = skills.values.sumOf { cats -> cats.values.sumOf { it.size } }
        val rowsWithLevelAndXp: Int get() = skills.values.sumOf { cats -> cats.values.sumOf { rows -> rows.count { it.level != null } } }
    }

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("skill_xp_wiki.json")
    private val chancePath = Constants.DATA_PATH.resolve("seed").resolve("skill_chance_wiki.json")

    val seed: Seed by lazy { load() }

    /** The woodcutting tree table keyed by the LOGS item name: "Oak logs" -> level 10, 37.5 xp. */
    val trees: Map<String, Row> by lazy { loadTrees() }

    /**
     * The success and depletion data per tree, keyed by logs (`Module:SkillUtils/TreeData`):
     * `chance` (the "chop chance", x10000 inside the formula), `ratio` (high/low, 31250 = x3.125
     * for every tree so far), `fell` (x/256 per successful chop that the tree becomes a stump;
     * -1 = never).
     */
    data class TreeChance(val logs: String, val name: String, val chance: Double, val ratio: Int, val fell: Int)
    val treeChance: Map<String, TreeChance> by lazy { loadTreeChance() }

    /** Hatchet power per ITEM name (`Module:SkillUtils/HatchetData`): Bronze 100 ... Dragon 800 ... Primal 1265. */
    data class Hatchet(val item: String, val level: Int, val power: Int)
    val hatchets: Map<String, Hatchet> by lazy { loadHatchets() }

    private fun loadTreeChance(): Map<String, TreeChance> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val trees = root.getAsJsonObject("trees") ?: return emptyMap()
        val out = LinkedHashMap<String, TreeChance>()
        for (t in trees.getAsJsonArray("data")) {
            val o = t as? JsonObject ?: continue
            val logs = o.get("logs")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val chance = o.get("chance")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
            val ratio = o.get("ratio")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 31250
            val fell = o.get("fell")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: -1
            if (logs !in out) out[logs] = TreeChance(logs, o.get("name")?.asString ?: logs, chance, ratio, fell)
        }
        return out
    }

    private fun loadHatchets(): Map<String, Hatchet> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val data = root.getAsJsonObject("hatchets")?.getAsJsonObject("data") ?: return emptyMap()
        val all = data.get("hatchets.all_hatchets") ?: return emptyMap()
        val entries = if (all.isJsonArray) all.asJsonArray.toList() else all.asJsonObject.entrySet().map { it.value }
        val out = LinkedHashMap<String, Hatchet>()
        for (e in entries) {
            val o = e as? JsonObject ?: continue
            val label = o.get("label")?.asString ?: continue
            val power = o.get("power")?.asInt ?: continue
            val level = o.get("level")?.asInt ?: 1
            // the module names the item by `image` when it is not "<Label> hatchet"
            val item = o.get("image")?.takeIf { it.isJsonPrimitive }?.asString ?: "$label hatchet"
            out[item] = Hatchet(item, level, power)
        }
        return out
    }

    /** The wiki's own interpolation (`Module:SkillUtils.jagex_interpolate`): NOT monotone, by design. */
    fun jagexInterpolate(low: Long, high: Long, level: Int): Long =
        Math.floorDiv(low * (99 - level), 98L) + Math.floorDiv(high * (level - 1), 98L)

    /**
     * Woodcutting success chance per cycle (`Module:SkillUtils.woodcutting_chance`, rev 36640342):
     *
     *     c    = chance x 10000
     *     low  = c + floor( floor(c/2) x (power - 100) / 100 )
     *     high = floor( low x ratio / 10000 )
     *     P    = (jagexInterpolate(low, high, level) + 1) / (256 x 10000), capped at 1
     *
     * Null when the wiki names neither the tree nor the hatchet. No buffs (auras, outfits,
     * familiars) - the calculator's multipliers are not modelled.
     */
    fun woodcuttingChance(logs: String, hatchetItem: String, level: Int): Double? {
        val tree = treeChance[logs] ?: return null
        val hatchet = hatchets[hatchetItem] ?: return null
        val c = Math.round(tree.chance * 10000)
        val tierIncrement = c / 2
        val low = c + Math.floorDiv(tierIncrement * (hatchet.power - 100), 100L)
        val high = Math.floorDiv(low * tree.ratio, 10000L)
        val raw = (jagexInterpolate(low, high, level) + 1).toDouble() / (256.0 * 10000.0)
        return raw.coerceIn(0.0, 1.0)
    }

 // ------------------------------------------------------------------ fishing
    //
 // Everything from here to `fellChance` was for [Fishing] and changes no
    // existing reading: `skill_chance_wiki.json` already carried a `fishing` block (rev 37011174,
    // `Module:Fishing chance calculator/data`) that nothing in the tree had ever read, and this is
    // the reading of it. No line above or below was edited.

    /**
     * One row of `Module:Fishing chance calculator/data`, keyed by the ITEM name the wiki's `image`
     * field carries ("Raw crayfish"), which is the same key [Fishing.Method.itemName] uses.
     *
     * [low] and [high] are out of 256 and are the level-1 and level-99 ends of the interpolation.
     * [delayTicks] is the wiki's own roll cadence, `delay or 4` game ticks - an independent
     * corroboration of [Fishing.CYCLE_TICKS], since the protocol measured 4 as well.
     */
    data class FishChance(
        val item: String, val name: String, val level: Int, val low: Int, val high: Int,
        val tool: String?, val spot: String?, val xpTenths: Int, val delayTicks: Int
    )

    val fish: Map<String, FishChance> by lazy { loadFish() }

    private fun loadFish(): Map<String, FishChance> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val block = root.getAsJsonObject("fishing") ?: return emptyMap()
        val out = LinkedHashMap<String, FishChance>()
        for (e in block.getAsJsonArray("data") ?: return emptyMap()) {
            val o = e as? JsonObject ?: continue
            val item = o.get("image")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val low = o.get("low")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: continue
            val high = o.get("high")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: continue
            val level = o.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 1
            val xp = o.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 0.0
            val delay = o.get("delay")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 4
            // several spots list the same fish; the first row is the base one, as for trees
            if (item !in out) out[item] = FishChance(
                item, o.get("name")?.asString ?: item, level, low, high,
                o.get("tool")?.takeIf { it.isJsonPrimitive }?.asString,
                o.get("spot")?.takeIf { it.isJsonPrimitive }?.asString,
                tenths(xp), delay
            )
        }
        return out
    }

    /**
     * The fishing module's OWN interpolation, which is NOT [jagexInterpolate].
     *
     * `Module:Fishing chance calculator` (stored at `data/wiki/xp/raw/Module_Fishing_chance_calculator.lua`)
     * floors the whole expression ONCE:
     *
     *     floor( ( (99-lvl)*low + (lvl-1)*high ) / 98 )
     *
     * while `Module:SkillUtils.jagex_interpolate`, which [woodcuttingChance] uses, floors each of
     * the two terms SEPARATELY. The two disagree by up to 1 out of 256. Reusing the woodcutting
     * one here would be reading the wrong module, so it is written out rather than shared.
     */
    fun fishingInterpolate(low: Long, high: Long, level: Int): Long =
        Math.floorDiv((99L - level) * low + (level - 1L) * high, 98L)

    /**
     * Fishing success chance per roll, `interpolate(low, high, level) / 256`, capped at 1.
     * Null when the wiki does not name the fish. No buffs - the calculator's chompas, outfits,
     * familiars, juju, rings and honed multipliers are not modelled.
     *
     * **THIS IS CONTRADICTED BY THE WIRE for the one fish anybody has caught on camera** - it says
     * 200/256 = 78.1% for a crayfish at Fishing 1 and the protocol paid 6 of 50 cycles. See
     * [Fishing.MEASURED_CHANCE], which outranks it, and the class doc of [Fishing] for the
     * arithmetic. The formula is kept because it is the only thing that will price a net or a rod
     * before somebody fishes one on camera, and because a layer that is wrong for one row and
     * silent for the rest is still better than nothing labelled AUTHORED.
     */
    fun fishingChance(item: String, level: Int): Double? {
        val f = fish[item] ?: return null
        return (fishingInterpolate(f.low.toLong(), f.high.toLong(), level).toDouble() / 256.0).coerceIn(0.0, 1.0)
    }

    /** How many fish rows the seed carried. Observable so a check asserts on a number. */
    fun fishCount(): Int = fish.size

    /** Chance that a successful chop turns the tree into a stump: fell/256; null when the wiki says never (-1) or does not name the tree. */
    fun fellChance(logs: String): Double? {
        val t = treeChance[logs] ?: return null
        if (t.fell < 0) return null
        return t.fell / 256.0
    }

    private fun tenths(x: Double): Int = Math.round(x * 10.0).toInt()

    private fun load(): Seed {
        if (!Files.isRegularFile(seedPath)) {
            logger.warn { "skilling xp wiki seed absent: $seedPath - the WIKI layer is empty" }
            return Seed(emptyMap(), emptyMap())
        }
        val root = JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        val skills = LinkedHashMap<String, Map<String, List<Row>>>()
        val revisions = LinkedHashMap<String, Int>()
        for ((skill, v) in root.getAsJsonObject("skills").entrySet()) {
            val o = v.asJsonObject
            val prov = o.getAsJsonObject("provenance")
            val url = prov?.get("url")?.asString ?: "runescape.wiki"
            prov?.get("revid")?.takeIf { it.isJsonPrimitive }?.asInt?.let { revisions[skill] = it }
            val cats = LinkedHashMap<String, List<Row>>()
            for ((cat, rowsEl) in o.getAsJsonObject("categories").entrySet()) {
                val rows = ArrayList<Row>()
                for (r in rowsEl.asJsonArray) {
                    if (!r.isJsonObject) continue
                    val ro = r.asJsonObject
                    val xp = ro.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
                    val level = ro.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    val name = ro.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    rows += Row(skill, cat, name, level, tenths(xp), "$url (rev ${revisions[skill] ?: "?"}) $cat")
                }
                cats[cat] = rows
            }
            skills[skill] = cats
        }
        logger.info { "skilling xp wiki seed: ${skills.size} skills, ${skills.values.sumOf { c -> c.values.sumOf { it.size } }} rows from $seedPath" }
        return Seed(skills, revisions)
    }

    private fun loadTrees(): Map<String, Row> {
        if (!Files.isRegularFile(chancePath)) return emptyMap()
        val root = JsonParser().parse(Files.newBufferedReader(chancePath)).asJsonObject
        val trees = root.getAsJsonObject("trees") ?: return emptyMap()
        val url = trees.getAsJsonObject("provenance")?.get("url")?.asString ?: "runescape.wiki"
        val rev = trees.getAsJsonObject("provenance")?.get("revid")?.asInt
        val out = LinkedHashMap<String, Row>()
        for (t in trees.getAsJsonArray("data")) {
            val o = t as? JsonObject ?: continue
            val logs = o.get("logs")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val xp = o.get("xp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
            val level = o.get("level")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            // several trees share one log (Tree / Dead tree / Evergreen -> Logs); the first row is the base one
            if (logs !in out) out[logs] = Row("Woodcutting", "TreeData", o.get("name")?.asString ?: logs, level, tenths(xp), "$url (rev $rev)")
        }
        return out
    }

    /** All rows of [skill] named [name] that carry a level, first category first. */
    fun rows(skill: String, name: String): List<Row> =
        seed.skills[skill]?.values?.flatten()?.filter { it.name == name && it.level != null } ?: emptyList()

    /** The wiki's level + xp for gathering [kind] yielding [itemName], or null when the wiki does not name it. */
    fun requirementFor(kind: ResourceNodes.Kind, itemName: String): Row? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> trees[itemName] ?: rows("Woodcutting", itemName).firstOrNull()
        ResourceNodes.Kind.MINING -> rows("Mining", itemName).firstOrNull()
        ResourceNodes.Kind.GATHERING -> null
    }

    fun skillCount(): Int = seed.skills.size
    fun rowCount(): Int = seed.rowCount
    fun rowsWithLevelAndXp(): Int = seed.rowsWithLevelAndXp
    fun rowCount(skill: String): Int = seed.skills[skill]?.values?.sumOf { rows -> rows.count { it.level != null } } ?: 0
}
