package com.opennxt.util

import com.opennxt.OpenNXT
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.ext.readSmartShort
import com.opennxt.ext.writeSmartShort
import mu.KotlinLogging

/**
 * The chat Huffman codec - the thing without which no player can say anything.
 */
class HuffmanCodec private constructor(
    /** Bit length per symbol, 256 entries; `0` means "symbol not in the code". */
    val lengths: IntArray,
    /** Per-symbol code, left-aligned to bit 31 exactly as `[huffman+0x58]` holds it. */
    val masks: IntArray
) {

    /**
     * Decode trie, `[0]` unused, two ints per node: `child[2*n]` / `child[2*n+1]`.
     * A negative entry is a leaf carrying `-(symbol + 1)`; `0` is absent.
     *
     * Built here rather than walked bit-by-bit against [masks] at decode time
     * because a linear scan over 256 symbols per bit is the kind of decoder that
     * works in a test and stalls a tick loop.
     */
    private val child: IntArray

    /** Internal (non-leaf) node count. 255 for a complete 256-leaf code. */
    val internalNodes: Int

    init {
        require(lengths.size == masks.size) { "lengths/masks disagree: ${lengths.size} vs ${masks.size}" }

        // Worst case one internal node per bit of every code, plus the root.
        var cap = 2
        for (l in lengths) cap += l
        child = IntArray(cap * 2)
        var next = 1
        for (sym in lengths.indices) {
            val bits = lengths[sym]
            if (bits == 0) continue
            var node = 1
            for (b in bits - 1 downTo 0) {
                val bit = (masks[sym] ushr (31 - (bits - 1 - b))) and 1
                val slot = node * 2 + bit
                if (b == 0) {
                    require(child[slot] == 0) {
                        "huffman table is not prefix-free: symbol $sym collides at depth ${bits - b}"
                    }
                    child[slot] = -(sym + 1)
                } else {
                    var nxt = child[slot]
                    require(nxt >= 0) {
                        "huffman table is not prefix-free: symbol $sym extends a leaf at depth ${bits - b}"
                    }
                    if (nxt == 0) {
                        next++
                        nxt = next
                        child[slot] = nxt
                    }
                    node = nxt
                }
            }
        }
        internalNodes = next
    }

    /** Symbols that have a code at all. */
    val symbolCount: Int get() = lengths.count { it != 0 }

    /** `sum(2^-len)`. Exactly 1.0 for a complete code; less means codes are wasted. */
    fun kraftSum(): Double {
        var sum = 0.0
        for (l in lengths) if (l != 0) sum += Math.pow(2.0, -l.toDouble())
        return sum
    }

    // ------------------------------------------------------------------ encode

    fun encode(text: String): ByteArray {
        val symbols = toCp1252(text)
        var bits = 0
        for (b in symbols) {
            val len = lengths[b.toInt() and 0xff]
            require(len != 0) { "no huffman code for symbol ${b.toInt() and 0xff}" }
            bits += len
        }

        val out = ByteArray((bits + 7) / 8)
        var pos = 0
        for (b in symbols) {
            val sym = b.toInt() and 0xff
            var word = masks[sym]
            var remaining = lengths[sym]
            // Same shape as: take bits off the TOP of
            // the left-aligned code word, OR them into the current byte, walk on.
            // `take` never exceeds 8, so `shl take` is always well defined.
            while (remaining > 0) {
                val byteIndex = pos shr 3
                val room = 8 - (pos and 7)
                val take = if (remaining < room) remaining else room
                val chunk = (word ushr (32 - take)) and ((1 shl take) - 1)
                out[byteIndex] = (out[byteIndex].toInt() or (chunk shl (room - take))).toByte()
                word = word shl take
                remaining -= take
                pos += take
            }
        }
        return out
    }

    /** Writes `[count][bitstream]` at the builder's cursor. */
    fun write(buf: GamePacketBuilder, text: String) {
        val bytes = encode(text)
        val count = text.length
        require(count in 0..0x7fff) { "huffman charCount does not fit a smart: $count" }
        buf.buffer.writeSmartShort(count)
        buf.putBytes(bytes)
    }

    // ------------------------------------------------------------------ decode

    /**
     * Decodes [charCount] symbols out of [data] starting at [offset].
     *
     * Returns the text and the number of BYTES consumed, which is
     * `ceil(bits/8)` - the padding belongs to this field, so a caller reading a
     * packet must skip it.
     */
    fun decode(data: ByteArray, offset: Int, charCount: Int): Pair<String, Int> {
        require(charCount >= 0) { "negative charCount: $charCount" }
        if (charCount == 0) return "" to 0

        val sb = StringBuilder(charCount)
        var node = 1
        var bits = 0
        var produced = 0
        val limitBits = (data.size - offset) * 8
        while (produced < charCount) {
            if (bits >= limitBits) {
                throw IllegalArgumentException(
                    "huffman bitstream ran out after $produced of $charCount symbols " +
                        "(${data.size - offset} bytes available)"
                )
            }
            val byte = data[offset + (bits shr 3)].toInt() and 0xff
            val bit = (byte ushr (7 - (bits and 7))) and 1
            bits++
            val nxt = child[node * 2 + bit]
            if (nxt == 0) {
                throw IllegalArgumentException(
                    "huffman bitstream took an undefined branch after $produced symbol(s), bit $bits"
                )
            }
            if (nxt < 0) {
                sb.append(TextUtils.cp1252ToChar((-nxt - 1).toByte()))
                produced++
                node = 1
            } else {
                node = nxt
            }
        }
        return sb.toString() to (bits + 7) / 8
    }

    /** Reads `[count][bitstream]` at the reader's cursor and advances past both. */
    fun readFrom(reader: GamePacketReader): String {
        val count = reader.buffer.readSmartShort()
        if (count == 0) return ""
        val available = reader.buffer.readableBytes()
        val bytes = ByteArray(available)
        reader.buffer.getBytes(reader.buffer.readerIndex(), bytes)
        val (text, consumed) = decode(bytes, 0, count)
        reader.buffer.readerIndex(reader.buffer.readerIndex() + consumed)
        return text
    }

    private fun toCp1252(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) out[i] = TextUtils.charToCp1252(text[i])
        return out
    }

    companion object {
        private val logger = KotlinLogging.logger { }

        /** js5 index holding the huffman group. */
        const val INDEX = 10

        /** The group's cache name; its hash is what the reference table stores. */
        const val NAME = "huffman"

        /** One symbol per byte of the payload. */
        const val SYMBOLS = 256

        fun fromLengths(raw: ByteArray): HuffmanCodec {
            val lengths = IntArray(raw.size) { raw[it].toInt() and 0xff }
            return fromLengths(lengths)
        }

        fun fromLengths(lengths: IntArray): HuffmanCodec {
            val masks = IntArray(lengths.size)
            val next = IntArray(33)

            for (sym in lengths.indices) {
                val bits = lengths[sym]
                if (bits == 0) continue
                require(bits in 1..32) { "symbol $sym has an impossible bit length $bits" }

                val bit = 1 shl (32 - bits)
                val code = next[bits]
                masks[sym] = code

                var newCode: Int
                if (code and bit != 0) {
                    newCode = next[bits - 1]
                } else {
                    newCode = code or bit
                    var k = bits - 1
                    while (k >= 1) {
                        val v = next[k]
                        if (v != code) break
                        val m = 1 shl (32 - k)
                        if (v and m != 0) {
                            next[k] = next[k - 1]
                            break
                        }
                        next[k] = v or m
                        k--
                    }
                }
                next[bits] = newCode

                for (i in bits + 1..32) {
                    if (next[i] == code) next[i] = newCode
                }
            }

            return HuffmanCodec(lengths, masks)
        }

        /**
         * The 256 raw bit-length bytes out of [filesystem], resolved BY NAME, or
         * null if this cache has no such group.
         *
         * Goes through the same path the rest of the server reads the cache with
         * - `read(index, name)` -> [Container.decode] -> the group blob - so
         * "present" here means present to this server, not merely present in
         * sqlite. Index 10 group 1 holds a single file, and
         * [com.opennxt.filesystem.Archive.decode] returns a one-file group's
         * blob verbatim, so the blob IS the table and no per-file split applies.
         */
        fun loadLengths(filesystem: Filesystem): ByteArray? {
            val raw = filesystem.read(INDEX, NAME) ?: return null
            val data = try {
                Container.decode(raw).data
            } catch (t: Throwable) {
                logger.warn(t) { "js5[$INDEX, '$NAME'] would not decode as a container" }
                return null
            }

            if (data.size != SYMBOLS) {
                logger.warn {
                    "js5[$INDEX, '$NAME'] is ${data.size} bytes, expected $SYMBOLS (one bit length per symbol); " +
                        "refusing it - a short table silently mis-decodes rather than failing"
                }
                return null
            }
            return data
        }

        fun load(filesystem: Filesystem): HuffmanCodec? {
            val raw = loadLengths(filesystem) ?: return null
            val codec = fromLengths(raw)
            logger.info {
                "Loaded the chat huffman table from js5[$INDEX, '$NAME']: ${codec.symbolCount} symbols, " +
                    "lengths ${codec.lengths.filter { it != 0 }.minOrNull()}..${codec.lengths.maxOrNull()}, " +
                    "kraft sum ${codec.kraftSum()}"
            }
            return codec
        }

        @Volatile
        private var cached: HuffmanCodec? = null

        @Volatile
        private var attempted = false

        /**
         * The server-wide table, loaded once from `OpenNXT.filesystem`, or null
         * if this cache has no huffman group.
         */
        @Synchronized
        fun instance(): HuffmanCodec? {
            if (attempted) return cached
            attempted = true
            cached = try {
                load(OpenNXT.filesystem)
            } catch (t: Throwable) {
                logger.warn(t) { "Could not load the chat huffman table; chat will be inert" }
                null
            }
            if (cached == null) {
                logger.warn {
                    "No chat huffman table in this cache (js5[$INDEX, '$NAME'] absent). Public chat is " +
                        "DISABLED in both directions - and the client cannot send it either, its own " +
                        "builder is gated on the same table."
                }
            }
            return cached
        }

        /** Drops the memoised table. Test hook; the server never calls it. */
        @Synchronized
        fun forget() {
            cached = null
            attempted = false
        }

        /** Installs [codec] as the server-wide table. Test hook. */
        @Synchronized
        fun install(codec: HuffmanCodec?) {
            cached = codec
            attempted = true
        }
    }
}
