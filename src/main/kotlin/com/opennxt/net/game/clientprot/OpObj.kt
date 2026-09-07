package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * A click on a ground object - a dropped item lying on a tile: OPOBJ1..6.
 *
 * 949 opcodes 28, 64, 143, 43, 45, 68 - in that order. Fixed 8 bytes each.
 */
sealed class OpObj(
    /** Which menu option: 1 for OPOBJ1 ... 6 for OPOBJ6. */
    val option: Int,
    /** The object (item) id of the ground object that was clicked. */
    val id: Int,
    /** Absolute world tile x. */
    val x: Int,
    /** Absolute world tile y. */
    val y: Int,
    /** Raw flags byte, already un-transformed by the `u128byte` codec. */
    val flags: Int
) : GamePacket {

    /** Bit 0: the ctrl key was held at click time. */
    val ctrlHeld: Boolean get() = (flags and 1) == 1

    /** Bit 1: a second client input-state bit whose meaning is not established. */
    val inputBit: Boolean get() = (flags and 2) == 2

    override fun toString(): String = "OPOBJ$option(obj=$id at ($x,$y), flags=$flags)"

    abstract class Codec<T : OpObj>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(id: Int, x: Int, y: Int, flags: Int): T

        override fun fromMap(packet: Map<String, Any>): T = create(
            packet["id"] as Int, packet["x"] as Int, packet["y"] as Int, packet["flags"] as Int
        )

        override fun toMap(packet: T): Map<String, Any> = mapOf(
            "id" to packet.id, "x" to packet.x, "y" to packet.y, "flags" to packet.flags
        )
    }
}

class OpObj1(id: Int, x: Int, y: Int, flags: Int) : OpObj(1, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj1>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj1(id, x, y, flags)
    }
}

class OpObj2(id: Int, x: Int, y: Int, flags: Int) : OpObj(2, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj2>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj2(id, x, y, flags)
    }
}

class OpObj3(id: Int, x: Int, y: Int, flags: Int) : OpObj(3, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj3>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj3(id, x, y, flags)
    }
}

class OpObj4(id: Int, x: Int, y: Int, flags: Int) : OpObj(4, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj4>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj4(id, x, y, flags)
    }
}

class OpObj5(id: Int, x: Int, y: Int, flags: Int) : OpObj(5, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj5>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj5(id, x, y, flags)
    }
}

class OpObj6(id: Int, x: Int, y: Int, flags: Int) : OpObj(6, id, x, y, flags) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpObj.Codec<OpObj6>(fields) {
        override fun create(id: Int, x: Int, y: Int, flags: Int) = OpObj6(id, x, y, flags)
    }
}
