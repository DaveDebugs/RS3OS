package com.opennxt.api.stat

/**
 * Level <-> experience conversion, driven entirely by the per-stat table the
 * cache supplies.
 *
 * There is no single RuneScape xp curve, so nothing here is hardcoded. Every
 * lookup goes through [Stat.table], which [Stat.reload] wires up from the stat
 * defaults in the cache. In the cache this checkout reads that means:
 *
 *  - 28 of the 29 declared skills carry no table of their own and fall back to
 *    [com.opennxt.resources.defaults.stats.StatExperienceTable.DEFAULT], the
 *    standard curve (83 xp for level 2, 13,034,431 for 99).
 *  - Invention carries the one table the cache actually ships: a 150-entry
 *    elite curve (830 xp for level 2, 80,618,654 for 120).
 *
 * Table indexing is `table[level - 1] == total xp required to BE that level`,
 * so entry 0 is 0 and level 1 is free. Both the cache-supplied table and the
 * synthesised default follow that convention.
 *
 * ## Where the ceiling comes from
 *
 * A table can be longer than the skill is allowed to get: Invention's has 150
 * entries but [com.opennxt.resources.defaults.stats.StatDefinition.cap] for
 * Invention is 120. The reachable maximum is therefore the smaller of the two,
 * which is also the rule
 * [com.opennxt.impl.stat.PlayerStatData.calculateLevel] already applies. Use
 * [maxLevel] rather than assuming 99 or 120; the caps in this cache are not
 * uniform (99, 110 and 120 all appear).
 */

/**
 * Highest level [stat] can reach: the shorter of what its table defines and
 * what its definition caps it at.
 */
fun maxLevel(stat: Stat): Int {
    requireLoaded(stat)
    return minOf(stat.table.size, stat.def.cap).coerceAtLeast(1)
}

/**
 * Total experience required to reach [level] in [stat].
 *
 * Levels below 1 clamp to 1 (which costs 0 xp) and levels above [maxLevel]
 * clamp to [maxLevel]; asking for level 200 Cooking returns the cost of the
 * highest level Cooking has rather than throwing or reading off the end.
 */
fun xpForLevel(stat: Stat, level: Int): Int {
    requireLoaded(stat)
    val clamped = level.coerceIn(1, maxLevel(stat))
    return stat.table[clamped - 1]
}

/**
 * The level [level] experience grants in [stat].
 *
 * Zero or negative xp is level 1, not level 0 - a fresh account has every skill
 * at its minimum, and there is no such thing as level 0. Experience past the
 * top of the table stays at [maxLevel].
 */
fun levelForXp(stat: Stat, xp: Int): Int {
    requireLoaded(stat)
    val table = stat.table
    val max = maxLevel(stat)
    if (xp <= table[0]) return 1

    // The table is non-decreasing, so binary search for the last entry that the
    // xp total covers. Linear scanning 150 entries on every xp drop is wasteful
    // when the ordering is guaranteed; StatExperienceTable.validate() rejects a
    // every table actually loaded from this cache.
    var low = 1
    var high = max
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (table[mid - 1] <= xp) low = mid else high = mid - 1
    }
    return low
}

/**
 * Experience still owed before [stat] reaches the next level from [xp], or 0
 * once [maxLevel] is reached and there is no next level to buy.
 */
fun xpToNextLevel(stat: Stat, xp: Int): Int {
    val level = levelForXp(stat, xp)
    if (level >= maxLevel(stat)) return 0
    return xpForLevel(stat, level + 1) - maxOf(xp, 0)
}

private fun requireLoaded(stat: Stat) {
    if (!stat.loaded) {
        throw IllegalStateException(
            "Stat.$stat has no experience table yet - Stat.reload() must run " +
                    "against the cache before levels can be calculated."
        )
    }
}
