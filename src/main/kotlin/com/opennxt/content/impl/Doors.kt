package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.content.SqliteDefinitions
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * Doors: the first content module.
 */
object Doors {
    private val logger = KotlinLogging.logger { }

    const val OPEN = "Open"
    const val CLOSE = "Close"

    /** `map_loc.type` values that are wall shapes: straight, diagonal, corner, square corner, diagonal wall. */
    val WALL_TYPES = setOf(0, 1, 2, 3, 9)

    /**
     * The loc's clip type, derived the way the client's own config parser does.
     */
    fun clipType(def: LocDefinition): Int = LocClipping.clipType(def, op74 = false)

    /**
     * The clip type the MAP LAYER will use for this loc, opcode-74 included.
     *
     * Prefer this over [clipType] wherever a loc id is in hand: the two
     * disagreed for 336 locs (22 placed as type 0), and the disagreement made
     * closing a door CREATE a wall edge `map_loc` never stated.
     */
    fun clipTypeFor(locId: Int, def: LocDefinition): Int = LocClipping.clipTypeFor(locId, def)

    /** [blocksWhenShut] with the map layer's own opcode-74 rule applied. */
    fun blocksWhenShutFor(locId: Int, def: LocDefinition): Boolean = clipTypeFor(locId, def) != 0

    /** Whether a shut instance of this loc should stop a player walking. */
    fun blocksWhenShut(def: LocDefinition): Boolean = clipType(def) != 0

    /** Whether the loc config says this object can be opened at all. */
    fun isOpenable(def: LocDefinition): Boolean = def.actions.any { it == OPEN }

    /**
     * Whether a *placement* is a door rather than a chest: an openable loc put
     * down as a wall shape. Chests, cupboards and drawers also declare "Open"
     * and are placed as type 10 scenery.
     */
    fun isDoorway(def: LocDefinition, type: Int): Boolean = isOpenable(def) && type in WALL_TYPES

    // ---- runtime state --------------------------------------------------

    /**
     * One door instance on one tile.
     *
     * [type] and [rot] are `map_loc`'s shape and rotation for this placement, and
     * they are what turn "block the tile" into "block the wall edge the door
     * actually sits on". [type] = -1 means the caller did not say, and the door
     * falls back to the whole-tile override this module has always used - which
     * is still right for a chest or a cupboard, and is what keeps a caller that
     * only knows a tile working unchanged.
     */
    data class Door(
        val locId: Int,
        val x: Int,
        val z: Int,
        val plane: Int,
        var open: Boolean,
        val type: Int = -1,
        val rot: Int = 0
    ) {
        /** The wall edge this placement blocks when shut, or 0 if it is not a wall. */
        val edgeMask: Int get() = LocClipping.WALL_EDGE[type]?.get(rot and 3) ?: 0

        /**
         * Whether this door has TAKEN ITS EDGE BACK OUT of [CollisionMap].
         *
         * A wall-shaped door does not add its own edge: [LocClipping] already
         * applied it from `map_loc`, because `map_loc` is where the shut world
         * comes from. So opening is a REMOVAL and closing is a RESTORE, and this
         * flag is what keeps the two symmetric - without it a second click would
         * decrement a count it had already decremented, and the fence post
         * sharing the edge would vanish.
         */
        var edgeRemoved: Boolean = false
            internal set

        override fun toString() =
            "Door($locId at $x,$z,$plane ${if (open) "open" else "shut"}" +
                (if (type >= 0) " shape $type rot $rot edge 0x%02x".format(edgeMask) else "") + ")"
    }

    private val doors = HashMap<Long, Door>()

    private fun key(x: Int, z: Int, plane: Int) =
        (plane.toLong() shl 60) or (x.toLong() shl 30) or z.toLong()

    fun at(x: Int, z: Int, plane: Int = 0): Door? = doors[key(x, z, plane)]
    fun isOpen(x: Int, z: Int, plane: Int = 0): Boolean = at(x, z, plane)?.open == true
    fun trackedCount(): Int = doors.size
    fun openCount(): Int = doors.values.count { it.open }

    /**
     * Put a door on a tile and apply its clipping.
     *
     * AUTHORED: "a door starts shut" is a decision, not something the cache says;
     * [open] exists so a world loader can restore saved state, and so the
     * open-door locs - the 698 that declare "Close" and never "Open" - can be
     * placed in the state they represent. Returns null if the loc declares
     * neither option, so a caller cannot accidentally turn a rock into a door.
     */
    fun place(
        locId: Int, x: Int, z: Int, plane: Int = 0, open: Boolean = false,
        type: Int = -1, rot: Int = 0
    ): Door? {
        val def = SqliteDefinitions.loc(locId) ?: return null
        if (!isOpenable(def) && !def.actions.any { it == CLOSE }) return null
        val door = Door(locId, x, z, plane, open = open, type = type, rot = rot)
        doors[key(x, z, plane)] = door
        applyClipping(def, door)
        return door
    }

