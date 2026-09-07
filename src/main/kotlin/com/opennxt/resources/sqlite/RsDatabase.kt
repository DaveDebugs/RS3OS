package com.opennxt.resources.sqlite

import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files
import java.sql.Connection
import org.sqlite.SQLiteConfig
import java.sql.ResultSet

/**
 * Read-only accessor to `data/rs3.sqlite`, the decoded-definition database.
 *
 * OpenNXT's own config codecs decode records straight out of the cache, which is
 * the right design when a decoder for that record type exists. It has three:
 * enum, param and struct. Items, NPCs, locs and varbits have none, and writing
 * four more opcode decoders in Kotlin would duplicate work already done and
 * validated elsewhere - the Python side decodes all four at 100% exact-consume
 * against this cache, and `buildall.sh` regenerates the database for any build.
 *
 * `FilesystemResourceCodec<T>` is handed a `Filesystem`, but nothing obliges a
 * codec to read from it. The codecs in this package ignore it and read here.
 *
 * The database is optional: if it is absent the codecs return null/empty rather
 * than throwing, so a server with no rs3.sqlite behaves exactly as it did before
 * this package existed.
 */
object RsDatabase {
    private val logger = KotlinLogging.logger { }

    val path = Constants.DATA_PATH.resolve("rs3.sqlite")

    val available: Boolean by lazy {
        val exists = Files.exists(path)
        if (!exists) logger.warn { "No definition database at $path - item/npc/loc/varbit lookups will return null" }
        exists
    }

    private val connection: Connection? by lazy {
        if (!available) null
        else {
            // sqlite-jdbc refuses setReadOnly() after connect - the flag has to be
            // set on the config used to open it. Read-only matters here: this
            // database is a build artefact of buildall.sh, and a stray write would
            // put the server's view out of step with the tool that produced it.
            val config = SQLiteConfig()
            config.setReadOnly(true)
            config.createConnection("jdbc:sqlite:$path").also {
                logger.info { "Opened definition database $path (read-only)" }
            }
        }
    }

    /** Whether [table] exists. Lets a codec degrade instead of throwing on an older database. */
    fun hasTable(table: String): Boolean {
        val c = connection ?: return false
        c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
            st.setString(1, table)
            st.executeQuery().use { return it.next() }
        }
    }

    /**
     * The column names of [table], lower-cased, or an empty set if the table (or
     * the database) is absent.
     */
    fun columnsOf(table: String): Set<String> {
        val c = connection ?: return emptySet()
        if (!hasTable(table)) return emptySet()
        val out = LinkedHashSet<String>()
        c.prepareStatement("PRAGMA table_info(\"$table\")").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString("name").lowercase()) }
        }
        return out
    }

    fun <T> queryOne(sql: String, id: Int, map: (ResultSet) -> T): T? {
        val c = connection ?: return null
        c.prepareStatement(sql).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> return if (rs.next()) map(rs) else null }
        }
    }

    fun <T> queryAll(sql: String, map: (ResultSet) -> T): List<T> {
        val c = connection ?: return emptyList()
        val out = ArrayList<T>()
        c.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> while (rs.next()) out.add(map(rs)) }
        }
        return out
    }

    /** [queryAll] with a single bound int - one row per result, not just the first. */
    fun <T> queryAll(sql: String, id: Int, map: (ResultSet) -> T): List<T> {
        val c = connection ?: return emptyList()
        val out = ArrayList<T>()
        c.prepareStatement(sql).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> while (rs.next()) out.add(map(rs)) }
        }
        return out
    }

    fun maxId(table: String): Int {
        val c = connection ?: return 0
        if (!hasTable(table)) return 0
        c.prepareStatement("SELECT MAX(id) FROM \"$table\"").use { st ->
            st.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** `getInt` returns 0 for SQL NULL, which is indistinguishable from a real 0. */
    fun ResultSet.intOrNull(column: String): Int? {
        val v = getInt(column)
        return if (wasNull()) null else v
    }
}
