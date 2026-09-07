package com.opennxt.model.entity.rendering.npc

import com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationGroupBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcByte25Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcByte34Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcDiscardedBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFaceCoordinateBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFaceEntityBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFlag28Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcForceMovementBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcLifepointsBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcSayBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcShort22Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcString17Block
import com.opennxt.net.buf.GamePacketBuilder

/**
 * The extended-info blocks one npc has pending for the next NPC_INFO.
 *
 * At most one of each type, which is a property of the wire and not a
 * simplification: the mask is a bitfield, so a second animation in the same
 * tick has nowhere to go. Queueing twice replaces.
 *
 * This class holds STATE ONLY. It queues nothing on its own, decides nothing
 * on its own, and nothing in this pass calls the setters during a world tick.
 * It exists so that when combat or AI is written, the wire format is already
 * proven and does not have to be re-derived at the same time as the gameplay.
 *
 * SWITCHES
 * --------
 *  `-Dopennxt.experiment.npcs.extended=false`
 *      turns the whole extended-info section off. NPCs still spawn, move and
 *      are removed; the has-extended-info bit in pass A / pass B goes back to
 *      0 everywhere and no per-npc bytes are appended after the bit section.
 *      This is the switch to use when something else is being tested and the
 *      blocks need to stop being a confound.
 *
 *  `-Dopennxt.experiment.npcs.block.<NAME>=false`
 *      turns off ONE block type. NAME is the [NpcUpdateBlockType] constant,
 *      case-insensitive: `animation`, `hits`, `face_coordinate`, `say`,
 *      `animation_group`. Everything else stays on the wire, so a suspect
 *      block can be bisected without a rebuild and without losing npcs.
 */
class NpcUpdates {

    var animation: NpcAnimationBlock? = null
        private set
    var hits: NpcHitsBlock? = null
        private set
    var faceCoordinate: NpcFaceCoordinateBlock? = null
        private set
    var say: NpcSayBlock? = null
        private set
    var animationGroup: NpcAnimationGroupBlock? = null
        private set
    var faceEntity: NpcFaceEntityBlock? = null
        private set
    var forceMovement: NpcForceMovementBlock? = null
        private set
    var string17: NpcString17Block? = null
        private set
    var short22: NpcShort22Block? = null
        private set
    var byte25: NpcByte25Block? = null
        private set
    var flag28: NpcFlag28Block? = null
        private set
    var byte34: NpcByte34Block? = null
        private set

    /**
     * The lifepoints block (mask bit 16) -- the number and the bar on interface
     * 1490 `toplevel_v2_target_info`. Queued by
     * [com.opennxt.model.world.WorldNpcs.applyDamage] and by
     * [com.opennxt.model.world.WorldNpc.heal] whenever the npc's current
     * lifepoints CHANGE, which is exactly when the reference client sends it.
     */
    var lifepoints: NpcLifepointsBlock? = null
        private set

    /**
     * The discarded-field bodies, keyed by type. A map rather than seven named
     * fields because they carry no meaning to name them by; what matters is that
     * each appears at most once and lands in its own place in the chain.
     */
    private val discarded = LinkedHashMap<NpcUpdateBlockType, NpcDiscardedBlock>()

    /**
     * Set the first time this state was handed to an encoder. [clear] uses it
     * so an update queued between two world ticks is not dropped before any
     * player has been offered it: see the note in
     * [com.opennxt.model.world.WorldNpcs.tick].
     */
    @Volatile
    var offered: Boolean = false
        internal set

    /**
     * Retires an already-transmitted set before a new block joins it.
     */
    private fun retireIfOffered() {
        if (offered) clear()
    }

    fun animate(block: NpcAnimationBlock?) = apply { retireIfOffered(); animation = block; offered = false }
    fun animate(id: Int, delay: Int = 0) = animate(NpcAnimationBlock.single(id, delay))

    fun hit(block: NpcHitsBlock?) = apply { retireIfOffered(); hits = block; offered = false }
    fun hit(vararg splats: NpcHitsBlock.Hit) = hit(NpcHitsBlock(splats.toList()))

    fun faceTile(block: NpcFaceCoordinateBlock?) = apply { retireIfOffered(); faceCoordinate = block; offered = false }
    fun faceTile(x: Int, y: Int) = faceTile(NpcFaceCoordinateBlock(x, y))

    fun say(block: NpcSayBlock?) = apply { retireIfOffered(); say = block; offered = false }
    fun say(text: String) = say(NpcSayBlock(text))

    fun animationGroup(block: NpcAnimationGroupBlock?) = apply { retireIfOffered(); animationGroup = block; offered = false }
    fun animationGroup(group: Int) = animationGroup(NpcAnimationGroupBlock(group))

