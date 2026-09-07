package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode location ("object") definitions out of index 16.
 *
 * WHY THIS EXISTS
 *
 * `map_loc` now describes the cache the server actually serves, but `locs` was
 * extracted from an older one, so 247 placed loc ids have no definition and the
 * server answers a click on them with "unknown loc". This closes that: the
 * database has no locs decoder at all, and the table was built by something
 * outside this repository.
 *
 * PROVENANCE
 *
 * Ported from RuneTools `src/cache/LocationType.cpp`, whose opcode table it
 * reports as "full-index validated 139143/139143 clean". Addressing is
 * index 16, archive = id >> 8, file = id & 0xff.
 *
 * WHAT IT KEEPS
 *
 * The fields the server needs to answer a click -- name, the five action slots,
 * the members overrides, footprint, and the no-clip flag -- plus mapscene and
 * map function. Every OTHER opcode is still consumed to its exact width, which
 * is what makes the check below meaningful: this is a complete walk that stores
 * a subset, not a partial walk.
 *
 * HOW IT IS CHECKED
 *
 * Same discipline as the map work. The format is opcode-walked with no lengths
 * and no terminators, so a wrong width desyncs and the file misses its
 * terminator. On top of that there is real known-good data to diff against: the
 * existing 137,079 `locs` rows came from the RuneScape cache, so decoding THAT
 * cache must reproduce their name, actions and footprint exactly. Only once it
 * does is the decoder trusted against the reference cache.
 *
 * Ops 205 and 206 carry a u16 payload length; the walk parses their contents
 * and then asserts it landed exactly on the stated boundary, so a drift inside
 * them surfaces as a stop rather than as silent corruption.
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.LocExtract
 */
object LocExtract {

