package com.opennxt.resources.defaults.stats

import kotlin.math.pow

class StatExperienceTable {

    companion object {
        val DEFAULT = StatExperienceTable()
    }

    private val table: IntArray

    /**
     * Number of entries this table holds. Exposed because callers outside this
     * class (notably com.opennxt.api.stat.Experience) must clamp to what the
     * table actually contains rather than assuming 99 or 120 - the cache ships
     * tables of different lengths.
     */
    val size: Int
        get() = table.size

    /** Raw entry at [index] (0-based), exactly as decoded from the cache. */
    operator fun get(index: Int): Int = table[index]

    /** Defensive copy of the raw entries, for tooling that inspects them. */
    fun toIntArray(): IntArray = table.copyOf()

    /**
     * The standard RuneScape curve, synthesised rather than read from the cache.
     *
     * Indexing follows the same convention as the tables the cache ships (see
     * [StatDefaults.decodeExperienceTables]): `table[level - 1]` is the total xp
     * needed to BE that level, so `table[0]` is 0 - level 1 costs nothing. That
     * convention is what [xpForLevel] and [levelForXp] below already assume, and
     * it is what the one real table in the cache (Invention's, whose entry 0 is
     * literally 0) does.
     *
     * This loop used to accumulate through `level` itself and store the result at
     * `table[level - 1]`, which put xp(level + 1) in the slot for `level`:
     * `table[0]` came out 83, the cost of level 2. Every lookup through this
     * table was therefore one level optimistic, and a brand new player with 0 xp
     * came back as level 0 rather than level 1, because 0 < 83 left the scan in
     * [levelForXp] with nothing to match. The cache-supplied table did not have
     * the fault, so the two disagreed by one level depending on which skill was
     * being asked about.
     *
     * The canonical definition sums the increments BELOW the target level:
     *   xp(L) = floor( 1/4 * sum(n = 1 until L) floor(n + 300 * 2^(n/7)) )
     * which is what this now does. Spot values it must reproduce: 83 at level 2,
     * 1,154 at 10, 101,333 at 50, 6,517,253 at 92, 13,034,431 at 99 and
     * 104,273,167 at 120.
     */
    constructor() {
        table = IntArray(120)
        var xp = 0
        table[0] = 0
        for (level in 2..120) {
            val n = level - 1
            val difference = (n.toDouble() + 300.0 * 2.0.pow(n.toDouble() / 7.0)).toInt()
            xp += difference
            table[level - 1] = xp / 4
        }
        validate()
    }

    constructor(values: IntArray) {
        this.table = values
    }

    /**
     * Validates the xp table
     */
    private fun validate() {
        for (pos in 1 until table.size) {
            if (table[pos - 1] < 0) {
                throw IllegalArgumentException("Negative XP at pos:" + (pos - 1))
            }
            if (table[pos] < table[pos - 1]) {
                throw IllegalArgumentException("XP goes backwards at pos:$pos")
            }
        }
    }

    /**
     * Gets the level based on the experience
     */
    fun levelForXp(xp: Int): Int {
        var level = 0
        var pos = 0
        while (pos < table.size && xp >= table[pos]) {
            level = 1 + pos
            pos++
        }
        return level
    }

    /**
     * Gets the xp required for a certain level
     */
    fun xpForLevel(level: Int): Int {
        var level = level
        if (level < 1) {
            return 0
        }
        if (level > table.size) {
            level = table.size
        }
        return table[level - 1]
    }

    override fun toString(): String {
        return "StatExperienceTable[table=${table.contentToString()}]"
    }
}