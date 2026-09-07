package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * The PLAYER blocks whose fields this build READS AND THROWS AWAY.
 */
class PlayerDiscardedBlock(
    type: UpdateBlockType,
    val f1: Int = 0,
    val f2: Int = 0,
    val f3: Int = 0
) : UpdateBlock(type) {

    /** The shape of one of these bodies. */
    enum class Shape { U16_U32_U8, SKIP1_U16X3, SKIP2_U16 }

    /** One body's measured layout. [modes] are descriptor bytes, in read order. */
    class Layout(
        val shape: Shape,
        val body: String,
        val descriptor: String,
        val modes: IntArray,
        val width: Int
    )

    val layout: Layout = LAYOUTS[type]
        ?: throw IllegalArgumentException(
            "${type.name} is not one of the discarded-field bodies; see PlayerDiscardedBlock.LAYOUTS"
        )

    init {
        when (layout.shape) {
            Shape.U16_U32_U8 -> {
                require(f1 in 0..0xffff) { "${type.name} f1 is a u16 (${layout.body}); got $f1" }
                require(f3 in 0..0xff) { "${type.name} f3 is a u8 (${layout.body}); got $f3" }
            }
            Shape.SKIP1_U16X3 -> {
                require(f1 in 0..0xffff && f2 in 0..0xffff && f3 in 0..0xffff) {
                    "${type.name} carries three u16 (${layout.body}); got $f1 / $f2 / $f3"
                }
            }
            Shape.SKIP2_U16 -> {
                require(f1 in 0..0xffff) { "${type.name} f1 is a u16 (${layout.body}); got $f1" }
            }
        }
    }

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        val m = layout.modes
        when (layout.shape) {
            Shape.U16_U32_U8 -> {
                ScalarModes.putU16(buffer, m[0], f1)
                ScalarModes.putU32(buffer, m[1], f2)
                ScalarModes.putU8(buffer, m[2], f3)
            }
            Shape.SKIP1_U16X3 -> {
                buffer.put(DataType.BYTE, SKIPPED_FILLER)
                ScalarModes.putU16(buffer, m[0], f1)
                ScalarModes.putU16(buffer, m[1], f2)
                ScalarModes.putU16(buffer, m[2], f3)
            }
            Shape.SKIP2_U16 -> {
                buffer.put(DataType.BYTE, SKIPPED_FILLER)
                buffer.put(DataType.BYTE, SKIPPED_FILLER)
                ScalarModes.putU16(buffer, m[0], f1)
            }
        }
    }

    companion object {

        /** What goes in a byte the client advances over without reading. */
        const val SKIPPED_FILLER = 0

        val LAYOUTS: Map<UpdateBlockType, Layout> = mapOf(
            UpdateBlockType.DISCARDED_13 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(3, 2, 3), 7
            ),
            UpdateBlockType.DISCARDED_9 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(1, 2, 1), 7
            ),
            UpdateBlockType.DISCARDED_3 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(1, 0, 0), 7
            ),
            UpdateBlockType.DISCARDED_27 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(3, 2, 0), 7
            ),
            UpdateBlockType.DISCARDED_16 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(2, 1, 0), 7
            ),
            UpdateBlockType.DISCARDED_23 to Layout(
                Shape.SKIP1_U16X3, "", "", intArrayOf(3, 3, 2), 7
            ),
            UpdateBlockType.DISCARDED_8 to Layout(
                Shape.SKIP2_U16, "", "", intArrayOf(3), 4
            )
        )

        /** The seven types this class can encode. */
        val TYPES: Set<UpdateBlockType> = LAYOUTS.keys
    }
}
