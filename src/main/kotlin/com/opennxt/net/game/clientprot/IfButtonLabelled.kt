package com.opennxt.net.game.clientprot

import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * Build 949 ClientProt **102**, var-byte - a click on an interface component
 * whose definition carries a non-empty menu string.
 *
 * @property slot dispatcher arg4, `0xffff` normalised to `-1` by [slotSigned].
 * @property op the menu option, **1-based** 1..10 - the sibling branch
 * @property label the component's own baked menu string (`componentDef+0x130`).
 * @property hash `(interface shl 16) or component`, big-endian.
 * @property defFlag the byte at `componentDef+0x148`, sent negated. Meaning
 */
class IfButtonLabelled(
    val slot: Int,
    val op: Int,
    val label: String,
    val hash: Int,
    val defFlag: Int
) : GamePacket {

    /** Interface id, the high half of [hash]. */
    val interfaceId: Int get() = (hash shr 16) and 0xffff

    /** Component id, the low half of [hash]. */
    val component: Int get() = hash and 0xffff

    /**
     * [slot] with the client's `0xffff` sentinel mapped back to -1.
     */
    val slotSigned: Int get() = if (slot == 0xffff) -1 else slot

    override fun toString(): String =
        "IF_BUTTON_LABELLED(interface=$interfaceId, component=$component, op=$op, " +
            "slot=$slotSigned, label='$label', defFlag=$defFlag)"

    /**
     * Field keys are the declaration file's: `slot`/`op`/`label`/`hash`/`defFlag`.
     */
    class Codec(fields: Array<PacketFieldDeclaration>) : DynamicGamePacketCodec<IfButtonLabelled>(fields) {
        override fun fromMap(packet: Map<String, Any>): IfButtonLabelled = IfButtonLabelled(
            slot = packet["slot"] as Int,
            op = packet["op"] as Int,
            label = packet["label"] as String,
            hash = packet["hash"] as Int,
            defFlag = packet["defFlag"] as Int
        )

        override fun toMap(packet: IfButtonLabelled): Map<String, Any> = mapOf(
            "slot" to packet.slot,
            "op" to packet.op,
            "label" to packet.label,
            "hash" to packet.hash,
            "defFlag" to packet.defFlag
        )
    }

    companion object {
        /** Build 949 opcode, read from the thunk at. */
        const val OPCODE = 102

        /** The declaration file's field keys, in wire order. Asserted by the check. */
        val FIELD_KEYS = listOf("slot", "op", "label", "hash", "defFlag")

        /** The declaration file's field types, in wire order. Asserted by the check. */
        val FIELD_TYPES = listOf("ushortle", "ubyte", "string", "int", "ubytec")

        /**
         * What is measured, what is authored, and what would settle the rest.
         */
        const val PROVENANCE: String =
            "ClientProt 102: - opcode 102 and size -1 from the initialiser thunk" +
                ", cross-checked by " +
                "clientProtSizes.toml[102]=-1 and by a blind whole-population control (148/157 thunk " +
                "sizes agree with the size table; shuffled pairing scores 16/18/14 of 157); the five " +
                "fields, their widths, their order, the little-endian slot, the big-endian hash and the " +
                "negated trailing byte from builder instruction by instruction, with the" +
                "length arithmetic closing at chars+9 = encodedLength+8. AUTHORED - the NAME " +
                "'IF_BUTTON_LABELLED' (nothing in client or cache spells it; a name is minted only " +
                "because PacketRegistry is name-keyed), the field key 'slot' (the value is dispatcher " +
                "arg4; only caller makes it word[componentDef+0xc]), and the field key" +
                "'defFlag' (byte componentDef+0x148, meaning unknown). WOULD SETTLE IT - the first real " +
                "102 frame settles what slot and defFlag carry for interface 1184; a Jagex symbol or a " +
                "later build's prot table settles the name; locating the 949 component-definition " +
                "parser settles that componentDef+0x130 is the cache's str2."
    }
}
