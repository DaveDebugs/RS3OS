package com.opennxt.model.items

/**
 * What an [ItemContainer.add] actually managed to do.
 *
 * Adds are allowed to be partial - a 28-slot inventory with 3 free slots being
 * handed 10 swords takes 3 and tells you about the other 7. A boolean return
 * would force the caller to either pre-check (racy) or lose the remainder.
 */
data class AddResult(val requested: Int, val added: Int) {
    val remaining: Int get() = requested - added
    val complete: Boolean get() = added == requested
    override fun toString() = "AddResult(requested=$requested, added=$added, remaining=$remaining)"
}

/** What an [ItemContainer.remove] managed to do. [shortfall] is what was asked for but not there. */
data class RemoveResult(val requested: Int, val removed: Int) {
    val shortfall: Int get() = requested - removed
    val complete: Boolean get() = removed == requested
    override fun toString() = "RemoveResult(requested=$requested, removed=$removed, shortfall=$shortfall)"
}

/**
 * A fixed-size array of item slots: inventory, bank tab, shop, equipment.
 *
 * Named `ItemContainer` rather than `Container` because `com.opennxt.filesystem.Container`
 * already exists and means a cache container; two `Container`s one import apart
 * would be a permanent source of wrong-import bugs.
 *
 * Slot semantics:
 *
 *  - A **stackable** id occupies exactly one slot however many you hold. Adding
 *    more finds the existing stack and grows its amount. If there is no stack
 *    and no free slot, nothing is added.
 *  - A **non-stackable** id occupies one slot per unit. Adding 5 costs 5 slots.
 *  - Stackability is read from the definition database via [ItemStacking]. It is
 *    deliberately not a constructor parameter or a per-call flag: a container
 *    that could be told "coins do not stack" is a container that will eventually
 *    be told exactly that by accident.
 *  - [stackAll] is the one override, and it only ever adds stacking, never
 *    removes it. It models a bank, where everything stacks regardless of what
 *    the item config says. A bank does not un-stack coins, so there is no
 *    corresponding "stackNothing".
 *
 * Overflow: amounts are RS's own 32-bit signed integers and saturate at
 * [Int.MAX_VALUE]. Adding 100 to a stack of `Int.MAX_VALUE - 10` puts 10 in and
 * reports 90 as [AddResult.remaining]; it never wraps to a negative amount, and
 * it never silently discards the 90 either. Callers that must not lose the
 * remainder can see it; callers that do not care can ignore it. The alternative
 * - throwing - would make an ordinary drop-on-a-full-stack into an exception
 * path, which is not how the game behaves.
 *
 * Not thread-safe. Containers belong to one player and are touched on that
 * player's tick.
 */
open class ItemContainer(val size: Int, val stackAll: Boolean = false) {

    companion object {
        /** The player inventory has been 28 slots since 2001 and is assumed nowhere else in this file. */
        const val INVENTORY_SIZE = 28

        fun inventory() = ItemContainer(INVENTORY_SIZE)
    }

    init {
        require(size > 0) { "container size must be positive: $size" }
    }

    private val slots = arrayOfNulls<Item>(size)

    /** Whether [id] stacks in *this* container. */
    fun stacks(id: Int): Boolean = stackAll || ItemStacking.isStackable(id)

    // ---- reading -------------------------------------------------------

    operator fun get(slot: Int): Item? {
        checkSlot(slot)
        return slots[slot]
    }

    fun isEmpty(slot: Int): Boolean = get(slot) == null

    /** Direct placement, bypassing stacking rules. Used by equipment and by deserialisation. */
    operator fun set(slot: Int, item: Item?) {
        checkSlot(slot)
        slots[slot] = item
    }

    /** Slots holding something. */
    fun usedSlots(): Int = slots.count { it != null }

    fun freeSlots(): Int = size - usedSlots()

    fun isFull(): Boolean = freeSlots() == 0

    fun firstFreeSlot(): Int = slots.indexOfFirst { it == null }

    fun slotOf(id: Int): Int = slots.indexOfFirst { it != null && it.id == id }

    /**
     * How many of [id] are held, across every slot.
     *
     * Long because a bank of stacks can legitimately hold more than
     * [Int.MAX_VALUE] in total even though no single stack can.
     */
    fun count(id: Int): Long = slots.sumOf { if (it != null && it.id == id) it.amount.toLong() else 0L }

    /** Every unit of every item, ignoring id. */
    fun totalCount(): Long = slots.sumOf { it?.amount?.toLong() ?: 0L }

