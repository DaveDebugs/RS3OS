package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 126 - "the packets after this one apply to THIS 8x8 zone".
 */
data class UpdateZonePartialFollows(val level: Int, val zoneX: Int, val zoneY: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateZonePartialFollows>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateZonePartialFollows =
            UpdateZonePartialFollows(packet["level"] as Int, packet["zoneX"] as Int, packet["zoneY"] as Int)

        override fun toMap(packet: UpdateZonePartialFollows): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["level"] = packet.level
            map["zoneX"] = packet.zoneX
            map["zoneY"] = packet.zoneY
            return map
        }
    }
}
