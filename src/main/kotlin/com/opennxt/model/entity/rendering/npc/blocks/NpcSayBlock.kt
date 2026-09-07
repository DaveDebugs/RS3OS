package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.SAY] -- the text that floats over the npc's head.
 */
class NpcSayBlock(val text: String) : NpcUpdateBlock(NpcUpdateBlockType.SAY) {

    init {
        require(!text.contains('\u0000')) {
            "an embedded NUL terminates the string and desynchronises every" +
                "block after it -- this section has no length prefix to recover from"
        }
        require(text.length <= MAX_LENGTH) {
            "overhead text longer than $MAX_LENGTH characters was not tested against this client"
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        buffer.putString(text)
    }

    companion object {
        const val MAX_LENGTH = 250
    }
}
