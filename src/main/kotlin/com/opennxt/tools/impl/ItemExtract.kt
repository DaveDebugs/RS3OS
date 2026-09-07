package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode item definitions out of index 19.
 *
 * WHY THIS EXISTS
 *
 * Same cache-vintage split that left `map_loc` with 247 undefined objects.
 * `items` was extracted from the `RuneScape` cache and tops out at id 60,906;
 * the cache the server actually serves reaches ~63,743. Nothing has tripped
 * over it yet only because no item id has been clicked -- the failure is
 * waiting, not absent.
 *
 * PROVENANCE
 *
 * Ported from RuneTools `src/cache/ItemType.cpp`. Addressing is index 19,
 * archive = id >> 8, file = id & 0xff.
 *
 * ONE OPCODE WORTH KNOWING ABOUT
 *
 * Opcode 9 is a counted list of big-smarts, not a payload-less flag. RuneTools
 * records that it read as a flag until build ~950 because with count 1 the
 * stream stayed aligned by accident -- the list aliased as an opcode-1 read.
 * Counts of 2 and 4 in newer files break that, which is exactly the kind of bug
 * the clean-decode gate below catches and eyeballing does not.
 *
 * Build 949 also widened every item-id link to u24 as ids passed 65535:
 * 190..199 supersede 100..109, and 201/202 supersede the note links 97/98.
 *
 * HOW IT IS CHECKED
 *
 * Decode the cache `items` was built from and reproduce it. Name and the five
 * ground options must match on every shared id before the decode is trusted
 * against the served cache.
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.ItemExtract
 */
object ItemExtract {

    private const val INDEX = 19

    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178
    )

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun u8(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else 0
        fun i8(): Int = if (buf.hasRemaining()) buf.get().toInt() else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun i32(): Int { var v = u8(); repeat(3) { v = (v shl 8) or u8() }; return v }
        fun i64(): Long {
            val hi = i32().toLong() and 0xffffffffL
            val lo = i32().toLong() and 0xffffffffL
            return (hi shl 32) or lo
        }
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

    private class Item(val id: Int) {
        var name = ""
        val options = arrayOfNulls<String>(5)
        val wornOptions = arrayOfNulls<String>(5)
        var stackable = false
        var tradeable = false
        var noted = false
        var notedTemplate = -1
        var notedUnnoted = -1
        var geLimit = -1
        var category = -1
        var value = -1L
        var stopOp = 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "items.tsv"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            System.err.println("no reference table for index $INDEX")
            return
        }

        var files = 0
        var clean = 0
        val stops = sortedMapOf<Int, Int>()
        val all = ArrayList<Item>()

        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                files++
                val it0 = decode(a * 256 + fid, d)
                if (it0.stopOp == 0) clean++ else stops[it0.stopOp] = (stops[it0.stopOp] ?: 0) + 1
                all.add(it0)
            }
        }

        println("index $INDEX items")
        println("  files            : $files")
        println("  decoded CLEAN    : $clean  (%.2f%%)".format(100.0 * clean / files))
        println("  stopped on opcode: " +
                stops.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }.ifEmpty { "(none)" })
        println("  with a name      : ${all.count { it.name.isNotEmpty() }}")
        println("  stackable / noted: ${all.count { it.stackable }} / ${all.count { it.noted }}")
        println("  id range         : ${all.minOfOrNull { it.id }}..${all.maxOfOrNull { it.id }}")

        File(out).bufferedWriter().use { w ->
            w.write("id\tname\tstackable\ttradeable\tnoted\tnotedTemplate\tnotedUnnoted\t" +
                    "geLimit\tcategory\tvalue\tstop\top0\top1\top2\top3\top4\t" +
                    "w0\tw1\tw2\tw3\tw4\n")
            for (x in all) {
                w.write(listOf(
                    x.id, tsv(x.name),
                    if (x.stackable) 1 else 0, if (x.tradeable) 1 else 0,
                    if (x.noted) 1 else 0, x.notedTemplate, x.notedUnnoted,
                    x.geLimit, x.category, x.value, x.stopOp
                ).joinToString("\t"))
                for (o in x.options) w.write("\t" + tsv(o ?: ""))
                for (o in x.wornOptions) w.write("\t" + tsv(o ?: ""))
                w.write("\n")
            }
        }
        println("  -> $out")
    }

    private fun tsv(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun decode(id: Int, data: ByteArray): Item {
        val d = Item(id)
        val r = Reader(ByteBuffer.wrap(data))
        while (true) {
            if (r.remaining() <= 0) break
            val op = r.u8()
            if (op == 0) break
            if (!one(r, d, op)) { d.stopOp = op; break }
        }
        return d
    }

    private fun one(r: Reader, d: Item, op: Int): Boolean {
        when (op) {
            1 -> r.bigSmart()
            2 -> d.name = r.str()
            3 -> r.str()
            4, 5, 6, 7, 8, 10, 18, 44, 45, 95, 110, 111, 112, 121, 122,
            127, 128, 129, 130, 139, 140, 161, 162, 163 -> r.u16()
            11 -> d.stackable = true
            12, 43 -> r.i32()
            // A counted list, NOT a flag -- see the class doc.
            9 -> { val n = r.u8(); repeat(n) { r.bigSmart() } }
            13, 14, 27, 96, 115, 134 -> r.u8()
            15, 16, 157, 167, 168, 178 -> {}
            23, 24, 25, 26, 78, 79, 90, 91, 92, 93 -> r.bigSmart()
            in 30..34 -> d.options[op - 30] = r.str()
            in 35..39 -> d.wornOptions[op - 35] = r.str()
            40, 41 -> { val n = r.u8(); repeat(n) { r.u16(); r.u16() } }
            42 -> { val n = r.u8(); repeat(n) { r.u8(); r.i8() } }
            65 -> d.tradeable = true
            69 -> d.geLimit = r.i32()
            94 -> d.category = r.u16()
            97 -> d.notedUnnoted = r.u16()
            98 -> { d.notedTemplate = r.u16(); d.noted = true }
            in 100..109 -> { r.u16(); r.u16() }
            113, 114 -> r.i8()
            125, 126 -> { r.i8(); r.i8(); r.i8() }
            132 -> { val n = r.u8(); repeat(n) { r.u16() } }
            in 142..146, in 150..154 -> r.u16()
            164 -> r.str()
            165 -> d.stackable = true
            181 -> d.value = r.i64()
            182 -> r.u24()
            // Build 949 widened the item-id links to u24 as ids passed 65535.
            in 190..199 -> { r.u24(); r.u16() }
            201 -> d.notedUnnoted = r.u24()
            202 -> { d.notedTemplate = r.u24(); d.noted = true }
            in 203..208 -> r.u24()
            in 242..248 -> r.bigSmart()
            249 -> {
                val n = r.u8()
                repeat(n) {
                    val isStr = r.u8() == 1
                    r.u24()
                    if (isStr) r.str() else r.i32()
                }
            }
            else -> return false
        }
        return true
    }
}
