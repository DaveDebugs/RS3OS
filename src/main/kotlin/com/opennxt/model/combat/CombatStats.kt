package com.opennxt.model.combat

import com.opennxt.api.stat.Stat

/**
 * An entity's combat-relevant state: lifepoints, and the base/current levels of
 * the stats combat reads.
 *
 * ## What in here is grounded in cache data and what is not
 *
 * This file sits on the wrong side of a hard line, and the line is worth
 * stating once, at the top, rather than being re-litigated at every constant.
 *
 * RuneScape's combat *formulas* - the accuracy roll, the max-hit calculation,
 * the damage roll - were never shipped in the game cache. They ran on Jagex's
 * servers. Nothing in `data/rs3.sqlite` can confirm or refute any of them, so
 * this file does not contain any of them. What it contains is bookkeeping:
 * storing levels, storing lifepoints, adding and subtracting them, and clamping
 * the results. That bookkeeping is not a reconstruction of anything - it is just
 * a container - and it is deliberately kept free of the parts that would be.
 *
 * The one number in this file that is a claim about RuneScape rather than a
 * container is [Lifepoints.PER_CONSTITUTION_LEVEL]. It is labelled at its
 * declaration. It is NOT verified against this cache, because it is not in this
 * cache.
 *
 * See [com.opennxt.model.combat.NpcCombat] for the NPC side, where the split
 * between measured and unmeasured is sharper still: NPC combat *ratings* are in
 * the cache, and NPC lifepoints are not.
 */

/**
 * The three combat classes an attack can belong to.
 *
 * These three names are no longer merely conventional: the cache spells them
 * out. Enum 16502 carries the strings "Magic", "Melee", "Ranged" and enum 7733
 * carries the ten specific attack types they subdivide into. See
 * [NpcCombatParams.WEAKNESS_CLASS] for how those two enums were reached and what
 * they license.
 *
 * A fourth class, Necromancy, exists in the data (enum 7733 key 37, and 9 NPCs
 * carry the matching param-26 value 17). It is deliberately NOT a member here.
 * This server models no necromancy combat, and adding a value the rest of the
 * package cannot handle would trade a missing feature for silent wrong answers;
 * [NpcWeakness] carries it as data and maps its [NpcWeakness.style] to null.
 */
enum class CombatStyle {
    MELEE,
    RANGED,
    MAGIC;

    /**
     * The style this one is strong against, i.e. the style of an NPC that is
     * weak to this one: Melee beats Ranged beats Magic beats Melee.
     *
     * This is the combat triangle, which is public RuneScape knowledge rather
     * than a cache field - but its use here is measured, not assumed. Applied to
     * the weakness class the cache does carry it recovers an NPC's own attack
     * style with 99.49% agreement over 4,669 unambiguous NPCs, and all six
     * possible assignments were scored (next best 72.39%). The measurement is at
     * [NpcCombatParams.WEAKNESS_CLASS]; [NpcCombatDefinition.combatStyle] is the
     * consumer.
     */
    val beats: CombatStyle
        get() = when (this) {
            MELEE -> RANGED
            RANGED -> MAGIC
            MAGIC -> MELEE
        }

    /** The inverse of [beats]: the style this one loses to. */
    val weakTo: CombatStyle
        get() = when (this) {
            MELEE -> MAGIC
            RANGED -> MELEE
            MAGIC -> RANGED
        }
}

/** Lifepoint arithmetic for players. */
object Lifepoints {

    /**
     * Lifepoints granted per level of Constitution.
     *
     * RECONSTRUCTED - NOT VERIFIED AGAINST THIS CACHE.
     *
     * This value is public knowledge about RuneScape 3: since the 2012 combat
     * rework, a player's maximum lifepoints is their Constitution level times
     * 100, so level 99 Constitution is 9,900 lifepoints and level 120 is 12,000,
     * before additive bonuses.
     *
     * I could not ground the 100 in `data/rs3.sqlite`, and I looked. What the
     * database has about Constitution is what it has about every other skill: a
     * stat definition, a level cap and an experience table. There is no column,
     * param or enum anywhere in the 32,687 NPC rows or the item params that
     * multiplies a Constitution level into a lifepoint total. The multiplier
     * lived in server code, and server code is not in a client cache.
     *
     * So: this is an assumption imported from outside the data, isolated to one
     * named constant so that it can be changed in one place if it is wrong, and
     * so that nothing downstream can mistake it for a measurement.
     */
    const val PER_CONSTITUTION_LEVEL = 100

    /**
     * Maximum lifepoints for a player at Constitution [constitutionLevel], plus
     * any flat [bonus] (item and buff bonuses in RS3 are additive on top of the
     * level-derived total, e.g. the Ring of Life-style flat boosts).
     *
     * Built entirely on [PER_CONSTITUTION_LEVEL], and therefore carries that
     * constant's caveat: reconstructed, not measured.
     *
     * Levels below 1 clamp to 1. There is no level 0 in RuneScape, and a
     * maximum of 0 lifepoints would make an entity permanently dead.
     */
    fun forConstitutionLevel(constitutionLevel: Int, bonus: Int = 0): Int =
        (constitutionLevel.coerceAtLeast(1) * PER_CONSTITUTION_LEVEL + bonus).coerceAtLeast(1)

