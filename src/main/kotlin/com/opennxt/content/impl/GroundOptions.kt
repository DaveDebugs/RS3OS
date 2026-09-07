package com.opennxt.content.impl

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * The five menu rows a GROUND item offers, derived from the served cache.
 */
object GroundOptions {
    private val logger = KotlinLogging.logger { }

    /** How many ground option slots an item definition carries. */
    const val ROWS = 5

    /**
     * The row the client's default array puts its left-click option on, 1-based,
     * i.e. the option carried by ClientProt OPOBJ3. See the class doc.
     */
    const val TAKE_ROW = 3

    /** The label the cache census gives [TAKE_ROW]. Matching is case-insensitive. */
    const val TAKE_LABEL = "Take"

    /**
     * The client's default ground option array, index 0..4 = rows 1..5.
     */
    val DEFAULT_ROWS: List<String?> = System.getProperty("opennxt.groundItems.defaultRows")
        ?.split('|')
        ?.map { it.trim().ifEmpty { null } }
        ?.let { supplied ->
            if (supplied.size != ROWS) {
                logger.warn {
                    "opennxt.groundItems.defaultRows needs exactly $ROWS |-separated segments; " +
                        "got ${supplied.size} - ignoring it and using the derived default."
                }
                null
            } else supplied
        }
        ?: List(ROWS) { if (it == TAKE_ROW - 1) TAKE_LABEL else null }

    // ------------------------------------------------------------- the cache

    private const val SQL_ONE =
        "SELECT field, value FROM items_attr WHERE id = ? AND field LIKE 'ground_actions_%'"
    private const val SQL_ALL =
        "SELECT id, field, value FROM items_attr WHERE field LIKE 'ground_actions_%'"

    /**
     * `ground_actions_3` -> 3; anything else -> -1.
     *
     * The LIKE also matches `ground_actions_cursor_0/3/4` (65 rows), which are
     * CURSOR ids and not option names. They fall out on -1. The filter is a
     * positive test on the slot number, not a negative test on the field name -
     * the same shape, and for the same reason, as
     * [com.opennxt.resources.sqlite.SqliteItemCodec]'s `slotOf`.
     */
    private fun slotOf(field: String): Int =
        field.removePrefix("ground_actions_").toIntOrNull()?.takeIf { it in 0 until ROWS } ?: -1

    /** `"Take"` -> `Take`. The attr values are JSON scalars. */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw.substring(1, raw.length - 1)
        else raw

    /**
     * The rows [itemId] AUTHORS, 0-based slot -> label. Empty for the vast
     * majority of items; `null` when there is no database to ask.
     */
    fun authoredRowsOf(itemId: Int): Map<Int, String>? {
        if (!RsDatabase.available) return null
        return RsDatabase.queryAll(SQL_ONE, itemId) {
            slotOf(it.getString("field")) to unquote(it.getString("value"))
        }.filter { it.first >= 0 }.toMap()
    }

    /**
     * The five rows [itemId] really offers: authored rows over [DEFAULT_ROWS].
     *
     * A `""` authored value (one item in this cache sets `ground_actions_2` to
     * the empty string) means "this row is REMOVED", not "this row is default" -
     * it is an explicit blank in the cache record and must not fall back.
     */
    fun rowsFor(itemId: Int): List<String?> {
        val authored = authoredRowsOf(itemId) ?: return DEFAULT_ROWS
        if (authored.isEmpty()) return DEFAULT_ROWS
        return (0 until ROWS).map { slot ->
            val a = authored[slot]
            when {
                a == null -> DEFAULT_ROWS[slot]
                a.isEmpty() || a == "null" -> null
                else -> a
            }
        }
    }

    /** The label on menu row [option] (1-based) for [itemId], or null if that row is empty. */
    fun optionFor(itemId: Int, option: Int): String? =
        if (option !in 1..ROWS) null else rowsFor(itemId).getOrNull(option - 1)

    /**
     * Whether menu row [option] of [itemId] is the take row.
     *
     * **The LABEL decides, not the row number.** For the overwhelming majority
     * of items that is row [TAKE_ROW], because that is where [DEFAULT_ROWS] puts
     * it; for the 20 items that author something else onto slot 2 (`Collect` x9,
     * `Collect story` x5, `Study`, `Observe`, `Harvest`, `Absorb`, `null`, `""`)
     * row 3 is NOT a take and must not be treated as one. Keying on the row
     * number would have silently picked up an item whose row 3 says `Absorb`.
     */
    fun isTake(itemId: Int, option: Int): Boolean =
        optionFor(itemId, option)?.equals(TAKE_LABEL, ignoreCase = true) == true

    // ------------------------------------------------------------- the census

    /**
     * slot -> (label -> how many items author it), over the WHOLE corpus.
     *
     * Derived, never typed: this is the population the class doc quotes, and a
     * check runs it rather than trusting the comment. One sweep, not a query per
     * item.
     */
    fun authoredCensus(): Map<Int, Map<String, Int>> {
        if (!RsDatabase.available) return emptyMap()
        val out = HashMap<Int, HashMap<String, Int>>()
        RsDatabase.queryAll(SQL_ALL) {
            slotOf(it.getString("field")) to unquote(it.getString("value"))
        }.forEach { (slot, label) ->
            if (slot >= 0) out.getOrPut(slot) { HashMap() }.merge(label, 1, Int::plus)
        }
        return out
    }

    /** How many items author any ground row at all. */
    fun authoredItemCount(): Int {
        if (!RsDatabase.available) return 0
        return RsDatabase.queryAll(SQL_ALL) {
            it.getInt("id") to slotOf(it.getString("field"))
        }.filter { it.second >= 0 }.map { it.first }.toSet().size
    }
}