    /**
     * [place], but leaves an already-tracked door alone.
     *
     * The distinction matters once a packet handler can reach this: a second click
     * on a gate must not reset it to shut, and must not re-add a wall edge that is
     * already counted. Returns the door either way.
     */
    fun placeIfAbsent(
        locId: Int, x: Int, z: Int, plane: Int = 0, open: Boolean = false,
        type: Int = -1, rot: Int = 0
    ): Door? = at(x, z, plane) ?: place(locId, x, z, plane, open, type, rot)

    /**
     * Forget every door and put the world back as it was: tile overrides dropped,
     * and any wall edge an open door had REMOVED restored.
     *
     * Restoring rather than removing is the right direction because a wall-shaped
     * door never added its edge - [LocClipping] did. Forgetting an open door
     * without restoring would leave a hole in a fence with nothing tracking it.
     */
    fun clear() {
        doors.values.forEach { door ->
            CollisionMap.setBlocked(door.x, door.z, door.plane, null)
            if (door.edgeRemoved && door.edgeMask != 0) {
                CollisionMap.addWall(door.x, door.z, door.plane, door.edgeMask)
                door.edgeRemoved = false
            }
        }
        doors.clear()
    }

    /**
     * The door's contribution to collision.
     *
     * A door that knows its shape contributes a WALL EDGE; one that does not
     * contributes the whole-tile override this module has always used. Both are
     * dropped when it opens, and neither writes `false` for an open door - that
     * would make a door built over a chasm passable.
     *
     * The edge goes through [CollisionMap.addWall]/[CollisionMap.removeWall],
     * which COUNT contributions, so a gate leaf sharing an edge with the fence
     * post next to it does not delete the fence when it opens.
     */
    private fun applyClipping(def: LocDefinition, door: Door) {
        // blocksWhenShutFor, not blocksWhenShut: the loc id is in hand here, and
        // the def-only rule disagrees with the map layer on 336 locs (opcode 74,
        // `unknown_4A`). On those, LocClipping states NO edge while the def-only
        // rule says the door blocks, so opening was a silent no-op that latched
        // edgeRemoved and closing then CREATED an edge map_loc never stated -
        // over-blocking, which is the direction this layer must never fail in.
        val shouldBlock = !door.open && blocksWhenShutFor(door.locId, def)
        val mask = door.edgeMask
        if (mask != 0) {
            // A wall shape: the edge, not the tile. Never leave a tile override
            // behind for one of these - it would block walking ALONG the fence,
            // past a gate, which is the bug this whole layer exists to remove.
            CollisionMap.setBlocked(door.x, door.z, door.plane, null)
            if (!blocksWhenShutFor(door.locId, def)) return
            // The baseline (shut) edge belongs to LocClipping. Opening removes it
            // once; closing puts it back once; a repeat in the same direction is a
            // no-op. CollisionMap counts contributions, so this cannot delete a
            // coincident fence post's edge either.
            if (shouldBlock && door.edgeRemoved) {
                CollisionMap.addWall(door.x, door.z, door.plane, mask)
                door.edgeRemoved = false
            } else if (!shouldBlock && !door.edgeRemoved) {
                CollisionMap.removeWall(door.x, door.z, door.plane, mask)
                door.edgeRemoved = true
            }
            return
        }
        if (shouldBlock) CollisionMap.setBlocked(door.x, door.z, door.plane, true)
        else CollisionMap.setBlocked(door.x, door.z, door.plane, null)
    }

    // ---- handlers -------------------------------------------------------

    /**
     * AUTHORED. "Open" toggles: it opens a shut door and shuts an open one. See
     * the no-id-swap note in the class comment for why it is a toggle.
     *
     * Returns "opened" / "closed" so a caller (and the check) can see which way
     * it went, mirroring `tools/content_doors.py`.
     */
    fun onOpen(ctx: LocContext): Any? {
        // A door the server has not seen before is learned on first interaction:
        // the map has 6679 openable placements - every map_loc row whose loc
        // declares "Open" in any action slot, which is the population this
        // handler can be reached for - and pre-registering all of them is the
 // world loader's job, not this handler's. (6666 until; that
        // figure named no population and does not re-derive under any reading
        // tried, so it is restated with its query rather than carried forward.)
        val door = at(ctx.x, ctx.z, ctx.plane)
            ?: run {
                val squareId = (ctx.z / 64) * 128 + (ctx.x / 64)
                val sql = "SELECT type, rot FROM map_loc WHERE square_id = $squareId AND loc_id = ${ctx.locId} AND x = ${ctx.x % 64} AND y = ${ctx.z % 64} AND plane = ${ctx.plane} LIMIT 1"
                val (type, rot) = com.opennxt.resources.sqlite.RsDatabase.queryAll(sql) { rs -> rs.getInt(1) to rs.getInt(2) }.firstOrNull() ?: (-1 to 0)
                Door(ctx.locId, ctx.x, ctx.z, ctx.plane, open = false, type = type, rot = rot).also { doors[key(ctx.x, ctx.z, ctx.plane)] = it }
            }

        door.open = !door.open
        applyClipping(ctx.definition, door)
        return if (door.open) "opened" else "closed"
    }

