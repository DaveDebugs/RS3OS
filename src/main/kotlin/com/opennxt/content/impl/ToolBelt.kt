package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

/**
 * THE TOOL BELT - "Add to tool belt", the backpack menu row that puts a tool away for good.
 */
object ToolBelt {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON, like [Bury.enabled]. */
    val enabled: Boolean get() = System.getProperty("opennxt.experiment.toolbelt") != "false"

    // ================================================================
    // THE CACHE'S OWN STRING, AND THE POPULATION IT DEFINES
    // ================================================================

    /**
     * The cache option string this module answers to. NOT assumed: censused over all five menu
     * rows of every item in `data/rs3.sqlite` - `Add to tool belt` is the only string in the whole
     * items table containing "belt", and it appears 191 times.
     */
    const val ADD_ACTION = "Add to tool belt"

    /** Every item id this cache gives an "$ADD_ACTION" row, lowest first. DERIVED, never typed. */
    fun beltItemIds(): List<Int> = ItemActions.idsWithAction(ADD_ACTION)

    /** How many there are. Observable so a check asserts a number. */
    fun beltItemCount(): Int = beltItemIds().size

    /** True when [itemId] declares the row at all - the server's own eligibility test. */
    fun isBeltItem(itemId: Int): Boolean = ItemActions.slotsWithAction(itemId, ADD_ACTION).isNotEmpty()

    /**
     * The base tier every account carries whether it added it or not, read out of
     * [Skilling.TOOLBELT_BASE] rather than repeated here - one source of truth for a fact that was
     * measured once.
     */
    fun baseTierIds(): Set<Int> = Skilling.TOOLBELT_BASE.values.toSet()

    /**
     * A bound on the stored belt, not a model of the reference client's slots (which are UNKNOWN - see the class
     * doc). It is the DERIVED population size, so it can never refuse an item the cache says is
     * beltable, and it still stops an unbounded set from growing inside a save blob - the
     * `InboundDrainLimit` rule that everything is bounded.
     */
    val CAPACITY: Int get() = maxOf(beltItemCount(), 1)

    // ================================================================
    // THE MESSAGES - INVENTED, and labelled INVENTED
    // ================================================================

    /** INVENTED. No observation holds a successful add. `%s` is the item's own cache name. */
    const val ADDED_MESSAGE = "You add your %s to your tool belt."

    /** INVENTED. The reference client's real wording for this refusal was never observed. */
    const val ALREADY_MESSAGE = "You already have that on your tool belt."

    /** INVENTED. The reference client's real wording for this refusal was never observed. */
    const val NOT_BELT_MESSAGE = "You can't add that to your tool belt."

    /** INVENTED. Sent when the belt cannot be reached at all - the item is NOT consumed. */
    const val NO_BELT_MESSAGE = "Your tool belt is unavailable right now."

    /** The MESSAGE_GAME type. 0, the tree's default - the reference client's type for these lines is UNKNOWN. */
    const val MESSAGE_TYPE = 0

    // ================================================================
    // THE SEAMS
    // ================================================================

    /** The player's backpack. [ToolBeltWiring] points this at the live container. */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /**
     * The player's STORED belt contents, or **null when there is no belt behind the seam at all**.
     * The null is load-bearing: it is the difference between "the belt is empty" and "this player
     * has no persistent belt", and only the second may refuse an add. Consuming an item into a
     * belt that does not exist is the item loss this distinction prevents.
     */
    @Volatile
    var beltReader: (ContentPlayer) -> Set<Int>? = { null }

    /** Adds [itemId] to the stored belt. False when it was already there or there is no belt. */
    @Volatile
    var beltWriter: (ContentPlayer, Int) -> Boolean = { _, _ -> false }

    /** `(player, type, message)`. */
    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    /** Re-send the backpack. Returns false when there is no live player behind the seam. */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    fun resetSeams() {
        containerSupplier = { it.inventory }
        beltReader = { null }
        beltWriter = { _, _ -> false }
        messageSink = { _, _, _ -> }
        inventoryResend = { false }
    }

    // ================================================================
    // READING THE BELT
    // ================================================================

    /**
     * What this player has ADDED to the belt. Empty when the belt is reachable and empty; empty
     * when it is not reachable either - use [hasBelt] to tell the two apart.
     *
     * **This is the function `Skilling.toolIn` needs.** See the class doc.
     */
    fun storedIdsFor(player: ContentPlayer): Set<Int> = beltReader(player) ?: emptySet()

    /** True when a persistent belt exists behind the seam for [player]. */
    fun hasBelt(player: ContentPlayer): Boolean = beltReader(player) != null

    /** Everything the belt holds: what was added, plus the base tier everybody has. */
    fun idsFor(player: ContentPlayer): Set<Int> = storedIdsFor(player) + baseTierIds()

    /** True when [itemId] is on the belt already, base tier included. */
    fun holds(player: ContentPlayer, itemId: Int): Boolean = itemId in idsFor(player)

