package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

/**
 * The ONE file that knows both a [com.opennxt.content.ContentPlayer] and a [WorldPlayer] for the
 * tool belt, shaped on [FiremakingWiring] and [BuryWiring].
 *
 * ## How a click gets here
 *
 * `Add to tool belt` is a BACKPACK menu row - row **4** for 172 of the 191 items that carry it -
 * so it arrives at [ItemOps.handleButton], which resolves the row to the cache's own option
 * string (through [ItemActions], which is what makes row 4 resolvable at all) and offers it to
 * [ItemOps.backpackActionHooks]. [install] adds one hook.
 *
 * ## Where the belt actually lives
 *
 * [WorldPlayer.toolbeltIds] / [WorldPlayer.addToToolbelt], persisted through
 * [com.opennxt.model.account.PlayerSave.toolbelt]. [ToolBelt] never sees a [WorldPlayer]; it asks
 * the two seams below. `beltReader` returning **null** is what tells the module there is no belt
 * at all, which is the only case in which it must refuse rather than consume.
 */
object ToolBeltWiring {

    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(ToolBelt.ADD_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = ToolBelt.add(content, itemId, name, slot, action)
            result.outcome != ToolBelt.Outcome.NOT_MINE
        }
    }

    fun install(): Boolean {
        if (!ToolBelt.enabled) {
            logger.warn { "toolbelt wiring: the tool belt is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        ToolBelt.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        // NULL means "this content player has no persistent belt", which is not the same as an
        // empty belt and is the reason this seam is nullable at all - see ToolBelt.beltReader.
        ToolBelt.beltReader = { content -> SkillingWiring.ownerOf(content)?.toolbeltIds() }

        ToolBelt.beltWriter = { content, itemId ->
            SkillingWiring.ownerOf(content)?.addToToolbelt(itemId) ?: false
        }

        ToolBelt.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        ToolBelt.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "toolbelt wiring: the '${ToolBelt.ADD_ACTION}' backpack row is hooked over " +
                "${runCatching { ToolBelt.beltItemCount() }.getOrDefault(-1)} beltable item ids " +
                "(the option string and the population are CACHE-DERIVED; the base tier " +
                "${ToolBelt.baseTierIds().sorted()} is Skilling.TOOLBELT_BASE. " +
                "The three replies are INVENTED - nothing observed holds an 'Add to tool belt' click. " +
                "Skilling.toolIn READS the stored belt (Skilling.beltSupplier, bound " +
                "by SkillingWiring to ToolBelt.storedIdsFor), so a belted hatchet chops; the union is " +
                "ADDITIVE, so an empty belt is exactly the pre-09-07 behaviour."
        }
        return true
    }

    /** Puts the ContentPlayer-only seams back and removes the hook. Only the check needs this. */
    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        ToolBelt.resetSeams()
        installed = false
    }
}
