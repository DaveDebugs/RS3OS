package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

data class UpdateInvStopTransmit(val inv: Int, val flags: Int = 0) : GamePacket {

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateInvStopTransmit>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateInvStopTransmit {
            return UpdateInvStopTransmit(packet["inv"] as Int, packet["flags"] as Int)
        }

        override fun toMap(packet: UpdateInvStopTransmit): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["flags"] = packet.flags
            map["inv"] = packet.inv
            return map
        }
    }
}
