package com.opennxt.net.game.serverprot.variables

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 51 (VARBIT_LARGE), size 6.
 */
data class VarbitLarge(val value: Int, val id: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<VarbitLarge>(fields) {
        override fun fromMap(packet: Map<String, Any>): VarbitLarge = VarbitLarge(packet["value"] as Int, packet["id"] as Int)

        override fun toMap(packet: VarbitLarge): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["value"] = packet.value
            map["id"] = packet.id
            return map
        }
    }
}
