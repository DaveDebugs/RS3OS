package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.BYTE_11] -- player bit 11, ONE PLAIN byte.
 */
class PlayerByte11Block(val value: Int) : UpdateBlock(UpdateBlockType.BYTE_11) {

    init {
        require(value in 0..0xff) { "one plain byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.BYTE, value)
    }
}

/**
 * [UpdateBlockType.FLAG_21] -- player bit 21, ONE ADD byte carrying a bool.
 */
class PlayerFlag21Block(val value: Int) : UpdateBlock(UpdateBlockType.FLAG_21) {

    init {
        require(value in 0..0xff) { "one ADD byte; got $value" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        ScalarModes.putU8(buffer, MODE, value)
    }

    companion object {
        /** = 0x01 = ADD, and `cmp..,0x81` shows it. */
        const val MODE = 1

        /** The only value `sete` at turns into true. */
        const val SET_VALUE = 1

        /** Any other value clears it; 0 is the one that reads back as 0x80. */
        const val CLEAR_VALUE = 0

        fun set() = PlayerFlag21Block(SET_VALUE)
        fun clear() = PlayerFlag21Block(CLEAR_VALUE)
    }
}
