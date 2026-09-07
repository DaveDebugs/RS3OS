package com.opennxt.net.game.serverprot.interfaces

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * ServerProt 108 (IF_SETCOLOUR), size 6.
 */
data class IfSetcolour(val colour: Int, val component: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfSetcolour>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfSetcolour = IfSetcolour(packet["colour"] as Int, packet["component"] as Int)

        override fun toMap(packet: IfSetcolour): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["colour"] = packet.colour
            map["component"] = packet.component
            return map
        }
    }
}
