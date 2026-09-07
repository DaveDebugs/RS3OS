package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

/**
 * THE BUILD-949 MINING LEVELS, READ FROM THE CACHE.
 *
 * ## What this replaces
 *
 * [com.opennxt.content.impl.Skilling]'s mining table had ONE row - Tin ore, the
 * only rock anybody had mined on camera - and even that row's LEVEL was the
 * authored floor of 1 rather than a measurement. Every other rock in the game,
 * 802 locs' worth, gathered at `DEFAULT_MINING_LEVEL` (1, INVENTED).
 *
 * The cache states the answer. dbtable 34 carries 91 named rock classes, each
 * with `[item id, level, weight]` yields, and a loc's own name joins to the
 * class name. `tools/949/derive_mining.py` writes the join to
 * `data/prot949/mining_949.tsv`; this reads it.
 *
 * ## What is claimed
 *
 * GROUNDED: the item a rock yields, and the LEVEL that yield requires. Both are
 * columns in the cache's own table, not a published figure and not a guess.
 *
 * NOT claimed: xp per ore. dbtable 34 does not state one, so mining xp is
 * untouched - `SkillingRates.MINING_XP_TENTHS` for tin, which is measured off
 * this build's own wire, and `DEFAULT_XP_TENTHS` (INVENTED) for everything
 * else. A level being grounded does not make the xp beside it grounded, and
 * this class does not blur them.
 *
 * ## The control that had to be fixed before this was trusted
 *
 * The derivation's first control expected Coal at level 30 and PASSED. It was
 * wrong twice over: coal is level 20 on the post-rework ladder this build is
 * on, and the reason the wrong number agreed was that the class table has two
 * rows named "Coal rock" - class 30 yielding Coal (453) at 20, class 41
 * yielding item 44777 at 30 - and a name-keyed dict had silently kept the
 * second. Classes are keyed by their own id now and duplicate names are
 * reported rather than resolved. The control reads 7/7 against class ids:
 * Copper 1, Tin 1, Iron 10, Coal 20, Mithril 30, Adamantite 40, Runite 50.
 *
 * ## The conflict rule
 *
 * Seven rock NAMES map to more than one class. That ambiguity is about the
 * rock, not the ore: this table is keyed by the ITEM, and an item is only
 * dropped when the same item name appears at two DIFFERENT levels, which is a
 * real contradiction rather than a naming collision. Dropped items are logged
 * with both levels so the disagreement is visible instead of averaged.
 */
object MiningTable949 {
    private val logger = KotlinLogging.logger { }

    /** One row of the derived table, as the cache states it. */
    data class Yield(
        val rock: String,
        val classId: Int,
        val itemId: Int,
        val itemName: String,
        val level: Int,
        val weight: Int,
        val suspect: Boolean,
        val ambiguous: Boolean,
    )

    private data class Loaded(val rows: List<Yield>, val levels: Map<String, Int>, val conflicts: Int)

    private val loaded: Loaded by lazy { load() }

    private fun load(): Loaded {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("mining_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "MiningTable949: no ${file.path}. Mining levels stay at the authored default; " +
                    "regenerate with tools/949/derive_mining.py to ground them."
            }
            return Loaded(emptyList(), emptyMap(), 0)
        }
        val rows = ArrayList<Yield>()
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("class\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 9) return@forEachLine
                val cls = p[0].toIntOrNull() ?: return@forEachLine
                val item = p[3].toIntOrNull() ?: return@forEachLine
                val lvl = p[5].toIntOrNull() ?: return@forEachLine
                rows.add(
                    Yield(
                        rock = p[1], classId = cls, itemId = item, itemName = p[4],
                        level = lvl, weight = p[6].toIntOrNull() ?: 0,
                        suspect = p[7] == "1", ambiguous = p[8] == "1",
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "MiningTable949: could not read ${file.path}; mining levels stay authored" }
            return Loaded(emptyList(), emptyMap(), 0)
        }

        // item name -> level, dropping any item whose level the table contradicts itself on.
        val byItem = HashMap<String, MutableSet<Int>>()
        for (r in rows) {
            if (r.itemName.isBlank()) continue
            byItem.getOrPut(r.itemName) { HashSet() }.add(r.level)
        }
        val levels = HashMap<String, Int>()
        var conflicts = 0
        for ((name, lv) in byItem) {
            if (lv.size == 1) {
                levels[name] = lv.first()
            } else {
                conflicts++
                logger.info {
                    "MiningTable949: '$name' is stated at ${lv.sorted().joinToString("/")} by different " +
                        "rock classes; no level is used for it rather than picking one"
                }
            }
        }
        logger.info {
            "MiningTable949: ${rows.size} yield rows over ${rows.map { it.classId }.toSet().size} rock " +
                "classes; ${levels.size} items carry an unambiguous cache-stated level" +
                (if (conflicts > 0) ", $conflicts dropped as contradictory" else "") +
                ". GROUNDED = item and level; xp is NOT from this table."
        }
        return Loaded(rows, levels, conflicts)
    }

    /** Every derived yield row. */
    fun rows(): List<Yield> = loaded.rows

    /** Item name -> the level the cache says that ore needs. Null when unstated or contradictory. */
    fun levelForItem(itemName: String): Int? = loaded.levels[itemName]

    /** Item name -> level, for every item with an unambiguous level. */
    fun levels(): Map<String, Int> = loaded.levels

    /**
     * ROCK name -> mining level, for the rocks whose name is unambiguous.
     *
     * [levels] is keyed by the ITEM a rock yields; this is keyed by the rock itself, which is what
     * [RockProspect949] can cross-check against, because the reference client's Prospect readout names the rock
     * and never the ore.
     *
     * SEVEN ROCK NAMES IN THIS TABLE ARE NOT UNIQUE - "Coal rock" is two classes at two different
     * levels, which is precisely the trap that made the derivation's first control pass for the
     * wrong reason. A name that resolves to more than one level is therefore DROPPED rather than
     * resolved to whichever row happened to be last. An absent rock is a rock this table cannot
     * speak for; it is not a rock at level 1.
     */
    fun levelsByRock(): Map<String, Int> {
        val byName = HashMap<String, MutableSet<Int>>()
        for (r in loaded.rows) {
            if (r.rock.isBlank()) continue
            byName.getOrPut(r.rock) { HashSet() }.add(r.level)
        }
        val out = HashMap<String, Int>()
        for ((name, lv) in byName) if (lv.size == 1) out[name] = lv.first()
        return out
    }

    /** Rows for one rock class name; several classes may share a name. */
    fun forRock(rock: String): List<Yield> = loaded.rows.filter { it.rock.equals(rock, ignoreCase = true) }

    fun size(): Int = loaded.rows.size
    fun conflictCount(): Int = loaded.conflicts
}
