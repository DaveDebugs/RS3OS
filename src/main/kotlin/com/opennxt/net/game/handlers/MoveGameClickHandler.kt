package com.opennxt.net.game.handlers

import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MoveGameClick
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Walk the player toward the clicked tile.
 *
 * This is deliberately a STRAIGHT LINE, not pathfinding. The package ships
 * without rs3.sqlite, so the collision map is absent and the real pathfinder
 * refuses every route - a naive walk is the honest maximum here, and it is
 * enough to prove the click -> move -> PLAYER_INFO loop end to end. It WILL
 * walk through walls. That is a known limitation, not an oversight; wire it to
 * the real pathfinder once collision data exists.
 *
 * Movement.addStep validates adjacency itself and returns false on an illegal
 * step, so the loop below cannot inject a teleporting "step".
 */
object MoveGameClickHandler : GamePacketHandler<WorldPlayer, MoveGameClick> {
    private val logger = KotlinLogging.logger { }

    /** Cap so one click cannot queue an unbounded walk. */
    private const val MAX_STEPS = 64

    /** Local sign helper - Integer.signum vs kotlin's sign is an overload ambiguity here. */
    private fun step(from: Int, to: Int): Int = if (to > from) 1 else if (to < from) -1 else 0

    // ================================================================
    // WALKING TO A LOC, WHICH IS NOT WALKING TO ITS TILE
    // ================================================================

    /**
     * What [walkToLoc] did. A value rather than an Int, because "already close
     * enough" and "found nothing" used to produce the SAME log lines and that
     * cost real debugging time.
     */
    sealed class LocWalk {
        /** The player was already standing somewhere legal. Nothing queued, nothing wrong. */
        data class AlreadyInRange(val x: Int, val z: Int) : LocWalk()

        /** Routed to [x],[z] - a tile beside the loc, not the loc's own tile. */
        data class Walked(val steps: Int, val x: Int, val z: Int) : LocWalk()

        /**
         * No standing tile routes exactly, so the player was walked as close as the
         * map allows - PathFinder's own definition of closest, the same one a click
         * across a river uses.
         */
        data class Approached(val steps: Int, val x: Int, val z: Int, val candidates: Int) : LocWalk()

        /**
         * No standing tile is reachable AT ALL and nothing closer exists either.
         *
         * This is a CONNECTIVITY fact about the map, not a failure of this function,
         * and the two are not the same thing. Measured case: Door 1239 at
         * (3103,3257) sits in a wall running up the west side of column x = 3103,
         * its inside tile is the only walkable standing tile, and the tile in front
         * of it - (3102,3257) - is blocked by the TERRAIN bitmap with no loc on it at
         * all. From (3102,3256) there is no route to the door, full stop. The right
         * answer is to stand still, and to say why.
         *
         * [fallbackSteps] is what the generic walk managed; 0 means the player was
         * already as close as the map allows.
         */
        data class Unreachable(val candidates: Int, val fallbackSteps: Int) : LocWalk()

        /** `map_loc` places nothing covering the clicked tile; treated as a bare tile click. */
        data class NoPlacement(val fallbackSteps: Int) : LocWalk()

        val queued: Int
            get() = when (this) {
                is AlreadyInRange -> 0
                is Walked -> steps
                is Approached -> steps
                is Unreachable -> fallbackSteps
                is NoPlacement -> fallbackSteps
            }

        /**
         * Whether this function failed to do something it should have done.
         */
        val refused: Boolean get() = this is NoPlacement && fallbackSteps == 0
    }

    /**
     * Walk the player to a tile they can interact with the clicked loc FROM.
     *
     * The old behaviour - [walk] straight at the clicked tile - is what produced
     * every refusal at distance <= 2 in the test run; see [LocInteraction] for the
     * measurement and the three separate reasons.
     *
     * Order of business, and each step is a distinct logged outcome:
     *
     *  1. Find the placement whose FOOTPRINT covers the clicked tile. Not the one
     *     whose origin equals it - 98,695 clickable tiles have no row of their own.
     *  2. If the player already stands on a legal tile, queue nothing and say so.
     *     This is the distance-0 case and it is a success.
     *  3. Otherwise route to the nearest legal standing tile with the pathfinder,
     *     `nearest = false` so a candidate either routes or is skipped. Best-effort
     *     routing here would put the player somewhere no candidate asked for.
     *  4. If nothing routes, fall back to [walk] - the generic
     *     as-close-as-possible walk - so this can never do LESS than before.
     */
    fun walkToLoc(context: WorldPlayer, clickedX: Int, clickedY: Int, locId: Int?, tag: String): LocWalk =
        walkToLoc(context.entity, clickedX, clickedY, locId, tag)