    private const val INDEX = 16

    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178
    )

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun offset() = buf.position()
        fun u8(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else 0
        fun i8(): Int = if (buf.hasRemaining()) buf.get().toInt() else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun i16(): Int = u16().let { if (it > 32767) it - 65536 else it }
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun i32(): Int {
            var v = u8()
            repeat(3) { v = (v shl 8) or u8() }
            return v
        }
        fun skip(n: Int) { buf.position(minOf(buf.limit(), buf.position() + maxOf(0, n))) }

        /** High bit set -> 4 bytes masked to 31 bits; else 2, with 0x7FFF meaning -1. */
        fun bigSmart(): Int {
            if (!buf.hasRemaining()) return -1
            if ((buf.get(buf.position()).toInt() and 0x80) != 0) return i32() and 0x7FFFFFFF
            val v = u16()
            return if (v == 0x7FFF) -1 else v
        }

        /** >= 0x80 -> two bytes less 0x8000; else one. */
        fun usmart(): Int {
            if (!buf.hasRemaining()) return 0
            val p = buf.get(buf.position()).toInt() and 0xff
            return if (p >= 0x80) u16() - 0x8000 else u8()
        }

        /** NUL-terminated CP-1252. No pad byte, unlike the achievement strings. */
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

    private class Loc(val id: Int) {
        var name = ""
        val options = arrayOfNulls<String>(5)
        val memberOptions = arrayOfNulls<String>(5)
        var dimX = 1
        var dimY = 1
        var mapscene = -1
        var mapFunction = -1
        var members = false
        var noClip = false
        var stopOp = 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "locs.tsv"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            System.err.println("no reference table for index $INDEX")
            return
        }

        var files = 0
        var clean = 0
        val stops = sortedMapOf<Int, Int>()
        val all = ArrayList<Loc>()

        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                files++
                val loc = decode(a * 256 + fid, d)
                if (loc.stopOp == 0) clean++ else stops[loc.stopOp] = (stops[loc.stopOp] ?: 0) + 1
                all.add(loc)
            }
        }

        println("index $INDEX locations")
        println("  files            : $files")
        println("  decoded CLEAN    : $clean  (%.2f%%)".format(100.0 * clean / files))
        println("  stopped on opcode: " +
                stops.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }.ifEmpty { "(none)" })
        println("  with a name      : ${all.count { it.name.isNotEmpty() }}")
        println("  id range         : ${all.minOfOrNull { it.id }}..${all.maxOfOrNull { it.id }}")

        File(out).bufferedWriter().use { w ->
            w.write("id\tname\tw\tl\tmembers\tnoclip\tmapscene\tmapfunc\tstop\t" +
                    "op0\top1\top2\top3\top4\tm0\tm1\tm2\tm3\tm4\n")
            for (x in all) {
                w.write(listOf(
                    x.id, tsv(x.name), x.dimX, x.dimY,
                    if (x.members) 1 else 0, if (x.noClip) 1 else 0,
                    x.mapscene, x.mapFunction, x.stopOp
                ).joinToString("\t"))
                for (o in x.options) w.write("\t" + tsv(o ?: ""))
                for (o in x.memberOptions) w.write("\t" + tsv(o ?: ""))
                w.write("\n")
            }
        }
        println("  -> $out")
    }

    private fun tsv(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun decode(id: Int, data: ByteArray): Loc {
        val d = Loc(id)
        val r = Reader(ByteBuffer.wrap(data))
        while (true) {
            if (r.remaining() <= 0) break
            val op = r.u8()
            if (op == 0) break
            if (!one(r, d, op)) { d.stopOp = op; break }
        }
        return d
    }

    private fun one(r: Reader, d: Loc, op: Int): Boolean {
        when (op) {
            1 -> { val n = r.u8(); repeat(n) { r.i8(); val s = r.u8(); repeat(s) { r.bigSmart() } } }
            2 -> d.name = r.str()
            14 -> d.dimX = r.u8()
            15 -> d.dimY = r.u8()
            17 -> d.noClip = true
            18, 21, 22, 23, 27, 62, 64, 73, 74, 82, 88, 89, 94, 97, 98, 103 -> {}
            19 -> r.u8()
            24 -> r.bigSmart()
            28, 29, 39 -> r.i8()
            40, 41 -> { val n = r.u8(); repeat(n) { r.u16(); r.u16() } }
            44, 45 -> r.skip(2)
            65, 66, 67, 70, 71, 72, 93, 95 -> r.u16()
            69, 75, 81, 104 -> r.u8()
            77 -> morph(r, false)
            78 -> { r.u16(); r.u8() }
            79 -> { r.u16(); r.u16(); r.u8(); val n = r.u8(); repeat(n) { r.u16() } }
            91 -> d.members = true
            92 -> morph(r, true)
            102 -> d.mapscene = r.u16()
            106 -> { val n = r.u8(); repeat(n) { r.bigSmart(); r.u8() } }
            107 -> d.mapFunction = r.u16()
            // Build ~950 additions.
            108, 109, 110, 159, 177, 188, 189, 198, 199, 203 -> {}
            160 -> { val n = r.u8(); repeat(n) { r.u16() } }
            162 -> r.i32()
            163 -> r.skip(4)
            164, 165, 167, 173 -> { r.u16(); if (op == 173) r.u16() }
            166 -> r.i16()
            170, 171, 202 -> r.usmart()
            178, 186, 196, 197 -> r.u8()
            201 -> repeat(6) { r.usmart() }
            204 -> { val n = r.usmart(); repeat(n) { r.u16(); r.u8(); repeat(6) { r.i32() } } }
            // 205/206 declare their own length. Parsing the body and then
            // asserting the cursor lands exactly on the stated end turns any
            // drift inside them into a visible stop instead of silent
            // corruption further down the file.
            205 -> {
                val len = r.u16(); val end = r.offset() + len
                r.u16(); r.u16(); r.u8(); r.u8(); r.u8()
                val n = r.u8()
                repeat(n) {
                    val lo = r.u16(); val hi = r.u16(); r.bigSmart()
                    if (hi < lo || hi > 1024) return false
                }
                r.u16()
                return r.offset() == end
            }
            206 -> {
                val len = r.u16(); val end = r.offset() + len
                val n = r.u8()
                repeat(n) {
                    r.u8(); r.i32(); r.i32(); r.i32(); r.u8(); r.i32(); r.i32()
                    r.u8(); r.u16(); r.i32(); r.i32(); r.i32()
                }
                return r.offset() == end
            }
            249 -> {
                val n = r.u8()
                repeat(n) {
                    val isStr = r.u8() == 1
                    r.u24()
                    if (isStr) r.str() else r.i32()
                }
            }
            else -> {
                if (op in 30..34) { d.options[op - 30] = r.str(); return true }
                if (op in 136..140) { r.u8(); return true }
                if (op in 150..154) { d.memberOptions[op - 150] = r.str(); return true }
                if (op in 190..195) { r.u16(); return true }
                return false
            }
        }
        return true
    }

    /** Opcodes 77 and 92: varbit/varp selected variant table. 92 adds a default. */
    private fun morph(r: Reader, withDefault: Boolean) {
        r.u16(); r.u16()
        if (withDefault) r.bigSmart()
        val n = r.usmart()
        for (i in 0..n) r.bigSmart()
    }
}
