package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.levelForXp
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.Lifepoints
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.map.PathFinder
import java.util.EnumMap
import java.util.Random

/**
 * A player-shaped participant in world state WITHOUT a network client -- the
 * "connection that lands" while the wire protocol is out of scope.
 *
 * This is deliberately NOT [WorldPlayer]. `WorldPlayer` is a network object
 * (it owns a `ConnectedClient` and a packet loop) and nothing here touches
 * it. A HeadlessPlayer is a [PlayerEntity] (position + [com.opennxt.model.entity.movement.Movement]
 * over real collision) plus a [ContentPlayer] (the state content handlers see),
 * driven by ticks instead of packets. It can:
 *
 * - be placed on a tile ([placeAt]);
 * - path somewhere over real collision ([pathTo] via [PathFinder], consumed
 * one tick at a time by [tick] through the entity's Movement);
 * - invoke a content action on a loc through [ContentRegistry]
 * ([interactWithLoc]) -- dispatch validation and all;
 * - attack a [WorldNpc] each tick ([attack]) using the RECONSTRUCTED
 * [CombatFormulas], with the NPC side of the formula fed from its
 * cache-grounded params.
 */
class HeadlessPlayer private constructor(
    val name: String,
    start: TileLocation,
    /** Explicit RECONSTRUCTED accuracy stand-in, or null in derived mode. */
    private val explicitAccuracy: Int?,
    /** Explicit RECONSTRUCTED max-hit stand-in (lifepoints), or null in derived mode. */
    private val explicitMaxHit: Int?
) : CombatDefender {
    /**
     * Explicit-stand-in constructor: both player-side numbers fixed by the
     * caller. RECONSTRUCTED stand-ins, visible at the construction site --
     * see the class doc.
     */
    constructor(name: String, start: TileLocation, accuracy: Int, maxHit: Int) :
        this(name, start, explicitAccuracy = accuracy, explicitMaxHit = maxHit)

    /**
     * Derived-stats constructor: accuracy and max hit come from
     * [PlayerCombatStats] over [skills] and the equipped [weapon]. Starts at
     * level 1 everywhere, unarmed -- both states documented, neither silent.
     */
    constructor(name: String, start: TileLocation) :
        this(name, start, explicitAccuracy = null, explicitMaxHit = null)

    /** True when this player's combat numbers are derived, not explicit stand-ins. */
    val derivesStats: Boolean get() = explicitAccuracy == null

    /**
     * Skill levels for the derived path. A stat absent from the map is level
     * 1 -- the floor the cache's own xp tables define (0 xp is level 1, see
     * [com.opennxt.api.stat.levelForXp]), which is a fresh account, not a
     * silent default. Use [setLevel] / [level]; XP-based callers can go
     * through [PlayerCombatStats.levelFromXp] (CACHE-grounded curve).
     */
    val skills: MutableMap<Stat, Int> = EnumMap(Stat::class.java)

    /** The level of [stat]: the map's entry, or the level-1 floor. */
    override fun level(stat: Stat): Int = skills[stat] ?: 1

    fun setLevel(stat: Stat, level: Int) {
        require(level >= 1) { "there is no level below 1 (the xp tables' own floor); got $level" }
        skills[stat] = level
    }

    // ------------------------------------------------------------------ xp

    /**
     * Experience per stat, in TENTHS of an experience point.
     *
     * Tenths, because the published RS3 combat rate ([COMBAT_XP_TENTHS_PER_DAMAGE])
     * is 0.4 xp per lifepoint of damage -- not an integer in whole xp -- and
     * storing rounded whole xp per hit would silently leak fractions the
     * published rate says the player earned. RS's own engine tracks xp in
     * tenths (the client displays the floor), so this is the game's own unit,
     * not an invention. [xp] exposes the whole-xp view the curve tables use.
     */
    val xpTenths: MutableMap<Stat, Int> = EnumMap(Stat::class.java)

    /** Whole experience points in [stat]: the tenths floor-divided by 10 -- the value the curve tables index by. */
    fun xp(stat: Stat): Int = (xpTenths[stat] ?: 0) / 10

    /** Every level-up this player has experienced, in order. Appended to by [grantCombatXp]. */
    val levelUps = ArrayList<LevelUp>()

    /** Every death this player has experienced, in order. Appended to by [takeDamage]. */
    val deaths = ArrayList<PlayerDeath>()

    /** The most recent [grantCombatXp] award, for callers observing attack side effects. */
    var lastXpAward: CombatXpAward? = null
        private set

    /**
     * Awards combat experience for [damage] lifepoints of damage dealt in
     * [style], at the published RS3 rates:
     *
     *  - **style skill**: 4 xp per 10 damage ([COMBAT_XP_TENTHS_PER_DAMAGE],
     *    RECONSTRUCTED -- see its declaration for the unit conversion);
     *  - **Constitution**: 1.33 xp per 10 damage
     *    ([CONSTITUTION_XP_TENTHS_PER_100_DAMAGE], same status).
     *
     * The style skill is the style's ACCURACY skill (melee -> Attack,
     * ranged -> Ranged, magic -> Magic). That mapping is a RECONSTRUCTED
     * simplification: real RS3 splits melee xp by the chosen combat mode
     * (Attack / Strength / Defence / Balanced), and combat modes are not
     * modelled here -- one governing skill per style, stated, is what this
     * takes. [damage] must already be capped at the target's remaining
     * lifepoints (overkill earns nothing; [attack] does the capping).
     *
     * Zero damage grants zero xp -- there is no participation award; the
     * check tool's "XP appears without damage" control leans on exactly this.
     *
     * Levels: xp flows into [skills] through the cache-verified curve
     * ([levelForXp]); each threshold crossed emits a [LevelUp] (also appended
     * to [levelUps]). A level in [skills] set higher than the xp supports
     * (via [setLevel]) is never lowered -- xp lifts levels, it does not
     * confiscate them. When the stat tables are not loaded (Stat.reload has
     * not run -- check tools that never touch xp don't load them), the tenths
     * still accumulate but levels cannot be recomputed; that is reported as
     * [CombatXpAward.levelsNotComputed], typed, instead of throwing or
     * silently guessing a curve.
     */
    /** Exact tenths per stat, carried so that awards below a tenth (1 damage at 0.05 xp = half a tenth) are not lost. */
    private val xpExactTenths: MutableMap<Stat, Double> = EnumMap(Stat::class.java)

    /**
     * The kill award for THIS player's share of [death]'s ledger: the block
     * for the damage they dealt, split over [CombatXp.selectedSkills] of the style they used, plus
     * Constitution's 0.33. Returns the award as tenths per stat; nothing when the ledger has no
     * entry for this name.
     */
    fun grantKillXp(death: DeathResult, npcId: Int?): Map<Stat, Int> {
        val damage = death.damageLedger[name] ?: return emptyMap()
        val style = death.styleLedger[name] ?: CombatStyle.MELEE
        val out = LinkedHashMap<Stat, Int>()
        for ((stat, xp) in com.opennxt.model.combat.CombatXp.splitAward(npcId, damage, style)) {
            val before = xpTenths[stat] ?: 0
            val exact = (xpExactTenths[stat] ?: before.toDouble()) + xp * 10.0
            xpExactTenths[stat] = exact
            val after = Math.floor(exact + 1e-9).toInt()
            xpTenths[stat] = after
            out[stat] = after - before
            if (stat.loaded) {
                val old = level(stat)
                val fromXp = levelForXp(stat, xp(stat))
                if (fromXp > old) { skills[stat] = fromXp; levelUps += LevelUp(stat, old, fromXp) }
            }
        }
        lastKillXp = out
        return out
    }
    var lastKillXp: Map<Stat, Int> = emptyMap()
        private set

    /**
     * A PER-HIT award helper. Since the server and [attack] pay on the kill
     * ([grantKillXp]); this stays as the direct rate probe the checks use (10 damage -> 5 tenths
     * to the style stat, 1 tenth to Constitution, etc.) and pays the whole block to ONE style stat.
     */
    fun grantCombatXp(style: CombatStyle, damage: Int, npcId: Int? = null): CombatXpAward {
        if (damage <= 0) {
            // No damage, no xp -- and no event pretending otherwise.
            return CombatXpAward(style, 0, 0, 0, emptyList(), null).also { lastXpAward = it }
        }
        val styleStat = when (style) {
            CombatStyle.MELEE -> Stat.ATTACK
            CombatStyle.RANGED -> Stat.RANGED
            CombatStyle.MAGIC -> Stat.MAGIC
        }
 //: the rate is CombatXp's - the wiki's per-npc experience / lifepoints,
        // else its 0.05 base - and Constitution takes a third. Awards are carried in exact
        // tenths and the stored integer tenths are the floor, so nothing is lost or invented.
        val styleXp = com.opennxt.model.combat.CombatXp.styleXp(npcId, damage)
        val constitutionXp = com.opennxt.model.combat.CombatXp.constitutionXp(styleXp)
        fun award(stat: Stat, xp: Double): Int {
            val before = xpTenths[stat] ?: 0
            val exact = (xpExactTenths[stat] ?: before.toDouble()) + xp * 10.0
            xpExactTenths[stat] = exact
            val after = Math.floor(exact + 1e-9).toInt()
            xpTenths[stat] = after
            return after - before
        }
        val styleTenths = award(styleStat, styleXp)
        val constitutionTenths = award(Stat.CONSTITUTION, constitutionXp)

        val ups = ArrayList<LevelUp>()
        var notComputed: String? = null
        for ((stat, tenths) in listOf(styleStat to styleTenths, Stat.CONSTITUTION to constitutionTenths)) {
            if (!stat.loaded) {
                notComputed = "Stat.$stat has no experience table (Stat.reload has not run) -- " +
                    "xp accumulated, levels not recomputed"
                continue
            }
            val old = level(stat)
            val fromXp = levelForXp(stat, xp(stat))
            if (fromXp > old) {
                skills[stat] = fromXp
                val up = LevelUp(stat, old, fromXp)
                ups += up
                levelUps += up
            }
        }
        return CombatXpAward(style, damage, styleTenths, constitutionTenths, ups, notComputed)
            .also { lastXpAward = it }
    }

    // ------------------------------------------------------------ lifepoints

    /**
     * Maximum lifepoints: Constitution level x [Lifepoints.PER_CONSTITUTION_LEVEL]
     * -- the documented public relation, via the constant that already carries
     * its citation and its RECONSTRUCTED status. No flat bonuses (no worn
     * items are modelled anywhere in this class).
     */
    override val maxLifepoints: Int get() = Lifepoints.forConstitutionLevel(level(Stat.CONSTITUTION))

    /**
     * Current lifepoints. Starts full; [takeDamage] subtracts, death restores
     * to full. A Constitution level-up raises [maxLifepoints] without
     * touching this (levelling does not heal -- keeping the two independent
     * is simpler and never invents health).
     */
    override var currentLifepoints: Int = Lifepoints.forConstitutionLevel(1)
        private set

    /**
     * Applies [amount] lifepoints of damage to this player.
     *
     * Returns null while the player survives. At 0 LP the player dies: they
     * respawn at Lumbridge ([LUMBRIDGE_RESPAWN_X]/[LUMBRIDGE_RESPAWN_Y] --
     * RECONSTRUCTED, see the constants) with [currentLifepoints] restored to
     * [maxLifepoints] and EVERY skill and xp total untouched (RS3 death does
     * not drain stats), and the typed [PlayerDeath] event is returned and
     * appended to [deaths]. Nothing else observes the death unless it looks.
     */
    override fun takeDamage(amount: Int): PlayerDeath? {
        if (amount <= 0) return null
        currentLifepoints = (currentLifepoints - amount).coerceAtLeast(0)
        if (currentLifepoints > 0) return null

        val diedAt = TileLocation(location.x, location.y, location.plane)
        placeAt(LUMBRIDGE_RESPAWN_X, LUMBRIDGE_RESPAWN_Y, LUMBRIDGE_RESPAWN_PLANE)
        currentLifepoints = maxLifepoints
        val death = PlayerDeath(
            player = name,
            diedAt = diedAt,
            respawnedAt = TileLocation(LUMBRIDGE_RESPAWN_X, LUMBRIDGE_RESPAWN_Y, LUMBRIDGE_RESPAWN_PLANE),
            lifepointsRestored = currentLifepoints
        )
        deaths += death
        return death
    }

    /**
     * Refills [currentLifepoints] to [maxLifepoints]. For callers that raise
     * Constitution via [setLevel] and want the health to match -- levelling
     * itself deliberately does not heal (see [currentLifepoints]).
     */
    fun restoreLifepoints() {
        currentLifepoints = maxLifepoints
    }

    companion object {
        /**
         * Combat xp per lifepoint of damage dealt, in TENTHS of an xp point.
         *
         * RECONSTRUCTED -- NOT IN THE CACHE. Source form: the runescape.wiki
         * "Combat experience" documentation of the RS3 rate, published as
         * **4 xp per 10 damage**. RS documentation usually quotes damage in
         * the x10 unit the cache's damage params use (a "150" chicken max hit
         * is 15 lifepoints -- see NpcCombatParams.MELEE_DAMAGE), so "10
         * damage" there is 1 lifepoint here, giving 0.4 xp per lifepoint of
         * damage-x1. In tenths of xp that is EXACTLY 4 per lifepoint -- this
         * constant -- with no rounding at all. The unit conversions, spelled
         * out once:
         *
         *     damage: cache x10 unit / 10  ->  lifepoints (damage-x1)
         *     xp:     4 tenths per lifepoint = 0.4 xp per lifepoint
         *                                    = "4 xp per 10 damage(x10)"
         */
        @Deprecated("Superseded: this applied the RS2 hitpoint rate to lifepoints, 8x too high. Use CombatXp.")
        const val COMBAT_XP_TENTHS_PER_DAMAGE = 4

        /**
         * Constitution xp per 100 lifepoints of damage dealt, in tenths.
         *
         * RECONSTRUCTED -- NOT IN THE CACHE. Source form: the same public
         * documentation's Constitution rate, **1.33 xp per 10 damage** --
         * exactly one third of the 4-per-10 style rate, in the same x10
         * damage unit. Per lifepoint that is 1.33 tenths, which is not an
         * integer, so it is held as 133 tenths per 100 lifepoints and
         * applied with integer floor division (`damage * 133 / 100`). The
 * floor is this server's rounding choice, stated: how Jagex's
         * servers rounded the odd hundredth was never published, and a floor
         * never awards xp the rate does not cover.
         */
        @Deprecated("Superseded with COMBAT_XP_TENTHS_PER_DAMAGE; Constitution is CombatXp.CONSTITUTION_SHARE of the style xp.")
        const val CONSTITUTION_XP_TENTHS_PER_100_DAMAGE = 133

        /**
         * The Lumbridge respawn tile: (3222, 3222, 0).
         *
         * RECONSTRUCTED. That players respawn in Lumbridge by default is
         * public knowledge; the exact tile is not in the cache. This is the
         * SAME tile this codebase already places fresh logins on
         * ([com.opennxt.net.login.LoginServerDecoder] constructs its
         * PlayerEntity at TileLocation(3222, 3222, 0)) -- reused rather than
         * re-invented, so "where players appear" is one fact with two
         * readers, not two facts.
         */
        const val LUMBRIDGE_RESPAWN_X = 3222
        const val LUMBRIDGE_RESPAWN_Y = 3222
        const val LUMBRIDGE_RESPAWN_PLANE = 0
    }

    /**
     * The equipped weapon, or null while unarmed. Only [equip] writes it, so
     * every change to it is paired with an update to [unarmedReason].
     */
    var weapon: EquippedWeapon? = null
        private set

    /**
     * Why this player is unarmed, whenever [weapon] is null. An unknown item
     * id passed to [equip] lands here by id, so falling back to the unarmed
     * baseline is REPORTED, never silent.
     */
    var unarmedReason: String = "nothing equipped yet -- unarmed baseline"
        private set

    /**
     * Equips [itemId] via [PlayerCombatStats.lookupWeapon]: the item
     * definition from the sqlite resource layer plus its cache param map.
     *
     * An id with no item row returns null AND leaves the player unarmed with
     * [unarmedReason] naming the failed id -- there is no default weapon, and
     * the fallback is visible, not silent. An id that exists always equips,
     * even with missing combat params; the gaps surface later as a typed
     * [AttackOutcome.NotComputable], not as zeros.
     */
    fun equip(itemId: Int): EquippedWeapon? {
        val looked = PlayerCombatStats.lookupWeapon(itemId)
        weapon = looked
        unarmedReason = if (looked == null)
            "equip($itemId): no item row for that id -- FELL BACK TO UNARMED (no default weapon)"
        else "not unarmed: ${looked.definition.name ?: "item"} (${looked.definition.id}) is equipped"
        return looked
    }

    /**
     * The full derived stat block for this player's current [skills] and
     * [weapon], straight from [PlayerCombatStats.derive]. Meaningful in both
     * modes (explicit-mode players still have levels and a weapon slot), but
     * only the derived mode's [accuracy]/[maxHit] read from it.
     */
    fun derivedStats(): PlayerDerivedStats = PlayerCombatStats.derive(
        attackLevel = level(Stat.ATTACK),
        strengthLevel = level(Stat.STRENGTH),
        magicLevel = level(Stat.MAGIC),
        rangedLevel = level(Stat.RANGED),
        defenceLevel = level(Stat.DEFENCE),
        weapon = weapon
    )

    /**
     * Player accuracy on the tier-ladder scale: the explicit stand-in, or
     * [PlayerDerivedStats.effectiveAccuracy] in derived mode -- null there
     * exactly when the weapon's cache data cannot support it (the reason is
     * in [derivedStats]' gaps).
     */
    val accuracy: Int?
        get() = explicitAccuracy ?: derivedStats().effectiveAccuracy

    /**
     * Player max hit in LIFEPOINTS: the explicit stand-in, or the derived
     * [PlayerDerivedStats.effectiveMaxHitX10] divided by 10 -- the same x10
     * relationship the cache's NPC damage params carry (param 641 = max hit
     * x10, see [com.opennxt.model.combat.NpcCombatParams.MELEE_DAMAGE]). The
     * division is done here, visibly, because [attack] rolls damage against
     * lifepoint totals. Null in derived mode when underivable.
     */
    val maxHit: Int?
        get() = explicitMaxHit ?: derivedStats().effectiveMaxHitX10?.div(10)

    val entity = PlayerEntity(TileLocation(start.x, start.y, start.plane))

    /** The state content handlers are shown; synced with [entity] around each dispatch. */
    val contentPlayer = ContentPlayer(name, TileLocation(start.x, start.y, start.plane))

    val location: TileLocation get() = entity.location
    val isMoving: Boolean get() = entity.movement.hasSteps

    init {
        entity.movement.speed = MovementSpeed.WALK
    }

    /** Puts the player on a tile, dropping any queued path. */
    fun placeAt(x: Int, y: Int, plane: Int = 0) {
        entity.movement.reset()
        entity.location = TileLocation(x, y, plane)
        entity.previousLocation = entity.location
    }

    /**
     * Paths to (x, z) over real collision and queues the route. [search] is
     * the PathFinder window ([PathFinder.SEARCH] = 128 is RS's own refusal
     * window; a larger value is a tool convenience, not an RS behaviour).
     *
     * Returns the number of steps queued, or -1 when the pathfinder refuses
     * (destination outside the window, or no route). Steps are queued through
     * Movement.addStep, so each is re-validated against collision, and the
     * walk is consumed by [tick] at one tile per tick (WALK speed).
     *
     * Both ends of the route are made solid before the search
     * ([Movement.clipPathEnds]). This function used to path against whatever the
     * once-per-tick loc-clipping drip feed had reached, which is how a route into
     * Fred's farmhouse came back going straight through its south wall.
     */
    /** The last route's final tile, for callers that verify arrival exactly. */
    var lastPathTerminus: PathFinder.Step? = null
        private set

    fun pathTo(x: Int, z: Int, search: Int = PathFinder.SEARCH): Int {
        val loc = entity.location
        // Same fix, same reason, as Movement.walkTo: without it this searches a
        // collision map the once-per-tick LocClipping drip feed has not reached and
        // routes through walls map_loc states. Measured and controlled at
        // [Movement.clipEndsBeforePathing]; reproduced by
 // section I.
        Movement.clipPathEnds(loc.x, loc.y, x, z, loc.plane)
        val path = PathFinder.find(loc.x, loc.y, x, z, loc.plane, search) ?: return -1
        lastPathTerminus = path.last()
        entity.movement.reset()
        var queued = 0
        for (step in path.drop(1)) {
            if (!entity.movement.addStep(step.x, step.z)) break
            queued++
        }
        return queued
    }

    /** One world tick: consume queued movement. */
    fun tick() {
        entity.movement.process()
    }

    /**
     * Invokes a content action on a loc through [ContentRegistry] -- the same
     * dispatch (definition-validated, anti-client-trust) a packet handler
     * will eventually call. The [contentPlayer]'s location is synced from the
     * entity before dispatch and back after, so a handler that moves the
     * player (ladders, stairs) moves this player.
     */
    fun interactWithLoc(locId: Int, action: String, x: Int, z: Int, plane: Int = location.plane): DispatchResult {
        contentPlayer.location = TileLocation(entity.location.x, entity.location.y, entity.location.plane)
        val result = ContentRegistry.dispatchLoc(contentPlayer, locId, action, x, z, plane)
        entity.movement.reset()
        entity.location = TileLocation(
            contentPlayer.location.x, contentPlayer.location.y, contentPlayer.location.plane
        )
        entity.previousLocation = entity.location
        return result
    }

    /**
     * One tick's attack on [npc], resolved through the RECONSTRUCTED
     * [CombatFormulas]:
     *
     *  - hit chance = [CombatFormulas.hitChance] ([accuracy] -- explicit
     *    stand-in or [PlayerCombatStats]-derived -- vs the NPC's
     *    cache-grounded armour (param 2865) and melee affinity slot);
     *  - damage = [CombatFormulas.damageRoll] over [maxHit] (same two
     *    sources);
     *  - death and drops via [WorldNpcs.applyDamage].
     *
     * Every input that would have to be invented instead comes back as a
     * typed [AttackOutcome.NotComputable] naming the missing piece -- in
     * derived mode that includes a player-side stat the equipped weapon's
     * cache data cannot support (the gap list from [derivedStats] rides
     * along). All randomness comes from [random], so a fight replays from a
     * seed.
     *
     * Side effects of a landed hit:
     *
     *  - **xp** via [grantCombatXp] (melee style -- this method models melee
     *    only, see the affinity slot above) for the damage APPLIED, capped at
     *    the target's remaining lifepoints: overkill earns nothing. The award
     *    (with any [LevelUp]s) is observable at [lastXpAward] / [levelUps].
     *  - **ground items** when [groundItems] is passed and the hit kills:
     *    the death's resolvable drops land on the death tile, owned by this
     *    player, via the [WorldNpcs.applyDamage] overload; the spawn result
     *    rides on [DeathResult.groundSpawn]. Passing null (the default, and
     *    what existing callers do) grounds nothing.
     */
    fun attack(
        npc: WorldNpc,
        npcs: WorldNpcs,
        random: Random,
        groundItems: GroundItems? = null
    ): AttackOutcome {
        val accuracy = this.accuracy ?: return AttackOutcome.NotComputable(
            "player $name has no derivable accuracy: ${derivedStats().gaps.joinToString("; ")}"
        )
        val maxHit = this.maxHit ?: return AttackOutcome.NotComputable(
            "player $name has no derivable max hit: ${derivedStats().gaps.joinToString("; ")}"
        )
        if (!npc.alive) return AttackOutcome.NotComputable("target is dead (respawns in ${npc.respawnTicksRemaining} ticks)")
        if (npc.lifepoints == null) return AttackOutcome.NotComputable(
            "target ${npc.gameId} has null seeded lifepoints -- nothing to damage, no default"
        )
        val combat = npc.combat ?: return AttackOutcome.NotComputable(
            "no combat definition for game id ${npc.gameId}"
        )
        val armour = combat.armour ?: return AttackOutcome.NotComputable(
            "target ${npc.gameId} carries no armour param (2865); absent is not zero"
        )
 //: relative slot order via affinityFor (see CombatFormulas.affinitySlot).
        val affinity = combat.affinityFor(CombatStyle.MELEE)
            ?: return AttackOutcome.NotComputable(
                "target ${npc.gameId} has no usable melee affinity: weak style unknown and the style slots " +
                    "disagree, or the slot param is absent; absent is not zero"
            )

        val chance = CombatFormulas.hitChance(accuracy, armour, affinity)
        if (random.nextDouble() >= chance) return AttackOutcome.Missed(chance)

        val damage = CombatFormulas.damageRoll(maxHit, random, CombatFormulas.PLAYER_AUTO_ATTACK_FLOOR_PERCENT) // 80%..100%
        // XP is for damage APPLIED: the roll capped at the target's remaining
        // lifepoints. Overkill damage was never dealt and earns nothing.
        val applied = minOf(damage, npc.currentLifepoints!!)
        val death = npcs.applyDamage(npc, damage, random, groundItems, owner = name, style = CombatStyle.MELEE)
 //xp on the KILL for this player's own share of the ledger, not per hit.
        if (death != null) grantKillXp(death, npc.gameId)
        return if (death != null) AttackOutcome.Killed(damage, death)
        else AttackOutcome.Hit(damage, npc.currentLifepoints!!)
    }

    override fun toString() =
        "HeadlessPlayer($name at ${location.x},${location.y},${location.plane}, " +
            if (derivesStats)
                "accuracy=${accuracy ?: "null"} maxHit=${maxHit ?: "null"} " +
                    "[DERIVED via PlayerCombatStats; weapon=${weapon?.definition?.name ?: "UNARMED: $unarmedReason"}])"
            else "accuracy=$accuracy maxHit=$maxHit [RECONSTRUCTED stand-ins])"
}

