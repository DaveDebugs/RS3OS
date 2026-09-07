package com.opennxt.model.combat

import java.util.Random

/**
 * RECONSTRUCTED combat formulas for the RS3 (post-EoC) combat model.
 */
object CombatFormulas {

    /**
     * Chance for an attack to hit, in `0.0..1.0`.
     *
     * RECONSTRUCTED - NOT VERIFIABLE AGAINST THIS CACHE.
     *
     * This follows the runescape.wiki "Hit chance" article's published form of
     * the RS3 model:
     *
     * ```
     * Hit chance = Affinity x Accuracy / Defence
     * ```
     *
     * where Affinity is the defender's affinity value against the attack style
     * (a number the wiki documents on a 0..100 scale, so 55 with equal accuracy
     * and defence means a 55% hit chance), Accuracy is the attacker's tier-based
     * accuracy rating, and Defence is the defender's tier-based defence rating.
     */
    fun hitChance(attackerAccuracy: Int, defenderArmour: Int, defenderAffinity: Int): Double {
        val accuracy = attackerAccuracy.coerceAtLeast(0).toDouble()
        val affinity = defenderAffinity.coerceAtLeast(0).toDouble()
        if (accuracy == 0.0 || affinity == 0.0) return 0.0
        if (defenderArmour <= 0) return 1.0
        val raw = (affinity / 100.0) * (accuracy / defenderArmour.toDouble())
        return raw.coerceIn(0.0, 1.0)
    }

    /**
     * THE PLAYER'S AUTO-ATTACK FLOOR, a level-1
     * Attack/Strength account with a bronze dagger (loadout panel: Damage 41, Accuracy 194, 1.8 s)
     */
    const val PLAYER_AUTO_ATTACK_FLOOR_PERCENT: Int = 80

    /**
     * Rolls damage for a hit: uniform in `[floorPercent% of maxHit, maxHit]`, inclusive.
     * [floorPercent] defaults to the published 20; the player's auto-attack passes
     * [PLAYER_AUTO_ATTACK_FLOOR_PERCENT] (80, measured - see it).
     *
     * RECONSTRUCTED - NOT VERIFIABLE AGAINST THIS CACHE (the 20 default; the 80 is measured).
     *
     * The band follows the runescape.wiki "Damage" / auto-attack documentation
     * of the post-EoC model: abilities and auto-attacks in RS3 do not roll
     * uniformly from 0 the way pre-EoC (and OSRS) damage does; they roll within
     * a band whose floor is a fixed fraction of the maximum, and 20%..100% is
     * the published auto-attack band (ability damage ranges vary per ability
     * and are NOT modelled here - one band for everything is a simplification).
     *
     * [maxHit] is in whatever unit the caller uses. The cache's NPC damage
     * params (641/643/965) are max hits x10 - see [NpcCombatParams.MELEE_DAMAGE] -
     * and this function does not rescale them; it rolls in the unit it is given.
     *
     * The 20% floor is `maxHit * 20 / 100` in integer arithmetic (floor), so
     * `maxHit < 5` degenerates gracefully: floor 0, roll uniform in `0..maxHit`.
     * Non-positive [maxHit] rolls 0 - a hit for nothing, not an error, because
     * 37 real NPCs carry a damage param of 0 and a handful carry negative ones.
     *
     * [random] is injected so the check tool can pin the seed and make claims
     * about 10,000 rolls that anyone can replay.
     */
    fun damageRoll(maxHit: Int, random: Random, floorPercent: Int = 20): Int {
        if (maxHit <= 0) return 0
        val floor = maxHit * floorPercent.coerceIn(0, 100) / 100
        return floor + random.nextInt(maxHit - floor + 1)
    }

    /**
     * The delay between attacks, as a typed result rather than a bare number.
     *
     * The *reading* of param 14 as "game ticks between attacks" is no longer an
     * inference: enum 6741 maps 1..8 to "0.6s".."4.8s", so the cache itself
     * states both the unit and that a tick is 600ms. [AttackDelay.Ticks.millis]
     * therefore rests on a cache field now, not on outside engine knowledge.
     * See [NpcCombatParams.ATTACK_SPEED].
     *
     * The caveat from that same KDoc is enforced here rather than repeated and
     * ignored: a tail of param-14 values (15, 20, 24, 25, 30, 85, 500, 1000 -
     * 216 NPCs) does not fit the reading at all. A 1000-tick "attack speed" is a
     * 10-minute swing timer; whatever those encode, it is not this, and silently
     * returning them as delays would launder a failed inference into a
     * scheduling bug. Values outside [PLAUSIBLE_TICKS] come back as
     * [AttackDelay.Implausible], carrying the raw value, and the caller must
     * decide what to do - there is no default.
     */
    fun tickDelay(attackSpeedTicks: Int): AttackDelay =
        if (attackSpeedTicks in PLAUSIBLE_TICKS) AttackDelay.Ticks(attackSpeedTicks)
        else AttackDelay.Implausible(attackSpeedTicks)

