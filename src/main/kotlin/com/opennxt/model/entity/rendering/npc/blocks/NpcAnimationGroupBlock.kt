package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.ANIMATION_GROUP] -- override the npc's animation group.
 */
class NpcAnimationGroupBlock(val group: Int) :
    NpcUpdateBlock(NpcUpdateBlockType.ANIMATION_GROUP) {

    init {
        require(group in 0..0xFFFF) { "the animation group is an unsigned short; got $group" }
    }

    override fun encode(buffer: GamePacketBuilder) {
        buffer.put(DataType.SHORT, DataOrder.LITTLE, group)
    }

    companion object {
        /** `cmp edx, 0xffff` -- restores the definition default. */
        const val DEFAULT = 0xFFFF

        fun clear(): NpcAnimationGroupBlock = NpcAnimationGroupBlock(DEFAULT)
    }
}
