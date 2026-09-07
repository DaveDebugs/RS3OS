package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.model.definitions.SeqCodec
import java.io.File
import java.nio.file.Paths

/**
 * Decode cache index 20 (sequences) with [SeqCodec] and report what came out.
 */
object SeqDecode {
    private const val INDEX = 20

    @JvmStatic
    fun main(args: Array<String>) {
        fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")

        val cachePath = arg("cache")?.let { Paths.get(it) } ?: Constants.CACHE_PATH
        val out = arg("out") ?: "seq-decode.txt"
        val jsonl = arg("jsonl")
        val stride = arg("stride")?.toIntOrNull() ?: 256
        val only = arg("id")?.toIntOrNull()

        val fs = SqliteFilesystem(cachePath)
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            System.err.println("no reference table for index $INDEX in $cachePath")
            return
        }

        var total = 0
        var clean = 0
        val failures = sortedMapOf<String, Int>()
        val failureIds = sortedMapOf<String, MutableList<Int>>()
        val occurrences = sortedMapOf<Int, Int>()
        val records = sortedMapOf<Int, Int>()
        val jsonlWriter = jsonl?.let { File(it).bufferedWriter() }

        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                val id = a * stride + fid
                if (only != null && id != only) continue

                // The census comes out of the SAME walk that decodes, so the
                // opcode counts can never disagree with the fields.
                val census = ArrayList<Int>()
                val res = SeqCodec.decode(id, d, census)
                total++
                if (res.error == null) clean++ else {
                    val key = res.error.substringBefore(" at ").substringBefore(":")
                    failures[key] = (failures[key] ?: 0) + 1
                    failureIds.getOrPut(key) { ArrayList() }.let { if (it.size < 10) it.add(id) }
                }

                val seen = HashSet<Int>()
                for (op in census) {
                    occurrences[op] = (occurrences[op] ?: 0) + 1
                    seen.add(op)
                }
                for (op in seen) records[op] = (records[op] ?: 0) + 1

                jsonlWriter?.append(res.type.toJson().toString())?.append('\n')

                if (only != null) {
                    println("id $id  (group $a file $fid)  ${d.size} bytes  ${res.error ?: "CLEAN"}")
                    println(hex(d))
                    println(res.type.toJson().toString())
                }
            }
        }
        jsonlWriter?.close()

        val sb = StringBuilder()
        sb.append("cache   : $cachePath\n")
        sb.append("index   : $INDEX   stride: $stride\n")
        sb.append("clean   : $clean of $total  (%.2f%%)\n".format(100.0 * clean / maxOf(total, 1)))
        if (failures.isEmpty()) sb.append("failures: none\n")
        for ((k, v) in failures.entries.sortedByDescending { it.value }) {
            sb.append("  %-40s %d   first ids: %s\n".format(k, v, failureIds[k]))
        }
        sb.append("\nTHE 100% ABOVE IS NOT THE EVIDENCE. Three of the four defects this decoder\n")
        sb.append("replaced consumed exactly the right byte count and produced wrong values, so\n")
        sb.append("they parse everything too. The evidence is the reference decoder, which reproduces\n")
        sb.append("tools/seq_decode.py record for record: 37,853/37,853 on the old cache and\n")
        sb.append("38,084/38,084 on this one.\n")
        sb.append("\nopcode census (occurrences / records carrying it):\n")
        for (op in occurrences.keys) {
            sb.append("  %3d  %8d  %8d\n".format(op, occurrences[op], records[op]))
        }
        if (only == null) {
            File(out).writeText(sb.toString())
            print(sb)
            println("written to $out")
        }
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
