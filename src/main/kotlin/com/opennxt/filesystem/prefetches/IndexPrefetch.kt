package com.opennxt.filesystem.prefetches

import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging

class IndexPrefetch(private val index: Int) : Prefetch {
    private val logger = KotlinLogging.logger { }

    /**
     * Size of an index, for the prefetch table the client is handed at login.
     *
     * Returns 0 when the index is absent from this cache instead of throwing.
     * The previous version used `!!` on three nullable reads, so a single
     * missing index aborted server startup with a NullPointerException - or, if
     * the id was past the end of the array, an IndexOutOfBoundsException raised
     * from deep inside prefetch generation, naming a number with no obvious
     * connection to the cache. A partially downloaded cache is a normal state,
     * and the prefetch table is a loading-progress hint: an index that is not
     * there has nothing to prefetch, and that is not a fatal condition.
     *
     * Failing loudly in the log and continuing is the right trade here. Failing
     * silently would not be - a wrong prefetch value shows up as a client that
     * sits at a loading bar, which is expensive to debug from the other end.
     */
    override fun calculateValue(store: Filesystem): Int {
        val buf = try {
            store.readReferenceTable(index)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
        if (buf == null) {
            logger.warn { "Index $index is not present in this cache - prefetch value 0." }
            return 0
        }

        val table = store.getReferenceTable(index)
        if (table == null) {
            logger.warn { "Index $index has no readable reference table - prefetch value 0." }
            return 0
        }

        var value = 0
        if (table.mask and 0x4 != 0) {
            value += table.totalCompressedSize().toInt()
        } else {
            for (entry in table.archives.keys) {
                val archive = store.read(index, entry)
                if (archive == null) {
                    logger.warn { "Index $index archive $entry is missing - skipped in prefetch." }
                    continue
                }
                value += archive.capacity() - 2
            }
        }

        return value + buf.capacity()
    }
}