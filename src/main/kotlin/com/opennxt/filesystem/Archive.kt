package com.opennxt.filesystem

import com.opennxt.ext.toByteArray
import java.nio.ByteBuffer

class Archive(
    val id: Int,
    var name: Int = 0
) {
    var crc = 0
    var version = 0
    var whirlpool: ByteArray? = null
    var uncompressedSize = 0
    var compressedSize = 0
    var hash = 0

    internal var loaded = false
    internal var requiresUpdate = false
    internal var files = sortedMapOf<Int, ArchiveFile>()

    fun putFile(id: Int, data: ByteArray) {
        requiresUpdate = true
        files[id] = ArchiveFile(id, data)
    }

    fun putFile(file: ArchiveFile) {
        requiresUpdate = true
        files[file.id] = file
    }

    /**
     * The NXT front-offset group layout:
     *
     *     u8        format = 0x01
     *     u32 BE    (numFiles + 1) offsets
     *     bytes     file data; file i is blob[off[i] until off[i+1]]
     *
     * Returns false if this blob is not that layout, leaving the buffer position
     * untouched so the caller can fall through to the legacy reading.
     *
     * Accepted only when every invariant holds - format byte, offset[0] ==
     * 1 + (n+1)*4, offset[n] == blob size, offsets non-decreasing. A legacy blob
     * will not satisfy those by accident, so this ADDS a layout rather than
     * replacing one, and genuinely legacy caches still work.
     */
    private fun decodeFrontOffsets(buffer: ByteBuffer): Boolean {
        val n = files.size
        val headerSize = 1 + (n + 1) * 4
        val limit = buffer.limit()
        if (limit < headerSize) return false

        val start = buffer.position()
        if (buffer.get(start).toInt() and 0xff != 1) return false

        val offsets = IntArray(n + 1)
        for (i in 0..n) {
            offsets[i] = buffer.getInt(start + 1 + i * 4)
        }
        if (offsets[0] != headerSize) return false
        if (offsets[n] != limit - start) return false
        for (i in 0 until n) {
            if (offsets[i] > offsets[i + 1]) return false
        }

        val ids = files.keys.sorted()
        for (i in 0 until n) {
            val len = offsets[i + 1] - offsets[i]
            val data = ByteArray(len)
            if (len > 0) {
                val dup = buffer.duplicate()
                dup.position(start + offsets[i])
                dup.get(data)
            }
            files[ids[i]]!!.data = data
        }
        return true
    }

    /**
     * Split a group blob into its member files.
     *
     * Two layouts exist and this cache uses the newer one. Only the legacy
     * trailing-chunk form was implemented here, and the failure was silent
     * rather than loud: a front-offset blob read as legacy finds a chunk count
     * of 0 in its final byte, allocates every file 0 bytes, and yields a group
     * of empty files without throwing.
     *
     * Everything downstream then looks healthy. The container decodes, the
     * reference table decodes, `EnumDefinition` comes back non-null - and every
     * value inside it is missing. That is how indexes 17 and 22 could pass a
     * container-level sweep while supplying no field data at all, and why
     * `Stat.reload` could look up skill names and silently get none.
     */
    fun decode(buffer: ByteBuffer) {
        loaded = true
        if (files.size == 1) {
            files[files.firstKey()]!!.data = buffer.toByteArray()
            return
        }

        if (decodeFrontOffsets(buffer)) return

        buffer.position(buffer.limit() - 1)
        val numChunks = buffer.get().toInt() and 0xff

        val size = files.size
        val chunkSizes = Array(numChunks) { IntArray(size) }
        val sizes = IntArray(size)
        val offsets = IntArray(size)
        val ids = files.keys.sorted().toIntArray()

        buffer.position(buffer.limit() - 1 - numChunks * (size * 4))
        for (chunk in 0 until numChunks) {
            var chunkSize = 0
            for (file in 0 until size) {
                val delta = buffer.int
                chunkSize += delta

                chunkSizes[chunk][file] = chunkSize
                sizes[file] += chunkSize
            }
        }

        for (file in 0 until size) {
            files[ids[file]]!!.data = ByteArray(sizes[file])
        }

        buffer.position(0)

        for (chunk in 0 until numChunks) {
            for (file in 0 until size) {
                val chunkSize = chunkSizes[chunk][file]

                val offset = offsets[file]
                buffer.get(files[ids[file]]!!.data, offset, chunkSize)
                offsets[file] += chunkSize
            }
        }
    }

    fun encode(): ByteBuffer {
        var size = 0
        files.values.forEach { size += it.data.size }
        val buffer = ByteBuffer.allocate(1 + size + files.size * 4)
        if (files.size == 1) {
            return ByteBuffer.wrap(files[files.firstKey()]?.data ?: ByteArray(0))
        } else {
            var last = 0
            files.forEach {
                if (last > it.key) throw IllegalStateException("out of order $last, ${it.key}")
                buffer.put(it.value.data)
                last = it.key
            }
            val filesArray = files.values.toTypedArray()
            for (i in filesArray.indices) {
                val file = filesArray[i]
                val fileSize = file.data.size
                val previousSize = if (i == 0) 0 else filesArray[i - 1].data.size
                buffer.putInt(fileSize - previousSize)
            }
            buffer.put(1)
        }
        buffer.flip()
        return buffer
    }
}