    /** AUTHORED. "Close" only ever closes; it is a no-op on a door already shut. */
    fun onClose(ctx: LocContext): Any? {
        val door = at(ctx.x, ctx.z, ctx.plane) ?: return "not-a-tracked-door"
        if (!door.open) return "already-closed"
        door.open = false
        applyClipping(ctx.definition, door)
        return "closed"
    }

    // ---- registration ---------------------------------------------------

    /**
     * Binds both options by name, across every loc that declares them. Returns
     * (locs bound to Open, locs bound to Close).
     */
    /**
     * Re-remove the edges of doors that are OPEN in a square that has just been
     * (re-)applied by [LocClipping].
     *
     * [LocClipping.applySquare] re-adds every edge `map_loc` states, including
     * the one an open door removed. Without this, an evict/reload cycle leaves
     * the door open-but-impassable forever, because [edgeRemoved] still reads
     * true so [applyClipping] believes it has nothing to do. Measured on the
     * Lumbridge gate 2320 at (3252,3266): step east went from allowed to refused
     * across an eviction and never recovered.
     *
     * Idempotent: the guard is `open && edgeRemoved && mask != 0`, and
     * [CollisionMap.removeWall] is counted, so re-asserting an edge that is
     * already absent cannot drive the count negative.
     */
    fun reassertOpenDoors(squareId: Int, plane: Int) {
        val baseX = (squareId and 0x7f) * CollisionMap.SIDE
        val baseZ = (squareId shr 7) * CollisionMap.SIDE
        var reasserted = 0
        doors.values.forEach { door ->
            if (door.plane != plane) return@forEach
            if (door.x !in baseX until baseX + CollisionMap.SIDE) return@forEach
            if (door.z !in baseZ until baseZ + CollisionMap.SIDE) return@forEach
            if (door.open && door.edgeRemoved && door.edgeMask != 0) {
                CollisionMap.removeWall(door.x, door.z, door.plane, door.edgeMask)
                reasserted++
            }
        }
        if (reasserted > 0) {
            logger.info {
                "doors: re-asserted $reasserted open door edge(s) after square $squareId plane $plane " +
                    "was applied - without this they would have become permanently impassable"
            }
        }
    }

    fun install(): Pair<Int, Int> {
        val opened = ContentRegistry.onLocAction(OPEN, ::onOpen)
        val closed = ContentRegistry.onLocAction(CLOSE, ::onClose)
        // Survive LocClipping's LRU: see reassertOpenDoors.
        if (LocClipping.afterApply.none { it == ::reassertOpenDoors })
            LocClipping.afterApply += ::reassertOpenDoors
        logger.info { "doors: bound Open across $opened locs, Close across $closed locs" }
        return opened to closed
    }

    // ---- the map ---------------------------------------------------------

    /** A placement of an openable loc, in absolute world tiles. */
    data class Placement(
        val locId: Int, val name: String?, val x: Int, val z: Int, val plane: Int,
        val type: Int, val rot: Int
    )

    /**
     * Where openable locs actually sit. `square_id` packs the map square: low 7
     * bits are the x square, the rest the z square, same as [CollisionMap].
     *
     * The `type` filter is what turns "openable" into "door": passing
     * [WALL_TYPES] excludes the chests and cupboards.
     */
    fun placements(limit: Int, wallTypesOnly: Boolean = true): List<Placement> {
        val typeFilter = if (wallTypesOnly) "AND ml.type IN (${WALL_TYPES.joinToString(",")})" else ""
        val sql = "SELECT ml.square_id, ml.plane, ml.x, ml.y, ml.loc_id, ml.type, ml.rot, l.name " +
            "FROM map_loc ml JOIN locs l ON l.id = ml.loc_id " +
            "WHERE l.actions_0 = 'Open' $typeFilter LIMIT $limit"
        return RsDatabase.queryAll(sql) { rs ->
            val sid = rs.getInt("square_id")
            Placement(
                locId = rs.getInt("loc_id"),
                name = rs.getString("name"),
                x = (sid and 0x7f) * CollisionMap.SIDE + rs.getInt("x"),
                z = (sid shr 7) * CollisionMap.SIDE + rs.getInt("y"),
                plane = rs.getInt("plane"),
                type = rs.getInt("type"),
                rot = rs.getInt("rot")
            )
        }
    }

    /** How many placements exist for locs declaring "Open" in `actions_0`, split by `map_loc.type`. */
    fun placementsByType(): Map<Int, Int> {
        val sql = "SELECT ml.type AS t, COUNT(*) AS n FROM map_loc ml JOIN locs l ON l.id = ml.loc_id " +
            "WHERE l.actions_0 = 'Open' GROUP BY ml.type"
        return RsDatabase.queryAll(sql) { it.getInt("t") to it.getInt("n") }.toMap()
    }
}
