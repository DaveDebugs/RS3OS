package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

/**
 * Obstacles: the "Climb-over" / "Climb over" options - stiles, low walls,
 * rockslides, obstacle nets.
 */
object Obstacles {
    private val logger = KotlinLogging.logger { }

    const val CLIMB_OVER_HYPHEN = "Climb-over"
    const val CLIMB_OVER_SPACED = "Climb over"

    /**
     * THE OTHER CROSSING VERBS THE CACHE USES..
     */
    val CROSSING_ACTIONS: List<String> = listOf(
        "Cross", "Jump", "Squeeze-through", "Swing-on", "Grapple", "Climb-into", "Vault",
    )

    /**
     * UNGROUNDED placeholder, and inert - see the class doc. Nothing assigns
     * `onAnimate`, and `sequences` in this database carries no names to check
     * 839 against.
     */
    const val CLIMB_ANIMATION = 839

    /**
     * The two tiles a crossing connects, as a span on one axis.
     *
     * [lo] and [hi] are the coordinates of the two SIDES of the obstacle on
     * [axisX] (x when true, z when false). For a 1x2 stile they are the two
     * footprint tiles; for a wall shape they are the two tiles the blocked edge
     * separates.
     */
    data class Crossing(val axisX: Boolean, val lo: Int, val hi: Int) {
        /**
         * The tile [p] crosses to: its REFLECTION through the obstacle.
         *
         * `lo + hi - p` rather than `p + 1` / `p - 1`, and the difference is the
         * second bug in the old implementation. That code asked
         * `if (pLoc.x <= locX) +1 else -1`, and the `<=` is asymmetric: a player
         * standing ON the loc's own tile satisfies it and is sent AWAY from the
         * obstacle. Half the rotations were wrong from one side and right from
         * the other. A reflection cannot have that defect - it is its own
         * inverse, so `mirror(mirror(p)) == p` for every p, and there is no side
         * of the obstacle it treats differently.
         *
         * It also generalises for free: a player standing one tile back from a
         * blocked stile (`lo - 1`) lands one tile past it (`hi + 1`), still on
         * the far side, without a second rule.
         */
        fun mirror(p: Int): Int = lo + hi - p
    }

    /**
     * Which way this placement is crossed, or null when nothing in the data says.
     *
     * Three cases, most specific first:
     *
     *  1. **A wall shape with a single resolved edge.** The crossing is the edge
     *     normal, and the two sides are the two tiles [LocClipping.WALL_EDGE]
     *     says that edge separates. 11 of the 317 climb-over placements are
     *     `type` 0 (9 at rot 1, 2 at rot 3), so this branch is small but real.
     *  2. **An elongated footprint.** The crossing is the LONG axis and the two
     *     sides are the footprint's own end tiles - the measurement in the class
     *     doc. This is every stile.
     *  3. **A square footprint** (1x1 included: 115 of the placements, plus the
     *     108 `type` 22 ground decorations). Nothing about the placement states
     *     an axis, so the player's own approach picks it: a player orthogonally
     *     off the footprint crosses along the axis they approached on. A player
     *     standing ON a square footprint, or diagonally off it, gets **null** -
     *     the honest answer, because the shape genuinely does not say.
     *
     * A two-edge corner (`type` 2) falls through case 1 deliberately: its
     * [LocInteraction.Placed.edgeMask] carries two bits, so no single normal
     * exists and case 3 asks the player instead of guessing one.
     */
    fun crossingOf(p: LocInteraction.Placed, px: Int, pz: Int): Crossing? {
        when (p.edgeMask) {
            CollisionMap.WALL_W -> return Crossing(true, p.originX - 1, p.originX)
            CollisionMap.WALL_E -> return Crossing(true, p.originX, p.originX + 1)
            CollisionMap.WALL_N -> return Crossing(false, p.originZ, p.originZ + 1)
            CollisionMap.WALL_S -> return Crossing(false, p.originZ - 1, p.originZ)
        }
        if (p.dz > p.dx) return Crossing(false, p.z0, p.z1)
        if (p.dx > p.dz) return Crossing(true, p.x0, p.x1)

        val offX = if (px < p.x0) px - p.x0 else if (px > p.x1) px - p.x1 else 0
        val offZ = if (pz < p.z0) pz - p.z0 else if (pz > p.z1) pz - p.z1 else 0
        if (offX != 0 && offZ == 0) return Crossing(true, p.x0, p.x1)
        if (offZ != 0 && offX == 0) return Crossing(false, p.z0, p.z1)
        return null
    }