    fun walkToLoc(context: WorldPlayer, clickedX: Int, clickedY: Int, tag: String): LocWalk =
        walkToLoc(context.entity, clickedX, clickedY, null, tag)

    fun walkToLoc(entity: PlayerEntity, clickedX: Int, clickedY: Int, tag: String): LocWalk =
        walkToLoc(entity, clickedX, clickedY, null, tag)

    /** [walkToLoc] against a bare [PlayerEntity], for the same reason [walk] has one. */
    fun walkToLoc(entity: PlayerEntity, clickedX: Int, clickedY: Int, locId: Int?, tag: String): LocWalk {
        val plane = entity.location.plane
        val from = entity.location

        val placed = com.opennxt.model.map.LocInteraction
            .placementsCovering(clickedX, clickedY, plane)
            .firstOrNull { locId == null || it.locId == locId }
        if (placed == null) {
            // Nothing the cache knows about covers that tile. Not an error - it is
            // also what a click on bare ground through OPLOC would look like - so
            // this degrades to the generic walk rather than refusing.
            val n = walk(entity, clickedX, clickedY, tag)
            logger.info {
                "$tag ($clickedX,$clickedY): map_loc places nothing covering that tile " +
                    "(searched a ${com.opennxt.model.map.LocInteraction.radius}-tile radius for a " +
                    "footprint that reaches it), so it was walked to as a plain tile - $n step(s)"
            }
            return LocWalk.NoPlacement(n)
        }

        if (com.opennxt.model.map.LocInteraction.inRange(placed, plane, from.x, from.y)) {
            // THE DISTANCE-0 AND ALREADY-ADJACENT CASE. Queue nothing, and do not
            // pretend anything failed: the action fires either way, and the old code
            // logged "no route" then "queued 0 step(s)" for exactly this.
            entity.movement.reset()
            logger.info {
                "$tag ($clickedX,$clickedY): already in range at (${from.x},${from.y}) - " +
                    "loc ${placed.locId} occupies ${placed.dx}x${placed.dz} at " +
                    "(${placed.x0}..${placed.x1},${placed.z0}..${placed.z1})" +
                    (if (placed.isWall) " as a wall on its 0x%02x edge".format(placed.edgeMask) else "") +
                    ". Nothing to walk."
            }
            return LocWalk.AlreadyInRange(from.x, from.y)
        }

        val candidates = com.opennxt.model.map.LocInteraction
            .standingTiles(placed, plane, from.x, from.y)
        entity.movement.reset()
        for (c in candidates) {
            // nearest = false: this candidate routes or it does not. The default
            // best-effort mode would return true having walked the player to some
            // tile that is not a standing tile at all, and report it as success.
            if (entity.movement.walkTo(c[0], c[1], nearest = false)) {
                val steps = entity.movement.remainingSteps
                logger.info {
                    "$tag ($clickedX,$clickedY): loc ${placed.locId} occupies ${placed.dx}x${placed.dz} " +
                        "at (${placed.x0}..${placed.x1},${placed.z0}..${placed.z1}); walked from " +
                        "(${from.x},${from.y}) to (${c[0]},${c[1]}) - $steps step(s), " +
                        "${candidates.size} standing tile(s) offered"
                }
                return LocWalk.Walked(steps, c[0], c[1])
            }
        }

        // Nothing routes exactly. Walk as close as the map allows to the NEAREST
        // standing tile - PathFinder's `nearest` mode, which is what a click across
        // a river does. Aiming this at the candidate rather than at the loc's own
        // tile is the whole point: the loc's tile may be the one place the player
        // can never stand.
        val nearest = candidates.firstOrNull()
        if (nearest != null) {
            entity.movement.reset()
            if (entity.movement.walkTo(nearest[0], nearest[1], nearest = true)) {
                val steps = entity.movement.remainingSteps
                logger.info {
                    "$tag ($clickedX,$clickedY): no standing tile beside loc ${placed.locId} routes " +
                        "exactly from (${from.x},${from.y}); walked as close as the map allows toward " +
                        "(${nearest[0]},${nearest[1]}) - $steps step(s), ${candidates.size} candidate(s)"
                }
                return LocWalk.Approached(steps, nearest[0], nearest[1], candidates.size)
            }
        }

        val n = walk(entity, clickedX, clickedY, tag)
        logger.info {
            "$tag ($clickedX,$clickedY): loc ${placed.locId} is not reachable from (${from.x},${from.y}) - " +
                "${candidates.size} standing tile(s) exist and none of them connects, and no tile closer " +
                "to it does either" + (if (n > 0) ", so the generic walk moved $n step(s)" else
                    ", so the player is already as close as the map allows and stays put") +
                ". A wall between the two is a fact about the map, not a routing failure."
        }
        return LocWalk.Unreachable(candidates.size, n)
    }

