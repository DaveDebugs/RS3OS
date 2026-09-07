package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 1 - remove the loc of the given shape from a tile in the current
 * zone.
 */
data class LocDel(val coord: Int, val shapeRotation: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocDel>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocDel =
            LocDel(packet["coord"] as Int, packet["shapeRotation"] as Int)

        override fun toMap(packet: LocDel): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["coord"] = packet.coord
            map["shapeRotation"] = packet.shapeRotation
            return map
        }
    }
}
