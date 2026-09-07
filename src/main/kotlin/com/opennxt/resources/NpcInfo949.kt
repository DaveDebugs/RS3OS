package com.opennxt.resources

/**
 * THE BUILD-949 NPC_INFO (ServerProt 90) WIRE FORMAT, read out of the client binary.
 */
object NpcInfo949 {

    /** Movement codes in the high-resolution loop, from the switch at. */
    const val MOVE_MASK_ONLY = 0      // - no further bits; a mask is always queued
    const val MOVE_ONE_DIR = 1        // - readBits(3) dir, readBits(1) hasMask
    const val MOVE_SELECTED = 2       // - readBits(1) selector, then 1 or 2 dirs
    const val MOVE_REMOVE = 3         // - NO BITS. The npc leaves the list.

    /** The add block's terminator, from `cmp eax,0xffff` at. */
    const val ADD_SENTINEL = 0xFFFF

    /**
     * The width of the two signed coordinate-delta fields in an add record, held at
     * `npcMgr+0xc0e8` and set at RUNTIME - which is why no static read and no observation-side
     * sweep ever found it.
     */
    const val COORD_BITS = 7

    /** Continuation-flag bit positions in the update mask. See [readUpdateMask]. */
    val MASK_CONTINUATIONS = intArrayOf(5, 14, 18, 26)

    fun readUpdateMask(next: () -> Int): Pair<Long, Int> {
        var used = 1
        var mask = (next() and 0xFF).toLong()
        if (mask and 0x20L != 0L) {
            mask = mask or ((next() and 0xFF).toLong() shl 8); used++
        }
        if ((mask shr 14) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 16; used++
        }
        if ((mask shr 18) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 24; used++
        }
        if ((mask shr 26) and 1L != 0L) {
            mask += (next() and 0xFF).toLong() shl 32; used++
        }
        return mask to used
    }

    /** How many bytes a mask of this value occupies. Derived from [readUpdateMask]'s flags. */
    fun maskWidth(mask: Long): Int {
        var n = 1
        if (mask and 0x20L != 0L) n++
        if ((mask shr 14) and 1L != 0L) n++
        if ((mask shr 18) and 1L != 0L) n++
        if ((mask shr 26) and 1L != 0L) n++
        return n
    }

    /** MSB-first bit reader, mirroring `readBits` at. */
    class BitReader(private val d: ByteArray) {
        var pos: Int = 0
            private set

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                if (pos >= d.size * 8) throw IndexOutOfBoundsException("out of bits")
                v = (v shl 1) or ((d[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
    }

    data class Add(val index: Int, val npcId: Int, val dx: Int, val dy: Int)

    fun highRes(payload: ByteArray): Pair<Int, IntArray> {
        val r = BitReader(payload)
        val count = r.bits(8)
        val tally = IntArray(4)
        repeat(count) {
            if (r.bits(1) == 0) return@repeat
            val mv = r.bits(2)
            tally[mv]++
            when (mv) {
                MOVE_MASK_ONLY -> {}
                MOVE_ONE_DIR -> { r.bits(3); r.bits(1) }
                MOVE_SELECTED -> {
                    if (r.bits(1) == 1) { r.bits(3); r.bits(3) } else r.bits(3)
                    r.bits(1)
                }
                // MOVE_REMOVE consumes nothing
            }
        }
        return r.pos to tally
    }

    /** The add block: index -> npc id, plus the signed view-relative deltas. */
    fun adds(payload: ByteArray, w: Int = COORD_BITS): List<Add> {
        val r = BitReader(payload)
        val (start, _) = highRes(payload)
        repeat(start) { r.bits(1) }          // seek; BitReader is forward-only by design
        val out = ArrayList<Add>()
        val half = (1 shl (w - 1)) - 1
        val full = 1 shl w
 // THE GUARD IS THE WHOLE RECORD, NOT JUST THE INDEX. Tightened.
        //
        // This read `>= 16`, the width of the index alone, and then went on to consume
        // 39 + 2w bits (index 16, dx w, npcId 16, three flag fields 1+2+3, dy w, 1) =
        // 53 at the measured w=7. So a payload with between 16 and 52 bits left entered
        // the loop and threw IndexOutOfBoundsException out of BitReader partway through
 // a record. wraps the call in a
        // try/catch, so the throw was silently absorbed and the packet still counted as
        // check now counts and prints the absorbed failures instead of discarding them,
        // and this guard makes the common case not raise them at all.
        //
        // A trailing partial record is not an error in the format: the sentinel 0xFFFF
        // ends the block and whatever follows is the byte-aligned mask block, which this
        // function does not read. Stopping cleanly at "not enough bits for another
        // record" is the correct reading of that, not a tolerance being widened.
        val recordBits = 39 + 2 * w
        while (payload.size * 8 - r.pos >= recordBits) {
            val index = r.bits(16)
            if (index == ADD_SENTINEL) break
            var dx = r.bits(w)
            val npcId = r.bits(16)
            r.bits(1); r.bits(2); r.bits(3)
            var dy = r.bits(w)
            r.bits(1)
            if (dx > half) dx -= full
            if (dy > half) dy -= full
            out.add(Add(index, npcId, dx, dy))
        }
        return out
    }
}
