package com.opennxt.net.game.serverprot.ifaces

import com.opennxt.model.InterfaceHash
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * The build-949 attribute-4 family: the four ServerProt packets that set a
 * component's MODEL SOURCE.
 *
 * Read `data/prot/949/serverProt/_ATTR4_COMMON.md` before touching any of
 * this. The short version:
 */
data class IfModelK1(val parent: InterfaceHash, val value: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfModelK1>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfModelK1 =
            IfModelK1(InterfaceHash(packet["parent"] as Int), packet["value"] as Int)

        override fun toMap(packet: IfModelK1): Map<String, Any> =
            mapOf("parent" to packet.parent.hash, "value" to packet.value)
    }
}

data class IfModelK2(val parent: InterfaceHash, val value: Int) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfModelK2>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfModelK2 =
            IfModelK2(InterfaceHash(packet["parent"] as Int), packet["value"] as Int)

        override fun toMap(packet: IfModelK2): Map<String, Any> =
            mapOf("parent" to packet.parent.hash, "value" to packet.value)
    }
}

data class IfModelK3Self(val parent: InterfaceHash) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfModelK3Self>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfModelK3Self =
            IfModelK3Self(InterfaceHash(packet["parent"] as Int))

        override fun toMap(packet: IfModelK3Self): Map<String, Any> =
            mapOf("parent" to packet.parent.hash)
    }
}

data class IfModelK5Self(val parent: InterfaceHash) : GamePacket {
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfModelK5Self>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfModelK5Self =
            IfModelK5Self(InterfaceHash(packet["parent"] as Int))

        override fun toMap(packet: IfModelK5Self): Map<String, Any> =
            mapOf("parent" to packet.parent.hash)
    }
}
