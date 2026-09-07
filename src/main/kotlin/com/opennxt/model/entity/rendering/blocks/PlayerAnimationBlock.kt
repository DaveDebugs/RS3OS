package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.ANIMATION] -- four animation ids and a delay.
 */
class PlayerAnimationBlock(
    val ids: IntArray,
    val delay: Int = 0
) : UpdateBlock(UpdateBlockType.ANIMATION) {

    init {
        require(ids.size == 4) {
            "the 949 animation block is exactly four ids; got ${ids.size}"
        }
        for (id in ids) {
            require(id == -1 || id in 0..0x7ffffffe) { "animation id $id is not encodable as a smart32" }
        }
        require(delay in 0..0xff) { "the delay is a single plain byte; $delay does not fit" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        for (id in ids) buffer.putLargeSmart(id)
        buffer.put(DataType.BYTE, delay)
    }

    companion object {
        /** One animation in slot 0, the other three slots empty. */
        fun single(id: Int, delay: Int = 0) = PlayerAnimationBlock(intArrayOf(id, -1, -1, -1), delay)

        /** Stop whatever is playing: all four slots empty. */
        fun stop() = PlayerAnimationBlock(intArrayOf(-1, -1, -1, -1), 0)
    }
}
