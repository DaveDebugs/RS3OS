package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.FACE_ENTITY] -- "turn the npc to look at that player /
 * that npc".
 */
class NpcFaceEntityBlock(val index: Int, val tag: Int) :
    NpcUpdateBlock(NpcUpdateBlockType.FACE_ENTITY) {

    init {
        require(index in 0..0xffff) {
            "the face-entity index is the low 16 bits of a u24;" +
                "$index does not fit"
        }
        require(tag in KNOWN_TAGS) {
            ("tag 0x%02x is not routed by the compare chain -- the" +
                "client would consume the three bytes and do nothing").format(tag)
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        ScalarModes.putU24(buffer, MODE, (tag shl 16) or index)
    }

    companion object {
        /**, pointed at by. Mode 0 = u24 BIG endian. */
        const val MODE = 0

        /** `cmp cl, 1` at, resolved through. */
        const val TAG_NPC = 1

        /** `cmp cl, 2` at, resolved through the player list. */
        const val TAG_PLAYER = 2

        /** `cmp cl, 0x7f` at -> the clear path. */
        const val TAG_CLEAR = 0x7f

        /** `cmp cl, 0xff` at -> the same clear path. */
        const val TAG_CLEAR_ALT = 0xff

        val KNOWN_TAGS = setOf(TAG_NPC, TAG_PLAYER, TAG_CLEAR, TAG_CLEAR_ALT)

        fun npc(index: Int) = NpcFaceEntityBlock(index, TAG_NPC)
        fun player(index: Int) = NpcFaceEntityBlock(index, TAG_PLAYER)

        /** The index is unread on this path; installs -1 itself. */
        fun clear() = NpcFaceEntityBlock(0xffff, TAG_CLEAR)
    }
}
