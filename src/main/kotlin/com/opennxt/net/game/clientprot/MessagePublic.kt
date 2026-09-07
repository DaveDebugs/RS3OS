package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.util.HuffmanCodec

/**
 * `MESSAGE_PUBLIC` - 949 ClientProt **35**, size -1 (var byte). A player typing
 * a line into the chatbox.
 */
data class MessagePublic(
    val colour: Int,
    val effect: Int,
    val text: String
) : GamePacket {

    /** Whether both tag fields are inside the ranges the client's own loops allow. */
    val tagsInRange: Boolean get() = colour in COLOURS.indices && effect in 0..EFFECTS.size

    override fun toString(): String =
        "MESSAGE_PUBLIC(colour=$colour${colourName()?.let { "/$it" } ?: ""}, " +
            "effect=$effect${effectName()?.let { "/$it" } ?: ""}, text=\"$text\")"

    fun colourName(): String? = COLOURS.getOrNull(colour)

    /** `null` for effect 0, which is "no effect" rather than an unnamed effect. */
    fun effectName(): String? = if (effect <= 0) null else EFFECTS.getOrNull(effect - 1)

    object Codec : GamePacketCodec<MessagePublic> {

        override fun encode(packet: MessagePublic, buf: GamePacketBuilder) {
            require(packet.colour in COLOURS.indices) {
                "colour ${packet.colour} is outside 0..${COLOURS.size - 1}"
            }
            require(packet.effect in 0..EFFECTS.size) {
                "effect ${packet.effect} is outside 0..${EFFECTS.size}"
            }
            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException(
                    "cannot encode MESSAGE_PUBLIC without the huffman table (js5[10, 'huffman'])"
                )

            buf.put(DataType.BYTE, packet.colour)
            buf.put(DataType.BYTE, packet.effect)
            huffman.write(buf, packet.text)
        }

        override fun decode(buf: GamePacketReader): MessagePublic {
            val colour = buf.getUnsigned(DataType.BYTE).toInt()
            val effect = buf.getUnsigned(DataType.BYTE).toInt()
            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException(
                    "MESSAGE_PUBLIC arrived but this server has no huffman table (js5[10, 'huffman']); " +
                        "the text cannot be decoded and the packet is dropped"
                )
            val text = huffman.readFrom(buf)
            return MessagePublic(colour, effect, text)
        }
    }

    companion object {
        val COLOURS = listOf(
            "yellow", "red", "green", "cyan", "purple", "white",
            "flash1", "flash2", "flash3", "glow1", "glow2", "glow3"
        )

        val EFFECTS = listOf("wave", "wave2", "shake", "scroll", "slide")
    }
}
