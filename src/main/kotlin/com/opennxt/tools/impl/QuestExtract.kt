package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode quest definitions out of index 2, archive 35.
 */
object QuestExtract {

    private const val ARCHIVE = 35

    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178
    )

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun u8(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun i32(): Int { var v = u8(); repeat(3) { v = (v shl 8) or u8() }; return v }
        fun bigSmart(): Int {
            if (!buf.hasRemaining()) return -1
            if ((buf.get(buf.position()).toInt() and 0x80) != 0) return i32() and 0x7FFFFFFF
            val v = u16()
            return if (v == 0x7FFF) -1 else v
        }
        fun str(): String {
            val sb = StringBuilder()
            while (buf.hasRemaining()) {
                val c = buf.get().toInt() and 0xff
                if (c == 0) return sb.toString()
                sb.appendCodePoint(if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80] else c)
            }
            return sb.toString()
        }
    }

 // `internal`, not `private`, since: QuestColumnsFill reads these
    // fields to write quest_item_sprite / quest_list_name into rs3.sqlite.
    internal class Quest(val id: Int) {
        var name = ""
        var members = false
        var difficulty = -1
        var points = 0
        var pointsReq = 0
        var parent = -1
        var vp = -1; var vpStart = 0; var vpEnd = 0
        var vb = -1; var vbStart = 0; var vbEnd = 0
        var journal = -1; var year = 0; var length = -1; var age = -1; var area = -1
        var desc = ""; var start = ""; var items = ""; var combat = ""; var rewards = ""
        /**
         * Opcode 2, the quest-list spelling, and opcode 17, the item sprite.
         *
         * Both were decoded to the right width and then DISCARDED, and that had a
         * cost two files away: `SqliteQuestCodec` declares `questListName` and
         * `questItemSprite`, `quests` has no column for either because this
         * writer never emitted one, and `HANDOFF-RECONCILED.md` section 6
         * concluded "`quest_item_sprite` has no source in the extracted cache".
         * It has one - it is opcode 17, here, and it always was.
         *
         * The corroboration is exact and was not tuned for: quest 66 (Dragon
         * Slayer) carries opcode 17 = 33638 and quest 135 (Desert Treasure)
         */
        var listName = ""
        var itemSprite = -1
        val questReqs = ArrayList<Int>()
        val statReqs = ArrayList<Pair<Int, Int>>()
        var stopOp = 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "quests.tsv"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val all = readAll(fs) ?: return

        val files = all.size
        var clean = 0
        var named = 0
        val stops = sortedMapOf<Int, Int>()

        for (q in all) {
            if (q.stopOp == 0) clean++ else stops[q.stopOp] = (stops[q.stopOp] ?: 0) + 1
            if (q.name.isNotEmpty()) named++
        }

        println("index ${Index.CONFIG} archive $ARCHIVE quests")
        println("  files            : $files")
        println("  decoded CLEAN    : $clean  (%.2f%%)".format(100.0 * clean / files))
        println("  with a name      : $named")
        println("  stopped on opcode: " +
                stops.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }.ifEmpty { "(none)" })
        println("  members / f2p    : ${all.count { it.members }} / ${all.count { !it.members }}")
        println("  with a varbit    : ${all.count { it.vb >= 0 }}")
        println("  with prereqs     : ${all.count { it.questReqs.isNotEmpty() }}")
        println("  total quest points: ${all.sumOf { it.points }}")
        println("  with a list name : ${all.count { it.listName.isNotEmpty() }}")
        println("  with an item sprite: ${all.count { it.itemSprite >= 0 }}" +
            "  (66 Dragon Slayer = ${all.firstOrNull { it.id == 66 }?.itemSprite}, " +
            "135 Desert Treasure = ${all.firstOrNull { it.id == 135 }?.itemSprite})")

        println("\n  sample:")
        for (q in all.filter { it.name.isNotEmpty() }.sortedBy { it.id }.take(5)) {
            println("    %-5d %-34s pts=%-3d diff=%-3d varbit=%-6d %s"
                .format(q.id, q.name.take(34), q.points, q.difficulty, q.vb, q.desc.take(40)))
        }

        File(out).bufferedWriter().use { w ->
            w.write("id\tname\tmembers\tdifficulty\tpoints\tpointsReq\tparent\t" +
                    "vp\tvpStart\tvpEnd\tvb\tvbStart\tvbEnd\tjournal\tyear\tlength\tage\tarea\t" +
                    "questReqs\tstatReqs\tstop\tdesc\tstart\titems\tcombat\trewards\t" +
                    // Appended, not inserted: every existing column keeps its
                    // position so a positional reader of an older file is unaffected.
                    "listName\titemSprite\n")
            for (q in all) {
                w.write(listOf(
                    q.id, tsv(q.name), if (q.members) 1 else 0, q.difficulty, q.points,
                    q.pointsReq, q.parent, q.vp, q.vpStart, q.vpEnd, q.vb, q.vbStart, q.vbEnd,
                    q.journal, q.year, q.length, q.age, q.area,
                    q.questReqs.joinToString(","),
                    q.statReqs.joinToString(",") { "${it.first}:${it.second}" },
                    q.stopOp, tsv(q.desc), tsv(q.start), tsv(q.items),
                    tsv(q.combat), tsv(q.rewards), tsv(q.listName), q.itemSprite
                ).joinToString("\t"))
                w.write("\n")
            }
        }
        println("\n  -> $out")
    }

    private fun tsv(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * Every quest in index 2 / archive 35 of [fs], decoded, in archive order -
     * one [Quest] per file that has data, the file id being the quest id. Null,
     * with the reason on stderr, when the cache has no such archive.
     */
    internal fun readAll(fs: SqliteFilesystem): List<Quest>? {
        val table = fs.getReferenceTable(Index.CONFIG)
        if (table == null) {
            System.err.println("no reference table for index ${Index.CONFIG}")
            return null
        }
        val archive = try { table.loadArchive(ARCHIVE) } catch (e: Exception) { null }
        if (archive == null) {
            System.err.println("index ${Index.CONFIG} has no archive $ARCHIVE")
            return null
        }
        val all = ArrayList<Quest>()
        for ((fid, f) in archive.files) {
            val d = f.data ?: continue
            all.add(decode(fid, d))
        }
        return all
    }

    internal fun decode(id: Int, data: ByteArray): Quest {
        val q = Quest(id)
        val r = Reader(ByteBuffer.wrap(data))
        while (r.remaining() > 0) {
            val op = r.u8()
            if (op == 0) break
            when (op) {
                // Both 1 and 2 carry a leading byte before the string; 1 is the
                // canonical name and 2 the list spelling.
                1 -> { r.u8(); q.name = r.str() }
                2 -> { r.u8(); q.listName = r.str() }
                // Progress triples. Only the FIRST is kept -- that is the
                // reference's own choice, and the rest are alternate tracks.
                3 -> {
                    val n = r.u8()
                    for (i in 0 until n) {
                        val v = r.u16(); val a = r.i32(); val b = r.i32()
                        if (i == 0) { q.vp = v; q.vpStart = a; q.vpEnd = b }
                    }
                }
                4 -> {
                    val n = r.u8()
                    for (i in 0 until n) {
                        val v = r.u16(); val a = r.i32(); val b = r.i32()
                        if (i == 0) { q.vb = v; q.vbStart = a; q.vbEnd = b }
                    }
                }
                5 -> q.parent = r.u16()
                6 -> r.u8()
                7 -> q.difficulty = r.u8()
                8 -> q.members = true
                9 -> q.points = r.u8()
                10 -> { val n = r.u8(); repeat(n) { r.i32() } }
                12 -> r.i32()
                13 -> { val n = r.u8(); repeat(n) { q.questReqs.add(r.u16()) } }
                14 -> { val n = r.u8(); repeat(n) { q.statReqs.add(r.u8() to r.u8()) } }
                15 -> q.pointsReq = r.u16()
                17 -> q.itemSprite = r.bigSmart()
                18, 19 -> { val n = r.u8(); repeat(n) { r.i32(); r.i32(); r.i32(); r.str() } }
                249 -> {
                    val n = r.u8()
                    repeat(n) {
                        val isStr = r.u8() == 1
                        val key = r.u24()
                        if (isStr) {
                            val v = r.str()
                            when {
                                key == 5968 -> q.desc = v
                                key == 7814 -> q.start = v
                                key == 9392 && q.start.isEmpty() -> q.start = v
                                key == 7815 -> q.items = v
                                key == 7816 -> q.combat = v
                                key == 7823 -> q.rewards = v
                            }
                        } else {
                            val v = r.i32()
                            when (key) {
                                7829 -> {}
                                7834 -> q.year = v
                                1345 -> q.journal = v
                                7855 -> q.length = v
                                7831 -> q.age = v
                                9393 -> q.area = v
                            }
                        }
                    }
                }
                else -> { q.stopOp = op; return q }
            }
        }
        return q
    }
}