    override fun handle(context: WorldPlayer, packet: MoveGameClick) {
        val queued = walk(context, packet.x, packet.y, "MOVE_GAMECLICK", "flags=0x${packet.flags.toString(16)} ")

        // THE DESTINATION MARKER. Reference answers every one of these with a
        // SET_MAP_FLAG carrying the CLICKED tile in local scene coordinates -
        // that fixed this packet's own axis/transform reading. The clicked tile
        // is what goes on the wire, not the resolved path endpoint: where the
        // player actually ends up is the walk's business, and the reference client marks the
        // tile the player asked for.
        //
        // Sent only when a step was queued. A click that queues nothing is one
        // the server refused - already standing there, or no legal step - and
        // planting a marker on a tile nobody is walking to would be a lie the
        // player can see.
        //
        // THE else BRANCH IS OURS, NOT THE REFERENCE CLIENT'S, and the distinction matters.
        // What the protocol establishes is what the idle form MEANS: a local
        // pair of (255,255) is outside any 256-tile scene, so it cannot name a
        // destination, and 20 of the 34 recorded frames carry it. What the
        // observation does NOT establish is when the reference client chooses to send it - the
        // frames are not paired with anything saying the player stopped. So
        // this server sends it on the one occasion it can justify from the
        // meaning alone: the click was refused, therefore there is no
        // destination, therefore any marker still on screen is stale.
        //
        // Clearing on ARRIVAL is the other obvious trigger and is deliberately
        // not done here - see [com.opennxt.content.impl.MapFlag].
        if (queued > 0) com.opennxt.content.impl.MapFlag.set(context, packet.x, packet.y)
        else com.opennxt.content.impl.MapFlag.clear(context)
    }

    /**
     * The walk this handler performs, exposed so other handlers can reuse it.
     *
     * Extracted rather than copied so there is exactly ONE walk in this repository:
     * [OpLocHandler] walks to a clicked scenery object through this function, so
     * the pathfinder-first / walk-as-close-as-possible / straight-line-fallback
     * behaviour and its four log lines cannot drift between a tile click and a
     * loc click. The body below is unchanged from what MOVE_GAMECLICK ran
     * before, with the literal "MOVE_GAMECLICK" replaced by [tag] and the
     * `flags=` field by [detail] (printed on the straight-line line only, which
     * is where it was printed before).
     *
     * Returns how many steps ended up queued: 0 when nothing legal was found,
     * and [Movement.remainingSteps] when the pathfinder supplied the route.
     */
    fun walk(context: WorldPlayer, targetX: Int, targetY: Int, tag: String, detail: String = "", search: Int? = null): Int =
        walk(context.entity, targetX, targetY, tag, detail, search)

