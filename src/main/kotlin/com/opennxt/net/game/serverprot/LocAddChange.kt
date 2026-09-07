package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 62 - place or replace a loc on a tile inside the current zone.
 * This is the packet a door opens with.
 */
data class LocAddChange(val shapeRotation: Int, val loc: Int, val coord: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocAddChange>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocAddChange =
            LocAddChange(packet["shapeRotation"] as Int, packet["loc"] as Int, packet["coord"] as Int)

        override fun toMap(packet: LocAddChange): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["shapeRotation"] = packet.shapeRotation
            map["loc"] = packet.loc
            map["coord"] = packet.coord
            return map
        }
    }
}
