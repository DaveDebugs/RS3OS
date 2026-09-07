package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.STRING_18] -- the SECOND string block on the player side.
 */
class PlayerStringFlagBlock(val text: String, val flag: Int = FLAG_ENABLE) :
    UpdateBlock(UpdateBlockType.STRING_18) {

    init {
        require(!text.contains('\u0000')) {
            "the string is NUL-terminated; an embedded NUL would truncate it and" +
                "leave every later block reading the wrong byte -- this section has no length prefix"
        }
        require(text.length <= MAX_LENGTH) {
            "a string longer than $MAX_LENGTH was not tested against this client"
        }
        require(flag in 0..0xff) { "the flag is one plain byte; got $flag" }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.putString(text)
        ScalarModes.putU8(buffer, FLAG_MODE, flag)
    }

    companion object {
        /** = 0x00, and the mode-0 arm at is a bare movzx. */
        const val FLAG_MODE = 0

        /** `test dil,1` at: with this clear the client drops the string. */
        const val FLAG_ENABLE = 1

        /** Self-imposed, like [PlayerSayBlock]: nothing in the body bounds the scan. */
        const val MAX_LENGTH = 250
    }
}
