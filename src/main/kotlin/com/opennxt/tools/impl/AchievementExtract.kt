package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode achievement definitions out of index 57.
 *
 * WHY INDEX 57
 *
 * [com.opennxt.filesystem.Index] has no constant for it -- it is one of twelve
 * indices in this cache that nothing here names. RuneTools identifies it:
 *
 *     constexpr int kIndexAchievements = 57;  // archive = id >> 7, file = id & 0x7f
 *
 * and our cache has 39 archives there, which at 128 files each covers ~5,000
 * ids -- the right order for an achievement list.
 *
 * PROVENANCE
 *
 * Ported from RuneTools `src/cache/Achievements.cpp`, whose own note says the
 * opcode loop was "re-validated in full on build 949: 5009/5009 decode clean and
 * the Quest Cape's 273 sub-achievement names all match quest config names".
 * Build 949 is this server's target, so unlike the map decoders this one did
 * not need its addressing or its widths re-derived -- but it still has to be
 * checked here rather than taken on trust.
 *
 * HOW IT IS CHECKED
 *
 * Three ways, none of which require a table that does not exist yet:
 *
 *   1. Clean-decode rate. The loop stops on an unknown opcode, so anything
 *      other than "ended on opcode 0" means the table is incomplete for this
 * cache. RuneTools measured 5009/5009.
 *   2. Varbit cross-reference. Op-14 requirements name varbit ids, and this
 *      database already holds 60,682 varbits. Ids that resolve are evidence the
 *      walk stayed aligned; ids that do not are evidence it drifted. This is the
 *      same zero-orphan check that confirmed `map_loc`'s 88,744 loc ids.
 *   3. Names. A desynced decode produces mojibake, not readable English.
 *
 * NOTE ON STRINGS
 *
 * Cache strings are CP-1252, not UTF-8 or Latin-1. A lone 0x80-0x9F byte -- a
 * curly apostrophe or an em-dash, both common in achievement text -- is invalid
 * UTF-8 and decodes to a replacement character if treated as such. Each is
 * mapped explicitly below. They are also PADDED: one leading byte before the
 * text that is not part of it.
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.AchievementExtract
 */
object AchievementExtract {

    private const val INDEX = 57

