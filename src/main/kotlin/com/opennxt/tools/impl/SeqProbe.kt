package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File

/**
 * Look at index 20 before writing a decoder for it.
 */
object SeqProbe {

    private const val INDEX = 20

    @JvmStatic
    fun main(args: Array<String>) {
        val hexCount = args.firstOrNull { it.startsWith("--hex=") }
            ?.substringAfter("=")?.toIntOrNull() ?: 3
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "seq-probe.txt"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            System.err.println("no reference table for index $INDEX")
            return
        }

        val archives = table.archives.keys.sorted()
        var files = 0L
        var bytes = 0L
        val perArchive = sortedMapOf<Int, Int>()
        val sizes = ArrayList<Int>()
        val firstOpcode = sortedMapOf<Int, Int>()
        val samples = HashMap<Int, ArrayList<Triple<Int, Int, ByteArray>>>()

        for (a in archives) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            perArchive[arc.files.size] = (perArchive[arc.files.size] ?: 0) + 1
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                files++
                bytes += d.size
                sizes.add(d.size)
                if (d.isEmpty()) continue
                val op = d[0].toInt() and 0xff
                firstOpcode[op] = (firstOpcode[op] ?: 0) + 1
                samples.getOrPut(op) { ArrayList() }.add(Triple(d.size, a * 128 + fid, d))
            }
        }

        sizes.sort()
        println("index $INDEX")
        println("  archives            : ${archives.size}")
        println("  files               : $files")
        println("  total bytes         : $bytes")
        println("  files per archive   : " +
                perArchive.entries.joinToString(", ") { "${it.key}->${it.value}" })
        if (sizes.isNotEmpty()) {
            println("  size min/med/max    : ${sizes.first()} / ${sizes[sizes.size / 2]} / ${sizes.last()}")
        }
        println("  distinct first bytes: ${firstOpcode.size}")
        println("  first-byte histogram: " +
                firstOpcode.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" })

        // payload, so this block is measuring divisibility, not a field width.
        // It opens 20,165 of the files because it is the commonest first field,
        // NOT because the walk breaks there - the walk broke on opcode 13.
        // Left running as the worked example.
        // If a file holds ONLY an op-15 record then
        //     size = 1 (opcode) + 1 (count) + count * K + 1 (terminator)
        // so K = (size - 3) / count. Tabulating that over every op-15 file says
        // whether K is even constant -- if it is not, op 15 is not a flat array
        // of fixed-width items and no single width will ever fix it.
        val kHist = sortedMapOf<Int, Int>()
        val kBad = sortedMapOf<Int, Int>()
        for ((size, _, d) in samples[15] ?: emptyList()) {
            if (d.size < 3) continue
            val count = d[1].toInt() and 0xff
            if (count == 0) continue
            val rem = size - 3
            if (rem % count == 0) kHist[rem / count] = (kHist[rem / count] ?: 0) + 1
            else kBad[count] = (kBad[count] ?: 0) + 1
        }
        println()
        println("  op15: (size-3)/count when it divides evenly:")
        println("    " + kHist.entries.sortedByDescending { it.value }.take(14)
            .joinToString(", ") { "K=${it.key} x${it.value}" })
        println("  op15: files where it does NOT divide evenly: ${kBad.values.sum()}")

        val sb = StringBuilder()
        for ((op, bucket) in samples.toSortedMap()) {
            bucket.sortBy { it.first }
            sb.append("========== leading opcode ").append(op)
                .append("  (").append(bucket.size).append(" files) ==========\n")
            for ((size, id, d) in bucket.take(hexCount)) {
                sb.append("id=").append(id).append("  ").append(size).append(" bytes\n")
                sb.append(hex(d)).append("\n")
            }
            sb.append("\n")
        }
        File(out).writeText(sb.toString())
        println("  sample hex          : $out")
    }

    private fun hex(d: ByteArray): String {
        val sb = StringBuilder()
        for (i in d.indices step 16) {
            sb.append("  %04x  ".format(i))
            val end = minOf(i + 16, d.size)
            for (j in i until end) sb.append("%02x ".format(d[j]))
            repeat(16 - (end - i)) { sb.append("   ") }
            sb.append(" |")
            for (j in i until end) {
                val c = d[j].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
        }
        return sb.toString()
    }
}
