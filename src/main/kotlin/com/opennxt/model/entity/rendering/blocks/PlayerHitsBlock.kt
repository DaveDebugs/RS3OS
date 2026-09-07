package com.opennxt.model.entity.rendering.blocks

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.ScalarModes
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder

/**
 * [UpdateBlockType.HITS] -- hit splats (and, on the client, health bars).
 */
class PlayerHitsBlock(
    val hits: List<Hit>,
    val bars: List<Bar> = emptyList()
) : UpdateBlock(UpdateBlockType.HITS) {

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
            require(f1 in 0..SMART16_MAX) { "f1 is a smart16; got $f1" }
            require(f2 in 0..SMART16_MAX) { "f2 is a smart16; got $f2" }
            require(f2 != BAR_ESCAPE) {
                "f2 = 0x7fff takes the removal path, which was not decoded"
            }
            require(f3 in 0..SMART16_MAX) { "f3 is a smart16; got $f3" }
            require(f4 in 0..0xff) { "f4 is one ADD byte; got $f4" }
            require(f5 in 0..0xff) { "f5 is one ADD byte; got $f5" }
            require(f6 in ScalarModes.MIN_BAR_SIGNED..ScalarModes.MAX_BAR_SIGNED) {
                "f6 is the signed smart of; got $f6"
            }
            require(f7 in 0..0xff) { "f7 is one NEGATE byte; got $f7" }
            require(f8 in 0..0xff) { "f8 is one ADD byte; got $f8" }
        }

        /** True when the client will read [f5] -- `test r12d,r12d`. */
        val hasF5: Boolean get() = f2 != 0

        /** True when the client will read [f7] -- `cmp eax,-1; jle`. */
        val hasF7: Boolean get() = f6 > -1

        /** True when the client will read [f8] -- both gates at once. */
        val hasF8: Boolean get() = hasF7 && hasF5

        /** Exactly how many bytes [encodeInto] writes. 5 at the smallest, 12 at the largest. */
        val width: Int
            get() = smartWidth(f1) + smartWidth(f2) + smartWidth(f3) + 1 +
                (if (hasF5) 1 else 0) + ScalarModes.barSignedLength(f6) +
                (if (hasF7) 1 else 0) + (if (hasF8) 1 else 0)

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

    data class Hit(val type: Int, val amount: Int, val delay: Int = 0) {
        init {
            require(type in 0..SMART16_MAX) { "splat type $type is not encodable as a smart16" }
            require(type != ESCAPE_FIVE_FIELD) {
                "splat type 0x7fff switches the client to the five-field record"
            }
            require(type != ESCAPE_U8_AMOUNT) {
                "splat type 0x7ffe switches the client to the u8-amount record"
            }
            require(amount in 0..SMART16_MAX) { "splat amount $amount is not encodable as a smart16" }
            require(delay in 0..SMART16_MAX) { "splat delay $delay is not encodable as a smart16" }
        }
    }

    init {
        require(hits.size in 0..0xff) {
            "the hit count is one plain byte; ${hits.size} splats do not fit"
        }
        require(bars.size in 0..0xff) {
            "the bar count is one plain byte; ${bars.size} bars do not fit"
        }
    }

    /** The bars that will actually be written -- empty when the switch is off. */
    fun writtenBars(): List<Bar> = if (barsEnabled) bars else emptyList()

    override fun encode(buffer: GamePacketBuilder, viewer: WorldPlayer, entity: Entity) {
        buffer.put(DataType.BYTE, hits.size)
        for (hit in hits) {
            buffer.putSmart(hit.type)
            buffer.putSmart(hit.amount)
            buffer.putSmart(hit.delay)
        }
        val written = writtenBars()
        buffer.put(DataType.BYTE, written.size)
        for (bar in written) bar.encodeInto(buffer)
    }

    companion object {
        /** `GamePacketBuilder.putSmart` tops out here; so does the inlined reader. */
        const val SMART16_MAX = 0x7fff

        const val ESCAPE_FIVE_FIELD = 0x7fff
        const val ESCAPE_U8_AMOUNT = 0x7ffe

        /**
         * The default bar count, which is a complete encoding and not a stub:
         */
        const val BAR_COUNT = 0

        /** at -- undecoded path. */
        const val BAR_ESCAPE = 0x7fff

        /** at cursor-1 for the inlined `add al,0x80`. */
        const val MODE_F4 = 1

        /** at cursor-1 for the inlined `add cl,0x80`. */
        const val MODE_F5 = 1

        /** at cursor-1 for the inlined `neg al`. */
        const val MODE_F7 = 2

        /** at cursor-1 for the inlined `add al,0x80`. */
        const val MODE_F8 = 1

        /** The smallest and largest a [Bar] can be. Both are reached by the checks. */
        const val BAR_MIN_WIDTH = 5
        const val BAR_MAX_WIDTH = 12

        /**
         * `-Dopennxt.experiment.player.healthbars=false` writes a bar count of 0
         * whatever the caller queued. Its own switch, separate from
         * `player.block.hits`, so the splats can stay on the wire while the bars
         * come off it.
         */
        val barsEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.player.healthbars") != "false"

        /** `putSmart`: one byte below 128, two above. */
        fun smartWidth(value: Int): Int = if (value >= 128) 2 else 1

        fun single(type: Int, amount: Int, delay: Int = 0) = PlayerHitsBlock(listOf(Hit(type, amount, delay)))
    }
}
