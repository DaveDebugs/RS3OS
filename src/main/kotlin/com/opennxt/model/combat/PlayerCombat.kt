package com.opennxt.model.combat

import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.world.DeathResult
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging
import java.util.IdentityHashMap
import java.util.Random

/**
 * The player -> npc attack loop: WIRING over parts that already existed.
 */
object PlayerCombat {
    private val logger = KotlinLogging.logger { }

    // ------------------------------------------------------------- gates

    /**
     * `-Dopennxt.experiment.combat=true`. Default OFF.
     */
    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat") == "true"

    /** `-Dopennxt.experiment.combat.face=false` drops both face-entity blocks. */
    val faceEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.face") != "false"

    /**
     * The hit splat's `type` field: [DEFAULT_HITMARK] unless the operator names
     * an id, or `none` to send no splat at all.
     */
    val splatType: Int?
        get() {
            val raw = System.getProperty("opennxt.experiment.combat.splattype")?.trim()
            if (raw.isNullOrEmpty()) return DEFAULT_HITMARK
            if (raw.equals("none", ignoreCase = true)) return null
            return raw.toIntOrNull() ?: DEFAULT_HITMARK
        }

    /**
     * The hitmark definition id a landed hit uses when the operator names none.
     */
    const val DEFAULT_HITMARK = 0

    /**
     * The provenance of [DEFAULT_HITMARK] as a runtime string, so a log line
     * cannot claim more than the cache supports. Asserted by
     * .
     */
    const val HITMARK_PROVENANCE =
        "GROUNDED existence + confirmed rendering + AUTHORED style: hitmark definition $DEFAULT_HITMARK " +
            "is one of 527 records in cache index 2 archive 46 (ids 0..526, contiguous) and carries the " +
            "numeric text template \"%1\". the real NXT 949 client: the operator " +
            "fought chickens on run server-20260817-190755 (npc.HITS=23 blocks on the wire) and REPORTED " +
            "SEEING DAMAGE NUMBERS ON SCREEN, so this record does render as a damage splat and the HITS block " +
            "field order and widths are correct end to end. What is still OURS is the STYLE: whether id " +
            "$DEFAULT_HITMARK is the same record Jagex uses for melee damage dealt (vs another of the 16 " +
            "%1-records - a different colour or icon) is not established, and would be settled by comparing " +
            "against a test run (rs3.ps1 -Task live). The default was null previously, which is why no " +
            "splat had ever reached the wire."

    // ------------------------------------------------------------- numbers

    /**
     * Damage applied per landed hit.
     *
     * **AUTHORED. NOT DERIVED FROM A FORMULA.** No combat formula is in this
     * cache and this server has no equipment model, so no roll produces this
     * number. What has changed since it was 1 is not its status - it is still
     * ours - but that it is now **BOUNDED BY A MEASUREMENT** and that the
     * measurement is re-run by a check rather than described in a comment.
     *
     * The bound: item **1277 (Bronze sword)**, the tier-1 melee weapon, carries
     * cache param **641 = 480**. Param 641 is a max hit x10 on both npcs and
     * items - that scaling is [NpcCombatParams.MELEE_DAMAGE]'s own reading and
     * is what [com.opennxt.model.world.NpcRetaliation] already divides by 10 to
     * get lifepoints - so the weakest real melee weapon in the cache implies a
     * max hit of **48 lifepoints**. This constant is that number.
     *
     * So the claim is exactly: *a flat 48 per hit is no larger than the max hit
     * the cache's own tier-1 weapon carries.* It is NOT "the damage a player
     * would do": a real roll is a distribution under a max, this is the max
     * applied every time, and no accuracy check is made at all.
     */
    const val DEMO_HIT = 48

    /**
     * The item whose cache param bounds [DEMO_HIT], and that param's value.
     * Named constants so the check compares against the same two numbers this
     * KDoc claims, and a change to one that is not a change to the other fails.
     */
    const val BOUNDING_WEAPON_ITEM = 1277
    const val BOUNDING_WEAPON_PARAM_641 = 480

    /**
     * Ticks between the player's hits.
     *
     * **INVENTED for the player side.** The npc side of this quantity IS in the
     * cache - param 14, in game ticks, with enum 6741 stating the unit outright
     * (see [NpcCombatParams.ATTACK_SPEED]) - but that is the NPC's swing timer,
     * not a player's. A player's comes from the equipped weapon, and this
     * server has no equipment model, so there is nothing to read.
     *
     * 4 is not arbitrary in magnitude, and that is the most that can be claimed
     * for it: 4 is the modal param-14 value over the npc population, carried by
     * **5,096 of the 8,718 npcs** that have the param (58.5%; 97.17% of those
     * npcs' values fall in the 1..8 range enum 6741 can render). Re-measurable
     * against `npcs_attr`. So the number is a plausible RS attack interval
     * borrowed from the npcs - it is NOT a measurement of a player's, and it
     * must not be read as one.
     */
    const val ATTACK_INTERVAL_TICKS = 4

    /**
     * Chebyshev tile distance at which the player may hit.
     *
     * RECONSTRUCTED simplification, and the same one [GroundItems.PICKUP_RANGE]
     * makes for the same reason: real RS resolves melee reach against both
     * entities' sizes and their exact tile footprints. Every npc this loop can
     * currently reach is `boundSize` 1 (the Chicken is), so "adjacent or
     * co-located" is the whole of the behaviour that can be exhibited, and
     * modelling a footprint rule nothing exercises would be untested precision.
     */
    const val ATTACK_RANGE = 1

 // --------------------------------------------- REAL DAMAGE

    val realDamageEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.realdamage") == "true"

    /**
     * The seed for [combatRandom]. Separate from [DROP_SEED] and
     * [RETALIATION_SEED] on purpose: three independent claims of replayability
     * sharing one stream means adding an accuracy roll silently reshuffles
     * every drop, which is the cross-talk [reset]'s own comment records having
     * been bitten by once already.
     *
     * `0x52454144` is "READ" - arbitrary, and it does not have to be anything
     * else; what matters is that it is fixed and printed.
     */
    const val REAL_DAMAGE_SEED = 0x52454144L

    val seedProperty: Long? = System.getProperty("opennxt.combat.seed")?.trim()?.let { raw ->
        raw.toLongOrNull().also { if (it == null) logger.warn { "-Dopennxt.combat.seed=$raw is not a whole number; IGNORED, the seed is random this run" } }
    }
    val bootSeed: Long = seedProperty ?: java.security.SecureRandom().nextLong()
    val seedPinned: Boolean get() = seedProperty != null
    private var seedLogged = false

    /** Deterministic accuracy/damage rolls under [realDamageEnabled]; see [REAL_DAMAGE_SEED]. */
    private val combatRandom = Random(bootSeed xor REAL_DAMAGE_SEED)

    /** Hits that landed through [resolveHit] since [reset]. */
    private var realHits: Int = 0

    /** Attacks that MISSED the accuracy roll since [reset]. */
    private var realMisses: Int = 0

    /** Attacks the model refused to compute since [reset]. */
    private var realRefusals: Int = 0

    /** The most recent refusal reason, for checks and for the operator. */
    private var lastRealRefusal: String? = null

    fun realHitCount(): Int = realHits
    fun realMissCount(): Int = realMisses
    fun realRefusalCount(): Int = realRefusals
    fun lastRealDamageRefusal(): String? = lastRealRefusal

    /** Distinct refusal reasons already logged, so a stalled fight logs once and not every tick. */
    private val loggedRefusals = java.util.Collections.synchronizedSet(HashSet<String>())
    private val loggedAmmoNote = HashSet<Int>()
    /** The clock of the last refused swing, so the swing animation is withheld for a refusal (review, styles INFO). */
    private var lastRealRefusalTick = -1

    /**
     * Resolves ONE swing through the existing model. See [realDamageEnabled]
     * for what is grounded, what is reconstructed and why a refusal never falls
     * back to a number.
     */
    fun resolveHit(player: WorldPlayer, npc: WorldNpc, random: Random): HitResolution {
        val weapon = player.wornWeapon
        val derived = com.opennxt.model.world.PlayerCombatStats.derive(
            attackLevel = player.level(com.opennxt.api.stat.Stat.ATTACK),
            strengthLevel = player.level(com.opennxt.api.stat.Stat.STRENGTH),
            magicLevel = player.level(com.opennxt.api.stat.Stat.MAGIC),
            rangedLevel = player.level(com.opennxt.api.stat.Stat.RANGED),
            defenceLevel = player.level(com.opennxt.api.stat.Stat.DEFENCE),
            weapon = weapon
        )
        val accuracy = derived.effectiveAccuracy
            ?: return HitResolution.NotComputable(
                "${player.name} has no derivable accuracy: ${derived.gaps.joinToString("; ")}"
            )
        val maxHitX10 = derived.effectiveMaxHitX10
            ?: return HitResolution.NotComputable(
                "${player.name} has no derivable max hit: ${derived.gaps.joinToString("; ")}"
            )
        val combat = npc.combat
            ?: return HitResolution.NotComputable("no combat definition for npc ${npc.gameId}")
        val armour = combat.armour
            ?: return HitResolution.NotComputable(
                "npc ${npc.gameId} carries no armour param (${NpcCombatParams.ARMOUR}); absent is not zero"
            )
 //: the slot is picked RELATIVE to the npc's weak style (CombatFormulas.affinitySlot);
        // until then every melee swing read slot 0, the npc's specific-weakness 90.
        // not fought adjacent - the gap derive() recorded is consulted here, not only logged.
        if (derived.style != CombatStyle.MELEE && derived.attackRange == null) {
            return HitResolution.NotComputable(
                "${player.name}'s ${derived.style} weapon carries no attack-range param (13); absent is not zero: ${derived.gaps.joinToString("; ")}"
            )
        }
 //: the affinity slot is the WEAPON's style's, not always melee's.
        val affinity = combat.affinityFor(derived.style)
            ?: return HitResolution.NotComputable(
                "npc ${npc.gameId} has no usable ${derived.style} affinity: weak style unknown and the style slots disagree, " +
                    "or the slot param is absent; absent is not zero"
            )
        if (derived.style == CombatStyle.RANGED && loggedAmmoNote.add(weapon?.definition?.id ?: -1)) {
            logger.info { "COMBAT ${player.name} fires ${weapon?.definition?.name}: ranged auto-attacks consume NO ammunition yet (no ammo model; I-20 open). Noted once per weapon." }
        }

        val chance = CombatFormulas.hitChance(accuracy, armour, affinity)
        if (random.nextDouble() >= chance) return HitResolution.Missed(chance)
        // x10 -> lifepoints, done visibly at the point of use.
 // 80%..100% for the player's auto-attack
        return HitResolution.Landed(CombatFormulas.damageRoll(maxHitX10 / 10, random, CombatFormulas.PLAYER_AUTO_ATTACK_FLOOR_PERCENT), chance)
    }


    /**
     * Ticks between [player]'s swings.
     *
     * With [realDamageEnabled] OFF, or with no weapon carrying param 14, this
     * is [interval] - the operator's override or the authored
     * [ATTACK_INTERVAL_TICKS]. With it ON and a weapon that carries param 14,
     * it is the WEAPON's number, straight out of the cache.
     *
     * An explicit `-Dopennxt.experiment.combat.interval` always wins, in both
     * arms: an operator who pinned the cadence is debugging something, and a
     * weapon quietly overriding their pin is the kind of surprise that costs an
     * hour.
     *
     * See [com.opennxt.model.world.EquippedWeapon.attackSpeedTicks] for the
     * measurement (4,418 items, 0.9989 of them in the two hand slots, shuffle
     * control) and for what is still not established (that the SERVER divides
     * swings by it).
     */
    fun intervalFor(player: WorldPlayer): Int {
        val explicit = System.getProperty("opennxt.experiment.combat.interval")?.trim()?.toIntOrNull()
        if (explicit != null) return explicit.coerceAtLeast(1)
        if (!realDamageEnabled) return interval()
        val fromWeapon = player.wornWeapon?.attackSpeedTicks ?: return interval()
        return fromWeapon.coerceAtLeast(1)
    }

