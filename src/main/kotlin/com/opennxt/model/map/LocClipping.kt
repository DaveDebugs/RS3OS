package com.opennxt.model.map

import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * The layer that makes walls solid: loc clipping, derived from `map_loc` and the
 * loc config, applied into [CollisionMap] one map square at a time.
 */
object LocClipping {
    private val logger = KotlinLogging.logger { }

    /** Kill switch. Default ON. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.locClipping") != "false"

    /**
     * How many (square, plane) pairs stay applied. A 256-tile scene spans at most
     * 25 squares per plane, and bridge support loads two planes, so 256 holds
     * several scenes' worth of walking plus the BFS's lazy expansion into
     * intermediate squares.
     */
    val capacity: Int = System.getProperty("opennxt.locClipping.capacity")?.toIntOrNull() ?: 256

    /**
     * Wall edge mask per rotation, for the two shapes the measurement covers.
     * See the class doc: rot 0 = W, 1 = N, 2 = E, 3 = S; type 2 is the corner
     * that carries two of them.
     */
    val WALL_EDGE: Map<Int, IntArray> = mapOf(
        0 to intArrayOf(CollisionMap.WALL_W, CollisionMap.WALL_N, CollisionMap.WALL_E, CollisionMap.WALL_S),
        2 to intArrayOf(
            CollisionMap.WALL_W or CollisionMap.WALL_N,
            CollisionMap.WALL_N or CollisionMap.WALL_E,
            CollisionMap.WALL_E or CollisionMap.WALL_S,
            CollisionMap.WALL_S or CollisionMap.WALL_W
        )
    )

