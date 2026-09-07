package com.opennxt.content.impl

import com.opennxt.api.stat.ExperienceSource
import com.opennxt.api.stat.Stat
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * The ONE place that knows both a [ContentPlayer] and a [WorldPlayer].
 */
object SkillingWiring {

    private val logger = KotlinLogging.logger { }

    /**
     * ContentPlayer -> WorldPlayer, both held weakly.
     */
    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    /** Records that [content] is [world]'s content view. Idempotent and cheap. */
    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    /** How many live pairings are known. Observable so a check asserts on a number. */
    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    /** Test seam: forget every pairing. */
    internal fun clear() = synchronized(owners) { owners.clear() }

    /**
     * Points [Skilling]'s three seams at the live server.
     *
     * Called from [com.opennxt.OpenNXT.reloadContent] right after
     * [Skilling.install]. Returns false when skilling is switched off, so the
     * seams are not moved for a module that will never fire.
     */
    fun install(): Boolean {
        if (!Skilling.enabled) {
            logger.warn { "skilling wiring: skilling is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        // Where a chopped log goes: the SAME container the login path sends and
        // WorldPlayer.toSave persists. Not a copy - PlayerInventory.backpackOf
        // is memoised per player, so the log is in the backpack that gets saved.
        Skilling.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        // The level a requirement is compared against: the BOOSTED level, which
        // is what PlayerStatContainer.getLevel returns by default and what the
        // rest of the server already treats as "the player's level".
        Skilling.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP. This is the ONLY route by which skilling xp reaches a player, and
        // it deliberately goes through PlayerStatContainer.addExperience rather
        // than PlayerStatData.addExperience directly: the container is what
        // moves boostedLevel with the delta and what owns the UPDATE_STAT
        // refresh - which is gated behind -Dopennxt.experiment.sendStats=true
        // unset the award lands in the container and in the save, and
        // PlayerStatContainer.suppressedRefreshes() counts the withheld write.
        Skilling.xpSink = { content, stat, amount ->
            val world = ownerOf(content)
            if (world != null) {
                world.stats.addExperience(stat, amount, SKILLING_SOURCE)
            }
        }

        Skilling.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) {
                PlayerInventory.sendBackpack(world)
                true
            } else {
                false
            }
        }

        Skilling.wornSupplier = { content -> ownerOf(content)?.let { PlayerInventory.wornOf(it) } }

 // The player's own tool belt. Additive: empty belt == the old behaviour.

        Skilling.beltSupplier = { content -> ToolBelt.storedIdsFor(content) }
        Gatherables.containerSupplier = Skilling.containerSupplier
        Gatherables.inventoryResend = { content -> Skilling.inventoryResend(content) }
        Gatherables.messageSender = { content, msg ->
            val world = ownerOf(content)
            if (world != null) {
                world.client.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
            }
        }
        Searchables.messageSender = Gatherables.messageSender
 //the per-cycle animation in all four slots, and the yield message
        Skilling.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }
        Skilling.messageSink = Gatherables.messageSender
 //the player turns to face the node on the arrival tick.
        // PLAYER_INFO mask bit 7, the 14-bit angle - see Skilling.faceSink and
        // PlayerFaceDirectionBlock.towards for the 46-observation fit behind the number.
        Skilling.faceSink = { content, angle ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        logger.info {
            "skilling wiring: backpack, level and xp seams now point at the live player. " +
                "UPDATE_STAT stays gated (sendStats=" +
                "${com.opennxt.impl.stat.PlayerStatContainer.sendStatsEnabled}); xp accrues and persists " +
                "but is NOT transmitted."
        }
        return true
    }

    /**
     * Puts the ContentPlayer-only seams back. Only
     * needs this, and it needs it because installing the live seams inside a
     * headless check would make every gather depend on a World.
     */
    fun uninstall() {
        Skilling.containerSupplier = { it.inventory }
        Skilling.wornSupplier = { null }
        Skilling.beltSupplier = { emptySet() }
        Skilling.levelSupplier = { _, _ -> 1 }
        Skilling.xpSink = { _, _, _ -> }
        Skilling.inventoryResend = { false }
        clear()
    }

    /**
     * The [ExperienceSource] skilling awards ride on.
     *
     * `boostFactor` 1.0: a bonus-xp or double-xp multiplier is not modelled and
     * a factor other than 1 here would be an invented rate on top of an already
     * invented rate. Named so a log or a save can tell skilling xp from combat
     * xp later.
     */
    object SKILLING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "SKILLING"
    }

    /** The container a gather would go into, for callers that want to look without gathering. */
    fun containerOf(content: ContentPlayer): ItemContainer = Skilling.containerSupplier(content)

    /** The level a requirement would be compared against. */
    fun levelOf(content: ContentPlayer, stat: Stat): Int = Skilling.levelSupplier(content, stat)
}
