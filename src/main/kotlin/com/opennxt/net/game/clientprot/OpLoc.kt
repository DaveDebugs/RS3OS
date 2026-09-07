package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * A click on a scenery object (a "loc"): OPLOC1..6, the six menu options.
 */
sealed class OpLoc(
    val option: Int,
    /** Absolute world tile x of the loc that was clicked. */
    val x: Int,
    /** Absolute world tile y of the loc that was clicked. */
    val y: Int,
    /** The loc id, as it appears in `locs.id` / `map_loc.loc_id`. */
    val id: Int,
    /**
     * The keyboard-modifier byte, already un-transformed by the `ubyte128`
     * codec: the client writes `((modifiers >> 2) & 1) + 0x80`, so 0 means ctrl
     * was not held and 1 means it was. Carried raw as well as via [ctrlHeld]
     * because only the two values 0 and 1 are reachable from that expression -
     * anything else on the wire means the model is wrong and should be visible.
     */
    val ctrl: Int
) : GamePacket {

    /** True when bit 2 of the client's modifier word was set at click time. */
    val ctrlHeld: Boolean get() = (ctrl and 1) == 1

    override fun toString(): String =
        "OPLOC$option(loc=$id at ($x,$y), ctrl=$ctrl)"

    /**
     * Shared body for the six codecs. Reads by KEY NAME, so a declaration file
     * that changes a field's TYPE (which is where the recovered evidence lives)
     * needs no change here, and one that renames a field fails loudly at the
     * first decode instead of quietly mis-decoding.
     */
    abstract class Codec<T : OpLoc>(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<T>(fields) {
        protected abstract fun create(x: Int, y: Int, id: Int, ctrl: Int): T

        override fun fromMap(packet: Map<String, Any>): T = create(
            packet["x"] as Int, packet["y"] as Int, packet["id"] as Int, packet["ctrl"] as Int
        )

        override fun toMap(packet: T): Map<String, Any> = mapOf(
            "x" to packet.x, "y" to packet.y, "id" to packet.id, "ctrl" to packet.ctrl
        )
    }
}

class OpLoc1(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(1, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc1>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc1(x, y, id, ctrl)
    }
}

class OpLoc2(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(2, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc2>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc2(x, y, id, ctrl)
    }
}

class OpLoc3(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(3, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc3>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc3(x, y, id, ctrl)
    }
}

class OpLoc4(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(4, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc4>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc4(x, y, id, ctrl)
    }
}

class OpLoc5(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(5, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc5>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc5(x, y, id, ctrl)
    }
}

class OpLoc6(x: Int, y: Int, id: Int, ctrl: Int) : OpLoc(6, x, y, id, ctrl) {
    class Codec(fields: Array<PacketFieldDeclaration>) : OpLoc.Codec<OpLoc6>(fields) {
        override fun create(x: Int, y: Int, id: Int, ctrl: Int) = OpLoc6(x, y, id, ctrl)
    }
}
