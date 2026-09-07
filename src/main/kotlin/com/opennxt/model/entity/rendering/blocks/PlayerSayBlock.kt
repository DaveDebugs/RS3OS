package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.SAY] -- overhead text.
 */
class PlayerSayBlock(val text: String) : UpdateBlock(UpdateBlockType.SAY) {

    init {
        require(!text.contains(NUL)) {
            "the say block is NUL-terminated; an embedded NUL would truncate it on the client"
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.putString(text)
    }

    companion object {
        /** The terminator the scan at stops on. */
        const val NUL = '\u0000'
    }
}