    /**
     * THE VARP THE 949 CLIENT READS CURRENT LIFEPOINTS FROM. .
     */
    const val CURRENT_LIFEPOINTS_VARP = 13537
}

/**
 * The stats combat reads.
 *
 * Grounded: these are [Stat] constants, whose ids come from the cache's own stat
 * definitions (see [Stat.reload]). The *selection* of which of the 28 are
 * "combat" stats is conventional knowledge, not a cache field - the cache does
 * not tag skills as combat or non-combat. It is only used here to decide what
 * [CombatStats] bothers to track.
 */
val COMBAT_STATS: List<Stat> = listOf(
    Stat.ATTACK,
    Stat.STRENGTH,
    Stat.DEFENCE,
    Stat.CONSTITUTION,
    Stat.RANGED,
    Stat.MAGIC,
    Stat.PRAYER,
    Stat.SUMMONING
)

/**
 * Mutable combat state for one entity.
 *
 * Two level numbers are kept per stat because boosts and drains need both:
 *
 *  - **base** level: what the entity's experience earns it. Changes only when
 *    the entity levels up.
 *  - **current** level: base, plus any boost, minus any drain. This is what a
 *    combat calculation would read and what the client's stat orb shows.
 *
 * A drain floors at 0 (a fully drained stat, not a negative one). A boost has no
 * ceiling imposed here: RuneScape's boost caps are per-item and per-prayer, they
 * are not a property of the stat, and inventing a global cap would be exactly
 * the sort of unfounded rule this class is trying to avoid. Callers that know
 * their boost's cap apply it themselves.
 *
 * Lifepoints are stored, not derived on read, so that damage and healing are not
 * silently undone by a level change mid-fight. [recalculateMaximum] is the
 * explicit way to re-derive the ceiling.
 *
 * This class holds no formulas. It does not decide whether an attack hits, or
 * for how much; it is told how much damage to apply. That separation is
 * deliberate - see the file header.
 */
