package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode object placements out of the map archives and emit them as TSV.
 *
 * WHY THIS EXISTS
 *
 * `rs3.sqlite`'s `map_loc` holds 802,252 rows across 554 distinct map squares.
 * There are 5,078 squares in `map_square`. The other 4,524 were never decoded --
 * measured, not assumed: only 33 squares appear in `map_keyed` at all and 21 of
 * those decoded fine, so encryption gates at most 12. The rest are plain and
 * simply were not read.
 *
 * FORMAT
 *
 * Archive id == square id == `i or (j shl 7)`. Index 5 carries no name hashes,
 * so archives must be loaded by id. File 0 is land placements, file 1 water,
 * file 3 terrain. Only file 0 belongs in `map_loc` -- the existing 802,252 rows
 * contain no file-1 placements.
 *
 * Each file is two nested loops:
 *
 *     id = -1
 *     loop:
 *         inc = smart, chained on 0x7FFF; 0 ends the file
 *         id += inc
 *         pos = 0
 *         loop:
 *             pinc = unsigned smart; 0 ends this object
 *             pos += pinc - 1
 *             plane = (pos shr 12) and 0x3
 *             x     = (pos shr 6)  and 0x3F
 *             y     =  pos         and 0x3F
 *             data  = unsigned byte
 *             type     = (data shr 2) and 0x1F
 *             rotation =  data        and 0x3
 *             if data >= 0x80: a flag byte, then the `xform` fields --
 *                 0x01 -> rot, four signed shorts
 *                 0x02 / 0x04 / 0x08 -> move x / y / z, one signed short each
 *                 0x10 -> uniform scale, one signed short
 *                 else 0x20 / 0x40 / 0x80 -> scale3 x / y / z, default 128
 *
 * VERIFIED
 *
 * Against all 554 squares already in `map_loc`, read from the `RuneScape`
 * cache: 802,252 of 802,252 rows reproduced exactly, xform strings included,
 * with zero extra rows and every archive consumed to the last byte.
 *
 * Read from the reference cache or the 947 cache instead, 502 of 554 squares match.
 * That difference is cache build, not decoder -- those two are an older build
 * than the one the database was extracted from. Use the `RuneScape` cache.
 *
 * Ported from RuneTools `src/cache/MapLocations.cpp`.
 *
 * Re-verify after any change with:
 *
 *     java -Dopennxt.cache=<RuneScape cache> -cp <cp>  *         com.opennxt.tools.impl.MapLocExtract  *         --out=maploc-known.tsv --only-known --known=known_squares.txt
 */
object MapLocExtract {

    /** square_id = j * 128 + i. Verified against six rows of `map_square`. */
    private fun squareId(i: Int, j: Int) = j * 128 + i

    class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun u8(): Int = buf.get().toInt() and 0xff
        fun u16(): Int = ((u8() shl 8) or u8())
        /** The transform components are signed -- `map_loc.xform` holds values
         *  like -32643, so reading these unsigned would corrupt them. */
        fun s16(): Int = u16().let { if (it > 32767) it - 65536 else it }
        fun skip(n: Int) { buf.position(buf.position() + minOf(n, buf.remaining())) }

        /**
         * "Smart": one byte if the top bit is clear, otherwise two with the top
         * bit masked off. The 15-bit form is what 0x7FFF chains on.
         */
        fun smart(): Int {
            val peek = buf.get(buf.position()).toInt() and 0xff
            return if (peek < 0x80) u8() else (u16() and 0x7fff)
        }

        /** Chained: 0x7FFF means "add and keep reading". */
        fun chainedSmart(): Int {
            var total = 0
            while (true) {
                val v = smart()
                total += v
                if (v != 0x7fff) return total
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "maploc.tsv"
        val onlyKnown = args.any { it == "--only-known" }
        val knownArg = args.firstOrNull { it.startsWith("--known=") }?.substringAfter("=")

        val known: Set<Int> = if (onlyKnown && knownArg != null) {
            File(knownArg).readLines().mapNotNull { it.trim().toIntOrNull() }.toSet()
        } else emptySet()

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.MAPS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.MAPS}")
            return
        }

        var squares = 0
        var rows = 0L
        var missing = 0
        var failed = 0
        var leftover = 0
        var leftoverBytes = 0L

        File(out).bufferedWriter().use { w ->
            w.write("square_id\tplane\tx\ty\tloc_id\ttype\trot\tfile\txform\n")
            // 128 x 256 is the RS region grid; squares outside it do not exist.
            for (i in 0 until 128) {
                for (j in 0 until 256) {
                    val sid = squareId(i, j)
                    if (onlyKnown && known.isNotEmpty() && sid !in known) continue

                    // archive id == square id == (i or (j shl 7)). Confirmed
                    // against RuneTools CacheReader.cpp: `int archive = rx | (ry << 7)`,
                    // and independently against map_square, where square_id = j*128 + i.
                    // Index 5 carries NO name hashes (0 of 8,675 archives), so a
                    // name lookup can never resolve -- it must be by id.
                    val arc = try {
                        table.loadArchive(sid)
                    } catch (e: Exception) {
                        null
                    }
                    if (arc == null) {
                        missing++
                        continue
                    }
                    // File 0 is land placements, file 1 is water. File 3 is
                    // terrain and is not decoded here.
                    val parts = listOfNotNull(
                        arc.files[0]?.data?.let { 0 to it },
                        arc.files[1]?.data?.let { 1 to it }
                    )
                    if (parts.isEmpty()) {
                        missing++
                        continue
                    }

                    try {
                        var n = 0L
                        for ((fileId, part) in parts) {
                            val rd = Reader(ByteBuffer.wrap(part))
                            n += decodeInto(rd, sid, fileId) { row ->
                                w.write("${row.squareId}\t${row.plane}\t${row.x}\t${row.y}\t" +
                                        "${row.locId}\t${row.type}\t${row.rot}\t${row.fileId}\t${row.xform}\n")
                            }
                            // Bytes left over mean the stream desynced. A clean
                            // decode consumes the archive exactly.
                            if (rd.remaining() > 0) {
                                leftover++
                                leftoverBytes += rd.remaining()
                            }
                        }
                        rows += n
                        squares++
                    } catch (e: Exception) {
                        // A single malformed square must not abandon the sweep;
                        // record it and carry on.
                        failed++
                        System.err.println("square $sid failed: ${e.message}")
                    }
                }
            }
        }

