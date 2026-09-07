package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.SHORT_22] -- npc bit 22, one BIG-endian u16 that overrides
 * a field of the npc TYPE DEFINITION.
 */
class NpcShort22Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.SHORT_22) {

    init {
        require(value in 0..0xffff) { "one BE u16; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU16(buffer, MODE, value)
    }

    companion object {
        /** = 0x00 = BIG endian, no transform. */
        const val MODE = 0

        /** `cmp r10d, 0xffff` at -- restores `def+0x2a8`. */
        const val DEFAULT = 0xffff

        fun clear() = NpcShort22Block(DEFAULT)
    }
}

/**
 * [NpcUpdateBlockType.BYTE_25] -- npc bit 25, one SUBTRACT byte.
 */
class NpcByte25Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.BYTE_25) {

    init {
        require(value in 0..0xff) { "one SUBTRACT byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        /** = 0x03 = SUBTRACT. */
        const val MODE = 3
    }
}

/**
 * [NpcUpdateBlockType.FLAG_28] -- npc bit 28, one ADD byte carrying a bool.
 */
class NpcFlag28Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.FLAG_28) {

    init {
        require(value in 0..0xff) { "one ADD byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        /** = 0x01 = ADD. */
        const val MODE = 1

        /** The only value `sete` at turns into true. */
        const val SET_VALUE = 1
        const val CLEAR_VALUE = 0

        fun set() = NpcFlag28Block(SET_VALUE)
        fun clear() = NpcFlag28Block(CLEAR_VALUE)
    }
}

/**
 * [NpcUpdateBlockType.BYTE_34] -- npc bit 34, one SUBTRACT byte where wire 0
 * means "clear".
 */
class NpcByte34Block(val value: Int) : NpcUpdateBlock(NpcUpdateBlockType.BYTE_34) {

    init {
        require(value in 0..0xff) { "one SUBTRACT byte; got $value" }
    }

    /** True when this encodes the clearing branch at rather than a value. */
    val clears: Boolean get() = value == CLEAR_VALUE

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        /** at cursor-1, and `0x80 - wire` in the instructions. */
        const val MODE = 3

        /**
         * The decoded value that takes the clearing branch, because `0x80 - 0x80`
         * is the wire byte 0. Nothing else can mean "clear" and this cannot mean
         * anything else.
         */
        const val CLEAR_VALUE = 0x80

        fun clear() = NpcByte34Block(CLEAR_VALUE)
    }
}
