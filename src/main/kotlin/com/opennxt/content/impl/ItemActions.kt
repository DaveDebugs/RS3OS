package com.opennxt.content.impl

import com.opennxt.resources.sqlite.SqliteItemCodec

/**
 * THE BY-ACTION CENSUS OVER BACKPACK MENU ROWS: "which items offer this option?".
 */
object ItemActions {

    /** The five menu-row indices, in the order a click's `op - 1` addresses them. */
    val ALL_SLOTS: List<Int> = listOf(0, 1, 2, 3, 4)

    /** The row `items` has no column for - the one the codec merges out of `items_attr`. */
    const val ATTR_SLOT = 3

    /** True when the definition database is open, so a census can be taken at all. */
    val isAvailable: Boolean get() = com.opennxt.resources.sqlite.RsDatabase.available

    /** The five menu rows of [itemId], nulls preserved. Empty when the item is not in the cache. */
    fun actionsOf(itemId: Int): Array<String?> =
        SqliteItemCodec.load(itemId)?.inventoryActions ?: arrayOfNulls(ALL_SLOTS.size)

    /** The option string of menu row index [slot] (i.e. `op - 1`) for [itemId], or null. */
    fun actionAt(itemId: Int, slot: Int): String? = actionsOf(itemId).getOrNull(slot)

    /** Which of the five rows [itemId] declares [action] on, lowest first. Empty when it does not. */
    fun slotsWithAction(itemId: Int, action: String): List<Int> {
        val actions = actionsOf(itemId)
        return ALL_SLOTS.filter { actions.getOrNull(it)?.equals(action, ignoreCase = true) == true }
    }

    /** The item's name out of the same cache, or null. */
    fun nameOf(itemId: Int): String? = SqliteItemCodec.load(itemId)?.name

    // ---------------------------------------------------------------- the index

    /**
     * lowercase option string -> the item ids that offer it on any row, sorted.
     *
     * One pass over every item definition, merged rows included, cached in [built]. One index
     * rather than a memo per action so that N verbs cost ONE sweep, not N. It is NOT a `by lazy`,
     * because a lazy cannot be re-derived and [invalidate] has to mean something.
     */
    @Volatile
    private var built: Map<String, List<Int>>? = null

    private fun build(): Map<String, List<Int>> {
        if (!isAvailable) return emptyMap()
        val out = HashMap<String, MutableList<Int>>()
        for ((id, def) in SqliteItemCodec.listAll()) {
            for (action in def.inventoryActions) {
                if (action.isNullOrBlank()) continue
                out.getOrPut(action.lowercase()) { ArrayList() }.add(id)
            }
        }
        return out.mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    /**
     * The index, built on first use. Two callers racing here both build and one wins - the sweep is
     * a pure read of a read-only database, so a duplicated build costs time and nothing else, which
     * is cheaper than holding a lock across a whole-corpus query.
     */
    private fun table(): Map<String, List<Int>> = built ?: build().also { built = it }

    /** Test seam: forget the index so a later call re-derives it. */
    fun invalidate() { built = null }

    /**
     * Every item id whose backpack menu carries [action] on ANY of its five rows, lowest first.
     * DERIVED over the whole cache - the population, never a list somebody typed.
     */
    fun idsWithAction(action: String): List<Int> = table()[action.lowercase()] ?: emptyList()

    /** How many item ids [idsWithAction] finds. Observable so a check asserts a number. */
    fun countWithAction(action: String): Int = idsWithAction(action).size

    /** How many distinct option strings the whole cache offers. Observable, and a cheap sanity pin. */
    fun distinctActionCount(): Int = table().size
}
