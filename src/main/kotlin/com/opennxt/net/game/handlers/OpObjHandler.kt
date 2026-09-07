package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.PickupResult
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpObj
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec
import mu.KotlinLogging

/**
 * A click on a ground object - a dropped item on a tile: OPOBJ1..6 (949 opcodes
 * 28, 64, 143, 43, 45, 68).
 *
 * ONE handler for six opcodes; [OpObj.option] carries which row was chosen.
 */
object OpObjHandler : GamePacketHandler<WorldPlayer, OpObj> {
    private val logger = KotlinLogging.logger { }

    /**
     * Which OPOBJ option means Take. See the class doc for the cache evidence
     * and for why this is a property rather than a constant.
     */
    val takeOption: Int = System.getProperty("opennxt.groundItems.takeOption")?.toIntOrNull() ?: 3

    /** Treat EVERY option as Take. For settling [takeOption] with one live click. */
    val takeAnyOption: Boolean = System.getProperty("opennxt.groundItems.takeAnyOption") == "true"

    /**
     * Ground items this server has at ([x], [y], [plane]).
     */
    internal fun itemsAt(x: Int, y: Int, plane: Int): List<GroundItem> {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return emptyList()
        return world.groundItems.itemsAt(x, y, plane)
    }

    /** Whether [option] is the take row. Extracted so the check exercises it. */
    internal fun isTake(option: Int): Boolean = takeAnyOption || option == takeOption

    /**
     * Whether [option] is the take row FOR THIS ITEM.
     */
    internal fun isTake(itemId: Int, option: Int): Boolean {
        if (takeAnyOption) return true
        if (!RsDatabase.available) return isTake(option)
        return com.opennxt.content.impl.GroundOptions.isTake(itemId, option)
    }

    override fun handle(context: WorldPlayer, packet: OpObj) {
        val plane = context.entity.location.plane
        val def = if (RsDatabase.available) SqliteItemCodec.load(packet.id) else null
        val name = def?.name ?: "unknown obj"

        val here = itemsAt(packet.x, packet.y, plane)
        val target = here.firstOrNull { it.itemId == packet.id }

        logger.info {
            "OPOBJ${packet.option} ${context.name} clicked $name (obj ${packet.id}) at " +
                "(${packet.x},${packet.y},plane $plane) option ${packet.option}" +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (packet.inputBit) " input-bit-set" else "") +
                (if (target != null) " [this server has that item on that tile]"
                else " [this server has ${here.size} item(s) there]") +
                (if (RsDatabase.available) "" else " [no rs3.sqlite - name unverified]")
        }

        if (target == null) {
            logger.warn {
                "OPOBJ${packet.option}: this server has no ground item ${packet.id} at " +
                    "(${packet.x},${packet.y},plane $plane). Either the client is naming something it was " +
                    "never told about, or this server's tile model disagrees with the scene it built. " +
                    "Walking anyway; nothing is picked up."
            }
            MoveGameClickHandler.walk(context, packet.x, packet.y, "OPOBJ${packet.option}")
            return
        }

        if (!isTake(packet.id, packet.option)) {
            // The one log line that answers "then which option IS Take". It
            // fires only when the option is not the configured one AND the item
            // is real, so a stray click on empty ground cannot produce it.
            logger.warn {
                "OPOBJ${packet.option} named a REAL ground item ($name), option " +
                    "'${com.opennxt.content.impl.GroundOptions.optionFor(packet.id, packet.option) ?: "(empty row)"}' " +
                    "- not a take, so nothing was taken. Walking to the tile."
            }
            MoveGameClickHandler.walk(context, packet.x, packet.y, "OPOBJ${packet.option}")
            return
        }

        // ------------------------------------------------------------------
 // THE LOOT WINDOW. The reference client does NOT take on this click.
        //
        // The operator's report was "clicking something on the ground doesn't
        // bring up the loot menu, it just adds it to the backpack", and the
        // the protocol agree with the operator rather than with this
        // the tick AFTER one is UPDATE_INV_FULL(inv 773) + IF_OPENSUB(1622 ->
        // 1477:705) + IF_SETEVENTS(1622:11) - never an OBJ_DEL for the clicked
        // item. The take happens later, on an IF_BUTTON1 inside the window.
        // Everything measured is in [com.opennxt.content.impl.LootWindow].
        //
        // The direct take below is kept as the fallback and is what
 // -Dopennxt.experiment.lootWindow=false restores, so the pre-
        // behaviour is one flag away and the change is bisectable.
        // ------------------------------------------------------------------
        if (com.opennxt.content.impl.LootWindow.open(context, packet.x, packet.y, plane)) return

        take(context, target)
    }

    /**
     * The take itself. `internal` so the check drives the REAL path.
     *
     * Order matters and is not cosmetic:
     *
     *  1. [com.opennxt.model.world.GroundItems.pickup] moves units out of the
     *     ground and into the backpack, or refuses with a typed reason. That
     *     model is the authority on range and capacity; this function adds no
     *     rules of its own.
     *  2. The backpack is re-sent only when something actually moved. A refused
     *     take must not produce an inventory packet, or a full backpack would
     *     look like a pickup that succeeded and did nothing.
     *  3. `OBJ_DEL` is NOT sent from here. The item is already out of the
     *     model, and [com.opennxt.model.world.GroundItemTransmitter.sync] runs
     *     later in this same tick ([com.opennxt.model.world.World.tick] handles
     *     incoming packets before it ticks players), sees it gone and sends the
     *     delete behind its own zone cursor. ONE code path owns what a client
     *     has been told; a second one racing it is how a double-delete gets
     *     written.
     */
    internal fun take(player: WorldPlayer, item: GroundItem) {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return
        val backpack = PlayerInventory.backpackOf(player)
        val before = item.quantity
        when (val result = world.groundItems.pickup(player.entity.location, item, backpack, player.name)) {
            is PickupResult.PickedUp -> {
                logger.info { "${player.name} took ${item.itemName} x$before (obj ${item.itemId})" }
                PlayerInventory.sendBackpack(player)
            }

            is PickupResult.Partial -> {
                logger.info {
                    "${player.name} took ${result.add.added} of ${item.itemName} x$before; " +
                        "${result.add.remaining} left on the ground (the backpack had no room for the rest)"
                }
                PlayerInventory.sendBackpack(player)
            }

            is PickupResult.ContainerFull ->
                logger.info { "${player.name} could not take ${item.itemName}: backpack full. The item stays." }

            is PickupResult.TooFar -> {
                logger.info {
                    "${player.name} is ${result.distance} tile(s) from ${item.itemName} " +
                        "(max ${result.allowed}); walking to it instead of taking it"
                }
                MoveGameClickHandler.walk(player, item.tile.x, item.tile.y, "OPOBJ-take")
            }

            is PickupResult.NotOnGround ->
                logger.info { "${player.name} clicked ${item.itemName}, but it is no longer on the ground" }

            is PickupResult.NotYours ->
                logger.info {
                    "${player.name} tried to take ${item.itemName} owned by ${result.owner}; public in " +
                        "${result.ticksUntilPublic} tick(s). Refused."
                }
        }
    }
}
