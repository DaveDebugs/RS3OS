package com.opennxt.resources.sqlite

import com.google.gson.JsonParser

/**
 * Cache enums, read from BOTH representations the decoder produced.
 */
object CacheEnums {

    /** One enum's pairs, in cache order. Keys and values kept as strings. */
    data class Entry(val key: String, val value: String)

    /** Which storage an enum's pairs were found in. For auditing and checks. */
    enum class Source { ENUM_ENTRY, ATTR_ARRAY_1, NONE }

    private val memo = HashMap<Int, List<Entry>>()
    private val sourceMemo = HashMap<Int, Source>()

    @Synchronized
    fun entries(enumId: Int): List<Entry> = memo.getOrPut(enumId) {
        if (!RsDatabase.available) return@getOrPut emptyList()

        // 1. the flattened table, which covers the *2 encodings
        val flat = RsDatabase.queryAll(
            "SELECT key, value FROM enum_entry WHERE enum_id = ?", enumId
        ) { Entry(it.getString("key"), it.getString("value")) }
        if (flat.isNotEmpty()) {
            sourceMemo[enumId] = Source.ENUM_ENTRY
            return@getOrPut flat
        }

        // 2. the *1 encodings the flattening step dropped
        for (field in listOf("intArrayValue1", "stringArrayValue1")) {
            val raw = RsDatabase.queryOne(
                "SELECT value FROM enums_attr WHERE id = ? AND field = '$field'", enumId
            ) { it.getString(1) } ?: continue
            val out = ArrayList<Entry>()
            for (el in JsonParser().parse(raw).asJsonArray) {
                val pair = el.asJsonArray
                if (pair.size() < 2) continue
                out.add(Entry(pair.get(0).asString, pair.get(1).asString))
            }
            if (out.isNotEmpty()) {
                sourceMemo[enumId] = Source.ATTR_ARRAY_1
                return@getOrPut out
            }
        }
        sourceMemo[enumId] = Source.NONE
        emptyList()
    }

    /** Where this enum's pairs came from. Calls [entries] first. */
    fun sourceOf(enumId: Int): Source {
        entries(enumId)
        return sourceMemo[enumId] ?: Source.NONE
    }

    /** Integer view, for the common case. Non-numeric pairs are skipped. */
    fun intMap(enumId: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (e in entries(enumId)) {
            val k = e.key.toIntOrNull() ?: continue
            val v = e.value.toIntOrNull() ?: continue
            out[k] = v
        }
        return out
    }

    /** Enum ids that `enum_entry` cannot see. The measured size of the bug. */
    fun idsMissingFromEnumEntry(): List<Int> {
        if (!RsDatabase.available) return emptyList()
        return RsDatabase.queryAll(
            "SELECT DISTINCT id FROM enums_attr WHERE field IN ('intArrayValue1','stringArrayValue1') " +
                "AND id NOT IN (SELECT DISTINCT enum_id FROM enum_entry) ORDER BY id"
        ) { it.getInt(1) }
    }

    val PROVENANCE: String =
        "cache enums: enum_entry only ever received the intArrayValue2/stringArrayValue2 encodings. " +
            "878 enums (698 int, 180 string) are stored as the *1 variants and have ZERO enum_entry " +
            "rows, so every query against that table has silently been missing them. The data is in " +
            "enums_attr as [key,value] pairs; this reader consults both. The real fix belongs in the " +
            "database builder and this is not it."
}
