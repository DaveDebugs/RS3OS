package com.opennxt.model.entity.rendering

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.entity.rendering.blocks.PlayerByte11Block
import com.opennxt.model.entity.rendering.blocks.PlayerDiscardedBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFaceEntityBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFlag21Block
import com.opennxt.model.entity.rendering.blocks.PlayerForceMovementBlock
import com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock
import com.opennxt.model.entity.rendering.blocks.PlayerSayBlock
import com.opennxt.model.entity.rendering.blocks.PlayerStringFlagBlock

/**
 * Queueing and GATING for the player extended-info blocks added alongside
 * [UpdateBlockType.APPEARANCE].
 *
 * The blocks themselves live in [EntityRenderer.blocks], the array that was
 * already there and has never had anything put in it; [UpdateBlockType.playerPos]
 * is the slot. This file only decides WHICH of them are allowed on the wire,
 * and offers a small typed way to put one there so that nothing has to reach
 * into a raw `arrayOfNulls<UpdateBlock>(30)` by index.
 *
 * NOTHING IN THIS PASS CALLS THE SETTERS DURING A WORLD TICK. This is wire
 * plumbing, not gameplay: no combat, no emote, no animation id is chosen
 * anywhere in this repository. The one exception is the opt-in demo in
 * `PlayerInfoEncoder`, whose CONTENT comes entirely from the property values
 * the operator types.
 *
 * SWITCHES
 * --------
 *  `-Dopennxt.experiment.playerExtended=false`
 *      turns off every block added by this pass. [UpdateBlockType.APPEARANCE]
 *      is untouched and keeps going out exactly as it does today, so a session
 *      with this set is byte-for-byte the known-good state.
 *
 *  `-Dopennxt.experiment.player.block.<NAME>=false`
 *      turns off ONE block. NAME is the [UpdateBlockType] constant,
 *      lower-cased: `face_entity`, `animation`, `hits`, `face_direction`,
 *      `say`. APPEARANCE deliberately does NOT respond to this - its kill
 *      switch is the existing `-Dopennxt.experiment.appearance=false`, and
 *      giving it a second one would let a typo silently change a
 * confirmed path.
 */
object PlayerUpdates {

    /**
     * Every block EXCEPT APPEARANCE, derived rather than listed.
     *
     * A hand-written list stops being complete the first time somebody adds an
     * enum entry, and the consequence of a missing entry here is a block that
     * goes on the wire with no kill switch. APPEARANCE is excluded because its
     * switch is the existing `-Dopennxt.experiment.appearance`, and giving a
     * confirmed path a second switch invites a typo to change it.
     */
    val EXPERIMENTAL: Set<UpdateBlockType> =
        UpdateBlockType.values().filter { it != UpdateBlockType.APPEARANCE }.toSet()

    /** `-Dopennxt.experiment.playerExtended=false` kills all of [EXPERIMENTAL]. */
    val extendedEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.playerExtended") != "false"

    /**
     * Whether [type] may be written at all. APPEARANCE always may, as far as
     * this file is concerned.
     */
    fun blockEnabled(type: UpdateBlockType): Boolean {
        if (type !in EXPERIMENTAL) return true
        if (!extendedEnabled) return false
        return System.getProperty("opennxt.experiment.player.block.${type.name.lowercase()}") != "false"
    }

    // ------------------------------------------------------------- queueing

    fun queue(entity: Entity, block: UpdateBlock) {
        entity.renderer.blocks[block.type.playerPos] = block
    }

    fun remove(entity: Entity, type: UpdateBlockType) {
        entity.renderer.blocks[type.playerPos] = null
    }

    /** Drops every experimental block. Leaves the APPEARANCE slot alone. */
    fun clear(entity: Entity) {
        for (type in EXPERIMENTAL) entity.renderer.blocks[type.playerPos] = null
    }

    fun animate(entity: Entity, block: PlayerAnimationBlock) = queue(entity, block)
    fun animate(entity: Entity, id: Int, delay: Int = 0) = queue(entity, PlayerAnimationBlock.single(id, delay))

    fun hit(entity: Entity, block: PlayerHitsBlock) = queue(entity, block)
    fun hit(entity: Entity, vararg splats: PlayerHitsBlock.Hit) = queue(entity, PlayerHitsBlock(splats.toList()))

    fun say(entity: Entity, block: PlayerSayBlock) = queue(entity, block)
    fun say(entity: Entity, text: String) = queue(entity, PlayerSayBlock(text))

    fun faceEntity(entity: Entity, block: PlayerFaceEntityBlock) = queue(entity, block)
    fun faceNpc(entity: Entity, index: Int) = queue(entity, PlayerFaceEntityBlock.npc(index))
    fun facePlayer(entity: Entity, index: Int) = queue(entity, PlayerFaceEntityBlock.player(index))
    fun faceNothing(entity: Entity) = queue(entity, PlayerFaceEntityBlock.clear())

    fun faceDirection(entity: Entity, block: PlayerFaceDirectionBlock) = queue(entity, block)
    fun faceDirection(entity: Entity, angle: Int) = queue(entity, PlayerFaceDirectionBlock(angle))

    fun forceMovement(entity: Entity, block: PlayerForceMovementBlock) = queue(entity, block)

