package com.opennxt.net.game.serverprot.audio

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 37 (MIDI_SONG), size 5.
 */
data class MidiSong(val volume: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MidiSong>(fields) {
        override fun fromMap(packet: Map<String, Any>): MidiSong = MidiSong(packet["volume"] as Int, packet["id"] as Int)

        override fun toMap(packet: MidiSong): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["volume"] = packet.volume
            map["id"] = packet.id
            return map
        }
    }
}
