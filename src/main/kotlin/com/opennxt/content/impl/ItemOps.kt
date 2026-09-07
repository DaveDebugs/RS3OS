package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.Item
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

/**
 * BACKPACK AND WORN ITEM OPTIONS - eat, wield, wear, remove, drop.
 */
object ItemOps {
    private val logger = KotlinLogging.logger { }

    /** toplevel_v2_inventory:item_layer - the backpack grid. */
    const val BACKPACK_IFACE = 1473
    const val BACKPACK_ITEM_LAYER = 5

    /** toplevel_v2_worn:item_layer - the worn-equipment grid. */
    const val WORN_IFACE = 1464
    const val WORN_ITEM_LAYER = 15

    /** `mid` when the clicked component carries no item. */
    private const val NO_ITEM = 0xFFFFFF

    /**
     * The verbs, censused over the whole cache rather than guessed (all 4 widget_action
     * columns of `items`): wear 9,885 / wield 5,391 / equip 17 - and nothing else that means "put it
     * on" (the only near-miss, "add to headwear" x3, is a different operation). drink 1,642 /
     * eat 569 / consume 17.
     */
    private val EQUIP_ACTIONS = setOf("wield", "wear", "equip")
    private val CONSUME_ACTIONS = setOf("eat", "drink", "consume")

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.itemOps") != "false"

    /**
     * Backpack menu rows this file does not implement, offered to whoever does.
     */
    val backpackActionHooks: MutableList<(WorldPlayer, String, Int, String, Int) -> Boolean> =
        java.util.concurrent.CopyOnWriteArrayList()

    /** True when this click was an item option and has been dealt with (or deliberately refused). */
    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        val iface = packet.interfaceId
        val comp = packet.component
        val backpack = iface == BACKPACK_IFACE && comp == BACKPACK_ITEM_LAYER
        val worn = iface == WORN_IFACE && comp == WORN_ITEM_LAYER
        if (!backpack && !worn) return false

        val itemId = packet.mid
        val slot = packet.arg2
        if (itemId == NO_ITEM || itemId < 0) return false

