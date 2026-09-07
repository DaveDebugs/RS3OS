package com.opennxt.tools.impl

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.tools.Tool
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Fills the world tables in `data/rs3.sqlite` from the game cache.
 *
 *     run-tool map-builder
 *     run-tool map-builder --database data/rs3.sqlite
 *
 * `db-builder` decodes what things ARE -- items, NPCs, objects, their names and
 * properties. This tool decodes where they STAND. Without it the server has
 * definitions and an empty world: no collision, so the player walks through
 * walls, and no object placements, so no doors, banks, trees or ladders exist
 * anywhere to click on.
 *
 * Four tables, all from index 5 (maps), one archive per map square:
 *
 *   `map_square`   file 3, the terrain walk: height, overlay, underlay, shape
 *                  and the settings byte, five parallel arrays per square.
 *   `map_blocked`  DERIVED from `map_square.flags` bit 0x1, one 512-byte bitmap
 *                  per square per plane. This is what the collision map reads.
 *   `map_loc`      files 0 (land) and 1 (water): every object placement, with
 *                  its position, type, rotation and transform.
 *   `map_keyed`    file 2, a flat run of 4-byte `key u16, value u16` records.
 *                  The server reads it as the NPC spawn table.
 *
 * ABOUT `map_keyed`. The key unpacks as `plane << 14 | x << 7 | y` and ascends
 * within every square. The value is an NPC id: 76% of the 1,388 records resolve
 * to a named NPC, and the names land where they should -- sheep and Fred the
 * Farmer on the Lumbridge farms, Zaff and Thessalia in Varrock, cave goblins in
 * Dorgesh-Kaan.
 *
 * Its limit is COVERAGE, not accuracy. Only 33 map squares carry file 2, so the
 * towns and landmarks it covers are populated and the countryside between them
 * is empty. `-Dopennxt.experiment.npcs.spawns=false` turns the layer off.
 *
 * Not filled: `map_env`, which is lighting the server does not use.
 *
 * SAFETY. The four tables are rewritten together and nothing else is touched.
 * They must be rewritten together: `map_blocked` is derived from the same bytes
 * as `map_square`, so leaving one stale would put collision and terrain on two
 * different caches. An existing non-empty set is refused unless `--force`.
 */
class MapBuilder : Tool("map-builder", "Fills the map tables in data/rs3.sqlite from the game cache") {

    private val database by option(help = "The database to fill (default: data/rs3.sqlite)")
        .default(Constants.DATA_PATH.resolve("rs3.sqlite").toString())

    private val force by option(help = "Rewrite the map tables even when they already hold rows")
        .flag(default = false)

    /** 4 planes x 64 x 64 tiles, the fixed shape of a map square. */
    private companion object {
        const val PLANES = 4
        const val SIDE = 64
        /** One plane's collision bitmap: 64 x 64 bits. */
        const val BITMAP_BYTES = SIDE * SIDE / 8
        /** The RS region grid. Squares outside it do not exist. */
        const val GRID_I = 128
        const val GRID_J = 256
    }

