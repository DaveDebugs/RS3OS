package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

data class UpdateInvFull(
    val inv: Int,
    val slots: List<InvEntry?>,
    val flags: Int = 0
) : GamePacket {

    object Codec : GamePacketCodec<UpdateInvFull> {

        override fun encode(packet: UpdateInvFull, buf: GamePacketBuilder) {
            require(packet.inv in 0..0xffff) { "inv id does not fit a ushort: ${packet.inv}" }
            require(packet.flags in 0..0xff) { "flags does not fit a ubyte: ${packet.flags}" }
            val withParams = packet.flags and 2 != 0
            require(withParams || packet.slots.none { it != null && it.params.isNotEmpty() }) {
                "a slot carries params but flags bit 1 is clear, so the client will not read them and every " +
                    "byte after the first one slides. flags=${packet.flags}"
            }
            require(packet.slots.size in 0..0xffff) { "slot count does not fit a ushort: ${packet.slots.size}" }

            buf.put(DataType.SHORT, packet.inv)
            buf.put(DataType.BYTE, packet.flags)
            buf.put(DataType.SHORT, packet.slots.size)

            packet.slots.forEach { entry ->
                buf.put(DataType.MEDIUM, if (entry == null) 0 else entry.obj + 1)
                putAmount(buf, entry?.amount ?: 0)
                // Unconditional, unlike PARTIAL: an empty slot still gets its
                // count byte, and here it still gets its param count too.
                if (withParams) putParams(buf, entry?.params ?: emptyList())
            }
        }

        override fun decode(buf: GamePacketReader): UpdateInvFull {
            val inv = buf.getUnsigned(DataType.SHORT).toInt()
            val flags = buf.getUnsigned(DataType.BYTE).toInt()
            val count = buf.getUnsigned(DataType.SHORT).toInt()

            val slots = ArrayList<InvEntry?>(count)
            repeat(count) {
                val objPlusOne = buf.getUnsigned(DataType.MEDIUM).toInt()
                val amount = getAmount(buf)
                val params = if (flags and 2 != 0) getParams(buf) else emptyList()
                slots.add(if (objPlusOne == 0) null else InvEntry(objPlusOne - 1, amount, params))
            }

            return UpdateInvFull(inv, slots, flags)
        }

        /**
         * The one-byte-with-0xff-escape amount, shared with [UpdateInvPartial].
         *
         * `0..254` is the byte form. EVERYTHING else - 255 included, and any
         * negative - is the escape, because 0xff is the marker and can never be
         * the value.
         */
        fun putAmount(buf: GamePacketBuilder, amount: Int) {
            if (amount in 0..254) {
                buf.put(DataType.BYTE, amount)
            } else {
                buf.put(DataType.BYTE, 0xff)
                buf.put(DataType.INT, amount)
            }
        }

        fun getAmount(buf: GamePacketReader): Int {
            val first = buf.getUnsigned(DataType.BYTE).toInt()
            return if (first == 0xff) buf.getSigned(DataType.INT).toInt() else first
        }

        /**
         * The `flags and 2` parameter list, shared with [UpdateInvPartial]:
         * a ubyte count then that many `(ushort key, int value)` pairs.
         *
         * The count is a BYTE, so a slot cannot carry more than 255 params;
         * [InvParam]'s own constructor bounds the key. Writing an over-long
         * list would truncate the count and leave the rest of the pairs to be
         * read as the next slot's object id.
         */
        fun putParams(buf: GamePacketBuilder, params: List<InvParam>) {
            require(params.size in 0..0xff) { "param count does not fit a ubyte: ${params.size}" }
            buf.put(DataType.BYTE, params.size)
            params.forEach { param ->
                buf.put(DataType.SHORT, param.key)
                buf.put(DataType.INT, param.value)
            }
        }

        fun getParams(buf: GamePacketReader): List<InvParam> {
            val n = buf.getUnsigned(DataType.BYTE).toInt()
            if (n == 0) return emptyList()
            val out = ArrayList<InvParam>(n)
            repeat(n) {
                out.add(InvParam(buf.getUnsigned(DataType.SHORT).toInt(), buf.getSigned(DataType.INT).toInt()))
            }
            return out
        }
    }
}
