package com.opennxt.resources.sqlite

/**
 * The definition types OpenNXT had none of.
 *
 * Field names follow the decoded database's column names rather than being
 * renamed to something prettier, so a question about where a value comes from is
 * answered by one grep in `rs3.sqlite`. Fields the server does not use yet are
 * deliberately absent rather than carried as nulls - `_attr` in the database
 * holds every field that did not earn a column, and can be read directly.
 *
 * Columns whose meaning is not known keep their literal database spelling
 * (`unknown_0A`, `unk2e`) instead of being camel-cased. Camel-casing them would
 * force a choice between `unknown0A` and `unknown0a` and make the grep back to
 * the column ambiguous, and the underscore is itself a useful marker: a field
 * that still looks like a column name is one nobody has identified yet.
 */

data class ItemDefinition(
    val id: Int,
    val name: String?,
    val members: Boolean,
    val tradeable: Boolean,
    val stackable: Boolean,
    val equipSlotId: Int?,
    val equipId: Int?,
    val buyLimit: Int?,
    val category: Int?,
    val dummyItem: Int?,
    /** widget_actions_0..4; index is the option slot, null where the slot is empty. */
    val inventoryActions: Array<String?>
) {
    override fun equals(other: Any?) = other is ItemDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "ItemDefinition($id, ${name ?: "unnamed"})"
}

data class NpcDefinition(
    val id: Int,
    val name: String?,
    /** boundSize - tiles occupied per side. Absent means 1. */
    val size: Int,
    val combatLevel: Int?,
    val movementType: Int?,
    val animationGroup: Int?,
    val drawMapDot: Boolean,
    /** actions_0..4; the right-click options. */
    val actions: Array<String?>
) {
    override fun equals(other: Any?) = other is NpcDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "NpcDefinition($id, ${name ?: "unnamed"}, size=$size)"
}

data class LocDefinition(
    val id: Int,
    val name: String?,
    /** width/length in tiles, before rotation. Absent means 1. */
    val width: Int,
    val length: Int,
    /**
     * The clipping flag. In the decoded config this is `blocks_movement`; a loc
     * that blocks movement is what bakes into the collision map. `walkable`
     * (config's own name) means "can be walked over" and is a different thing -
     * both are carried so neither has to be inferred from the other.
     */
    val blocksMovement: Boolean,
    val walkable: Boolean,
    val allowsLineOfSight: Boolean,
    val animation: Int?,
    val isMembers: Boolean,
    /** actions_0..4; "Open" on a door lives here. */
    val actions: Array<String?>
) {
    override fun equals(other: Any?) = other is LocDefinition && other.id == id
    override fun hashCode() = id
    override fun toString() = "LocDefinition($id, ${name ?: "unnamed"}, ${width}x$length)"
}

/**
 * A varbit is a named bit range inside a 32-bit varp: reading it is
 * `(varp >> bitStart) and mask`, writing it must preserve the bits either side.
 *
 * [varId] is not a bare varp id for every row: 10774 of the 61872 rows carry a
 * value above 65535, and `varId ushr 16` lines up with [com.opennxt.api.vars.VarDomain]
 * (0=player, 1=npc, 2=client, ... 8=campaign). Evidence: splitting the table that
 * way and looking `varId and 0xFFFF` up in the matching `vars_*` table resolves
 * every row for domains 1, 4, 5 and 8, and all but 247 rows overall. Nothing here
 * acts on that split - see [com.opennxt.model.vars.VarPlayerState], which refuses
 * varbits whose domain is not the player's rather than writing them into a varp
 * number that no player has.
 */
data class VarBitDefinition(
    val id: Int,
    val varId: Int,
    val bitStart: Int,
    val bitEnd: Int
) {
    val bitCount: Int get() = bitEnd - bitStart + 1

    /** The field's bits, right-aligned. All ones (-1) for a field spanning the whole varp. */
    val mask: Int get() = if (bitCount >= 32) -1 else (1 shl bitCount) - 1

    /**
     * The largest value the field holds, as the bit pattern it holds it as.
     * For a 32-bit field that pattern is -1 as a signed Int, not a maximum to
     * compare against - use [fits] to range-check, never `value <= maxValue`.
     */
    val maxValue: Int get() = mask

    /** The same maximum as an unbounded quantity: 4294967295 for a 32-bit field. */
    val maxValueUnsigned: Long get() = if (bitCount in 1..32) (1L shl bitCount) - 1 else 0L

    /**
     * Whether the row describes a bit range that can exist in a 32-bit varp.
     * Measured over rs3.sqlite: all 61872 rows are well formed. Callers still
     * check, because a malformed row would otherwise be shifted by a nonsense
     * amount and silently corrupt a neighbouring field.
     */
    val isWellFormed: Boolean
        get() = bitStart in 0..31 && bitEnd in 0..31 && bitStart <= bitEnd

    /**
     * Whether [value] fits the field. A 32-bit field is the whole varp, so every
     * Int fits it - including negative ones, which is why this is not a range
     * comparison. Narrower fields are unsigned: 0..[maxValue].
     */
    fun fits(value: Int): Boolean = if (bitCount >= 32) true else value in 0..maxValue

    fun read(varpValue: Int): Int = (varpValue ushr bitStart) and mask

    fun write(varpValue: Int, value: Int): Int {
        require(fits(value)) {
            "varbit $id holds $bitCount bit(s) (0..$maxValueUnsigned); refusing to write $value"
        }
        return (varpValue and (mask shl bitStart).inv()) or ((value and mask) shl bitStart)
    }

    override fun toString() = "VarBitDefinition($id, varp=$varId, bits=$bitStart..$bitEnd)"
}