    fun contains(id: Int): Boolean = slotOf(id) >= 0

    fun contains(id: Int, amount: Int): Boolean = count(id) >= amount.toLong()

    fun contains(item: Item): Boolean = contains(item.id, item.amount)

    /** A snapshot; mutating it does not touch the container. */
    fun toArray(): Array<Item?> = slots.copyOf()

    fun items(): List<Item> = slots.filterNotNull()

    // ---- writing -------------------------------------------------------

    /**
     * Adds up to [amount] of [id]. See the class doc for partial adds and
     * overflow. Returns what actually went in.
     */
    fun add(id: Int, amount: Int): AddResult {
        require(amount >= 0) { "cannot add a negative amount: $amount" }
        if (amount == 0) return AddResult(0, 0)

        if (stacks(id)) {
            val existing = slotOf(id)
            if (existing >= 0) {
                val current = slots[existing]!!
                val room = Int.MAX_VALUE - current.amount
                val give = minOf(room, amount)
                if (give > 0) slots[existing] = Item(id, current.amount + give)
                return AddResult(amount, give)
            }
            val free = firstFreeSlot()
            if (free < 0) return AddResult(amount, 0)
            slots[free] = Item(id, amount)
            return AddResult(amount, amount)
        }

        // Non-stackable: one slot per unit, as many as there are free slots.
        var placed = 0
        for (slot in slots.indices) {
            if (placed == amount) break
            if (slots[slot] == null) {
                slots[slot] = Item(id, 1)
                placed++
            }
        }
        return AddResult(amount, placed)
    }

    fun add(item: Item): AddResult = add(item.id, item.amount)

    /**
     * Removes up to [amount] of [id], emptying stacks as it goes.
     *
     * Removing more than is held removes everything that is there and reports
     * the difference as [RemoveResult.shortfall]. It does not throw and does not
     * roll back: "take what you can" is what the game does when a stack is
     * partially consumed.
     */
    fun remove(id: Int, amount: Int): RemoveResult {
        require(amount >= 0) { "cannot remove a negative amount: $amount" }
        if (amount == 0) return RemoveResult(0, 0)

        var left = amount
        for (slot in slots.indices) {
            if (left == 0) break
            val item = slots[slot] ?: continue
            if (item.id != id) continue
            val take = minOf(item.amount, left)
            slots[slot] = item.minus(take)
            left -= take
        }
        return RemoveResult(amount, amount - left)
    }

    fun remove(item: Item): RemoveResult = remove(item.id, item.amount)

    /** Removes whatever is in [slot] and returns it. */
    fun removeSlot(slot: Int): Item? {
        checkSlot(slot)
        val item = slots[slot]
        slots[slot] = null
        return item
    }

    fun clear() {
        slots.fill(null)
    }

    // ---- rearranging ---------------------------------------------------

    /**
     * Exchanges two slots. This is RS's "swap" bank/inventory arrangement mode,
     * and what dragging one item onto another does in the inventory.
     */
    fun swap(from: Int, to: Int) {
        checkSlot(from)
        checkSlot(to)
        val tmp = slots[from]
        slots[from] = slots[to]
        slots[to] = tmp
    }

    /**
     * Lifts the item out of [from] and re-inserts it at [to], shifting everything
     * between the two along by one. This is RS's "insert" arrangement mode, and
     * it is a genuinely different operation from [swap]: with A B C D, moving
     * slot 0 to slot 2 gives B C A D under insert and C B A D under swap.
     */
    fun insert(from: Int, to: Int) {
        checkSlot(from)
        checkSlot(to)
        if (from == to) return
        val moving = slots[from]
        if (from < to) {
            for (i in from until to) slots[i] = slots[i + 1]
        } else {
            for (i in from downTo to + 1) slots[i] = slots[i - 1]
        }
        slots[to] = moving
    }

    /** Pushes every item to the front, preserving order and leaving the gaps at the end. */
    fun compact() {
        val packed = slots.filterNotNull()
        slots.fill(null)
        packed.forEachIndexed { i, item -> slots[i] = item }
    }

    private fun checkSlot(slot: Int) {
        if (slot < 0 || slot >= size) throw IndexOutOfBoundsException("slot $slot outside 0..${size - 1}")
    }

    override fun toString() =
        "ItemContainer(size=$size, used=${usedSlots()}, stackAll=$stackAll, ${items()})"
}
