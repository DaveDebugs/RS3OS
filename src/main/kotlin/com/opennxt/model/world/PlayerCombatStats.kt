package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.levelForXp
import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatParams
import com.opennxt.resources.sqlite.ItemDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteItemCodec

/**
 * Derives a PLAYER's side of the combat formulas -- effective accuracy,
 * effective max hit, defence -- from skill levels and an equipped weapon.
 */
object PlayerCombatStats {

    /**
     * Ability damage granted per level of the damage-dealing skill, in the
     * x10 unit (2.5 damage per level, so 25 here).
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS. Source form: the
     * runescape.wiki "Ability damage" article's published RS3 rule that base
     * ability damage includes 2.5 x the level of the damage skill (Strength
     * for melee, Ranged for ranged, Magic for magic). No cache field relates
     * a skill level to a damage number.
     */
    const val ABILITY_DAMAGE_PER_LEVEL_X10 = 25

    /**
     * The RS3 tier/level rating function `f(x) = 0.0008x^3 + 4x + 40`.
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS as a *player* formula.
     * Source form: the runescape.wiki "Accuracy" / "Armour" articles, which
     * publish f(x) as the function behind RS3's tier-based accuracy, armour
     * and level ratings.
     *
     * What the cache CAN do is corroborate the function on the ITEM side:
     */
    fun tierFunction(x: Int): Double {
        val v = x.coerceAtLeast(1).toDouble()
        return 0.0008 * v * v * v + 4.0 * v + 40.0
    }

    /**
     * The level-derived accuracy/defence term: `floor(f(level))`.
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS. Source form: the
     * runescape.wiki "Accuracy" article's RS3 model, where an attacker's
     * accuracy is the sum of a level term f(level) and an equipment term.
 * The floor is this server's rounding choice (stated, not sourced);
     */
    fun levelAccuracyTerm(level: Int): Int = Math.floor(tierFunction(level)).toInt()

    /**
     * Effective accuracy: `floor(f(styleLevel)) + weapon accuracy`, on the
     * same tier-ladder scale as NPC param 29 / item param 3267, ready for
     * [com.opennxt.model.combat.CombatFormulas.hitChance].
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS. Source form: the
     * runescape.wiki "Accuracy" article (accuracy = level term + equipment
     * term). The weapon term itself is CACHE-grounded (param 3267); only the
     * level term and the sum are invented.
     *
     *  - [weapon] null is UNARMED: the documented unarmed baseline is a zero
     *    equipment term (an empty loadout shows no accuracy bonus), so the
     *    level term stands alone. Labelled here so the baseline is a choice
     *    with a name, not a silent zero.
     *  - A weapon that carries NO accuracy param returns null. Absent is not
     *    zero in this data (see [NpcCombatParams]); a stat the cache did not
     *    give the item does not become a default.
     *
     * [styleLevel] is the level of the skill governing the attack style
     * (Attack for melee, Ranged, Magic) -- [derive] resolves which.
     */
    fun effectiveAccuracy(styleLevel: Int, weapon: EquippedWeapon?): Int? {
        val levelTerm = levelAccuracyTerm(styleLevel)
        if (weapon == null) return levelTerm
        val weaponTerm = weapon.accuracy ?: return null
        return levelTerm + weaponTerm
    }

    /**
     * Effective max hit in the cache's x10 unit:
     * `weapon damage (param 641) + 25 x damage-skill level`.
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS. Source form: the
     * runescape.wiki "Ability damage" article (base ability damage = weapon
     * damage + 2.5 x skill level). The weapon term is CACHE-grounded (param
     * 641, x10); the 2.5-per-level term and the sum are not. Ability-specific
     * damage ranges, dual wielding and damage bonuses from armour are NOT
     * modelled -- one number stands for the whole loadout.
     *
     *  - [weapon] null is UNARMED: the documented unarmed baseline is a zero
     *    weapon-damage term, so the max hit is the level term alone
     *    (25 x level here = 2.5 x level in lifepoints).
     *  - A weapon with NO damage param returns null -- absent is not zero.
     */
    fun effectiveMaxHitX10(damageSkillLevel: Int, weapon: EquippedWeapon?): Int? {
        val levelTerm = ABILITY_DAMAGE_PER_LEVEL_X10 * damageSkillLevel.coerceAtLeast(1)
        if (weapon == null) return levelTerm
        val weaponTerm = weapon.damageX10 ?: return null
        return levelTerm + weaponTerm
    }