    fun stringFlag(entity: Entity, block: PlayerStringFlagBlock) = queue(entity, block)
    fun stringFlag(entity: Entity, text: String, flag: Int = PlayerStringFlagBlock.FLAG_ENABLE) =
        queue(entity, PlayerStringFlagBlock(text, flag))

    fun byte11(entity: Entity, value: Int) = queue(entity, PlayerByte11Block(value))
    fun flag21(entity: Entity, value: Int) = queue(entity, PlayerFlag21Block(value))

    /**
     * One of the seven bodies whose fields the client discards. [type] must be a
     * key of [PlayerDiscardedBlock.LAYOUTS]; anything else throws, because the
     * whole point of these is that their WIDTH is exact.
     */
    fun discarded(entity: Entity, type: UpdateBlockType, f1: Int = 0, f2: Int = 0, f3: Int = 0) =
        queue(entity, PlayerDiscardedBlock(type, f1, f2, f3))

    /**
     * A one-shot, opt-in way to put ONE of these blocks on the wire so an
     * operator can see whether it lands, without a rebuild and without this
     * server growing any gameplay.
     *
     * Every value comes from the property the operator types. Nothing is
     * chosen here - in particular no animation id, because the
     * animation-group config is not decoded and picking an id would be
     * inventing content. With no properties set, [apply] does nothing at all.
     *
     *   -Dopennxt.experiment.player.demo.say=<text>
     *   -Dopennxt.experiment.player.demo.anim=<id>[,<delay>]
     *   -Dopennxt.experiment.player.demo.hit=<type>,<amount>[,<delay>]
     *   -Dopennxt.experiment.player.demo.facedir=<0..16383>
     *   -Dopennxt.experiment.player.demo.facenpc=<index>
     *   -Dopennxt.experiment.player.demo.faceplayer=<index>
     *   -Dopennxt.experiment.player.demo.force=a1,a2,a3,b1,b2,b3,c1,c2,angle
     *   -Dopennxt.experiment.player.demo.string18=<text>[,<flag>]
     *
     * It fires ONCE per entity, tracked by identity, because these blocks are
     * queued state with no tick owner yet (see the HANDOFF note: clearing
     * belongs in the world tick, which this pass does not own). Firing every
     * tick would re-send the same overhead text forever.
     */
    object Demo {

        private const val PREFIX = "opennxt.experiment.player.demo."

        private val fired = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Entity, Boolean>()
        )

        /** True when at least one demo property is set. */
        val armed: Boolean
            get() = KEYS.any { System.getProperty(PREFIX + it) != null }

        /**
         * PUBLIC so the harness can clear every one of them without keeping its
         * own copy of the list. A harness list that drifts from this one measures
         * the wrong thing silently, which is worse than not measuring.
         */
        val KEYS = listOf(
            "say", "anim", "hit", "facedir", "facenpc", "faceplayer", "force", "string18"
        )

        /** The full property name for [key], for a caller that wants to print it. */
        fun property(key: String): String = PREFIX + key

        @Synchronized
        fun apply(entity: Entity) {
            if (!extendedEnabled || !armed) return
            if (!fired.add(entity)) return

            System.getProperty(PREFIX + "say")?.let { say(entity, it) }
            System.getProperty(PREFIX + "anim")?.let {
                val parts = it.split(',')
                animate(entity, parts[0].trim().toInt(), parts.getOrNull(1)?.trim()?.toInt() ?: 0)
            }
            System.getProperty(PREFIX + "hit")?.let {
                val parts = it.split(',')
                require(parts.size >= 2) { "player.demo.hit wants <type>,<amount>[,<delay>]" }
                hit(
                    entity, PlayerHitsBlock.Hit(
                        parts[0].trim().toInt(), parts[1].trim().toInt(),
                        parts.getOrNull(2)?.trim()?.toInt() ?: 0
                    )
                )
            }
            System.getProperty(PREFIX + "facedir")?.let { faceDirection(entity, it.trim().toInt()) }
            System.getProperty(PREFIX + "facenpc")?.let { faceNpc(entity, it.trim().toInt()) }
            System.getProperty(PREFIX + "faceplayer")?.let { facePlayer(entity, it.trim().toInt()) }
            // -Dopennxt.experiment.player.demo.force=a1,a2,a3,b1,b2,b3,c1,c2,angle
            // Nine numbers, all of them the operator's. Nothing is defaulted,
            // because a "reasonable" force-movement is a gameplay decision and
            // the field meanings are not decoded.
            System.getProperty(PREFIX + "force")?.let {
                val p = it.split(',').map { v -> v.trim().toInt() }
                require(p.size == 9) {
                    "player.demo.force wants nine numbers: a1,a2,a3,b1,b2,b3,c1,c2,angle"
                }
                forceMovement(
                    entity,
                    PlayerForceMovementBlock(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
                )
            }
            // -Dopennxt.experiment.player.demo.string18=<text>[,<flag>]
            System.getProperty(PREFIX + "string18")?.let {
                val comma = it.lastIndexOf(',')
                val flag = if (comma > 0) it.substring(comma + 1).trim().toIntOrNull() else null
                if (flag != null) stringFlag(entity, it.substring(0, comma), flag)
                else stringFlag(entity, it)
            }
        }

        /** For the harness: forget who has already been fired at. */
        @Synchronized
        fun reset() = fired.clear()
    }
}
