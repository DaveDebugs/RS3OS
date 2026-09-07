package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 41 - put an item on a tile inside the zone the last
 * [UpdateZonePartialFollows] named.
 */
data class ObjAdd(val coord: Int, val count: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ObjAdd>(fields) {
        override fun fromMap(packet: Map<String, Any>): ObjAdd =
            ObjAdd(packet["coord"] as Int, packet["count"] as Int, packet["id"] as Int)

        override fun toMap(packet: ObjAdd): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["coord"] = packet.coord
            map["count"] = packet.count
            map["id"] = packet.id
            return map
        }
    }
}
