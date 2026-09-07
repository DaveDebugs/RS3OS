package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 47 - play an animation on a loc already on a tile.
 */
data class LocAnim(val delayOrSpeed: Int, val coord: Int, val anim: Int, val shapeRotation: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<LocAnim>(fields) {
        override fun fromMap(packet: Map<String, Any>): LocAnim = LocAnim(
            packet["delayOrSpeed"] as Int, packet["coord"] as Int,
            packet["anim"] as Int, packet["shapeRotation"] as Int
        )

        override fun toMap(packet: LocAnim): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["delayOrSpeed"] = packet.delayOrSpeed
            map["coord"] = packet.coord
            map["anim"] = packet.anim
            map["shapeRotation"] = packet.shapeRotation
            return map
        }
    }
}
