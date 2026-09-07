package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

data class SetMapFlag(
    val target: Int,
    val unk1: Int,
    /** Destination y within the loaded scene. Add the scene base from REBUILD_NORMAL. */
    val localY: Int,
    /** Destination x within the loaded scene, sent negated (ubytec). */
    val localX: Int,
    val unk4: Int,
    val unk5: Int,
    val unk6: Int
) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<SetMapFlag>(fields) {
        override fun fromMap(packet: Map<String, Any>): SetMapFlag {
            return SetMapFlag(
                packet["target"] as Int,
                packet["unk1"] as Int,
                packet["localY"] as Int,
                packet["localX"] as Int,
                packet["unk4"] as Int,
                packet["unk5"] as Int,
                packet["unk6"] as Int
            )
        }

        override fun toMap(packet: SetMapFlag): Map<String, Any> {
            val map = Object2ObjectOpenHashMap<String, Any>()
            map["target"] = packet.target
            map["unk1"] = packet.unk1
            map["localY"] = packet.localY
            map["localX"] = packet.localX
            map["unk4"] = packet.unk4
            map["unk5"] = packet.unk5
            map["unk6"] = packet.unk6
            return map
        }
    }
}