package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.api.stat.ExperienceSource
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * The ONE place that knows both a [ContentPlayer] and a [WorldPlayer] for [Cooking].
 *
 * A deliberate copy of [SkillingWiring]'s shape, not a reuse of its map. Sharing the owner map
 * would make [Cooking] depend on [Skilling] being enabled - `SkillingWiring.install` returns
 * early and moves NO seams when `-Dopennxt.experiment.skilling=false`, and cooking has no reason
 * to stop working because gathering was switched off. The two maps hold the same pairs and cost
 * one weak reference each.
 */
object CookingWiring {

    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    /** Records that [content] is [world]'s content view. Idempotent and cheap. */
    fun bind(content: ContentPlayer, world: WorldPlayer) {
        if (owners[content]?.get() === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    /** How many live pairings are known. Observable so a check asserts on a number. */
    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    /**
     * Points [Cooking]'s seams at the live server and registers the fires.
     *
     * The integrator calls this from `OpenNXT.reloadContent` beside `SkillingWiring.install()`.
     * Returns the number of fire loc ids bound, 0 when cooking is switched off.
     */
    fun install(): Int {
        if (!Cooking.enabled) {
            logger.warn { "cooking wiring: cooking is disabled, leaving the ContentPlayer-only seams in place" }
            return 0
        }

        // The SAME container the login path sends and WorldPlayer.toSave persists, not a copy -
        // PlayerInventory.backpackOf is memoised per player, so a cooked crayfish is in the
        // backpack that gets saved.
        Cooking.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        // The BOOSTED level, which is what the rest of the server already treats as "the player's
        // level" - and it is the right one for cooking: the wiki's Cooking article lists temporary
        // boosts under "Preventing burning", so a boost is meant to reduce the burn chance.
        Cooking.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP goes through PlayerStatContainer.addExperience for the reason SkillingWiring states:
        // the container owns the boosted-level move and the UPDATE_STAT refresh, and that refresh
        // un-gates it.
        Cooking.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, COOKING_SOURCE)
        }

        Cooking.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Cooking.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }

        Cooking.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
        }

 //the player turns to face the fire on the tick the cook starts.
        // PLAYER_INFO mask bit 7; see Cooking.faceSink and PlayerFaceDirectionBlock.towards.
        Cooking.faceSink = { content, angle ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        // The live tile, so the movement stop in Cooking.tick compares against the entity rather
        // than against a ContentPlayer copy that only the packet handler ever updates.
        Cooking.locationSupplier = { content ->
            ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: content.location
        }

        // A disconnect must stop the action. The proper fix is a Cooking.stopFor call in
        // World.cullDisconnected beside the Skilling one, and World.kt is not this pass's file;
        // until the integrator adds it, this seam makes the next tick notice on its own.
        Cooking.onlineCheck = { content ->
            val owner = ownerOf(content)
            // runCatching: OpenNXT.world is a lateinit-style singleton and a check that installs
            // the wiring headlessly must not blow up on the first tick. No world = no evidence of
            // a logout, so the action keeps running (the movement stop still applies).
            val world = runCatching { OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

        val bound = Cooking.install()
        logger.info {
            "cooking wiring: backpack, level, xp, animation and message seams now point at the live " +
                "player; $bound fire loc(s) bound, ${Cooking.recipes.size} recipes"
        }
        return bound
    }

    /**
     * Puts the ContentPlayer-only seams back. Only needs
     * this, and it needs it for the reason SkillingWiring.uninstall gives: installing the live
     * seams inside a headless check would make every cycle depend on a World.
     */
    fun uninstall() {
        Cooking.containerSupplier = { it.inventory }
        Cooking.levelSupplier = { _, _ -> 1 }
        Cooking.xpSink = { _, _, _ -> }
        Cooking.inventoryResend = { false }
        Cooking.animationSink = { _, _ -> }
        Cooking.messageSink = { _, _ -> }
        Cooking.faceSink = { _, _ -> }
        Cooking.onlineCheck = { true }
        Cooking.locationSupplier = { it.location }
        // Back to the STRICT reading. `firePresent` is the one seam whose ContentPlayer-only
        // default is not "permissive": a module with no wiring must refuse to cook on a fire it
        // cannot see, not trust the packet. See Cooking.firePresent.
        Cooking.firePresent = { locId, x, z, plane -> Cooking.defaultFirePresent(locId, x, z, plane) }
        clear()
    }

    /**
     * The [ExperienceSource] cooking xp rides on. `boostFactor` 1.0 for the reason
     * [SkillingWiring.SKILLING_SOURCE] gives - the fire's 10% is already inside
     * [Cooking.MEASURED_XP_TENTHS] / [Cooking.FIRE_XP_BONUS_PERCENT], and applying it twice is
     * exactly the kind of invented rate that constant exists to avoid.
     */
    object COOKING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "COOKING"
    }
}