/**
 * What one tick's attack did, as a value. [NotComputable] carries the exact
 * missing input instead of defaulting it -- an attack that cannot be computed
 * from real data is a reportable outcome, not a zero.
 */
sealed class AttackOutcome {
    data class Missed(val hitChance: Double) : AttackOutcome()
    data class Hit(val damage: Int, val targetLifepointsLeft: Int) : AttackOutcome()
    data class Killed(val damage: Int, val death: DeathResult) : AttackOutcome()
    data class NotComputable(val reason: String) : AttackOutcome()
}

/**
 * One observed level-up: [stat] rose from [oldLevel] to [newLevel] because
 * accumulated xp crossed the cache-verified curve's threshold
 * ([com.opennxt.api.stat.xpForLevel]). Emitted by
 * [HeadlessPlayer.grantCombatXp] and collected on [HeadlessPlayer.levelUps].
 */
data class LevelUp(val stat: Stat, val oldLevel: Int, val newLevel: Int) {
    override fun toString() = "LevelUp($stat $oldLevel -> $newLevel)"
}

/**
 * One combat xp award, all amounts in TENTHS of xp (see
 * [HeadlessPlayer.xpTenths] for why tenths).
 *
 * [levelsNotComputed] is non-null when the xp accumulated but levels could
 * not be recomputed because the stat tables are not loaded -- a typed report,
 * not a throw and not a silently guessed curve.
 */
