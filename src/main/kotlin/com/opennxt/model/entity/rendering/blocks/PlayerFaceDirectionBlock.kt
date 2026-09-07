package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.FACE_DIRECTION] -- an absolute compass angle.
 */
class PlayerFaceDirectionBlock(val angle: Int) : UpdateBlock(UpdateBlockType.FACE_DIRECTION) {

    init {
        require(angle in 0..0x3fff) {
            "the client; $angle is outside the 14-bit turn"
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.SHORT, DataOrder.LITTLE, DataTransformation.ADD, angle)
    }

    companion object {
        /** 16384 units to a full turn. */
        const val FULL_TURN = 0x4000

        /** 16384 / 2pi - the units-per-radian this angle is measured in. */
        const val UNITS_PER_RADIAN = FULL_TURN / (2.0 * Math.PI)

        /**
         * The angle that turns a player standing on ([fromX], [fromZ]) to look at the loc whose
         * footprint origin is ([targetX], [targetZ]) and whose ROTATED footprint is
         * [sizeX] x [sizeZ] tiles.
         */
        fun towards(
            fromX: Int,
            fromZ: Int,
            targetX: Int,
            targetZ: Int,
            sizeX: Int = 1,
            sizeZ: Int = 1
        ): Int {
            require(sizeX >= 1 && sizeZ >= 1) { "a loc footprint is at least 1x1; got ${sizeX}x$sizeZ" }
            val dx = (2 * fromX + 1) - (2 * targetX + sizeX)
            val dz = (2 * fromZ + 1) - (2 * targetZ + sizeZ)
            return (Math.atan2(dx.toDouble(), dz.toDouble()) * UNITS_PER_RADIAN).toInt() and (FULL_TURN - 1)
        }
    }
}
