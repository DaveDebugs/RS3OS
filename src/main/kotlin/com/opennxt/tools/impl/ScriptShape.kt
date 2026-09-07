package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.nio.ByteBuffer

/**
 * Test candidate CS2 instruction encodings against all 21,110 clientscripts.
 *
 * WHY A TESTER RATHER THAN A DECODER
 *
 * Index 12 has no terminator to land on and no known-good table to diff
 * against, so neither gate that verified the other formats is available. What
 * IS available is a different self-check: the instruction body has to walk from
 * its start and land EXACTLY on the trailer boundary. A wrong operand width
 * overshoots or undershoots, and across 21,110 files the right encoding is the
 * one that lands cleanly on nearly all of them while a wrong one lands on
 * almost none.
 *
 * So this scores hypotheses instead of asserting one. Read off the samples in
 * [ScriptProbe]:
 *
 *   - `07 aa 00 00 00 00` is the last instruction of every sampled script,
 *     which reads as (u16 opcode 0x07aa, 4-byte operand) -- a RETURN.
 *   - the same opcode takes two operand shapes:
 *         05 37 00 00 00 00 01          int 1
 *         05 37 02 4f 77 6e 65 64 00    the string "Owned"
 *     so the operand appears to be a 1-byte TYPE tag followed by a typed value.
 *
 * Both are hypotheses. The numbers below are what decide between them.
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.ScriptShape
 */
object ScriptShape {

    /** A candidate encoding: how wide the opcode is and how the operand is read. */
    private class Shape(val name: String, val walk: (ByteBuffer, Int) -> Int)

    /**
     * Walk the body from `start` and return the offset it lands on, or -1 if it
     * ran off the end. The caller compares that against the trailer boundary.
     */
    private fun walkTyped(b: ByteBuffer, start: Int, opWidth: Int, limit: Int): Int {
        var p = start
        var guard = 0
        while (p < limit) {
            if (guard++ > 200000) return -1
            if (p + opWidth > limit) return -1
            p += opWidth
            if (p >= limit) return -1
            val type = b.get(p).toInt() and 0xff
            p += 1
            when (type) {
                0, 1 -> p += 4                      // int
                2 -> {                              // NUL-terminated string
                    while (p < limit && b.get(p).toInt() != 0) p++
                    if (p >= limit) return -1
                    p += 1
                }
                else -> return -1
            }
        }
        return p
    }

    private fun walkFixed(b: ByteBuffer, start: Int, opWidth: Int, operand: Int, limit: Int): Int {
        var p = start
        var guard = 0
        while (p < limit) {
            if (guard++ > 200000) return -1
            p += opWidth + operand
            if (p > limit) return -1
        }
        return p
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.CLIENTSCRIPTS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.CLIENTSCRIPTS}")
            return
        }

        val files = ArrayList<ByteArray>()
        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((_fid, f) in arc.files) f.data?.let { files.add(it) }
        }
        println("index ${Index.CLIENTSCRIPTS}: ${files.size} script(s)")

        // Candidate trailer sizes. The body must end where the trailer begins,
        // so this is swept alongside the encoding rather than fixed in advance.
        val trailerSizes = listOf(12, 13, 14, 15, 16, 17, 18, 19, 20)
        // Candidate header sizes -- how many bytes precede the first instruction.
        val headers = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)

        val shapes = listOf(
            Shape("u16 op + typed operand") { b, lim -> walkTyped(b, 0, 2, lim) },
            Shape("u8  op + typed operand") { b, lim -> walkTyped(b, 0, 1, lim) },
            Shape("u16 op + int32") { b, lim -> walkFixed(b, 0, 2, 4, lim) },
            Shape("u16 op + int16") { b, lim -> walkFixed(b, 0, 2, 2, lim) }
        )

        println("\n%-26s %-8s %-8s %8s %7s" % arrayOf("shape", "header", "trailer", "landed", "rate"))
        var best = ""
        var bestN = 0
        for (shape in shapes) {
            for (h in headers) {
                for (t in trailerSizes) {
                    var ok = 0
                    for (d in files) {
                        val limit = d.size - t
                        if (limit <= h) continue
                        val b = ByteBuffer.wrap(d)
                        // Re-walk from the header offset by slicing the view.
                        val landed = walkFrom(shape, b, h, limit)
                        if (landed == limit) ok++
                    }
                    if (ok > bestN) {
                        bestN = ok
                        best = "%-26s h=%-6d t=%-6d %8d %6.2f%%".format(
                            shape.name, h, t, ok, 100.0 * ok / files.size)
                    }
                    if (ok * 100 / files.size >= 5) {
                        println("%-26s %-8d %-8d %8d %6.2f%%".format(
                            shape.name, h, t, ok, 100.0 * ok / files.size))
                    }
                }
            }
        }
        println("\nbest: $best")
        println("(anything under a few percent is noise -- a wrong encoding still")
        println(" lands on the boundary occasionally by chance)")
    }

    private fun walkFrom(shape: Shape, b: ByteBuffer, start: Int, limit: Int): Int {
        return when {
            shape.name.startsWith("u16 op + typed") -> walkTyped(b, start, 2, limit)
            shape.name.startsWith("u8  op + typed") -> walkTyped(b, start, 1, limit)
            shape.name.startsWith("u16 op + int32") -> walkFixed(b, start, 2, 4, limit)
            else -> walkFixed(b, start, 2, 2, limit)
        }
    }

    private operator fun String.rem(args: Array<String>): String = this.format(*args)
}
