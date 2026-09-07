package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

/**
 * Ladders: vertical movement bound to the "Climb-*" options.
 *
 * ## What is grounded in the cache
 *
 * Ladders are **not** a hardcoded id list. Like [Doors], they are found by asking
 * the loc config which locs carry a climb option - the option list *is* the
 * right-click menu, and "Climb-up" being present is the cache's own statement
 * that this object goes up. Measured on `data/rs3.sqlite` (`locs.actions_0` plus
 * the `locs_attr` rows for slots 1..4, which [com.opennxt.resources.sqlite.SqliteLocCodec]
 * merges):
 *
 * ```
 *   locs declaring "Climb-up"                       555   (526 in actions_0, 29 in attr slots)
 *   locs declaring "Climb-down"                     523   (491 in actions_0, 32 in attr slots)
 *   locs declaring "Climb"                          358   (352 in actions_0,  6 in attr slots)
 *   locs declaring "Climb-Up"   (capital U)           3   \  real, distinct options; matching is
 *   locs declaring "Climb-Down" (capital D)           5   /  case-sensitive, so these are NOT bound
 *   mid-shaft ladders declaring all three: e.g. 1748 'Ladder' actions = [Climb, Climb up, Climb down]
 * ```
 *
 * **Which direction a loc goes comes from its action strings**: a loc declaring
 * "Climb-up" is a bottom-of-ladder, one declaring "Climb-down" is a
 * top-of-ladder, and one declaring "Climb" is ambiguous (typically a mid-shaft
 * ladder that also declares the spaced variants "Climb up"/"Climb down" in slots
 * 1..2 - see loc 1748). That mapping is read out of the definitions, not
 * invented here.
 *
 * The binding is by declared option, not by name: "Climb-up" is also declared by
 * ropes, vines and rock faces. That is the same deliberate over-selection [Doors]
 * makes with "Open" (chests declare it too) - the option is what the client can
 * legitimately send, so the option is what gets a handler.
 *
 * Unbound variants, counted rather than ignored: "Climb-Up" (3), "Climb-Down"
 * (5), "Climb up" / "Climb down" (spaced; live mostly in attr slots of "Climb"
 * ladders), and the long tail ("Climb-over", "Climb-across", ...). They stay
 * unbound until someone decides what they do; dispatching them reports
 * `NoHandler`, which is a content gap, not a rejection.
 *
 * ## What is RECONSTRUCTED, not extracted
 *
 * The cache says a ladder has an option called "Climb-up". It does not say where
 * climbing puts you - no cache ever did; destinations lived in RuneScript on
 * Jagex's side. The following is a reconstruction, labelled as such:
 *
 *  - **RECONSTRUCTED: the destination is the player's own tile, one plane up or
 *    down.** Real RS moves you to an authored tile near the ladder's other end
 *    (often offset a tile so you do not stand inside the ladder). That authored
 *    table does not exist in this database, so same-tile plane movement is the
 *    reconstruction. Plane is clamped to [MIN_PLANE]..[MAX_PLANE] (RS planes are
 *    0..3); climbing past either end refuses with "at-top"/"at-bottom".
 *  - **AUTHORED: "Climb" defaults to up.** Real RS opens a dialogue asking which
 *    way. There is no dialogue system here yet, so "Climb" tries up first and
 *    falls back to down if up is clamped or blocked; if neither works it refuses
 *    with "nowhere-to-climb". When a dialogue system exists this handler is the
 *    seam it replaces.
 *
 * ## The collision gate
 *
 * A destination is only entered if [CollisionMap] says it can be stood on. An
 * unmapped square reports blocked (that is [CollisionMap]'s own rule: absent
 * data must not read as open ground), so a ladder whose upper plane has no
 * bitmap refuses with "destination-blocked" instead of teleporting the player
 * into the void. This mirrors how [Doors] treats the baked bitmap as the source
 * of truth for the ground it stands on. When no collision data is loaded at all
 * ([CollisionMap.available] is false) the gate is skipped rather than refusing
 * every climb - the same degradation the rest of the server applies when
 * rs3.sqlite is absent.
 */
