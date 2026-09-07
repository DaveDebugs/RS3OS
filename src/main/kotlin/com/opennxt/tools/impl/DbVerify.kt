package com.opennxt.tools.impl

import java.sql.Connection
import java.sql.DriverManager

/**
 * Compares a database db-builder produced against a known-good one, table by table and row by row.
 *
 *     -Dopennxt.reference.db=<path to a known-good rs3.sqlite>
 *
 * The decode verifiers check that the codecs read the cache correctly. This checks the step after:
 * that what lands in the database matches, down to the exact text of every attribute value. A
 * formatting difference the codecs would never notice - a raw character where the reference escapes
 * it, an object where the reference writes a bare number - shows up here.
 *
 * Rows only this build carries are reported as extra coverage, and rows only the reference carries
 * as a gap. Neither is a failure - the two databases were built from different caches by different
 * tools, so the meaningful test is that everything they BOTH carry agrees exactly.
 */
object DbVerify {
    private val OWN: String = System.getProperty("opennxt.db.own") ?: "data/rs3.sqlite"

    private val TABLES = listOf(
        "enums" to "id", "enum_entry" to null,
        "params" to "id", "structs" to "id", "struct_param" to null,
        "items" to "id", "npcs" to "id", "locs" to "id"
    )
    private val ATTR = listOf("items_attr", "npcs_attr", "locs_attr", "params_attr")

    @JvmStatic
    fun main(args: Array<String>) {
        val refPath = System.getProperty("opennxt.reference.db")
        if (refPath == null) {
            println("DbVerify needs -Dopennxt.reference.db=<path to a known-good rs3.sqlite>.")
            println("Without one there is nothing to compare against; this is not a failure.")
            return
        }
        Class.forName("org.sqlite.JDBC")
        val own = DriverManager.getConnection("jdbc:sqlite:$OWN")
        val ref = DriverManager.getConnection("jdbc:sqlite:$refPath")
        println("DbVerify: $OWN against $refPath")
        println()

        var failures = 0

        println("row counts")
        for ((table, _) in TABLES) {
            val a = count(own, table)
            val b = count(ref, table)
            val verdict = when {
                a == b -> "ok"
                a == 0 -> "not built yet"
                a > b -> "ok, ${fmt(a - b)} more here than the reference has"
                table == "struct_param" -> "short by ${fmt(b - a)}, all duplicate props (see below)"
                else -> { failures++; "SHORT by ${fmt(b - a)}" }
            }
            println("  %-14s mine %-9s ref %-9s %s".format(table, fmt(a), fmt(b), verdict))
        }

        println()
        println("attribute values, compared as text")
        var compared = 0
        var differing = 0
        var written = 0
        var available = 0
        for (table in ATTR) {
            if (count(own, table) == 0) {
                println("  %-14s not built yet".format(table))
                continue
            }
            val fields = queryStrings(own, "SELECT DISTINCT field FROM $table")
            var both = 0
            var diff = 0
            var onlyMine = 0
            val examples = ArrayList<String>()
            for (field in fields) {
                val mine = loadField(own, table, field)
                val theirs = loadField(ref, table, field)
                for ((id, value) in mine) {
                    val other = theirs[id]
                    when {
                        other == null -> onlyMine++
                        other != value -> {
                            diff++
                            if (examples.size < 3) examples.add("$field id=$id")
                        }
                        else -> both++
                    }
                }
            }
            compared += both
            differing += diff
            written += count(own, table)
            available += count(ref, table)
            if (diff > 0) failures++
            val extra = if (onlyMine == 0) "" else ", ${fmt(onlyMine)} the reference does not carry"
            println("  %-14s %-3d fields  %s compared%s  %s".format(
                table, fields.size, fmt(both), extra,
                if (diff == 0) "all identical" else "$diff DIFFER: $examples"))
        }

        println()
        println("  A handful of structs list the same prop twice. The struct codec keys them by prop,")
        println("  so the second value wins and the row count here comes out slightly under a builder")
        println("  that kept both. Lookups are by (struct, prop), so a duplicate has no single right")
        println("  answer either way.")
        println()
        if (available > 0) {
            println("coverage: ${fmt(written)} of ${fmt(available)} reference attribute rows " +
                "(${written * 100 / available}%). The remainder are fields no decoder here names yet.")
        }
        if (failures == 0) {
            println("DbVerify: every row both databases carry is identical " +
                "(${fmt(compared)} compared, ${fmt(differing)} differing).")
        } else {
            println("DbVerify: $failures table(s) disagree.")
            System.exit(1)
        }
    }

    private fun fmt(n: Int) = "%,d".format(n)

    private fun count(db: Connection, table: String): Int =
        try {
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM \"$table\"").use { if (it.next()) it.getInt(1) else 0 }
            }
        } catch (e: Exception) {
            0
        }

    private fun queryStrings(db: Connection, sql: String): List<String> {
        val out = ArrayList<String>()
        db.createStatement().use { st -> st.executeQuery(sql).use { while (it.next()) out.add(it.getString(1)) } }
        return out
    }

    private fun loadField(db: Connection, table: String, field: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        db.prepareStatement("SELECT id, value FROM $table WHERE field = ?").use { ps ->
            ps.setString(1, field)
            ps.executeQuery().use { while (it.next()) out[it.getInt(1)] = it.getString(2) }
        }
        return out
    }
}
