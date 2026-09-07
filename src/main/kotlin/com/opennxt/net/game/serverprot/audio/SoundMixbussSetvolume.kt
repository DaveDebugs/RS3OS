package com.opennxt.net.game.serverprot.audio

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 128 (SOUND_MIXBUSS_SETVOLUME), size 4.
 */
data class SoundMixbussSetvolume(val bus: Int, val gain: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SoundMixbussSetvolume>(fields) {
        override fun fromMap(packet: Map<String, Any>): SoundMixbussSetvolume = SoundMixbussSetvolume(packet["bus"] as Int, packet["gain"] as Int)

        override fun toMap(packet: SoundMixbussSetvolume): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["bus"] = packet.bus
            map["gain"] = packet.gain
            return map
        }
    }
}
