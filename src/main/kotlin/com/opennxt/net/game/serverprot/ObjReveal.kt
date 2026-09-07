package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 157 - "add this item to the tile UNLESS you are [receiver]".
 */
data class ObjReveal(val id: Int, val count: Int, val coord: Int, val receiver: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<ObjReveal>(fields) {
        override fun fromMap(packet: Map<String, Any>): ObjReveal = ObjReveal(
            packet["id"] as Int, packet["count"] as Int, packet["coord"] as Int, packet["receiver"] as Int
        )

        override fun toMap(packet: ObjReveal): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["id"] = packet.id
            map["count"] = packet.count
            map["coord"] = packet.coord
            map["receiver"] = packet.receiver
            return map
        }
    }
}
