package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.LIFEPOINTS] -- mask bit 16, the block that puts the
 * TARGET INFORMATION panel's lifepoint bar and its number on the screen.
 *
 * @see NpcHitsBlock.Bar for the OTHER health field (the floating bar's 0..255
 */
class NpcLifepointsBlock(val entries: List<Entry>) : NpcUpdateBlock(NpcUpdateBlockType.LIFEPOINTS) {

    /** One `{kind, current, maximum}` triple. */
    data class Entry(val current: Int, val maximum: Int, val kind: Int = KIND_WIRE_BYTE) {
        init {
            require(kind in 0..0xff) { "kind is one byte; got $kind" }
            require(current in 0..MAX_CURRENT) {
                "current is a 4-byte field; got $current"
            }
            require(maximum in 0..MAX_MAXIMUM) {
                "maximum is a 3-byte field and cannot exceed $MAX_MAXIMUM; got $maximum"
            }
            require(current <= maximum) {
                "current ($current) above maximum ($maximum) draws a bar longer than its track"
            }
        }
    }

    init {
        require(entries.isNotEmpty()) { "a lifepoints block with no entries carries nothing" }
        require(entries.size <= MAX_ENTRIES) {
            "the entry count is one byte; got ${entries.size}"
        }
    }

    /** Exactly how many bytes [encode] writes: 1 + 8 per entry. */
    val width: Int get() = 1 + ENTRY_WIDTH * entries.size

    override fun encode(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, entries.size)
        for (entry in entries) {
            buffer.put(DataType.BYTE, entry.kind)
            buffer.put(DataType.INT, DataOrder.INVERSED_MIDDLE, entry.current)
            putMediumMiddle(buffer, entry.maximum)
        }
    }

    companion object {

        /**
         * The `kind` byte the reference client sends, in 1,535 of 1,535 observed records.
         * Replayed rather than interpreted -- see the class doc.
         */
        const val KIND_WIRE_BYTE = 3

        /** The count is one byte. */
        const val MAX_ENTRIES = 255

        /** `count` + 1 + 4 + 3 per entry, the width `npc_info_masks.py` measured. */
        const val ENTRY_WIDTH = 8

        /** `current` is four bytes. */
        const val MAX_CURRENT = Int.MAX_VALUE

        /** `maximum` is THREE bytes -- 100,000 (the training dummy) fits, 16.7M is the ceiling. */
        const val MAX_MAXIMUM = 0xFFFFFF

        /**
         * The three-byte `maximum`, in the order the protocol shows:
         * `[v>>8, v>>16, v]`. [DataOrder.MIDDLE] is the same permutation but
         * [GamePacketBuilder] refuses it for anything but a 4-byte INT
         * (`"Middle endian can only be used with an integer"`), so the three
         * bytes are written here rather than a fifth order being added to a
         * builder every packet in the tree shares.
         */
        fun putMediumMiddle(buffer: GamePacketBuilder, value: Int) {
            buffer.put(DataType.BYTE, (value shr 8) and 0xff)
            buffer.put(DataType.BYTE, (value shr 16) and 0xff)
            buffer.put(DataType.BYTE, value and 0xff)
        }

        /** One entry for an npc at [current] of [maximum]. */
        fun single(current: Int, maximum: Int): NpcLifepointsBlock =
            NpcLifepointsBlock(listOf(Entry(current, maximum)))
    }
}
