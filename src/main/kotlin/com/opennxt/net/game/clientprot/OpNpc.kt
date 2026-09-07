package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * A click on an NPC: OPNPC1..6, the six menu options.
 *
 * 949 opcodes 60, 78, 39, 53, 75, 65 - in that order. Fixed 3 bytes each.
 */
sealed class OpNpc(
    val option: Int,
    /** The client's 16-bit NPC slot index - the same index `NPC_INFO` assigns. */
    val index: Int,
    /**
     * The keyboard-modifier bit, untransformed: the client writes
     * `(modifiers >> 2) & 1` with **no** `+0x80`, unlike [OpLoc]. So only 0 and
     * 1 are reachable, and anything else on the wire means the model is wrong
     * and should be visible rather than masked.
     */
    val ctrl: Int
) : GamePacket {

    /** True when bit 2 of the client's modifier word was set at click time. */
    val ctrlHeld: Boolean get() = (ctrl and 1) == 1

    override fun toString(): String = "OPNPC$option(npcIndex=$index, ctrl=$ctrl)"

    /**
     * Shared body for the six codecs. Reads by KEY NAME, so a declaration file
     * that changes a field's TYPE needs no change here, and one that renames a
     * field fails loudly at the first decode instead of mis-decoding quietly.
     */
    abstract class Codec<T : OpNpc>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(index: Int, ctrl: Int): T

        override fun fromMap(packet: Map<String, Any>): T =
            create(packet["index"] as Int, packet["ctrl"] as Int)

        override fun toMap(packet: T): Map<String, Any> =
            mapOf("index" to packet.index, "ctrl" to packet.ctrl)
    }
}

class OpNpc1(index: Int, ctrl: Int) : OpNpc(1, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc1>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc1(index, ctrl)
    }
}

class OpNpc2(index: Int, ctrl: Int) : OpNpc(2, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc2>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc2(index, ctrl)
    }
}

class OpNpc3(index: Int, ctrl: Int) : OpNpc(3, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc3>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc3(index, ctrl)
    }
}

class OpNpc4(index: Int, ctrl: Int) : OpNpc(4, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc4>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc4(index, ctrl)
    }
}

class OpNpc5(index: Int, ctrl: Int) : OpNpc(5, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc5>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc5(index, ctrl)
    }
}

class OpNpc6(index: Int, ctrl: Int) : OpNpc(6, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpNpc.Codec<OpNpc6>(fields) {
        override fun create(index: Int, ctrl: Int) = OpNpc6(index, ctrl)
    }
}
