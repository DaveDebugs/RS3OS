package com.opennxt.model.entity.rendering.npc

import com.opennxt.net.buf.GamePacketBuilder

/**
 * The NPC extended-info mask of build 949-5, recovered from the client.
 */
enum class NpcUpdateBlockType(val bit: Int, val order: Int) {

    ANIMATION(3, 4),

    HITS(0, 6),

    FACE_COORDINATE(6, 18),

    SAY(2, 19),

    ANIMATION_GROUP(10, 29),

    /**
     * FACE ENTITY -- bit 7, three bytes, **BIG endian with the tag FIRST**.
     */
    FACE_ENTITY(7, 7),

    FORCE_MOVEMENT(11, 12),

    STRING_17(17, 26),

    SHORT_22(22, 1),

    /** Bit 25 -- one SUBTRACT byte into `entity+0x117c`. */
    BYTE_25(25, 8),

    /**
     * Bit 28 -- one ADD byte whose `== 1` becomes a bool at `entity+0xda5`
     */
    FLAG_28(28, 3),

    BYTE_34(34, 21),

    LIFEPOINTS(16, 13),

    // ---------------------------------------------------------------------
    // The bodies whose fields this build reads and discards. See
    // NpcDiscardedBlock: not optional, because the cursor advances anyway.
    // ---------------------------------------------------------------------

    /** 7 bytes: u16 + u32 + u8, modes 1/2/2. Body. */
    DISCARDED_4(4, 2),

    /** 7 bytes: u16 + u32 + u8, modes 1/0/2. Body. */
    DISCARDED_8(8, 24),

    /** 7 bytes: u16 + u32 + u8, modes 3/3/0. Body. */
    DISCARDED_24(24, 27),

    /** 7 bytes: u16 + u32 + u8, modes 0/2/2. Body. */
    DISCARDED_27(27, 14),

    /** 7 bytes: u16 + u32 + u8, modes 0/1/2. Body. */
    DISCARDED_29(29, 22),

    DISCARDED_12(12, 28),

    DISCARDED_13(13, 20);

    /** The mask contribution of this block. */
    val maskBit: Long get() = 1L shl bit

    companion object {

        private val CONTINUATIONS = listOf(32 to 26, 24 to 18, 16 to 14, 8 to 5)

        /**
         * Bits tests nowhere. Setting one silently eats a byte.
         */
        val NEVER_TESTED = setOf(15)

        val DISPATCH_ORDER = listOf(
            20, 22, 4, 28, 3, 1, 0, 7, 25, 9, 21, 31, 11, 16, 27, 33, 30, 19, 6,
            2, 13, 34, 29, 32, 8, 23, 17, 24, 12, 10
        )

        /**
         * What is on the wire for the bits this pass did NOT implement, so the
         * next person starts from measurements rather than from the chain.
         * `null` means "the body was read but its field list was not settled".
         */
        val UNDECODED: Map<Int, String> = mapOf(
            1 to "1 x smart32 into vtable+0x218; a float const and a u16 const from.rdata are pushed first",
            9 to "large: u8, arrays, smart32, s16 x4, u16 x2 ... 2345 bytes of body, not settled",
 //: bit 16 LEFT this map. It read "one u32, rest not
            // settled"; it is now [LIFEPOINTS] - u8 count, then per entry u8 kind + u32 current
            // (INVERSED_MIDDLE) + u24 maximum ([v>>8,v>>16,v]). 1,535 the reference client records, 1,484
            // agreeing with an independent splat-sum oracle. See NpcLifepointsBlock's KDoc.
 //by the packet-end oracle:
            // skip 2, u8 count, then count x 7-byte entries (u8 + u16 + u32) - 3 + 7n. A 3-byte
            19 to "skip 2, u8 count, count x (u8 + u16 + u32) = 3 + 7n bytes; entry object via [rax+0x10] - ",
            20 to "u8 flags then up to four sub-blocks (r15b bits 0.3), arrays of u16, into",
            21 to " x2 then; no scalar reads",
            23 to "same shape as 19: skip 2, u8 count, count x 7 bytes = 3 + 7n; 279 occurrences - ",
            30 to "u16 + u32 plus several container calls; not settled",
            31 to "u8 inline x4 then u16 x2 = 8 bytes fixed (every transform mode of a reader consumes the same width) - 0 occurrences",
            32 to "u32 x3 in MIDDLE-endian form, then hit/health helpers; not settled",
            33 to "s16 x2 then u32 x11; not settled"
        )

        /**
         * What WAS decoded in this pass, keyed by bit, with the address the
         * layout was read from -- so a hole can be told from a block that simply
         * has no name.
         */
        val DECODED_THIS_PASS: Map<Int, String> = mapOf(
            4 to " 7-byte discarded u16+u32+u8",
            7 to " face entity, u24 BIG, tag first",
            8 to " 7-byte discarded u16+u32+u8",
            11 to " force movement, 12 bytes",
            12 to " 7-byte discarded skip1+u16x3",
            13 to " 4-byte discarded skip2+u16",
            17 to " one NUL-terminated string, empty restores def+0x1b8",
            22 to " u16 BE -> entity+0x1178, 0xffff restores def+0x2a8",
            24 to " 7-byte discarded u16+u32+u8",
            25 to " u8 SUBTRACT -> entity+0x117c",
            27 to " 7-byte discarded u16+u32+u8",
            28 to " u8 ADD -> bool at entity+0xda5",
            29 to " 7-byte discarded u16+u32+u8",
            34 to " u8 SUBTRACT -> entity+0x1180, wire 0 clears +0x1181",
            0 to " the eight-field health-bar record (see NpcHitsBlock.Bar)",
            16 to " lifepoints: u8 count, {u8 kind, u32 current INVERSED_MIDDLE," +
                "u24 maximum [v>>8,v>>16,v]} - (see NpcLifepointsBlock)"
        )

        fun writeMask(buffer: GamePacketBuilder, dataMask: Long) {
            require(dataMask != 0L) { "an extended-info block with an empty mask is not encodable" }
            for (bit in NEVER_TESTED) {
                require((dataMask shr bit) and 1L == 0L) {
                    "mask bit $bit is tested nowhere; it cannot carry a block"
                }
            }

            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0L) mask = mask or (1L shl bit)
            }

            buffer.putRawByte((mask and 0xff).toInt())
            if ((mask shr 5) and 1L == 1L) buffer.putRawByte(((mask shr 8) and 0xff).toInt())
            if ((mask shr 14) and 1L == 1L) buffer.putRawByte(((mask shr 16) and 0xff).toInt())
            if ((mask shr 18) and 1L == 1L) buffer.putRawByte(((mask shr 24) and 0xff).toInt())
            if ((mask shr 26) and 1L == 1L) buffer.putRawByte(((mask shr 32) and 0xff).toInt())
        }

        /** How many bytes [writeMask] would emit for [dataMask]. */
        fun maskLength(dataMask: Long): Int {
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0L) mask = mask or (1L shl bit)
            }
            var n = 1
            if ((mask shr 5) and 1L == 1L) n++
            if ((mask shr 14) and 1L == 1L) n++
            if ((mask shr 18) and 1L == 1L) n++
            if ((mask shr 26) and 1L == 1L) n++
            return n
        }
    }
}

/** `put(DataType.BYTE, v)` without importing DataType at every call site. */
private fun GamePacketBuilder.putRawByte(value: Int) {
    put(com.opennxt.net.buf.DataType.BYTE, value)
}
