package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.FACE_ENTITY] -- "turn to look at that npc / that player".
 */
class PlayerFaceEntityBlock(val index: Int, val tag: Int) : UpdateBlock(UpdateBlockType.FACE_ENTITY) {

    init {
        require(index in 0..0xffff) { "the face-entity index is 16 bits; $index does not fit" }
        require(tag in KNOWN_TAGS) {
            "tag 0x%02x is not - the client would eat the three bytes and do nothing".format(tag)
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.BYTE, index and 0xff)
        buffer.put(DataType.BYTE, (index shr 8) and 0xff)
        buffer.put(DataType.BYTE, tag)
    }

    companion object {
        /** -> `[entity+0x228] = 1`, resolved through. */
        const val TAG_NPC = 1

        /** -> `[entity+0x228] = 2`, resolved through the player list. */
        const val TAG_PLAYER = 2

        /** What the client writes for itself when it clears a target. */
        const val TAG_CLEAR = 0x7f

        /** The other constant routes to the same clear path. */
        const val TAG_CLEAR_ALT = 0xff

        val KNOWN_TAGS = setOf(TAG_NPC, TAG_PLAYER, TAG_CLEAR, TAG_CLEAR_ALT)

        fun npc(index: Int) = PlayerFaceEntityBlock(index, TAG_NPC)
        fun player(index: Int) = PlayerFaceEntityBlock(index, TAG_PLAYER)

        /** The index is ignored on this path; the client substitutes -1 itself. */
        fun clear() = PlayerFaceEntityBlock(0xffff, TAG_CLEAR)
    }
}