    /** CP-1252 replacements for 0x80..0x9F, where it differs from Latin-1. */
    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178
    )

    /**
     * Past the end every read yields 0 or "", and the loop stops on the next
     * opcode. That matches the reference decoder: a short file is not an
     * exception, it is an early stop that gets counted.
     */
    private class Reader(val buf: ByteBuffer) {
        fun eof() = !buf.hasRemaining()
        fun u8(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun u32(): Int {
            var v = u8()
            repeat(3) { v = (v shl 8) or u8() }
            return v
        }

        /** High bit set -> two bytes with the flag cleared; otherwise one. */
        fun usmart(): Int {
            val i = u8()
            return if (i >= 0x80) ((i - 0x80) shl 8) or u8() else i
        }

        /** High bit set -> 4 bytes masked to 31 bits; else 2, with 0x7FFF meaning 0. */
        fun smart32(): Int {
            if (buf.hasRemaining() && (buf.get(buf.position()).toInt() and 0x80) != 0) {
                return u32() and 0x7FFFFFFF
            }
            val v = u16()
            return if (v == 0x7FFF) 0 else v
        }

        /** Padded, NUL-terminated CP-1252 string: one pad byte, then the text. */
        fun pstr(): String {
            u8()
            val sb = StringBuilder()
            while (buf.hasRemaining()) {
                val c = buf.get().toInt() and 0xff
                if (c == 0) return sb.toString()
                sb.appendCodePoint(if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80] else c)
            }
            return sb.toString()
        }
    }

    private class Ach(val id: Int) {
        var name = ""; var desc = ""; var reward = ""
        var cat = -1; var subcat = -1; var sprite = -1
        var points = 0; var hidden = 0; var combatMastery = -1
        // Op 19's PRESENCE marks an achievement free-to-play, so absence means
        // members. Defaulting this to false would silently mark the whole game
        // free.
        var members = true
        var named = false
        val varbits = ArrayList<Int>()
        val subach = ArrayList<Int>()
        var stopOp = 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "achievements.tsv"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            System.err.println("no reference table for index $INDEX")
            return
        }

        val all = ArrayList<Ach>()
        var files = 0
        val stops = sortedMapOf<Int, Int>()

        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                files++
                val ach = decode(a * 128 + fid, d)
                stops[ach.stopOp] = (stops[ach.stopOp] ?: 0) + 1
                all.add(ach)
            }
        }

        val clean = all.count { it.stopOp == 0 }
        println("index $INDEX achievements")
        println("  files                : $files")
        println("  decoded CLEAN        : $clean  (%.2f%%)".format(100.0 * clean / files))
        println("  stopped on opcode    : " +
                stops.entries.filter { it.key != 0 }.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }
                    .ifEmpty { "(none)" })
        println("  with a name          : ${all.count { it.named }}")
        println("  distinct varbits used: ${all.flatMap { it.varbits }.toSet().size}")
        println("  members / f2p        : ${all.count { it.members }} / ${all.count { !it.members }}")

        val named = all.filter { it.named }
        if (named.isNotEmpty()) {
            println("\n  sample names:")
            for (x in named.take(6)) {
                println("    %-6d %-42s %s".format(x.id, x.name.take(42), x.desc.take(48)))
            }
        }

        File(out).bufferedWriter().use { w ->
            w.write("id\tname\tdesc\treward\tcat\tsubcat\tsprite\tpoints\thidden\tmembers\tvarbits\tsubach\n")
            for (x in all) {
                w.write(listOf(
                    x.id, tsv(x.name), tsv(x.desc), tsv(x.reward), x.cat, x.subcat,
                    x.sprite, x.points, x.hidden, if (x.members) 1 else 0,
                    x.varbits.joinToString(","), x.subach.joinToString(",")
                ).joinToString("\t"))
                w.write("\n")
            }
        }
        println("\n  -> $out")
    }

    private fun tsv(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun decode(id: Int, data: ByteArray): Ach {
        val a = Ach(id)
        val r = Reader(ByteBuffer.wrap(data))
        while (true) {
            if (r.eof()) break
            val op = r.u8()
            if (op == 0) break
            when (op) {
                1 -> { a.name = r.pstr(); a.named = true }
                // Descriptions: count, then count x [tag byte + padded string].
                // The first is the standard text; the rest are group-ironman
                // variants and are read only to stay aligned.
                2 -> {
                    val n = r.u8().coerceIn(0, 16)
                    for (i in 0 until n) {
                        r.u8()
                        val s = r.pstr()
                        if (i == 0) a.desc = s
                    }
                }
                3 -> a.cat = r.u16()
                4 -> a.sprite = r.smart32()
                5 -> a.points = r.u8()
                6 -> r.u16()
                7 -> a.reward = r.pstr()
                8 -> { val c = r.usmart(); repeat(c) { r.u8(); r.u8(); r.pstr(); r.u8(); r.u16() } }
                9, 10 -> { val c = r.u8(); repeat(c) { r.u8(); r.smart32(); r.pstr(); r.u8(); r.u16() } }
                11 -> { val c = r.u8(); repeat(c) { r.u24() } }
                // Skill requirement: (?, level, name, ?, skill).
                12 -> { val c = r.usmart(); repeat(c) { r.u8(); r.u8(); r.pstr(); r.u8(); r.u16() } }
                // Same shape as 14, but the u16 ids are VARP ids rather than
                // varbits, so they are NOT collected into a.varbits -- mixing
                // the two id spaces would corrupt the cross-check.
                13 -> {
                    val c = r.usmart()
                    repeat(c) {
                        r.u8(); r.smart32(); r.pstr()
                        val m = r.u8(); repeat(m) { r.u16() }
                    }
                }
                // Completion requirements: satisfied when the live varbit value
                // reaches the target. Multiple ids in one entry SUM.
                14 -> {
                    val c = r.usmart()
                    repeat(c) {
                        r.u8(); r.smart32(); r.pstr()
                        val m = r.u8(); repeat(m) { a.varbits.add(r.u16()) }
                    }
                }
                15 -> { val c = r.usmart(); repeat(c) { a.subach.add(r.u24()) } }
                16 -> a.subcat = r.u16()
                17 -> {}
                18 -> a.hidden = r.u8()
                19 -> a.members = false
                20, 21 -> { val c = r.u8(); repeat(c) { r.u24() } }
                // Packed bit requirement. 23 indexes VARP ids, 25 indexes VARBIT
                // ids; the wire shape is identical.
                23, 25 -> {
                    val c = r.usmart()
                    repeat(c) { r.u8(); r.u16(); r.u8(); r.pstr(); r.u8() }
                }
                // Combat-mastery achievements carry their name HERE, not in op 1.
                26 -> { a.combatMastery = r.u16(); r.u8(); a.name = r.pstr(); a.named = true }
                27 -> {}
                28 -> { val c = r.u8(); repeat(c) { r.u8() } }
                29, 31, 37, 38 -> r.u8()
                30 -> { val c = r.u8(); repeat(c) { r.usmart() } }
                32 -> { r.u8(); r.u8(); r.u8() }
                35 -> {}
                else -> { a.stopOp = op; return a }
            }
        }
        return a
    }
}