    /**
     * The belt ids that are tools for [kind], by [Skilling]'s own name rule, so a caller does not
     * have to re-derive "what is a hatchet". Provided for the `Skilling.toolIn` patch and for the
     * check; nothing else calls it yet.
     */
    fun toolIdsFor(player: ContentPlayer, kind: ResourceNodes.Kind): Set<Int> {
        // Skilling's own suffixes, referenced rather than repeated: one rule, one place.
        val suffix = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> Skilling.HATCHET_SUFFIX
            ResourceNodes.Kind.MINING -> Skilling.PICKAXE_SUFFIX
            ResourceNodes.Kind.GATHERING -> return emptySet()
        }
        return idsFor(player).filter { id ->
            (Skilling.itemNameOf(id) ?: ItemActions.nameOf(id))?.endsWith(suffix, ignoreCase = true) == true
        }.toSet()
    }

    // ================================================================
    // THE ADD
    // ================================================================

    enum class Outcome {
        /** The row was not "$ADD_ACTION", or the module is off: [ItemOps] keeps the click. */
        NOT_MINE,

        /** The cache gives this item no "$ADD_ACTION" row. Server-authoritative refusal. */
        NOT_BELT_ITEM,

        /** No persistent belt behind the seam. The item is NOT consumed. */
        NO_BELT,

        /** The clicked slot no longer holds the clicked item - a stale or duplicated click. */
        GONE,

        /** The belt already holds it, base tier included. The item is NOT consumed. */
        ALREADY,

        /** The bound was reached. The item is NOT consumed. */
        FULL,

        /** One item consumed out of the backpack and stored on the belt. */
        ADDED,
    }

    data class Result(val outcome: Outcome, val itemId: Int, val beltSize: Int = 0)

    @Volatile
    private var added: Int = 0

    fun addedCount(): Int = added
    internal fun resetCounters() { added = 0 }

    /**
     * Put backpack slot [slot] on the belt.
     *
     * SERVER-AUTHORITATIVE, and the ORDER is the safety argument:
     *
     *  1. the row string must be [ADD_ACTION] - the CACHE's string, never a row number;
     *  2. the item must declare that row in this cache - a hand-built packet naming a cabbage is
     *     refused here, not trusted;
     *  3. a belt must EXIST ([hasBelt]) - otherwise the item would be eaten by a seam that stores
     *     nothing;
     *  4. the belt must not already hold it, base tier included, and must not be at the bound;
     *  5. the clicked slot must still hold the clicked item - which is what makes a double click,
     *     a lag burst or a replayed frame safe: the second copy is [Outcome.GONE];
     *  6. **exactly one** is consumed - the decrement, not `removeSlot`. Two of the 191 beltables
     *     are stackable in this cache, so this is not hypothetical;
     *  7. the belt write happens LAST, and if it somehow refuses, the item goes straight back into
     *     the backpack (there is a free slot by construction - we just took it out) and the whole
     *     thing is refused. An item may never be destroyed by a failure path.
     */
    fun add(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, itemId)
        if (!action.equals(ADD_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, itemId)
        val name = itemName ?: ItemActions.nameOf(itemId) ?: "item $itemId"

        if (!isBeltItem(itemId)) {
            messageSink(player, MESSAGE_TYPE, NOT_BELT_MESSAGE)
            logger.info { "toolbelt ${player.name}: $name declares no '$ADD_ACTION' row in this cache - refused." }
            return Result(Outcome.NOT_BELT_ITEM, itemId)
        }
        val stored = beltReader(player)
        if (stored == null) {
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.warn { "toolbelt ${player.name}: no persistent belt behind the seam - $name was NOT consumed." }
            return Result(Outcome.NO_BELT, itemId)
        }
        if (itemId in stored || itemId in baseTierIds()) {
            messageSink(player, MESSAGE_TYPE, ALREADY_MESSAGE)
            logger.info { "toolbelt ${player.name}: the belt already holds $name - refused, nothing consumed." }
            return Result(Outcome.ALREADY, itemId, stored.size)
        }
        if (stored.size >= CAPACITY) {
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.warn { "toolbelt ${player.name}: the stored belt is at its bound (${stored.size}/$CAPACITY) - refused." }
            return Result(Outcome.FULL, itemId, stored.size)
        }

        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "toolbelt ${player.name}: slot $slot is outside the backpack - refused." }
            return Result(Outcome.GONE, itemId, stored.size)
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "toolbelt ${player.name}: slot $slot holds ${held?.id ?: "nothing"}, the client said $itemId - refused."
            }
            return Result(Outcome.GONE, itemId, stored.size)
        }

        // ONE item, not the stack.
        val left = held.amount - 1
        if (left > 0) container[slot] = Item(held.id, left) else container.removeSlot(slot)

        if (!beltWriter(player, itemId)) {
            // Cannot happen given the checks above, and is not silently swallowed if it does: the
            // item goes back where it came from rather than vanishing.
            if (left > 0) container[slot] = Item(held.id, held.amount) else container[slot] = held
            messageSink(player, MESSAGE_TYPE, NO_BELT_MESSAGE)
            logger.error { "toolbelt ${player.name}: the belt refused $name after it was taken out; put back in slot $slot." }
            return Result(Outcome.NO_BELT, itemId, stored.size)
        }

        inventoryResend(player)
        messageSink(player, MESSAGE_TYPE, ADDED_MESSAGE.format(name.lowercase()))
        added++
        val size = beltReader(player)?.size ?: (stored.size + 1)
        logger.info { "toolbelt ${player.name}: $name (slot $slot) added to the tool belt; $size stored, $left left in the slot." }
        return Result(Outcome.ADDED, itemId, size)
    }
}
