package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The client reporting a mouse click. 949 opcode 2, fixed 6 bytes.
 */
class EventMouseClick(val x: Int, val y: Int, val buttonDelta: Int) : GamePacket {

    /** True when bit 15 is set: the click was NOT the left button. */
    val notLeftButton: Boolean get() = (buttonDelta and 0x8000) != 0

    /** Milliseconds since the previous report, saturating at 0x7fff. */
    val deltaMs: Int get() = buttonDelta and 0x7fff

    /** True when [deltaMs] sat on its ceiling, so the real gap is unknown. */
    val deltaSaturated: Boolean get() = deltaMs == 0x7fff

    override fun toString(): String =
        "EVENT_MOUSE_CLICK(x=$x, y=$y, notLeftButton=$notLeftButton, deltaMs=$deltaMs" +
            (if (deltaSaturated) " SATURATED" else "") + ")"

    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<EventMouseClick>(fields) {
        override fun fromMap(packet: Map<String, Any>): EventMouseClick = EventMouseClick(
            packet["x"] as Int,
            packet["y"] as Int,
            packet["buttondelta"] as Int
        )

        override fun toMap(packet: EventMouseClick): Map<String, Any> = mapOf(
            "x" to packet.x,
            "y" to packet.y,
            "buttondelta" to packet.buttonDelta
        )
    }
}
