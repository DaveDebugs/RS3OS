package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.Index
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import java.io.File
import java.nio.ByteBuffer

/**
 * Decode varbit definitions out of index 2, archive 69.
 *
 * WHY THIS EXISTS
 *
 * `achievements` cannot move to the cache the server serves until this does.
 * Decoding the current cache's achievements produces 137 requirements naming
 * varbits the database has never heard of, and the achievement loader's gate
 * correctly refuses on that -- config tables have to move as a set, in
 * dependency order, and this is the dependency.
 *
 * WHERE IT LIVES
 *
 * Index 2 (configs), archive 69, one file per varbit, the FILE ID being the
 * varbit id. The database's own `varbits._group` column already says 69 on every
 * row, so the addressing is confirmed by the table rather than assumed.
 *
 * FORMAT
 *
 * A three-opcode walk, the smallest in this cache:
 *
 *     1   domain (u8) then varp (u16)
 *     2   lsb (u8) then msb (u8)
 *     16  a flag, no payload
 *     0   ends the record
 *
 * Anything else stops the walk, because an unknown opcode here has an unknown
 * width and guessing would silently corrupt every field after it. A record that
 * does not end up with domain 0 and all three values set is skipped rather than
 * half-loaded -- that is the reference decoder's own rule.
 *
 * Ported from RuneTools `CacheReader.cpp` (`LoadVarbitMapLocked`).
 *
 * HOW IT IS CHECKED
 *
 * 60,682 rows already exist, extracted from the `RuneScape` cache. Decoding that
 * cache has to hand them back -- varp, bit_start and bit_end on every row --
 * before this is trusted against the served cache.
 *
 *     java -Dopennxt.cache=<cache> -cp <cp> com.opennxt.tools.impl.VarbitExtract
 */
object VarbitExtract {

    private const val ARCHIVE = 69

    private class Reader(val buf: ByteBuffer) {
        fun remaining() = buf.remaining()
        fun u8(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else 0
        fun u16(): Int = (u8() shl 8) or u8()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")
            ?: "varbits.tsv"

        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val table = fs.getReferenceTable(Index.CONFIG)
        if (table == null) {
            System.err.println("no reference table for index ${Index.CONFIG}")
            return
        }
        val archive = try { table.loadArchive(ARCHIVE) } catch (e: Exception) { null }
        if (archive == null) {
            System.err.println("index ${Index.CONFIG} has no archive $ARCHIVE")
            return
        }

        var files = 0
        var kept = 0
        var skipped = 0
        val stops = sortedMapOf<Int, Int>()

        File(out).bufferedWriter().use { w ->
            w.write("id\tvarid\tlsb\tmsb\tdomain\n")
            for ((fid, f) in archive.files) {
                val d = f.data ?: continue
                files++
                val r = Reader(ByteBuffer.wrap(d))
                var domain = -1; var varp = -1; var lsb = -1; var msb = -1
                while (r.remaining() > 0) {
                    val op = r.u8()
                    if (op == 0) break
                    when (op) {
                        1 -> { domain = r.u8(); varp = r.u16() }
                        2 -> { lsb = r.u8(); msb = r.u8() }
                        16 -> {}
                        else -> { stops[op] = (stops[op] ?: 0) + 1; break }
                    }
                }
                // RuneTools keeps only domain 0, because that is all its panel
                // needed. This database keeps EVERY domain and packs the pair
                // into one column as (domain shl 16) or varp -- which is how
                // `varid` values like 65589 (domain 1, varp 53) and 327680
                // (domain 5, varp 0) come to sit in a column that otherwise
                // never exceeds 65535. Reproducing the table means reproducing
                // that packing, so the filter is on "was it read at all", not
                // on the domain.
                if (varp < 0 || lsb < 0 || msb < 0) {
                    skipped++
                    continue
                }
                kept++
                val varid = (domain shl 16) or varp
                w.write("$fid\t$varid\t$lsb\t$msb\t$domain\n")
            }
        }

        println("index ${Index.CONFIG} archive $ARCHIVE varbits")
        println("  files            : $files")
        println("  decoded          : $kept")
        println("  skipped          : $skipped  (a field was never set)")
        println("  stopped on opcode: " +
                stops.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }.ifEmpty { "(none)" })
        println("  -> $out")
    }
}
