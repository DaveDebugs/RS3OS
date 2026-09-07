package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.api.stat.Stat
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.resources.FilesystemResources

/**
 * Group-level sweep: split every group into files and check the bytes arrive.
 *
 * `CacheSweep` decodes containers and stops there. That is a real check of a
 * real layer, and it reported 30 indices and 129 archives clean while the group
 * splitter was returning empty files for every multi-file group in the cache.
 * The layer it did not touch was the broken one.
 *
 * So this walks the next layer down: reference table -> container -> ARCHIVE
 * SPLIT -> per-file bytes, and counts files that arrive empty. Emptiness is the
 * signal precisely because the old failure was silent - nothing threw, every
 * file was simply zero bytes long.
 *
 * A group legitimately containing an empty file is possible, so this reports
 * proportions rather than asserting zero. What is NOT plausible is an index
 * where every file of every group is empty; that is the bug's signature and it
 * fails the run.
 */
object GroupSweep {
    private const val GROUPS_PER_INDEX = 12

    @JvmStatic
    fun main(args: Array<String>) {
        val fs = SqliteFilesystem(Constants.CACHE_PATH)

        println()
        println("=== group-level sweep: are file bytes actually arriving? ===")
        println("  %-5s %-8s %-8s %-9s %-9s %s".format(
            "index", "groups", "sampled", "files", "empty", "note"))

        var totalFiles = 0
        var totalEmpty = 0
        var suspiciousIndices = 0
        val notes = ArrayList<String>()

        for (index in 0 until fs.numIndices()) {
            val table = try {
                fs.getReferenceTable(index) ?: continue
            } catch (e: Exception) {
                notes.add("index $index: reference table failed - ${e.javaClass.simpleName}")
                continue
            }

            val ids = table.archives.keys.toList()
            if (ids.isEmpty()) continue

            var sampled = 0
            var files = 0
            var empty = 0
            var multiFileGroupsSeen = 0

            val step = maxOf(1, ids.size / GROUPS_PER_INDEX)
            var i = 0
            while (i < ids.size && sampled < GROUPS_PER_INDEX) {
                val gid = ids[i]
                val raw = fs.read(index, gid)
                if (raw != null) {
                    try {
                        val archive = table.archives[gid]!!
                        val data = Container.decode(raw).data
                        archive.decode(java.nio.ByteBuffer.wrap(data))
                        val members = archive.files.values.toList()
                        if (members.size > 1) multiFileGroupsSeen++
                        members.forEach { f ->
                            files++
                            if (f.data.isEmpty()) empty++
                        }
                        sampled++
                    } catch (e: Exception) {
                        notes.add("index $index group $gid: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                i += step
            }

            totalFiles += files
            totalEmpty += empty

            // The bug's signature: multi-file groups present, and every single
            // file empty. One empty file is data; all of them is framing.
            val allEmpty = files > 0 && empty == files && multiFileGroupsSeen > 0
            if (allEmpty) suspiciousIndices++

            println("  %-5d %-8d %-8d %-9d %-9d %s".format(
                index, ids.size, sampled, files, empty,
                if (allEmpty) "ALL EMPTY - group splitting is broken here"
                else if (empty > 0) "%.1f%% empty".format(100.0 * empty / files)
                else ""))
        }

        println()
        println("=== totals ===")
        println("  files split out       : %d".format(totalFiles))
        println("  of those, empty       : %d (%.2f%%)".format(
            totalEmpty, if (totalFiles == 0) 0.0 else 100.0 * totalEmpty / totalFiles))
        println("  indices entirely empty: %d".format(suspiciousIndices))

        if (notes.isNotEmpty()) {
            println()
            println("=== notes (first 15) ===")
            notes.take(15).forEach { println("  $it") }
        }

        // --- what the group fix restored -----------------------------------
        // Stat.reload reads skill display names out of enum 680. Before the
        // group splitter understood this cache's layout, that enum decoded to
        // zero entries and every name silently fell back to a default. Printing
        // them is the cheapest possible proof the fix reaches real behaviour and
        // not just a decoder statistic.
        println()
        println("=== skill display names (enum 680, via Stat.reload) ===")
        FilesystemResources(fs, Constants.RESOURCE_PATH)
        Stat.reload()
        val named = Stat.values().filter {
            val d = runCatching { it.display }.getOrNull()
            d != null && d != "null" && d.isNotBlank()
        }
        println("  named: %d of %d".format(named.size, Stat.values().size))
        println("  " + Stat.values().joinToString(", ") {
            runCatching { it.display }.getOrNull() ?: "<unset>"
        })

        println()
        println("=== verdict ===")
        val ok = suspiciousIndices == 0 && named.size == Stat.values().size
        if (ok) {
            println("  every sampled index yields real file bytes, and all %d skills resolved a name"
                .format(Stat.values().size))
        } else {
            if (suspiciousIndices > 0)
                println("  %d index(es) produced only empty files - group splitting is wrong for them"
                    .format(suspiciousIndices))
            if (named.size != Stat.values().size)
                println("  only %d of %d skills resolved a display name"
                    .format(named.size, Stat.values().size))
        }
        System.exit(if (ok) 0 else 1)
    }
}
