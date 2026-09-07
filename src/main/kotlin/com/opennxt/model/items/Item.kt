package com.opennxt.model.items

import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.ItemDefinition
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap

/**
 * A quantity of one item id.
 *
 * Immutable on purpose: a container hands these out, and a caller that could
 * mutate one would be mutating the container behind its back. Every "change"
 * produces a new instance.
 *
 * [amount] is at least 1. There is no such thing as an item you have zero of -
 * an empty slot is `null`, not `Item(id, 0)`. Keeping the two distinct means
 * `contains` never has to ask whether a present-but-empty stack counts.
 */
data class Item(val id: Int, val amount: Int = 1) {
    init {
        require(id >= 0) { "item id must not be negative: $id" }
        require(amount >= 1) { "item amount must be at least 1 (an empty slot is null, not Item($id, $amount))" }
    }

    /** The definition backing this item, or null if the id is not in the database. */
    val definition: ItemDefinition?
        get() = ItemStacking.definition(id)

    val name: String?
        get() = definition?.name

    /** Whether this id occupies one slot however many you hold. See [ItemStacking]. */
    val stackable: Boolean
        get() = ItemStacking.isStackable(id)

    /**
     * [amount] + [delta], saturating at [Int.MAX_VALUE] rather than wrapping.
     * Wrapping is the failure mode that turns a duplication bug into a negative
     * bank balance, so it is ruled out at the value type.
     */
    fun plus(delta: Int): Item {
        require(delta >= 0) { "use minus() to reduce an amount" }
        val room = Int.MAX_VALUE - amount
        return Item(id, amount + minOf(room, delta))
    }

    /** [amount] - [delta], or null when nothing would be left. */
    fun minus(delta: Int): Item? {
        require(delta >= 0) { "use plus() to raise an amount" }
        return if (delta >= amount) null else Item(id, amount - delta)
    }

    fun withAmount(newAmount: Int): Item? = if (newAmount <= 0) null else Item(id, newAmount)

    override fun toString() = "Item($id${name?.let { " $it" } ?: ""} x$amount)"
}

/**
 * Answers "does this id stack?" from the definition database, never from a flag
 * the caller passed in.
 *
 * Two sources feed the answer:
 *
 * 1. `ItemDefinition.stackable`, which is the `stackable_1` column (config
 * opcode 11: present means stackable). 5659 of the 60617 item rows carry it.
 */
object ItemStacking {
    private val cache = Int2BooleanOpenHashMap()

    /** id -> the item it is a note of, for rows that are notes. */
    fun noteOf(id: Int): Int? = RsDatabase.queryOne(
        "SELECT noteTemplate_old, noteData_old FROM items WHERE id = ?", id
    ) { rs ->
        val template = rs.getInt("noteTemplate_old")
        if (rs.wasNull()) null else {
            val data = rs.getInt("noteData_old")
            if (rs.wasNull()) null else data
        }
    }

    fun isNote(id: Int): Boolean = noteOf(id) != null

    fun definition(id: Int): ItemDefinition? {
        val resources = try {
            FilesystemResources.instance
        } catch (e: UninitializedPropertyAccessException) {
            throw IllegalStateException("FilesystemResources has not been constructed; item lookups are unavailable", e)
        }
        return resources.get<ItemDefinition>(id)
    }

    fun isStackable(id: Int): Boolean {
        if (cache.containsKey(id)) return cache.get(id)
        val def = definition(id)
        val stacks = when {
            def == null -> false          // an id with no row cannot be reasoned about; treat as non-stackable
            def.stackable -> true
            isNote(id) -> true            // stackability inherited from note template 799
            else -> false
        }
        cache.put(id, stacks)
        return stacks
    }

    /** Drops memoised answers. Only useful if the database is swapped under a running process. */
    fun invalidate() = cache.clear()
}
