package com.opennxt.net.game.serverprot.ifaces

import com.opennxt.model.InterfaceHash
import com.opennxt.net.buf.DataOrder
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

/**
 * **IF_OPENSUB_ACTIVE_NPC** -- 949 ServerProt opcode **71**, size **25**.
 */
data class IfOpensubActiveNpc(
    /** The npc's NPC_INFO slot ([com.opennxt.model.world.WorldNpc.infoIndex]). */
    val npcIndex: Int,
    /** `(parentInterface shl 16) or component` -- the mount point. */
    val parent: InterfaceHash,
    /** The interface to mount there. */
    val id: Int,
    /** Replayed; see the class doc. */
    val flag: Int = OBSERVED_FLAG_BYTE
) : GamePacket {

    object Codec : GamePacketCodec<IfOpensubActiveNpc> {
        override fun encode(packet: IfOpensubActiveNpc, buf: GamePacketBuilder) {
            require(packet.npcIndex in 0..0xffff) { "npc index out of the u16 field: ${packet.npcIndex}" }
            require(packet.id in 0..0xffff) { "interface id out of the u16 field: ${packet.id}" }
            require(packet.flag in 0..0xff) { "flag is one byte: ${packet.flag}" }
            buf.put(DataType.INT, 0)                                   // 0..3
            buf.put(DataType.SHORT, DataOrder.LITTLE, packet.npcIndex) // 4..5
            buf.put(DataType.INT, DataOrder.LITTLE, packet.parent.hash)// 6..9
            buf.put(DataType.INT, 0)                                   // 10..13
            buf.put(DataType.INT, 0)                                   // 14..17
            buf.put(DataType.INT, 0)                                   // 18..21
            buf.put(DataType.SHORT, packet.id)                         // 22..23
            buf.put(DataType.BYTE, packet.flag)                        // 24
        }

        override fun decode(buf: GamePacketReader): IfOpensubActiveNpc {
            buf.getSigned(DataType.INT)
            val index = buf.getUnsigned(DataType.SHORT, DataOrder.LITTLE).toInt()
            val parent = buf.getSigned(DataType.INT, DataOrder.LITTLE).toInt()
            buf.getSigned(DataType.INT); buf.getSigned(DataType.INT); buf.getSigned(DataType.INT)
            val id = buf.getUnsigned(DataType.SHORT).toInt()
            val flag = buf.getUnsigned(DataType.BYTE).toInt()
            return IfOpensubActiveNpc(index, InterfaceHash(parent), id, flag)
        }
    }

    companion object {
        /** Byte 24 in 55 of 55 observed frames. */
        const val OBSERVED_FLAG_BYTE = 0xff

        /** `toplevel_v2_targeting` -- opened at login by `WorldPlayer`'s HUD mount. */
        const val TARGETING_INTERFACE = 1488

        /** The component of 1488 the target panel mounts into. */
        const val TARGETING_COMPONENT = 4

        /** `toplevel_v2_target_info` -- the name / level / lifepoints panel. */
        const val TARGET_INFO_INTERFACE = 1490

        /** The mount point, `1488:4`. */
        val TARGET_INFO_PARENT: InterfaceHash
            get() = InterfaceHash((TARGETING_INTERFACE shl 16) or TARGETING_COMPONENT)

        /** The reference frame for "show the target panel for this npc". */
        fun targetInfo(npcIndex: Int): IfOpensubActiveNpc =
            IfOpensubActiveNpc(npcIndex, TARGET_INFO_PARENT, TARGET_INFO_INTERFACE)
    }
}
