package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.resources.config.locs.LocFilesystemCodec
import java.sql.DriverManager

/** Compares LocFilesystemCodec against a reference rs3.sqlite. See -Dopennxt.reference.db. */
object LocDecodeVerify {
    @JvmStatic
    fun main(args: Array<String>) {
        val ref = System.getProperty("opennxt.reference.db") ?: "data/rs3.sqlite"
        Class.forName("org.sqlite.JDBC")
        val db = DriverManager.getConnection("jdbc:sqlite:$ref")
        val fs = SqliteFilesystem(Constants.CACHE_PATH)

        val cols = listOf("name", "width", "length", "blocks_movement", "walkable",
            "allows_lineofsight", "animation", "is_members", "actions_0")
        val rows = HashMap<Int, Map<String, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT game_id, ${cols.joinToString(", ") { "\"$it\"" }} FROM locs").use { rs ->
                while (rs.next()) {
                    val m = HashMap<String, String?>()
                    for (c in cols) m[c] = rs.getString(c)
                    rows[rs.getInt("game_id")] = m
                }
            }
        }
        val attr = HashMap<Int, HashMap<String, String>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, field, value FROM locs_attr WHERE field LIKE 'actions_%' OR field = 'extra'").use { rs ->
                while (rs.next()) attr.getOrPut(rs.getInt("id")) { HashMap() }[rs.getString("field")] = rs.getString("value")
            }
        }
        println("reference: ${rows.size} locs")

        var compared = 0
        val bad = LinkedHashMap<String, MutableList<Int>>()
        fun mismatch(field: String, id: Int) = bad.getOrPut(field) { ArrayList() }.add(id)

        for ((id, want) in rows) {
            val got = LocFilesystemCodec.load(fs, id)
            if (got == null) {
                mismatch("record absent from cache", id)
                continue
            }
            compared++
            if ((want["name"] ?: "") != (got.name ?: "")) mismatch("name", id)
            if (want["width"]?.toInt() != got.width) mismatch("width", id)
            if (want["length"]?.toInt() != got.length) mismatch("length", id)
            if (want["animation"]?.toInt() != got.animation) mismatch("animation", id)
            if ((want["actions_0"] ?: "") != (got.actions[0] ?: "")) mismatch("actions_0", id)
            if ((want["blocks_movement"] != null) != (got.blocksMovement == true)) mismatch("blocks_movement", id)
            if ((want["walkable"] != null) != (got.walkable == true)) mismatch("walkable", id)
            if ((want["allows_lineofsight"] != null) != (got.allowsLineOfSight == true)) mismatch("allows_lineofsight", id)
            if ((want["is_members"] != null) != (got.isMembers == true)) mismatch("is_members", id)
            val a = attr[id]
            for (slot in 1..4) {
                val w = a?.get("actions_$slot")?.trim('"')
                if ((w ?: "") != (got.actions[slot] ?: "")) mismatch("actions_$slot", id)
            }
            val wantParams = a?.get("extra")
            val n = if (wantParams == null) 0 else Regex("\"prop\":").findAll(wantParams).count()
            if (n != got.params.size) mismatch("params count", id)
        }

        println("compared $compared locs")
        if (bad.isEmpty()) {
            println("LocDecodeVerify: every field matches the reference.")
        } else {
            bad.entries.sortedByDescending { it.value.size }.forEach { (k, v) ->
                println("  ${v.size} mismatched $k   e.g. ${v.take(6)}")
            }
            println("LocDecodeVerify: ${bad.values.sumOf { it.size }} mismatches.")
        }
    }
}
