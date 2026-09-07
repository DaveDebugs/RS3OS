package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 14 - take an item off a tile inside the current zone.
 */
data class ObjDel(val coord: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ObjDel>(fields) {
        override fun fromMap(packet: Map<String, Any>): ObjDel =
            ObjDel(packet["coord"] as Int, packet["id"] as Int)

        override fun toMap(packet: ObjDel): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["coord"] = packet.coord
            map["id"] = packet.id
            return map
        }
    }
}
