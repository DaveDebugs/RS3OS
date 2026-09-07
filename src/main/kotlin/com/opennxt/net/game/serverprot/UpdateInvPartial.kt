package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

data class UpdateInvPartial(
    val inv: Int,
    val entries: List<Pair<Int, InvEntry?>>,
    val flags: Int = 0
) : GamePacket {

    object Codec : GamePacketCodec<UpdateInvPartial> {

        override fun encode(packet: UpdateInvPartial, buf: GamePacketBuilder) {
            require(packet.inv in 0..0xffff) { "inv id does not fit a ushort: ${packet.inv}" }
            require(packet.flags in 0..0xff) { "flags does not fit a ubyte: ${packet.flags}" }
            val withParams = packet.flags and 2 != 0
            require(withParams || packet.entries.none { it.second?.params?.isNotEmpty() == true }) {
                "an entry carries params but flags bit 1 is clear, so the client will not read them and the " +
                    "rest of the packet decodes as garbage - this loop is driven by the payload length, not " +
                    "by a count, so nothing at either end would report it. flags=${packet.flags}"
            }

            buf.put(DataType.SHORT, packet.inv)
            buf.put(DataType.BYTE, packet.flags)

            packet.entries.forEach { (slot, entry) ->
                require(slot in 0..0x7fff) {
                    "slot $slot cannot be encoded as a smart: the client recovers a 2-byte smart as " +
                        "(wire + 0x8000) and 0xffff, so only 0..0x7fff round-trip"
                }
                buf.putSmart(slot)

                val objPlusOne = if (entry == null) 0 else entry.obj + 1
                buf.put(DataType.MEDIUM, objPlusOne)

                // The conditional. Present ONLY when the slot holds something -
                // and the params ride inside the same gate.
                if (objPlusOne != 0) {
                    UpdateInvFull.Codec.putAmount(buf, entry!!.amount)
                    if (withParams) UpdateInvFull.Codec.putParams(buf, entry.params)
                }
            }
        }

        override fun decode(buf: GamePacketReader): UpdateInvPartial {
            val inv = buf.getUnsigned(DataType.SHORT).toInt()
            val flags = buf.getUnsigned(DataType.BYTE).toInt()

            val entries = ArrayList<Pair<Int, InvEntry?>>()
            while (buf.buffer.isReadable) {
                val slot = getSmart(buf)
                val objPlusOne = buf.getUnsigned(DataType.MEDIUM).toInt()
                if (objPlusOne == 0) {
                    entries.add(slot to null)
                } else {
                    val amount = UpdateInvFull.Codec.getAmount(buf)
                    val params = if (flags and 2 != 0) UpdateInvFull.Codec.getParams(buf) else emptyList()
                    entries.add(slot to InvEntry(objPlusOne - 1, amount, params))
                }
            }

            return UpdateInvPartial(inv, entries, flags)
        }

        fun getSmart(buf: GamePacketReader): Int {
            val peek = buf.buffer.getUnsignedByte(buf.buffer.readerIndex()).toInt()
            return if (peek < 0x80) buf.getUnsigned(DataType.BYTE).toInt()
            else (buf.getUnsigned(DataType.SHORT).toInt() + 0x8000) and 0xffff
        }
    }
}