    /**
     * [walk] against a bare [PlayerEntity].
     *
     * The body is what `walk(WorldPlayer, ...)` always ran - it only ever touched
     * `context.entity` - hoisted so a check can drive THIS walk without a socket,
     * a login or a world. `WorldPlayer` is not constructible without a
     * `ConnectedClient`, and a check that had to fake one would end up testing the
     * fake.
     */
    fun walk(entity: PlayerEntity, targetX: Int, targetY: Int, tag: String, detail: String = "", search: Int? = null): Int {

        // A new click REPLACES the current path - that is what RS does, and it
        // is also required for correctness here. Movement.addStep measures from
        // `queue.lastOrNull() ?: entity.location`, so with a path already queued
        // it validates against the TAIL while this handler computes steps from
        // entity.location. Those disagree the moment anything is queued, and
        // every later click was refused for being >1 tile from a tail 64 steps
        // away. Observed exactly that: first click queued 64, all subsequent
        // clicks queued 0.
        entity.movement.reset()

        val from = entity.location

        // Real pathfinding first, when there is collision data to path over.
        // walkTo() runs PathFinder and routes AROUND obstacles; the straight
        // line below cannot, and walking a diagonal through a building is
        // exactly the "no control over where I went" the straight line produces.
        //
        // walkTo returns false the way RS itself refuses - destination outside
        // the search window, or genuinely no route - which is a real outcome,
        // not an error. Falling back to the straight line in that case keeps the
        // click responsive instead of silently doing nothing.
        if (com.opennxt.model.map.CollisionMap.available) {
            if (if (search == null) entity.movement.walkTo(targetX, targetY) else entity.movement.walkTo(targetX, targetY, nearest = true, search = search)) {
                logger.info {
                    "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                        "-> PATHFOUND ${entity.movement.remainingSteps} step(s)"
                }
                return entity.movement.remainingSteps
            }
            // No direct route. Walk AS CLOSE AS POSSIBLE instead of giving up -
            // click across a river in RS and you walk to the bank, you do not
            // stand still.
            //
            // Retry the real pathfinder against points stepped back along the
            // line toward the target, furthest first, and take the first that
            // routes. This reuses PathFinder rather than hand-rolling a walk,
            // so every step it produces is collision-legal by construction.
            //
            // The straight-line fallback below cannot do this job: it takes the
            // first step toward the target and stops dead when that one tile is
            // blocked. Measured - player on the east shore at (3200,3129) with
            // the entire west column blocked, clicking west across water:
            // "no route, falling back to a straight line" then "queued 0 step(s)".
            // Two log lines, zero movement, every click.
            val dxTotal = targetX - from.x
            val dyTotal = targetY - from.y
            val dist = maxOf(Math.abs(dxTotal), Math.abs(dyTotal))
            if (dist > 1) {
                // 8 probes is enough resolution to land near any shoreline
                // without turning one click into 60 pathfinder runs.
                val probes = minOf(8, dist - 1)
                for (i in 1..probes) {
                    val frac = (probes - i + 1).toDouble() / (probes + 1)
                    val tx = from.x + Math.round(dxTotal * frac).toInt()
                    val ty = from.y + Math.round(dyTotal * frac).toInt()
                    if (tx == from.x && ty == from.y) continue
                    if (entity.movement.walkTo(tx, ty)) {
                        logger.info {
                            "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                                "unreachable -> walking as close as possible, to ($tx,$ty), " +
                                "${entity.movement.remainingSteps} step(s)"
                        }
                        return entity.movement.remainingSteps
                    }
                }
            }

            logger.info {
                "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                    "-> no route and no reachable point along the way; straight line next"
            }
        }

        var cx = from.x
        var cy = from.y
        var queued = 0

        while ((cx != targetX || cy != targetY) && queued < MAX_STEPS) {
            val nx = cx + step(cx, targetX)
            val ny = cy + step(cy, targetY)
            if (!entity.movement.addStep(nx, ny)) break
            cx = nx; cy = ny; queued++
        }

        logger.info {
            "$tag from (${from.x},${from.y}) to (${targetX},${targetY}) " +
                "$detail-> queued $queued step(s)" +
                if (queued == 0) " (no legal step - already there, or addStep refused)" else ""
        }

        return queued
    }
}