    override fun runTool() {
        val cache = SqliteFilesystem(Constants.CACHE_PATH)
        val table = cache.getReferenceTable(Index.MAPS)
            ?: throw IllegalStateException(
                "the cache at ${Constants.CACHE_PATH} has no reference table for index ${Index.MAPS}.\n" +
                    "  Download one first: run-tool cache-downloader"
            )

        DriverManager.getConnection("jdbc:sqlite:$database").use { db ->
            db.autoCommit = false
            createTables(db)
            guardExisting(db)

            val squares = ArrayList<Pair<Int, MapTileExtract.Square>>()
            var locRows = 0L
            var keyedRows = 0L
            var noTerrain = 0
            var shortWalk = 0
            var locFailed = 0
            var desynced = 0

            db.prepareStatement(
                "INSERT INTO map_keyed (square_id, plane, tile_x, tile_z, local_x, local_y, value, key) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
            ).use { insertKeyed ->
            db.prepareStatement(
                "INSERT INTO map_loc (square_id, plane, x, y, loc_id, type, rot, xform) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
            ).use { insertLoc ->
                for (i in 0 until GRID_I) {
                    for (j in 0 until GRID_J) {
                        // archive id == square id == j * 128 + i.
                        val sid = j * GRID_I + i
                        val archive = try { table.loadArchive(sid) } catch (e: Exception) { null } ?: continue

                        val terrain = archive.files[3]?.data
                        if (terrain == null) {
                            noTerrain++
                        } else {
                            val square = MapTileExtract.decodeSquare(terrain)
                            if (square == null) {
                                // A square that ran short mid-walk is dropped whole: the tile
                                // format has no lengths, so everything after a bad byte is
                                // suspect and half a square is worse than none.
                                shortWalk++
                            } else {
                                squares.add(sid to square)
                            }
                        }

                        // File 2: the keyed records. Absent from all but a
                        // handful of squares, so there is no "missing" counter.
                        archive.files[2]?.data?.let { keyed ->
                            keyedRows += decodeKeyed(sid, keyed) { row ->
                                insertKeyed.setInt(1, row.squareId)
                                insertKeyed.setInt(2, row.plane)
                                insertKeyed.setInt(3, row.tileX)
                                insertKeyed.setInt(4, row.tileZ)
                                insertKeyed.setInt(5, row.localX)
                                insertKeyed.setInt(6, row.localY)
                                insertKeyed.setInt(7, row.value)
                                insertKeyed.setInt(8, row.key)
                                insertKeyed.addBatch()
                            }
                        }

                        // File 0 is land, file 1 water. Only land is stored: the
                        // two use different conventions and mixing them into one
                        // table would make the rows mean two things.
                        val land = archive.files[0]?.data
                        if (land != null) {
                            try {
                                val reader = MapLocExtract.Reader(ByteBuffer.wrap(land))
                                locRows += MapLocExtract.decodeInto(reader, sid, 0) { row ->
                                    insertLoc.setInt(1, row.squareId)
                                    insertLoc.setInt(2, row.plane)
                                    insertLoc.setInt(3, row.x)
                                    insertLoc.setInt(4, row.y)
                                    insertLoc.setInt(5, row.locId)
                                    insertLoc.setInt(6, row.type)
                                    insertLoc.setInt(7, row.rot)
                                    insertLoc.setString(8, row.xform)
                                    insertLoc.addBatch()
                                }
                                // Bytes left over mean the stream desynced; a clean
                                // decode consumes the archive exactly.
                                if (reader.remaining() > 0) desynced++
                                insertLoc.executeBatch()
                            } catch (e: Exception) {
                                // One malformed square must not abandon the sweep.
                                locFailed++
                                logger.warn { "square $sid: placement decode failed - ${e.message}" }
                            }
                        }
                    }
                }
                insertLoc.executeBatch()
            }
                insertKeyed.executeBatch()
            }

            writeSquares(db, squares)
            db.commit()

            logger.info { "map_square  : ${squares.size} squares" }
            logger.info { "map_blocked : ${squares.size * PLANES} bitmaps" }
            logger.info { "map_loc     : $locRows placements" }
            logger.info { "map_keyed   : $keyedRows records (npc spawns; see this tool's notes)" }
            if (noTerrain > 0) logger.info { "no file 3   : $noTerrain archives" }
            if (shortWalk > 0) logger.warn { "ran short   : $shortWalk squares dropped" }
            if (locFailed > 0) logger.warn { "failed      : $locFailed squares" }
            if (desynced > 0) logger.warn { "desynced    : $desynced placement files left bytes unread" }

            if (squares.isEmpty()) {
                throw IllegalStateException(
                    "no map square decoded. The cache at ${Constants.CACHE_PATH} is empty or is not\n" +
                        "  a 949 cache; the map tables have been left as they were."
                )
            }
            logger.info { "Done. The server will read collision and placements from $database." }
        }
    }

    /** One record of index 5 file 2. */
    private class KeyedRow(
        val squareId: Int, val plane: Int, val tileX: Int, val tileZ: Int,
        val localX: Int, val localY: Int, val value: Int, val key: Int
    )

    /**
     * Decode index 5 file 2: a flat run of 4-byte records, no header and no
     * terminator, each `key u16` then `value u16`.
     *
     * The key packs as `plane << 14 | x << 7 | y`, which is the packing
     * `NpcSpawnData.packKey` re-checks every row against at load time -- so a
     * mistake here fails loudly at boot rather than quietly misplacing NPCs.
     */
    private fun decodeKeyed(sid: Int, data: ByteArray, emit: (KeyedRow) -> Unit): Long {
        if (data.size % 4 != 0) {
            logger.warn { "square $sid: file 2 is ${data.size} bytes, not a multiple of 4 - skipped" }
            return 0
        }
        val buf = ByteBuffer.wrap(data)
        var n = 0L
        while (buf.remaining() >= 4) {
            val key = buf.short.toInt() and 0xffff
            val value = buf.short.toInt() and 0xffff
            val plane = (key shr 14) and 0x3
            val localX = (key shr 7) and 0x7f
            val localY = key and 0x7f
            emit(
                KeyedRow(
                    squareId = sid, plane = plane,
                    tileX = (sid and 0x7f) * SIDE + localX,
                    tileZ = (sid shr 7) * SIDE + localY,
                    localX = localX, localY = localY, value = value, key = key
                )
            )
            n++
        }
        return n
    }