data class CombatXpAward(
    val style: CombatStyle,
    /** Lifepoints of damage the award was for (already capped at the target's remaining LP). */
    val damage: Int,
    val styleXpTenths: Int,
    val constitutionXpTenths: Int,
    val levelUps: List<LevelUp>,
    val levelsNotComputed: String?
)

/**
 * One player death: dropped to 0 lifepoints at [diedAt], respawned at
 * [respawnedAt] (Lumbridge -- see [HeadlessPlayer.LUMBRIDGE_RESPAWN_X]) with
 * [lifepointsRestored] LP and every skill and xp total intact. Emitted by
 * [HeadlessPlayer.takeDamage] and collected on [HeadlessPlayer.deaths].
 */
data class PlayerDeath(
    val player: String,
    val diedAt: TileLocation,
    val respawnedAt: TileLocation,
    val lifepointsRestored: Int
)

/**
 * The DEFENDING side of a fight: what [NpcRetaliation] needs to hit somebody.
 *
 * ## Why this exists (and why it is four members and not a player model)
 *
 * [NpcRetaliation] was written against [HeadlessPlayer] and could therefore only
 * ever hit a player that exists inside a check. [WorldPlayer] - the one a real
 * socket produces - had no lifepoints at all, so a live npc could not fight
 * back. The two ways to fix that were to widen the retaliation call to whatever
 * both players can offer, or to write a second retaliation for the live path.
 * The second one is how a codebase ends up with two combat models that disagree,
 * which this server has already had to retract once (see
 * [com.opennxt.model.combat.PlayerCombat.XP_TENTHS_PER_DAMAGE], where two xp
 * rates in one tree differed by a factor of ten).
 *
 * So this is the FIRST option, and it is deliberately the smallest surface that
 * makes retaliation work: the defence level the hit chance reads, the two
 * lifepoint numbers, and the damage application that owns death. Nothing about
 * attacking, equipment, xp or position is here - a defender does not need them,
 * and putting them here would make this an interface about players rather than
 * about being hit.
 *
 * Both implementations reconstruct the maximum the same way, through
 * [Lifepoints.PER_CONSTITUTION_LEVEL], and both respawn at the same
 * RECONSTRUCTED Lumbridge tile. That is the point: one reconstruction, two
 * readers.
 */
interface CombatDefender {
    /** The defender's level in [stat]; [Stat.DEFENCE] is the one retaliation reads. */
    fun level(stat: Stat): Int

    /** Lifepoints remaining. */
    val currentLifepoints: Int

    /** Lifepoints at full health. */
    val maxLifepoints: Int

    /**
     * Applies [amount] lifepoints of damage; null while the defender survives,
     * a typed [PlayerDeath] at 0 (which the implementation is also responsible
     * for acting on - respawn, restore).
     */
    fun takeDamage(amount: Int): PlayerDeath?
}
