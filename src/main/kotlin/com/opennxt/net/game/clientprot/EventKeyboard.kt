package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

/**
 * A keyboard-event batch from the client. 949 opcode 77, var-short.
 *
 * FIELD LAYOUT IS PARTIALLY DECODED.
 *
 * The one observed sample was 4 bytes: `51 ff ff ff`. 0x51 = 81 is the Windows
 * virtual-key code for VK_KEY_Q, consistent with the interpretation that byte 0
 * is a key code. The remaining three bytes (all 0xff) are plausibly a signed
 * -1 for "key released" or "no modifier / no repeat", but this is not confirmed.
 *
 * The mobile APK binary contains these related strings:
 *   - `AKeyEvent_getKeyCode` (Android NDK key code reader)
 *   - `keyCodeToUnicodeCharacter` (engine key-to-char mapping)
 *   - `cc_if_input_setkeyhandlingmode` (CS2 opcode for key handling)
 *
 * WHAT THIS PACKET IS FOR: raw keyboard events for keys NOT bound via the CS2
 * `setopkey` / `if_setopkey` system. Bound keys generate IF_BUTTON packets
 * instead. This packet carries chat-typing input and unbound key presses.
 *
 * WHY WE HANDLE IT: the ESC key binding that CS2 scripts would normally set up
 * via `setopkey` is not functioning (likely due to missing varcs that the onload
 * scripts depend on). As a fallback the server intercepts ESC (0x1B = 27) here
 * and triggers the close action directly.
 *
 * VK_ESCAPE = 0x1B (27) on Windows. The NXT client uses Windows virtual key
 * codes on desktop. On mobile the Android key codes are mapped through JNI.
 *
 * THIS IS AN INTERIM SOLUTION. Once the CS2 keybind chain is fully working,
 * ESC presses will arrive as IF_BUTTON on the bound component and this handler
 * will only be needed for chat/typing.
 */
class EventKeyboard(val payload: ByteArray) : GamePacket {

    /**
     * The key code from the first byte of the payload.
     * On Windows NXT this is the Windows virtual key code.
     * Returns -1 if the payload is empty.
     */
    val keyCode: Int get() = if (payload.isNotEmpty()) payload[0].toInt() and 0xff else -1

    /**
     * True if the key code byte is VK_ESCAPE (0x1B = 27).
     */
    val isEscape: Boolean get() = keyCode == 0x1B

    /** Payload as lowercase hex. */
    fun hex(limit: Int = 32): String {
        val shown = minOf(limit, payload.size)
        val sb = StringBuilder(shown * 3)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            sb.append(((payload[i].toInt() and 0xff) + 0x100).toString(16).substring(1))
        }
        if (payload.size > shown) sb.append(" ... (${payload.size - shown} more)")
        return sb.toString()
    }

    override fun toString(): String =
        "EVENT_KEYBOARD(keyCode=$keyCode${if (isEscape) " ESC" else ""}, ${payload.size} bytes: ${hex()})"

    /**
     * Raw-bytes codec for opcode 77. Consumes all readable bytes without
     * interpreting field boundaries, because the exact layout is not confirmed.
     * Encode reproduces the input verbatim for proxy forwarding.
     */
    class Codec : GamePacketCodec<EventKeyboard> {
        override fun decode(buf: GamePacketReader): EventKeyboard {
            val bytes = ByteArray(buf.buffer.readableBytes())
            buf.getBytes(bytes)
            return EventKeyboard(bytes)
        }

        override fun encode(packet: EventKeyboard, buf: GamePacketBuilder) {
            buf.putBytes(packet.payload)
        }
    }
}