/**
 * An animation.
 *
 * [id] and [gameId] are the same number. Both are dense: 37853 rows over
 * 0..37852, and `id <> game_id` selects zero of them. `_group`/`_file` are
 * carried alongside at stride 128 - `_group*128 + _file = id` on all 37853
 * rows, max `_file` 127, max `_group` 295 - which is the packing
 * [com.opennxt.model.definitions.SeqDefinitions] uses at runtime. Every
 * cross-reference therefore resolves straight against [id]: of the 8920
 * non-null `spotanims.sequence` values ZERO fail to match one, and the same
 * holds for `animgroups.run` (1163 non-null, zero misses).
 */
data class SequenceDefinition(
    val id: Int,
    val gameId: Int,
    val skeletalAnimation: Int?,
    val unknown_02: Int?,
    val unknown_05: Int?,
    /**
     * The item held in the left hand while this animation plays, or null.
     *
     * EVIDENCED. The NAME is DOCUMENTED (RuneLite `SequenceLoader` op 6); what
     * makes it usable here is cache-side corroboration, because a name from
     * another decoder is a lead, not a measurement. Of the 642 references that
     * resolve to an equippable item, the values occupy exactly two of fourteen
     * equip slots - slot 5 (shield) 57.32% and slot 3 (weapon) 42.68% - and 06
     * = shield with 07 = weapon jointly on 303 of 357. A permutation control
     * fired (top-slot share mean 25.5%, max 33.1%).
     *
     * Explicitly NOT part of the evidence: that the values resolve to valid
     * item ids at all. They do, 100% of the time, and that is worth nothing -
     * the item id space is dense enough that any 16-bit number lands on one.
     */
    val leftHandItem: Int?,

    /**
     * The item held in the right hand while this animation plays, or null.
     * DOCUMENTED as RuneLite `SequenceLoader` op 7, corroborated from the
     * cache: of its 3,056 real references 2,162 are equippable against a 32.6%
     * baseline, and 2,102 of those - 97.22% - sit in equip slot 3, the weapon
     * slot. See [leftHandItem] for the control.
     */
    val rightHandItem: Int?,
    val unknown_09: Int?,
    val unknown_0A: Int?,
    val unknown_0B: Int?,
    val unknown_0F: Int?,
    val unknown_12: Int?,
    val unknown_18: Int?
) {
    override fun toString() = "SequenceDefinition($id, gameId=$gameId)"
}

/**
 * A graphical effect ("gfx"): a model, optionally animated by a sequence.
 *
 * [model] is the only field set on every row, so it is the only non-null one.
 * [sequence] is a `sequences.game_id`, not a `sequences.id` - see
 * [SequenceDefinition]. It is null on 161 rows, meaning a still model rather
 * than sequence 0, which is why it is nullable instead of defaulted.
 */
data class SpotAnimDefinition(
    val id: Int,
    val ambient: Int?,
    val contrast: Int?,
    val model: Int,
    val sequence: Int?,
    val unk0a: Int?,
    val unk2e: Int?
) {
    override fun toString() = "SpotAnimDefinition($id, model=$model)"
}

/**
 * The set of animations an NPC or player uses for movement; `npcs.animation_group`
 * indexes this table by id (verified: every non-null `animation_group` resolves).
 *
 * Every field is a `sequences.game_id` - see [SequenceDefinition] - and every one
 * is nullable: a group typically defines only the handful of animations it
 * overrides (`run` is set on 1163 of 4907 rows, `walk_back` on 886), and null
 * means "not overridden", which is a different statement from "sequence 0".
 */
data class AnimGroupDefinition(
    val id: Int,
    val run: Int?,
    val turnonspot1: Int?,
    val turnonspot2: Int?,
    val unknown_32: Int?,
    val unknown_33: Int?,
    val walkBack: Int?,
    val walkLeft: Int?,
    val walkRight: Int?
) {
    override fun toString() = "AnimGroupDefinition($id)"
}

/**
 * A quest, as the quest list widget sees it.
 *
 * [members] is a presence flag and follows `ItemDefinition.members`: the opcode
 * is either there or it is not, so absent is false rather than unknown. The
 * numeric fields are not flags - [questPoints] is absent on 154 of 531 rows, and
 * a quest awarding an unrecorded number of points is not a quest awarding zero -
 * so those stay nullable.
 *
 * [questListName] is read as a string even though its column is declared NUMERIC:
 * SQLite's NUMERIC affinity leaves values it cannot convert as TEXT, and every
 * non-null value in this column is text. Reading it with getInt would silently
 * yield 0 for all 529 of them.
 */
data class QuestDefinition(
    val id: Int,
    val name: String?,
    val members: Boolean,
    val questDifficulty: Int?,
    val questItemSprite: Int?,
    val questListName: String?,
    val questPoints: Int?
) {
    override fun toString() = "QuestDefinition($id, ${name ?: "unnamed"})"
}