    val CORNER_POST: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(CollisionMap.WALL_NW, CollisionMap.WALL_NE, CollisionMap.WALL_SE, CollisionMap.WALL_SW),
        3 to intArrayOf(CollisionMap.WALL_NW, CollisionMap.WALL_NE, CollisionMap.WALL_SE, CollisionMap.WALL_SW)
    )

    /**
     * `map_loc.type` 9, the diagonal wall.
     *
     * - it really is a diagonal, and rotation really does pick which
     * diagonal. A run of diagonal walls continues into the tile that shares the
     * segment's endpoints, so the neighbour it chains to identifies its
     * orientation. Over all 132,448 placements:
     *
     * ```
     * rot 0 n=33,309 NE/SW neighbour 48.3% NW/SE neighbour 4.8%
     * rot 1 n=33,097 NE/SW neighbour 4.8% NW/SE neighbour 46.5%
     * rot 2 n=32,891 NE/SW neighbour 48.5% NW/SE neighbour 5.0%
     * rot 3 n=33,151 NE/SW neighbour 4.8% NW/SE neighbour 47.6%
     * ```
     *
     * A 10x margin, and the two pairs pick OPPOSITE diagonals: rot 0/2 lie along
     * SW-NE, rot 1/3 along NW-SE.
     *
     * AUTHORED - what it does to movement. A diagonal wall cuts a tile in HALF.
     * Nothing in this collision model can represent half a tile: the mask has
     * four edges and four corners and no notion of an interior partition. Every
     * implementation of this in every server promotes it to a whole-tile block,
     * and that promotion is a decision, not a reading of the cache.
     */
    const val DIAGONAL_TYPE = 9

    /** @see DIAGONAL_TYPE */
    @Volatile
    var diagonalWallsEnabled: Boolean = System.getProperty("opennxt.experiment.map.diagonalWalls") == "true"

    /**
     * Printed once at startup, so no reader can mistake a blocked diagonal tile
     * for something the cache stated.
     */
    val PROVENANCE: String =
        "wall mask: 8 directions. and ON: four EDGE bits from map_loc type 0/2 (rot->edge " +
            " 7,283-to-1 by prior work) and four CORNER bits from type 1/3 (rot->corner " +
            "98.9%/89.7% against a 25.0% shuffle control and a 0.4% off-by-one, n=72,217+14,469 decided). " +
            "Corner bits cost 0 of 596,709 reachable tiles over 347 squares while closing 1,436 diagonal " +
            "cuts ( flood, the one figure here not re-derived on the cache import; " +
            "a 24-square flood says 0 of 28,185 and 212 cuts); that zero is why they " +
            "default ON. Corner posts are NOT the doorframe fix - " +
            "of 115 posts sitting on a door wall's own endpoint they close 0 cuts the door's edge did not " +
            "already close. AUTHORED and OFF: type 9 (132,448 placements, confirmed diagonal at a 10x " +
            "chaining margin) as a WHOLE-tile block, because the model cannot hold half a tile; that " +
            "promotion strands 34,005 of 596,709 reachable tiles (5.7%, same flood). " +
            "Settle it by splitting a type-9 " +
            "tile into two half-tile nodes in the pathfinder rather than by blocking it. " +
            "-Dopennxt.experiment.map.walledges=false, -Dopennxt.experiment.map.diagonalWalls=true."

    /** `map_loc.type` values this applies as a wall edge. */
    val WALL_TYPES: Set<Int> = WALL_EDGE.keys

    /** `map_loc.type` values this applies as a corner post. */
    val CORNER_TYPES: Set<Int> = CORNER_POST.keys

    /** `map_loc.type` values this applies as an occupied footprint. */
    val FOOTPRINT_TYPES: IntRange = 10..21

    /**
     * The clip type, exactly as computes it.
     */
    fun clipType(def: LocDefinition, op74: Boolean = false): Int {
        var clip = 2
        if (def.walkable) clip = 0
        if (def.blocksMovement) clip = 1
        if (op74) clip = 0
        return clip
    }

    /**
     * `rot 0/2 -> (width, length)`; `rot 1/3` swap.
     */
    fun footprint(rot: Int, width: Int, length: Int): Pair<Int, Int> =
        if ((rot and 0xfd) == 0) width to length else length to width

    // ---------------------------------------------------------------- defs

    /** clipType, width and length for every loc, in three queries rather than 139,587. */
    private class ClipDef(val clip: Int, val width: Int, val length: Int)

    private val clipDefs: Map<Int, ClipDef> by lazy {
        if (!RsDatabase.available) return@lazy emptyMap()
        val op74 = HashSet(
            RsDatabase.queryAll("SELECT id FROM locs_attr WHERE field = 'unknown_4A'") { it.getInt(1) }
        )
        val out = HashMap<Int, ClipDef>(150_000)
        RsDatabase.queryAll(
            "SELECT id, walkable, blocks_movement, width, length FROM locs"
        ) { rs ->
            val id = rs.getInt("id")
            var clip = 2
            if (rs.getInt("walkable") != 0) clip = 0
            if (rs.getInt("blocks_movement") != 0) clip = 1
            if (id in op74) clip = 0
            val w = rs.getInt("width").let { if (rs.wasNull() || it <= 0) 1 else it }
            val l = rs.getInt("length").let { if (rs.wasNull() || it <= 0) 1 else it }
            id to ClipDef(clip, w, l)
        }.forEach { (id, d) -> out[id] = d }
        logger.info {
            "loc clipping: ${out.size} loc definitions, ${op74.size} of them pass-through via opcode 74"
        }
        out
    }

    /**
     * The clip type this layer will actually use for [locId] — i.e. [clipType]
     * with the opcode-74 pass-through already applied.
     *
     * Exists so `Doors` cannot diverge from the map layer. It used to call
     * `clipType(def, op74 = false)`, which disagreed for locs in
     * `Open n unknown_4A` that are not `walkable`: this layer added no edge
     * (clip 0) while `Doors.blocksWhenShut` said true, so opening was a no-op
     * that latched `edgeRemoved`, and CLOSING then CREATED an edge that
     * `map_loc` never stated. Measured: 336 such locs, 22 placed as type 0 —
     * over-blocking, the direction this file's own doc calls unsafe.
     *
     * Falls back to the def-only rule when the id is unknown to the index.
     */
    fun clipTypeFor(locId: Int, def: LocDefinition): Int =
        clipDefs[locId]?.clip ?: clipType(def, op74 = false)

    /**
     * The clip type this layer will use for [locId], or 2 (the blocking default)
     * for an id the index does not know.
     *
     * Public so a check can filter placements by the SHIPPED rule instead of
     * re-deriving `walkable`/`blocks_movement`/opcode-74 for itself - a check
     * that reimplements the thing it is checking measures its own copy.
     */
    fun clipOf(locId: Int): Int = clipDefs[locId]?.clip ?: 2

    /** How many loc definitions carry clip info. 0 until first use. */
    fun definitionCount(): Int = clipDefs.size

    /** Loc ids that set opcode 74 (`unknown_4A`) and are therefore pass-through. */
    fun op74Locs(): Int =
        if (!RsDatabase.available) 0
        else RsDatabase.queryAll("SELECT COUNT(*) FROM locs_attr WHERE field = 'unknown_4A'") { it.getInt(1) }
            .firstOrNull() ?: 0

    // ------------------------------------------------------------- squares

    /** What one square contributed, so it can be taken back exactly. */
    /**
     * Callbacks run immediately after a square's wall contribution lands.
     *
     * ## Why this exists — a measured defect, not a design flourish
     *
     * [applySquare] re-adds every wall edge `map_loc` states for the square. It
     * has no idea that another layer may have deliberately taken one of those
     * edges back OUT: an open door. `Doors` removes the edge once and latches
     * `edgeRemoved = true`, so when the LRU evicts a square and the player walks
     * back into it, the edge returns and the door's latch says "already removed"
     * — the door reads as open, is impassable, and NEVER recovers. Clicking it
     * afterwards only toggles the latch; the mask stays blocked forever.
     *
     * Reproduced on the real Lumbridge gate (loc 2320 at 3252,3266, type 0
     * rot 2): shut mask 0x8 -> open mask 0x0, step east allowed -> evict ->
     * re-apply -> mask 0x8 with open still true, step east REFUSED, and the
     * subsequent open/close cycle oscillates 0x8 <-> 0x8. Reachable in a normal
     * session: the cap is 128 (square, plane) pairs and WorldPlayer.tick applies
     * one square per tick as the player walks.
     *
     * This ALSO falsified this file's own former headline claim that "eviction
     * fails OPEN ... it can never seal a square shut". It could, and did.
     *
     * A list of callbacks rather than a direct call into `Doors`, because the
     * map layer must not import content — that dependency runs the other way.
     */
    val afterApply = java.util.concurrent.CopyOnWriteArrayList<(Int, Int) -> Unit>()

    /**
     * [corners] and [diag9] freeze the two experiment switches at the moment the
     * square was applied, so [unload] removes exactly what [applySquare] added
     * even if a switch moved in between. See [undo].
     */
    private class Applied(
        val squareId: Int, val plane: Int, val walls: Int, val footprints: Int,
        val corners: Boolean, val diag9: Boolean
    )

    /**
     * Applied squares, insertion-ordered. THE single source of truth for "is this
     * square loaded"; [unload] removes from here and from [CollisionMap] together.
     */
    private val loaded = LinkedHashMap<Long, Applied>()

    private fun squareKey(squareId: Int, plane: Int) = (squareId.toLong() shl 8) or plane.toLong()

    /** Whether the square containing ([x], [z]) is applied for [plane]. */
    fun isLoadedAt(x: Int, z: Int, plane: Int): Boolean {
        val sid = ((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE)
        return synchronized(loaded) { loaded.containsKey(squareKey(sid, plane)) }
    }

    fun loadedSquares(): Int = synchronized(loaded) { loaded.size }

    /**
     * Applies the loc clipping for the square containing ([x], [z]) on [plane].
     *
     * Idempotent: a square already applied is left alone rather than counted
     * twice. Returns the number of `map_loc` rows that contributed something, or
     * 0 if the square was already loaded, the experiment is off, or there is no
     * database.
     */
    fun applySquareAt(x: Int, z: Int, plane: Int): Int {
        if (!enabled || !RsDatabase.available) return 0
        val sid = ((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE)
        return applySquare(sid, plane)
    }

    fun applySquare(squareId: Int, plane: Int): Int {
        if (!enabled || !RsDatabase.available) return 0
        val k = squareKey(squareId, plane)
        synchronized(loaded) { if (loaded.containsKey(k)) return 0 }

        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        // Read the two switches ONCE, here, and carry them on the Applied record.
        // Everything below - including the race undo - uses these locals, so one
        // square is always applied and removed under one consistent pair. See
        // [undo] for why re-reading them later is a bug rather than a shortcut.
        val corners = CollisionMap.cornerPostsEnabled
        val diag9 = diagonalWallsEnabled
        var walls = 0
        var footprints = 0
        val rows = RsDatabase.queryAll(
            "SELECT x, y, loc_id, type, rot FROM map_loc WHERE square_id = ? AND plane = $plane", squareId
        ) { rs ->
            intArrayOf(rs.getInt("x"), rs.getInt("y"), rs.getInt("loc_id"), rs.getInt("type"), rs.getInt("rot"))
        }
        for (r in rows) {
            val (lx, lz, locId) = Triple(r[0], r[1], r[2])
            val type = r[3]
            val rot = r[4] and 3
            val def = clipDefs[locId] ?: continue
            if (def.clip == 0) continue
            val wx = baseX + lx
            val wz = baseZ + lz
            val edge = WALL_EDGE[type]
            val post = CORNER_POST[type]
            if (edge != null) {
                CollisionMap.addWall(wx, wz, plane, edge[rot])
                walls++
            } else if (post != null && corners) {
                // A CORNER bit, not an edge one. Goes through the same counted
                // add/remove as a wall so a post and a wall can share a tile, and
                // is counted into `walls` so `undo` needs no second rule.
                CollisionMap.addWall(wx, wz, plane, post[rot])
                walls++
            } else if (type == DIAGONAL_TYPE && diag9) {
                CollisionMap.addOccupancy(wx, wz, plane)
                footprints++
            } else if (type in FOOTPRINT_TYPES) {
                val (dx, dz) = footprint(rot, def.width, def.length)
                for (ox in 0 until dx) for (oz in 0 until dz) {
                    CollisionMap.addOccupancy(wx + ox, wz + oz, plane)
                }
                footprints++
            }
        }
        synchronized(loaded) {
            if (loaded.containsKey(k)) {
                // Another thread won the race. Take this pass back rather than
                // leaving a second, unrecorded contribution behind.
                undo(plane, rows, baseX, baseZ, corners, diag9)
                return 0
            }
            loaded[k] = Applied(squareId, plane, walls, footprints, corners, diag9)
        }
        // Let layers that removed an edge re-assert it before anything reads
        // this square. Outside the lock: a callback re-entering applySquare
        // would deadlock, and the contribution is already recorded.
        afterApply.forEach { it(squareId, plane) }
        evictIfNeeded()
        return walls + footprints
    }

    /**
     * [corners] and [diag9] are the switch values AS THEY WERE WHEN THE SQUARE
     * WAS APPLIED, carried on [Applied], not re-read from the switches.
     *
     * Re-reading them would resurrect this file's signature bug in a new place:
     * a check (or an operator) that flips `walledges` between apply and unload
     * would make the removal pass skip contributions the apply pass made, and the
     * corner bits for that square would be stuck ON for the life of the process
     * with nothing recording that they were there. Passing them in makes apply and
     * undo provably symmetric for any switch schedule.
     */
    private fun undo(
        plane: Int, rows: List<IntArray>, baseX: Int, baseZ: Int,
        corners: Boolean, diag9: Boolean
    ) {
        for (r in rows) {
            val def = clipDefs[r[2]] ?: continue
            if (def.clip == 0) continue
            val wx = baseX + r[0]
            val wz = baseZ + r[1]
            val rot = r[4] and 3
            val edge = WALL_EDGE[r[3]]
            val post = CORNER_POST[r[3]]
            if (edge != null) {
                CollisionMap.removeWall(wx, wz, plane, edge[rot])
            } else if (post != null && corners) {
                CollisionMap.removeWall(wx, wz, plane, post[rot])
            } else if (r[3] == DIAGONAL_TYPE && diag9) {
                CollisionMap.removeOccupancy(wx, wz, plane)
            } else if (r[3] in FOOTPRINT_TYPES) {
                val (dx, dz) = footprint(rot, def.width, def.length)
                for (ox in 0 until dx) for (oz in 0 until dz) {
                    CollisionMap.removeOccupancy(wx + ox, wz + oz, plane)
                }
            }
        }
    }

    /**
     * Takes one square's contribution back out of [CollisionMap] and forgets it.
     *
     * Re-reads `map_loc` to know what to remove. That is a second query for the
     * same rows, and it is deliberate: the alternative is caching the row list,
     * which is a second structure that can disagree with [loaded] - which is the
     * exact bug the class doc is about.
     */
    fun unload(squareId: Int, plane: Int): Boolean {
        val k = squareKey(squareId, plane)
        synchronized(loaded) { if (!loaded.containsKey(k)) return false }
        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        val rows = RsDatabase.queryAll(
            "SELECT x, y, loc_id, type, rot FROM map_loc WHERE square_id = ? AND plane = $plane", squareId
        ) { rs ->
            intArrayOf(rs.getInt("x"), rs.getInt("y"), rs.getInt("loc_id"), rs.getInt("type"), rs.getInt("rot"))
        }
        synchronized(loaded) {
            val was = loaded.remove(k) ?: return false
            undo(plane, rows, baseX, baseZ, was.corners, was.diag9)
        }
        return true
    }

    /** Unloads the square containing ([x], [z]). For checks. */
    fun unloadAt(x: Int, z: Int, plane: Int): Boolean =
        unload(((z / CollisionMap.SIDE) shl 7) or (x / CollisionMap.SIDE), plane)

    private fun evictIfNeeded() {
        while (true) {
            val victim = synchronized(loaded) {
                if (loaded.size <= capacity) return
                loaded.entries.first().value
            }
            if (!unload(victim.squareId, victim.plane)) return
        }
    }

    /** Forgets everything and takes it all back out of [CollisionMap]. */
    fun clear() {
        val all = synchronized(loaded) { loaded.values.toList() }
        all.forEach { unload(it.squareId, it.plane) }
        synchronized(loaded) { loaded.clear() }
    }

    /**
     * Applies the loc clipping for every square a scene centred on
     * ([x], [z], [plane]) touches - the 3x3 block of squares around the player,
     * which covers the 104-tile scene the client is sent whatever its offset
     * inside its own square.
     *
     * Returns the number of squares newly applied.
     */
    fun applySceneAt(x: Int, z: Int, plane: Int, maxSquares: Int = Int.MAX_VALUE): Int {
        if (!enabled || !RsDatabase.available) return 0
        var n = 0
        for ((sx, sz) in SCENE_ORDER) {
            if (n >= maxSquares) break
            val px = x + sx * CollisionMap.SIDE
            val pz = z + sz * CollisionMap.SIDE
            if (px < 0 || pz < 0) continue
            if (applySquareAt(px, pz, plane) > 0) n++
        }
        return n
    }

    /**
     * The player's OWN square first, then the ring around it.
     *
     * Order matters once [applySceneAt] is allowed to stop early: with
     * `maxSquares = 1` per tick the tile the player is standing on gets its walls
     * on the very first tick, and the ring fills in over the next eight. The
     * reverse order would leave the player able to walk out of the building they
     * spawned in while the server busied itself with the field next door.
     */
    private val SCENE_ORDER = listOf(
        0 to 0,
        -1 to 0, 1 to 0, 0 to -1, 0 to 1,
        -1 to -1, 1 to -1, -1 to 1, 1 to 1
    )

    /**
     * Builds the definition index now rather than on whichever thread happens to
     * need it first.
     */
    fun warm(): Int = definitionCount()
}
