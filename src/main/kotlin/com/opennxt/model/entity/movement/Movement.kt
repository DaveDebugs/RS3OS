package com.opennxt.model.entity.movement

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.model.map.PathFinder
import com.opennxt.model.world.TileLocation
import java.util.*

/**
 * Tile-by-tile movement over real collision, one tick at a time.
 *
 * `process()` was an empty function body at HEAD - the server could hold a
 * position but never change one. This consumes a queued path at RS speeds:
 * walking advances one tile per tick, running two, and a run with only one tile
 * left takes it as a walk rather than stalling or overshooting.
 *
 * Collision is re-checked on every step rather than trusted from when the path
 * was computed. A door can shut between the click and the arrival; a mover that
 * trusts a stale path walks through it.
 */
class Movement(val entity: Entity) {
    private val queue = LinkedList<TileLocation>()
    private val isPlayer = entity is PlayerEntity
    var speed = MovementSpeed.RUN
    var currentSpeed = MovementSpeed.STATIONARY
    var nextWalkDirection: CompassPoint? = null
    var nextRunDirection: CompassPoint? = null
    var teleportLocation: TileLocation? = null
    var onArrival: (() -> Unit)? = null

    val hasSteps: Boolean get() = queue.isNotEmpty()
    val remainingSteps: Int get() = queue.size

    /**
     * The run reserve behind [speed], or null for anything that is not a player.
     *
     * A GETTER rather than a field: `PlayerEntity` constructs its `Movement` in its own
     * initialiser, so a field here would observation whatever `runEnergy` held at that instant and
     * depend on the declaration order of a class in another package. The cost is one cast per
     * tick per moving entity, which is nothing next to the collision lookups either side of it.
     */
    private val runEnergy: RunEnergy? get() = (entity as? PlayerEntity)?.runEnergy

    /**
     * The tile the queued path ends on, or null when nothing is queued.
     */
    fun destination(): Pair<Int, Int>? = queue.lastOrNull()?.let { it.x to it.y }

    fun reset() {
        queue.clear()
        currentSpeed = MovementSpeed.STATIONARY
        nextWalkDirection = null
        nextRunDirection = null
        onArrival = null
    }

    fun teleport(location: TileLocation) {
        queue.clear()
        teleportLocation = location
        onArrival = null
    }

    /** Queue a single tile. False if it is not a legal step from the current tail. */
    fun addStep(x: Int, z: Int): Boolean {
        val from = queue.lastOrNull() ?: entity.location
        val dx = Integer.signum(x - from.x)
        val dz = Integer.signum(z - from.y)
        if (dx == 0 && dz == 0) return false
        if (Math.abs(x - from.x) > 1 || Math.abs(z - from.y) > 1) return false
        if (!CollisionMap.canStep(from.x, from.y, dx, dz, from.plane)) return false
        queue.add(TileLocation(x, z, from.plane))
        return true
    }

    /**
     * Path to a destination and queue the result.
     *
     * Returns false when RS itself would refuse - destination outside the search
     * window, or no route at all. That is a real outcome rather than an error:
     * the player clicks closer and tries again.
     *
     * [search] is the pathfinder's window, defaulting to RS's own
     * [PathFinder.SEARCH]. It is exposed because the cost of one search is
     * quadratic in it - `(search+1)^2` nodes worst case, 16,641 at the default -
     * and a caller on the TICK path chasing an adjacent target has no business
     * paying for a 128-tile flood. [com.opennxt.model.combat.PlayerCombat]'s
     * chase sizes the window to the distance for exactly that reason; the
     * measured node counts are at PlayerCombat.CHASE_SEARCH_CAP.
     *
     * **Both ends are clipped first** ([clipPathEnds]). Before that call this
     * function pathed against a collision map the once-per-tick loc-clipping drip
     * feed had not yet reached, and routed straight through walls `map_loc`
     */
    fun walkTo(x: Int, z: Int, nearest: Boolean = true, search: Int = PathFinder.SEARCH): Boolean {
        val loc = entity.location
        // Both ends solid BEFORE the search. See [Companion.clipPathEnds] - without
        // this the search runs against whatever the once-per-tick drip feed has
        // reached so far and walks straight through Fred's farmhouse.
        clipPathEnds(loc.x, loc.y, x, z, loc.plane)
        val path = PathFinder.find(loc.x, loc.y, x, z, loc.plane, search = search, nearest = nearest)
            ?: return false
        queue.clear()
 // (CODE-REVIEW-FULL C2-M1, seen live the same day): an arrival
        // callback belongs to the walk that installed it. This replaces the queue,
        // so whatever was waiting for the OLD walk to end must go with it -
        // otherwise a chase's re-path (PlayerCombat.chase -> walkTo, every tick
        // while engaged) empties the queue at the npc's tile and the OPLOC
        // callback fires THERE. Live: two clicks on a stile at 8 tiles while a
        // chicken was attacking; both "arrived" one tile east, at the chicken.
        // Callers that want a callback set it AFTER walkTo returns, as
        // OpLocHandler does.
        onArrival = null
        // The first entry of a path is the tile already stood on.
        path.drop(1).forEach { queue.add(TileLocation(it.x, it.z, loc.plane)) }
        return queue.isNotEmpty()
    }

