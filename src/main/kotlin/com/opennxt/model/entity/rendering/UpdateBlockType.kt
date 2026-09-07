package com.opennxt.model.entity.rendering

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * The PLAYER extended-info mask of build 949-5, recovered from the client.
 */
enum class UpdateBlockType(
    val playerMask: Int,
    val playerPos: Int,
    val npcMask: Int = 0,
    val npcPos: Int = -1
) {

    FACE_ENTITY(0x1, 10),

    ANIMATION(0x2, 11),

    /**
     * playerMask 0x4 - BIT 2, not bit 0. Read out of the 949 client, not
     * inherited from the Java-era servers this encoder was ported from.
     */
    APPEARANCE(0x4, 3),

    HITS(0x10, 12),

    /**
     * FACE DIRECTION -- an absolute compass angle, not a tile.
     */
    FACE_DIRECTION(0x80, 13),

    SAY(0x400, 14),

    FORCE_MOVEMENT(0x40, 15),

    STRING_18(1 shl 18, 16),

    BYTE_11(1 shl 11, 17),

    /**
     * Bit 21 -- one ADD byte whose `== 1` becomes a bool at `entity+0x1091`
     */
    FLAG_21(1 shl 21, 18),

    // ---------------------------------------------------------------------
    // The bodies whose fields this build READS AND DISCARDS. They carry no
    // meaning that can be recovered from this binary and they are NOT
    // optional: the client's cursor advances over them either way, and this
    // section has no length field anywhere. See PlayerDiscardedBlock, which
    // holds the measured width and transform of each.
    // ---------------------------------------------------------------------

    /** 7 bytes: u16 + u32 + u8, modes 3/2/3. Body. */
    DISCARDED_13(1 shl 13, 19),

    /** 7 bytes: u16 + u32 + u8, modes 1/2/1. Body. */
    DISCARDED_9(1 shl 9, 20),

    /** 7 bytes: u16 + u32 + u8, modes 1/0/0. Body. */
    DISCARDED_3(1 shl 3, 21),

    /** 7 bytes: u16 + u32 + u8, modes 3/2/0. Body. */
    DISCARDED_27(1 shl 27, 22),

    /** 7 bytes: u16 + u32 + u8, modes 2/1/0. Body. */
    DISCARDED_16(1 shl 16, 23),

    DISCARDED_23(1 shl 23, 24),

    DISCARDED_8(1 shl 8, 25);

    /** The single mask bit this block occupies. */
    val playerBit: Int get() = Integer.numberOfTrailingZeros(playerMask)

    /**
     * Where this block sits ON THE WIRE: its index in the client's own test
     * chain. Derived, never hand-written, so it cannot drift from
     * [DISPATCH_ORDER].
     */
    val wireOrder: Int get() = DISPATCH_ORDER.indexOf(playerBit)

    companion object {

        val CONTINUATIONS = listOf(24 to 17, 16 to 14, 8 to 5)

        val DISPATCH_ORDER = listOf(
            13, 10, 12, 7, 26, 4, 9, 11, 2, 3, 1, 23,
            24, 27, 18, 19, 6, 20, 8, 22, 16, 0, 25, 21
        )

        val NEVER_TESTED = setOf(15, 28, 29, 30, 31)

        /**
         * What is on the wire for the bits this pass did NOT implement, so the
         * next person starts from measurements rather than from the chain.
         */
        val UNDECODED: Map<Any, String> = mapOf(
            12 to "descriptor u8 length + that many bytes through the copy routine, then / - the OTHER byte-array block",
            19 to "no scalar reads of its own; goes through [rax+0x10] and",
            20 to "u8 SUBTRACT, u8 SUBTRACT, u8 &0x7f, u8 ADD, u16, u16 -> a table index and a float",
            22 to "two skipped bytes then on entity+0x148 and the same shape as bit 19",
            24 to "npc-config machinery: / / x4, then u16 + u32",
            25 to "u32 x3 through the MIDDLE-endian reader, then the same hit-splat helpers as HITS; not settled",
            26 to "u8 count then a per-element record of s16 x2 + u32 x10; 2830 bytes of body, not settled",
            "HITS bar escape" to
                "f2 == 0x7fff leaves the eight-field record for, which " +
                "walks the existing bar list comparing [. + 8] against f1. Field" +
                "list of that path NOT decoded; PlayerHitsBlock.Bar refuses f2 = 0x7fff.",
            "HITS splat escapes" to
                "type 0x7fff selects a five-field splat and type 0x7ffe" +
                "replaces the amount smart16 with a descriptor u8. Both refused by Hit."
        )

        /**
         * What WAS decoded in this pass, so the next reader can tell a hole from
         * a block that simply has no name. Keyed by bit, value = the address the
         * layout was read from.
         */
        val DECODED_THIS_PASS: Map<Int, String> = mapOf(
            3 to " 7-byte discarded u16+u32+u8",
            6 to " force movement, 12 bytes",
            8 to " 4-byte discarded skip2+u16",
            9 to " 7-byte discarded u16+u32+u8",
            11 to " one PLAIN byte -> entity+0x1094",
            13 to " 7-byte discarded u16+u32+u8",
            16 to " 7-byte discarded u16+u32+u8",
            18 to " [string NUL][u8 PLAIN flag]",
            21 to " one ADD byte -> bool at entity+0x1091",
            23 to " 7-byte discarded skip1+u16x3",
            27 to " 7-byte discarded u16+u32+u8"
        )

        fun writeMask(buffer: GamePacketBuilder, dataMask: Int) {
            require(dataMask != 0) { "an extended-info record with an empty mask is not encodable" }
            for (bit in NEVER_TESTED) {
                require((dataMask ushr bit) and 1 == 0) {
                    "player mask bit $bit is tested nowhere; it cannot carry a block"
                }
            }
            // A continuation bit is not a data bit: / /
            // consume it to decide how many mask bytes follow, and
            // no body in the chain is guarded by it. Setting one by hand adds a
            // mask byte the caller did not intend, which shifts every block.
            // This is also why a "mask of exactly 0xff" is not a thing: 0xff
            // contains bit 5.
            for ((_, bit) in CONTINUATIONS) {
                require((dataMask ushr bit) and 1 == 0) {
                    "player mask bit $bit is a CONTINUATION flag, not a block bit;" +
                        "writeMask sets it itself"
                }
            }

            val mask = withContinuations(dataMask)
            buffer.put(DataType.BYTE, mask and 0xff)
            if ((mask ushr 5) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 8) and 0xff)
            if ((mask ushr 14) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 16) and 0xff)
            if ((mask ushr 17) and 1 == 1) buffer.put(DataType.BYTE, (mask ushr 24) and 0xff)
        }

        /** [dataMask] with every continuation bit its width implies turned on. */
        fun withContinuations(dataMask: Int): Int {
            var mask = dataMask
            for ((threshold, bit) in CONTINUATIONS) {
                if ((mask ushr threshold) != 0) mask = mask or (1 shl bit)
            }
            return mask
        }

        /** How many bytes [writeMask] would emit for [dataMask]. */
        fun maskLength(dataMask: Int): Int {
            val mask = withContinuations(dataMask)
            var n = 1
            if ((mask ushr 5) and 1 == 1) n++
            if ((mask ushr 14) and 1 == 1) n++
            if ((mask ushr 17) and 1 == 1) n++
            return n
        }
    }
}
