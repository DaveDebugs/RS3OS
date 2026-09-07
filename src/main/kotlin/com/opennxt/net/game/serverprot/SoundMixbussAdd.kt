package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * SOUND_MIXBUSS_ADD - build 949 ServerProt opcode 217, 6 bytes.
 */
data class SoundMixbussAdd(val bus: Int, val parent: Int, val gain: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) :
        DynamicGamePacketCodec<SoundMixbussAdd>(fields) {

        override fun fromMap(packet: Map<String, Any>): SoundMixbussAdd =
            SoundMixbussAdd(packet["bus"] as Int, packet["parent"] as Int, packet["gain"] as Int)

        override fun toMap(packet: SoundMixbussAdd): Map<String, Any> = mapOf(
            "bus" to packet.bus,
            "parent" to packet.parent,
            "gain" to packet.gain
        )
    }
}
