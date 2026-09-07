package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The client asking to walk to a tile CLICKED ON THE MINIMAP. 949 opcode 66,
 * fixed 18 bytes. Registered - until then a minimap click was
 * dropped on the floor with "no registered codec".
 */
class MoveMinimapClick(
    val x: Int,
    val y: Int,
    val flags: Int,
    val camera1: Int,
    val camera2: Int
) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MoveMinimapClick>(fields) {
        override fun fromMap(packet: Map<String, Any>): MoveMinimapClick =
            MoveMinimapClick(
                packet["x"] as Int, packet["y"] as Int, packet["flags"] as Int,
                packet["camera1"] as Int, packet["camera2"] as Int
            )

        override fun toMap(packet: MoveMinimapClick): Map<String, Any> = mapOf(
            "x" to packet.x, "y" to packet.y, "flags" to packet.flags,
            "junk1" to 0xff, "junk2" to 0xff, "junk3" to 0, "junk4" to 0x39,
            "junk5" to 0, "junk6" to 0, "junk7" to 0x59,
            "camera1" to packet.camera1, "camera2" to packet.camera2, "junk8" to 0x3f
        )
    }
}
