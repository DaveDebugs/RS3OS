package com.opennxt.model.world

import com.opennxt.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * NPC spawn layer, read out of the decoded RS3 cache database (data/rs3.sqlite).
 */
object NpcSpawnData {

    /** Overridable for tests: -Dopennxt.cachedb=/path/to/rs3.sqlite */
    val dbPath: Path
        get() = System.getProperty("opennxt.cachedb")
            ?.let { Path.of(it) }
            ?: Constants.DATA_PATH.resolve("rs3.sqlite")

    /**
     * One npc spawn: an npc game id on an absolute world tile.
     *
     * Every field the cache actually carries is here. The fields it does not
     * carry are absent rather than defaulted, so nothing invented can leak into
     * a server: there is no respawnTicks, no wanderRadius and no direction on
     * this class because there are no such bytes in the file.
     */
    data class Spawn(
        /** npcs.game_id. NOT npcs.id. */
        val npcId: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        /** index 5 group id: (x/64) | (y/64) << 7 */
        val squareId: Int,
        /** npcs.name for [npcId] in game-id space, or null if unnamed/unknown. */
        val name: String?
    ) {
        val squareX: Int get() = squareId and 0x7f
        val squareY: Int get() = squareId shr 7
        val localX: Int get() = x and 0x3f
        val localY: Int get() = y and 0x3f
        override fun toString() = "${name ?: "npc$npcId"}($npcId) @ ($x,$y,$plane)"
    }

    /**
     * Metadata the cache does NOT ship for a spawn. Present as an explicit,
     * queryable list so a caller cannot mistake absence for "not decoded yet".
     */
    val ABSENT_SPAWN_FIELDS = listOf(
        "respawnTicks", "wanderRadius", "facingDirection",
        "spawnGroup", "membersOnly", "worldFlags"
    )

    /** Squares in the index 5 manifest. Measured from the reference table. */
    const val MANIFEST_SQUARES = 8515

    /** Squares whose manifest child list contains file 2. Measured. */
    const val SQUARES_WITH_FILE2 = 45

    private var loaded = false
    private val all = ArrayList<Spawn>()
    private val byNpc = HashMap<Int, MutableList<Spawn>>()
    private val bySquare = HashMap<Int, MutableList<Spawn>>()
    private val npcNames = HashMap<Int, String>()

    /**
     * Re-pack a raw index-5-file-2 u16 key from coordinates: plane<<14 | x<<7 | y.
     *
     * [rebuildKey] is retired. It existed because `map_keyed` used to be written
     * by a decoder with the WRONG packing (`plane<<14 | flag<<12 | y<<6 | x`),
     * so this class had to reconstruct the raw word and take it apart correctly.
     * The migration that retired it happened as one change: mapfile2.py and
     * exportlights.py now write the corrected columns AND the raw `key` per row,
     * rs3.sqlite was rebuilt, and [load] now verifies `key == packKey(...)` on
     * every row - so a stale table (old packing, or missing the key column)
     * throws at load instead of silently corrupting coordinates.
     */
    fun packKey(plane: Int, localX: Int, localY: Int): Int =
        (plane shl 14) or (localX shl 7) or localY

    /** The corrected unpack: plane<<14 | x<<7 | y. */
    fun unpackPlane(key: Int): Int = (key shr 14) and 0x3
    fun unpackX(key: Int): Int = (key shr 7) and 0x7f
    fun unpackY(key: Int): Int = key and 0x7f

    @Synchronized
    fun load() {
        if (loaded) return
        val p = dbPath
        require(Files.exists(p)) { "cache database not found: ${p.toAbsolutePath()}" }
        DriverManager.getConnection("jdbc:sqlite:${p.toAbsolutePath()}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery("SELECT game_id, name FROM npcs WHERE game_id IS NOT NULL AND name IS NOT NULL")
                    .use { rs -> while (rs.next()) npcNames.putIfAbsent(rs.getInt(1), rs.getString(2)) }

                st.executeQuery(
                    "SELECT square_id, plane, local_x, local_y, value, key FROM map_keyed"
                ).use { rs ->
                    while (rs.next()) {
                        val square = rs.getInt(1)
                        val plane = rs.getInt(2)
                        val localX = rs.getInt(3)
                        val localY = rs.getInt(4)
                        val rawKey = rs.getInt(6)
                        check(rawKey == packKey(plane, localX, localY)) {
                            "map_keyed row (square=$square) stores key=$rawKey but its columns " +
                                "(plane=$plane, local_x=$localX, local_y=$localY) re-pack to " +
                                "${packKey(plane, localX, localY)}. Either the table predates the " +
                                "corrected packing (rebuild rs3.sqlite with the fixed mapfile2.py " +
                                "and exportlights.py) or the packing changed again."
                        }
                        val npcId = rs.getInt(5)
                        val sp = Spawn(
                            npcId = npcId,
                            x = (square and 0x7f) * 64 + localX,
                            y = (square shr 7) * 64 + localY,
                            plane = plane,
                            squareId = square,
                            name = npcNames[npcId]
                        )
                        all.add(sp)
                        byNpc.getOrPut(npcId) { ArrayList() }.add(sp)
                        bySquare.getOrPut(square) { ArrayList() }.add(sp)
                    }
                }
            }
        }
        loaded = true
    }

    fun spawns(): List<Spawn> { load(); return all }

    /** Keyed on npcs.game_id. Passing an archive id gets you the wrong npc or none. */
    fun spawnsOfNpc(gameId: Int): List<Spawn> { load(); return byNpc[gameId] ?: emptyList() }

    fun spawnsInSquare(squareId: Int): List<Spawn> { load(); return bySquare[squareId] ?: emptyList() }

    /** Case-insensitive exact name match, in game-id space. */
    fun spawnsNamed(name: String): List<Spawn> =
        spawns().filter { it.name.equals(name, ignoreCase = true) }

    fun spawnsInBox(x0: Int, y0: Int, x1: Int, y1: Int, plane: Int? = null): List<Spawn> =
        spawns().filter {
            it.x in x0..x1 && it.y in y0..y1 && (plane == null || it.plane == plane)
        }

    /** Square ids that carry at least one spawn. */
    fun coveredSquares(): Set<Int> { load(); return bySquare.keys.toSet() }

    /** True if the cache has any spawn data for the square containing (x, y). */
    fun isCovered(x: Int, y: Int): Boolean =
        coveredSquares().contains((x / 64) or ((y / 64) shl 7))

    /**
     * Spawn metadata is never available: the record is 4 bytes and both are
     * spent. Always null, by construction, for every id. See [ABSENT_SPAWN_FIELDS].
     */
    fun spawnMetadataFor(@Suppress("UNUSED_PARAMETER") gameId: Int): Map<String, Int>? = null

    /** Fraction of the manifest's map squares that carry any spawn data. */
    fun coverageFraction(): Double = coveredSquares().size.toDouble() / MANIFEST_SQUARES
}