    fun faceEntity(block: NpcFaceEntityBlock?) = apply { retireIfOffered(); faceEntity = block; offered = false }
    fun faceNpc(index: Int) = faceEntity(NpcFaceEntityBlock.npc(index))
    fun facePlayer(index: Int) = faceEntity(NpcFaceEntityBlock.player(index))
    fun faceNothing() = faceEntity(NpcFaceEntityBlock.clear())

    fun forceMovement(block: NpcForceMovementBlock?) = apply { retireIfOffered(); forceMovement = block; offered = false }

    fun string17(block: NpcString17Block?) = apply { retireIfOffered(); string17 = block; offered = false }
    fun string17(text: String) = string17(NpcString17Block(text))

    fun short22(block: NpcShort22Block?) = apply { retireIfOffered(); short22 = block; offered = false }
    fun short22(value: Int) = short22(NpcShort22Block(value))

    fun byte25(value: Int) = apply { retireIfOffered(); byte25 = NpcByte25Block(value); offered = false }
    fun flag28(value: Int) = apply { retireIfOffered(); flag28 = NpcFlag28Block(value); offered = false }
    fun byte34(value: Int) = apply { retireIfOffered(); byte34 = NpcByte34Block(value); offered = false }

    fun lifepoints(block: NpcLifepointsBlock?) = apply { retireIfOffered(); lifepoints = block; offered = false }

    /** One entry: [current] of [maximum]. */
    fun lifepoints(current: Int, maximum: Int) = lifepoints(NpcLifepointsBlock.single(current, maximum))

    /**
     * One of the seven discarded-field bodies. [type] must be a key of
     * [NpcDiscardedBlock.LAYOUTS]; anything else throws, because the whole point
     * of these is that their WIDTH is exact.
     */
    fun discarded(type: NpcUpdateBlockType, f1: Int = 0, f2: Int = 0, f3: Int = 0) = apply {
        retireIfOffered()
        discarded[type] = NpcDiscardedBlock(type, f1, f2, f3)
        offered = false
    }

    fun clear() {
        animation = null
        hits = null
        faceCoordinate = null
        say = null
        animationGroup = null
        faceEntity = null
        forceMovement = null
        string17 = null
        short22 = null
        byte25 = null
        flag28 = null
        byte34 = null
        lifepoints = null
        discarded.clear()
        offered = false
    }

    /**
     * The blocks that will actually be written, in the client's dispatch order
     * ([NpcUpdateBlockType.order]) and with the disabled ones removed.
     *
     * Sorting here rather than trusting declaration order is deliberate: the
     * extended-info section has no length fields at all, so a block written
     * out of order does not produce a bad block, it produces a bad REST OF THE
     * PACKET.
     */
    fun blocks(): List<NpcUpdateBlock> {
        if (!extendedEnabled) return emptyList()
        val out = ArrayList<NpcUpdateBlock>(16)
        animation?.let { out.add(it) }
        hits?.let { out.add(it) }
        faceCoordinate?.let { out.add(it) }
        say?.let { out.add(it) }
        animationGroup?.let { out.add(it) }
        faceEntity?.let { out.add(it) }
        forceMovement?.let { out.add(it) }
        string17?.let { out.add(it) }
        short22?.let { out.add(it) }
        byte25?.let { out.add(it) }
        flag28?.let { out.add(it) }
        byte34?.let { out.add(it) }
        lifepoints?.let { out.add(it) }
        out.addAll(discarded.values)
        out.removeAll { !blockEnabled(it.type) }
        out.sortBy { it.type.order }
        return out
    }

    /** True when this npc has at least one block that will be written. */
    fun isEmpty(): Boolean = blocks().isEmpty()

    /**
     * Writes the two skipped bytes, the mask and every enabled block.
     * Returns false when there was nothing to write, in which case NOTHING was
     * written and the caller must not have set the has-extended-info bit.
     */
    fun encode(buffer: GamePacketBuilder): Boolean {
        val blocks = blocks()
        if (blocks.isEmpty()) return false

        // Two bytes are skipped per npc,
        // before the mask, without ever being read. Their value is arbitrary.
        buffer.put(com.opennxt.net.buf.DataType.BYTE, SKIPPED_FILLER)
        buffer.put(com.opennxt.net.buf.DataType.BYTE, SKIPPED_FILLER)

        var mask = 0L
        for (block in blocks) mask = mask or block.type.maskBit
        NpcUpdateBlockType.writeMask(buffer, mask)

        for (block in blocks) block.encode(buffer)
        return true
    }

    companion object {

        const val SKIPPED_FILLER = 0

        /** `-Dopennxt.experiment.npcs.extended=false` kills the whole section. */
        val extendedEnabled: Boolean =
            System.getProperty("opennxt.experiment.npcs.extended") != "false"

        /** `-Dopennxt.experiment.npcs.block.<name>=false` kills one block type. */
        fun blockEnabled(type: NpcUpdateBlockType): Boolean =
            System.getProperty("opennxt.experiment.npcs.block.${type.name.lowercase()}") != "false"
    }
}
