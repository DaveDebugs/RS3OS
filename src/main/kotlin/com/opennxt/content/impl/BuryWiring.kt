package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

/**
 * The ONE file that knows both a [com.opennxt.content.ContentPlayer] and a [WorldPlayer] for
 * burying, shaped on [FiremakingWiring] and for the reason its KDoc gives.
 *
 * ## How a click gets here
 *
 * Not through [com.opennxt.content.ContentRegistry]: `Bury` is a BACKPACK menu row (61 of the 62
 * buryables carry it on row 1, `Corpse of woman` on row 3), so it arrives at [ItemOps.handleButton],
 * which resolves the row to the cache's own option string and offers it to
 * [ItemOps.backpackActionHooks]. [install] adds one hook. That is the whole route.
 *
 * ## The pairing
 *
 * [SkillingWiring.bind] is reused rather than duplicated, exactly as [FiremakingWiring] does: its
 * map is documented as "the ONE place that knows both", it is weakly keyed on both sides, and the
 * hook has both objects in hand.
 */
object BuryWiring {

    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    /** Whether [install] has bound the hook and the seams. Observable so a check asserts a number. */
    fun isInstalled(): Boolean = installed

    /**
     * Held as a field so [install] is idempotent (two hooks would bury two bones for one click)
     * and so [uninstall] removes exactly it.
     */
    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(Bury.BURY_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Bury.bury(content, itemId, name, slot, action)
            // NOT_MINE means the module declined (switched off): let ItemOps print its own line.
            // Every other outcome - including the refusals - is a decision this module made.
            result.outcome != Bury.Outcome.NOT_MINE
        }
    }

    fun install(): Boolean {
        if (!Bury.enabled) {
            logger.warn { "bury wiring: burying is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        // The SAME backpack the login path sends and WorldPlayer.toSave persists - not a copy.
        Bury.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        // XP through PlayerStatContainer.addExperience, whose UPDATE_STAT refresh is gated behind
        // on the bury tick, this repository accrues the same 4.5 and withholds the packet.
        Bury.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        // The type is the 109, not the 0 the rest of this repository passes - see Bury.MESSAGE_TYPE.
        Bury.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        Bury.animationSink = { content, ids ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0))
            }
        }

        Bury.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "bury wiring: the '${Bury.BURY_ACTION}' backpack row is hooked over " +
                "${runCatching { Bury.buryableItemCount() }.getOrDefault(-1)} buryable item ids; " +
                "${Bury.BONES_XP_TENTHS / 10.0} ${Bury.STAT.name} xp per Bones and " +
                "animation ${Bury.BURY_ANIMATION[0]} with '${Bury.BURY_MESSAGE}' type ${Bury.MESSAGE_TYPE} " +
                " (all); every other bone is priced by the " +
                "wiki layer, and the ${runCatching { Bury.buryableItemCount() - Bury.wikiTable().size }.getOrDefault(-1)} " +
                "the wiki does not name fall back to INVENTED."
        }
        return true
    }

    /**
     * Puts the ContentPlayer-only seams back and removes the hook. Only
     * needs this, for the reason
     * [FiremakingWiring.uninstall] gives.
     */
    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Bury.resetSeams()
        installed = false
    }
}
