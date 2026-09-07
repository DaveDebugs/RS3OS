package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * IF_BUTTON2 .. IF_BUTTON10 - the rest of the interface-click family.
 */
sealed class IfButtonN(
    /** Which member of the family this is: 2 for IF_BUTTON2, 10 for IF_BUTTON10. */
    val buttonOp: Int,
    /** First 4 bytes, BE. Confirmed on IF_BUTTON1 to be interface<<16|component. */
    val hash: Int,
    /** Bytes 4..6 as an unsigned medium - field `component` in the declaration. */
    val mid: Int,
    /** Bytes 7..8 as an unsigned short - field `arg2` in the declaration. */
    val arg2: Int
) : GamePacket {

    val interfaceId: Int get() = (hash shr 16) and 0xffff
    val component: Int get() = hash and 0xffff

    // ---- reading (B), recomputed from the same five bytes -------------------
    // mid is bytes b0 b1 b2 and arg2 is b3 b4, so the IF_BUTTON1 cut is
    // slot = b0 b1, item = b2 b3, flags = b4. Checked against IF_BUTTON1's
    // observation: mid=0xffffff, arg2=0xffff -> slot=0xffff, item=0xffff,
    // flags=0xff, which is what IfButton1 decodes those bytes to.
    val altSlot: Int get() = (mid shr 8) and 0xffff
    val altItem: Int get() = ((mid and 0xff) shl 8) or ((arg2 shr 8) and 0xff)
    val altFlags: Int get() = arg2 and 0xff

    override fun toString(): String =
        "IF_BUTTON$buttonOp(interface=$interfaceId, component=$component, mid=$mid, arg2=$arg2)"

    /**
     * Shared body for the nine codecs. Each opcode gets its OWN packet class
     * rather than one class carrying an opcode field, because
     * [com.opennxt.net.game.PacketRegistry] keys its class->registration map by
     * KClass: nine opcodes sharing one class would silently overwrite each
     * other there, and the handler could not tell which menu option was clicked.
     */
    abstract class Codec<T : IfButtonN>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(hash: Int, mid: Int, arg2: Int): T

        override fun fromMap(packet: Map<String, Any>): T =
            create(packet["arg1"] as Int, packet["component"] as Int, packet["arg2"] as Int)

        override fun toMap(packet: T): Map<String, Any> =
            mapOf("arg1" to packet.hash, "component" to packet.mid, "arg2" to packet.arg2)
    }
}

class IfButton2(hash: Int, mid: Int, arg2: Int) : IfButtonN(2, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton2>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton2(hash, mid, arg2)
    }
}

class IfButton3(hash: Int, mid: Int, arg2: Int) : IfButtonN(3, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton3>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton3(hash, mid, arg2)
    }
}

class IfButton4(hash: Int, mid: Int, arg2: Int) : IfButtonN(4, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton4>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton4(hash, mid, arg2)
    }
}

class IfButton5(hash: Int, mid: Int, arg2: Int) : IfButtonN(5, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton5>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton5(hash, mid, arg2)
    }
}

class IfButton6(hash: Int, mid: Int, arg2: Int) : IfButtonN(6, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton6>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton6(hash, mid, arg2)
    }
}

class IfButton7(hash: Int, mid: Int, arg2: Int) : IfButtonN(7, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton7>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton7(hash, mid, arg2)
    }
}

class IfButton8(hash: Int, mid: Int, arg2: Int) : IfButtonN(8, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton8>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton8(hash, mid, arg2)
    }
}

class IfButton9(hash: Int, mid: Int, arg2: Int) : IfButtonN(9, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton9>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton9(hash, mid, arg2)
    }
}

class IfButton10(hash: Int, mid: Int, arg2: Int) : IfButtonN(10, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton10>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton10(hash, mid, arg2)
    }
}
