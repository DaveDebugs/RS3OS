package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 28 - the same zone cursor as [UpdateZonePartialFollows], plus a
 * CLEAR of the 8x8 zone.
 */
data class UpdateZoneFullFollows(val level: Int, val zoneX: Int, val zoneY: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<UpdateZoneFullFollows>(fields) {
        override fun fromMap(packet: Map<String, Any>): UpdateZoneFullFollows =
            UpdateZoneFullFollows(packet["level"] as Int, packet["zoneX"] as Int, packet["zoneY"] as Int)

        override fun toMap(packet: UpdateZoneFullFollows): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["level"] = packet.level
            map["zoneX"] = packet.zoneX
            map["zoneY"] = packet.zoneY
            return map
        }
    }
}
