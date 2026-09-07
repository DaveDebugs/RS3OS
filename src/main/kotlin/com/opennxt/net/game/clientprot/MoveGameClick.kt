package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The client asking to walk to a tile. 949 opcode 107, fixed 5 bytes.
 *
 * Layout read straight off the wire (diag-20260816-111650), not inferred:
 */
class MoveGameClick(val x: Int, val y: Int, val flags: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<MoveGameClick>(fields) {
        override fun fromMap(packet: Map<String, Any>): MoveGameClick =
            MoveGameClick(packet["x"] as Int, packet["y"] as Int, packet["flags"] as Int)

        override fun toMap(packet: MoveGameClick): Map<String, Any> =
            mapOf("x" to packet.x, "y" to packet.y, "flags" to packet.flags)
    }
}
