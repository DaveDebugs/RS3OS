package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * The framing around [com.opennxt.model.entity.player.appearance.PlayerModel.data].
 *
 * Both halves of this used to be the 317-era idiom - a NEGATE'd length followed by
 * the payload written backwards - and the 949 client agrees with neither.
 */
class AppearanceUpdateBlock : UpdateBlock(UpdateBlockType.APPEARANCE) {
    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        if (entity is PlayerEntity) {
            val data = entity.model.data

            buffer.put(DataType.BYTE, data.size)
            buffer.putBytes(DataTransformation.ADD, data)
        }
    }
}