package com.opennxt.net.game.serverprot.variables

import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

/**
 * ServerProt 200 (`STORE_SERVERPERM_VARCS_ACK`), size 0 - the server half of the interface-settings
 * save loop.
 */
object StoreServerpermVarcsAck : GamePacket {

    object Codec : GamePacketCodec<StoreServerpermVarcsAck> {
        /** Size 0: the opcode IS the message. Writing anything here would desynchronise the stream. */
        override fun encode(packet: StoreServerpermVarcsAck, buf: GamePacketBuilder) = Unit

        override fun decode(buf: GamePacketReader): StoreServerpermVarcsAck = StoreServerpermVarcsAck
    }

    override fun toString(): String = "StoreServerpermVarcsAck"
}
