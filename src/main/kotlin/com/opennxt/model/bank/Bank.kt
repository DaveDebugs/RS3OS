package com.opennxt.model.bank

import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.items.ItemStacking

/**
 * A per-player bank, built on the same [ItemContainer] machinery the inventory
 * and equipment use - not a parallel container type.
 */
class Bank {

    companion object {
        /**
         * RECONSTRUCTED: the RS3 base bank size for members, 510 slots, as
         * publicly documented - the cache does not carry this number. See the
         * class comment.
         */
        const val CAPACITY = 510
    }

    /** `stackAll = true` is the whole trick: this is what makes it a bank. */
    private val container = ItemContainer(CAPACITY, stackAll = true)

    // ---- results ---------------------------------------------------------

    /**
     * What a [deposit] did, as a type rather than a number, so "the id does not
     * exist" and "you deposited zero" cannot be confused.
     */
    sealed class DepositResult {
        /**
         * [deposited] of the [requested] moved from the source into the bank.
         * [deposited] can be short of [requested] when the source held fewer
         * than asked for (deposit-all semantics: take what is there), or - only
         * at the [Int.MAX_VALUE] stack cap - when the bank stack saturated, in
         * which case the overflow is returned to the source, not destroyed.
         */
        data class Deposited(val id: Int, val requested: Int, val deposited: Int, val bankedTotal: Long) :
            DepositResult() {
            val complete: Boolean get() = deposited == requested
        }

        /** The id has no row in the definition database. Nothing moved. */
        data class UnknownItem(val id: Int) : DepositResult()

        /** The source container holds none of this id. Nothing moved. */
        data class NothingToDeposit(val id: Int) : DepositResult()

        /** Every one of the [CAPACITY] slots is taken and this id has no existing stack. Nothing moved. */
        data class BankFull(val id: Int) : DepositResult()
    }

    /** What a [withdraw] did. */
    sealed class WithdrawResult {
        /**
         * [withdrawn] of the [requested] moved into the target. Partial when
         * the target ran out of room (unstackable ids cost one target slot per
         * unit) or the bank held fewer than asked; either way [stillBanked] is
         * what remains here - the remainder is never dropped.
         */
        data class Withdrawn(val id: Int, val requested: Int, val withdrawn: Int, val stillBanked: Long) :
            WithdrawResult() {
            val shortfall: Int get() = requested - withdrawn
            val complete: Boolean get() = withdrawn == requested
        }

        /** The id has no row in the definition database. Nothing moved. */
        data class UnknownItem(val id: Int) : WithdrawResult()

        /** The bank holds none of this id. Nothing moved. */
        data class NotBanked(val id: Int) : WithdrawResult()

        /** The target container could not take a single unit. Nothing moved. */
        data class TargetFull(val id: Int) : WithdrawResult()
    }

    // ---- reading ---------------------------------------------------------

    fun usedSlots(): Int = container.usedSlots()
    fun freeSlots(): Int = container.freeSlots()
    fun isFull(): Boolean = container.isFull()
    fun count(id: Int): Long = container.count(id)
    fun contains(id: Int): Boolean = container.contains(id)
    fun isEmpty(): Boolean = container.usedSlots() == 0

    /** The slot the id's single stack occupies, or -1. One id, one slot - always. */
    fun slotOf(id: Int): Int = container.slotOf(id)

    /**
     * What this bank holds in [slot], or null when the slot is empty or outside
     * `0 until CAPACITY`.
     */
    fun itemAt(slot: Int): BankedItem? {
        if (slot < 0 || slot >= CAPACITY) return null
        val item = container[slot] ?: return null
        return BankedItem(slot, item.id, item.amount)
    }

    // ---- persistence surface ---------------------------------------------

    /**
     * One occupied bank slot as data: the (slot, id, amount) triple
     * [com.opennxt.model.account.PlayerSave.SavedItem] is written in. A
     * separate type on purpose - [Bank] does not depend on the account layer,
     * the account layer converts.
     */
    data class BankedItem(val slot: Int, val id: Int, val amount: Int)

    /**
     * The bank's contents, one triple per occupied slot, in slot order. Empty
     * slots are absent, never zero-amount entries.
     *
     * ## Read-only, deliberately
     *
     * This hands out a list of immutable triples - not the [ItemContainer],
     * not a mutable view of it - because every rule this class exists to
     * enforce lives on the way IN:
     *
     *  - item identity is checked against the definition database, so an id
     *    with no row cannot enter ([DepositResult.UnknownItem]);
     *  - the [CAPACITY] slot limit is enforced before the source is touched
     *    ([DepositResult.BankFull]);
     *  - every move reports what it did as a typed result, so a partial or
     *    refused move cannot be mistaken for a complete one.
     *
     * A caller holding the container could place an unknown id, a 511th stack
     * or a zero amount with nothing to report it - silently, which is the one
     * thing this class refuses everywhere else. So mutation goes through
     * [deposit]/[withdraw], and this accessor is the honest minimum a save
     * needs: what is in here, and where.
     *
     * The one other write door is [restore], and it is not a general mutator -
     * see its doc.
     */
    fun contents(): List<BankedItem> = container.toArray().withIndex()
        .mapNotNull { (slot, item) -> item?.let { BankedItem(slot, it.id, it.amount) } }

