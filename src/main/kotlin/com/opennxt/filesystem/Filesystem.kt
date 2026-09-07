package com.opennxt.filesystem

import java.nio.ByteBuffer
import java.nio.file.Path

abstract class Filesystem(val path: Path) {

    private val checkedReferenceTables = BooleanArray(255)

    private val cachedReferenceTables = arrayOfNulls<ReferenceTable>(255)

    abstract fun exists(index: Int, archive: Int): Boolean

    abstract fun read(index: Int, archive: Int): ByteBuffer?

    abstract fun read(index: Int, name: String): ByteBuffer?

    abstract fun readReferenceTable(index: Int): ByteBuffer?

    abstract fun createIndex(id: Int)

    /**
     * `@Synchronized` because this cache is read and written from three threads
     * with no memory barrier of any kind.
     *
     * [cachedReferenceTables] and [checkedReferenceTables] are plain arrays. The
     * tick thread (content lookups on indices 2/17/22), `js5-thread` and the
     * Netty HTTP event loop all reach this method, and the store on the
     * second-to-last line publishes a ReferenceTable whose `decode` has only
     * just written its fields. With no barrier another thread can observe that
     * reference while those fields are still default - a partially constructed
     * table that resolves archives to nothing, with nothing anywhere to say so.
     * `checkedReferenceTables` has the mirror problem: two threads can both pass
     * the check and decode the same table twice.
     *
     * Serialising the whole method is the cheap correct answer: it runs once per
     * index in practice, and every call after that is a cache hit.
     */
    @Synchronized
    fun getReferenceTable(index: Int, ignoreChecked: Boolean = false): ReferenceTable? {
        val cached = cachedReferenceTables[index]
        if (cached != null) return cached

        if (!ignoreChecked) {
            if (checkedReferenceTables[index]) return null
            checkedReferenceTables[index] = true
        }

        val table = ReferenceTable(this, index)
        val container = readReferenceTable(index) ?: return null
        val data = ByteBuffer.wrap(Container.decode(container).data)
        table.decode(data)
        cachedReferenceTables[index] = table

        return table
    }

    abstract fun write(index: Int, archive: Int, data: Container)

    abstract fun write(index: Int, archive: Int, compressed: ByteArray, version: Int, crc: Int)

    abstract fun writeReferenceTable(index: Int, data: Container)

    abstract fun writeReferenceTable(index: Int, compressed: ByteArray, version: Int, crc: Int)

    abstract fun numIndices(): Int

    fun update() {
        cachedReferenceTables.forEach { it?.update() }
    }

}