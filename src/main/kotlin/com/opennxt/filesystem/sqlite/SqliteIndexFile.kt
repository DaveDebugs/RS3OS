package com.opennxt.filesystem.sqlite

import com.opennxt.filesystem.ReferenceTable
import java.io.Closeable
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Read-write accessor to SQLite files.
 *
 * EVERY accessor below is `@Synchronized`, and that is not decoration.
 *
 * This class holds ONE JDBC connection and five long-lived PreparedStatement
 * fields, and three different threads reach them at runtime:
 *
 *  - `js5-thread`, via Js5Session.loadFileData -> readReferenceTable -> getRawTable
 *  - the Netty HTTP event loop, via Js5MsEndpoint.handle -> filesystem.read(40, g)
 *  - the tick thread, via the *FilesystemCodec.getReferenceTable(2/17/22) lookups
 *
 * `getRawTable`, `hasReferenceTable`, `getMaxArchive` and `exists` each execute
 * a SHARED statement object with no lock. JDBC closes a statement's previous
 * ResultSet when that statement is re-executed, so two threads on the same index
 * race for one of two failures: `SQLException: ResultSet closed`, or - worse,
 * because nothing would ever report it - one thread quietly reading the other's
 * row. `getRaw`/`putRaw` build a fresh statement per call but still share the
 * connection, which JDBC does not require to be thread-safe either.
 *
 * Not reproduced under load, so this is a correctness fix rather than a fix for
 * an observed failure: the window needs two threads on the SAME index (each
 * index is its own SqliteIndexFile with its own connection) and xerial's driver
 * synchronises a good deal internally. Serialising one connection is what the
 * JDBC contract asks for regardless, and the contention added is bounded by how
 * fast SQLite answers a primary-key lookup.
 */
class SqliteIndexFile(val path: Path) : Closeable, AutoCloseable {
    val table: ReferenceTable? = null

    val connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.prepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `cache`(
              `KEY` INTEGER PRIMARY KEY,
              `DATA` BLOB,
              `VERSION` INTEGER,
              `CRC` INTEGER
            );
        """.trimIndent()
        ).use { stmt -> stmt.executeUpdate() }

        connection.prepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `cache_index`(
              `KEY` INTEGER PRIMARY KEY,
              `DATA` BLOB,
              `VERSION` INTEGER,
              `CRC` INTEGER
            );
        """.trimIndent()
        ).use { stmt -> stmt.executeUpdate() }
    }

    val archiveExistsStmt = connection.prepareStatement("SELECT 1 FROM `cache` WHERE `KEY` = ?;")
    val getMaxArchiveStmt = connection.prepareStatement("SELECT MAX(`KEY`) FROM `cache`;")
    val getArchiveDataStmt = connection.prepareStatement("SELECT `DATA` FROM `cache` WHERE `KEY` = ?;")
    val getReferenceDataStmt = connection.prepareStatement("SELECT `DATA` FROM `cache_index` WHERE `KEY` = 1;")
    val putArchiveDataStmt = connection.prepareStatement(
        """
            INSERT INTO `cache`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(?, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = ?;
    """.trimIndent()
    )
    val putReferenceDataStmt = connection.prepareStatement(
        """
            INSERT INTO `cache_index`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(1, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = 1;
    """.trimIndent()
    )

    @Synchronized
    fun putRawTable(data: ByteArray, version: Int, crc: Int): Int {
        val stmt = putReferenceDataStmt
        stmt.clearParameters()
        stmt.setBytes(1, data)
        stmt.setInt(2, version)
        stmt.setInt(3, crc)
        stmt.setBytes(4, data)
        stmt.setInt(5, version)
        stmt.setInt(6, crc)
        return stmt.executeUpdate()
    }

    @Synchronized
    fun putRaw(archive: Int, data: ByteArray, version: Int, crc: Int): Int {
//        val stmt = putArchiveDataStmt
        connection.prepareStatement(
            """
            INSERT INTO `cache`(`KEY`, `DATA`, `VERSION`, `CRC`)
              VALUES(?, ?, ?, ?)
              ON CONFLICT(`KEY`) DO UPDATE SET
                `DATA` = ?, `VERSION` = ?, `CRC` = ?
              WHERE `KEY` = ?;
            """
        ).use { stmt ->
            stmt.clearParameters()
            stmt.setInt(1, archive)
            stmt.setBytes(2, data)
            stmt.setInt(3, version)
            stmt.setInt(4, crc)
            stmt.setBytes(5, data)
            stmt.setInt(6, version)
            stmt.setInt(7, crc)
            stmt.setInt(8, archive)
            return stmt.executeUpdate()
        }
    }

    @Synchronized
    fun hasReferenceTable(): Boolean {
        getReferenceDataStmt.executeQuery().use { return it.next() }
    }

    @Synchronized
    override fun close() {
        getMaxArchiveStmt.close()
        getArchiveDataStmt.close()
        getReferenceDataStmt.close()
        putArchiveDataStmt.close()
        putReferenceDataStmt.close()
    }

    @Synchronized
    fun getMaxArchive(): Int {
        getMaxArchiveStmt.executeQuery().use {
            if (!it.next()) return 0
            return it.getInt(1)
        }
    }

    @Synchronized
    fun exists(id: Int): Boolean {
        val stmt = archiveExistsStmt
        stmt.clearParameters()
        stmt.setInt(1, id)
        stmt.executeQuery().use { return it.next() }
    }

    @Synchronized
    fun getRaw(id: Int): ByteArray? {
//        val stmt = getArchiveDataStmt
        connection.prepareStatement("SELECT `DATA` FROM `cache` WHERE `KEY` = ?;").use { stmt ->
            stmt.clearParameters()
            stmt.setInt(1, id)
            stmt.executeQuery().use {
                if (!it.next()) return null
                return it.getBytes("DATA")
            }
        }
    }

    @Synchronized
    fun getRawTable(): ByteArray? {
        getReferenceDataStmt.executeQuery().use {
            if (!it.next()) return null
            return it.getBytes("DATA")
        }
    }
}