        return if (backpack) backpack(player, packet.buttonOp, itemId, slot)
        else worn(player, packet.buttonOp, itemId, slot)
    }

    internal fun rowForOp(player: WorldPlayer, iface: Int, component: Int, op: Int): Int? =
        rowForOp(player.interfaces.armedMaskFor(iface, component), op)

    /**
     * The mapping itself, as a pure function of the armed mask - so a headless check can drive the
     * same code the server does instead of a copy of it.
     */
    internal fun rowForOp(mask: Int?, op: Int): Int? {
        if (mask == null) return op - 1
        val index = enabledOps(mask).indexOf(op)
        return if (index < 0) null else index
    }

    /** The op slots a mask enables, in ascending order: bit `n` enables op `n`. */
    internal fun enabledOps(mask: Int): List<Int> = (1..10).filter { (mask shr it) and 1 == 1 }

    private fun backpack(player: WorldPlayer, op: Int, itemId: Int, slot: Int): Boolean {
        val container = PlayerInventory.backpackOf(player)
        val held = container[slot]
        // The client's view can lag a container change by a tick. Refusing on a mismatch is what
        // keeps a stale click from consuming whatever moved into that slot in the meantime.
        if (held == null || held.id != itemId) {
            logger.info {
                "itemOps ${player.name}: refused backpack op $op - the client says item $itemId is in " +
                    "slot $slot, the server has ${held?.id ?: "nothing"} there."
            }
            return true
        }
        val definition = SqliteItemCodec.load(itemId)
        // MENU ROW 4 USED TO BE UNREACHABLE HERE, and the repair is one layer down rather than in
        // this line. `items` has columns for widget_actions_0/1/2/4 only; `widget_actions_3` lives
        // in `items_attr` (2,649 rows, 172 of them "Add to tool belt" - every hatchet and pickaxe
 // among them), and until `SqliteItemCodec` could not see it, so every row-4
        // click resolved to null here and was dropped as "no menu row". That is exactly what the
        // operator hit when he could not put a tool on his belt. `SqliteItemCodec.load` now merges
        // the attr rows itself, so this line needs no fallback - see its KDoc, and [ItemActions]
        // for the by-action census built on the same merged definitions.
        // op -> ROW through the armed events mask; see [rowForOp]. `op - 1` was wrong for rows 4
        // and 5 and is the reason the tool belt could not be used.
        val row = rowForOp(player, BACKPACK_IFACE, BACKPACK_ITEM_LAYER, op)
        val action = if (row == null) null else definition?.inventoryActions?.getOrNull(row)
        val name = definition?.name ?: "item $itemId"
        if (action == null) {
            logger.info {
                "itemOps ${player.name}: $name has no option for op $op " +
                    "(menu row ${row?.plus(1) ?: "unmapped"}; the armed mask enables " +
                    "${player.interfaces.armedMaskFor(BACKPACK_IFACE, BACKPACK_ITEM_LAYER)
                        ?.let { m -> (1..10).filter { (m shr it) and 1 == 1 } } ?: "nothing"}) - ignored."
            }
            return true
        }
        val key = action.lowercase()
        return when {
            key in EQUIP_ACTIONS -> equip(player, container, slot, itemId, name, action)
            key in CONSUME_ACTIONS -> {
                container.removeSlot(slot)
                PlayerInventory.sendBackpack(player)
                player.client.write(MessageGame(0, "You ${key} the ${name.lowercase()}."))
                logger.info { "itemOps ${player.name}: $action $name (slot $slot) - consumed." }
                true
            }
            key == "drop" -> {
                player.client.write(MessageGame(0, "You cannot drop things here yet."))
                logger.info { "itemOps ${player.name}: Drop $name refused - no ground-item system in this repository." }
                true
            }
            else -> {
 //: the ONE extension point for a skill that owns a backpack menu row.
                // Firemaking's "Light" is the first ([Firemaking]); every hook is asked in
                // registration order and the first that claims the click ends it. Nothing above
                // this line changed: Eat / Wield / Drop still win, so a hook cannot hijack a verb
                // this file already implements.
                for (hook in backpackActionHooks) {
                    val claimed = runCatching { hook(player, action, itemId, name, slot) }.getOrElse {
                        logger.error(it) { "itemOps ${player.name}: a backpack action hook threw on '$action' $name - treated as unclaimed." }
                        false
                    }
                    if (claimed) return true
                }
                logger.info { "itemOps ${player.name}: '$action' on $name (slot $slot) is not implemented." }
                true
            }
        }
    }

    internal fun equip(
        player: WorldPlayer,
        container: com.opennxt.model.items.ItemContainer,
        slot: Int,
        itemId: Int,
        name: String,
        action: String
    ): Boolean {
        if (!PlayerInventory.equipEnabled) {
            logger.warn {
                "itemOps ${player.name}: '$action' on $name refused - " +
                    "-Dopennxt.experiment.equip is not true, so PlayerInventory.sendWorn would " +
                    "refuse to put inv 94 on the wire and the item would vanish from the backpack " +
                    "without appearing in the equipment panel."
            }
            player.client.write(MessageGame(0, "Equipment is disabled on this server."))
            return true
        }
        val definition = SqliteItemCodec.load(itemId)
        val wearSlot = definition?.equipSlotId
        if (wearSlot == null || wearSlot < 0 || wearSlot >= PlayerInventory.WORN_SIZE) {
            logger.info { "itemOps ${player.name}: $name declares no usable equipSlotId - refused." }
            player.client.write(MessageGame(0, "You can't wear that."))
            return true
        }
        val worn = player.worn
        val replaced = worn[wearSlot]
        val held = container[slot]
        if (held == null || held.id != itemId) {
            logger.info { "itemOps ${player.name}: refused $action - backpack slot $slot no longer holds $itemId." }
            return true
        }
        // Take the item out FIRST, so the displaced one has a slot to come back to even when the
        // backpack was full - a swap must never be able to destroy an item.
 // (audit E-06, I-01): the WHOLE stack moves - 100 arrows worn are 100 arrows,
        // not 1 with 99 destroyed; a same-id worn stack merges rather than being replaced.
        // not become a "stack of 2" the backpack can never take back), and only when the sum fits.
        val merge = replaced != null && replaced.id == held.id && container.stacks(held.id)
        if (merge && replaced!!.amount.toLong() + held.amount > Int.MAX_VALUE) {
            logger.info { "itemOps ${player.name}: refused $action - the worn stack cannot hold ${held.amount} more." }
            player.client.write(MessageGame(0, "You can't carry that many."))
            return true
        }
        container.removeSlot(slot)
        if (merge) {
            worn[wearSlot] = Item(itemId, replaced!!.amount + held.amount)
        } else {
            worn[wearSlot] = Item(itemId, held.amount)
        }
        if (replaced != null && !merge) {
            val back = container.add(replaced)
            if (!back.complete) {
                // Cannot happen with the removal above, and is not silently swallowed if it does.
                worn[wearSlot] = replaced
                container[slot] = held
                logger.error { "itemOps ${player.name}: swap aborted - no room for ${replaced.id}; nothing changed." }
                return true
            }
        }
        PlayerInventory.sendBackpack(player)
        PlayerInventory.sendWorn(player)
        player.entity.model.dirty = true
        logger.info {
            "itemOps ${player.name}: $action $name (item $itemId) from backpack slot $slot into worn " +
                "slot $wearSlot" + (if (replaced != null) ", ${replaced.id} back to the backpack" else "") + "."
        }
        return true
    }

    private fun worn(player: WorldPlayer, op: Int, itemId: Int, slot: Int): Boolean {
        if (!PlayerInventory.equipEnabled) return true
        val worn = player.worn
        val held = worn[slot]
        if (held == null || held.id != itemId) {
            logger.info {
                "itemOps ${player.name}: refused worn op $op - client says item $itemId in worn slot $slot, " +
                    "server has ${held?.id ?: "nothing"}."
            }
            return true
        }
        val container = PlayerInventory.backpackOf(player)
        if (container.isFull()) {
            player.client.write(MessageGame(0, "Your backpack is too full."))
            return true
        }
        worn.removeSlot(slot)
        val back = container.add(held)
        if (!back.complete) {
            // review #3: a worn stack the backpack cannot take whole goes back where it was, minus
            // what did fit - never destroyed. (isFull() above only guarantees ONE free slot.)
            val left = held.amount - back.added
            worn[slot] = Item(held.id, left)
            logger.warn { "itemOps ${player.name}: only ${back.added} of ${held.amount} x ${held.id} fit in the backpack; $left stay worn." }
        }
        PlayerInventory.sendBackpack(player)
        PlayerInventory.sendWorn(player)
        player.entity.model.dirty = true
        val name = SqliteItemCodec.load(itemId)?.name ?: "item $itemId"
        logger.info { "itemOps ${player.name}: removed $name from worn slot $slot (menu row $op)." }
        return true
    }
}
