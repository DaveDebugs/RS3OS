package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The client reporting its window mode and framebuffer size. 949 opcode 48,
 * fixed 6 bytes.
 *
 * Two INDEPENDENT lines of evidence, which is why this is treated as real and
 * not as another observed blob:
 *
 *  1. The name comes out of the client binary - 48 = "WINDOW_STATUS" was
 * already in data/prot/949/clientProtNames.toml before any wire observation.
 *  2. The bytes decode as a canonical resolution without any fitting. Read off
 * the wire (60s test runs, 3 occurrences, byte-identical every time):
 *
 *         02 05 00 02 d0 03
 *         [ubyte mode=2][ushort BE width=0x0500=1280][ushort BE height=0x02d0=720][ubyte 0x03]
 *
 *     1280x720 is a real 16:9 mode. A wrong field split would have to land on
 *     one by accident.
 *
 * [mode] is carried through as the raw number. It is NOT mapped to
 * fixed/resizable/fullscreen here: only the single value 2 has ever been
 * observed, and one constant cannot establish an enumeration. Same for
 * [trailing] - 0x03 on all three samples, meaning unknown, so it is kept rather
 * than named or acted on.
 */
class WindowStatus(val mode: Int, val width: Int, val height: Int, val trailing: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<WindowStatus>(fields) {
        override fun fromMap(packet: Map<String, Any>): WindowStatus = WindowStatus(
            packet["mode"] as Int, packet["width"] as Int,
            packet["height"] as Int, packet["trailing"] as Int
        )

        override fun toMap(packet: WindowStatus): Map<String, Any> = mapOf(
            "mode" to packet.mode, "width" to packet.width,
            "height" to packet.height, "trailing" to packet.trailing
        )
    }

    override fun toString(): String =
        "WindowStatus(mode=$mode, ${width}x$height, trailing=$trailing)"
}
