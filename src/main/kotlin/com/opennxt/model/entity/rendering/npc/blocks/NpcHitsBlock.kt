package com.opennxt.model.entity.rendering.npc.blocks

import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlock
import com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType
import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [NpcUpdateBlockType.HITS] -- hit splats, and the health-bar list that shares
 * the block.
 */
class NpcHitsBlock(
    val hits: List<Hit>,
    val bars: List<Bar> = emptyList()
) : NpcUpdateBlock(NpcUpdateBlockType.HITS) {

    data class Bar(
        val f1: Int,
        val f2: Int,
        val f3: Int,
        val f4: Int,
        val f5: Int = 0,
        val f6: Int = ScalarModes.MIN_BAR_SIGNED,
        val f7: Int = 0,
        val f8: Int = 0
    ) {
        init {
            require(f1 in 0..SMART_MAX) { "f1 is a smart16; got $f1" }
            require(f2 in 0..SMART_MAX) { "f2 is a smart16; got $f2" }
            require(f2 != BAR_ESCAPE) {
                "f2 = 0x7fff takes the path, which was not decoded"
            }
            require(f3 in 0..SMART_MAX) { "f3 is a smart16; got $f3" }
            require(f4 in 0..0xff) { "f4 is one PLAIN byte; got $f4" }
            require(f5 in 0..0xff) { "f5 is one ADD byte; got $f5" }
            require(f6 in ScalarModes.MIN_BAR_SIGNED..ScalarModes.MAX_BAR_SIGNED) {
                "f6 is the signed smart of; got $f6"
            }
            require(f7 in 0..0xff) { "f7 is one ADD byte; got $f7" }
            require(f8 in 0..0xff) { "f8 is one NEGATE byte; got $f8" }
        }

        /** `test ebx,ebx; je`. */
        val hasF5: Boolean get() = f2 != 0

        /** `cmp r13d,-1; jle`. */
        val hasF7: Boolean get() = f6 > -1

        val hasF8: Boolean get() = hasF7 && hasF5

        /** Exactly how many bytes [encodeInto] writes: 5 at the smallest, 12 at the largest. */
        val width: Int
            get() = smartWidth(f1) + smartWidth(f2) + smartWidth(f3) + 1 +
                (if (hasF5) 1 else 0) + ScalarModes.barSignedLength(f6) +
                (if (hasF7) 1 else 0) + (if (hasF8) 1 else 0)

        companion object {

            /**
             * The bar the reference client sends for an npc at [current] of [maximum]: the
             * FLOATING bar over an npc's head, which is a different thing from
             * the target panel's bar ([NpcLifepointsBlock], mask bit 16).
             */
            fun health(current: Int, maximum: Int): Bar {
                require(maximum > 0) { "a bar with maximum $maximum has no fraction to draw" }
                require(current in 0..maximum) { "current $current is not in 0..$maximum" }
                return Bar(f1 = 0, f2 = 0, f3 = 0, f4 = 255 * current / maximum, f6 = -1)
            }

            /**
             * The "take this bar away" record: `f2 = 0x7fff`, no payload.
             */
            const val REMOVE_BAR_SENTINEL = BAR_ESCAPE
        }

        fun encodeInto(buffer: GamePacketBuilder) {
            buffer.putSmart(f1)
            buffer.putSmart(f2)
            buffer.putSmart(f3)
            ScalarModes.putU8(buffer, MODE_F4, f4)
            if (hasF5) ScalarModes.putU8(buffer, MODE_F5, f5)
            ScalarModes.putBarSigned(buffer, f6)
            if (hasF7) ScalarModes.putU8(buffer, MODE_F7, f7)
            if (hasF8) ScalarModes.putU8(buffer, MODE_F8, f8)
        }
    }

    init {
        require(hits.isNotEmpty() || bars.isNotEmpty()) {
            "a hits block with no hits and no bars carries nothing"
        }
        require(hits.size <= MAX_HITS) {
            "the hit count is one byte; got ${hits.size}"
        }
        require(bars.size <= MAX_HITS) {
            "the bar count is one byte; got ${bars.size}"
        }
    }

    /** The bars that will actually be written -- empty when the switch is off. */
    fun writtenBars(): List<Bar> = if (barsEnabled) bars else emptyList()

    override fun encode(buffer: GamePacketBuilder) {
        buffer.put(DataType.BYTE, DataTransformation.SUBTRACT, hits.size)
        for (hit in hits) {
            buffer.putSmart(hit.type)
            buffer.putSmart(hit.amount)
            buffer.putSmart(hit.delay)
        }
        val written = writtenBars()
        buffer.put(DataType.BYTE, DataTransformation.NEGATE, written.size)
        for (bar in written) bar.encodeInto(buffer)
    }

    data class Hit(val type: Int, val amount: Int, val delay: Int = 0) {
        init {
            require(type in 0 until ESCAPE_LOW) {
                "splat type must be 0..${ESCAPE_LOW - 1}: $ESCAPE_LOW (0x7ffe) and $ESCAPE_HIGH " +
                    "(0x7fff) switch to field lists this pass did not decode"
            }
            require(amount in 0..SMART_MAX) { "amount is a smart16, 0..$SMART_MAX; got $amount" }
            require(delay in 0..SMART_MAX) { "delay is a smart16, 0..$SMART_MAX; got $delay" }
        }
    }

    companion object {
        /** reads one byte. */
        const val MAX_HITS = 255

        const val HEALTH_BARS = 0

        /** `cmp ebx, r11d` (0x7fff) at -- undecoded path. */
        const val BAR_ESCAPE = 0x7FFF

        const val MODE_F4 = 3

        /** = 0x01 = ADD. */
        const val MODE_F5 = 1

        /** = 0x01 = ADD. The player's f7 is NEGATE. */
        const val MODE_F7 = 1

        /** = 0x02 = NEGATE. The player's f8 is ADD. */
        const val MODE_F8 = 2

        const val BAR_MIN_WIDTH = 5
        const val BAR_MAX_WIDTH = 12

        /** `-Dopennxt.experiment.npcs.healthbars=false` forces a bar count of 0. */
        val barsEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.npcs.healthbars") != "false"

        /** `putSmart`: one byte below 128, two above. */
        fun smartWidth(value: Int): Int = if (value >= 128) 2 else 1

        /** tops out at 0x7fff. */
        const val SMART_MAX = 0x7FFF

        /** `cmp ebx, 0x7ffe` -- alternate form, not decoded. */
        const val ESCAPE_LOW = 0x7FFE

        /** `cmp ebx, r11d` (0x7fff) -- alternate form, not decoded. */
        const val ESCAPE_HIGH = 0x7FFF
    }
}