class CombatStats(
    /**
     * Maximum lifepoints. For a player this normally comes from
     * [Lifepoints.forConstitutionLevel]; for an NPC it has to be supplied by the
     * caller, because NPC lifepoints are not in this cache - see [NpcCombat].
     */
    maximumLifepoints: Int,
    /** Flat additive lifepoint bonus, applied on top of the level-derived total. */
    var lifepointBonus: Int = 0
) {
    private val baseLevels = IntArray(Stat.values().size) { 1 }
    private val currentLevels = IntArray(Stat.values().size) { 1 }

    var maximumLifepoints: Int = maximumLifepoints.coerceAtLeast(1)
        private set

    /** Current lifepoints. Always in `0..maximumLifepoints`. */
    var currentLifepoints: Int = this.maximumLifepoints
        private set

    /** An entity is dead once it has no lifepoints left. */
    val isDead: Boolean get() = currentLifepoints <= 0

    /** Current lifepoints as a 0..100 percentage of the maximum, rounded down. */
    val lifepointPercent: Int
        get() = if (maximumLifepoints <= 0) 0 else (currentLifepoints * 100) / maximumLifepoints

    // ---------------------------------------------------------------- levels

    fun baseLevel(stat: Stat): Int = baseLevels[stat.id]

    fun currentLevel(stat: Stat): Int = currentLevels[stat.id]

    /**
     * Sets the base level, and moves the current level with it by the same
     * amount so an existing boost or drain survives a level-up rather than being
     * silently cancelled.
     *
     * Setting Constitution does NOT recalculate maximum lifepoints on its own -
     * call [recalculateMaximum] for that. Keeping it explicit means an NPC,
     * whose lifepoints have nothing to do with a Constitution level, cannot have
     * its maximum overwritten by a stat assignment.
     */
    fun setBaseLevel(stat: Stat, level: Int) {
        val clamped = level.coerceAtLeast(1)
        val delta = clamped - baseLevels[stat.id]
        baseLevels[stat.id] = clamped
        currentLevels[stat.id] = (currentLevels[stat.id] + delta).coerceAtLeast(0)
    }

    /** Sets base and current level together, discarding any boost or drain. */
    fun setLevel(stat: Stat, level: Int) {
        val clamped = level.coerceAtLeast(1)
        baseLevels[stat.id] = clamped
        currentLevels[stat.id] = clamped
    }

    /** Raises the current level. Positive [amount] only; use [drain] to lower it. */
    fun boost(stat: Stat, amount: Int) {
        if (amount <= 0) return
        currentLevels[stat.id] = currentLevels[stat.id] + amount
    }

    /** Lowers the current level, flooring at 0. Returns the amount actually drained. */
    fun drain(stat: Stat, amount: Int): Int {
        if (amount <= 0) return 0
        val before = currentLevels[stat.id]
        currentLevels[stat.id] = (before - amount).coerceAtLeast(0)
        return before - currentLevels[stat.id]
    }

    /** Clears any boost or drain on [stat]. */
    fun restore(stat: Stat) {
        currentLevels[stat.id] = baseLevels[stat.id]
    }

    /** Clears every boost and drain. */
    fun restoreAllLevels() {
        for (stat in Stat.values()) currentLevels[stat.id] = baseLevels[stat.id]
    }

    /** How far [stat] is above (positive) or below (negative) its base level. */
    fun modifier(stat: Stat): Int = currentLevels[stat.id] - baseLevels[stat.id]

    // ------------------------------------------------------------ lifepoints

    /**
     * Re-derives [maximumLifepoints] from the *base* Constitution level via
     * [Lifepoints.forConstitutionLevel], plus [lifepointBonus].
     *
     * Base, not current: this is the player rule, and it inherits
     * [Lifepoints.PER_CONSTITUTION_LEVEL]'s reconstructed status. Only call it
     * for entities whose maximum is supposed to follow Constitution - an NPC's
     * does not.
     *
     * Current lifepoints are clamped down if the new maximum is lower, and left
     * alone otherwise: a level-up raises the ceiling, it does not heal.
     */
    fun recalculateMaximum() {
        setMaximumLifepoints(Lifepoints.forConstitutionLevel(baseLevel(Stat.CONSTITUTION), lifepointBonus))
    }

    /** Sets the maximum directly, clamping current lifepoints into the new range. */
    fun setMaximumLifepoints(maximum: Int) {
        maximumLifepoints = maximum.coerceAtLeast(1)
        if (currentLifepoints > maximumLifepoints) currentLifepoints = maximumLifepoints
    }

    /**
     * Applies [amount] damage and returns how much was *actually* applied, which
     * is less than [amount] when the hit would take the entity below 0.
     *
     * The distinction matters for anything that reads damage back out - xp,
     * threat, overkill - which should count damage dealt, not damage attempted.
     * Non-positive amounts apply nothing.
     */
    fun damage(amount: Int): Int {
        if (amount <= 0) return 0
        val applied = minOf(amount, currentLifepoints)
        currentLifepoints -= applied
        return applied
    }

    /**
     * Heals up to [amount], returning how much was actually restored.
     *
     * Healing a dead entity is allowed and brings it back above 0; refusing to
     * would put a resurrection rule in a container class, and whether death is
     * final is the caller's decision, not this class's.
     */
    fun heal(amount: Int): Int {
        if (amount <= 0) return 0
        val applied = minOf(amount, maximumLifepoints - currentLifepoints)
        if (applied <= 0) return 0
        currentLifepoints += applied
        return applied
    }

    /** Sets current lifepoints directly, clamped to `0..maximumLifepoints`. */
    fun setLifepoints(value: Int) {
        currentLifepoints = value.coerceIn(0, maximumLifepoints)
    }

    /** Restores to full. */
    fun healToFull() {
        currentLifepoints = maximumLifepoints
    }

    override fun toString() =
        "CombatStats(lp=$currentLifepoints/$maximumLifepoints, " +
            COMBAT_STATS.joinToString(", ") { "${it.name.take(3)}=${currentLevel(it)}/${baseLevel(it)}" } + ")"

    companion object {
        /**
         * A player's starting combat state: every combat stat at level 1 except
         * Constitution at 10, and lifepoints derived from that.
         *
         * Constitution starting at 10 is RuneScape convention (a new account has
         * 10 Hitpoints/Constitution and 1,000 lifepoints), not a cache field.
         * It is here rather than hardcoded at call sites for the same reason as
         * [Lifepoints.PER_CONSTITUTION_LEVEL]: one place to correct.
         */
        const val STARTING_CONSTITUTION_LEVEL = 10

        fun newPlayer(): CombatStats {
            val stats = CombatStats(Lifepoints.forConstitutionLevel(STARTING_CONSTITUTION_LEVEL))
            COMBAT_STATS.forEach { stats.setLevel(it, 1) }
            stats.setLevel(Stat.CONSTITUTION, STARTING_CONSTITUTION_LEVEL)
            stats.recalculateMaximum()
            stats.healToFull()
            return stats
        }

        /**
         * A player's combat state at the given Constitution level, everything
         * else left at level 1. Convenience for tools and tests.
         */
        fun forConstitution(level: Int, bonus: Int = 0): CombatStats {
            val stats = CombatStats(1, bonus)
            stats.setLevel(Stat.CONSTITUTION, level)
            stats.recalculateMaximum()
            stats.healToFull()
            return stats
        }
    }
}
