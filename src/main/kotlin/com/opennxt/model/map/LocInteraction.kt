package com.opennxt.model.map

import com.opennxt.resources.sqlite.RsDatabase

/**
 * Where a player has to be standing to interact with a loc.
 */
object LocInteraction {

    /**
     * How far [placementsCovering] looks for an origin whose footprint reaches
     * the clicked tile.
     *
     * 10 is measured, not picked: of 6,867,629 placements, 94.14% are 1x1 and
     * **99.83% have max(width, length) <= 10**. The tail runs to 48x40, so this
     * is a stated cut and not a claim of completeness - a click on the far corner
     * of a 48-tile loc resolves as a bare tile, exactly as it did before.
     */
    val radius: Int = System.getProperty("opennxt.locInteraction.radius")?.toIntOrNull() ?: 10

    /**
     * One placed loc, with the tile range it actually occupies.
     *
     * [x0]..[x1] and [z0]..[z1] are inclusive and already rotation-swapped.
     */
    data class Placed(
        val locId: Int,
        val originX: Int,
        val originZ: Int,
        val type: Int,
        val rot: Int,
        val width: Int,
        val length: Int
    ) {
        val dx: Int get() = LocClipping.footprint(rot, width, length).first
        val dz: Int get() = LocClipping.footprint(rot, width, length).second
        val x0: Int get() = originX
        val z0: Int get() = originZ
        val x1: Int get() = originX + dx - 1
        val z1: Int get() = originZ + dz - 1

        val isWall: Boolean get() = type in 0..3 || type == 9

        /** The wall edge this shape blocks, or 0 for a shape whose edge is unresolved. */
        val edgeMask: Int get() = LocClipping.WALL_EDGE[type]?.get(rot and 3) ?: 0

        fun covers(x: Int, z: Int): Boolean = x in x0..x1 && z in z0..z1

        val tiles: Int get() = dx * dz

        override fun toString() =
            "Placed($locId @$originX,$originZ t$type r$rot ${dx}x$dz [$x0..$x1,$z0..$z1])"
    }

    /**
     * Every placed loc whose FOOTPRINT covers ([x], [z]) - not only those whose
     * `map_loc` origin is that tile.
     *
     * `map_loc` records one row per placement, at the footprint's origin. A 2x2
     * loc therefore leaves three of its four tiles with no row at all, and
     * `OpLocHandler.placementsAt` - which matches the origin exactly - returns
     * NOTHING for a click on one of them. Measured:
     *
     * ```
     * multi-tile footprint placements 391,933
     * tiles they cover that carry no row of their own 5,661,874
     * ...of placements whose loc is clickable 26,151
     * ...tiles those cover with no row of their own 99,153
     * ```
     *
     * So 99,153 tiles a player can click resolve to "map_loc has 0 loc(s)
     * there". This is the function that answers them.
     */
    fun placementsCovering(x: Int, z: Int, plane: Int): List<Placed> {
        if (!RsDatabase.available) return emptyList()
        val r = radius
        val out = ArrayList<Placed>()
        val squaresX = setOf((x - r) / CollisionMap.SIDE, (x + r) / CollisionMap.SIDE)
        val squaresZ = setOf((z - r) / CollisionMap.SIDE, (z + r) / CollisionMap.SIDE)
        for (sz in squaresZ) for (sx in squaresX) {
            if (sx < 0 || sz < 0) continue
            val sid = (sz shl 7) or sx
            val bx = sx * CollisionMap.SIDE
            val bz = sz * CollisionMap.SIDE
            val sql = "SELECT m.x AS lx, m.y AS lz, m.loc_id AS id, m.type AS t, m.rot AS r, " +
                "COALESCE(l.width, 1) AS w, COALESCE(l.length, 1) AS len " +
                "FROM map_loc m JOIN locs l ON l.id = m.loc_id " +
                "WHERE m.square_id = ? AND m.plane = $plane " +
                "AND m.x BETWEEN ${x - r - bx} AND ${x + r - bx} " +
                "AND m.y BETWEEN ${z - r - bz} AND ${z + r - bz}"
            RsDatabase.queryAll(sql, sid) { rs ->
                Placed(
                    locId = rs.getInt("id"),
                    originX = bx + rs.getInt("lx"),
                    originZ = bz + rs.getInt("lz"),
                    type = rs.getInt("t"),
                    rot = rs.getInt("r"),
                    width = rs.getInt("w"),
                    length = rs.getInt("len")
                )
            }.forEach { if (it.covers(x, z)) out.add(it) }
        }
        return out.sortedWith(
            compareBy(
                { if (it.originX == x && it.originZ == z) 0 else 1 },
                { it.tiles },
                { it.locId }
            )
        )
    }

