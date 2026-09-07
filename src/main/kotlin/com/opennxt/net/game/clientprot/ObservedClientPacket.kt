package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

/**
 * A client packet whose FRAMING is known and whose MEANING is not.
 *
 * WHY THIS EXISTS
 *   A 60s run with a real 949 client produced fourteen inbound opcodes with no
 *   registered codec. Every one of them framed correctly - the sizes in
 *   data/prot/949/clientProtSizes.toml, extracted from the client binary, agreed
 *   with the wire on every frame - so the bytes were being read perfectly and
 *   then thrown away with a WARN. Two of those opcodes (4 and 98, ~250 bytes
 *   each) accounted for 75 of the packets in that minute, and the resulting warn
 *   spam is what buries everything else in the log.
 *
 *   This is the middle position between the two bad options. Naming them would
 *   be a guess written down as a fact. Leaving them dropped keeps the log
 *   unreadable and throws away the only data that could ever identify them. So
 *   they are CONSUMED - decoded into their raw bytes, counted, and sampled into
 *   the log at a rate limit - and nothing anywhere claims to know what they are.
 *
 * THIS CLASS DELIBERATELY HAS NO NAMED FIELDS.
 *   [payload] is bytes. There is no `x`, no `id`, no `flags`, because no field
 *   split for any of these opcodes is supported by evidence. The moment one is,
 *   it gets a real class next to [MoveGameClick] and comes OFF the list below.
 *
 * ENCODE IS A VERBATIM PASSTHROUGH, and that is load-bearing rather than
 * decorative: in proxy mode ConnectedProxyClient forwards every decoded packet
 * to the far side, so a codec that could not reproduce its own input would
 * corrupt the proxied stream. See [com.opennxt.net.ConnectedClient.write],
 * which routes this class by the packet's OWN opcode rather than by a
 * class->registration lookup - fourteen opcodes share this one class, so the
 * class map cannot say which opcode a given instance came in on.
 */
class ObservedClientPacket(val opcode: Int, val payload: ByteArray) : GamePacket {

    /** Payload as lowercase space-separated hex, capped at [limit] bytes. */
    fun hex(limit: Int = 32): String {
        val shown = minOf(limit, payload.size)
        val sb = StringBuilder(shown * 3 + 16)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            sb.append(((payload[i].toInt() and 0xff) + 0x100).toString(16).substring(1))
        }
        if (payload.size > shown) sb.append(" ... (${payload.size - shown} more)")
        return sb.toString()
    }

    /** Big-endian unsigned short at [index], or -1 if the payload is too short. */
    fun u16(index: Int): Int =
        if (index + 1 >= payload.size) -1
        else ((payload[index].toInt() and 0xff) shl 8) or (payload[index + 1].toInt() and 0xff)

    /** Unsigned byte at [index], or -1 if the payload is too short. */
    fun u8(index: Int): Int = if (index >= payload.size) -1 else payload[index].toInt() and 0xff

    override fun toString(): String = "ObservedClientPacket(op=$opcode, ${payload.size} bytes: ${hex()})"

    class Codec(private val opcode: Int) : GamePacketCodec<ObservedClientPacket> {
        override fun decode(buf: GamePacketReader): ObservedClientPacket {
            // Consume EVERYTHING. ConnectedClient.receive warns about leftover
            // readable bytes after a decode, and for a packet whose whole point
            // is "we do not know the layout" that warning would fire on every
            // single frame and be exactly as useless as the drop warning this
            // replaces.
            val bytes = ByteArray(buf.buffer.readableBytes())
            buf.getBytes(bytes)
            return ObservedClientPacket(opcode, bytes)
        }

        override fun encode(packet: ObservedClientPacket, buf: GamePacketBuilder) {
            buf.putBytes(packet.payload)
        }
    }

    companion object {
        /**
         * The client->server opcodes consumed as observed blobs on build 949.
         *
         * This is an EXPLICIT list, not "everything unregistered". A catch-all
         * would swallow the next genuinely new opcode too, and the "Dropping
         * inbound packet with no registered codec" warning is the only thing
         * that would ever announce one.
         */
        val OPCODES = intArrayOf(4, 67, 98, 113, 146) // 25 (IF_BUTTOND), 16, 89, 114, 133 and 127 (RESUME_PAUSEBUTTON) are left to the generated classes; 105 (VARC_TRANSMIT) is left to its own codec, which carries the client interface state and is the receive half of the settings save loop
 // 25: fixed 18 bytes, previously DROPPED with "no registered
        // codec". Sender pair (25/153), the component-targeted member of
        // the "use selection on X" menu family (clientProtNames.toml:317). Observed
        // frames decode [10 bytes of -1][1477:frame-layer hash][ushort][1482 hash] -
        // the two distinct frames seen named the Backpack (1477:107) and Worn
        // Equipment (1477:118) window frame layers during windowed-mode drag
    }
}
