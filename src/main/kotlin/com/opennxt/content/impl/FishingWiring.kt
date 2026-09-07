package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.api.stat.ExperienceSource
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

/**
 * Points [Fishing]'s seams at the live server. The sibling of [SkillingWiring], and it borrows
 * that object's ContentPlayer -> WorldPlayer map rather than keeping a second one.
 */
object FishingWiring {

    private val logger = KotlinLogging.logger { }

    /**
     * The [WorldPlayer] behind a content view, out of [SkillingWiring]'s one map.
     */
    fun ownerOf(content: ContentPlayer): WorldPlayer? = SkillingWiring.ownerOf(content)

    /**
     * The live npc at NPC_INFO slot [index], or null. Null when there is no World at all.
     */
    fun npcAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)?.takeIf { it.alive }
    }

    /**
     * Points every [Fishing] seam at the live player. Called from `OpenNXT.reloadContent` right
     * after [Fishing.install]. Returns false when fishing is switched off, so the seams are not
     * moved for a module that will never fire.
     */
    fun install(): Boolean {
        if (!Fishing.enabled) {
            logger.warn { "fishing wiring: fishing is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        // The SAME backpack the login path sends and WorldPlayer.toSave persists - PlayerInventory
        // memoises it per player, so a caught crayfish is in the backpack that gets saved.
        Fishing.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }
        Fishing.wornSupplier = { content -> ownerOf(content)?.let { PlayerInventory.wornOf(it) } }

        // The BOOSTED level, which is what the rest of the server treats as "the player's level".
        Fishing.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP through PlayerStatContainer.addExperience, whose UPDATE_STAT refresh is gated behind
        // lands in the container and in the save and the withheld write is counted.
        Fishing.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, FISHING_SOURCE)
        }

        Fishing.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        // all four animation slots, delay 0, on the arrival tick and every cycle tick.
        Fishing.animationSink = { content, ids ->
            ownerOf(content)?.let { world -> PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0)) }
        }
        Fishing.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(MessageGame(0, msg))
        }

        // ARRIVAL IS AN EVENT. `Movement.hasSteps` is `queue.isNotEmpty()` (Movement.kt:33), the
        // same state `Movement.process` reads before it fires an `onArrival` callback - so this
        // answers exactly the question OpLocHandler's callback answers, without needing to own a
        // callback slot a re-targetable fishing action would keep clobbering. See
        // [Fishing.standingStill] for the defect this closes. Falls back to TRUE (standing still)
        // for an unbound player, which is the headless behaviour every check drives.
        Fishing.standingStill = { content ->
            val world = ownerOf(content)
            if (world == null) true else runCatching { !world.entity.movement.hasSteps }.getOrDefault(true)
        }

        // The spot. `alive` is what ends the action when the spot moves or is despawned; the tile
        // is what the arrival test measures against, because a spot that stepped between the click
        // and the arrival must be judged where it is now.
        Fishing.spotAlive = { index -> npcAtIndex(index) != null }
        Fishing.spotTile = { index ->
            npcAtIndex(index)?.location?.let { intArrayOf(it.x, it.y, it.plane) }
        }

        logger.info {
            "fishing wiring: backpack, worn, level, xp, animation, message and the two spot seams now " +
                "point at the live player. UPDATE_STAT stays gated " +
                "(sendStats=${com.opennxt.impl.stat.PlayerStatContainer.sendStatsEnabled}); xp accrues " +
                "and persists but is NOT transmitted."
        }
        return true
    }

    /**
     * Puts the ContentPlayer-only seams back. Only needs
     * this, and it needs it because installing the live seams inside a headless check would make
     * every cycle depend on a World.
     */
    fun uninstall() {
        Fishing.containerSupplier = { it.inventory }
        Fishing.wornSupplier = { null }
        Fishing.levelSupplier = { _, _ -> 1 }
        Fishing.xpSink = { _, _, _ -> }
        Fishing.inventoryResend = { false }
        Fishing.animationSink = { _, _ -> }
        Fishing.messageSink = { _, _ -> }
        Fishing.spotAlive = { true }
        Fishing.spotTile = { null }
        Fishing.standingStill = { true }
    }

    /**
     * The [ExperienceSource] fishing awards ride on.
     *
     * `boostFactor` 1.0 for the reason [SkillingWiring.SKILLING_SOURCE] gives: a bonus-xp or
     * double-xp multiplier is not modelled and a factor other than 1 here would be an invented
     * rate on top of a measured one. Named so a log or a save can tell fishing xp from the rest.
     */
    object FISHING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "FISHING"
    }
}
