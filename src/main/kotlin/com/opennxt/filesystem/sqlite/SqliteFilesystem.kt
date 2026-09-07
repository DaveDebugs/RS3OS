package com.opennxt.filesystem.sqlite

import com.opennxt.ext.toFilesystemHash
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

class SqliteFilesystem(path: Path) : Filesystem(path) {
    val logger = KotlinLogging.logger { }

    var indices: Array<SqliteIndexFile?>

    init {
        if (!Files.exists(path))
            Files.createDirectories(path)

        logger.info { "Opening SQLite filesystem from $path" }

        // Discover indices from what is actually on disk, rather than counting
        // upwards from js5-0 and stopping at the first gap. The original was:
        //
        //     var count = 0
        //     while (count < 255 && Files.exists(path.resolve("js5-$count.jcache"))) count++
        //
        // which assumes caches are contiguous from zero. Real NXT caches are not.
        // A current live cache (build NXT-RSC285-BWXR, 8.4 GB) holds indices
        //
        //     1 2 3 5 8 10 12 13 14 16 17 18 19 20 21 22 23 24 26 27 28 29
        //     40 41 42 47 48 49 52 54 55 56 57 58 59 60 61 62 65 66
        //
        // There is no js5-0 at all, so that loop terminates immediately and
        // reports "Discovered a total of 0 indices" for a perfectly good cache.
        // Worse, the failure surfaces somewhere else entirely - as an
        // IndexOutOfBoundsException while generating the prefetch table - so the
        // symptom points nowhere near the cause. Sparse and partial caches are
        // normal; the client downloads what it needs.
        //
        // Glob instead, size the array to the highest id seen, and leave gaps
        // null. Index NUMBERING IS PRESERVED, which is the part that matters:
        // every caller addresses indices by real id (maps is 5, clientscripts is
        // 12), so compacting them would silently misroute every read.
        val found = sortedSetOf<Int>()
        Files.newDirectoryStream(path, "js5-*.jcache").use { stream ->
            stream.forEach {
                val id = it.fileName.toString()
                    .removePrefix("js5-").removeSuffix(".jcache").toIntOrNull()
                if (id != null && id in 0..254) found.add(id)
            }
        }

        if (found.isEmpty()) {
            logger.warn { "No js5-*.jcache files found in $path - filesystem is empty." }
            indices = arrayOfNulls(0)
        } else {
            val highest = found.last()
            logger.info { "Discovered ${found.size} indices (highest $highest): ${found.joinToString(" ")}" }
            if (found.size != highest + 1) {
                logger.info {
                    "Cache is sparse - ${highest + 1 - found.size} of ${highest + 1} slots absent. " +
                            "Normal for a partially downloaded cache; gaps left unmapped."
                }
            }
            indices = Array(highest + 1) { i ->
                if (i !in found) return@Array null
                val file = SqliteIndexFile(path.resolve("js5-$i.jcache"))
                logger.trace("Loading index $i, contains data: ${file.hasReferenceTable()} with max archive: ${file.getMaxArchive()}")
                file
            }
        }
    }

    /**
     * Materialises index [id], creating `js5-<id>.jcache` if it is not already
     * on disk. Idempotent: creating an index that already exists is a no-op.
     *
     * The original refused anything but an append:
     *
     *     if (id < indices.size) throw IllegalArgumentException("index $id already exists")
     *     if (id != indices.size) throw IllegalArgumentException("create indices one by one")
     *
     * Both guards are wrong for the caches this actually runs against. Real NXT
     * caches are SPARSE - a live 8.7 GB cache holds indices 1,2,3,5,8,10,12,...
     * with no js5-0, no js5-4, no js5-6 and a 10-wide hole at 30..39. The array
     * is sized to the highest id seen and the gaps are null, so `id < size` is
     * true for every hole while the file is still absent. The append-only rule
     * then makes the holes permanently unfillable, which is exactly what the
     * cache repair path needs to do: the checksum table names indices this cache
     * has never held, and there is no legal order in which to add them one by
     * one because the tail already extends past them.
     *
     * The distinction that matters is between the ARRAY SLOT and the FILE. A
     * non-null slot means an open SqliteIndexFile, and that is the only case
     * worth short-circuiting - reopening it would leak a JDBC connection and
     * hand out two writers onto one SQLite file. A null slot means "no file
     * yet", whether it sits inside the array or past its end, and both are
     * created the same way.
     *
     * Index numbering is preserved, as everywhere else in this class: callers
     * address indices by real id (maps is 5, clientscripts is 12), so growth is
     * always to `id + 1` with nulls in between, never a compaction.
     */
    override fun createIndex(id: Int) {
        if (id < 0 || id > 254) {
            throw IllegalArgumentException("index id out of range: $id (valid 0..254)")
        }

        if (id < indices.size && indices[id] != null) return

        if (id >= indices.size) {
            val tmp = indices
            indices = Array(id + 1) { if (it < tmp.size) tmp[it] else null }
        }

        indices[id] = SqliteIndexFile(path.resolve("js5-$id.jcache"))
    }

    // Reads treat an out-of-range index the same as an absent one: null / false,
    // not an exception. On a sparse cache "index 30 does not exist" and "index 30
    // is past the end of the array" are the same fact about the world, and every
    // caller already handles null - LibraryPrefetch does `?: return 0`, and the
    // reference-table path is null-checked. Throwing instead turned a missing
    // index into a startup crash raised several frames away from the cause.
    //
    // WRITES still throw. Writing to an index that does not exist is a caller
    // bug rather than a fact about the cache, and silently dropping it would
    // corrupt a cache rather than merely fail to read one.
    override fun exists(index: Int, archive: Int): Boolean {
        if (index < 0 || index >= indices.size) return false

        return indices[index]?.exists(archive) ?: false
    }

    override fun read(index: Int, archive: Int): ByteBuffer? {
        if (index < 0 || index >= indices.size) return null

        return ByteBuffer.wrap(indices[index]?.getRaw(archive) ?: return null)
    }

    override fun read(index: Int, name: String): ByteBuffer? {
        val table = getReferenceTable(index) ?: return null
        val hash = name.toFilesystemHash()
        val id = (table.archives.entries.firstOrNull { it.value.name == hash } ?: return null).key
        return read(index, id)
    }

    override fun readReferenceTable(index: Int): ByteBuffer? {
        if (index < 0 || index >= indices.size) return null

        return ByteBuffer.wrap(indices[index]?.getRawTable() ?: return null)
    }

    override fun write(index: Int, archive: Int, data: Container) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        val compressed = data.compress().array()
        val crc = CRC32()
        crc.update(compressed, 0, compressed.size - 2)
        write(index, archive, compressed, data.version, crc.value.toInt())
    }

    override fun write(index: Int, archive: Int, compressed: ByteArray, version: Int, crc: Int) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        indices[index]?.putRaw(archive, compressed, version, crc)
    }

    override fun writeReferenceTable(index: Int, data: Container) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        if (data.version == -1) data.version = 100
        val compressed = data.compress().array()
        val crc = CRC32()
        crc.update(compressed, 0, compressed.size - 2)
        writeReferenceTable(index, compressed, data.version, crc.value.toInt())
    }

    override fun writeReferenceTable(index: Int, compressed: ByteArray, version: Int, crc: Int) {
        if (index < 0 || index >= indices.size) throw IndexOutOfBoundsException("index out of bounds: $index")

        indices[index]?.putRawTable(compressed, version, crc)
    }

    override fun numIndices(): Int = indices.size

}