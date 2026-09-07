package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.util.HuffmanCodec

/**
 * `MESSAGE_PUBLIC` - 949 ServerProt **33**, size -1 (var byte). Somebody else's
 * public line, delivered to one viewer.
 *
 * The class is suffixed `Out` only because
 * [com.opennxt.net.game.clientprot.MessagePublic] shares the protocol name and
 * [com.opennxt.net.game.PacketRegistry] star-imports both packages; the name in
 * `data/prot/949/serverProtNames.toml` is `MESSAGE_PUBLIC`.
 *
 * Hand-written, no `.txt`, same reason as the inbound half: the Huffman field is
 * not a `name type` line.
 */
data class MessagePublicOut(
    val index: Int,
    val colour: Int = 0,
    val effect: Int = 0,
    val rights: Int = 0,
    val text: String
) : GamePacket {

    override fun toString(): String =
        "MESSAGE_PUBLIC(index=$index, colour=$colour, effect=$effect, rights=$rights, text=\"$text\")"

    object Codec : GamePacketCodec<MessagePublicOut> {

        override fun encode(packet: MessagePublicOut, buf: GamePacketBuilder) {
            require(packet.index in 0..0xffff) { "player index does not fit a ushort: ${packet.index}" }
            require(packet.colour in 0..0xff) { "colour does not fit the high byte of tags: ${packet.colour}" }
            require(packet.effect in 0..0x7f) {
                "effect ${packet.effect} does not fit the low byte of tags below the escape bit"
            }
            require(packet.rights in 0..RIGHTS_ROWS - 1) {
                "rights ${packet.rights} is outside the ${RIGHTS_ROWS} rows of the crown table;" +
                    "the client would index past it"
            }

            val tags = pack(packet.colour, packet.effect)
            require(tags and ESCAPE == 0) {
                // The .format() must cover the WHOLE message: applied to the
                // second literal alone it silently prints "0x%04x".
                ("tags 0x%04x sets bit 15, which switches the client to the quick-chat body at " +
                    " and makes it stop reading the huffman text").format(tags)
            }

            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException(
                    "cannot encode MESSAGE_PUBLIC without the huffman table (js5[10, 'huffman'])"
                )

            buf.put(DataType.SHORT, packet.index)
            buf.put(DataType.SHORT, tags)
            buf.put(DataType.BYTE, packet.rights)
            huffman.write(buf, packet.text)
        }

        override fun decode(buf: GamePacketReader): MessagePublicOut {
            val index = buf.getUnsigned(DataType.SHORT).toInt()
            val tags = buf.getUnsigned(DataType.SHORT).toInt()
            val rights = buf.getUnsigned(DataType.BYTE).toInt()
            if (tags and ESCAPE != 0) {
                // Mirrors the client: the escape body has no huffman text at all.
                throw IllegalArgumentException(
                    "MESSAGE_PUBLIC with the bit-15 escape set carries a quick-chat phrase id, not text; " +
                        " is not decoded here"
                )
            }
            val huffman = HuffmanCodec.instance()
                ?: throw IllegalStateException("no huffman table (js5[10, 'huffman'])")
            return MessagePublicOut(index, colourOf(tags), effectOf(tags), rights, huffman.readFrom(buf))
        }
    }

    companion object {
        const val ESCAPE = 0x8000

        /** Usable rows of the crown table at; row 0 draws nothing. */
        const val RIGHTS_ROWS = 13

        fun pack(colour: Int, effect: Int): Int = ((colour and 0xff) shl 8) or (effect and 0xff)

        fun colourOf(tags: Int): Int = (tags ushr 8) and 0x7f

        fun effectOf(tags: Int): Int = tags and 0xff
    }
}