    /** The covering placement for [locId] specifically, or null. */
    fun placementOf(locId: Int, x: Int, z: Int, plane: Int): Placed? =
        placementsCovering(x, z, plane).firstOrNull { it.locId == locId }

    /**
     * Every tile a player may stand on to interact with [p], nearest-first from
     * ([fromX], [fromZ]).
     *
     * Only WALKABLE tiles are returned, so a candidate is never a tile the
     * pathfinder would refuse anyway. The order is (Chebyshev distance from the
     * player, then z, then x) - the tie-break is fixed for the same reason
     * [PathFinder]'s direction order is: two equally good destinations are not
     * interchangeable when the result is logged and diffed.
     */
    fun standingTiles(p: Placed, plane: Int, fromX: Int, fromZ: Int): List<IntArray> {
        val cand = LinkedHashSet<Long>()
        fun add(x: Int, z: Int) { cand.add((x.toLong() shl 32) or (z.toLong() and 0xffffffffL)) }

        if (p.isWall) {
            // A wall occupies no tile - it is one EDGE of its tile. The two places
            // a player can be to use it are that tile and the tile on the other
            // side of the edge. Strictest reading, and the one that walks.
            add(p.originX, p.originZ)
            when (p.edgeMask) {
                CollisionMap.WALL_W -> add(p.originX - 1, p.originZ)
                CollisionMap.WALL_E -> add(p.originX + 1, p.originZ)
                CollisionMap.WALL_N -> add(p.originX, p.originZ + 1)
                CollisionMap.WALL_S -> add(p.originX, p.originZ - 1)
                else -> {
                    // Types 1, 3 and 9, and the two-edge corner shape: the edge is
 // either unresolved or plural, so every orthogonal neighbour is
                    // offered rather than guessing one.
                    add(p.originX - 1, p.originZ); add(p.originX + 1, p.originZ)
                    add(p.originX, p.originZ - 1); add(p.originX, p.originZ + 1)
                }
            }
        } else {
            // Standing ON it counts when it does not block - Wheat is clip 0 and a
            // player walks through it. The walkable filter below decides that from
            // the collision map rather than from a guess about the shape.
            for (x in p.x0..p.x1) for (z in p.z0..p.z1) add(x, z)
            // ...and the orthogonal ring. Corners are excluded: RS does not let you
            // reach most objects diagonally, and offering a corner would sometimes
            // stop the walk one tile short of anywhere useful.
            for (x in p.x0..p.x1) { add(x, p.z0 - 1); add(x, p.z1 + 1) }
            for (z in p.z0..p.z1) { add(p.x0 - 1, z); add(p.x1 + 1, z) }
        }

        return cand.asSequence()
            .map { intArrayOf((it shr 32).toInt(), (it and 0xffffffffL).toInt()) }
            .filter { CollisionMap.walkable(it[0], it[1], plane) }
            .sortedWith(
                compareBy(
                    { maxOf(Math.abs(it[0] - fromX), Math.abs(it[1] - fromZ)) },
                    { it[1] },
                    { it[0] }
                )
            )
            .toList()
    }

    /** Whether ([px], [pz]) is already a legal place to stand for [p]. */
    fun inRange(p: Placed, plane: Int, px: Int, pz: Int): Boolean =
        standingTiles(p, plane, px, pz).any { it[0] == px && it[1] == pz }
}
