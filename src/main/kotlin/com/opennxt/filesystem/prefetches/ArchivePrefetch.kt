package com.opennxt.filesystem.prefetches

import com.opennxt.filesystem.Filesystem

class ArchivePrefetch(private val index: Int, private val archive: Int) : Prefetch {
    override fun calculateValue(store: Filesystem): Int {
        // `?: return 0` rather than `!!`: a cache that lacks this archive is a
        // partial cache, not a broken server. Same reasoning as IndexPrefetch.
        return (store.read(index, archive) ?: return 0).capacity() - 2
    }
}