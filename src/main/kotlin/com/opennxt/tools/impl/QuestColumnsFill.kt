package com.opennxt.tools.impl

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.tools.Tool
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import kotlin.system.exitProcess
import org.sqlite.SQLiteConfig

/**
 * Adds `quest_item_sprite` and `quest_list_name` to `quests` in `data/rs3.sqlite`,
 * in place, from cache index 2 / archive 35.
 */
class QuestColumnsFill : Tool(
    "quest-columns-fill",
    "Adds quest_item_sprite / quest_list_name to quests in rs3.sqlite from cache index 2 archive 35"
) {
    private val dryRun by option("--dry-run", help = "Decode, diff and count; write nothing").flag()

    override fun runTool() {
        exitProcess(fill(dryRun))
    }

    private fun fill(dryRun: Boolean): Int {
        val quests = QuestExtract.readAll(filesystem)
        if (quests == null) {
            println("FAIL: the cache has no quest archive; nothing was measured")
            return 2
        }
        val cacheIds = quests.map { it.id }.toSet()
        println("cache   : ${quests.size} quest files, ${quests.count { it.itemSprite >= 0 }} with an item sprite, " +
                "${quests.count { it.listName.isNotEmpty() }} with a list name")

        val dbPath = RsDatabase.path.toAbsolutePath()
        if (!Files.exists(dbPath)) {
            println("FAIL: no database at $dbPath")
            return 2
        }
        println("database: $dbPath${if (dryRun) "  (DRY RUN - opened read-only, nothing written)" else ""}")

        // sqlite-jdbc takes read-only from the config used to open, not from the
        // URL or from setReadOnly() after the fact - same as RsDatabase.
        val url = "jdbc:sqlite:$dbPath"
        val conn = if (dryRun) SQLiteConfig().apply { setReadOnly(true) }.createConnection(url)
                   else DriverManager.getConnection(url)
        conn.use { c ->
            val before = columns(c)
            val addSprite = SPRITE !in before
            val addList = LIST !in before
            println("columns before: ${before.size} (${if (addSprite) "no " else ""}$SPRITE, ${if (addList) "no " else ""}$LIST)")

            val tableIds = HashSet<Int>()
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM quests").use { rs -> while (rs.next()) tableIds.add(rs.getInt(1)) }
            }
            val onlyCache = (cacheIds - tableIds).sorted()
            val onlyTable = (tableIds - cacheIds).sorted()
            println("table   : ${tableIds.size} rows")
            println("ids in cache but not in table: ${onlyCache.size} ${onlyCache.take(10)}")
            println("ids in table but not in cache: ${onlyTable.size} ${onlyTable.take(10)}")

            if (dryRun) {
                // A freshly added column is NULL in every row, so a quest that
                // carries neither opcode is already at its final value and the
 // NULL-safe UPDATE below will not touch it.:
                // 371 of 533 changed on the first real run, 162 carried neither.
                val pending = if (addSprite || addList)
                    quests.count { it.id in tableIds && (it.itemSprite >= 0 || it.listName.isNotEmpty()) }
                else quests.count { it.id in tableIds && differs(c, it) }
                println("rows that a real run would change: $pending")
                return 0
            }

            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    if (addSprite) st.execute("ALTER TABLE quests ADD COLUMN $SPRITE INTEGER")
                    if (addList) st.execute("ALTER TABLE quests ADD COLUMN $LIST TEXT")
                }
                println("DDL applied   : ${listOfNotNull(if (addSprite) "ADD COLUMN $SPRITE INTEGER" else null,
                                                          if (addList) "ADD COLUMN $LIST TEXT" else null)
                                             .ifEmpty { listOf("(none - both columns already present)") }
                                             .joinToString("; ")}")

                var matched = 0
                var changed = 0
                c.prepareStatement(
                    "UPDATE quests SET $SPRITE = ?, $LIST = ? WHERE id = ? AND ($SPRITE IS NOT ? OR $LIST IS NOT ?)"
                ).use { up ->
                    for (q in quests) {
                        if (q.id !in tableIds) continue
                        matched++
                        bindSprite(up, 1, q); bindList(up, 2, q)
                        up.setInt(3, q.id)
                        bindSprite(up, 4, q); bindList(up, 5, q)
                        changed += up.executeUpdate()
                    }
                }
                c.commit()
                println("rows matched  : $matched")
                println("rows changed  : $changed")
            } catch (e: Exception) {
                c.rollback()
                println("FAIL: rolled back - ${e.message}")
                return 1
            }
            c.autoCommit = true

            // Read back what was written, and compare every row against the decoder.
            val after = columns(c)
            println("columns after : ${after.size} (${after.joinToString(", ")})")
            val nonNullSprites = scalar(c, "SELECT COUNT(*) FROM quests WHERE $SPRITE IS NOT NULL")
            val nonNullNames = scalar(c, "SELECT COUNT(*) FROM quests WHERE $LIST IS NOT NULL")
            println("non-null $SPRITE : $nonNullSprites")
            println("non-null $LIST   : $nonNullNames")
            var disagree = 0
            for (q in quests) if (q.id in tableIds && differs(c, q)) disagree++
            println("rows whose readback disagrees with the decoder: $disagree")
            for (id in intArrayOf(66, 135)) {
                c.prepareStatement("SELECT name, $SPRITE, $LIST FROM quests WHERE id = ?").use { st ->
                    st.setInt(1, id)
                    st.executeQuery().use { rs ->
                        if (rs.next()) {
                            val sprite = rs.getInt(2).let { if (rs.wasNull()) null else it }
                            println("  quest $id ${rs.getString(1)}: $SPRITE=$sprite $LIST=${rs.getString(3)}")
                        }
                    }
                }
            }

            val integrity = ArrayList<String>()
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs -> while (rs.next()) integrity.add(rs.getString(1)) }
            }
            println("PRAGMA integrity_check: ${integrity.joinToString(" | ")}")

            val ok = disagree == 0 && integrity == listOf("ok")
            println(if (ok) "OK" else "FAIL")
            return if (ok) 0 else 1
        }
    }

    private fun columns(c: Connection): List<String> {
        val out = ArrayList<String>()
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(quests)").use { rs -> while (rs.next()) out.add(rs.getString("name").lowercase()) }
        }
        return out
    }

    private fun scalar(c: Connection, sql: String): Int =
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else -1 } }

    /** True when the stored pair for [q] is not the decoded pair, NULL-safely. */
    private fun differs(c: Connection, q: QuestExtract.Quest): Boolean =
        c.prepareStatement("SELECT 1 FROM quests WHERE id = ? AND ($SPRITE IS NOT ? OR $LIST IS NOT ?)").use { st ->
            st.setInt(1, q.id)
            bindSprite(st, 2, q); bindList(st, 3, q)
            st.executeQuery().use { it.next() }
        }

    private fun bindSprite(st: java.sql.PreparedStatement, i: Int, q: QuestExtract.Quest) {
        if (q.itemSprite >= 0) st.setInt(i, q.itemSprite) else st.setNull(i, Types.INTEGER)
    }

    private fun bindList(st: java.sql.PreparedStatement, i: Int, q: QuestExtract.Quest) {
        if (q.listName.isNotEmpty()) st.setString(i, q.listName) else st.setNull(i, Types.VARCHAR)
    }

    companion object {
        const val SPRITE = "quest_item_sprite"
        const val LIST = "quest_list_name"
    }
}

/**
 * Static entry point, same shape as [ProtCoverageCheckMain]: a companion `main`
 * collides with Clikt's inherited instance `main`, so the static one lives in a
 * sibling object. `run-tool quest-columns-fill` reaches the same command.
 */
object QuestColumnsFillMain {
    @JvmStatic
    fun main(args: Array<String>) {
        QuestColumnsFill().main(args)
    }
}