    /**
     * The real-damage path's provenance as a runtime string, so a log line
     * cannot claim more than the cache supports. Asserted by
     * .
     */
    val REAL_DAMAGE_PROVENANCE: String =
        "real damage (-Dopennxt.experiment.combat.realdamage, DEFAULT OFF): AUTHORED - NOTHING. The path " +
            "calls the model that was already in this repository and was reachable only from check tools: " +
            "PlayerCombatStats.derive -> CombatFormulas.hitChance(accuracy, npc param 2865, the affinity of the weapon's style) " +
            "-> CombatFormulas.damageRoll over the weapon's own-style max hit (params 641/643/965) / 10. GROUNDED: item params " +
            "3267/641/749/750/14 (melee), 4/643 (ranged), 3/965 (magic), 13 (reach), the style flags 2825/2826/2827, and npc params 2865/affinity. RECONSTRUCTED: hitChance, the 20%..100% " +
            "damage band, and every level-to-rating formula - each names its published source and none is " +
            "verifiable against this cache. The attack interval is the weapon's own param 14 " +
            "(4418 items, values {3,4,5,6,12}, 0.9989 of them in equip slots 3 and 5 against a 0.3687 " +
            "shuffle control; param 14's own declared defaultint is 4, which is exactly the interval this " +
            "file authored while claiming there was nothing to read). REFUSALS DO NOT FALL BACK to the " +
            "flat DEMO_HIT: an uncomputable swing applies zero and is counted. WITH THE SWITCH OFF none of " +
            "this runs and the flat path is unchanged."

 // ------------------------------------------------------- chasing

    /**
     * `-Dopennxt.experiment.combat.chase=false` turns re-pathing OFF, which
     * reproduces the stall this feature exists to remove. Default ON.
     */
    val chaseEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.chase") != "false"

    /**
     * Minimum ticks between two pathfinder runs for one engagement.
     *
     * AUTHORED, and it exists because of a measured cost, not a guess.
     * [com.opennxt.model.map.PathFinder] is an unbudgeted BFS with no heuristic
     * running on the tick thread; at the default 128-tile window one search can
     * dequeue up to `129 x 129 = 16,641` nodes, and a full flood of that window
     * was measured at 31-48 ms idle and 120-220 ms under load. A re-path every
     * tick per engaged player is therefore a scale risk on its own.
     *
     * 2 is the smallest value that halves the worst case while leaving the chase
     * visibly responsive: an npc steps at most once every
     * [com.opennxt.model.world.NpcWander.INTERVAL_TICKS] = 8 ticks, so a cooldown
     * of 2 cannot make the player lag more than 2 ticks behind a step it was
     * always going to take 8 ticks to repeat. The cadence gate is per
     * engagement, not global, so one player's chase cannot starve another's.
     */
    const val CHASE_REPATH_COOLDOWN_TICKS = 2

    /**
     * How many candidate standing tiles one re-path may try before giving up
     * this tick.
     *
     * AUTHORED. Each candidate that does NOT route costs a full flood of the
     * window (the BFS cannot know it is unreachable without exhausting it), so
     * this is the multiplier on the worst case and the reason it is 3 and not 8.
     * Candidates are ordered nearest-first, so the three tried are the three a
     * player would actually walk to.
     */
    const val CHASE_CANDIDATES = 3

    /**
     * Upper bound on the pathfinder window the chase asks for.
     */
    const val CHASE_SEARCH_CAP = 32

    /**
     * Chebyshev distance past which the chase gives up and DISENGAGES.
     *
     * AUTHORED. Its job is to stop an engagement outliving the fight: without
     * it, a target that becomes unreachable (a wandering npc behind a wall, or
     * one whose leash pulls it out of the search window) leaves a live
     * engagement re-pathing forever. 16 is one tile past NPC_INFO's 14-tile
     * transmit reach plus a leash step, i.e. "the client can no longer see it",
     * which is the point at which a fight the player cannot observe should not
     * still be a fight. It is NOT an RS number - real RS breaks melee combat off
     * on its own rules - and nothing but the disengage decision reads it.
     */
    const val CHASE_GIVE_UP_DISTANCE = 16

    /** Pathfinder runs the chase has made since [reset]. Observable so a check can price it. */
    private var chasePathfinds: Int = 0

    /** Nodes those runs dequeued in total (see [PathFinder.lastNodesVisited]). */
    private var chaseNodes: Long = 0

    /** Ticks on which a chase actually re-pathed. */
    private var chaseRepaths: Int = 0

    /** Engagements dropped by [CHASE_GIVE_UP_DISTANCE]. */
    private var chaseGiveUps: Int = 0

    fun chasePathfindCount(): Int = chasePathfinds
    fun chaseNodeCount(): Long = chaseNodes
    fun chaseRepathCount(): Int = chaseRepaths
    fun chaseGiveUpCount(): Int = chaseGiveUps

    /**
     * The tiles a player may stand on to attack [npc], nearest-first from
     * ([fromX], [fromZ]).
     *
     * DERIVED, and from two things that are already measured:
     *
     *  - the npc's FOOTPRINT is `npcs.boundSize` (via
     *    [NpcCombatDefinition.size]), which is 1 where the column is null - and
     *    it is null on 11,680 rows, so "absent means 1" is the config default
     *    rather than a fallback of ours;
     *  - the RING is every tile within [ATTACK_RANGE] of that footprint, i.e.
     *    exactly the set of tiles from which [tick]'s own distance test passes.
     *    Diagonals are INCLUDED because that test is Chebyshev; excluding them
     *    would produce a walk to a tile the loop then refuses to hit from, which
     *    is the class of bug [com.opennxt.model.map.LocInteraction] was written
     *    to fix in the loc case.
     *
     * Only walkable tiles are returned, so a candidate is never a tile the
     * pathfinder would refuse anyway, and the sort is (Chebyshev distance from
     * the player, then z, then x) - fixed, for the same reason PathFinder's
     * direction order is fixed: two equally good destinations are not
     * interchangeable when the result is logged and diffed.
     */
    fun attackTilesFor(npc: WorldNpc, fromX: Int, fromZ: Int, range: Int = ATTACK_RANGE): List<IntArray> {
        val loc = npc.location
        val size = (npc.combat?.size ?: 1).coerceAtLeast(1)
        val x0 = loc.x
        val z0 = loc.y
        val x1 = loc.x + size - 1
        val z1 = loc.y + size - 1
        val out = ArrayList<IntArray>()
 //: the ring is the PLAYER's reach wide (a bow's 7), not always one tile.
        for (x in (x0 - range)..(x1 + range)) {
            for (z in (z0 - range)..(z1 + range)) {
                // The npc's own tiles are excluded: an npc occupies them. Every
                // other tile in the box is within ATTACK_RANGE of the footprint
                // by construction.
                if (x in x0..x1 && z in z0..z1) continue
                if (!com.opennxt.model.map.CollisionMap.walkable(x, z, loc.plane)) continue
                out.add(intArrayOf(x, z))
            }
        }
        return out.sortedWith(
            compareBy(
                { maxOf(Math.abs(it[0] - fromX), Math.abs(it[1] - fromZ)) },
                { it[1] },
                { it[0] }
            )
        )
    }

    /**
     * The option slot whose action string is "Attack" on the great majority of
     * attackable npcs, i.e. OPNPC2.
     *
     * **This constant is documentation, not a gate.** [shouldEngage] tests the
     * clicked option's own ACTION STRING, so the loop never needs to assume an
     * index - which is just as well, because assuming this one would be wrong
     * for 74 npcs. Measured over all 32,687 `npcs` rows:
     *
     * actions_0 = 'Attack' 71 npcs (OPNPC1)
     * actions_1 = 'Attack' 7,840 npcs (OPNPC2) <- this constant
     * actions_2 = 'Attack' 3 npcs (OPNPC3)
     */
    const val ATTACK_OPTION = 2

    /**
     * The action string that means "this option starts a fight", compared
     * case-insensitively.
     *
     * GROUNDED, and it is a cache string rather than a client built-in - which
     * is worth stating because the opposite was believed. 7,914 of the 32,687
     * npcs carry it in one of `actions_0..2`, and that set is EXACTLY the set
     * of npcs whose `attackCursor` column is non-null: 7,914 both ways, 0 with
     * a cursor but no string, 0 with a string but no cursor, over the whole
     * table. Two independently decoded columns agreeing on the same 7,914 rows
     * is what identifies attackability here.
     *
     * `npcs.combat > 0` is NOT this marker and is not used as one: 8,404 rows
     * have a combat level, so 490 of them have a level and no Attack option.
     */
    const val ATTACK_ACTION = "Attack"

    /**
     * Seed for the drop-roll [Random].
     *
     * Fixed so a session's kills are replayable: [WorldNpcs.applyDamage] takes
     * the RNG as an argument precisely so a death can be reproduced from a
     * seed, and handing it a time-seeded generator would throw that property
     * away for nothing. 0x43424154 is the ASCII of "CBAT" and means nothing
     * else.
     */
    const val DROP_SEED = 0x43424154L

    // ------------------------------------------------------------- state

    /**
     * One player's fight.
     */
    private class Engagement(val npc: WorldNpc, nextAttackAt: Int) {
        var nextAttackAt: Int = nextAttackAt

        var pursuit: AggroTarget? = null

        /** The npc tile the last chase path was computed against; MIN_VALUE = never. */
        var pathedTargetX: Int = Int.MIN_VALUE
        var pathedTargetZ: Int = Int.MIN_VALUE

        /** The tile the last chase path was aimed AT, or null when the chase queued nothing. */
        var chaseDestX: Int = Int.MIN_VALUE
        var chaseDestZ: Int = Int.MIN_VALUE

        /** [clock] of the last pathfinder run; the [CHASE_REPATH_COOLDOWN_TICKS] gate. */
        var lastRepathAt: Int = Int.MIN_VALUE
    }

    /**
     * Live engagements, keyed by IDENTITY. [WorldPlayer] has no `equals`
     * contract for use as a hash key, and two players can share a name across
     * a reconnect; identity is the only thing that is certainly stable for as
     * long as the object is in the world.
     */
    private val engagements = IdentityHashMap<WorldPlayer, Engagement>()

    private val swingReadyAt = IdentityHashMap<WorldPlayer, Int>()

    /**
     * How many live engagements target each npc: the
     * predicate [isInCombat] that stops [com.opennxt.model.world.NpcWander]
     */
    private val engagedNpcCounts = IdentityHashMap<WorldNpc, Int>()

    /** True while some player is engaged on [npc] or the npc holds an aggro target. */
    fun isInCombat(npc: WorldNpc): Boolean = engagedNpcCounts.containsKey(npc) || aggroTargets.containsKey(npc)

