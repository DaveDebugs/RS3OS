package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.resources.config.npcs.NpcFilesystemCodec
import java.sql.DriverManager

/**
 * Compares what NpcFilesystemCodec pulls out of the cache against a reference copy of rs3.sqlite.
 *
 *     -Dopennxt.reference.db=<path to a known-good rs3.sqlite>
 */
object NpcDecodeVerify {
    @JvmStatic
    fun main(args: Array<String>) {
        val ref = System.getProperty("opennxt.reference.db") ?: "data/rs3.sqlite"
        Class.forName("org.sqlite.JDBC")
        val db = DriverManager.getConnection("jdbc:sqlite:$ref")
        val fs = SqliteFilesystem(Constants.CACHE_PATH)

        val cols = listOf("name", "boundSize", "combat", "movementType", "animation_group",
            "drawMapDot", "actions_0", "actions_1", "actions_2", "attackCursor", "movementCapabilities")
        val rows = HashMap<Int, Map<String, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT game_id, ${cols.joinToString(", ") { "\"$it\"" }} FROM npcs").use { rs ->
                while (rs.next()) {
                    val m = HashMap<String, String?>()
                    for (c in cols) m[c] = rs.getString(c)
                    rows[rs.getInt("game_id")] = m
                }
            }
        }
        val attr = HashMap<Int, HashMap<String, String>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, field, value FROM npcs_attr").use { rs ->
                while (rs.next()) attr.getOrPut(rs.getInt("id")) { HashMap() }[rs.getString("field")] = rs.getString("value")
            }
        }
        println("reference: ${rows.size} npcs, ${attr.size} with extra fields")

        var compared = 0
        val bad = LinkedHashMap<String, MutableList<Int>>()
        fun mismatch(field: String, id: Int) = bad.getOrPut(field) { ArrayList() }.add(id)

        for ((id, want) in rows) {
            val got = NpcFilesystemCodec.load(fs, id)
            if (got == null) {
                mismatch("record absent from cache", id)
                continue
            }
            compared++
            if ((want["name"] ?: "") != (got.name ?: "")) mismatch("name", id)
            if (want["boundSize"]?.toInt() != got.boundSize) mismatch("boundSize", id)
            if (want["combat"]?.toInt() != got.combat) mismatch("combat", id)
            if (want["animation_group"]?.toInt() != got.animationGroup) mismatch("animation_group", id)
            if (want["attackCursor"]?.toInt() != got.attackCursor) mismatch("attackCursor", id)
            if (want["movementCapabilities"]?.toInt() != got.movementCapabilities) mismatch("movementCapabilities", id)
            if ((want["drawMapDot"] != null) != (got.drawMapDot == false)) mismatch("drawMapDot", id)
            for (slot in 0..2) {
                val w = want["actions_$slot"]
                if ((w ?: "") != (got.actions[slot] ?: "")) mismatch("actions_$slot", id)
            }
            val a = attr[id]
            for (slot in 3..4) {
                val w = a?.get("actions_$slot")?.trim('"')
                if ((w ?: "") != (got.actions[slot] ?: "")) mismatch("actions_$slot", id)
            }
            for (slot in 0..4) {
                val w = a?.get("members_actions_$slot")?.trim('"')
                if ((w ?: "") != (got.membersActions[slot] ?: "")) mismatch("members_actions_$slot", id)
            }
            val wantModels = a?.get("models")
            val gotModels = got.models?.joinToString(",", "[", "]")
            if ((wantModels ?: "") != (gotModels ?: "")) mismatch("models", id)
            val wantHeads = a?.get("headModels")
            val gotHeads = got.headModels?.joinToString(",", "[", "]")
            if ((wantHeads ?: "") != (gotHeads ?: "")) mismatch("headModels", id)
            val wantParams = a?.get("extra")
            val n = if (wantParams == null) 0 else Regex("\"prop\":").findAll(wantParams).count()
            if (n != got.params.size) mismatch("params count", id)
        }

        println("compared $compared npcs across ${cols.size + 4} fields")
        if (bad.isEmpty()) {
            println("NpcDecodeVerify: every field matches the reference.")
        } else {
            bad.entries.sortedByDescending { it.value.size }.forEach { (k, v) ->
                println("  ${v.size} mismatched $k   e.g. ${v.take(6)}")
            }
            println("NpcDecodeVerify: ${bad.values.sumOf { it.size }} mismatches.")
        }
    }
}
