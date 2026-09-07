package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The client reporting its camera orientation. 949 opcode 12, fixed 4 bytes.
 */
class EventCameraPosition(val angle1: Int, val angle2: Int) : GamePacket {

    override fun toString(): String = "EVENT_CAMERA_POSITION(angle1=$angle1, angle2=$angle2)"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<EventCameraPosition>(fields) {
        override fun fromMap(packet: Map<String, Any>): EventCameraPosition =
            EventCameraPosition(packet["angle1"] as Int, packet["angle2"] as Int)

        override fun toMap(packet: EventCameraPosition): Map<String, Any> =
            mapOf("angle1" to packet.angle1, "angle2" to packet.angle2)
    }
}
