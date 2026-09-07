package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.STRING_17] -- the SECOND string block on the npc side.
 */
class NpcString17Block(val text: String) : NpcUpdateBlock(NpcUpdateBlockType.STRING_17) {

    init {
        require(!text.contains('\u0000')) {
            "an embedded NUL terminates the string and desynchronises every block" +
                "after it -- this section has no length prefix to recover from"
        }
        require(text.length <= MAX_LENGTH) {
            "a string longer than $MAX_LENGTH was not tested against this client"
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        buffer.putString(text)
    }

    companion object {
        /** Self-imposed: nothing in the body bounds the scan at. */
        const val MAX_LENGTH = 250

        /** The empty string restores `def+0x1b8`. */
        fun restoreDefault() = NpcString17Block("")
    }
}
