package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

/**
 * BURYING A BONE - the backpack menu row "Bury", built.
 */
object Bury {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON, like [Firemaking.enabled]. */
    val enabled: Boolean get() = System.getProperty("opennxt.experiment.bury") != "false"

    // ================================================================
    // THE CONSTANTS
    // ================================================================

    /**
     * The cache option string this module answers to. Read off `items.widget_actions_0` of 526
     * (`Bones`); 62 item ids in this cache carry it, [buryableItemIds] derives them.
     */
    const val BURY_ACTION = "Bury"

    /**
     * THE LINE THAT COMES FIRST, and that this module shipped without.
     */
    const val DIG_MESSAGE = "You dig a hole in the ground."

    const val BURY_MESSAGE = "You bury the bones."

    /**
     * The `MESSAGE_GAME` type the reference client stamps on it: **109**, not the 0 the rest of this repository passes.
     */
    const val MESSAGE_TYPE = 109

    /** All four animation slots, delay 0, on the bury tick. on 9 of 11 bury ticks. */
    val BURY_ANIMATION: IntArray = intArrayOf(18008, 18008, 18008, 18008)

    /** The item the eleven measured buries used. `items.name` 526 = "Bones". */
    const val BONES_ITEM = 526

    /** Prayer xp for one `Bones`, in TENTHS. 45 xp over 10 UPDATE_STAT deltas. */
    const val BONES_XP_TENTHS = 45

    /** The skill it trains. `Stat.PRAYER` is id 5, which is the stat id the wire's UPDATE_STAT carried. */
    val STAT: Stat = Stat.PRAYER

    /** The wiki module and the ONE category of it that is the burying table. */
    const val WIKI_SKILL = "Prayer"
    const val WIKI_CATEGORY = "Bones and Ashes"

    // ================================================================
    // THE XP TABLE
    // ================================================================

    /** Xp for one bone, and where the number came from - printed on every bury. */
    data class Requirement(val xpTenths: Int, val source: String)

    /**
     * The layer, keyed by item ID rather than by name so a cache rename cannot move it.
     * One row, because one bone was observed.
     */
    val MEASURED: Map<Int, Int> = mapOf(BONES_ITEM to BONES_XP_TENTHS)

    /**
     * INVENTED fallback for a buryable the wiki does not name - 31 of the 62. It is the measured
     * `Bones` rate, which is the LOWEST in the wiki table, so an unknown bone can never pay more
     * than the cheapest known one. Named INVENTED in the log line, every time.
     */
    val DEFAULT_REQUIREMENT = Requirement(BONES_XP_TENTHS, "INVENTED (default: the measured Bones rate)")

    private val requirementMemo = java.util.concurrent.ConcurrentHashMap<Int, Requirement>()

    /** Test seam: forget the resolved table so a property flip is visible. */
    internal fun clearRequirementMemo() = requirementMemo.clear()

    /**
     * The wiki's burying table, item NAME -> xp in tenths, read out of the layer already in the
     * tree. Empty when the seed is absent or `-Dopennxt.seed.skillxp=off`.
     */
    fun wikiTable(): Map<String, Int> {
        if (!SkillXpWiki.enabled) return emptyMap()
        val rows = SkillXpWiki.seed.skills[WIKI_SKILL]?.get(WIKI_CATEGORY) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (row in rows) if (row.name !in out) out[row.name] = row.xpTenths
        return out
    }

    /**
     * Xp for burying [itemId], resolved > WIKI > INVENTED - the same precedence
     * [Skilling.requirementFor] uses, and for the same reason: this build's own wire outranks a
     * wiki page.
     */
    fun requirementFor(itemId: Int, itemName: String?): Requirement = requirementMemo.computeIfAbsent(itemId) {
        MEASURED[itemId]?.let { return@computeIfAbsent Requirement(it, "(the reference client 949 wire, $WIKI_SKILL UPDATE_STAT)") }
        val name = itemName ?: ItemActions.nameOf(itemId)
        val wiki = name?.let { wikiTable()[it] }
        if (wiki != null) Requirement(wiki, "WIKI (Module:Skill calc/$WIKI_SKILL/data, $WIKI_CATEGORY)")
        else DEFAULT_REQUIREMENT
    }

    // ================================================================
    // THE POPULATION - derived, never typed
    // ================================================================

    /** Every item id this cache gives a "$BURY_ACTION" menu row, lowest first. */
    fun buryableItemIds(): List<Int> = ItemActions.idsWithAction(BURY_ACTION)

    /** How many there are. Observable so a check asserts a number. */
    fun buryableItemCount(): Int = buryableItemIds().size

    /** True when [itemId] declares the bury row at all - the server's own eligibility test. */
    fun isBuryable(itemId: Int): Boolean =
        ItemActions.slotsWithAction(itemId, BURY_ACTION).isNotEmpty()

