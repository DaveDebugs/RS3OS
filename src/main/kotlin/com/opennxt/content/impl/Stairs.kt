package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

/**
 * Stairs: vertical movement bound to the "Walk-*" options.
 *
 * ## What is grounded in the cache
 *
 * Same shape as [Ladders]: staircases are found by asking the loc config which
 * locs carry a walk-up/walk-down option, not by keeping a list. The variant
 * spellings were checked against `data/rs3.sqlite` before anything was bound,
 * because action matching is exact and case-sensitive:
 *
 * ```
 *   locs declaring "Walk-up"                         13   e.g. 1730 'Staircase', 2539 'Stairs'
 *   locs declaring "Walk-down"                       11   e.g. 1731 'Staircase', 2540 'Stairs'
 *   locs declaring "Walk-Up"   (capital U)            0   \  the capitalised variants do NOT exist
 *   locs declaring "Walk-Down" (capital D)            0   /  in this corpus, so nothing binds them
 *   locs declaring "Walk up" / "Walk down" (spaced)   1 each   (24672/24673 'Staircase' - unbound)
 * ```
 *
 * Binding "Walk-Up" would be refused by [ContentRegistry.onLocAction] outright -
 * it requires at least one declaring loc - which is the registration-time
 * counterpart of dispatch rejecting undeclared actions. The spaced variants and
 * the rest of the "Walk-*" family ("Walk-across" 52, "Walk-to" 36, "Walk-on" 31,
 * "Walk-through" 9) are counted here and left unbound: they are traversal, not
 * stairs, and dispatching them reports `NoHandler` (a content gap), never a
 * silent acceptance.
 *
 * As with [Ladders], **which way a staircase goes comes from its action
 * strings**: "Walk-up" locs are bottom-of-stairs, "Walk-down" locs are
 * top-of-stairs. A shut/open-style pairing (1730/1731, 2539/2540) exists for the
 * same reason doors come as two loc ids - the two ends are different objects.
 *
 * ## What is RECONSTRUCTED, not extracted
 *
 *  - **RECONSTRUCTED: the destination is the player's own tile, one plane up or
 *    down**, clamped to planes 0..3. Real RS moves you to an authored tile at
 *    the staircase's other end - typically displaced by the staircase's own
 *    length, which for stairs is very visible - but that destination table is
 *    RuneScript-side and not in this database. Same-tile plane movement is the
 *    honest reconstruction until destinations are authored.
 *
 * ## The collision gate
 *
 * Identical to [Ladders]: the destination must be standable per [CollisionMap]
 * when collision data is loaded, an absent square counts as blocked, and the
 * refusal is the documented "destination-blocked" rather than a teleport into
 * the void. Skipped entirely only when [CollisionMap.available] is false.
 */
object Stairs {
    private val logger = KotlinLogging.logger { }

    const val WALK_UP = "Walk-up"
    const val WALK_DOWN = "Walk-down"

    /** RS planes are 0..3; both ends are clamps, not wraps. */
    const val MIN_PLANE = 0
    const val MAX_PLANE = 3

    private fun destinationWalkable(x: Int, y: Int, plane: Int): Boolean =
        !CollisionMap.available || CollisionMap.walkable(x, y, plane)

    /**
     * One plane step from the player's current tile. RECONSTRUCTED - see the
     * class comment. Refuses with "at-top"/"at-bottom" at the clamps and
     * "destination-blocked" when the destination cannot be stood on.
     */
    private fun walk(ctx: LocContext, direction: Int): Any? {
        val action = if (direction > 0) WALK_UP else WALK_DOWN
        val customDest = TeleportRegistry.getDestination(action, ctx.x, ctx.z, ctx.plane)
        if (customDest != null) {
            ctx.player.location = customDest
            return if (direction > 0) "walked-up" else "walked-down"
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
        return if (direction > 0) "walked-up" else "walked-down"
    }

    /** "Walk-up": the definitions' own statement that this loc goes up. */
    fun onWalkUp(ctx: LocContext): Any? = walk(ctx, +1)

    /** "Walk-down": the definitions' own statement that this loc goes down. */
    fun onWalkDown(ctx: LocContext): Any? = walk(ctx, -1)

    // ---- registration ---------------------------------------------------

    /**
     * Binds both options by name, across every loc that declares them. Returns
     * (locs bound to Walk-up, locs bound to Walk-down). "Walk-Up"/"Walk-Down"
     * are not bound because zero locs declare them - measured, see the class
     * comment - and binding a name no loc declares is a registration error.
     */
    fun install(): Pair<Int, Int> {
        val up = ContentRegistry.onLocAction(WALK_UP, ::onWalkUp)
        val down = ContentRegistry.onLocAction(WALK_DOWN, ::onWalkDown)
        logger.info { "stairs: bound Walk-up across $up locs, Walk-down across $down" }
        return up to down
    }
}