object Ladders {
    private val logger = KotlinLogging.logger { }

    const val CLIMB_UP = "Climb-up"
    const val CLIMB_DOWN = "Climb-down"
    const val CLIMB = "Climb"

    /** RS planes are 0..3; both ends are clamps, not wraps. */
    const val MIN_PLANE = 0
    const val MAX_PLANE = 3

    /**
     * Whether the destination tile can be stood on. Skipped (true) when no
     * collision data is loaded; an *absent square* with data loaded is blocked.
     */
    private fun destinationWalkable(x: Int, y: Int, plane: Int): Boolean =
        !CollisionMap.available || CollisionMap.walkable(x, y, plane)

    /**
     * One plane step from the player's current tile. RECONSTRUCTED - see the
     * class comment. Returns a documented refusal instead of moving when the
     * plane clamps or the destination is not standable.
     */
    private fun climb(ctx: LocContext, direction: Int): Any? {
        val action = if (direction > 0) CLIMB_UP else CLIMB_DOWN
        val customDest = TeleportRegistry.getDestination(action, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            return if (direction > 0) "climbed-up" else "climbed-down"
        }

        val from = ctx.player.location
        val target = from.plane + direction
        if (target > MAX_PLANE) return "at-top"
        if (target < MIN_PLANE) return "at-bottom"
        
        var destX = ctx.x
        var destY = ctx.z
        if (!destinationWalkable(destX, destY, target)) {
            val dx = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
            val dy = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
            var found = false
            for (i in 0 until 8) {
                if (destinationWalkable(ctx.x + dx[i], ctx.z + dy[i], target)) {
                    destX = ctx.x + dx[i]
                    destY = ctx.z + dy[i]
                    found = true
                    break
                }
            }
            if (!found) return "destination-blocked"
        }
        ctx.player.location = TileLocation(destX, destY, target)
        return if (direction > 0) "climbed-up" else "climbed-down"
    }

    /** "Climb-up": the definitions' own statement that this loc goes up. */
    fun onClimbUp(ctx: LocContext): Any? = climb(ctx, +1)

    /** "Climb-down": the definitions' own statement that this loc goes down. */
    fun onClimbDown(ctx: LocContext): Any? = climb(ctx, -1)

    /**
     * AUTHORED. "Climb" is direction-ambiguous in the config, and real RS asks.
     * With no dialogue system the default is: up if up is possible, else down,
     * else refuse. The preference for up is a choice, written down here.
     */
    fun onClimb(ctx: LocContext): Any? {
        val customDest = TeleportRegistry.getDestination(CLIMB, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            // the exact string isn't used by much but let's just return a generic success
            return "climbed"
        }

        val from = ctx.player.location
        if (from.plane + 1 <= MAX_PLANE && destinationWalkable(from.x, from.y, from.plane + 1)) {
            return climb(ctx, +1)
        }
        if (from.plane - 1 >= MIN_PLANE && destinationWalkable(from.x, from.y, from.plane - 1)) {
            return climb(ctx, -1)
        }
        return "nowhere-to-climb"
    }

    // ---- registration ---------------------------------------------------

    /**
     * Binds the three options by name, across every loc that declares them.
     * Returns (locs bound to Climb-up, to Climb-down, to Climb). Matching is
     * exact and case-sensitive - see [com.opennxt.content.SqliteDefinitions] -
     * so the capitalised "Climb-Up"/"Climb-Down" variants are not swept in.
     */
    fun install(): Triple<Int, Int, Int> {
        val up = ContentRegistry.onLocAction(CLIMB_UP, ::onClimbUp)
        val down = ContentRegistry.onLocAction(CLIMB_DOWN, ::onClimbDown)
        val climb = ContentRegistry.onLocAction(CLIMB, ::onClimb)
        logger.info { "ladders: bound Climb-up across $up locs, Climb-down across $down, Climb across $climb" }
        return Triple(up, down, climb)
    }
}
