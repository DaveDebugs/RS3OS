package com.opennxt.net.game.clientprot

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.resources.config.vars.BaseVarType
import com.opennxt.resources.config.vars.ScriptVarType
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * ClientProt 105 (`VARC_TRANSMIT`), var-short - THE CLIENT'S OWN INTERFACE STATE, pushed to the
 * server so it can be stored and handed back at the next login.
 *
 * @param allSent the leading byte. The client's own flag for "this is the last packet of the
 * @param values varc id -> value, in wire order. `Int`, `Long` or `String` per the cache type.
 */
class VarcTransmit(val allSent: Int, val values: Map<Int, Any>) : GamePacket {

    object Codec : GamePacketCodec<VarcTransmit> {
        private val logger = KotlinLogging.logger { }

        /**
         * varc id -> [BaseVarType], read once from `vars_client`.
         *
         * Lazy and cached: this is consulted per ENTRY, and the reference client-sized packet carries a couple
         * of hundred of them at up to one packet per second.
         */
        private val baseTypes: Map<Int, BaseVarType> by lazy {
            if (!RsDatabase.available) {
                logger.error { "VARC_TRANSMIT: no rs3.sqlite, so no varc types - nothing can be decoded" }
                return@lazy emptyMap()
            }
            val out = HashMap<Int, BaseVarType>()
            RsDatabase.queryAll("SELECT id, type FROM vars_client") { rs ->
                rs.getInt("id") to rs.getInt("type")
            }.forEach { (id, type) ->
                // An unknown type id is left OUT rather than defaulted to INT. A wrong width does
                // not lose one value, it desynchronises everything after it.
                ScriptVarType.values().firstOrNull { it.id == type }?.let { out[id] = it.type }
            }
            logger.info { "VARC_TRANSMIT: ${out.size} varc types read from vars_client" }
            out
        }

        override fun decode(buf: GamePacketReader): VarcTransmit {
            val b = buf.buffer
            val allSent = b.readUnsignedByte().toInt()
            val values = LinkedHashMap<Int, Any>()
            while (b.readableBytes() >= 2) {
                val id = b.readUnsignedShort()
                when (baseTypes[id]) {
                    BaseVarType.INTEGER -> {
                        if (b.readableBytes() < 4) break
                        values[id] = b.readInt()
                    }
                    BaseVarType.LONG -> {
                        if (b.readableBytes() < 8) break
                        values[id] = b.readLong()
                    }
                    BaseVarType.STRING -> values[id] = buf.getString()
                    else -> {
                        // No definition, or a base type whose wire form this repository has not
                        // established (COORDFINE). Either way the width of THIS value is unknown,
                        // so every byte after it is unaligned. Stop, keep what was decoded, and
                        // say which id did it - the alternative is a plausible-looking map built
                        // out of misaligned bytes.
                        logger.warn {
                            "VARC_TRANSMIT: varc $id has no usable base type in vars_client " +
                                "(${baseTypes[id]}); the value width is unknown, so decoding stops " +
                                "here with ${values.size} entry(s) and ${b.readableBytes()} byte(s) " +
                                "unread. This is a gap in the type table, not a framing error."
                        }
                        b.skipBytes(b.readableBytes())
                        break
                    }
                }
            }
            return VarcTransmit(allSent, values)
        }

        /**
         * Never sent by this server - 105 is client->server. Implemented rather than left throwing
         * so a round-trip test can exist; it writes the layout [decode] reads.
         */
        override fun encode(packet: VarcTransmit, buf: GamePacketBuilder) {
            buf.put(com.opennxt.net.buf.DataType.BYTE, packet.allSent.toLong())
            for ((id, v) in packet.values) {
                buf.put(com.opennxt.net.buf.DataType.SHORT, id.toLong())
                when (v) {
                    is Int -> buf.put(com.opennxt.net.buf.DataType.INT, v.toLong())
                    is Long -> buf.put(com.opennxt.net.buf.DataType.LONG, v)
                    is String -> buf.putString(v)
                    else -> error("VARC_TRANSMIT: cannot encode ${v.javaClass.simpleName} for varc $id")
                }
            }
        }
    }

    override fun toString(): String =
        "VarcTransmit(allSent=$allSent, ${values.size} varc(s)" +
            (values.entries.take(3).joinToString(prefix = ": ", postfix = if (values.size > 3) ", ..." else "") {
                "${it.key}=${it.value}"
            }) + ")"
}
