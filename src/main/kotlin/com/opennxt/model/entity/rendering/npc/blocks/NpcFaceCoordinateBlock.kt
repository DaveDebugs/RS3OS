package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.FACE_COORDINATE] -- turn the npc to face a world tile.
 */
class NpcFaceCoordinateBlock(val tileX: Int, val tileY: Int) :
    NpcUpdateBlock(NpcUpdateBlockType.FACE_COORDINATE) {

    init {
        require(tileX in 0..MAX_TILE && tileY in 0..MAX_TILE) {
            "a tile is encoded as tile*2+1 in an unsigned short, so it must be 0..$MAX_TILE; " +
                "got ($tileX, $tileY)"
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
        buffer.put(DataType.SHORT, DataOrder.BIG, DataTransformation.NONE, tileX * 2 + 1)
        buffer.put(DataType.SHORT, DataOrder.BIG, DataTransformation.ADD, tileY * 2 + 1)
    }

    companion object {
        /** `tile * 2 + 1` must fit an unsigned short. */
        const val MAX_TILE = (0xFFFF - 1) / 2
    }
}
