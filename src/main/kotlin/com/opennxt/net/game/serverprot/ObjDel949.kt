package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * OBJ_DEL_949 - build 949 ServerProt opcode 12, 4 bytes.
 */
data class ObjDel949(val id: Int, val coord: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) :
        DynamicGamePacketCodec<ObjDel949>(fields) {

        override fun fromMap(packet: Map<String, Any>): ObjDel949 =
            ObjDel949(packet["id"] as Int, packet["coord"] as Int)

        override fun toMap(packet: ObjDel949): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["id"] = packet.id
            map["coord"] = packet.coord
            return map
        }
    }
}
