package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 202 - change the stack size of an item already on a tile.
 */
data class ObjCount(val coord: Int, val id: Int, val oldCount: Int, val newCount: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ObjCount>(fields) {
        override fun fromMap(packet: Map<String, Any>): ObjCount = ObjCount(
            packet["coord"] as Int, packet["id"] as Int, packet["oldCount"] as Int, packet["newCount"] as Int
        )

        override fun toMap(packet: ObjCount): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["coord"] = packet.coord
            map["id"] = packet.id
            map["oldCount"] = packet.oldCount
            map["newCount"] = packet.newCount
            return map
        }
    }
}