    /**
     * Where a player at ([px], [pz]) ends up after crossing [p], or null when
     * the crossing direction is not determined.
     *
     * Pure: it reads no collision and moves nobody, so a check can pin the
     * geometry without standing a world up. The collision gate lives in
     * [onClimbOver], where the refusal it produces can be reported.
     */
    fun destinationFor(p: LocInteraction.Placed, px: Int, pz: Int, plane: Int): TileLocation? {
        val cross = crossingOf(p, px, pz) ?: return null
        return if (cross.axisX) TileLocation(cross.mirror(px), pz, plane)
        else TileLocation(px, cross.mirror(pz), plane)
    }

    /**
     * "Climb-over" / "Climb over".
     *
     * Returns a string naming what happened, the way [Doors.onOpen] returns
     * "opened"/"closed": `"climbed-over"`, or one of four documented refusals -
     * `"no-placement"` (nothing in `map_loc` puts this loc within
     * [LocInteraction.radius] of the clicked tile, so there is no footprint to
     * cross), `"crossing-direction-unknown"` (case 3 above), `"already-across"`
     * (the reflection is a no-op) and `"destination-blocked"`.
     */
    fun onClimbOver(ctx: LocContext): Any? {
        val player = ctx.player
        val from = player.location

        val placed = LocInteraction.placementOf(ctx.locId, ctx.x, ctx.z, ctx.plane)
            ?: return "no-placement"

        val dest = destinationFor(placed, from.x, from.y, from.plane)
            ?: return "crossing-direction-unknown"

        if (dest.x == from.x && dest.y == from.y) return "already-across"

        // The gate the old version did not have. CollisionMap.available is false
        // when no map data is loaded at all, and the rest of the server degrades
        // by skipping rather than refusing everything in that state - the same
        // rule Ladders.destinationWalkable applies.
        if (CollisionMap.available && !CollisionMap.walkable(dest.x, dest.y, dest.plane)) {
            logger.info {
                "obstacles: refused '${ctx.action}' on loc ${ctx.locId} at (${ctx.x},${ctx.z},${ctx.plane}) - " +
                    "(${from.x},${from.y}) would cross to (${dest.x},${dest.y}), which is blocked"
            }
            return "destination-blocked"
        }

        player.location = dest
        // Inert seam, not a behaviour: nothing assigns onAnimate. See the class doc.
        player.onAnimate?.invoke(CLIMB_ANIMATION)
        return "climbed-over"
    }

    fun install(): Int {
        var count = ContentRegistry.onLocAction(CLIMB_OVER_HYPHEN, ::onClimbOver)
        count += ContentRegistry.onLocAction(CLIMB_OVER_SPACED, ::onClimbOver)
        val climbOver = count

        // The crossing verbs, bound to the SAME handler. Each is attempted
        // separately and a string this cache does not declare is skipped with a
        // line rather than thrown: ContentRegistry.onLocAction requires a
        // non-empty population, and this install is NOT contained by OpenNXT the
        // way Skilling's is, so one absent string would take the whole boot down
        // over an obstacle nobody can reach anyway.
        val bound = LinkedHashMap<String, Int>()
        val absent = ArrayList<String>()
        for (action in CROSSING_ACTIONS) {
            try {
                val n = ContentRegistry.onLocAction(action, ::onClimbOver)
                bound[action] = n
                count += n
            } catch (e: IllegalArgumentException) {
                absent += action
            }
        }
        logger.info { "obstacles: bound Climb over across $climbOver locs" }
        if (bound.isNotEmpty()) {
            logger.info {
                "obstacles: bound ${bound.size} further crossing verb(s) across " +
                    "${bound.values.sum()} locs - " + bound.entries.joinToString(", ") { "'${it.key}' ${it.value}" } +
                    ". Same handler, same reflection through the loc footprint; the MOVE only. " +
                    "No skill, xp, level requirement or failure chance is claimed - the cache states " +
                    "the option and the footprint, not a skill."
            }
        }
        if (absent.isNotEmpty()) {
            logger.info {
                "obstacles: ${absent.size} crossing verb(s) not declared by any loc in this cache, " +
                    "skipped: ${absent.joinToString(", ")}"
            }
        }
        return count
    }
}
