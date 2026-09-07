package com.opennxt.model.map

import java.util.ArrayDeque

/**
 * RS-accurate pathfinding: bounded breadth-first search, no heuristic.
 *
 * This is deliberately not A*. Both open reference implementations
 * (blurite/pathfinder, 2004Scape/rsmod-pathfinder) are bounded BFS, and three
 * differences from A* are behavioural rather than performance details:
 *
 * **The search is bounded.** RS searches a [SEARCH]x[SEARCH] window centred
 * between source and destination. A destination outside it yields NO path - the
 * player has to click closer. An unbounded A* will happily return a 320-step
 * route across three regions that the real game refuses. That difference is
 * invisible to any "is the path shortest" test.
 *
 * **Direction order is fixed**, so paths are reproducible. Two shortest paths of
 * equal length are not interchangeable when a client predicts movement locally;
 * A*'s tie-breaking falls out of heap ordering, so server and client can pick
 * different routes and disagree about where the player is mid-walk.
 *
 * **Unreachable destinations walk as close as possible.** Clicking into a wall
 * walks you to the nearest reachable tile rather than doing nothing.
 */
object PathFinder {

    /**
     * Load a tile's wall data as the search reaches it, rather than relying on
     * something having pre-loaded the square.
     */
    @Volatile
    @JvmStatic
    var lazyClippingEnabled: Boolean =
        System.getProperty("opennxt.experiment.map.lazyPathClipping") != "false"

    /** Expansion order. Fixed, because reproducibility is the point. */
    private val DIRS = arrayOf(
        intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
        intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(1, 1)
    )

    const val SEARCH = 128

    data class Step(val x: Int, val z: Int)

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    /**
     * Nodes DEQUEUED by the most recent [find] on THIS thread.
     *
     * A work counter, not a timer, and it exists because every previous
     * statement about this function's cost was a wall-clock number: "one
     * unreachable click is 31-48 ms" and, on a loaded machine, "120-220 ms".
     */
    private val lastVisited = ThreadLocal.withInitial { 0 }

    /** @see lastVisited */
    fun lastNodesVisited(): Int = lastVisited.get()

    /**
     * The largest number of nodes any one [find] can dequeue, for a given
     * window: `(search + 1)^2`. The BFS marks a node in `came` before queueing
     * it and never queues a marked node, so no tile inside the window can be
     * dequeued twice. That is an upper bound BY CONSTRUCTION, which is exactly
     * why a check must not merely assert it - it cannot fail. What CAN fail is
     * whether a measured search actually approaches it, which is what makes a
     * flood a flood.
     */
    fun nodeBound(search: Int = SEARCH): Int = (search + 1) * (search + 1)

    /**
     * @param nearest walk to the closest reachable tile when the goal is unreachable
     * @return the tile sequence including the start tile, or null if no path exists
     */
    fun find(
        startX: Int, startZ: Int,
        goalX: Int, goalZ: Int,
        plane: Int = 0,
        search: Int = SEARCH,
        nearest: Boolean = true
    ): List<Step>? {
        val counter = IntArray(1)
        try {
            return findCounting(startX, startZ, goalX, goalZ, plane, search, nearest, counter)
        } finally {
            lastVisited.set(counter[0])
        }
    }

    private fun findCounting(
        startX: Int, startZ: Int,
        goalX: Int, goalZ: Int,
        plane: Int,
        search: Int,
        nearest: Boolean,
        counter: IntArray
    ): List<Step>? {
        if (lazyClippingEnabled) LocClipping.applySquareAt(startX, startZ, plane)
        if (CollisionMap.blocked(startX, startZ, plane)) return null
        if (startX == goalX && startZ == goalZ) return listOf(Step(startX, startZ))

        val half = search / 2
        val cx = (startX + goalX) / 2
        val cz = (startZ + goalZ) / 2
        val x0 = cx - half; val x1 = cx + half
        val z0 = cz - half; val z1 = cz + half

        // Outside the window RS does not path at all. Returning a long path here
        // is the single most misleading thing this function could do.
        if (goalX !in x0..x1 || goalZ !in z0..z1) return null

        val came = HashMap<Long, Long>()
        val startKey = key(startX, startZ)
        came[startKey] = -1L
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(startX, startZ))

        var bestX = startX; var bestZ = startZ
        var bestDist = maxOf(Math.abs(startX - goalX), Math.abs(startZ - goalZ))
        var found = false

        while (queue.isNotEmpty()) {
            val (x, z) = queue.poll()
            counter[0]++
            if (x == goalX && z == goalZ) { found = true; break }
            for (d in DIRS) {
                val nx = x + d[0]; val nz = z + d[1]
                if (nx !in x0..x1 || nz !in z0..z1) continue
                val nk = key(nx, nz)
                if (came.containsKey(nk)) continue
                // Lazy loc clipping: ensure the square containing this tile has its
                // wall edges loaded before testing walkability. applySquareAt is
                // idempotent and memoised per (square, plane), so an already-loaded
                // square costs one hash lookup and no SQLite query. This fixes the
                // middle-of-path clipping gap where intermediate squares between the
                // start and end had no walls and the BFS walked through buildings.
                if (lazyClippingEnabled) LocClipping.applySquareAt(nx, nz, plane)
                if (!CollisionMap.canStep(x, z, d[0], d[1], plane)) continue
                came[nk] = key(x, z)
                val dist = maxOf(Math.abs(nx - goalX), Math.abs(nz - goalZ))
                if (dist < bestDist) { bestDist = dist; bestX = nx; bestZ = nz }
                queue.add(intArrayOf(nx, nz))
            }
        }

        var endX = goalX; var endZ = goalZ
        if (!found) {
            if (!nearest || (bestX == startX && bestZ == startZ)) return null
            endX = bestX; endZ = bestZ
        }

        val out = ArrayList<Step>()
        var cur = key(endX, endZ)
        while (cur != -1L) {
            out.add(Step((cur shr 32).toInt(), (cur and 0xffffffffL).toInt()))
            cur = came[cur] ?: break
        }
        out.reverse()
        return out
    }
}
