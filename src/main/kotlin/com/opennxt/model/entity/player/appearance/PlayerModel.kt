package com.opennxt.model.entity.player.appearance

import com.opennxt.model.entity.PlayerEntity
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.defaults.wearpos.WearposDefaults
import com.opennxt.util.MD5
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

class PlayerModel(val player: PlayerEntity) {
    /** The conversion map of appearance index -> equipment index */
    private val CONVERSION_MAP = intArrayOf(0, 1, 2, 3, 4, 5, -1, 7, -1, 9, 10, -1, -1, -1, -1, -1, -1, -1, 18)

    var gender = Gender.MALE
    var renderType = RenderType.PLAYER

    var npcId = -1
    var showSkillLevel = false // if the player has weapons sheated, this is true

    /**
     * The default identity kit, indexed by [getLookIndex]'s look index, which is
     * the same numbering as the cache's `identitykit.bodypart`:
     *
     *     0 hair   1 jaw   2 torso   3 arms   4 hands   5 legs   6 feet
     *
     * A 0 here means "send no kit for that slot" - [appendAppearance] only emits a
     * kit when `idkit[look] > 0`, so slot 3 (arms) goes out as [SLOT_EMPTY].
     *
     * These ids must come from the kit block build 949 actually draws. The
     * classic low-numbered kit (`3, 14, 18, 26, 34, 38, 42`) does not: the ids
     * exist, carry the right `bodypart`, and their models are present, but the
     * body models they point at are from an older block the 949 player-model
     * pipeline does not render. The result on screen is a floating head, because
     * hair and jaw are the only two slots whose ids fall in the range 949 still
     * uses.
     *
     * The body kits a 949 client expects carry models in the 94926..95112 range:
     * torso 875, hands 811, legs 827, feet 843. Hair (3) and jaw (14) are left
     * as they were, since those already drew.
     *
     * Arms is deliberately 0. The slot is not dressed by a kit at all - the
     * `idkit[look] > 0` guard turns it into [SLOT_EMPTY], which is what a client
     * expects to receive there.
     *
     * If the body ever stops rendering again, put the old ids back to confirm the
     * block is the cause; setting arms alone to a kit separates "the old block
     * does not draw" from "an arms kit breaks the model build".
     *
     * This array is MALE-ONLY. A female character uses bodyparts 7/9/10/11/12/13
     * and nothing here reads [gender] when choosing kits yet.
     */
    val idkit = intArrayOf(3, 14, 875, 0, 811, 827, 843)
    val colours = intArrayOf(3, 16, 16, 0, 0, 0, 0, 0, 0, 0)

    var data = ByteArray(0)
    var hash = ByteArray(0)

    var dirty = true

    private fun ByteBuf.toByteArray(): ByteArray {
        val readable = readableBytes()
        val out = ByteArray(readable)
        readBytes(out)
        return out
    }

    fun refresh() {
        dirty = false

        val buf = Unpooled.buffer()
        val builder = GamePacketBuilder(buf)

        var flags = 0x0
        if (gender == Gender.FEMALE) flags = flags or 0x1
        if (showSkillLevel) flags = flags or 0x4
        builder.put(DataType.BYTE, flags)

        builder.put(DataType.BYTE, 0) // RenderType. TODO: Figure values out
        appendAppearance(builder)
        builder.putString(player.controllingPlayer?.name ?: "null")
        builder.put(DataType.BYTE, 3) // Combat level
        if (showSkillLevel) {
            builder.put(DataType.SHORT, 1000) // Total skill level
        } else {
            builder.put(DataType.BYTE, 0) // Combat level + summoning OR 0
            builder.put(DataType.BYTE, -1)
        }
        builder.put(DataType.BYTE, 0)

        this.data = buf.toByteArray()
        this.hash = MD5.hash(data)

        buf.release()
    }

    private fun getLookIndex(slot: Int): Int = when (slot) {
        4 -> 2
        6 -> 3
        7 -> 5
        8 -> 0
        9 -> 4
        10 -> 6
        11 -> 1
        else -> -1
    }

    /**
     * Unsigned LEB128, the encoding the 949 slot loop reads.
     */
    private fun putVarInt(builder: GamePacketBuilder, value: Int) {
        require(value >= 0) { "varint value must be non-negative, was $value" }
        var v = value
        while (v > 0x7f) {
            builder.put(DataType.BYTE, (v and 0x7f) or 0x80)
            v = v ushr 7
        }
        builder.put(DataType.BYTE, v)
    }

    private fun appendAppearance(builder: GamePacketBuilder) {
        val wearpos = FilesystemResources.instance.defaults.get<WearposDefaults>().slots

        for (index in 0 until wearpos.size) {
            // The client skips a slot when its wearpos table entry is exactly 1
            // and consumes NO
            // bytes for it. Mirror that test exactly - "!= 0" only happens to
            // asserts that it does.
            if (wearpos[index] == SLOT_SKIPPED_BY_CLIENT) continue

            // Items (Equipment)
            val equipSlot = CONVERSION_MAP[index]
 // A cosmetic override shows in the slot whatever is really worn there :
            // the reference client's wardrobe is exactly this - the appearance carries the override item's id,
            // the worn container keeps the real item). Same OBJ_BIAS varint as a worn item.
            val cosmetic = if (equipSlot != -1) player.controllingPlayer?.cosmeticFor(equipSlot) else null
            if (cosmetic != null) {
                putVarInt(builder, OBJ_BIAS + cosmetic)
                continue
            }
            val item = if (equipSlot != -1) player.controllingPlayer?.worn?.get(equipSlot) else null
            if (item != null) {
                putVarInt(builder, OBJ_BIAS + item.id)   // NOT 16385: see OBJ_BIAS
                continue
            }

            val lookIndex = getLookIndex(index)
            if (lookIndex != -1 && idkit[lookIndex] > 0) {
                putVarInt(builder, KIT_BIAS + idkit[lookIndex])
                continue
            }

            putVarInt(builder, SLOT_EMPTY)
        }

        // Customisation bitmask. [ctx+0x18] is initialised to -1 and
        // only leaves -1 on the slot-0 "model override" path, so for an ordinary
        // player the client ALWAYS reads this u16 here (`jne` not
        // taken) and parses one recolour/retexture block per SET bit
        // It must stay 0 until the server actually emits those blocks: the parser
        // writes straight through [dst+0x50] / [dst+0x68], which are NULL unless the
        // slot's definition carried a colour/texture table, and that NULL write at
        // / is the crash this file used to cause.
        builder.put(DataType.SHORT, CUSTOMISATION_NONE)

        // 10 body colours, then 10 more, each ONE byte, both loops
        for (i in colours)
            builder.put(DataType.BYTE, i)
        for (i in 0 until 10)
            builder.put(DataType.BYTE, 0)

        // Signed u16 read into [ctx+0x14].
        builder.put(DataType.SHORT, 2699)
    }

    companion object {
        const val SLOT_EMPTY = 0

        const val KIT_BIAS = 2

        /**
         * Bias applied to worn-object ids, and simultaneously the kit/obj split:
         */
        const val OBJ_BIAS = 0x800

        const val SLOT0_MODEL_OVERRIDE = 1

        /** A wearpos table entry the client's slot loop skips. */
        const val SLOT_SKIPPED_BY_CLIENT = 1

        /** No recolour/retexture blocks follow the slot list. */
        const val CUSTOMISATION_NONE = 0

        /** Both client colour arrays are pinned to this length. */
        const val COLOUR_COUNT = 10
    }
}