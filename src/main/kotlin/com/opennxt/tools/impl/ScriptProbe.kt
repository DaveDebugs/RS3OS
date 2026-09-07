package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Look at index 12 (clientscripts) before trying to decode it.
 */
object ScriptProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "script-probe.txt"
        val hexCount = args.firstOrNull { it.startsWith("--hex=") }
            ?.substringAfter("=")?.toIntOrNull() ?: 4

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.CLIENTSCRIPTS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.CLIENTSCRIPTS}")
            return
        }

        var files = 0
        var bytes = 0L
        val sizes = ArrayList<Int>()
        val perArchive = sortedMapOf<Int, Int>()
        val samples = ArrayList<Triple<Int, Int, ByteArray>>()
        val lastByte = sortedMapOf<Int, Int>()

        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            perArchive[arc.files.size] = (perArchive[arc.files.size] ?: 0) + 1
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                files++
                bytes += d.size
                sizes.add(d.size)
                if (d.isNotEmpty()) {
                    val lb = d[d.size - 1].toInt() and 0xff
                    lastByte[lb] = (lastByte[lb] ?: 0) + 1
                }
                if (d.size in 12..140) samples.add(Triple(d.size, a, d))
            }
        }

        sizes.sort()
        println("index ${Index.CLIENTSCRIPTS} clientscripts")
        println("  archives          : ${table.archives.size}")
        println("  files             : $files")
        println("  total bytes       : $bytes")
        println("  files per archive : " +
                perArchive.entries.joinToString(", ") { "${it.key}->${it.value}" })
        if (sizes.isNotEmpty()) {
            println("  size min/med/max  : ${sizes.first()} / ${sizes[sizes.size / 2]} / ${sizes.last()}")
        }
        println("  last byte, top 8  : " +
                lastByte.entries.sortedByDescending { it.value }.take(8)
                    .joinToString(", ") { "0x%02x x%d".format(it.key, it.value) })

        // The trailer hypothesis, tested rather than assumed. In the RS2 shape
        // the final two bytes give the switch-table length; the fixed trailer is
        // 12 bytes of counts before that. If that is right for this build then
        // for most files
        //     bodyEnd = size - 2 - switchLen - 12
        // must be a sane positive offset, and the instruction count stored there
        // must be plausible against the file's size.
        var plausible = 0
        var negative = 0
        var wild = 0
        for ((size, _a, d) in samples) {
            val b = ByteBuffer.wrap(d)
            val switchLen = ((d[size - 2].toInt() and 0xff) shl 8) or (d[size - 1].toInt() and 0xff)
            val end = size - 2 - switchLen - 12
            when {
                end < 0 -> negative++
                end > size -> wild++
                else -> {
                    val n = b.getInt(end)
                    if (n in 1..(size)) plausible++ else wild++
                }
            }
        }
        println()
        println("  trailer hypothesis over ${samples.size} small file(s):")
        println("    plausible instruction count : $plausible")
        println("    trailer runs off the front  : $negative")
        println("    count is not size-plausible : $wild")

        val sb = StringBuilder()
        samples.sortBy { it.first }
        // Spread the samples across the SIZE RANGE. Taking the smallest gives
        // three byte-identical 26-byte stubs, which show the skeleton and
        // nothing about how a real script is laid out.
        val spread = if (samples.isEmpty()) emptyList() else
            (0 until hexCount).map { samples[(it * (samples.size - 1)) / maxOf(1, hexCount - 1)] }
        for ((size, a, d) in spread) {
            sb.append("archive=").append(a).append("  ").append(size).append(" bytes\n")
            sb.append("  HEAD\n").append(hex(d, 0, minOf(64, size)))
            if (size > 64) sb.append("  TAIL\n").append(hex(d, maxOf(0, size - 48), size))
            sb.append("\n")
        }
        File(out).writeText(sb.toString())
        println("  sample hex        : $out")
    }

    private fun hex(d: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < to) {
            val end = minOf(i + 16, to)
            sb.append("  %04x  ".format(i))
            for (j in i until end) sb.append("%02x ".format(d[j]))
            repeat(16 - (end - i)) { sb.append("   ") }
            sb.append(" |")
            for (j in i until end) {
                val c = d[j].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            i = end
        }
        return sb.toString()
    }
}