    // ================================================================
    // THE SEAMS
    // ================================================================

    /** The player's backpack. [BuryWiring] points this at the live container the login path sends. */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /** Xp in whole points (fractional - Prayer's 4.5 is exactly why [Requirement] stores tenths). */
    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    /** `(player, type, message)` - the type is [MESSAGE_TYPE], not 0. */
    @Volatile
    var messageSink: (ContentPlayer, Int, String) -> Unit = { _, _, _ -> }

    /** All four slots, delay 0. */
    @Volatile
    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }

    /** Re-send the backpack. Returns false when there is no live player behind the seam. */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    /** Puts every seam back the way a check needs them. */
    fun resetSeams() {
        containerSupplier = { it.inventory }
        xpSink = { _, _, _ -> }
        messageSink = { _, _, _ -> }
        animationSink = { _, _ -> }
        inventoryResend = { false }
        clearRequirementMemo()
    }

    // ================================================================
    // THE BURY
    // ================================================================

    enum class Outcome {
        /** The row was not "$BURY_ACTION", or the module is switched off: [ItemOps] keeps the click. */
        NOT_MINE,

        /** The item declares no bury row in this cache. Server-authoritative refusal. */
        NOT_BURYABLE,

        /** The clicked slot no longer holds the clicked item - a stale or duplicated click. */
        GONE,

        /** One bone consumed, xp paid, line and animation sent. */
        BURIED,
    }

    data class Result(
        val outcome: Outcome,
        val itemId: Int,
        val xpTenths: Int = 0,
        val source: String = "",
        val remaining: Int = 0,
    )

    /** How many buries this process has completed. Observable so a check can assert a number. */
    @Volatile
    private var buried: Int = 0

    fun buriedCount(): Int = buried
    internal fun resetCounters() { buried = 0 }

    /**
     * Bury one bone out of backpack [slot].
     *
     * SERVER-AUTHORITATIVE at every step, in this order, because the order is what makes the
     * failure paths safe:
     *
     *  1. the row string must be [BURY_ACTION] - the CACHE's string, never a row number;
     *  2. the item must declare that row in this cache ([isBuryable]) - a hand-built packet naming
     *     a cabbage is refused here, not trusted because the client said so;
     *  3. the clicked slot must still hold the clicked item - this is what makes a double click,
     *     a lag burst or a replayed frame safe: the second copy finds a slot that no longer holds
     *     that bone and is refused with [Outcome.GONE];
     *  4. **exactly one** is consumed. `removeSlot` would take the whole stack; every one of the 62
     *     buryables is non-stackable in this cache (derived: `stackable_1` is null for all 62), so
     *     the two are the same today - and the decrement is written anyway, because "it happens to
     *     be 1 today" is not a reason to write code that destroys 999 bones the day it is not.
     *  5. xp is paid AFTER the item is gone, so a throwing xp sink cannot pay twice for one bone.
     */
    fun bury(player: ContentPlayer, itemId: Int, itemName: String?, slot: Int, action: String): Result {
        if (!enabled) return Result(Outcome.NOT_MINE, itemId)
        if (!action.equals(BURY_ACTION, ignoreCase = true)) return Result(Outcome.NOT_MINE, itemId)
        if (!isBuryable(itemId)) {
            logger.info { "bury ${player.name}: item $itemId declares no '$BURY_ACTION' row in this cache - refused." }
            return Result(Outcome.NOT_BURYABLE, itemId)
        }
        val container = containerSupplier(player)
        if (slot < 0 || slot >= container.size) {
            logger.info { "bury ${player.name}: slot $slot is outside the backpack - refused." }
            return Result(Outcome.GONE, itemId)
        }
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "bury ${player.name}: slot $slot holds ${held?.id ?: "nothing"}, the client said $itemId - refused."
            }
            return Result(Outcome.GONE, itemId)
        }
        // ONE bone, not the stack.
        val left = held.amount - 1
        if (left > 0) container[slot] = Item(held.id, left) else container.removeSlot(slot)

        val requirement = requirementFor(itemId, itemName)
        inventoryResend(player)
        animationSink(player, BURY_ANIMATION)
        // Both lines, in the reference client's measured order, on the same tick - see [DIG_MESSAGE].
        messageSink(player, MESSAGE_TYPE, DIG_MESSAGE)
        messageSink(player, MESSAGE_TYPE, BURY_MESSAGE)
        xpSink(player, STAT, requirement.xpTenths / 10.0)
        buried++
        logger.info {
            "bury ${player.name}: ${itemName ?: "item $itemId"} (slot $slot) buried for " +
                "${requirement.xpTenths / 10.0} ${STAT.name} xp [${requirement.source}]; $left left in the slot."
        }
        return Result(Outcome.BURIED, itemId, requirement.xpTenths, requirement.source, left)
    }
}
