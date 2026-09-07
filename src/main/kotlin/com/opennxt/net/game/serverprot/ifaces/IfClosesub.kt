package com.opennxt.net.game.serverprot.ifaces

import com.opennxt.model.InterfaceHash
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * Removes the sub-interface mounted at [parent].
 */
data class IfClosesub(val parent: InterfaceHash) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfClosesub>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfClosesub =
            IfClosesub(InterfaceHash(packet["parent"] as Int))

        override fun toMap(packet: IfClosesub): Map<String, Any> = mapOf(
            "parent" to packet.parent.hash
        )
    }
}
