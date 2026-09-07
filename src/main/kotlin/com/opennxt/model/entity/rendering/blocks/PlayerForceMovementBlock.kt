package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.FORCE_MOVEMENT] -- player bit 6, twelve bytes: two 3-axis
 * offsets, two cycle counts and an angle.
 */
class PlayerForceMovementBlock(
    val a1: Int,
    val a2: Int,
    val a3: Int,
    val b1: Int,
    val b2: Int,
    val b3: Int,
    val c1: Int,
    val c2: Int,
    val angle: Int
) : UpdateBlock(UpdateBlockType.FORCE_MOVEMENT) {

    init {
        for ((name, v) in listOf("a1" to a1, "a2" to a2, "a3" to a3, "b1" to b1, "b2" to b2, "b3" to b3)) {
            require(v in -128..127) {
                "$name is a SIGNED byte; got $v"
            }
        }
        require(c1 in 0..0xffff) { "c1 is a u16; got $c1" }
        require(c2 in 0..0xffff) { "c2 is a u16; got $c2" }
        require(angle in 0..0x3fff) {
            "the client; $angle is outside the 14-bit turn"
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        ScalarModes.putU8(buffer, MODE_A1, a1)
        ScalarModes.putU8(buffer, MODE_A2, a2)
        ScalarModes.putU8(buffer, MODE_A3, a3)
        ScalarModes.putU8(buffer, MODE_B1, b1)
        ScalarModes.putU8(buffer, MODE_B2, b2)
        ScalarModes.putU8(buffer, MODE_B3, b3)
        ScalarModes.putU16(buffer, MODE_C1, c1)
        ScalarModes.putU16(buffer, MODE_C2, c2)
        ScalarModes.putU16(buffer, MODE_ANGLE, angle)
    }

    companion object {
        /** = `00 01 00 03 03 01`. */
        const val MODE_A1 = 0
        const val MODE_A2 = 1
        const val MODE_A3 = 0
        const val MODE_B1 = 3
        const val MODE_B2 = 3
        const val MODE_B3 = 1

        /** / `d7` / `d8` = `02 02 00`. */
        const val MODE_C1 = 2
        const val MODE_C2 = 2
        const val MODE_ANGLE = 0

        /** 6 bytes + 3 shorts. */
        const val WIDTH = 12

        /** `and r10d, 0x3fff` at. */
        const val FULL_TURN = 0x4000
    }
}