    /**
     * One plane's collision bitmap, rebuilt from the settings byte.
     *
     * Bit 0x1 of a tile's settings marks it blocked. The bit order is
     * `index = x * 64 + y`, LSB first within its byte.
     */
    private fun blockedBitmap(settings: ByteArray, plane: Int): ByteArray {
        val out = ByteArray(BITMAP_BYTES)
        val base = plane * SIDE
        for (x in 0 until SIDE) {
            val row = (base + x) * SIDE
            for (y in 0 until SIDE) {
                if ((settings[row + y].toInt() and 0x1) != 0) {
                    val n = x * SIDE + y
                    out[n shr 3] = (out[n shr 3].toInt() or (1 shl (n and 7))).toByte()
                }
            }
        }
        return out
    }

    /** Big-endian int16 array, the encoding the existing columns use. */
    private fun shortsToBlob(values: ShortArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (k in values.indices) {
            out[k * 2] = (values[k].toInt() shr 8).toByte()
            out[k * 2 + 1] = values[k].toInt().toByte()
        }
        return out
    }

    private fun writeSquares(db: Connection, squares: List<Pair<Int, MapTileExtract.Square>>) {
        db.prepareStatement(
            "INSERT INTO map_square (square_id, i, j, planes, heights, flags, underlay, overlay, shape) " +
                "VALUES (?,?,?,?,?,?,?,?,?)"
        ).use { st ->
            for ((sid, sq) in squares) {
                st.setInt(1, sid)
                st.setInt(2, sid % GRID_I)
                st.setInt(3, sid / GRID_I)
                st.setInt(4, PLANES)
                st.setBytes(5, shortsToBlob(sq.heights))
                st.setBytes(6, sq.settings)
                st.setBytes(7, shortsToBlob(sq.underlay))
                st.setBytes(8, shortsToBlob(sq.overlay))
                st.setBytes(9, sq.shape)
                st.addBatch()
            }
            st.executeBatch()
        }

        db.prepareStatement(
            "INSERT INTO map_blocked (square_id, plane, bitmap) VALUES (?,?,?)"
        ).use { st ->
            for ((sid, sq) in squares) {
                for (plane in 0 until PLANES) {
                    st.setInt(1, sid)
                    st.setInt(2, plane)
                    st.setBytes(3, blockedBitmap(sq.settings, plane))
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
    }

    private fun createTables(db: Connection) {
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_square (square_id INTEGER PRIMARY KEY, i INTEGER, " +
                    "j INTEGER, planes INTEGER, heights BLOB, flags BLOB, underlay BLOB, " +
                    "overlay BLOB, shape BLOB)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_blocked (square_id INTEGER, plane INTEGER, bitmap BLOB)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_loc (square_id INTEGER, plane INTEGER, x INTEGER, " +
                    "y INTEGER, loc_id INTEGER, type INTEGER, rot INTEGER, xform TEXT)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS map_keyed (square_id INTEGER, plane INTEGER, " +
                    "tile_x INTEGER, tile_z INTEGER, local_x INTEGER, local_y INTEGER, " +
                    "value INTEGER, key INTEGER)"
            )
        }
    }

    /**
     * Refuse to overwrite populated tables without `--force`, then clear all
     * three together so terrain, collision and placements always come from one
     * cache.
     */
    private fun guardExisting(db: Connection) {
        val counts = listOf("map_square", "map_blocked", "map_loc", "map_keyed").associateWith { t ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $t").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
        val populated = counts.filterValues { it > 0 }
        if (populated.isNotEmpty() && !force) {
            throw IllegalStateException(
                "$database already holds map data (" +
                    populated.entries.joinToString(", ") { "${it.key} ${it.value}" } + ").\n" +
                    "  This tool REPLACES all four tables. Pass --force if you mean to rebuild them."
            )
        }
        db.createStatement().use { st ->
            for (t in listOf("map_square", "map_blocked", "map_loc", "map_keyed")) st.executeUpdate("DELETE FROM $t")
        }
    }
}
