package com.opennxt.filesystem

import com.opennxt.filesystem.compression.BZIP2Compression
import com.opennxt.filesystem.compression.ContainerCompression
import com.opennxt.filesystem.compression.GZIPCompression
import com.opennxt.filesystem.compression.LZMACompression
import java.nio.ByteBuffer
import java.util.zip.Inflater

class Container(
    var data: ByteArray,
    var compression: ContainerCompression = ContainerCompression.LZMA,
    var version: Int = -1
) {
    companion object {
        /**
         * "ZLB" - the envelope current NXT caches wrap container payloads in:
         *
         *     "ZLB"   4 bytes magic
         *     length        4 bytes big-endian, size of the INFLATED payload
         *     zlib stream   to end of buffer (begins 0x78 0x9c)
         *
         * Inflating it yields an ordinary js5 container which then decodes as
         * normal, so this is a wrapper around the existing format rather than a
         * replacement for it.
         *
         * Without this, the first byte of a real 2026 cache container - 0x5A,
         * the 'Z' of "ZLB" - is read as a compression id and the server dies
         * with "No compression found for id: 90". Nothing in that message hints
         * that the cause is an unrecognised envelope, which is why it is spelled
         * out here.
         */
        private val ZLB_MAGIC = byteArrayOf(0x5A, 0x4C, 0x42, 0x01)

        /** Sanity bound so a corrupt length field cannot request a huge array. */
        private const val MAX_ZLB_INFLATED = 512 * 1024 * 1024

        /**
         * Inflates a ZLB envelope, or returns null if this is not one.
         *
         * Made internal-visible (was private) because [com.opennxt.net.js5.Js5Encoder]
         * has to re-wrap these before they go on the wire: the 947 client
         * rejects any container whose compression id exceeds 3, and 'Z' is
         * 0x5A, so serving a ZLB blob verbatim makes the client drop the JS5
         * connection outright.
         */
        fun unwrapZlbOrNull(data: ByteBuffer): ByteBuffer? = unwrapZlb(data)

        private fun unwrapZlb(data: ByteBuffer): ByteBuffer? {
            if (data.remaining() < 8) return null
            val start = data.position()
            for (i in ZLB_MAGIC.indices) {
                if (data.get(start + i) != ZLB_MAGIC[i]) return null
            }

            val dup = data.duplicate()
            dup.position(start + ZLB_MAGIC.size)
            val inflatedSize = dup.int
            if (inflatedSize < 0 || inflatedSize > MAX_ZLB_INFLATED) {
                throw IllegalArgumentException(
                    "ZLB container declares an implausible inflated size: $inflatedSize"
                )
            }

            val deflated = ByteArray(dup.remaining())
            dup.get(deflated)

            val out = ByteArray(inflatedSize)
            val inflater = Inflater()
            try {
                inflater.setInput(deflated)
                var off = 0
                while (off < inflatedSize && !inflater.finished()) {
                    val n = inflater.inflate(out, off, inflatedSize - off)
                    // inflate() returning 0 while it still wants input means the
                    // stream is truncated. Bail rather than spin forever.
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    off += n
                }
                if (off != inflatedSize) {
                    throw IllegalArgumentException(
                        "ZLB container declared $inflatedSize inflated bytes but produced $off"
                    )
                }
            } finally {
                inflater.end()
            }

            return ByteBuffer.wrap(out)
        }

        fun decode(rawData: ByteBuffer): Container {
            if (!rawData.hasRemaining()) throw IllegalArgumentException("Provided non-readable (empty?) buffer")

            // A ZLB envelope REPLACES the classic container header - it does not
            // sit on top of one. Inflating it yields the final payload directly:
            // for a reference table that is the table itself (first byte is the
            // protocol version, 7), and for an archive it is the archive data.
            //
            // Getting this wrong is easy and looks convincing. Treating the
            // inflated bytes as another container reads that protocol byte as a
            // compression id and fails with "No compression found for id: 7" -
            // a different wrong answer that still points at compression rather
            // than at framing.
            //
            // There is no version trailer in this form, hence version = -1.
            val unwrapped = unwrapZlb(rawData)
            if (unwrapped != null) {
                val bytes = ByteArray(unwrapped.remaining())
                unwrapped.get(bytes)
                return Container(bytes, ContainerCompression.NONE, -1)
            }

            val data = rawData
            val compression = ContainerCompression.of(data.get().toInt())
            val size = data.int

            val decompressedSize = if (compression == ContainerCompression.NONE) 0 else data.int
            val compressed = ByteArray(size)
            data.get(compressed)
            val version = if (data.remaining() >= 2) data.short.toInt() and 0xffff else -1

            val decompressed = when (compression) {
                ContainerCompression.NONE -> compressed
                ContainerCompression.BZIP2 -> BZIP2Compression.decompress(compressed)
                ContainerCompression.GZIP -> GZIPCompression.decompress(compressed)
                ContainerCompression.LZMA -> LZMACompression.decompress(compressed, decompressedSize)
            }

            return Container(decompressed, compression, version)
        }

        fun wrap(data: ByteBuffer): ByteBuffer {
            val buf = ByteBuffer.allocate(5 + data.remaining())

            buf.put(ContainerCompression.NONE.id.toByte())
            buf.putInt(data.remaining())
            buf.put(data)
            buf.flip()

            return buf
        }

        fun wrap(data: ByteArray): ByteBuffer {
            val buf = ByteBuffer.allocate(5 + data.size)

            buf.put(ContainerCompression.NONE.id.toByte())
            buf.putInt(data.size)
            buf.put(data)
            buf.flip()

            return buf
        }
    }

    fun compress(): ByteBuffer {
        val compressed = when (compression) {
            ContainerCompression.NONE -> data
            ContainerCompression.BZIP2 -> BZIP2Compression.compress(data)
            ContainerCompression.GZIP -> GZIPCompression.compress(data)
            ContainerCompression.LZMA -> LZMACompression.compress(data)
        }

        val buffer =
            ByteBuffer.allocate(compressed.size + 1 + 4 + (if (compression != ContainerCompression.NONE) 4 else 0) + (if (version != -1) 2 else 0))
        buffer.put(compression.id.toByte())
        buffer.putInt(compressed.size)
        if (compression != ContainerCompression.NONE)
            buffer.putInt(data.size)
        buffer.put(compressed)
        if (version != -1)
            buffer.putShort(version.toShort())
        buffer.flip()

        return buffer
    }
}