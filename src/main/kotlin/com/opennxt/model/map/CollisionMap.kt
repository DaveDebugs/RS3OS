package com.opennxt.model.map

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections

/**
 * Tile collision, read from `map_blocked` in the definition database.
 */
object CollisionMap {
    private val logger = KotlinLogging.logger { }

    const val SIDE = 64
    private const val BITMAP_BYTES = SIDE * SIDE / 8

    /** (squareX, squareZ) -> square_id, loaded once. */
    private val squareIds = HashMap<Int, Int>()

    /** (square_id, plane) -> bitmap, or null for "looked up, absent". LRU-bounded. */
    private const val CACHE_SIZE = 4096
    private val bitmaps = Collections.synchronizedMap(
        object : LinkedHashMap<Long, ByteArray?>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, ByteArray?>) = size > CACHE_SIZE
        }
    )

    /** Runtime overrides: (x, z, plane) -> blocked. Wins over the bitmap. */
    private val dynamic = Collections.synchronizedMap(HashMap<Long, Boolean>())

    // ================================================================
    // WALL EDGES
    // ================================================================

    /**
     * Edge bits, exactly the values `tools/clipmap.py` bakes into `map_clip`.
     *
     * All EIGHT are live as of the corner-post measurement. The four orthogonal
     * ones are EDGES of the tile and are set by `map_loc.type` 0 and 2; the four
     * diagonal ones are CORNERS of the tile - lattice points, not edges - and are
     * set by `map_loc.type` 1 and 3. See [LocClipping.CORNER_POST] for the
     * rot -> corner measurement and its shuffle control.
     *
     * The two halves are not interchangeable and are consumed by different code:
     * an EDGE bit stops an orthogonal step (and, transitively, the diagonals that
     * decompose through it), a CORNER bit stops ONLY the one diagonal step that
     * passes through that lattice point. That asymmetry is why adding the corner
     * bits cannot strand anybody - see [cornerBlocked].
     */
    const val WALL_NW = 0x1
    const val WALL_N = 0x2
    const val WALL_NE = 0x4
    const val WALL_E = 0x8
    const val WALL_SE = 0x10
    const val WALL_S = 0x20
    const val WALL_SW = 0x40
    const val WALL_W = 0x80

    /**
     * The eight bits in a fixed slot order, so [bumpWall] and [wallMask] cannot
     * disagree about which slot means what. Order: N, E, S, W, NW, NE, SE, SW.
     */
    private val BIT_ORDER = intArrayOf(WALL_N, WALL_E, WALL_S, WALL_W, WALL_NW, WALL_NE, WALL_SE, WALL_SW)

    /**
     * Per-tile, per-direction wall COUNTS, indexed by [BIT_ORDER].
     */
    private val wallCounts = Collections.synchronizedMap(HashMap<Long, IntArray>())

    /** Loc footprint occupancy, counted for the same reason [wallCounts] is. */
    private val occupancy = Collections.synchronizedMap(HashMap<Long, Int>())

    /**
     * `-Dopennxt.experiment.map.walledges=false` turns the DIAGONAL half of the
     * mask off - the corner posts. Default **ON**.
     */
    @Volatile
    var cornerPostsEnabled: Boolean = System.getProperty("opennxt.experiment.map.walledges") != "false"

    private fun dirIndex(bit: Int): Int = when (bit) {
        WALL_N -> 0; WALL_E -> 1; WALL_S -> 2; WALL_W -> 3
        WALL_NW -> 4; WALL_NE -> 5; WALL_SE -> 6; WALL_SW -> 7
        else -> -1
    }

    /**
     * Every bit currently blocked on this tile - four EDGES and four CORNERS.
     *
     * Callers that only want the orthogonal half must mask it themselves. The
     * two existing `when (edgeMask)` consumers
     * ([LocInteraction.standingTiles], `Doors`) are unaffected: they switch on a
     * mask built from [LocClipping.WALL_EDGE], which is still types 0 and 2 only,
     * not on this function's result.
     */
    fun wallMask(x: Int, z: Int, plane: Int = 0): Int {
        val c = wallCounts[key3(x, z, plane)] ?: return 0
        var m = 0
        for (i in BIT_ORDER.indices) if (c[i] > 0) m = m or BIT_ORDER[i]
        return m
    }

    /** Just the four orthogonal edge bits, for callers that predate the corners. */
    fun edgeMaskOnly(x: Int, z: Int, plane: Int = 0): Int =
        wallMask(x, z, plane) and (WALL_N or WALL_E or WALL_S or WALL_W)

    /** Adds one contribution for each of the eight bits set in [mask]. */
    fun addWall(x: Int, z: Int, plane: Int, mask: Int) = bumpWall(x, z, plane, mask, +1)

    /** Removes one contribution for each of the eight bits set in [mask]. Never goes below zero. */
    fun removeWall(x: Int, z: Int, plane: Int, mask: Int) = bumpWall(x, z, plane, mask, -1)

    private fun bumpWall(x: Int, z: Int, plane: Int, mask: Int, delta: Int) {
        if (mask == 0) return
        val k = key3(x, z, plane)
        synchronized(wallCounts) {
            val c = wallCounts[k] ?: IntArray(BIT_ORDER.size).also { if (delta > 0) wallCounts[k] = it }
            for (bit in BIT_ORDER) {
                if (mask and bit == 0) continue
                val i = dirIndex(bit)
                c[i] = maxOf(0, c[i] + delta)
            }
            if (c.all { it == 0 }) wallCounts.remove(k)
        }
    }

    fun addOccupancy(x: Int, z: Int, plane: Int) = bumpOccupancy(x, z, plane, +1)
    fun removeOccupancy(x: Int, z: Int, plane: Int) = bumpOccupancy(x, z, plane, -1)

    private fun bumpOccupancy(x: Int, z: Int, plane: Int, delta: Int) {
        val k = key3(x, z, plane)
        synchronized(occupancy) {
            val n = (occupancy[k] ?: 0) + delta
            if (n <= 0) occupancy.remove(k) else occupancy[k] = n
        }
    }

    /** How many tiles carry at least one wall edge. For checks. */
    fun walledTiles(): Int = wallCounts.size

    /** How many tiles are occupied by a loc footprint. For checks. */
    fun occupiedTiles(): Int = occupancy.size

    /** Drops every wall edge and every footprint. Does NOT touch [dynamic]. */
    fun clearLocClipping() {
        wallCounts.clear()
        occupancy.clear()
    }

    /**
     * Whether a step from ([x], [z]) in direction ([dx], [dz]) crosses a blocked
     * edge.
     *
     * An edge belongs to two tiles at once. It is blocked if EITHER side declares
     * it, so a wall stated once - on the loc's own tile, which is all `map_loc`
     * gives - stops movement in both directions without the mirror bit the
     * classic client writes onto the neighbour.
     */
    fun edgeBlocked(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        val ep = BridgeFlags.effectivePlane(x, z, plane)
        val from = wallMask(x, z, ep)
        val to = wallMask(x + dx, z + dz, BridgeFlags.effectivePlane(x + dx, z + dz, plane))
        return when {
            dx > 0 && dz == 0 -> (from and WALL_E) != 0 || (to and WALL_W) != 0
            dx < 0 && dz == 0 -> (from and WALL_W) != 0 || (to and WALL_E) != 0
            dz > 0 && dx == 0 -> (from and WALL_N) != 0 || (to and WALL_S) != 0
            dz < 0 && dx == 0 -> (from and WALL_S) != 0 || (to and WALL_N) != 0
            else -> false
        }
    }

    /**
     * Whether a DIAGONAL step from ([x], [z]) by ([dx], [dz]) passes through a
     * corner post.
     */
    fun cornerBlocked(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        if (!cornerPostsEnabled) return false
        if (dx == 0 || dz == 0) return false
        // (a, b, bit): tile (a, b) names the shared lattice point with `bit`.
        return when {
            dx > 0 && dz > 0 ->
                has(x, z, WALL_NE, plane) || has(x + 1, z, WALL_NW, plane) ||
                    has(x, z + 1, WALL_SE, plane) || has(x + 1, z + 1, WALL_SW, plane)
            dx > 0 && dz < 0 ->
                has(x, z, WALL_SE, plane) || has(x + 1, z, WALL_SW, plane) ||
                    has(x, z - 1, WALL_NE, plane) || has(x + 1, z - 1, WALL_NW, plane)
            dx < 0 && dz > 0 ->
                has(x, z, WALL_NW, plane) || has(x - 1, z, WALL_NE, plane) ||
                    has(x, z + 1, WALL_SW, plane) || has(x - 1, z + 1, WALL_SE, plane)
            else ->
                has(x, z, WALL_SW, plane) || has(x - 1, z, WALL_SE, plane) ||
                    has(x, z - 1, WALL_NW, plane) || has(x - 1, z - 1, WALL_NE, plane)
        }
    }

    private fun has(x: Int, z: Int, bit: Int, plane: Int): Boolean = (wallMask(x, z, plane) and bit) != 0

    @Volatile private var warnedNoCollision = false

    val available: Boolean by lazy {
        val ok = RsDatabase.available && RsDatabase.hasTable("map_blocked")
        if (!ok) logger.warn { "No map_blocked table - every tile will report blocked" }
        else loadSquareIndex()
        ok
    }

    private fun loadSquareIndex() {
        RsDatabase.queryAll("SELECT square_id, i, j FROM map_square") { rs ->
            Triple(rs.getInt("square_id"), rs.getInt("i"), rs.getInt("j"))
        }.forEach { (id, _, _) ->
            // square_id packs the square's world position: low 7 bits are the x
            // square, the rest are the z square. Same packing testdoors.py uses to
            // turn a placement back into world coordinates.
            squareIds[key2(id and 0x7f, id shr 7)] = id
        }
        logger.info { "Loaded ${squareIds.size} map squares" }
    }

    /** How many map squares the collision index knows about. 0 until [available]. */
    fun loadedSquares(): Int = squareIds.size

    private fun key2(a: Int, b: Int) = (a shl 16) or (b and 0xffff)
    private fun key3(x: Int, z: Int, plane: Int) =
        (plane.toLong() shl 60) or (x.toLong() shl 30) or z.toLong()

    private fun squareId(x: Int, z: Int): Int? = squareIds[key2(x / SIDE, z / SIDE)]

    /**
     * The bitmap for one (square, plane), from the LRU cache or from the
     * database.
     */
    private fun bitmap(squareId: Int, plane: Int): ByteArray? {
        val k = (squareId.toLong() shl 8) or plane.toLong()
        synchronized(bitmaps) { if (bitmaps.containsKey(k)) return bitmaps[k] }
        val data = RsDatabase.queryOne(
            "SELECT bitmap FROM map_blocked WHERE square_id = ? AND plane = $plane", squareId
        ) { it.getBytes("bitmap") }
        if (data != null && data.size != BITMAP_BYTES) {
            logger.warn { "square $squareId plane $plane: ${data.size} bytes, expected $BITMAP_BYTES" }
        }
        bitmaps[k] = data
        return data
    }

    /** How many (square, plane) bitmaps are cached right now. For checks. */
    fun cachedBitmaps(): Int = bitmaps.size

    /**
     * DEV FALLBACK: with NO collision data at all, treat the world as open.
     *
     * `!available` means the collision source is entirely absent - this package
     * ships without rs3.sqlite - not that a particular square is missing. In
     * that state "unmapped ground is blocked" makes EVERY tile in the world
     * unwalkable, so addStep refuses the first step of every path and the
     * player can never move. That was observed directly:
     *
     *   MOVE_GAMECLICK from (3222,3222) to (3216,3091) -> queued 0 step(s)
     *
     * Refusing all movement protects nothing when there is no map to protect,
     * so this opens it - ONCE, LOUDLY, and only for the total-absence case.
     *
     * The narrower `return true` paths below are UNCHANGED on purpose: if the
     * data IS loaded and a specific square or plane bitmap is missing, that is
     * genuine off-the-edge-of-the-world and stays blocked. This relaxation is
     * exactly as wide as the ignorance that causes it.
     *
     * Consequence to be honest about: with this active players walk through
     * walls, because there are no walls. Load rs3.sqlite and it turns itself
     * off.
     */
    fun blocked(x: Int, z: Int, plane: Int = 0): Boolean {
        // Bridge plane remap: if the tile above has settings bit 0x2, the bridge
        // deck's collision lives on plane+1 and should be used instead. Without
        // this, pathfinding sees water (plane 0 terrain) where a bridge crosses.
        val ep = BridgeFlags.effectivePlane(x, z, plane)
        dynamic[key3(x, z, ep)]?.let { return it }
        // A loc standing ON the tile, as opposed to a wall on one of its edges.
        // After [dynamic] so an explicit override still wins, and before the
        // bitmap because the bitmap knows nothing about locs at all.
        if (occupancy.isNotEmpty() && (occupancy[key3(x, z, ep)] ?: 0) > 0) return true
        if (!available) {
            if (!warnedNoCollision) {
                warnedNoCollision = true
                mu.KotlinLogging.logger("CollisionMap").warn {
                    "No collision data (rs3.sqlite absent) - treating the ENTIRE world as walkable so " +
                        "movement works. Players will walk through walls. This message appears once."
                }
            }
            return false
        }
        val sid = squareId(x, z) ?: return true
        val bits = bitmap(sid, ep) ?: return true
        // X-MAJOR. The baker bakes `n = x * 64 + y` and this read `z * 64 + x`
 // until - see the retraction on this class. Transposing it is
        // what let a player walk off the roof of Lumbridge castle.
        val idx = (x % SIDE) * SIDE + (z % SIDE)
        val byte = idx shr 3
        if (byte >= bits.size) return true
        return (bits[byte].toInt() shr (idx and 7)) and 1 != 0
    }

    fun walkable(x: Int, z: Int, plane: Int = 0): Boolean = !blocked(x, z, plane)

    /** Override one tile. Pass null to drop the override and fall back to the bitmap. */
    fun setBlocked(x: Int, z: Int, plane: Int, value: Boolean?) {
        val k = key3(x, z, plane)
        if (value == null) dynamic.remove(k) else dynamic[k] = value
    }

    fun clearOverrides() = dynamic.clear()

    fun overrideCount(): Int = dynamic.size

    /**
     * Whether a single step of (dx, dz) is legal.
     *
     * Diagonals require BOTH orthogonal neighbours to be free. RS does not let
     * you cut the corner between two walls, and a pathfinder that allows it
     * produces routes the client will refuse to walk.
     *
     * A diagonal also has to clear the wall edges of BOTH decompositions - go
     * east-then-north and north-then-east - because a diagonal move physically
     * passes the corner and either wall stops it. Checking only one decomposition
     * is how a player slips around the hinge side of a shut door.
     */
    fun canStep(x: Int, z: Int, dx: Int, dz: Int, plane: Int = 0): Boolean {
        if (blocked(x + dx, z + dz, plane)) return false
        if (dx != 0 && dz != 0) {
            if (blocked(x + dx, z, plane)) return false
            if (blocked(x, z + dz, plane)) return false
            if (edgeBlocked(x, z, dx, 0, plane) || edgeBlocked(x + dx, z, 0, dz, plane)) return false
            if (edgeBlocked(x, z, 0, dz, plane) || edgeBlocked(x, z + dz, dx, 0, plane)) return false
            if (cornerBlocked(x, z, dx, dz, plane)) return false
            return true
        }
        return !edgeBlocked(x, z, dx, dz, plane)
    }
}
