package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.resources.config.items.ItemFilesystemCodec
import java.sql.DriverManager

/** Compares ItemFilesystemCodec against a reference rs3.sqlite. See -Dopennxt.reference.db. */
object ItemDecodeVerify {
    @JvmStatic
    fun main(args: Array<String>) {
        val ref = System.getProperty("opennxt.reference.db") ?: "data/rs3.sqlite"
        Class.forName("org.sqlite.JDBC")
        val db = DriverManager.getConnection("jdbc:sqlite:$ref")
        val fs = SqliteFilesystem(Constants.CACHE_PATH)

        val cols = listOf("name", "members", "tradeable", "stackable_1", "equipSlotId", "equipId",
            "buy_limit", "category", "dummyItem",
            "widget_actions_0", "widget_actions_1", "widget_actions_2", "widget_actions_4")
        val rows = HashMap<Int, Map<String, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT game_id, ${cols.joinToString(", ") { "\"$it\"" }} FROM items").use { rs ->
                while (rs.next()) {
                    val m = HashMap<String, String?>()
                    for (c in cols) m[c] = rs.getString(c)
                    rows[rs.getInt("game_id")] = m
                }
            }
        }
        val attr = HashMap<Int, HashMap<String, String>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, field, value FROM items_attr WHERE field = 'extra' OR field = 'widget_actions_3'").use { rs ->
                while (rs.next()) attr.getOrPut(rs.getInt("id")) { HashMap() }[rs.getString("field")] = rs.getString("value")
            }
        }
        println("reference: ${rows.size} items")

        var compared = 0
        val bad = LinkedHashMap<String, MutableList<Int>>()
        fun mismatch(field: String, id: Int) = bad.getOrPut(field) { ArrayList() }.add(id)

        for ((id, want) in rows) {
            val got = ItemFilesystemCodec.load(fs, id)
            if (got == null) {
                mismatch("record absent from cache", id)
                continue
            }
            compared++
            if ((want["name"] ?: "") != (got.name ?: "")) mismatch("name", id)
            if ((want["members"] != null) != (got.members == true)) mismatch("members", id)
            if ((want["tradeable"] != null) != (got.tradeable == true)) mismatch("tradeable", id)
            if ((want["stackable_1"] != null) != (got.stackable == true)) mismatch("stackable_1", id)
            if (want["equipSlotId"]?.toInt() != got.equipSlotId) mismatch("equipSlotId", id)
            if (want["equipId"]?.toInt() != got.equipId) mismatch("equipId", id)
            if (want["buy_limit"]?.toInt() != got.buyLimit) mismatch("buy_limit", id)
            if (want["category"]?.toInt() != got.category) mismatch("category", id)
            if (want["dummyItem"]?.toInt() != got.dummyItem) mismatch("dummyItem", id)
            for (slot in listOf(0, 1, 2, 4)) {
                val w = want["widget_actions_$slot"]
                if ((w ?: "") != (got.widgetActions[slot] ?: "")) mismatch("widget_actions_$slot", id)
            }
            val a = attr[id]
            val w3 = a?.get("widget_actions_3")?.trim('"')
            if ((w3 ?: "") != (got.widgetActions[3] ?: "")) mismatch("widget_actions_3", id)
            val wantParams = a?.get("extra")
            val n = if (wantParams == null) 0 else Regex("\"prop\":").findAll(wantParams).count()
            if (n != got.params.size) mismatch("params count", id)
        }

        println("compared $compared items")
        if (bad.isEmpty()) {
            println("ItemDecodeVerify: every field matches the reference.")
        } else {
            bad.entries.sortedByDescending { it.value.size }.forEach { (k, v) ->
                println("  ${v.size} mismatched $k   e.g. ${v.take(6)}")
            }
            println("ItemDecodeVerify: ${bad.values.sumOf { it.size }} mismatches.")
        }
    }
}
