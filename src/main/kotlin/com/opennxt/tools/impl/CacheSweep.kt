package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.sqlite.SqliteFilesystem

/**
 * Reads every index in the cache through OpenNXT's own filesystem layer and
 * reports what decodes.
 *
 * The ZLB container envelope was found by accident: the server happened to touch
 * one index during prefetch generation and fell over. Nothing had checked the
 * rest. This sweeps all of them deliberately, so a second surprise of that kind
 * is found here rather than three hours into a boot sequence.
 *
 * It exercises the real path - readReferenceTable -> Container.decode ->
 * ReferenceTable.decode, then a sample of archives through the same container
 * decoder - rather than reading bytes and eyeballing them. A test that does not
 * use the production path proves nothing about the production path.
 *
 * The control at the end matters as much as the sweep: it reads an index that
 * cannot exist, and the run FAILS if that returns data. Without it, "everything
 * decoded" could equally mean "the reader returns success for anything".
 */
object CacheSweep {
    private const val ARCHIVES_PER_INDEX = 5

    @JvmStatic
    fun main(args: Array<String>) {
        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        println()
        println("=== per-index sweep ===")
        println("  %-5s %-10s %-9s %-9s %s".format("index", "table", "archives", "sampled", "note"))

        var indicesOk = 0
        var indicesFailed = 0
        var archivesOk = 0
        var archivesFailed = 0
        val failures = ArrayList<String>()

        for (index in 0 until fs.numIndices()) {
            if (fs.readReferenceTable(index) == null) continue   // absent, not a failure

            val table = try {
                fs.getReferenceTable(index)
            } catch (e: Exception) {
                indicesFailed++
                failures.add("index $index reference table: ${e.javaClass.simpleName}: ${e.message}")
                println("  %-5d %-10s %-9s %-9s %s".format(index, "FAIL", "-", "-", e.javaClass.simpleName))
                continue
            }

            if (table == null) {
                indicesFailed++
                failures.add("index $index reference table decoded to null")
                println("  %-5d %-10s %-9s %-9s %s".format(index, "null", "-", "-", "decoded to null"))
                continue
            }

            indicesOk++
            val ids = table.archives.keys.toList()

            // Sample archives spread across the index rather than the first few:
            // the first archive of an index is the one most likely to have been
            // exercised already, and a format that changes partway through would
            // hide behind it.
            var sampled = 0
            var localFail = 0
            if (ids.isNotEmpty()) {
                val step = maxOf(1, ids.size / ARCHIVES_PER_INDEX)
                var i = 0
                while (i < ids.size && sampled < ARCHIVES_PER_INDEX) {
                    val id = ids[i]
                    val raw = fs.read(index, id)
                    if (raw != null) {
                        try {
                            Container.decode(raw)
                            archivesOk++
                        } catch (e: Exception) {
                            archivesFailed++
                            localFail++
                            if (failures.size < 40)
                                failures.add("index $index archive $id: ${e.javaClass.simpleName}: ${e.message}")
                        }
                        sampled++
                    }
                    i += step
                }
            }

            println("  %-5d %-10s %-9d %-9d %s".format(
                index, "ok", ids.size, sampled,
                if (localFail == 0) "" else "$localFail archive(s) FAILED"))
        }

        println()
        println("=== totals ===")
        println("  indices decoded        : %d".format(indicesOk))
        println("  indices failed         : %d".format(indicesFailed))
        println("  archives decoded       : %d".format(archivesOk))
        println("  archives failed        : %d".format(archivesFailed))

        if (failures.isNotEmpty()) {
            println()
            println("=== failures (first ${minOf(failures.size, 20)}) ===")
            failures.take(20).forEach { println("  $it") }
        }

        // --- control -------------------------------------------------------
        // An index number no cache has. If this returns anything, the sweep
        // above is meaningless because the reader would report success for
        // input that cannot exist.
        println()
        println("=== control ===")
        val bogus = fs.readReferenceTable(250)
        val controlOk = bogus == null
        println("  index 250 (cannot exist) -> %s  %s".format(
            if (bogus == null) "null" else "DATA", if (controlOk) "ok" else "BROKEN"))

        println()
        println("=== verdict ===")
        val ok = indicesFailed == 0 && archivesFailed == 0 && controlOk
        if (ok) {
            println("  every index and sampled archive decoded, and the control shows reads can miss")
        } else {
            println("  FAILURES above")
        }
        System.exit(if (ok) 0 else 1)
    }
}