    /**
     * Puts a saved layout back, slot for slot: the login-time deserialisation
     * counterpart of [contents], and the ONLY write that does not go through
     * the deposit/withdraw rules.
     *
     * It is a **wholesale replace**, never a merge: the bank is cleared first,
     * so restoring twice cannot double a stack. That is what makes it safe for
     * a relogin onto a live in-memory bank (see
     * [com.opennxt.content.impl.Banks.restoreBank], which is where the relogin
     * ordering is argued).
     *
     * It places items directly, the way [ItemContainer.set] documents for
     * deserialisation, so a restored bank has the exact arrangement it was
     * saved with - running the layout back through [deposit] would re-pack it
     * and silently rearrange a player's bank on every login.
     *
     * What it DOES check, throwing [IllegalArgumentException] that names the
     * problem rather than loading a plausible-looking bank:
     *
     *  - slots inside 0..[CAPACITY]-1, no slot twice;
     *  - amounts >= 1 (an empty slot is an absent entry, not a zero);
     *  - no id in two slots - "one id, one slot" is the invariant [slotOf] and
     *    [count] are written against, and a duplicate would make [count]
     *    silently disagree with what the bank interface shows.
     *
     * It deliberately does NOT re-check ids against the definition database.
     * Nothing enters a bank without a definition row in the first place
     * ([deposit] refuses), and dropping ids on the way back in would DELETE a
     * player's items whenever the running cache is older or newer than the one
     * the save was written under - silent item loss, which is worse than
     * carrying an id the current cache cannot describe.
     */
    fun restore(items: List<BankedItem>) {
        val seenSlots = HashSet<Int>()
        val seenIds = HashSet<Int>()
        items.forEach {
            require(it.slot in 0 until CAPACITY) { "bank slot ${it.slot} outside 0..${CAPACITY - 1}" }
            require(it.amount >= 1) { "bank slot ${it.slot} has amount ${it.amount}; an empty slot is omitted, not zero" }
            require(seenSlots.add(it.slot)) { "bank slot ${it.slot} appears twice in the restored layout" }
            require(seenIds.add(it.id)) { "item ${it.id} appears in two bank slots; a bank holds one slot per id" }
        }
        container.clear()
        items.forEach { container[it.slot] = Item(it.id, it.amount) }
    }

    // ---- moving items ----------------------------------------------------

    /**
     * Moves up to [amount] of [id] from [from] into the bank.
     *
     * Deposit-all semantics on the amount: asking for more than the source
     * holds deposits what is there and reports it, because that is what the
     * bank interface's "Deposit-All" button does. Items are removed from the
     * source FIRST and anything the bank could not absorb (only possible at the
     * [Int.MAX_VALUE] stack cap, since the deposit is capped at what the source
     * held) goes straight back into the source's freed slots - the move is
     * lossless in both directions.
     */
    fun deposit(id: Int, amount: Int, from: ItemContainer): DepositResult {
        require(amount >= 0) { "cannot deposit a negative amount: $amount" }
        if (ItemStacking.definition(id) == null) return DepositResult.UnknownItem(id)

        val held = from.count(id).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (held == 0) return DepositResult.NothingToDeposit(id)
        val moving = minOf(amount, held)
        if (moving == 0) return DepositResult.Deposited(id, amount, 0, count(id))

        // Refuse before touching the source when no slot can receive the id.
        if (container.isFull() && !container.contains(id)) return DepositResult.BankFull(id)

        val taken = from.remove(id, moving).removed
        val added = container.add(id, taken)
        if (added.remaining > 0) from.add(id, added.remaining) // stack-cap overflow back to the source
        return DepositResult.Deposited(id, amount, added.added, count(id))
    }

    /**
     * Moves up to [amount] of [id] from the bank into [to].
     *
     * The amount actually moved is the smallest of: what was asked for, what is
     * banked, and what [to] has room for under ITS stacking rules - the bank
     * stacks everything, the target only stacks what the item config says. Only
     * what the target accepted is removed from the bank, so a partial
     * withdrawal leaves the remainder banked rather than on the floor.
     */
    fun withdraw(id: Int, amount: Int, to: ItemContainer): WithdrawResult {
        require(amount >= 0) { "cannot withdraw a negative amount: $amount" }
        if (ItemStacking.definition(id) == null) return WithdrawResult.UnknownItem(id)

        val banked = count(id)
        if (banked == 0L) return WithdrawResult.NotBanked(id)
        val wanted = minOf(amount.toLong(), banked).toInt()
        if (wanted == 0) return WithdrawResult.Withdrawn(id, amount, 0, banked)

        // Offer the target first; it reports what it accepted. Then remove
        // exactly that much from the bank - never more.
        val accepted = to.add(id, wanted).added
        if (accepted == 0) return WithdrawResult.TargetFull(id)
        container.remove(id, accepted)
        return WithdrawResult.Withdrawn(id, amount, accepted, count(id))
    }

    /** Empties the bank. For tests; a real player has no "wipe my bank" button. */
    fun clear() = container.clear()

    override fun toString() = "Bank(${usedSlots()}/$CAPACITY slots used)"
}
