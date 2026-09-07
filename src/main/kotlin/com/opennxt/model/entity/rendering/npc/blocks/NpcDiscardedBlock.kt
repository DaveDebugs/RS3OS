package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * The NPC blocks whose fields this build READS AND THROWS AWAY.
 *
 * The same argument as the player side's `PlayerDiscardedBlock`: a body with no
 * store cannot be named from this binary, but it MUST be encodable to the exact
 * byte, because nothing in the extended-info section is length-prefixed and the
 * client's cursor advances over these fields whether or not anything reads them.
 * Fields are therefore positional -- `f1`, `f2`, `f3` -- and only the width and
 * the transform are claimed.
 */
class NpcDiscardedBlock(
    type: NpcUpdateBlockType,
    val f1: Int = 0,
    val f2: Int = 0,
    val f3: Int = 0
) : NpcUpdateBlock(type) {

    enum class Shape { U16_U32_U8, SKIP1_U16X3, SKIP2_U16 }

    class Layout(
        val shape: Shape,
        val body: String,
        val descriptor: String,
        val modes: IntArray,
        val width: Int
    )

    val layout: Layout = LAYOUTS[type]
        ?: throw IllegalArgumentException(
            "${type.name} is not one of the discarded-field bodies; see NpcDiscardedBlock.LAYOUTS"
        )

    init {
        when (layout.shape) {
            Shape.U16_U32_U8 -> {
                require(f1 in 0..0xffff) { "${type.name} f1 is a u16 (${layout.body}); got $f1" }
                require(f3 in 0..0xff) { "${type.name} f3 is a u8 (${layout.body}); got $f3" }
            }
            Shape.SKIP1_U16X3 -> require(f1 in 0..0xffff && f2 in 0..0xffff && f3 in 0..0xffff) {
                "${type.name} carries three u16 (${layout.body}); got $f1 / $f2 / $f3"
            }
            Shape.SKIP2_U16 -> require(f1 in 0..0xffff) {
                "${type.name} f1 is a u16 (${layout.body}); got $f1"
            }
        }
    }

    override fun encode(buffer: GamePacketBuilder) {
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

        const val SKIPPED_FILLER = 0

        val LAYOUTS: Map<NpcUpdateBlockType, Layout> = mapOf(
            NpcUpdateBlockType.DISCARDED_4 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(1, 2, 2), 7
            ),
            NpcUpdateBlockType.DISCARDED_8 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(1, 0, 2), 7
            ),
            NpcUpdateBlockType.DISCARDED_24 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(3, 3, 0), 7
            ),
            NpcUpdateBlockType.DISCARDED_27 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(0, 2, 2), 7
            ),
            NpcUpdateBlockType.DISCARDED_29 to Layout(
                Shape.U16_U32_U8, "", "", intArrayOf(0, 1, 2), 7
            ),
            NpcUpdateBlockType.DISCARDED_12 to Layout(
                Shape.SKIP1_U16X3, "", "", intArrayOf(0, 1, 0), 7
            ),
            NpcUpdateBlockType.DISCARDED_13 to Layout(
                Shape.SKIP2_U16, "", "", intArrayOf(3), 4
            )
        )

        val TYPES: Set<NpcUpdateBlockType> = LAYOUTS.keys
    }
}