        println("squares decoded : $squares")
        println("placements      : $rows")
        println("archives absent : $missing")
        println("squares failed  : $failed")
        println("desynced parts  : $leftover ($leftoverBytes bytes unread)")
        println("written         : $out")
    }

    /** One placement: a row of `map_loc`, plus the file it came from. */
    class LocRow(
        val squareId: Int, val plane: Int, val x: Int, val y: Int,
        val locId: Int, val type: Int, val rot: Int,
        /** 0 = land, 1 = water. Only land is loaded into `map_loc`. */
        val fileId: Int,
        val xform: String
    )

    /**
     * Decode one placement file, handing each row to [emit].
     *
     * The single implementation of the placement format. [main] writes the rows
     * as TSV; [MapBuilder] writes them to `map_loc`.
     */
    fun decodeInto(r: Reader, sid: Int, fileId: Int, emit: (LocRow) -> Unit): Long {
        var id = -1
        var count = 0L
        while (r.remaining() > 0) {
            val inc = r.chainedSmart()
            if (inc == 0) break
            id += inc

            // pos starts at 0, not -1. Verified against square 5032: with -1
            // every field matched `map_loc` except y, which was uniformly one
            // low. The `- 1` on the increment stays; only the seed changes.
            var pos = 0
            while (r.remaining() > 0) {
                val pinc = r.smart()
                if (pinc == 0) break
                pos += pinc - 1

                val plane = (pos shr 12) and 0x3
                val x = (pos shr 6) and 0x3f
                val y = pos and 0x3f

                if (r.remaining() < 1) return count
                val data = r.u8()
                val type = (data shr 2) and 0x1f
                val rot = data and 0x3

                // RS3 extra block, present whenever bit 0x80 of the attribute
                // byte is set -- which is nearly every placement. The widths are
                // NOT uniform: bit 0 carries eight bytes, and bit 0x10 is
                // mutually exclusive with the three above it. Treating it as
                // "one u16 per set bit" desynced 221 of 554 archives and left
                // a million bytes unread.
                //
                // This block IS the `xform` column. Bit 0x01's eight bytes are
                // the four-component rotation; 0x02/0x04/0x08 are the x/y/z
                // translation. That correspondence was read off the existing
                // data, where every xform value is of the form
                // {"rot":[a,b,c,d],"move":{"x":..,"y":..,"z":..}} with absent
                // parts omitted -- matching this bit layout exactly.
                //
                // 0x10 is a uniform scale; it is mutually exclusive with the
                // three per-axis bits above it, which together form `scale3`.
                // An axis whose bit is clear defaults to 128 -- the identity --
                // which is why stored scale3 values always carry all three
                // components even when only one differs.
                //
                // Key order below is rot, move, scale, scale3, matching how the
                // existing 42,925 rows are serialised, so the strings compare
                // byte-for-byte rather than needing to be re-parsed.
                var xform = ""
                if (data >= 0x80 && r.remaining() > 0) {
                    val flags = r.u8()
                    val parts = ArrayList<String>(4)
                    if ((flags and 0x01) != 0) {
                        parts.add("\"rot\":[${r.s16()},${r.s16()},${r.s16()},${r.s16()}]")
                    }
                    val move = ArrayList<String>(3)
                    if ((flags and 0x02) != 0) move.add("\"x\":${r.s16()}")
                    if ((flags and 0x04) != 0) move.add("\"y\":${r.s16()}")
                    if ((flags and 0x08) != 0) move.add("\"z\":${r.s16()}")
                    if (move.isNotEmpty()) parts.add("\"move\":{${move.joinToString(",")}}")
                    if ((flags and 0x10) != 0) {
                        parts.add("\"scale\":${r.s16()}")
                    } else if ((flags and 0xe0) != 0) {
                        val sx = if ((flags and 0x20) != 0) r.s16() else 128
                        val sy = if ((flags and 0x40) != 0) r.s16() else 128
                        val sz = if ((flags and 0x80) != 0) r.s16() else 128
                        parts.add("\"scale3\":{\"x\":$sx,\"y\":$sy,\"z\":$sz}")
                    }
                    if (parts.isNotEmpty()) xform = "{${parts.joinToString(",")}}"
                }

                emit(LocRow(sid, plane, x, y, id, type, rot, fileId, xform))
                count++
            }
        }
        return count
    }
}
