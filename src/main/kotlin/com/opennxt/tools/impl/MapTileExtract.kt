package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode terrain tiles out of the map archives.
 *
 * WHY THIS EXISTS
 *
 * `map_square` already has five columns for this -- heights, flags, underlay,
 * overlay, shape -- and all five are NULL for all 5,078 rows. The table is a
 * destination that was never filled. Only `planes` carries a value.
 *
 * WHERE IT LIVES
 *
 * Index 5, archive id == square id (same addressing [MapLocExtract] uses and
 * verified against), file **3**. Files 0 and 1 are land and water placements
 * and belong to `map_loc`; file 3 is terrain and belongs here.
 *
 * FORMAT
 *
 * A flat walk of 4 x 64 x 64 tiles in plane, x, y order -- no lengths, no
 * terminators, so a single misread byte corrupts everything after it. Per tile
 * one flag byte, then only the fields its bits select:
 *
 *     0x1 -> shape (byte) and overlay id (unsigned smart)
 *     0x2 -> settings (byte)   bit 0x1 = blocked/void, 0x2 = bridge
 *     0x4 -> underlay id (unsigned smart)
 *     0x8 -> height (byte, or unsigned short in format 936)
 *
 * Format 936 is flagged by a five-byte "jagx" header and widens heights
 * to 16 bits. Absent fields keep their defaults: settings 0, heights INT16_MIN,
 * underlay/overlay/shape -1.
 *
 * After the tile walk the file continues with a non-members bitmask and an
 * environment section. Neither is consumed here -- `map_env` is already fully
 * populated from another source, so there is nothing to gain and a partially
 * understood opcode format to get wrong. Trailing bytes are reported, not
 * parsed. Running SHORT mid-walk is the real failure signal.
 *
 * Ported from RuneTools `src/cache/MapTiles.cpp`.
 *
 * OUTPUT
 *
 * One binary stream, big-endian, per square:
 *
 *     square_id  int32
 *     settings   16384 x int8      (4*64*64)
 *     heights    16384 x int16
 *     underlay   16384 x int16
 *     overlay    16384 x int16
 *     shape      16384 x int8
 *
 * flat index = (plane * 64 + x) * 64 + y, matching the decode order.
 *
 * VERIFY BEFORE LOADING
 *
 * `map_blocked` is already populated -- 20,312 rows of 512-byte bitmaps, one
 * per square and plane, 7,695 of them non-zero. Those bits are derivable from
 * settings bit 0x1, so they are known-good data this decode can be checked
 * against before a single column is written. `tools/load_maptiles.py` does that
 * check and refuses to load if it fails.
 */
object MapTileExtract {

    private const val TILES = 4 * 64 * 64

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun skip(n: Int) { buf.position(buf.position() + minOf(n, buf.remaining())) }
        fun u8(): Int = buf.get().toInt() and 0xff
        fun u16(): Int = ((u8() shl 8) or u8())

        /** `>= 0x80` takes two bytes less 0x8000; otherwise one. */
        fun smart(): Int {
            if (remaining() <= 0) return 0
            val peek = buf.get(buf.position()).toInt() and 0xff
            return if (peek >= 0x80) u16() - 0x8000 else u8()
        }
    }

    /** Thrown when the walk runs out of bytes -- a real misparse, not a short file. */
    private class Underrun : Exception()

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "maptiles.bin"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.MAPS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.MAPS}")
            return
        }

        var squares = 0
        var missing = 0
        var short = 0
        var trailerBytes = 0L
        var fmt936 = 0

        DataOutputStream(BufferedOutputStream(File(out).outputStream(), 1 shl 20)).use { w ->
            for (sid in 0 until 32768) {
                val arc = try { table.loadArchive(sid) } catch (e: Exception) { null }
                val data = arc?.files?.get(3)?.data
                if (data == null) {
                    missing++
                    continue
                }

                val sq = decodeSquare(data)
                if (sq == null) {
                    // Ran short mid-walk. Everything decoded so far is suspect,
                    // so the square is dropped rather than written half-right.
                    short++
                    System.err.println("square $sid ran short mid-walk")
                    continue
                }
                if (sq.wide) fmt936++
                trailerBytes += sq.trailingBytes

                w.writeInt(sid)
                w.write(sq.settings)
                for (v in sq.heights) w.writeShort(v.toInt())
                for (v in sq.underlay) w.writeShort(v.toInt())
                for (v in sq.overlay) w.writeShort(v.toInt())
                w.write(sq.shape)
                squares++
            }
        }

        println("squares decoded : $squares")
        println("format 936      : $fmt936")
        println("no file 3       : $missing")
        println("ran short       : $short")
        println("trailer bytes   : $trailerBytes (env section, not parsed)")
        println("written         : $out")
    }

    /**
     * One decoded map square: five parallel arrays over the 4 x 64 x 64 tile
     * walk, indexed `(plane * 64 + x) * 64 + y`.
     */
    class Square(
        val settings: ByteArray, val heights: ShortArray, val underlay: ShortArray,
        val overlay: ShortArray, val shape: ByteArray,
        /** True when the square used format 936, which widens heights to 16 bits. */
        val wide: Boolean,
        /** Bytes after the tile walk -- the environment section, which is not parsed. */
        val trailingBytes: Int
    )

    /**
     * Decode index 5 file 3 for one square, or null when the walk runs short.
     *
     * The single implementation of the tile format. [main] writes it to a flat
     * file; [MapBuilder] writes it to the map tables.
     */
    fun decodeSquare(data: ByteArray): Square? {
        val r = Reader(ByteBuffer.wrap(data))
        // Format 936 prefixes "jagx" and widens heights to 16 bits.
        val wide = data.size >= 5 && data[0] == 'j'.code.toByte() &&
                data[1] == 'a'.code.toByte() && data[2] == 'g'.code.toByte() &&
                data[3] == 'x'.code.toByte() && data[4] == 0x01.toByte()
        if (wide) r.skip(5)

        val settings = ByteArray(TILES)
        val heights = ShortArray(TILES) { Short.MIN_VALUE }
        val underlay = ShortArray(TILES) { -1 }
        val overlay = ShortArray(TILES) { -1 }
        val shape = ByteArray(TILES) { -1 }

        try {
            decode(r, wide, settings, heights, underlay, overlay, shape)
        } catch (e: Underrun) {
            return null
        }
        return Square(settings, heights, underlay, overlay, shape, wide, r.remaining())
    }

    private fun decode(
        r: Reader, wide: Boolean, settings: ByteArray, heights: ShortArray,
        underlay: ShortArray, overlay: ShortArray, shape: ByteArray
    ) {
        // Fixed 4 x 64 x 64 walk in plane, x, y order. There is no terminator
        // and no per-tile length, so the loop bounds ARE the format.
        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    if (r.remaining() <= 0) throw Underrun()
                    val i = (plane * 64 + x) * 64 + y
                    val flags = r.u8()
                    if ((flags and 0x1) != 0) {
                        shape[i] = r.u8().toByte()
                        overlay[i] = r.smart().toShort()
                    }
                    if ((flags and 0x2) != 0) settings[i] = r.u8().toByte()
                    if ((flags and 0x4) != 0) underlay[i] = r.smart().toShort()
                    if ((flags and 0x8) != 0) {
                        heights[i] = (if (wide) r.u16() else r.u8()).toShort()
                    }
                }
            }
        }
    }
}