    /**
     * Defence rating: `floor(f(defenceLevel))`, the Defence term of
     * [com.opennxt.model.combat.CombatFormulas.hitChance] when this player
     * is the target.
     *
     * RECONSTRUCTED -- THE CACHE CANNOT VERIFY THIS. Source form: the
     * runescape.wiki "Armour" article (defence = level term + armour term).
     * Worn armour is NOT modelled here, so the armour term is absent by
     * construction -- an unarmoured player, stated, not a stat that went
     * missing.
     */
    fun defence(defenceLevel: Int): Int = levelAccuracyTerm(defenceLevel)

    /**
     * The player's COMBAT LEVEL, for the npc aggression rule.
     */
    fun combatLevel(
        attack: Int, strength: Int, defence: Int, constitution: Int, prayer: Int, summoning: Int,
        ranged: Int, magic: Int, necromancy: Int
    ): Int {
        val base = 0.25 * (defence + constitution + prayer / 2 + summoning / 2)
        val best = maxOf(attack + strength, 2 * ranged, 2 * magic, 2 * necromancy)
        return (base + 0.325 * best).toInt()
    }

    fun combatLevel(player: WorldPlayer): Int = combatLevel(
        player.level(Stat.ATTACK), player.level(Stat.STRENGTH), player.level(Stat.DEFENCE),
        player.level(Stat.CONSTITUTION), player.level(Stat.PRAYER), player.level(Stat.SUMMONING),
        player.level(Stat.RANGED), player.level(Stat.MAGIC), player.level(Stat.NECROMANCY)
    )

