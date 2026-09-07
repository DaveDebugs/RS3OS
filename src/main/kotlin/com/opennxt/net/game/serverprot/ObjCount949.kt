package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * OBJ_COUNT_949 - build 949 ServerProt opcode 186, 8 bytes.
 */
data class ObjCount949(
    val coord: Int,
    val id: Int,
    val oldCount: Int,
    val newCount: Int
) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) :
        DynamicGamePacketCodec<ObjCount949>(fields) {

        override fun fromMap(packet: Map<String, Any>): ObjCount949 = ObjCount949(
            packet["coord"] as Int,
            packet["id"] as Int,
            packet["oldCount"] as Int,
            packet["newCount"] as Int
        )

        override fun toMap(packet: ObjCount949): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["coord"] = packet.coord
            map["id"] = packet.id
            map["oldCount"] = packet.oldCount
            map["newCount"] = packet.newCount
            return map
        }
    }
}
