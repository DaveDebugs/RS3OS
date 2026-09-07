package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * OBJ_REVEAL_949 - build 949 ServerProt opcode 211, 8 bytes.
 */
data class ObjReveal949(
    val count: Int,
    val id: Int,
    val coord: Int,
    val receiver: Int
) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) :
        DynamicGamePacketCodec<ObjReveal949>(fields) {

        override fun fromMap(packet: Map<String, Any>): ObjReveal949 = ObjReveal949(
            packet["count"] as Int,
            packet["id"] as Int,
            packet["coord"] as Int,
            packet["receiver"] as Int
        )

        override fun toMap(packet: ObjReveal949): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["count"] = packet.count
            map["id"] = packet.id
            map["coord"] = packet.coord
            map["receiver"] = packet.receiver
            return map
        }
    }
}
