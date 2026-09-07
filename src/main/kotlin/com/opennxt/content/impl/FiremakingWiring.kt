package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

/**
 * The ONE file that knows both a [ContentPlayer] and a [WorldPlayer] for firemaking.
 *
 * Shaped on [SkillingWiring] and for the same reason its KDoc gives: [Firemaking] reaches the live
 * player only through function-valued seams and imports nothing from `model.world` except the two
 * plain value types [TileLocation] and `LocChanges.Key`, so it can be driven by a check with no
 * socket and no World.
 *
 * ## How a click gets here
 *
 * Not through [com.opennxt.content.ContentRegistry]: lighting logs is a BACKPACK menu row
 * (IF_BUTTON2 on 1473:5, see [Firemaking]'s class doc), not a loc or npc option. It arrives at
 * [ItemOps.handleButton], which resolves the row to the cache's own option string and - for a verb
 * it does not implement itself - offers it to [ItemOps.backpackActionHooks]. [install] adds one
 * hook. That is the whole route, and it is why there is no `onLocAction` call anywhere in this
 * module.
 *
 * ## The pairing
 *
 * [SkillingWiring.bind] is reused rather than duplicated - its map is documented as "the ONE place
 * that knows both", it is weakly keyed on both sides, and the hook has both objects in hand. This
 * file adds no second identity map.
 */
object FiremakingWiring {

    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    /** Whether [install] has bound the hook and the seams. Observable so a check asserts a number. */
    fun isInstalled(): Boolean = installed

    /**
     * The hook itself, held as a field so [install] is idempotent (adding it twice would light two
     * fires for one click) and so [uninstall] can remove exactly it.
     */
    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
        if (!action.equals(Firemaking.LIGHT_ACTION, ignoreCase = true)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Firemaking.light(content, itemId, name, slot, action)
            // NOT_MINE means the module declined (switched off): let ItemOps print its own line.
            result.outcome != Firemaking.Outcome.NOT_MINE
        }
    }

    /**
     * Points [Firemaking]'s seams at the live server and adds the [ItemOps] hook.
     *
     * Returns false when firemaking is switched off, so the seams are not moved for a module that
     * will never fire - the same contract [SkillingWiring.install] has.
     */
    fun install(): Boolean {
        if (!Firemaking.enabled) {
            logger.warn { "firemaking wiring: firemaking is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        // The SAME backpack the login path sends and WorldPlayer.toSave persists - not a copy.
        Firemaking.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Firemaking.levelSupplier = { content, stat ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP through PlayerStatContainer.addExperience, whose UPDATE_STAT refresh is gated behind
        // on the catch tick; this repository accrues the same 40 and withholds the packet.
        Firemaking.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        Firemaking.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        Firemaking.messageSink = { content, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(0, msg))
        }

        Firemaking.animationSink = { content, ids ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0))
            }
        }

        // Where the player REALLY is - the entity's tile, not the ContentPlayer's, which OpLoc only
        // updates on a loc click. The fire lands on this tile, so getting it wrong puts the fire in
        // the wrong place, which is exactly what the check pins.
        Firemaking.tileSupplier = { content ->
            SkillingWiring.ownerOf(content)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) }
                ?: content.location
        }

        Firemaking.canStep = { from, dx, dz ->
            runCatching { CollisionMap.canStep(from.x, from.y, dx, dz, from.plane) }.getOrDefault(false)
        }

 //the catch tick carries FACE_DIRECTION as well as the loc, the
        // message and the xp. PLAYER_INFO mask bit 7; see Firemaking.faceSink.
        Firemaking.faceSink = { content, angle ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.faceDirection(world.entity, angle)
            }
        }

        // The reference client sends the step off the fire's tile as a TELEPORT-form PLAYER_INFO update (type 3,
        // a 1-tile local jump), not as a walk step - measured on every fire whose step carried a
        // delta. Movement.teleport is this repository's own type-3 path, so the wire shape matches.
        Firemaking.stepSink = { content, to ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) false else {
                world.entity.movement.teleport(TileLocation(to.x, to.y, to.plane))
                content.location = TileLocation(to.x, to.y, to.plane)
                true
            }
        }

        // The logs the reference client drops on the ground for the length of the wait (OBJ_ADD 1511 x1 on the
        // attempt tick, OBJ_DEL on the catch tick). Owned by the lighter, so a second player cannot
        // take the logs out from under a fire that is about to catch.
        Firemaking.groundAdd = { content, itemId, itemName, tile ->
            val world = runCatching { OpenNXT.world }.getOrNull()
            if (world == null) null else runCatching {
                world.groundItems.spawnItem(
                    itemId = itemId, itemName = itemName, quantity = 1,
                    tile = TileLocation(tile.x, tile.y, tile.plane),
                    owner = content.name, source = "firemaking"
                )
            }.getOrNull()
        }

        Firemaking.groundRemove = { handle ->
            val world = runCatching { OpenNXT.world }.getOrNull()
            if (world == null || handle !is GroundItem) false else world.groundItems.remove(handle)
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        logger.info {
            "firemaking wiring: backpack, level, xp, animation, tile and step seams now point at the live player; " +
                "the '${Firemaking.LIGHT_ACTION}' backpack row is hooked. Fire loc ${Firemaking.FIRE_LOC} " +
                "shape ${Firemaking.FIRE_SHAPE} rot ${Firemaking.FIRE_ROTATION}, ${Firemaking.LOGS_XP_TENTHS / 10.0} xp " +
                "per Logs; catch ${Firemaking.CATCH_PERCENT}%/tick (FITTED); " +
                "fire lasts ${Firemaking.FIRE_TICKS} ticks (INVENTED, > the measured ${Firemaking.MEASURED_MIN_FIRE_TICKS})."
        }
        return true
    }

    /**
     * Puts the ContentPlayer-only seams back and removes the hook.
     */
    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Firemaking.containerSupplier = { it.inventory }
        Firemaking.levelSupplier = { _, _ -> 1 }
        Firemaking.xpSink = { _, _, _ -> }
        Firemaking.messageSink = { _, _ -> }
        Firemaking.animationSink = { _, _ -> }
        Firemaking.inventoryResend = { false }
        Firemaking.tileSupplier = { it.location }
        Firemaking.canStep = { _, _, _ -> true }
        Firemaking.stepSink = { _, _ -> false }
        Firemaking.groundAdd = { _, _, _, _ -> null }
        Firemaking.groundRemove = { false }
        Firemaking.faceSink = { _, _ -> }
        installed = false
    }

    /**
     * The hook, exposed so a check can drive the ItemOps route without a WorldPlayer being needed
     * to reach it. Returns the same "claimed" boolean [ItemOps] reads.
     */
    fun hookCount(): Int = ItemOps.backpackActionHooks.size
}
