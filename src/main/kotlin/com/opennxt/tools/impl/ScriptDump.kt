package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Dump every clientscript to one flat file so the format can be solved offline.
 *
 * The CS2 encoding is not going to be settled by one pass -- operand width is
 * per-opcode, so the table and the walk have to be solved against each other.
 * That is an iterative search, and iterating it through a Kotlin rebuild each
 * round would be the slowest possible way to do it. Dump once, solve in a
 * script, and only come back here when the answer is known.
 *
 * Format, big-endian:  repeated { int archiveId, int length, length bytes }
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.ScriptDump
 */
object ScriptDump {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "scripts.bin"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.CLIENTSCRIPTS)
        if (table == null) {
            System.err.println("no reference table for index ${Index.CLIENTSCRIPTS}")
            return
        }

        var n = 0
        var bytes = 0L
        DataOutputStream(BufferedOutputStream(File(out).outputStream(), 1 shl 20)).use { w ->
            for (a in table.archives.keys.sorted()) {
                val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
                for ((_fid, f) in arc.files) {
                    val d = f.data ?: continue
                    w.writeInt(a)
                    w.writeInt(d.size)
                    w.write(d)
                    n++
                    bytes += d.size
                }
            }
        }
        println("wrote $n script(s), $bytes byte(s) -> $out")
    }
}
