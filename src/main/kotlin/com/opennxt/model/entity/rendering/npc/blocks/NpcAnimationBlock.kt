package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.ANIMATION] -- four animation ids and a delay.
 */
class NpcAnimationBlock(
    val ids: IntArray,
    val delay: Int
) : NpcUpdateBlock(NpcUpdateBlockType.ANIMATION) {

    init {
        require(ids.size == SLOTS) {
            "the 949 animation block is exactly $SLOTS ids ( reads four," +
                " compares four); got ${ids.size}"
        }
        require(delay in 0..255) { "the delay is one byte; got $delay" }
        for (id in ids) {
            // -1 is the only negative that survives the round trip:
            // putLargeSmart writes 0x7fff for ANY negative, and the client maps
            // 0x7fff back to -1. 0x7fff itself is safe because
            // putLargeSmart pushes `value >= 32767` into the four-byte form,
            // which the client reads as 32767, not as the sentinel.
            require(id == NONE || id >= 0) {
                "an animation id is either $NONE or non-negative; $id would arrive as $NONE"
            }
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        for (id in ids) buffer.putLargeSmart(id)
        buffer.put(DataType.BYTE, DataTransformation.ADD, delay)
    }

    companion object {
        /** Slots read by. */
        const val SLOTS = 4

        /** smart32's -1, `0x7fff` on the wire. */
        const val NONE = -1

        /** One id in slot 0, the other three empty -- the ordinary case. */
        fun single(id: Int, delay: Int = 0): NpcAnimationBlock =
            NpcAnimationBlock(intArrayOf(id, NONE, NONE, NONE), delay)

        /** Cancels whatever is playing: all four slots empty. */
        fun stop(): NpcAnimationBlock =
            NpcAnimationBlock(intArrayOf(NONE, NONE, NONE, NONE), 0)
    }
}