    /**
     * The range of param-14 values this package is willing to read as an attack
     * interval.
     *
     * **This bound comes from the cache**, which is a change: it used to be an
     * AUTHORED 1.12, picked as "the observed plausible range with headroom".
     * Enum 6741 is the client's own param-14 -> display-string table and it has
     * exactly eight keys, 1 through 8. A value of 9 is not a slower attack, it
     * is a value the client cannot render, so the honest upper bound is 8.
     *
     * The narrowing moves 26 NPCs (one at 9, twenty-five at 10) from
     * [AttackDelay.Ticks] to [AttackDelay.Implausible]: 0.30% of the 8,715 that
     * carry the param, taking coverage from 97.46% to 97.17%. That is the point
     * - they were being read as swing timers on no evidence.
     */
    val PLAUSIBLE_TICKS = 1..8

    /** Milliseconds per game tick. Public engine knowledge, not a cache field. */
    const val TICK_MILLIS = 600

    /**
     * Convenience: one NPC attacking another, previewed entirely from
     * cache-grounded params run through the reconstructed formulas above.
     *
     * Returns null - never a defaulted preview - when anything needed is
     * missing:
     *
     * - either NPC does not exist
     * - the attacker's combat-class param is absent or unmapped (no style
     * means no way to pick which accuracy, damage and affinity apply)
     * - the attacker lacks the accuracy or damage param for its style
     * - the defender lacks armour, or the affinity slot for that style
     * - the attacker's param-14 value is outside [PLAUSIBLE_TICKS] (an
     * [AttackDelay.Implausible] speed must not silently become a delay)
     */
    fun npcVsNpcPreview(attackerGameId: Int, defenderGameId: Int): NpcVsNpcPreview? {
        val attacker = NpcCombat.load(attackerGameId) ?: return null
        val defender = NpcCombat.load(defenderGameId) ?: return null
        val style = attacker.combatStyle ?: return null
        val accuracy = attacker.accuracyFor(style) ?: return null
        val maxHitX10 = attacker.damageFor(style) ?: return null
        val armour = defender.armour ?: return null
        val affinity = defender.affinityFor(style) ?: return null
        val speed = when (val delay = tickDelay(attacker.attackSpeed ?: return null)) {
            is AttackDelay.Ticks -> delay
            is AttackDelay.Implausible -> return null
        }
        return NpcVsNpcPreview(
            attackerId = attacker.id,
            defenderId = defender.id,
            style = style,
            hitChance = hitChance(accuracy, armour, affinity),
            maxHitX10 = maxHitX10,
            attackSpeed = speed
        )
    }

    /**
     * The style [style] beats on the combat triangle: Magic > Melee > Ranged > Magic.
     * Public game knowledge, and it is what the affinity vector turns out to be
     * ordered by - see [affinitySlot].
     */
    fun beats(style: CombatStyle): CombatStyle = when (style) {
        CombatStyle.MAGIC -> CombatStyle.MELEE
        CombatStyle.MELEE -> CombatStyle.RANGED
        CombatStyle.RANGED -> CombatStyle.MAGIC
    }

    /**
     * Which of the four affinity slots ([NpcCombatParams.AFFINITY]) an attack of
     * [style] reads against a defender that is weak to [weakTo].
     */
    fun affinitySlot(style: CombatStyle, weakTo: CombatStyle): Int = when (style) {
        weakTo -> 1
        beats(weakTo) -> 2
        else -> 3
    }
}

/**
 * The result of reading a param-14 value as an attack delay.
 *
 * A sealed type instead of a nullable Int, so the implausible case carries the
 * evidence (the raw value) instead of dissolving into "null, for some reason",
 * and so that a `when` over it cannot forget the bad case exists.
 */
sealed class AttackDelay {
    /** A plausible attack speed. [ticks] is in game ticks, [millis] at 600ms per tick. */
    data class Ticks(val ticks: Int) : AttackDelay() {
        val millis: Int get() = ticks * CombatFormulas.TICK_MILLIS
    }

    /**
     * A param-14 value the attack-speed reading does not explain (the 24 / 85 /
     * 500 / 1000 tail measured in [NpcCombatParams.ATTACK_SPEED]). Not a delay.
     */
    data class Implausible(val rawValue: Int) : AttackDelay()
}

/**
 * One NPC's opening position against another, computed by
 * [CombatFormulas.npcVsNpcPreview]. Inputs are cache-grounded params; the
 * [hitChance] combining them is RECONSTRUCTED and inherits every caveat on
 * [CombatFormulas.hitChance]. [maxHitX10] is in the cache's x10 unit.
 */
data class NpcVsNpcPreview(
    val attackerId: Int,
    val defenderId: Int,
    val style: CombatStyle,
    val hitChance: Double,
    val maxHitX10: Int,
    val attackSpeed: AttackDelay.Ticks
)