    /** Players killed by retaliation THIS tick (audit C-03): the aggression pass must not hit them again. */
    private val diedThisTick = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())

    /** Engagements ended because the player changed plane (audit C-05). */
    private var planeDisengages = 0
    fun planeDisengageCount(): Int = planeDisengages

    /** Engages that found the player's swing timer still running and kept it (since [reset]). */
    private var retargetsHeld = 0
    fun retargetsHeldCount(): Int = retargetsHeld

    /** The loop's own tick clock, read by the cadence gate. */
    private var clock: Int = 0

    /** Drop rolls; seeded from [bootSeed] (see it), reseeded to [DROP_SEED] by [reset]. */
    private val random = Random(bootSeed xor DROP_SEED)

    /** Hits actually applied since [reset]. Observable so a check can assert on it. */
    private var hitsApplied: Int = 0

    /** Kills since [reset]. */
    private var kills: Int = 0

    fun hitCount(): Int = hitsApplied
    fun killCount(): Int = kills
    fun ticks(): Int = clock
    fun engagementCount(): Int = engagements.size

    /** The npc [player] is fighting, or null. */
    fun targetOf(player: WorldPlayer): WorldNpc? = engagements[player]?.npc

    /**
     * Drops all state. Intended for a harness that drives
     * this object repeatedly in one process; the server never calls it.
     */
    fun reset() {
        engagements.clear()
        swingReadyAt.clear(); retargetsHeld = 0
        engagedNpcCounts.clear(); diedThisTick.clear(); planeDisengages = 0; returningHome.clear(); aggroHomeAttempts.clear(); aggroHomePathfinds = 0
        retaliationAttempts = 0; pursuits = 0
        loggedAmmoNote.clear(); loggedRangeless.clear(); lastRealRefusalTick = -1
        aggroTargets.clear(); aggroFirstSeen.clear()
        aggroAcquired = 0; aggroDropped = 0; aggroSwings = 0; aggroTolerated = 0; aggroPathfinds = 0; aggroPathFailures = 0
        clock = 0
        hitsApplied = 0
        kills = 0
        xpAwarded = 0.0
        random.setSeed(DROP_SEED)
        chasePathfinds = 0
        chaseNodes = 0
        chaseRepaths = 0
        chaseGiveUps = 0
        realHits = 0
        realMisses = 0
        realRefusals = 0
        lastRealRefusal = null
        loggedRefusals.clear()
        combatRandom.setSeed(REAL_DAMAGE_SEED)
        retaliationHits = 0
        retaliationDamage = 0
        playerDeaths = 0
        lastRetaliationRefusal = null
        // A FRESH engine, not a cleared one: NpcRetaliation holds its own tick
        // clock and its own per-npc attack-speed gates, and leaving them behind
        // would let one section's swing timers decide when the next section's
        // npc is allowed to hit. Discovered the honest way - two sections of
        // method exists to prevent.
        retaliation = com.opennxt.model.world.NpcRetaliation()
        retaliationRandom.setSeed(RETALIATION_SEED)
    }

    // ------------------------------------------------------------- engaging

    /**
     * Whether a click should start a fight: the clicked option's own action
     * string is [ATTACK_ACTION].
     *
     * [action] is what [com.opennxt.resources.sqlite.SqliteNpcCodec] read out
     * of `actions_<option-1>` for this npc - so this asks the cache "is the row
     * the player clicked an Attack row", which needs no assumption about WHICH
     * row that is. Null (an empty slot) is not an attack.
     */
    fun shouldEngage(action: String?): Boolean = action != null && action.equals(ATTACK_ACTION, ignoreCase = true)

    /**
     * Points [player] at [npc].
     *
     * Returns a typed [EngageResult]: this refuses rather than defaults, and
     * every refusal names the missing piece. In particular an npc whose
     * [WorldNpc.lifepoints] is null is refused HERE, because
     * [WorldNpc.damage] would throw on it and a fight that cannot land a hit
     * should not be entered - see that method for why there is no default
     * maximum.
     */
    fun engage(player: WorldPlayer, npc: WorldNpc): EngageResult {
        if (!enabled) return EngageResult.Disabled
        if (!npc.alive) return EngageResult.Refused(
            "npc ${npc.gameId} is dead (respawns in ${npc.respawnTicksRemaining} ticks)"
        )
        if (npc.lifepoints == null) return EngageResult.Refused(
            "npc ${npc.gameId} (${npc.name ?: "unnamed"}) has null seeded lifepoints - no seed source " +
                "covers this game id and there is no default, so it has no health to remove"
        )
        if (npc.infoIndex < 0) return EngageResult.Refused(
            "npc ${npc.gameId} has no NPC_INFO slot, so the client has no handle to face or splat"
        )

        // The swing timer is the PLAYER's, not the click's: carry whatever is
        // still running, from the live engagement or from the map that outlives it.
        val readyAt = maxOf(engagements[player]?.nextAttackAt ?: 0, swingReadyAt[player] ?: 0)
        if (readyAt > clock) retargetsHeld++
 // (audit C-04): switching target ends the old fight properly - the old
        // npc gets its face-clear - instead of being overwritten with its face target set.
        val previous = engagements[player]
        if (previous != null && previous.npc === npc) {
            // its chase cooldowns, its pathed target and the npc's pursuit - and only the swing timer
            // is (re)seeded. A fresh object per click let a click-per-tick client re-path every tick.
            previous.nextAttackAt = readyAt
            if (faceEnabled) {
                npc.pendingUpdates.facePlayer(player.entity.index)
                PlayerUpdates.faceNpc(player.entity, npc.infoIndex)
            }
            // The target panel again: the reference client re-sends the whole mount on a re-click
            // (IF_OPENSUB_ACTIVE_NPC twice, one tick apart, on every OPNPC2), and the
            // client's own targeting script closes the panel when you click away.
            TargetHud.show(player, npc)
            return EngageResult.Engaged(npc)
        }
        if (previous != null) disengage(player)
        engagements[player] = Engagement(npc, readyAt)
        engagedNpcCounts[npc] = (engagedNpcCounts[npc] ?: 0) + 1

        // Face, once, at engage - not every tick. The client STORES the target
        // (entity+0x1b4 / entity+0x228, see NpcFaceEntityBlock), so a re-send
        // every tick would be the same fact paid for repeatedly.
        if (faceEnabled) {
            npc.pendingUpdates.facePlayer(player.entity.index)
            PlayerUpdates.faceNpc(player.entity, npc.infoIndex)
        }

 // THE TARGET INFORMATION PANEL. The reference client sends SET_TARGET, the
        // health percentage varc, IF_OPENSUB_ACTIVE_NPC(1488:4, 1490) and
        // RUNCLIENTSCRIPT 82 on the tick the OPNPC2 lands; [TargetHud] is that
        // sequence and the evidence for it.
        TargetHud.show(player, npc)
        return EngageResult.Engaged(npc)
    }

    /**
     * Ends [player]'s fight and clears both face-entity targets.
     *
     * Returns true if there was a fight to end. The face CLEAR matters: the
     * client holds the target until told otherwise, so without it a player and
     * a corpse keep staring at each other's last known slot.
     */
    fun disengage(player: WorldPlayer): Boolean {
        val engagement = engagements.remove(player) ?: return false
        engagedNpcCounts[engagement.npc]?.let { n -> if (n <= 1) engagedNpcCounts.remove(engagement.npc) else engagedNpcCounts[engagement.npc] = n - 1 }
        if (faceEnabled) {
            if (engagement.npc.alive) engagement.npc.pendingUpdates.faceNothing()
            PlayerUpdates.faceNothing(player.entity)
        }
        // ...and the panel comes down: SET_TARGET 0, varc 2059 = 0, IF_CLOSESUB(1488:4).
        // The reference client sends exactly this on the tick the target dies (observation tick 2209).
        TargetHud.hide(player)
        return true
    }

    /**
     * Drops every reference to [npc]: the players engaged
     * on it are disengaged, its aggro target, first-seen, home-walk and pursuit state removed.
     */
    fun forgetNpc(npc: WorldNpc) {
        val engaged = engagements.entries.filter { it.value.npc === npc }.map { it.key }
        for (player in engaged) disengage(player)
        engagedNpcCounts.remove(npc)
        aggroTargets.remove(npc)
        aggroFirstSeen.remove(npc)
        returningHome.remove(npc)
        aggroHomeAttempts.remove(npc)
        retaliationTargets.remove(npc)
        retaliation.forget(npc)
    }
    /** Whether the retaliation engine still holds a cadence timer for [npc] (check probe). */
    fun retaliationTracks(npc: WorldNpc): Boolean = retaliation.tracks(npc)

    /**
     * WHOM A MANY-ATTACKED NPC SWINGS AT.
     */
    private val retaliationTargets = IdentityHashMap<WorldNpc, WorldPlayer>()
    fun retaliationTargetOf(npc: WorldNpc): WorldPlayer? = retaliationTargets[npc]
    /** Swings withheld from an in-reach attacker because the npc chose another (since boot). */
    var retaliationDeferred: Int = 0
        private set

    private fun chooseRetaliationTargets(players: List<WorldPlayer>) {
        retaliationTargets.clear()
        val candidates = IdentityHashMap<WorldNpc, ArrayList<WorldPlayer>>()
        for (player in players) {
            val e = engagements[player] ?: continue
            val npc = e.npc
            if (!npc.alive) continue
            val loc = player.entity.location
            if (loc.plane != npc.location.plane) continue
            if (footprintDistance(npc, loc.x, loc.y) > npcAttackRange(npc)) continue
            candidates.getOrPut(npc) { ArrayList() }.add(player)
        }
        for ((npc, list) in candidates) {
            val aggro = aggroTargets[npc]?.player?.takeIf { a -> list.any { it === a } }
            retaliationTargets[npc] = aggro ?: list.maxByOrNull { npc.damageBy[it.name] ?: 0 } ?: list.first()
        }
    }

    // ------------------------------------------------------------- the tick

    /**
     * One world tick of every live fight.
     *
     * Call it AFTER incoming packets are handled (so a click engages on the
     * same tick it arrives) and BEFORE the players are encoded (so the blocks
     * queued here ride out on this tick's NPC_INFO). `World.tick` does exactly
     * that.
     *
     * Per engagement, in order:
     *
     * 1. the npc died or was removed -> disengage, nothing else
     * 2. out of the player's reach ([playerAttackRange]; [ATTACK_RANGE] unarmed) -> CHASE: re-path onto the target's attack ring,
     * under the budget at [chaseEnabled], and hit nothing this tick.
     */
    fun tick(npcs: WorldNpcs, groundItems: GroundItems, players: List<WorldPlayer>): List<DeathResult> {
        if (!enabled) return emptyList()
        clock++
        if (!seedLogged) {
            seedLogged = true
            logger.info {
                "COMBAT rng seed=$bootSeed " + (if (seedPinned) "(pinned by -Dopennxt.combat.seed)"
                else "(SecureRandom at boot; pass -Dopennxt.combat.seed=$bootSeed to replay this session's rolls)")
            }
        }
        diedThisTick.clear()
        // Expired swing timers carry no information (a fresh engage would seed 0
        // anyway), so dropping them here is what bounds [swingReadyAt].
        if (swingReadyAt.isNotEmpty()) swingReadyAt.values.removeIf { it <= clock }
        // The retaliation engine's own clock, advanced once per world tick and
        // NOT once per engagement: an npc's attack-speed gate is a property of
        // the npc, so ticking it per player would let a second player double an
        // npc's swing rate.
        retaliation.tick()

        // Drop engagements whose player is no longer in the world.
        //
        // Without this the map is a slow leak: [World.cullDisconnected] removes a
        // disconnected player from its own set but knows nothing about this one,
        // so a player who logs out mid-fight leaves an entry that is never
        // matched again and never removed - holding a WorldPlayer, its entity and
        // a WorldNpc alive for the life of the process. Pruning here rather than
        // in the cull keeps the whole engagement lifecycle in one file.
        if (engagements.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
 // (audit T-08): through disengage, so the npc's face target is cleared
            // rather than left pointing at an index the next login may be handed.
            val gone = engagements.keys.filter { it !in live }
            for (player in gone) disengage(player)
        }

        val deaths = ArrayList<DeathResult>()
        chooseRetaliationTargets(players)
        for (player in players) {
            val engagement = engagements[player] ?: continue
            val npc = engagement.npc

            if (!npc.alive) {
                disengage(player)
                continue
            }

            val location = player.entity.location
            val target = npc.location
            if (location.plane != target.plane) {
 // (audit C-05): a plane change ends the fight like the chase's
                // give-up does, instead of freezing the engagement until the npc dies.
                planeDisengages++
                disengage(player)
                continue
            }
            val distance = footprintDistance(npc, location.x, location.y)   // to the footprint (review #2)
 //: the PLAYER's reach is the weapon's (item param 13: bow 7, staff 8), else adjacent.
            val playerRange = playerAttackRange(player)
            if (distance > playerRange) {
                // THE CHASE. Everything about its cost is in `chase`.
                if (!chase(player, engagement, npc, distance, playerRange)) {
                    // Gave up (target beyond CHASE_GIVE_UP_DISTANCE): the
                    // engagement is already gone, so do not go on to hit it.
                    continue
                }
                continue
            }

            // In range. Stop walking, but ONLY if the queued path is the one this
            // loop put there: a player who clicked somewhere else mid-fight owns
            // their own movement, and stomping it here would make combat quietly
            // override a click. The destination comparison is what distinguishes
            // the two.
            val dest = player.entity.movement.destination()
            if (dest != null && dest.first == engagement.chaseDestX && dest.second == engagement.chaseDestZ) {
                player.entity.movement.reset()
                engagement.chaseDestX = Int.MIN_VALUE
                engagement.chaseDestZ = Int.MIN_VALUE
            }

            // The player's swing, on ITS cadence. Wrapped in an `if` rather than
 // a `continue` since because the npc's swing below runs on
            // a DIFFERENT clock (its own cache param 14), and gating the npc on
            // the player's cadence would silently slow every npc in the world to
            // the player's attack speed.
            if (clock >= engagement.nextAttackAt) {
                // The cadence. With -Dopennxt.experiment.combat.realdamage on and
                // a weapon carrying cache param 14, this is the WEAPON's swing
                // timer; otherwise it is the authored [ATTACK_INTERVAL_TICKS].
                // See [intervalFor].
                engagement.nextAttackAt = clock + intervalFor(player)
                swingReadyAt[player] = engagement.nextAttackAt

                val animId = player.wornWeapon?.definition?.id?.let { itemId -> 
                    val params = WeaponAnimations.sequenceParamsFor(itemId)
                    params[4385] ?: params[2914] ?: params.values.firstOrNull()
                } ?: WeaponAnimations.selected() ?: 422 // 422 is unarmed punch
                // (the animation is queued below, once the swing is known not to be a refusal)

                // WHAT TO HIT FOR. Two arms, and the flat one is untouched:
                //
                //  - switch off  -> [damage], i.e. the flat AUTHORED [DEMO_HIT];
                //  - switch on   -> [resolveHit] through the model that already
                //                   existed (accuracy vs armour, then a roll
                //                   under a weapon-derived max).
                //
                // A miss or a refusal yields null and the swing does NOTHING -
                // no damage, no splat, no xp - rather than falling back to the
                // authored number. See [realDamageEnabled].
                val requested: Int? = if (!realDamageEnabled) damage() else {
                    when (val resolution = resolveHit(player, npc, combatRandom)) {
                        is HitResolution.Landed -> { realHits++; resolution.damage }
                        is HitResolution.Missed -> { realMisses++; null }
                        is HitResolution.NotComputable -> {
                            realRefusals++
                            lastRealRefusalTick = clock
                            lastRealRefusal = resolution.reason
                            // Once per distinct reason: a stalled fight refuses
                            // every cadence tick, and a log that repeats itself
                            // is a log that gets skimmed.
                            if (loggedRefusals.add(resolution.reason)) {
                                logger.warn {
                                    "COMBAT ${player.name}: real damage is ON but this swing is not " +
                                        "computable, so NOTHING was applied - ${resolution.reason}. " +
                                        "(Warned once per distinct reason.) $PROVENANCE_SHORT"
                                }
                            }
 // after a sandbox rollback. The
                            // recovery replay ended on this pass's SECOND
                            // mutation control, which made this branch fall back
                            // to damage() - the flat authored 48 - instead of
                            // null. That is precisely the behaviour real damage
                            // exists to refuse: an uncomputable swing must apply
                            // NOTHING, so the absence of a cache value can never
                            // be silently replaced by an invented one.
                            null
                        }
                    }
                }
                // A refused swing (uncomputable) plays no animation; a miss is a real swing and does.
                if (requested != null || lastRealRefusalTick != clock) {
                    com.opennxt.model.entity.rendering.PlayerUpdates.animate(player.entity, animId)
                }
 // (audit C-02): a miss or a refusal used to `continue` here and
                // skip the npc's own swing below for that tick, so the npc's cadence drifted
                // with the player's miss pattern (and starved entirely under interval=1).
                // The apply block is now conditional; the retaliation is reached regardless.
                val before = if (requested == null) null else npc.currentLifepoints
                if (requested != null && before != null) {
                // Snapshot before the hit: applyDamage floors at 0, so the amount
                // actually removed is not necessarily the amount asked for, and the
                // splat must show what happened rather than what was requested.
                val death = npcs.applyDamage(npc, requested, random, groundItems, player.name, playerStyle(player))
                val applied = before - (npc.currentLifepoints ?: 0)
                if (applied > 0) hitsApplied++
                // The panel's percentage. One CLIENT_SETVARC_SMALL per damage tick is the
                // ONLY thing the reference client sends here besides NPC_INFO - every packet on the tick
                // the protocol's cow went 479 -> 452 was enumerated. The NUMBER on the bar
                // rides in NPC_INFO's bit-16 block, queued by WorldNpcs.applyDamage.
                if (applied > 0) TargetHud.update(player, npc)

                // The splat. [DEFAULT_HITMARK] is used unless the operator asked for
                // a different id or for `none`; see [splatType] for exactly what is
                // grounded about the default and what is not.
                val type = splatType
                if (type != null && applied > 0) {
                    // Delay splat slightly so it hits when the weapon swings
                    val seqDuration = com.opennxt.model.definitions.SeqDefinitions.get(animId)?.durationInTicks ?: 1
                    // 30 client ticks = 1 server tick
                    val splatDelay = (seqDuration / 2).coerceAtLeast(1) * 30
                    npc.pendingUpdates.hit(NpcHitsBlock.Hit(type, applied, splatDelay)) 
                }

                // Experience is paid ON THE KILL, by damage share, to every attacker on the ledger.

                if (death != null) {
                    kills++
                    deaths += death
                    awardKillExperience(death, npc, players)
                    logger.info {
                        "COMBAT ${player.name} killed ${npc.name ?: "npc${npc.gameId}"} (${npc.gameId}) - " +
                            "respawns in ${death.respawnTicks} ticks; drops: " +
                            (if (death.dropped.isEmpty()) "none" else death.dropped.joinToString()) +
                            (if (death.unexpandedTableRolls.isEmpty()) ""
                            else " [unexpanded: ${death.unexpandedTableRolls.size}]")
                    }
                    // The corpse IS transmitted on this tick and for its linger
 //, so the killing blow's splat
                    // and death animation ride out; the fight is over, so no retaliation.
                    disengage(player)
                    continue
                }
                } // requested != null && before != null
            }

            // ...and the npc hits back - from ITS reach. A player shooting from seven tiles is
 // out of a melee npc's reach, so the npc closes the distance instead ,
            // styles): the same bounded walk the aggression path uses, inside the npc's leash.
            if (distance <= npcAttackRange(npc)) {
 // the npc's swing is its own per-npc pass below (review MINOR: a chosen target
                // whose own chase `continue`d above never reached this site, and every other in-reach
                // attacker was deferred, so a ranged npc swung at nobody); here only the deferral count
                if (retaliationTargets[npc] !== player) retaliationDeferred++
            } else {
                // one cooldown per npc: reuse the aggro target when it is this player (review, styles LOW)
                val pursuit = aggroTargets[npc]?.takeIf { it.player === player }
                    ?: engagement.pursuit ?: AggroTarget(player).also { engagement.pursuit = it }
                pursuits++
                aggroWalk(npc, pursuit)
            }
        }

 // THE NPCS' SWINGS, one pass per npc at its chosen target: independent of where
        // the chosen player's own loop iteration ended (chasing, in range), as long as the npc is
        // alive, the player still engaged on it, in the npc's reach and not dead this tick.
        for ((npc, chosen) in ArrayList(retaliationTargets.entries).map { it.key to it.value }) {
            if (!npc.alive) continue
            if (engagements[chosen]?.npc !== npc) continue
            if (chosen in diedThisTick) continue
            val loc = chosen.entity.location
            if (loc.plane != npc.location.plane) continue
            if (footprintDistance(npc, loc.x, loc.y) > npcAttackRange(npc)) continue
            retaliate(chosen, npc)
        }

 //: npcs that START fights. See the AGGRESSION section.
        aggressionTick(npcs, players)
        return deaths
    }

 // ------------------------------------------------------- aggression

    const val AGGRO_SWITCH = "opennxt.experiment.npcs.aggro"

    /** 10 minutes at 600 ms per tick - the longer bound of the wiki's "5-10 minutes". */
    const val AGGRO_TOLERANCE_TICKS = 1000

    /** Beyond this many tiles from the npc the tolerance clock for that npc resets (wiki: "moves too far out"). */
    const val AGGRO_TOLERANCE_RESET_DISTANCE = 32

    /** Ticks between path recomputations for one aggressive npc. */
    const val AGGRO_REPATH_TICKS = 4

    /** The level-76 threshold above which the level rule no longer applies (wiki). */
    const val AGGRO_ALWAYS_LEVEL = 76

    /** The radius, or null when the switch is absent, `off`, or unparseable. Never a default number. */
    val aggroRadius: Int?
        get() {
            val raw = System.getProperty(AGGRO_SWITCH)?.trim() ?: return null
            if (raw.equals("off", ignoreCase = true)) return null
            return raw.toIntOrNull()?.takeIf { it > 0 }
        }

    /** THE LEVEL RULE, as a pure function so a check can drive it with the wiki's own examples. */
    fun aggressiveTowards(npcLevel: Int, playerLevel: Int): Boolean =
        npcLevel >= AGGRO_ALWAYS_LEVEL || playerLevel < 2 * npcLevel + 1   // "not aggressive to players who ARE double plus one"

    /** Whether [npc] is one the wiki lists as aggressive (per npc id; null/qualified entries are NOT aggressive). */
    fun isAggressive(npc: WorldNpc): Boolean =
        (SeedData.wikiCombat(npc.gameId)?.aggressive ?: NpcBestiary.aggressive(npc.gameId)) == true

    private class AggroTarget(val player: WorldPlayer) {
        // NOT Int.MIN_VALUE: `clock - lastPathTick` must not overflow (it did, and the
        var lastPathTick = -1_000_000
        var lastPathX = Int.MIN_VALUE
        var lastPathY = Int.MIN_VALUE
    }

    /** npc -> the player it is hunting. Identity-keyed like [engagements]. */
    private val aggroTargets = IdentityHashMap<WorldNpc, AggroTarget>()

    /** npc -> (player -> tick the player first came within the radius), for tolerance. */
    private val aggroFirstSeen = IdentityHashMap<WorldNpc, IdentityHashMap<WorldPlayer, Int>>()

    private var aggroAcquired = 0
    private var aggroDropped = 0
    private var aggroSwings = 0
    private var aggroTolerated = 0

    fun aggroAcquiredCount(): Int = aggroAcquired
    fun aggroDroppedCount(): Int = aggroDropped
    fun aggroSwingCount(): Int = aggroSwings
    fun aggroToleratedCount(): Int = aggroTolerated
    fun aggroTargetOf(npc: WorldNpc): WorldPlayer? = aggroTargets[npc]?.player
    fun aggroTargetCount(): Int = aggroTargets.size

    /** The player's combat level for the rule: the same derivation the world shows the client. */
    private fun combatLevelOf(player: WorldPlayer): Int =
        com.opennxt.model.world.PlayerCombatStats.combatLevel(player)

    private fun aggressionTick(npcs: WorldNpcs, players: List<WorldPlayer>) {
        val radius = aggroRadius ?: run {
            if (aggroTargets.isNotEmpty()) { aggroTargets.clear(); aggroFirstSeen.clear() }
            return
        }
        if (players.isEmpty()) { aggroTargets.clear(); aggroFirstSeen.clear(); return }
        // MEDIUM: otherwise every logged-out WorldPlayer that ever stood near an aggressive npc
        // is pinned for the life of the process).
        if (aggroFirstSeen.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<WorldPlayer, Boolean>())
            live.addAll(players)
            val outer = aggroFirstSeen.entries.iterator()
            while (outer.hasNext()) {
                val (_, seen) = outer.next()
                seen.keys.removeAll { it !in live }
                if (seen.isEmpty()) outer.remove()
            }
        }

        // 1. existing targets: keep, swing, walk, or drop
        val it = aggroTargets.entries.iterator()
        while (it.hasNext()) {
            val (npc, target) = it.next()
            val player = target.player
            val drop = when {
                !npc.alive -> "npc died"
                player !in players -> "player left the world"
                player.currentLifepoints <= 0 -> "player is dead"
                player in diedThisTick -> "player died this tick"
                player.entity.location.plane != npc.location.plane -> "plane changed"
                distance(npc, player) > radius * 2 -> "player beyond twice the radius"
                beyondLeash(npc) -> "npc beyond its wander leash (kited); walking home"
                tolerated(npc, player) -> "tolerance"
                else -> null
            }
            if (drop != null) {
                it.remove(); aggroDropped++
                npc.pendingUpdates.faceNothing()
                // review #6: never queue a walk on a corpse (die() reset its movement this tick)
                if (npc.alive && beyondLeash(npc)) {
                    returningHome[npc] = clock; aggroHomePathfinds++
                    npc.movement.walkTo(npc.spawn.x, npc.spawn.y, nearest = true, search = CHASE_SEARCH_CAP)
                }
                logger.info { "AGGRO npc ${npc.gameId} (${npc.name}) dropped ${player.name}: $drop [tick $clock]" }
                continue
            }
            val d = distance(npc, player)
            if (d > npcAttackRange(npc)) {
                aggroWalk(npc, target)
            } else {
                aggroSwings++
                retaliate(player, npc)
                // the killer drops its target through THIS iterator (review #2), never from retaliate()
                if (player in diedThisTick) { it.remove(); aggroDropped++ }
            }
        }

        // 1b. npcs walking home: released when the homeward queue is empty and they are inside the
        // leash; re-pathed (counted) when the queue emptied beyond it (audit T-03).
        if (returningHome.isNotEmpty()) {
            val home = returningHome.entries.iterator()
            while (home.hasNext()) {
                val entry = home.next()
                val npc = entry.key
                if (!npc.alive) { home.remove(); continue }
                if (npc.movement.hasSteps) continue
                if (beyondLeash(npc)) {
                    // review #7: bounded - re-path on the aggro cooldown, give up after AGGRO_HOME_ATTEMPTS
                    if (clock - entry.value < AGGRO_REPATH_TICKS) continue
                    if (aggroHomeAttempts.merge(npc, 1, Int::plus)!! > AGGRO_HOME_ATTEMPTS) {
                        home.remove(); aggroHomeAttempts.remove(npc)
                        logger.info { "AGGRO npc ${npc.gameId} (${npc.name}) has no route home inside its leash after $AGGRO_HOME_ATTEMPTS attempts; released where it stands [tick $clock]" }
                        continue
                    }
                    entry.setValue(clock); aggroHomePathfinds++
                    npc.movement.walkTo(npc.spawn.x, npc.spawn.y, nearest = true, search = CHASE_SEARCH_CAP)
                } else { home.remove(); aggroHomeAttempts.remove(npc) }
            }
        }

        // 2. acquisition: an aggressive, alive, fightable npc with no target, a player within the radius
        for (npc in npcs.all()) {
            if (!npc.alive || aggroTargets.containsKey(npc) || npc.lifepoints == null) continue
            if (returningHome.containsKey(npc)) continue
            val level = npc.combat?.combatLevel ?: 0
            if (level <= 0) continue
            var candidate: WorldPlayer? = null
            var best = Int.MAX_VALUE
            val npcRadius = aggroRadiusFor(npc, radius)
            for (player in players) {
                if (player.currentLifepoints <= 0) continue
                if (player in diedThisTick) continue   // audit C-03: respawned this tick, not a target
                if (player.entity.location.plane != npc.location.plane) continue
                val d = distance(npc, player)
                if (d > npcRadius) {
                    if (d > AGGRO_TOLERANCE_RESET_DISTANCE) aggroFirstSeen[npc]?.remove(player)
                    continue
                }
                if (!isAggressive(npc)) continue            // cheap tests first; the wiki lookup last
                aggroFirstSeen.getOrPut(npc) { IdentityHashMap() }.putIfAbsent(player, clock)
                if (tolerated(npc, player)) continue
                if (!aggressiveTowards(level, combatLevelOf(player))) continue
                if (d < best) { best = d; candidate = player }
            }
            val chosen = candidate ?: continue
            aggroTargets[npc] = AggroTarget(chosen)
            aggroAcquired++
            chosen.entity.index.let { idx -> if (idx >= 0) npc.pendingUpdates.facePlayer(idx) }
            logger.info {
                "AGGRO npc ${npc.gameId} (${npc.name}, level $level) targets ${chosen.name} " +
                    "(level ${combatLevelOf(chosen)}) at distance $best [tick $clock, radius $radius]"
            }
        }
    }

    private fun beyondLeash(npc: WorldNpc): Boolean =
        maxOf(Math.abs(npc.location.x - npc.spawn.x), Math.abs(npc.location.y - npc.spawn.y)) > com.opennxt.model.world.NpcWander.LEASH_RADIUS

    fun aggroFirstSeenCount(): Int = aggroFirstSeen.values.sumOf { it.size }

    private fun distance(npc: WorldNpc, player: WorldPlayer): Int =
        footprintDistance(npc, player.entity.location.x, player.entity.location.y)

    fun footprintDistance(npc: WorldNpc, px: Int, pz: Int): Int {
        val loc = npc.location
        val size = (npc.combat?.size ?: 1).coerceAtLeast(1)
        val dx = maxOf(loc.x - px, px - (loc.x + size - 1), 0)
        val dz = maxOf(loc.y - pz, pz - (loc.y + size - 1), 0)
        return maxOf(dx, dz)
    }

    private fun tolerated(npc: WorldNpc, player: WorldPlayer): Boolean {
        val first = aggroFirstSeen[npc]?.get(player) ?: return false
        val docile = clock - first >= AGGRO_TOLERANCE_TICKS
        if (docile) aggroTolerated++
        return docile
    }

    /**
     * How far npc [npc] can swing from, in tiles (Chebyshev). Every npc is adjacent-only for
     * now; per-npc ranges are not modelled, so a ranged npc closes to melee distance first.
     */
    fun npcAttackRange(npc: WorldNpc): Int = ATTACK_RANGE

    /** The radius an aggressive npc scans. */
    fun aggroRadiusFor(npc: WorldNpc, switchRadius: Int): Int = switchRadius

    /**
     * Npcs walking home after a leash drop (audit T-03). While an npc is here the
     * acquisition pass leaves it alone, so a player parked just past the leash no
     * longer makes it drop-and-reacquire every tick; it is released once its
     * homeward queue is empty and it is back inside the leash.
     */
    private val returningHome = IdentityHashMap<WorldNpc, Int>()   // npc -> clock of its last walk-home pathfind
    fun returningHomeCount(): Int = returningHome.size

    /** Walk-home re-paths after which a stranded npc (no route back inside its leash) is released (review #7). */
    const val AGGRO_HOME_ATTEMPTS = 5

    /** Walk-home pathfinds issued after a leash drop (counted; they used to be invisible). */
    private var aggroHomePathfinds = 0
    private val aggroHomeAttempts = IdentityHashMap<WorldNpc, Int>()
    fun aggroHomePathfindCount(): Int = aggroHomePathfinds

    private var aggroPathfinds = 0
    private var aggroPathFailures = 0
    fun aggroPathfindCount(): Int = aggroPathfinds
    fun aggroPathFailureCount(): Int = aggroPathFailures

    /**
     * One step of pursuit, the mirror of [chase]: the tiles ADJACENT TO THE PLAYER
     * (a player occupies its own tile, so the target tile itself never routes),
     * nearest to the npc first, at most [CHASE_CANDIDATES] pathfinder runs, each
     * `nearest = false` so a tile routes or is skipped. Re-path only when the
     * npc's queue is dry, and never more often than [AGGRO_REPATH_TICKS].
     */
    private fun aggroWalk(npc: WorldNpc, target: AggroTarget) {
        val loc = target.player.entity.location
        if (npc.movement.hasSteps) return
        if (clock - target.lastPathTick < AGGRO_REPATH_TICKS) return
        target.lastPathTick = clock
        target.lastPathX = loc.x
        target.lastPathY = loc.y
        val from = npc.location
        val candidates = ArrayList<IntArray>()
        val leash = com.opennxt.model.world.NpcWander.LEASH_RADIUS
        for (dx in -ATTACK_RANGE..ATTACK_RANGE) for (dy in -ATTACK_RANGE..ATTACK_RANGE) {
            if (dx == 0 && dy == 0) continue
            val cx = loc.x + dx; val cy = loc.y + dy
            // audit T-03: never path to a tile past the wander leash - the drop rule would
            // only send the npc straight back, one pathfind each way, forever.
            if (Math.abs(cx - npc.spawn.x) > leash || Math.abs(cy - npc.spawn.y) > leash) continue
            candidates.add(intArrayOf(cx, cy))
        }
        candidates.sortBy { maxOf(Math.abs(it[0] - from.x), Math.abs(it[1] - from.y)) }
        var tried = 0
        for (c in candidates) {
            if (tried >= CHASE_CANDIDATES) break
            tried++
            aggroPathfinds++
            if (npc.movement.walkTo(c[0], c[1], nearest = false, search = CHASE_SEARCH_CAP)) return
        }
        aggroPathFailures++
    }

    // ------------------------------------------------------- the chase

    /**
     * Walks [player] toward [npc]. Returns false when the engagement was DROPPED
     * (target too far), true in every other case including "did nothing".
     *
     * The budget, all of it, in one place:
     *
     *  - nothing at all unless [chaseEnabled];
     *  - drop the fight past [CHASE_GIVE_UP_DISTANCE];
     *  - re-path only when the target has MOVED since the last path or the
     *    player's queue has run dry - a player already walking to the right tile
     *    needs no second opinion;
     *  - and never more often than [CHASE_REPATH_COOLDOWN_TICKS] per engagement;
     *  - at most [CHASE_CANDIDATES] pathfinder runs when it does re-path, over a
     *    window sized to the distance and capped at [CHASE_SEARCH_CAP].
     *
     * Every run's node count is accumulated into [chaseNodeCount] from
     * [com.opennxt.model.map.PathFinder.lastNodesVisited], so the cost of this
     * function is a number and not a paragraph.
     */
    private fun chase(player: WorldPlayer, engagement: Engagement, npc: WorldNpc, distance: Int, range: Int = ATTACK_RANGE): Boolean {
        if (distance > CHASE_GIVE_UP_DISTANCE) {
            chaseGiveUps++
            logger.info {
                "COMBAT ${player.name} disengaged from ${npc.name ?: "npc${npc.gameId}"}: $distance tiles is " +
                    "past CHASE_GIVE_UP_DISTANCE ($CHASE_GIVE_UP_DISTANCE), which is AUTHORED - see " +
                    "PlayerCombat.CHASE_GIVE_UP_DISTANCE"
            }
            disengage(player)
            return false
        }
        if (!chaseEnabled) return true

        val target = npc.location
        val targetMoved = target.x != engagement.pathedTargetX || target.y != engagement.pathedTargetZ
        val idle = !player.entity.movement.hasSteps
        if (!targetMoved && !idle) return true
        if (engagement.lastRepathAt != Int.MIN_VALUE &&
            clock - engagement.lastRepathAt < CHASE_REPATH_COOLDOWN_TICKS
        ) return true

        engagement.lastRepathAt = clock
        engagement.pathedTargetX = target.x
        engagement.pathedTargetZ = target.y
        chaseRepaths++

        val from = player.entity.location
        // The window must be at least the distance for the goal to be inside a
        // box centred between the two; +2 leaves room to path AROUND one tile of
        // obstruction rather than only through it.
        val window = (2 * distance + 2).coerceIn(8, CHASE_SEARCH_CAP)
        val candidates = attackTilesFor(npc, from.x, from.y, range)
        var tried = 0
        for (c in candidates) {
            if (tried >= CHASE_CANDIDATES) break
            tried++
            // nearest = false, exactly as MoveGameClickHandler.walkToLoc does per
            // candidate: this tile routes or it is skipped. Best-effort mode would
            // walk the player to some tile no candidate asked for and report
            // success, which is the bug LocInteraction was written to kill.
            val queued = player.entity.movement.walkTo(c[0], c[1], nearest = false, search = window)
            chasePathfinds++
            chaseNodes += com.opennxt.model.map.PathFinder.lastNodesVisited()
            if (queued) {
                engagement.chaseDestX = c[0]
                engagement.chaseDestZ = c[1]
                return true
            }
        }
        // Nothing routed. Not an error and not a give-up: the npc is wandering
        // and will very likely step somewhere reachable within a few ticks. The
        // cooldown stops this from becoming a per-tick flood.
        engagement.chaseDestX = Int.MIN_VALUE
        engagement.chaseDestZ = Int.MIN_VALUE
        return true
    }

    // ------------------------------------------------------- retaliation

    /**
     * `-Dopennxt.experiment.combat.retaliate=false` stops npcs hitting back.
     * Default ON, under the master combat gate.
     *
     * ## WHY THIS IS ALLOWED TO BE ON BY DEFAULT
     *
     * It adds no new model. [com.opennxt.model.world.NpcRetaliation] already
     * existed, already fought back at the RECONSTRUCTED [CombatFormulas], and
     * every refusal it can make is typed and names its missing param. What it
     * could not do was reach a real player: it took a
     * [com.opennxt.model.world.HeadlessPlayer], and [WorldPlayer] had no
     * lifepoints. Both sides of that are now fixed in the smallest way available
     * - [com.opennxt.model.world.CombatDefender], four members - so this is
     * WIRING, which is what this file is for.
     *
     * The numbers it fights with are the npc's own cache params (accuracy, damage
     * x10, attack speed 14) against the player's derived Defence, and the
     * formulas combining them are RECONSTRUCTED and say so at their own
     * declarations. Nothing here scales or reinterprets them.
     */
    val retaliationEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.retaliate") != "false"

    /**
     * The retaliation engine. ONE instance, ticked once per [tick], so an npc's
     * attack-speed gate is on the same clock no matter which player it is
     * fighting.
     */
    private var retaliation = com.opennxt.model.world.NpcRetaliation()

    /**
     * Seed for the retaliation rolls, and it is a SEPARATE generator from
     * [random] on purpose.
     *
     * [DROP_SEED]'s whole claim is that a session's kills are replayable. Drawing
     * retaliation's hit-chance and damage rolls from the same [Random] would have
     * quietly broken that: the number of draws before a kill would then depend on
     * how many times an npc happened to swing back, so the SAME fight with
     * `-Dopennxt.experiment.combat.retaliate=false` would roll different drops.
     * Two independent streams keep the two properties independent.
     *
     * 0x5254414c is the ASCII of "RTAL" and means nothing else.
     */
    const val RETALIATION_SEED = 0x5254414cL

    private val retaliationRandom = Random(bootSeed xor RETALIATION_SEED)

    /** Retaliation hits landed on players since [reset]. */
    private var retaliationHits: Int = 0

    /** Damage those hits did, in lifepoints. */
    private var retaliationDamage: Int = 0

    /** Player deaths since [reset]. */
    private var playerDeaths: Int = 0

    /** The most recent refusal reason, so a check can print WHY nothing happened. */
    private var lastRetaliationRefusal: String? = null

    fun retaliationHitCount(): Int = retaliationHits
    fun retaliationDamageTotal(): Int = retaliationDamage
    fun playerDeathCount(): Int = playerDeaths
    fun lastRetaliationRefusal(): String? = lastRetaliationRefusal

    /**
     * One retaliation attempt by [npc] against [player], and the consequences of
     * a player death.
     *
     * The attempt itself is [com.opennxt.model.world.NpcRetaliation.retaliate] -
     * not reimplemented, not wrapped in a second hit chance. This function owns
     * only what a WORLD player needs that a headless one does not: the hit splat
     * on the player's own update queue, and ending the fight when the player
     * dies (the teleport itself is [WorldPlayer.takeDamage]'s, so death is one
     * behaviour with one owner).
     *
     * Contained: a retaliation that throws must not take the rest of the combat
     * phase - or the player's own hit, already applied - down with it.
     */
    /** Retaliation attempts (calls into the engine, any outcome) since [reset]; audit C-02's pin. */
    private var retaliationAttempts = 0
    fun retaliationAttemptCount(): Int = retaliationAttempts

    private var pursuits = 0
    fun pursuitCount(): Int = pursuits

    /**
     * The player's reach in tiles: the worn weapon's attack-range param (13) when it has one,
     * else [ATTACK_RANGE]. A ranged/magic weapon WITHOUT the param falls back to adjacency and
     * says so once per item - the cache did not state a range, and adjacency is the one reach
     * that is always meaningful.
     */
    fun playerAttackRange(player: WorldPlayer): Int {
        val weapon = player.wornWeapon ?: return ATTACK_RANGE
        val range = weapon.attackRange
        if (range == null && loggedRangeless.add(weapon.definition.id)) {
            logger.warn { "COMBAT weapon ${weapon.definition.id} (${weapon.definition.name}) is ${weapon.style ?: "of no style"} and carries no attack-range param (13); the engagement stands adjacent and resolveHit REFUSES its swings (a non-melee weapon with no stated reach). [tick $clock]" }
        }
        return range ?: ATTACK_RANGE
    }
    private val loggedRangeless = HashSet<Int>()

    /** The style the player's next swing is in: the weapon's, or melee unarmed. */
    fun playerStyle(player: WorldPlayer): CombatStyle = player.wornWeapon?.style ?: CombatStyle.MELEE

    /**
     * Records the destination of the walk the Attack CLICK queued (OpNpcHandler) as this
     * engagement's own chase destination, so that the in-range "stop walking, but only if the
     * this an archer clicking a chicken five tiles away fired once and then walked all the way
     * in, because the click's walk was nobody's).
     */
    fun recordClickWalk(player: WorldPlayer, destX: Int, destZ: Int) {
        val e = engagements[player] ?: return
        e.chaseDestX = destX; e.chaseDestZ = destZ
    }

    /** The nearest tile within [range] of [npc]'s footprint from ([fromX],[fromZ]), or null when none routes. */
    fun nearestAttackTile(npc: WorldNpc, fromX: Int, fromZ: Int, range: Int): IntArray? =
        attackTilesFor(npc, fromX, fromZ, range).firstOrNull()

    private fun retaliate(player: WorldPlayer, npc: WorldNpc) {
        if (!retaliationEnabled) return
        retaliationAttempts++
        try {
            val before = player.currentLifepoints
            when (val outcome = retaliation.retaliate(npc, player, retaliationRandom)) {
                is com.opennxt.model.world.RetaliationOutcome.Refused -> {
                    lastRetaliationRefusal = outcome.reason
                }
                is com.opennxt.model.world.RetaliationOutcome.NotDue -> {}
                is com.opennxt.model.world.RetaliationOutcome.Missed -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                }
                is com.opennxt.model.world.RetaliationOutcome.Hit -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                    onRetaliationDamage(player, npc, before - player.currentLifepoints, npcAnimId ?: -1)
                }
                is com.opennxt.model.world.RetaliationOutcome.KilledPlayer -> {
                    val npcAnimId = npcAttackAnimation(npc)
                    if (npcAnimId != null) npc.pendingUpdates.animate(npcAnimId)
                    // takeDamage already restored lifepoints and queued the
                    // respawn teleport, so `before` is the whole of the damage.
                    onRetaliationDamage(player, npc, before)
                    playerDeaths++
                    diedThisTick.add(player)   // audit C-03: no second npc may hit the respawned player this tick
                    // aggressionTick's own iteration over aggroTargets and threw ConcurrentModification
                    // on the next entry); aggressionTick drops the killer's target itself, after the swing.
                    logger.info {
                        "COMBAT ${player.name} was KILLED by ${npc.name ?: "npc${npc.gameId}"} " +
                            "(${npc.gameId}) at (${outcome.death.diedAt.x},${outcome.death.diedAt.y}) - " +
                            "respawned at (${outcome.death.respawnedAt.x},${outcome.death.respawnedAt.y}) " +
                            "with ${outcome.death.lifepointsRestored} lp. $PROVENANCE_SHORT"
                    }
                    disengage(player)
                }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Retaliation by npc ${npc.gameId} against ${player.name} failed; contained" }
        }
    }

    /**
     * The sequence an npc plays when it swings. Precedence:
     * 1. the operator's struct slot (`-Dopennxt.experiment.combat.anim.npcslot`, the 452 struct npcs);
     */
    fun npcAttackAnimation(npc: WorldNpc): Int? =
        WeaponAnimations.npcSelected(npc.gameId)
            ?: NpcBestiary.attackAnimation(npc.gameId)
            ?: WeaponAnimations.npcSequenceParams(npc.gameId)[2914]
            ?: WeaponAnimations.npcSequenceParams(npc.gameId).values.firstOrNull()

    /** Counts the damage and puts a splat on the PLAYER, the mirror of the npc's. */
    private fun onRetaliationDamage(player: WorldPlayer, npc: WorldNpc, amount: Int, animId: Int = 422) {
        if (amount <= 0) return
        retaliationHits++
        retaliationDamage += amount
        // The player-side splat, through the wire-verified HITS block
        // (PlayerHitsBlock, whose every field is read out of the binary). The
        // TYPE is the same AUTHORED choice as the npc's - see [DEFAULT_HITMARK];
        // `splattype=none` silences both sides, which is what makes the two
        val type = splatType ?: return
        if (!faceEnabled) return
        val seqDuration = com.opennxt.model.definitions.SeqDefinitions.get(animId)?.durationInTicks ?: 1
        val splatDelay = (seqDuration / 2).coerceAtLeast(1) * 30
        PlayerUpdates.hit(player.entity, com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock.Hit(type, amount, splatDelay))
    }

    val RETALIATION_PROVENANCE =
        "WIRING, not a new model: NpcRetaliation (already verified) now reaches a real WorldPlayer through " +
            "CombatDefender. Npc side = its own cache params (accuracy, damage x10 / 10, attack speed 14). " +
            "Player side = RECONSTRUCTED: max lifepoints are Constitution level x " +
            "${Lifepoints.PER_CONSTITUTION_LEVEL} (public RS3 knowledge, measured NOT to be in this cache), " +
            "defence is PlayerCombatStats.defence over the Defence level with NO worn armour modelled, " +
            "affinity is the wiki's armourless 55, and the Lumbridge respawn tile " +
            "(${com.opennxt.model.world.HeadlessPlayer.LUMBRIDGE_RESPAWN_X}," +
            "${com.opennxt.model.world.HeadlessPlayer.LUMBRIDGE_RESPAWN_Y}) is the tile this codebase " +
            "already logs players in on. The player's lifepoints are NOT on the wire: see " +
            "WorldPlayer.lifepointsUiStatus for why no packet was invented to show them."

    /**
     * Experience per lifepoint of damage, in TENTHS of an xp point.
     */
    const val XP_TENTHS_PER_DAMAGE = com.opennxt.model.world.HeadlessPlayer.COMBAT_XP_TENTHS_PER_DAMAGE

    /** Constitution's share, per 100 lifepoints, in tenths. Same source. */
    const val CONSTITUTION_XP_TENTHS_PER_100 =
        com.opennxt.model.world.HeadlessPlayer.CONSTITUTION_XP_TENTHS_PER_100_DAMAGE

    /**
     * The style skill melee xp lands in.
     *
     * RECONSTRUCTED simplification, and [com.opennxt.model.world.HeadlessPlayer.grantCombatXp]
     * makes the same one for the same stated reason: real RS3 splits melee xp by
     * the chosen combat mode (Attack / Strength / Defence / Balanced) and combat
     * modes are not modelled anywhere in this server. One governing skill per
     * style, said out loud.
     */
    val XP_STAT = com.opennxt.api.stat.Stat.ATTACK

    val XP_PROVENANCE =
        CombatXp.PROVENANCE + " Into the weapon's style stat (Attack / Ranged / Magic; $XP_STAT unarmed) plus CONSTITUTION. earlier: this loop briefly carried an " +
            "AUTHORED 4.0 xp/lifepoint. GROUNDED by contrast: the xp->level curve the award feeds (verified by " +
            ")."

    /** Total xp this loop has awarded since [reset]. Observable so a check can assert it. */
    private var xpAwarded: Double = 0.0

    fun experienceAwarded(): Double = xpAwarded

    /**
     * Adds the style and Constitution shares of [damage] to [player]'s stats.
     */
    fun xpStatFor(style: CombatStyle): com.opennxt.api.stat.Stat = when (style) {
        CombatStyle.MELEE -> XP_STAT
        CombatStyle.RANGED -> com.opennxt.api.stat.Stat.RANGED
        CombatStyle.MAGIC -> com.opennxt.api.stat.Stat.MAGIC
    }

    private fun awardKillExperience(death: DeathResult, npc: WorldNpc, players: List<WorldPlayer>) {
        if (death.damageLedger.isEmpty()) return
        val byName = HashMap<String, WorldPlayer>()
        for (p in players) byName[p.name] = p
        for ((name, damage) in death.damageLedger) {
            val player = byName[name] ?: continue
            val style = death.styleLedger[name] ?: CombatStyle.MELEE
            val award = CombatXp.splitAward(npc.gameId, damage, style)
            try {
                for ((stat, xp) in award) {
                    val levels = player.stats.addExperience(stat, xp)
                    xpAwarded += xp
                    if (levels > 0) logger.info {
                        "COMBAT ${player.name} gained $levels ${stat.name} level(s) -> ${player.stats.getLevel(stat)} " +
                            "(${player.stats[stat].experience} xp) on the kill of ${npc.name ?: "npc${npc.gameId}"}. $PROVENANCE_SHORT"
                    }
                }
                killAwards++
            } catch (t: Throwable) {
                logger.error(t) { "Kill award to ${player.name} failed; the kill still stands" }
            }
        }
    }
    /** Kill awards paid since boot (one per attacker per kill). */
    var killAwards: Int = 0
        private set

    @Suppress("unused")   // kept for the per-hit path's history; the kill award above is the live one
    private fun awardExperience(player: WorldPlayer, damage: Int, npc: WorldNpc, style: CombatStyle = CombatStyle.MELEE) {
 //: CombatXp owns the rate - the wiki's per-npc experience / lifepoints
        // for this npc id, else its 0.05 base - with Constitution at a third. The stat
        // container takes fractional xp, so nothing is rounded here.
        val styleXp = CombatXp.styleXp(npc.gameId, damage)
        val constitutionXp = CombatXp.constitutionXp(styleXp)
        val stat = xpStatFor(style)
        try {
            val levels = player.stats.addExperience(stat, styleXp)
            player.stats.addExperience(com.opennxt.api.stat.Stat.CONSTITUTION, constitutionXp)
            xpAwarded += styleXp + constitutionXp
            if (levels > 0) {
                logger.info {
                    "COMBAT ${player.name} gained $levels ${stat.name} level(s) -> " +
                        "${player.stats.getLevel(stat)} (${player.stats[stat].experience} xp). $PROVENANCE_SHORT"
                }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Awarding $styleXp xp to ${player.name} failed; the hit still landed" }
        }
    }

    /** [DEMO_HIT], or the operator's override. */
    fun damage(): Int =
        System.getProperty("opennxt.experiment.combat.hit")?.trim()?.toIntOrNull() ?: DEMO_HIT

    /** [ATTACK_INTERVAL_TICKS], or the operator's override. Never below 1. */
    fun interval(): Int =
        (System.getProperty("opennxt.experiment.combat.interval")?.trim()?.toIntOrNull()
            ?: ATTACK_INTERVAL_TICKS).coerceAtLeast(1)

    val PROVENANCE =
        "AUTHORED: damage per hit ($DEMO_HIT) and the player's attack interval ($ATTACK_INTERVAL_TICKS ticks). " +
            "No combat formula is in this cache and this server has no equipment model, so neither is derived. " +
            "BOUNDED, though: $DEMO_HIT x10 = ${DEMO_HIT * 10} is exactly item $BOUNDING_WEAPON_ITEM " +
            "(Bronze sword) param 641, i.e. the max hit the cache's own tier-1 weapon carries, so the number " +
            "is no larger than a measured one -- but a max applied every hit is not a damage roll. The " +
            "interval's MAGNITUDE is borrowed from the modal npc param-14 value (4, on 5096 of 8718 npcs; " +
            "this string said '5105 of 8923' previously, which counted param ENTRIES rather than " +
            "npc ids - 208 npcs list param 14 twice); a " +
            "player's swing timer is not an npc's. Note: \"no player " +
            "weapon exists in this cache\", which is FALSE -- 9,217 items carry a (requirement skill, level) " +
            "pair and the melee weapons carry accuracy (3267) and damage (641) params, all verified by " +
            " GROUNDED 5. What does not exist is an equipped-weapon model in THIS SERVER. " +
 // 7914 -> 7917: the served-vintage migration added 3 attackers. The
            // INVARIANT is what this string is really claiming and it still holds exactly -
            // 'Attack' npcs == attackCursor npcs, 7917/7917. Only the population moved.
            "GROUNDED by contrast: the 'Attack' action string (7917 npcs, exactly the attackCursor set), " +
            "lifepoints (SeedData provenance), the drop table, and the hitmark id's existence " +
            "($HITMARK_PROVENANCE). $XP_PROVENANCE"

    /**
     * The one-line form, for PER-EVENT log lines.
     *
     * ## Why this exists
     *
     * [PROVENANCE] is ~1,500 characters and it was being appended to every
     * engagement, every xp award and every retaliation death. A four-kill
     * session in logs/server-20260817-190755 emitted it six times -- about 9 KB
     * of byte-identical prose -- and the per-event facts that actually differ
     * (which npc, which slot, what lifepoints) were buried inside it.
     *
     * That is not a cosmetic complaint. It cost real time: reading that log I
     * matched `-Dopennxt.experiment.combat=true` and
     * `-Dopennxt.experiment.combat.demospawn=off` out of the ADVICE TEXT inside
     * these strings and briefly concluded that combat had run without its flag
     * and that something was forcing the demo spawn off. Both were false. A log
     * line that repeats a paragraph of documentation is a log line you skim, and
     * a skimmed log is how a wrong claim gets made.
     *
     * So: the full [PROVENANCE] is printed ONCE, at boot, by [logProvenanceOnce].
     * Per-event lines carry this instead -- short, and still explicit that the
     * two numbers are ours, so no single line can be read as claiming the damage
     * or the interval was measured.
     */
    const val PROVENANCE_SHORT = "[damage + interval AUTHORED - full provenance at boot]"

 // ------------------------------------------------- FLAG STATE

    /**
     * One `-D` switch that changes what combat does, plus everything a reader
     * needs to establish its value FROM THE LOG ALONE.
     *
     * @param property the full system-property name, as an operator types it
     * @param default the [resolve] result when nothing is set. Asserted.
     * @param group which line of the block it is printed on
     * @param meaning one clause, for the operator
     * @param resolve reads the LIVE value. A getter and not a recorded value on
     */
    data class CombatFlag(
        val property: String,
        val default: String,
        val group: String,
        val meaning: String,
        val resolve: () -> String
    )

    /** ON/OFF, never true/false. See [CombatFlag]. */
    private fun onOff(b: Boolean): String = if (b) "ON" else "OFF"

    /** A switch whose owner sessions `!= "false"`, i.e. default ON. */
    private fun defaultOnProp(name: String): String = onOff(System.getProperty(name) != "false")

    /** A switch whose owner sessions `== "true"`, i.e. default OFF. */
    private fun defaultOffProp(name: String): String = onOff(System.getProperty(name) == "true")

    /**
     * Every `-D` switch that changes what combat does, in the order the block
     * prints them.
     *
     * The list is deliberately WIDER than `opennxt.experiment.combat.*`. Three of
     * the four things that can make a live combat session do nothing are not
     * spelled `combat`:
     *
     * - `opennxt.experiment.npcs`, `.npcs.spawns`, `.npcs.wander` and
     * `.combat.demospawn` decide whether there is anything on screen to click;
     * - `opennxt.experiment.npcs.extended` carries the HITS and FACE_ENTITY
     * blocks this loop queues - with it off the damage still lands and nothing
     * is drawn;
     * - `opennxt.experiment.equip` decides whether a weapon can exist, and
     * [realDamageEnabled] REFUSES rather than falling back, so realdamage
     * without equip is a fight that never ends.
     */
    val FLAGS: List<CombatFlag> = listOf(
        CombatFlag(
            "opennxt.experiment.combat", "OFF", "loop",
            "master gate; OFF means tick() returns on its first line and no npc ever takes damage"
        ) { onOff(enabled) },
        CombatFlag(
            "opennxt.experiment.combat.realdamage", "OFF", "loop",
            "ON replaces the authored flat hit with PlayerCombatStats/CombatFormulas and REFUSES " +
                "(zero damage, no fallback) when the model cannot compute - see REAL_DAMAGE_PROVENANCE"
        ) { onOff(realDamageEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.chase", "ON", "loop",
            "ON re-paths the player onto a moving target; OFF reproduces the measured permanent stall"
        ) { onOff(chaseEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.retaliate", "ON", "loop",
            "ON lets npcs hit back, which is the only thing that can kill the player"
        ) { onOff(retaliationEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.face", "ON", "loop",
            "ON queues both face-entity blocks; OFF leaves the splats and drops the turning"
        ) { onOff(faceEnabled) },
        CombatFlag(
            "opennxt.experiment.combat.hit", DEMO_HIT.toString(), "loop",
            "lifepoints per landed hit on the flat path; AUTHORED, and unused while realdamage is ON"
        ) { damage().toString() },
        CombatFlag(
            "opennxt.experiment.combat.interval", ATTACK_INTERVAL_TICKS.toString(), "loop",
            "ticks between swings; an explicit value wins in BOTH arms, otherwise realdamage=ON takes " +
                "the weapon's own cache param 14 per player and this number is not what swings"
        ) { interval().toString() },
        CombatFlag(
            "opennxt.experiment.combat.splattype", DEFAULT_HITMARK.toString(), "loop",
            "hitmark definition id for the HITS block; NOSPLAT queues no block at all, which is what " +
                "put zero splats on the wire for a whole live session"
        ) { splatType?.toString() ?: "NOSPLAT" },
        CombatFlag(
            "opennxt.experiment.combat.deathlinger", "0", "loop",
            "extra ticks a corpse stays transmissible beyond its death tick; AUTHORED above 0"
        ) { NpcDeathTransmission.lingerTicks.toString() },
        CombatFlag(
            "opennxt.experiment.combat.anim.npcslot", "UNSET", "loop",
            "param id the npc attack-animation experiment selects; UNSET selects no sequence and never falls back"
        ) { WeaponAnimations.npcSlotConfigured()?.toString() ?: "UNSET" },
 //: the phantom is real. Documented since 2026-08 and read by nothing until
        // NpcAnimObservations - see its KDoc for what means and where the rows come from.
        CombatFlag(
            NpcAnimObservations.DEATH_SWITCH, "", "loop",
            "npc death animation: = the observed sequence for the npc or its animation group " +
                "(data/seed/npc_anims_observed.tsv), off = none, <seq> = that sequence for every npc"
        ) { NpcAnimObservations.mode },
        CombatFlag(
            "opennxt.experiment.combat.anim.weapon", "UNSET", "loop",
            "item:param for the player weapon-animation experiment; UNSET selects no sequence"
        ) { WeaponAnimations.configured()?.let { "${it.first}:${it.second}" } ?: "UNSET" },

        CombatFlag(
            "opennxt.experiment.combat.demospawn",
            "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_X}," +
                "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_Y}," +
                "${com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_PLANE}",
            "target",
            "tile of the ONLY fightable entity within NPC_INFO reach of the login tile; NOSPAWN removes it " +
                "and leaves the operator with nothing to attack (0 of 1388 cache spawns are within 40 tiles)"
        ) {
            WorldNpcs.combatDemoSpawn?.let { "${it.x},${it.y},${it.plane}" } ?: "NOSPAWN"
        },
        CombatFlag(
            "opennxt.experiment.combat.demospawn.wander", "ON", "target",
            "ON makes the demo npc behave like a cache spawn; OFF freezes it and hides chase defects"
        ) { onOff(WorldNpcs.combatDemoWander) },
        CombatFlag(
            "opennxt.experiment.npcs.spawns", "ON", "target",
            "OFF instantiates no CACHE npc at all; the demo spawn is a separate switch"
        ) { defaultOnProp("opennxt.experiment.npcs.spawns") },
        CombatFlag(
            "opennxt.experiment.npcs.wander", "ON", "target",
            "OFF freezes every npc where it stands"
        ) { defaultOnProp("opennxt.experiment.npcs.wander") },
 //: npcs that start fights. The VALUE is the radius; OFF is the default
        // because the radius is the one number nothing publishes - see the AGGRESSION section.
        CombatFlag(
            AGGRO_SWITCH, "OFF", "target",
            "npc aggression radius in tiles; OFF = no npc attacks first. Which npcs: the wiki's aggressive flag per id; " +
                "level rule and 10-minute tolerance from runescape.wiki/w/Aggressiveness"
        ) { aggroRadius?.toString() ?: "OFF" },
        CombatFlag(
            "opennxt.experiment.npcs", "ON", "target",
            "OFF suppresses the NPC_INFO packet outright - nothing is on screen to click"
        ) { defaultOnProp("opennxt.experiment.npcs") },
        CombatFlag(
            "opennxt.experiment.seed.variantDisambiguation", "ON", "target",
            "the lifepoints layer; OFF changes how much damage a target takes to kill"
        ) { onOff(SeedData.variantDisambiguationEnabled) },

        CombatFlag(
            "opennxt.experiment.npcs.extended", "ON", "visible",
            "OFF drops the extended-info blocks that CARRY the splat and the facing - damage still lands, " +
                "nothing is drawn, and the fight looks like it is not happening"
        ) { defaultOnProp("opennxt.experiment.npcs.extended") },
        CombatFlag(
            "opennxt.experiment.npcs.block.hits", "ON", "visible",
            "OFF kills the ONE block this loop queues to draw a damage number, and nothing else"
        ) { defaultOnProp("opennxt.experiment.npcs.block.hits") },
        CombatFlag(
            "opennxt.experiment.npcs.block.face_entity", "ON", "visible",
            "OFF kills the npc-side turn-to-face block"
        ) { defaultOnProp("opennxt.experiment.npcs.block.face_entity") },
        CombatFlag(
            "opennxt.experiment.npcs.block.lifepoints", "ON", "visible",
            "OFF kills NPC_INFO mask bit 16, the block that carries the target panel's lifepoint " +
                "number and bar (NpcLifepointsBlock). The splats keep working without it - which is " +
                "exactly the state the operator reported."
        ) { defaultOnProp("opennxt.experiment.npcs.block.lifepoints") },
        CombatFlag(
            "opennxt.experiment.playerExtended", "ON", "visible",
            "OFF drops the PLAYER extended-info section, which carries the retaliation splat the operator " +
                "takes and the player's own turn-to-face"
        ) { defaultOnProp("opennxt.experiment.playerExtended") },
        CombatFlag(
            "opennxt.experiment.player.block.hits", "ON", "visible",
            "OFF kills the splat that shows the operator the damage an npc did to THEM"
        ) { defaultOnProp("opennxt.experiment.player.block.hits") },
        CombatFlag(
            "opennxt.experiment.player.block.face_entity", "ON", "visible",
            "OFF kills the player-side turn-to-face block"
        ) { defaultOnProp("opennxt.experiment.player.block.face_entity") },
        CombatFlag(
            "opennxt.experiment.npcs.healthbars", "ON", "visible",
            "OFF removes the npc health bar from the HITS block"
        ) { defaultOnProp("opennxt.experiment.npcs.healthbars") },
        CombatFlag(
            "opennxt.experiment.player.healthbars", "ON", "visible",
            "OFF removes the player health bar, which is the only lifepoint UI retaliation has"
        ) { defaultOnProp("opennxt.experiment.player.healthbars") },
        CombatFlag(
            "opennxt.experiment.combat.targethud", "ON", "visible",
            "OFF takes the whole target information panel off the wire - SET_TARGET, the health " +
                "percentage varc, IF_OPENSUB_ACTIVE_NPC(1488:4, 1490) and RUNCLIENTSCRIPT 82 - " +
                "without disabling combat. The NPC_INFO lifepoints block has its own switch, " +
                "opennxt.experiment.npcs.block.lifepoints."
        ) { defaultOnProp("opennxt.experiment.combat.targethud") },

        CombatFlag(
            "opennxt.experiment.equip", "OFF", "weapon",
            "OFF means no weapon can be worn, so realdamage=ON has nothing to derive from and refuses every swing"
 //. This read defaultOnProp, i.e. `!= "false"`, so with
            // nothing set the registry resolved ON while the DECLARED default one
            // line above says OFF and the actual gate,
            // PlayerInventory.equipEnabled, is `== "true"` and returns false. The
            // server therefore wrote `opennxt.experiment.equip=ON;` into its own
            // log and then refused every ::equip - the log advertised a default
            // that drift and had been red on it for six assertions.
            //
            // defaultOffProp is `== "true"`, which is equipEnabled's predicate
            // character for character, so the registry, the declaration and the
            // gate now all say the same thing. This changes NO runtime behaviour:
            // equipEnabled was and remains the only thing that decides whether a
            // weapon can be worn.
        ) { defaultOffProp("opennxt.experiment.equip") },
        CombatFlag(
            "opennxt.equip.command", "::equip", "weapon",
            "chat prefix that equips an item id"
        ) { System.getProperty("opennxt.equip.command")?.trim()?.takeIf { it.isNotEmpty() } ?: "::equip" },

        CombatFlag(
            "opennxt.experiment.sendStats", "OFF", "xp",
            "combat xp is awarded to the container either way; ON also writes UPDATE_STAT, a " +
                "949 client crash"
        ) { defaultOffProp("opennxt.experiment.sendStats") }
    )

    /** [CombatFlag.resolve], contained: a resolver that throws must not kill boot. */
    fun resolvedValueOf(flag: CombatFlag): String =
        try {
            flag.resolve()
        } catch (t: Throwable) {
            "UNRESOLVED-${t.javaClass.simpleName}"
        }

    /**
     * The literal an operator greps for: `property=VALUE;`.
     */
    fun tokenFor(flag: CombatFlag): String = "${flag.property}=${resolvedValueOf(flag)};"

    /**
     * One clause naming the FIRST reason this run will do nothing visible, or
     * that nothing is in the way.
     *
     * Ordered by what stops the session earliest, because that is the order the
     * operator hits them: no gate, then no target, then no drawing, then a
     * refusal loop. Only the first is named - a list of six would be skimmed,
     * which is the failure [PROVENANCE_SHORT] records.
     */
    fun flagEffectLine(): String {
        if (!enabled) {
            return "combat.flags[EFFECT]: MASTER GATE OFF - every Attack click will answer " +
                "\"combat is OFF\" and no npc will take damage. Nothing below this matters until it is ON."
        }
        if (WorldNpcs.combatDemoSpawn == null && System.getProperty("opennxt.experiment.npcs.spawns") == "false") {
            return "combat.flags[EFFECT]: combat is ON but there is NOTHING TO FIGHT - the demo spawn is " +
                "off and cache spawns are off."
        }
        if (WorldNpcs.combatDemoSpawn == null) {
            return "combat.flags[EFFECT]: combat is ON but the demo spawn is off, and 0 of the 1388 cache " +
                "spawns are within 40 tiles of the login tile (nearest 42, NPC_INFO reach 14), so a player " +
                "who logs in and does not walk has nothing to attack."
        }
        if (System.getProperty("opennxt.experiment.npcs") == "false" ||
            System.getProperty("opennxt.experiment.npcs.extended") == "false"
        ) {
            return "combat.flags[EFFECT]: combat is ON and damage will land, but the packet that CARRIES " +
                "the splat is suppressed, so the fight will be invisible on the client."
        }
        if (realDamageEnabled && System.getProperty("opennxt.experiment.equip") != "true") {
            return "combat.flags[EFFECT]: realdamage is ON and equip is OFF - the model cannot derive " +
                "accuracy from an unarmed player, every swing will REFUSE, zero damage will be applied and " +
                "the fight will never end. This is a refusal, not a bug; it is counted at realRefusalCount()."
        }
        if (realDamageEnabled) {
            return "combat.flags[EFFECT]: combat is ON with the reconstructed damage model; damage varies " +
                "per swing and the cadence is the equipped weapon's own cache param 14 unless pinned."
        }
        return "combat.flags[EFFECT]: combat is ON with the AUTHORED flat path - ${damage()} lifepoints " +
            "every ${interval()} tick(s). The demo target (npc ${WorldNpcs.COMBAT_DEMO_NPC}) carries " +
            "${SeedData.lifepoints(WorldNpcs.COMBAT_DEMO_NPC)?.value ?: -1} lifepoints, so a kill is " +
            "${killHitsForDemoTarget()} landed hit(s)."
    }

    /**
     * Hits to kill the demo target on the flat path, or -1 when the seed carries
     * no lifepoints for it.
     *
     * Ceiling division, stated rather than assumed: 250 lifepoints against 48 a
     * hit is 6 hits, not 5, and the last hit overkills by 38.
     */
    fun killHitsForDemoTarget(): Int {
        val lp = SeedData.lifepoints(WorldNpcs.COMBAT_DEMO_NPC)?.value ?: return -1
        val d = damage()
        if (d <= 0) return -1
        return (lp + d - 1) / d
    }

    /**
     * The compact state block: one header, one line per group, one effect line.
     *
     * Deliberately NOT the 1,500-character [PROVENANCE]. That string answers
     * "where did this number come from"; this one answers "what is switched on
     * RIGHT NOW", which is the question the operator could not answer before
     * launching the client.
     */
    fun flagStateLines(): List<String> {
        val out = ArrayList<String>()
        out.add(
            "COMBAT FLAG STATE: ${FLAGS.size} switches, resolved. Every token below is property=VALUE; " +
                "grep one token to read that flag off this log. Values are ON/OFF/number/NOSPAWN/NOSPLAT/" +
                "UNSET and NEVER the words true or false, so no token here can be produced by switch-advice " +
                "text elsewhere in this log - which has been misread as state before (see PROVENANCE_SHORT)."
        )
        for (group in FLAGS.map { it.group }.distinct()) {
            out.add(
                "combat.flags[$group]: " +
                    FLAGS.filter { it.group == group }.joinToString(" ") { tokenFor(it) }
            )
        }
        out.add(try { flagEffectLine() } catch (t: Throwable) { "combat.flags[EFFECT]: UNRESOLVED-${t.javaClass.simpleName}" })
        return out
    }

    @Volatile
    private var flagStateLogged = false

    /**
     * Prints [flagStateLines] exactly once per JVM, at boot, through the same
     * logger every other combat line uses - so the state is in the LOG, not on
     * stdout, and survives `-Dorg.slf4j.simpleLogger.logFile`.
     *
     * One `logger.info` per line rather than one embedded newline block: slf4j's
     * per-record prefix then lands on every line, so a `grep combat.flags` on a
     * 1 MB server log returns the whole state with timestamps and nothing else.
     */
    fun logFlagStateOnce() {
        if (flagStateLogged) return
        synchronized(this) {
            if (flagStateLogged) return
            flagStateLogged = true
            flagStateLines().forEach { line -> logger.info { line } }
        }
    }

    fun resetLogOnceForChecks() {
        synchronized(this) {
            flagStateLogged = false
            provenanceLogged = false
        }
    }

    @Volatile
    private var provenanceLogged = false

    /**
     * Prints [PROVENANCE] exactly once per JVM, at boot, so the per-event lines
     * can stay short without the invented numbers becoming unattributed.
     * Idempotent and cheap to call from anywhere.
     */
    fun logProvenanceOnce() {
        if (provenanceLogged) return
        synchronized(this) {
            if (provenanceLogged) return
            provenanceLogged = true
            // The COMPACT STATE FIRST, then the essay. Both are printed once,
            // and the order matters: the state block is what an operator needs
            // before they launch the client, and burying it under 1,500
            // characters of provenance is how it gets skimmed.
            logFlagStateOnce()
            logger.info { "combat provenance (printed once; per-event lines carry the short form): $PROVENANCE" }
        }
    }
}

/**
 * What [PlayerCombat.engage] did. A sealed type rather than a Boolean so a
 * refusal carries the reason and a caller cannot mistake "combat is switched
 * off" for "that npc cannot be fought".
 */
sealed class EngageResult {
    /** The master gate is off. Not a refusal about this npc. */
    object Disabled : EngageResult()

    /** This npc cannot be fought, and here is exactly why. */
    data class Refused(val reason: String) : EngageResult()

    data class Engaged(val npc: WorldNpc) : EngageResult()
}


/**
 * What one swing resolved to under [PlayerCombat.realDamageEnabled].
 *
 * A sealed type rather than an `Int?`, for the same reason
 * [com.opennxt.model.world.AttackOutcome] is one: "0 damage", "the accuracy
 * roll failed" and "this player's weapon has no accuracy param so nothing can
 * be computed" are three different events, and collapsing them into a number
 * is how an invented default gets in. [NotComputable] carries the exact
 * missing input; nothing consumes it as a damage value.
 */
sealed class HitResolution {
    /** The accuracy roll passed and the damage roll produced [damage] lifepoints. */
    data class Landed(val damage: Int, val hitChance: Double) : HitResolution()

    /** The accuracy roll failed at [hitChance]. No damage, and deliberately no splat. */
    data class Missed(val hitChance: Double) : HitResolution()

    /** The model refused: [reason] names the missing input. No damage, and NO fallback. */
    data class NotComputable(val reason: String) : HitResolution()
}
