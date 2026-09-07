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
 * The ONE place that knows both a [ContentPlayer] and a [WorldPlayer] for [Smithing].
 *
 * A deliberate copy of [CookingWiring]'s shape (which is itself a copy of [SkillingWiring]'s),
 * and not a reuse of either map, for the reason [CookingWiring]'s class doc gives: sharing a map
 * would make smithing depend on another module being enabled, and a smelt has no business
 * stopping because gathering was switched off. Everything those two docs argue about weak keys,
 * weak values and degradation applies here word for word.
 *
 * ## How the click gets here
 *
 * "Smelt", "Heat" and "Smith" are not in `ResourceNodes.isGatherAction` and are neither "Bank"
 * nor "Use", so `OpLocHandler.handle` -> `BanksWiring.handleLocClick` hands them to
 * [LocWiring.routeOther], which is the generic loc router and is ON by default
 * (`-Dopennxt.experiment.loc.dispatch` is `!= "false"`). That router binds Banks, Dialogue and
 * Skilling before it dispatches; [bind] is called there too, added by this pass, so the seams
 * below see the real backpack rather than the ContentPlayer's throwaway one.
 */
object SmithingWiring {

    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        Collections.synchronizedMap(WeakHashMap())

    /** Records that [content] is [world]'s content view. Idempotent and cheap. */
    fun bind(content: ContentPlayer, world: WorldPlayer) {
        if (owners[content]?.get() === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    /**
     * The WorldPlayer this content view belongs to.
     *
     * Falls back to [SkillingWiring.ownerOf], which `LocWiring.routeOther` has always bound on
     * the same line: if this pass's `bind` call is ever removed from the router the module
     * degrades to using the skilling map rather than silently smelting into a throwaway
     * inventory, which is the data-loss bug `OpLocHandler`'s own doc warns about.
     */
    fun ownerOf(content: ContentPlayer): WorldPlayer? =
        owners[content]?.get() ?: SkillingWiring.ownerOf(content)

    /** How many live pairings this map holds. Observable so a check asserts on a number. */
    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    /**
     * Points [Smithing]'s seams at the live server and binds the three loc actions.
     *
     * The integrator calls this from `OpenNXT.reloadContent` beside `SkillingWiring.install()`
     * and `CookingWiring.install()`. Returns the number of loc ids bound, 0 when smithing is off.
     */
    fun install(): Int {
        if (!Smithing.enabled) {
            logger.warn { "smithing wiring: smithing is disabled, leaving the ContentPlayer-only seams in place" }
            return 0
        }

        // The SAME container the login path sends and WorldPlayer.toSave persists, not a copy.
        Smithing.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        // The BOOSTED level, which is what the rest of the server treats as "the player's level".
        // It is also the level [Smithing.heatMax] reads, so a Smithing boost raises the heat
        // ceiling - untested against the reference client, and named here so the assumption is visible.
        Smithing.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP through PlayerStatContainer.addExperience for the reason SkillingWiring states: the
        // container owns the boosted-level move and the UPDATE_STAT refresh, and that refresh is
        // gated behind -Dopennxt.experiment.sendStats. Nothing here un-gates it.
        Smithing.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, SMITHING_SOURCE)
        }

        Smithing.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Smithing.animationSink = { content, ids ->
            ownerOf(content)?.let { world ->
                com.opennxt.model.entity.rendering.PlayerUpdates.animate(
                    world.entity, com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock(ids, 0)
                )
            }
        }

        Smithing.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(com.opennxt.net.game.serverprot.MessageGame(0, msg))
        }

        // The live tile, so the movement stop in Smithing.tick compares against the entity rather
        // than against a ContentPlayer copy only the packet handler updates.
        Smithing.locationSupplier = { content ->
            ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: content.location
        }

        // A disconnect must stop the action. The proper fix is a Smithing.cull call in
        // World.cullDisconnected beside the Skilling one, and World.kt is not this pass's file;
        // until the integrator adds it, this seam makes the next tick notice on its own.
        Smithing.onlineCheck = { content ->
            val owner = ownerOf(content)
            val world = runCatching { OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

        val bound = Smithing.install()
        logger.info {
            "smithing wiring: backpack, level, xp, animation and message seams now point at the live " +
                "player; $bound loc id(s) bound, ${Smithing.smeltRecipes.size} smelt and " +
                "${Smithing.smithRecipes.size} smith recipes"
        }
        return bound
    }

    /**
     * Puts the ContentPlayer-only seams back. Only needs
     * it, and for the reason SkillingWiring.uninstall gives: installing the live seams inside a
     * headless check would make every cycle depend on a World.
     */
    fun uninstall() {
        Smithing.containerSupplier = { it.inventory }
        Smithing.levelSupplier = { _, _ -> 1 }
        Smithing.xpSink = { _, _, _ -> }
        Smithing.inventoryResend = { false }
        Smithing.animationSink = { _, _ -> }
        Smithing.messageSink = { _, _ -> }
        Smithing.onlineCheck = { true }
        Smithing.locationSupplier = { it.location }
        Smithing.smeltChoice = Smithing.DEFAULT_SMELT_CHOICE
        Smithing.smithChoice = Smithing.DEFAULT_SMITH_CHOICE
        clear()
    }

    /**
     * The [ExperienceSource] smithing xp rides on. `boostFactor` 1.0 for the reason
     * [SkillingWiring.SKILLING_SOURCE] gives: a multiplier here would be an invented rate on top
     * of the cache's own per-recipe number.
     */
    object SMITHING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "SMITHING"
    }
}
