package com.opennxt.net.game.serverprot.interfaces

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 88 (IF_MOVESUB), size 8.
 */
data class IfMovesub(val from: Int, val to: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfMovesub>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfMovesub = IfMovesub(packet["from"] as Int, packet["to"] as Int)

        override fun toMap(packet: IfMovesub): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["from"] = packet.from
            map["to"] = packet.to
            return map
        }
    }
}