    /**
     * ONE TICK OF A PLAYER'S MOVEMENT, run energy included. Returns whether the avatar advanced
     * TWO tiles - i.e. whether the reserve was charged.
     */
    fun processPlayerTick(): Boolean {
        val energy = runEnergy ?: run { process(); return false }
        speed = if (energy.toggled) MovementSpeed.RUN else MovementSpeed.WALK
        process()
        val ran = currentSpeed == MovementSpeed.RUN
        energy.tick(ran)
        return ran
    }

    fun process() {
        nextWalkDirection = null
        nextRunDirection = null

        teleportLocation?.let { destination ->
            entity.previousLocation = entity.location
            entity.location = destination
            teleportLocation = null
            currentSpeed = MovementSpeed.INSTANT
            queue.clear()

            val arrived = onArrival
            onArrival = null
            arrived?.invoke()
            return
        }

        if (queue.isEmpty()) {
            currentSpeed = MovementSpeed.STATIONARY

            val arrived = onArrival
            if (arrived != null) {
                onArrival = null
                arrived.invoke()
            }
            return
        }

        entity.previousLocation = entity.location

        val first = step()
        if (first == null) {
            // Blocked since the path was computed. Drop the rest rather than
            // walking a route that no longer exists.
            reset()
            return
        }
        nextWalkDirection = first

        // Running takes a second tile in the same tick. With one tile left it
        // degrades to a walk - it does not stall and does not overshoot.
        //
 // ...and since it also has to be AFFORDABLE. [RunEnergy.canRunStep] is the
        // second tile's whole gate: the toggle (varp 463) and enough reserve to pay the measured
        // 7 tenths this tick costs. Anything that is not a PlayerEntity has no RunEnergy and is
        // governed by [speed] alone, exactly as before - that is what keeps WorldNpc, and
 // 's bare-PlayerEntity runner (which never ticks
        // the reserve, so it stays full), on the behaviour they were written against.
        if (speed == MovementSpeed.RUN && queue.isNotEmpty() && runEnergy?.canRunStep() != false) {
            val second = step()
            if (second != null) {
                nextRunDirection = second
                currentSpeed = MovementSpeed.RUN
                return
            }
        }
        currentSpeed = MovementSpeed.WALK
    }

    /** Take one tile off the queue if it is still walkable. Null if it is not. */
    private fun step(): CompassPoint? {
        val next = queue.peek() ?: return null
        val from = entity.location
        val dx = Integer.signum(next.x - from.x)
        val dz = Integer.signum(next.y - from.y)
        if (!CollisionMap.canStep(from.x, from.y, dx, dz, from.plane)) return null
        queue.poll()
        entity.location = next
        return CompassPoint.forDelta(dx, dz)
    }

    companion object {
        /**
         * Clip BOTH ends of a path before searching it. Default **ON**;
         * `-Dopennxt.experiment.pathClipping=false` turns it off.
         */
        @Volatile
        @JvmStatic
        var clipEndsBeforePathing: Boolean =
            System.getProperty("opennxt.experiment.pathClipping") != "false"

        /** Printed at boot so nobody has to read this file to know which way it is set. */
        val PROVENANCE: String =
            "path clipping: both path ends are made solid before PathFinder.find " +
                "(clipEndsBeforePathing=$clipEndsBeforePathing). without it, " +
                "(3195,3280)->(3206,3290) ends INSIDE Fred's farmhouse in 13 steps; with it, " +
                "19 steps ending (3202,3287) outside. AUTHORED: nothing - the walls are " +
                "LocClipping's, applied sooner. KNOWN GAP: only the two END squares are " +
                "clipped, not every square the search window touches."

        private val endClips = java.util.concurrent.atomic.AtomicLong()

        /**
         * How many times [clipPathEnds] has newly applied a square in this JVM.
         *
         * A work counter for checks, not a timer. It counts SQUARES that
         * contributed, so a square already loaded - the common case once a player
         * has stood still for nine ticks - adds nothing.
         */
        @JvmStatic
        fun endClipApplications(): Long = endClips.get()

        /**
         * Make the squares containing both ends of a prospective path solid.
         *
         * At most TWO [LocClipping.applySquareAt] calls, both idempotent, both
         * no-ops once the square is loaded. That is the whole cost bound: a walk
         * within one already-loaded square pays two hash lookups, and the worst
         * case is two cold squares (~7.4 ms of SQLite) on a click that crosses a
         * square boundary into ground nobody has visited.
         *
         * Returns the number of squares this call newly applied (0, 1 or 2).
         * Caveat worth stating: [LocClipping.applySquareAt] returns 0 both for
         * "already loaded" and for "loaded, but no row contributed", so a genuinely
         * empty square counts as 0 here. Checks that need the exact figure diff
         * [LocClipping.loadedSquares] instead.
         *
         * @see clipEndsBeforePathing for the measurement and the controls.
         */
        @JvmStatic
        fun clipPathEnds(fromX: Int, fromZ: Int, toX: Int, toZ: Int, plane: Int): Int {
            if (!clipEndsBeforePathing) return 0
            var n = 0
            if (LocClipping.applySquareAt(fromX, fromZ, plane) > 0) n++
            if (LocClipping.applySquareAt(toX, toZ, plane) > 0) n++
            if (n > 0) endClips.addAndGet(n.toLong())
            return n
        }
    }

}