    /**
     * Derives the full stat block for one player: the style (and therefore
     * which levels feed which formula) is the weapon's own ([EquippedWeapon.style]:
     */
    fun derive(
        attackLevel: Int,
        strengthLevel: Int,
        magicLevel: Int,
        rangedLevel: Int,
        defenceLevel: Int,
        weapon: EquippedWeapon?
    ): PlayerDerivedStats {
        val defenceRating = defence(defenceLevel)
        if (weapon == null) {
            // Unarmed: the documented baseline, both terms computable always.
            return PlayerDerivedStats(
                effectiveAccuracy = effectiveAccuracy(attackLevel, null),
                effectiveMaxHitX10 = effectiveMaxHitX10(strengthLevel, null),
                defence = defenceRating,
                gaps = emptyList()
            )
        }
        val gaps = mutableListOf<String>()
        val skill = weapon.requirementSkill
 //: the style is the weapon's own (flag 2825/2826/2827, else the 749 skill).
        val style = weapon.style
        val (accuracyLevel, damageLevel) = when (style) {
            com.opennxt.model.combat.CombatStyle.MELEE -> attackLevel to strengthLevel
            com.opennxt.model.combat.CombatStyle.RANGED -> rangedLevel to rangedLevel
            com.opennxt.model.combat.CombatStyle.MAGIC -> magicLevel to magicLevel
            null -> {
                gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) has " +
                    "no style flag (2825/2826/2827) and " +
                    (if (skill == null) "no requirement-skill param (749)"
                     else "requirement skill $skill, not a combat style") +
                    " -- governing style unknown, and there is no default style"
                return PlayerDerivedStats(null, null, defenceRating, gaps)
            }
        }
        val accuracyParam = when (style) {
            com.opennxt.model.combat.CombatStyle.RANGED -> NpcCombatParams.ITEM_RANGED_ACCURACY
            com.opennxt.model.combat.CombatStyle.MAGIC -> NpcCombatParams.ITEM_MAGIC_ACCURACY
            else -> NpcCombatParams.ITEM_ACCURACY
        }
        val damageParam = when (style) {
            com.opennxt.model.combat.CombatStyle.RANGED -> NpcCombatParams.ITEM_RANGED_DAMAGE
            com.opennxt.model.combat.CombatStyle.MAGIC -> NpcCombatParams.ITEM_MAGIC_DAMAGE
            else -> NpcCombatParams.ITEM_DAMAGE
        }
        val accuracy = effectiveAccuracy(accuracyLevel, weapon)
        if (accuracy == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "carries no $style accuracy param ($accuracyParam); absent is not zero"
        val maxHitX10 = effectiveMaxHitX10(damageLevel, weapon)
        if (maxHitX10 == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "carries no $style damage param ($damageParam); absent is not zero" +
            (if (style == com.opennxt.model.combat.CombatStyle.MAGIC) " - magic auto-attack damage is the spell's, and there is no spell model yet" else "")
        val range = weapon.attackRange
        if (range == null) gaps += "weapon ${weapon.definition.id} (${weapon.definition.name}) " +
            "carries no attack-range param (13); absent is not zero"
        return PlayerDerivedStats(accuracy, maxHitX10, defenceRating, gaps, style, range)
    }

    /**
     * Level from total experience, via the cache's own xp tables.
     */
    fun levelFromXp(stat: Stat, xp: Int): Int = levelForXp(stat, xp)

    /**
     * Looks up an item id as an equippable weapon: the [ItemDefinition] via
     * the sqlite resource layer plus its `items_attr` param map (the same
     * decode verified item params
     * through).
     */
    fun lookupWeapon(itemId: Int): EquippedWeapon? {
 //: memoised - the engagement loop now reads the weapon every tick (range and
        // style), and item definitions are immutable at runtime (I-08). A miss is cached too.
        synchronized(weaponCache) {
            if (weaponCache.containsKey(itemId)) return weaponCache[itemId]
        }
        val definition = SqliteItemCodec.load(itemId)
        val weapon = if (definition == null) null else {
            val json = RsDatabase.queryOne(
                "SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", itemId
            ) { it.getString("value") }
            val params = json?.let { NpcCombat.parseParams(it) } ?: emptyMap()
            EquippedWeapon(definition, params)
        }
        synchronized(weaponCache) { weaponCache[itemId] = weapon }
        return weapon
    }

    private val weaponCache = HashMap<Int, EquippedWeapon?>()
}

/**
 * One equipped weapon: its definition plus its raw `items_attr` param map.
 */
data class EquippedWeapon(
    val definition: ItemDefinition,
    val params: Map<Int, Int>
) {
    /**
     * The weapon's combat style: the item's own style flag (params 2825 / 2826 /
     * 2827, see [NpcCombatParams.ITEM_STYLE_MELEE]), else the requirement skill (749) when it
     * names Attack/Strength, Ranged or Magic; null when neither says - and null is reported as
     * a gap by [PlayerCombatStats.derive], never defaulted to melee.
     */
    /* Precedence when an item carries two flags (5 items do: 24189 Moia's hands, 28737 Icyenic
     * staff, 28738 Infernal staff, 30345 Aviansie wand, 30346 Orkish wand - all 2825+2827):
     * RANGED, then MAGIC, then MELEE - so those five are MAGIC and, carrying no 965, are refused.
     * Stated and pinned rather than left to the order of a `when`. */
    val style: com.opennxt.model.combat.CombatStyle? get() = when {
        params[NpcCombatParams.ITEM_STYLE_RANGED] == 1 -> com.opennxt.model.combat.CombatStyle.RANGED
        params[NpcCombatParams.ITEM_STYLE_MAGIC] == 1 -> com.opennxt.model.combat.CombatStyle.MAGIC
        params[NpcCombatParams.ITEM_STYLE_MELEE] == 1 -> com.opennxt.model.combat.CombatStyle.MELEE
        else -> when (requirementSkill) {
            Stat.ATTACK.id, Stat.STRENGTH.id -> com.opennxt.model.combat.CombatStyle.MELEE
            Stat.RANGED.id -> com.opennxt.model.combat.CombatStyle.RANGED
            Stat.MAGIC.id -> com.opennxt.model.combat.CombatStyle.MAGIC
            else -> null
        }
    }

    /**
     * The tier-ladder accuracy rating for the weapon's OWN style: item param 3267 (melee),
     * 4 (ranged) or 3 (magic). CACHE-grounded; see [NpcCombatParams.ITEM_RANGED_ACCURACY].
     */
    val accuracy: Int? get() = when (style) {
        com.opennxt.model.combat.CombatStyle.RANGED -> params[NpcCombatParams.ITEM_RANGED_ACCURACY]
        com.opennxt.model.combat.CombatStyle.MAGIC -> params[NpcCombatParams.ITEM_MAGIC_ACCURACY]
        else -> params[NpcCombatParams.ITEM_ACCURACY]
    }

    /**
     * Max hit x10 for the weapon's own style: param 641 (melee), 643 (ranged) or 965 (magic,
     * which staves do not carry - magic damage is the spell's and is not modelled). CACHE-grounded.
     */
    val damageX10: Int? get() = when (style) {
        com.opennxt.model.combat.CombatStyle.RANGED -> params[NpcCombatParams.ITEM_RANGED_DAMAGE]
        com.opennxt.model.combat.CombatStyle.MAGIC -> params[NpcCombatParams.ITEM_MAGIC_DAMAGE]
        else -> params[NpcCombatParams.ITEM_DAMAGE]
    }

    /**
     * Attack range in tiles (Chebyshev): item param 13 when carried AND positive (a 0 is carried
     * by some melee items and means "no stated reach", not "cannot reach"); 1 (adjacent) for a
     * melee weapon without one; null for a ranged/magic weapon without one (a gap, not a default).
     */
    val attackRange: Int? get() = params[NpcCombatParams.ITEM_ATTACK_RANGE]?.takeIf { it > 0 }
        ?: if (style == com.opennxt.model.combat.CombatStyle.MELEE) 1 else null

    /** Item param 749, the requirement's skill id in [Stat] numbering. CACHE-grounded. */
    val requirementSkill: Int? get() = params[NpcCombatParams.ITEM_REQUIREMENT_SKILL]

    /** Item param 750, the requirement's level. CACHE-grounded. */
    val requirementLevel: Int? get() = params[NpcCombatParams.ITEM_REQUIREMENT_LEVEL]

    /**
     * Item param **14**: ticks between swings. CACHE-grounded, and this is the
     * strongest item-param reading in this file.
     */
    val attackSpeedTicks: Int? get() = params[NpcCombatParams.ATTACK_SPEED]

    /**
     * The cache's wear slot for this item (`items.equipSlotId`), or null when
     * the cache gives it none - which is 40,876 of the 60,617 items, so null
     * here means "not wearable" and never "slot 0". See
     * [com.opennxt.model.entity.player.PlayerInventory.wornInv].
     */
    val equipSlot: Int? get() = definition.equipSlotId

    override fun toString() =
        "EquippedWeapon(${definition.id} ${definition.name ?: "unnamed"}, " +
            "accuracy=${accuracy ?: "null"}, damageX10=${damageX10 ?: "null"}, " +
            "req=${requirementSkill ?: "null"}/${requirementLevel ?: "null"})"
}

/**
 * One player's derived stat block, from [PlayerCombatStats.derive].
 *
 * [effectiveAccuracy] and [effectiveMaxHitX10] are null exactly when the
 * equipped weapon's cache data cannot support them, and every null is
 * explained by an entry in [gaps] -- a caller printing the gaps shows WHY the
 * player cannot fight, instead of fighting with an invented number.
 * [effectiveMaxHitX10] is in the cache's max-hit-x10 unit; divide by 10 for
 * lifepoints, visibly, at the point of use.
 */
data class PlayerDerivedStats(
    val effectiveAccuracy: Int?,
    val effectiveMaxHitX10: Int?,
    val defence: Int,
    val gaps: List<String>,
    val style: com.opennxt.model.combat.CombatStyle = com.opennxt.model.combat.CombatStyle.MELEE,
    /** Tiles the player can swing from; 1 unarmed; null when a ranged/magic weapon carries no range param. */
    val attackRange: Int? = 1
)
