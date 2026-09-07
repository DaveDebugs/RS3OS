package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * A click on another player: OPPLAYER1..10, the ten menu options.
 *
 * 949 opcodes 130, 79, 140, 103, 59, 96, 26, 122, 80, 54 - in that order. Fixed
 * 3 bytes each.
 */
sealed class OpPlayer(
    /** Which menu option: 1 for OPPLAYER1 ... 10 for OPPLAYER10. */
    val option: Int,
    /** The client's 16-bit PLAYER slot index - the same index `PLAYER_INFO` assigns. */
    val index: Int,
    /**
     * The keyboard-modifier bit, untransformed: `(modifiers >> 2) & 1` with no
     * `+0x80`. Only 0 and 1 are reachable.
     */
    val ctrl: Int
) : GamePacket {

    /** True when bit 2 of the client's modifier word was set at click time. */
    val ctrlHeld: Boolean get() = (ctrl and 1) == 1

    override fun toString(): String = "OPPLAYER$option(playerIndex=$index, ctrl=$ctrl)"

    abstract class Codec<T : OpPlayer>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(index: Int, ctrl: Int): T

        override fun fromMap(packet: Map<String, Any>): T =
            create(packet["index"] as Int, packet["ctrl"] as Int)

        override fun toMap(packet: T): Map<String, Any> =
            mapOf("index" to packet.index, "ctrl" to packet.ctrl)
    }
}

class OpPlayer1(index: Int, ctrl: Int) : OpPlayer(1, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer1>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer1(index, ctrl)
    }
}

class OpPlayer2(index: Int, ctrl: Int) : OpPlayer(2, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer2>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer2(index, ctrl)
    }
}

class OpPlayer3(index: Int, ctrl: Int) : OpPlayer(3, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer3>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer3(index, ctrl)
    }
}

class OpPlayer4(index: Int, ctrl: Int) : OpPlayer(4, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer4>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer4(index, ctrl)
    }
}

class OpPlayer5(index: Int, ctrl: Int) : OpPlayer(5, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer5>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer5(index, ctrl)
    }
}

class OpPlayer6(index: Int, ctrl: Int) : OpPlayer(6, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer6>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer6(index, ctrl)
    }
}

class OpPlayer7(index: Int, ctrl: Int) : OpPlayer(7, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer7>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer7(index, ctrl)
    }
}

class OpPlayer8(index: Int, ctrl: Int) : OpPlayer(8, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer8>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer8(index, ctrl)
    }
}

class OpPlayer9(index: Int, ctrl: Int) : OpPlayer(9, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer9>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer9(index, ctrl)
    }
}

class OpPlayer10(index: Int, ctrl: Int) : OpPlayer(10, index, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpPlayer.Codec<OpPlayer10>(fields) {
        override fun create(index: Int, ctrl: Int) = OpPlayer10(index, ctrl)
    }
}
