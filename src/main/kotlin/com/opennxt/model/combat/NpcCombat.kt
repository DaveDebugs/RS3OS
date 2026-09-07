package com.opennxt.model.combat

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.model.world.WorldNpc
import com.opennxt.resources.sqlite.SqliteNpcCodec

/**
 * The combat-relevant part of an NPC definition, read out of `data/rs3.sqlite`.
 *
 * ## What is actually in the database
 *
 * Two places, and they are very different in how confidently they can be read.
 *
 * **1. Columns on `npcs` (32,687 rows).** These are named by the decoder and are
 * not in doubt:
 *
 *  - `combat` - the combat level. Non-null on every row; 0 on 24,283 of them
 *    (non-combatant NPCs) and > 0 on 8,404.
 *  - `boundSize` - tiles per side. Null on 11,680 rows, meaning 1.
 *  - `actions_0..2`, `attackCursor` - whether the NPC is attackable at all.
 *
 * **2. A param map in `npcs_attr` where `field = 'extra'`** (12,269 NPCs have
 * one; 8,027 of those have `combat > 0`). This is where the combat ratings live,
 * and here the situation is: the *values* are in the cache, the *names* are not.
 * `params` has 9,403 rows and carries only a type and a default - no label. So
 * every param id below is an INFERENCE, and each one carries the evidence that
 * produced it. See [NpcCombatParams].
 *
 * ## What is NOT in the database: lifepoints
 *
 * NPC lifepoints are absent. This is a measured claim, not an assumption:
 *
 *  - Nex (npc 26887, combat level 1001) has 20 params; the largest value among
 *    them is 12,500. Nex has 200,000 lifepoints in RS3.
 *  - Vorago (npc 28752, combat level 10000) has 22 params; the largest value
 *    among them is 50,000. Vorago has 160,000 lifepoints.
 *
 * No param on either NPC is within an order of magnitude of its lifepoint total,
 * and no column on `npcs` holds one either. Lifepoints were a server-side
 * property; the client never needed the number, only the health bar percentage.
 *
 * That is why [toCombatStats] *requires* the caller to pass lifepoints in. A
 * default would be a fabrication, and making it a required argument means the
 * gap has to be filled deliberately, from an external table, rather than being
 * forgotten and quietly filled with a plausible-looking guess.
 */
object NpcCombat {

    /**
     * Loads the combat-relevant definition for [id], or null if there is no such
     * NPC (or no database).
     *
     * Reuses [SqliteNpcCodec] for the `npcs` columns rather than re-querying
     * them, so the two cannot drift, and adds the param map on top.
     */
    fun load(id: Int): NpcCombatDefinition? {
        val base = SqliteNpcCodec.load(id) ?: return null
        return NpcCombatDefinition(base, loadParams(id))
    }

    /**
     * The `extra` param map for [id] as prop -> int value, empty if the NPC has
     * none (20,418 of the 32,687 NPCs have none).
     *
     * String-valued params are dropped: every combat quantity observed in this
     * table is an int, and the string entries are ability names and tooltips.
     */
    fun loadParams(id: Int): Map<Int, Int> {
        if (!RsDatabase.hasTable("npcs_attr")) return emptyMap()
        val json = RsDatabase.queryOne(
            "SELECT value FROM npcs_attr WHERE id = ? AND field = 'extra'", id
        ) { it.getString("value") } ?: return emptyMap()
        return parseParams(json)
    }

    /**
     * Parses the stored param array, e.g.
     * `[{"prop":641,"intvalue":6720,"stringvalue":null}, ...]`.
     *
     * Malformed rows are skipped rather than throwing: this is a decoded
     * artefact of buildall.sh, and one bad row should not make an NPC
     * unloadable.
     */
    fun parseParams(json: String): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        val root = try {
            JsonParser().parse(json)
        } catch (e: Exception) {
            return out
        }
        if (!root.isJsonArray) return out
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val prop = obj.get("prop") ?: continue
            val value = obj.get("intvalue") ?: continue
            if (value.isJsonNull) continue
            try {
                out[prop.asInt] = value.asInt
            } catch (e: Exception) {
                // not an int param; ignore
            }
        }
        return out
    }
}

/**
 * Whether a DEAD npc is still transmitted to the client, and for how long.
 */
object NpcDeathTransmission {

    /** The switch name, so a check and a boot line cannot spell it differently. */
    const val LINGER_SWITCH = "opennxt.experiment.combat.deathlinger"

    /**
     * Extra ticks a corpse is transmitted for BEYOND the death tick. Default 0.
     *
     * Read on every call rather than cached: a check flips it inside one
     * process, and a cached value would make the second arm silently measure
     * the first arm's setting. Negative and unparseable both read as 0 - this
     * refuses rather than guesses, in line with everything else here.
     */
    val lingerTicks: Int
        get() {
            val raw = System.getProperty(LINGER_SWITCH)?.trim()?.toIntOrNull() ?: return 0
            if (raw <= 0) return 0
            return minOf(raw, WorldNpc.RESPAWN_TICKS - 1)
        }

    fun lingerTicksFor(npc: WorldNpc): Int {
        val explicit = System.getProperty(LINGER_SWITCH)?.trim()?.toIntOrNull()
        // respawn - not below the constant, which would cap a 59-tick Man at 49.
        if (explicit != null) return minOf(explicit.coerceAtLeast(0), npc.respawnTotal - 1).coerceAtLeast(0)
        val seq = npc.deathAnimation?.sequence ?: return 0
        val ticks = NpcAnimObservations.sequenceLengthTicks(seq) ?: return 0
        return minOf(ticks, npc.respawnTotal - 1).coerceAtLeast(0)   // the npc's OWN respawn
    }

    /**
     * Ticks since [npc] died: 0 on the tick the killing blow landed, 1 on the
     * next world tick, and -1 while it is alive.
     *
     * Derived from `respawnTicksRemaining` rather than from a new field on
     * [WorldNpc], deliberately: `die()` sets the counter to
     * [WorldNpc.RESPAWN_TICKS] and `tickRespawn()` decrements it once per world
     * tick, so the information already exists and a second field could drift
     * from it. The one assumption is that `die()` was called with
     * `RESPAWN_TICKS`, which is the only call site there is
     * (`WorldNpcs.applyDamage`); a shorter respawn passed in by some future
     * caller would make this read low, never high, so the corpse would vanish
     * early rather than linger wrongly.
     */
    fun ticksDead(npc: WorldNpc): Int {
        if (npc.alive) return -1
        val remaining = npc.respawnTicksRemaining
        if (remaining < 0) return -1
 //: subtract from the countdown the death was armed with (per-npc respawns),
        // which retires the KDoc assumption above that every death uses RESPAWN_TICKS.
        return (npc.respawnTotal - remaining).coerceAtLeast(0)
    }

    /**
     * Whether the NPC_INFO encoder should still put [npc] in a viewer's scene.
     *
     * True for every live npc, so the call site reads as a widening of the
     * existing `!npc.alive` skip and not as a second policy. For a corpse it is
     * true while [ticksDead] is within [lingerTicks] - i.e. on the death tick
     * alone by default.
     */
    fun transmissible(npc: WorldNpc): Boolean {
        if (npc.despawned) return false   // WorldNpcs.despawn - gone for good, no corpse
        if (npc.alive) return true
 // after a sandbox rollback. The recovery replayed
        // this pass's recorded tool calls in order, and its LAST write to this
        // file was a deliberate MUTATION CONTROL (`return false`, the old
        // encoder behaviour) rather than the final implementation - the restore
        // that followed it was a separate call the filter did not carry. The
        // body below is the one the KDoc above describes and the one
        // ticksDead is within lingerTicks, i.e. the death tick alone by default.
        val dead = ticksDead(npc)
        return dead >= 0 && dead <= lingerTicksFor(npc)
    }

    /**
     * Whether [npc]'s update queue must be thrown away NOW because it can never
     * be transmitted.
     *
     * True only for a corpse that is past its transmission window and still
     * holds blocks. Everything else - live npcs, a corpse on its death tick, an
     * empty queue - is false, so this can only ever destroy blocks that had no
     * remaining path to a client.
     *
     * Counted, because a check that cannot see it fire cannot prove it fires.
     */
    fun shouldRetire(npc: WorldNpc): Boolean {
        if (npc.alive) return false
        if (transmissible(npc)) return false
        if (npc.pendingUpdates.blocks().isEmpty()) return false
        queuesRetired++
        return true
    }

    /** Queues dropped by [shouldRetire] since [resetCounters]. */
    var queuesRetired: Int = 0
        private set

    /** For checks that drive several kills in one process. The server never calls it. */
    fun resetCounters() {
        queuesRetired = 0
    }

    val PROVENANCE: String =
        "npc death transmission. THE DEFECT IS killing a Cow applies 21 hits and puts 20 " +
            "HITS blocks on the wire, and after the killing tick the npc's own pendingUpdates still " +
            "holds that 21st block with offered=false - so the killing blow's splat and the death " +
            "animation were QUEUED and then DROPPED by NpcInfoEncoder.collect's `if (!npc.alive) " +
            "continue`, not never queued. A second defect fell out of it: WorldNpcs.tick sweeps a " +
            "queue only when offered, so a corpse's queue survived the whole respawn countdown and " +
            "was transmitted on the RESPAWN tick. NOT AUTHORED: the one-tick window. " +
            "NPC_INFO is built once per tick and the blocks are queued during that tick's combat " +
            "phase, so one tick is the mechanical minimum for an already-queued block to reach a " +
            "viewer - lingerTicks defaults to 0 and the corpse is gone on the next tick. AUTHORED: " +
            "any longer corpse linger, which is what a real death animation would need. This cache " +
            "does not identify a death sequence for any npc (see WeaponAnimations.NPC_PROVENANCE), " +
            "so it cannot state the duration either; -D" + LINGER_SWITCH + "=<ticks>, default 0, " +
            "clamped below RESPAWN_TICKS. WHAT WOULD SETTLE IT: sequences_attr.frames for an " +
            "identified death slot, divided by the 30 Hz frame clock. SETTLED for the npcs " +
            "NpcAnimObservations identifies a death sequence for (the reference client-recorded kills, by npc id then " +
            "animation group): lingerTicksFor(npc) holds such a corpse for the sequence's frame-list length " +
            "(read at 20 ms per unit; 5/3 longer if the clock is 30 Hz), clamped below RESPAWN_TICKS; the " +
            "switch above still overrides for everyone, and a corpse with no death sequence keeps the 0."
}

/**
 * Param ids used by NPC (and, for several of them, item) combat data.
 *
 * NONE of these names come from the cache. `params` carries no label for any
 * param id, so every name here is inferred from how the values behave. The
 * evidence is recorded per constant so that a wrong inference can be argued
 * with rather than merely believed.
 *
 * The single strongest piece of cross-cutting evidence, which several of these
 * rest on: items carry an accuracy-shaped param (3267) whose 79 distinct values
 * form a ladder that rises monotonically with the item's level requirement
 * (tier 1 -> 150, tier 10 -> 181, tier 20 -> 316, tier 30 -> 470, tier 40 ->
 * 628, tier 50 -> 850, tier 60 -> 1132, tier 70 -> 1486, tier 80 -> 1783, tier
 * 90 -> 2074, tier 99 -> 2287). 75 of those 79 ladder values also occur as NPC
 * param 29. Two different tables, decoded independently, landing on the same
 * value ladder is not a coincidence, and it is what identifies 29 as the NPC
 * counterpart of an item's accuracy.
 *
 * What this does NOT establish is what a server is supposed to *do* with these
 * numbers. The formula that turns an accuracy rating and an armour rating into a
 * hit chance is not in the cache and is not reconstructed anywhere in this
 * package.
 */
object NpcCombatParams {

    /**
     * Melee damage rating.
     *
     * INFERRED. Evidence: param 641 appears on weapons as well as NPCs, and on
     * weapons it tracks tier (Bronze sword 480, Dragon scimitar 5,760, Abyssal
     * whip 6,720). An item has no lifepoints, so on items 641 is a damage-shaped
     * quantity, and the NPC values sit on the same scale.
     *
     * Explicitly NOT lifepoints, despite low-level NPCs making it look like they
     * could be (Rat 40, Chicken 150, Cow 200): Nex's 641 is 12,500 against
     * 200,000 actual lifepoints, and Vorago's is 30,000 against 160,000. It
     * tracks max hit, not health.
     */
    const val MELEE_DAMAGE = 641

    /**
     * Melee accuracy rating.
     *
     * EVIDENCED, and no longer resting on the item-3267 overlap alone. The cache
     * carries the ladder itself: **enum 7339** is a 200-entry int->int table,
     * key 1 -> 110, key 20 -> 316, key 50 -> 850, key 99 -> 3031, key 200 ->
     * 18100 - the level -> rating curve both the item and the NPC ratings are
     * drawn from. Sweeping every NPC that carries param 29, **6,345 of 7,377
     * values (86.0%) are exact members of that enum's value set**.
     *
     * The remaining 14% is a real limit and is not smoothed over. The common
     * off-ladder values are round numbers (10 x61, 20 x60, 40 x47, 50 x29) plus
     * a tail of NEGATIVE ratings (-10 x54, -47, -42, -30, -20) which always
     * appear beside a negative param 641 on the same NPC (Chicken 29=-47 with
     * 641=-420, Imp 29=-42 with 641=-370). Whatever those encode, it is not an
     * accuracy on this ladder, and this reading does not explain them.
     */
    const val MELEE_ACCURACY = 29

    /**
     * Ranged damage / accuracy.
     *
     * INFERRED, from the pairing pattern: NPCs that carry 643 also carry 4, and
     * their values sit on the same two scales as 641/29. Item 4 appears on the
     * Magic shortbow (850, a tier-50 ladder value) where item 3267 is absent,
     * which is what a separate ranged accuracy slot looks like.
     */
    const val RANGED_DAMAGE = 643
    const val RANGED_ACCURACY = 4

    /**
     * Magic damage / accuracy.
     *
     * INFERRED, same pairing argument. Confirmed negatively by the Imp (npc
     * 1348), which carries 641=160 and 29=140 alongside 643=0, 4=0, 965=0 and
     * 3=0 - a melee-only creature with its ranged and magic slots explicitly
     * zeroed rather than absent.
     */
    const val MAGIC_DAMAGE = 965
    const val MAGIC_ACCURACY = 3

    /**
     * Armour rating - the defensive counterpart of accuracy.
     *
     * EVIDENCED on the same enum-7339 ladder as [MELEE_ACCURACY], and more
     * cleanly than accuracy is: **8,305 of 8,519 values (97.5%) are exact
     * members of enum 7339's value set**, against 86.0% for param 29. It is also
     * present on more NPCs than any damage param, which is what a universal
     * defensive stat looks like.
     *
     * The 2.5% off-ladder tail is small round numbers (0 x21, 1 x17, 600 x14,
     * 200 x13) and, unlike accuracy, contains no negatives - the minimum over
     * the whole population is 0.
     *
     * What the two ladders being the same does NOT establish: how a server turns
     * an accuracy rating and an armour rating into a hit chance. That formula is
     * not in the cache.
     */
    const val ARMOUR = 2865

    /**
     * Attack interval in game ticks.
     *
     * EVIDENCED, including its unit and its domain, both of which used to be
     * assumptions. **Enum 6741** is an 8-entry int->string table and it is the
     * cache stating the unit outright:
     *
     *     1 -> "0.6s"   2 -> "1.2s"   3 -> "1.8s"   4 -> "2.4s"
     *     5 -> "3s"     6 -> "3.6s"   7 -> "4.2s"   8 -> "4.8s"
     *
     * That is exactly 0.6 seconds per unit - the game tick - so param 14 is an
     * interval in ticks and not, say, a rate. It also fixes the DOMAIN: the enum
     * stops at 8, so a value the client can render at all is 1..8. 8,468 of the
     * 8,715 NPCs carrying param 14 (97.17%) are inside it. This is where
     * [com.opennxt.model.combat.CombatFormulas.PLAUSIBLE_TICKS] now comes from;
     * it used to be an authored 1..12.
     *
     * The distribution corroborates independently: 5,095 NPCs at 4 (RuneScape's
     * standard attack), 1,790 at 5, 917 at 6, and it separates NPCs correctly by
     * known speed - General Graardor (slow) 6, Kree'arra (fast) 3.
     *
     * The tail is unchanged and still unexplained: 216 NPCs carry 15, 20, 24,
     * 25, 30, 85, 500 or 1000, plus 26 at 9..10 which the enum also cannot
     * render. Those are not attack intervals under this reading, which is why
     * [NpcCombatDefinition] exposes the raw value rather than a sanitised one.
     */
    const val ATTACK_SPEED = 14

    /**
     * Coarse weakness class - what the client's combat icon shows this NPC is
     * WEAK TO. It was called `COMBAT_CLASS` here, which was half right in a way
     * that mattered; the rename and this KDoc are the correction.
     *
     * EVIDENCED, and it is now the best-evidenced param in this file, which is a
     * reversal - it used to be described as the weakest inference here.
     */
    const val WEAKNESS_CLASS = 26

    /**
     * Fine-grained weakness - the specific attack type this NPC is weak to,
     * read through **enum 7733** (see [WEAKNESS_CLASS] for the full table and
     * the evidence).
     *
     * 8,466 NPCs carry it, marginally more than carry [WEAKNESS_CLASS] (8,385).
     * Where both are present and both name a style they agree 93.64% of the
     * time; this one is the more specific of the two and the one client script
     * 4120832 prefers.
     */
    const val WEAKNESS = 2848

    /**
     * Four defensive affinity slots - how much damage a style does to this NPC.
     *
     * INFERRED, and **weaker than this KDoc used to claim**. Two corrections
     * from the param deep-dive, both of which cut against the previous text.
     *
     * **The Graardor/Kree'arra argument does not survive a sweep.** It said the
     * slots "discriminate correctly" on those two bosses. Run against the whole
     * population instead of two hand-picked examples, using the now-evidenced
     * weakness ([WEAKNESS], enum 7733) as the thing to discriminate: among NPCs
     * with a unique maximum affinity, slot 0 is that maximum for **37 of 37**
     * magic-weak NPCs, **52 of 52** melee-weak, **38 of 38** ranged-weak and
     * **2 of 2** necromancy-weak. A slot that is the largest for every weakness
     * carries no information about weakness. The two bosses agreed with the
     * assumed order by chance, and picking them was the error.
     *
     * **90/70/60/50 is not "the default set".** It is one common vector among
     * 88 distinct ones over 4,559 NPCs, and not even the most common: (40,40,
     * 40,40) x1,427, (null,null,60,50) x856, (90,70,60,50) x795, (60,60,60,60)
     * x233.
     *
     * So the slots stay numbered rather than named, as before - but now because
     * a test to name them was run and FAILED, not because one was never tried.
     * [com.opennxt.model.combat.CombatFormulas.affinitySlot]'s melee/ranged/
     * magic/necromancy order is therefore AUTHORED and unsupported, and is
     * labelled as such there.
     */
    val AFFINITY = intArrayOf(2849, 2850, 2851, 2852)

    /**
     * Item param: the skill id of an equipment requirement, paired with
     * [ITEM_REQUIREMENT_LEVEL].
     */
    const val ITEM_REQUIREMENT_SKILL = 749
    const val ITEM_REQUIREMENT_LEVEL = 750

    /** Item param: accuracy rating. INFERRED - the tier ladder described above. */
    const val ITEM_ACCURACY = 3267

    /** Item param: melee damage rating. Same id as [MELEE_DAMAGE]. */
    const val ITEM_DAMAGE = 641

    /**
     * THE PLAYER'S OTHER TWO STYLES, off the items themselves.
     */
    const val ITEM_RANGED_ACCURACY = 4
    const val ITEM_MAGIC_ACCURACY = 3
    const val ITEM_RANGED_DAMAGE = 643
    const val ITEM_MAGIC_DAMAGE = 965
    const val ITEM_ATTACK_RANGE = 13
    const val ITEM_STYLE_MELEE = 2825
    const val ITEM_STYLE_RANGED = 2826
    const val ITEM_STYLE_MAGIC = 2827
}

/**
 * The twelve values of **enum 7733**, transcribed from the cache rather than
 * invented: the enum's `stringArrayValue1` is
 *
 *     [[0,"None"],[5,"Melee (Stabbing)"],[6,"Melee (Slashing)"],
 *      [7,"Melee (Crushing)"],[8,"Ranged (Arrow)"],[9,"Ranged (Bolt)"],
 *      [10,"Ranged (Thrown)"],[1,"Magic (Air)"],[2,"Magic (Water)"],
 *      [3,"Magic (Earth)"],[4,"Magic (Fire)"],[37,"Necromancy"]]
 *
 * [label] is that string verbatim, so a wrong transcription is visible rather
 * than hidden behind a prettier name. [key] is the param value.
 *
 * [style] is the one derived field: the [CombatStyle] the label's leading word
 * names, and null for `None` and `Necromancy` - Necromancy is a fourth style
 * this server does not model, and collapsing it into one of the three would be
 * a fabrication. That grouping is what the 93.64% cross-tab in
 * [NpcCombatParams.WEAKNESS_CLASS] is computed over.
 */
enum class NpcWeakness(val key: Int, val label: String, val style: CombatStyle?) {
    NONE(0, "None", null),
    MAGIC_AIR(1, "Magic (Air)", CombatStyle.MAGIC),
    MAGIC_WATER(2, "Magic (Water)", CombatStyle.MAGIC),
    MAGIC_EARTH(3, "Magic (Earth)", CombatStyle.MAGIC),
    MAGIC_FIRE(4, "Magic (Fire)", CombatStyle.MAGIC),
    MELEE_STAB(5, "Melee (Stabbing)", CombatStyle.MELEE),
    MELEE_SLASH(6, "Melee (Slashing)", CombatStyle.MELEE),
    MELEE_CRUSH(7, "Melee (Crushing)", CombatStyle.MELEE),
    RANGED_ARROW(8, "Ranged (Arrow)", CombatStyle.RANGED),
    RANGED_BOLT(9, "Ranged (Bolt)", CombatStyle.RANGED),
    RANGED_THROWN(10, "Ranged (Thrown)", CombatStyle.RANGED),
    NECROMANCY(37, "Necromancy", null);

    companion object {
        private val BY_KEY = values().associateBy { it.key }

        /**
         * The entry for a raw param-2848 value, or null if the enum has no such
         * key. Null is not an error: 101 NPCs carry 21, 11 or 13, which enum
         * 7733 does not define, and those must not be forced into a bucket.
         */
        fun of(key: Int): NpcWeakness? = BY_KEY[key]
    }
}

/**
 * An NPC's combat-relevant definition: the `npcs` columns plus its param map.
 *
 * [params] is exposed raw as well as through the named accessors, because the
 * names are inferences (see [NpcCombatParams]) and anything that wants to check
 * the inference needs to reach the number underneath it.
 *
 * Every rating accessor is nullable. A missing param means the cache did not
 * give this NPC that rating, which is not the same as the rating being zero -
 * the Imp carries explicit zeros for its ranged and magic slots, so zero and
 * absent genuinely mean different things in this data.
 */
class NpcCombatDefinition(
    val definition: NpcDefinition,
    val params: Map<Int, Int>
) {
    val id: Int get() = definition.id
    val name: String? get() = definition.name

    /** Tiles per side. `boundSize`, defaulting to 1 where the column is null. */
    val size: Int get() = definition.size

    /** `npcs.combat`. 0 means a non-combatant, not "unknown". */
    val combatLevel: Int get() = definition.combatLevel ?: 0

    /**
     * Whether the NPC can be attacked: any right-click option is "Attack".
     *
     * Grounded - it reads the decoded `actions_0..4` columns directly.
     */
    val attackable: Boolean
        get() = definition.actions.any { it != null && it.equals("Attack", ignoreCase = true) }

    /** True if this NPC carries any of the inferred combat ratings. */
    val hasCombatParams: Boolean
        get() = params.keys.any {
            it == NpcCombatParams.MELEE_DAMAGE || it == NpcCombatParams.MELEE_ACCURACY ||
                it == NpcCombatParams.ARMOUR
        }

    val meleeDamage: Int? get() = params[NpcCombatParams.MELEE_DAMAGE]
    val meleeAccuracy: Int? get() = params[NpcCombatParams.MELEE_ACCURACY]
    val rangedDamage: Int? get() = params[NpcCombatParams.RANGED_DAMAGE]
    val rangedAccuracy: Int? get() = params[NpcCombatParams.RANGED_ACCURACY]
    val magicDamage: Int? get() = params[NpcCombatParams.MAGIC_DAMAGE]
    val magicAccuracy: Int? get() = params[NpcCombatParams.MAGIC_ACCURACY]
    val armour: Int? get() = params[NpcCombatParams.ARMOUR]

    /** Attack speed in ticks. See [NpcCombatParams.ATTACK_SPEED] for the caveat on outliers. */
    val attackSpeed: Int? get() = params[NpcCombatParams.ATTACK_SPEED]

    /** The four affinity slots, null where absent. Slots are numbered, not named - see [NpcCombatParams.AFFINITY]. */
    val affinities: List<Int?> get() = NpcCombatParams.AFFINITY.map { params[it] }

    /**
     * The style this NPC is WEAK TO, for picking an affinity slot: the coarse
     * class (param 26) first, else the style band of the specific weakness
     * (param 2848 through [NpcWeakness.style]). Null when neither says.
     */
    val weakTo: CombatStyle? get() = weaknessClass ?: weakness?.style

    /**
     * The affinity an attack of [style] reads against this NPC, by the RELATIVE
     * slot order [CombatFormulas.affinitySlot] establishes.
     */
    fun affinityFor(style: CombatStyle): Int? {
        val weak = weakTo
        if (weak != null) return affinities[CombatFormulas.affinitySlot(style, weak)]
        val styleSlots = listOf(affinities[1], affinities[2], affinities[3])
        if (styleSlots.any { it == null }) return null
        return if (styleSlots.distinct().size == 1) styleSlots[0] else null
    }

    /**
     * The raw [NpcCombatParams.WEAKNESS_CLASS] value, unmapped. Exposed because
     * three of its observed values (0, 9, 17) have no reading yet and anything
     * that wants to study them needs the number, not a null.
     */
    val weaknessClassRaw: Int? get() = params[NpcCombatParams.WEAKNESS_CLASS]

    /**
     * What this NPC is WEAK TO, coarsely, exactly as enum 16502 labels it:
     * 1 = Magic, 2 = Melee, 3 = Ranged. Null when the param is absent or holds
     * one of the values enum 16502 has no key for (0, 9, 17 - see
     * [NpcCombatParams.WEAKNESS_CLASS]).
     */
    val weaknessClass: CombatStyle?
        get() = when (weaknessClassRaw) {
            1 -> CombatStyle.MAGIC
            2 -> CombatStyle.MELEE
            3 -> CombatStyle.RANGED
            else -> null
        }

    /**
     * What this NPC is weak to, precisely, through enum 7733 - the specific
     * attack type rather than the class. Null when absent or when the value is
     * not a key of that enum (21, 11, 13 occur and are unexplained).
     */
    val weakness: NpcWeakness? get() = params[NpcCombatParams.WEAKNESS]?.let(NpcWeakness::of)

    /**
     * The style this NPC ATTACKS with, derived from its weakness class by the
     * combat triangle.
     *
     * This returns exactly what it returned before the param-26 deep-dive
     * (26 = 1 -> MELEE, 2 -> RANGED, 3 -> MAGIC); what changed is that it is no
     * longer a direct relabelling of the number. The number's own label is the
     * weakness ([weaknessClass], from enum 16502), and the NPC's style is that
     * weakness's triangle opposite - a derivation that is measured, not assumed:
     * over the 4,669 NPCs whose own style is unambiguous (exactly one non-zero
     * damage param) it is right **99.49%** of the time, against 72.39% for the
     * next best of the six possible assignments. See
     * [NpcCombatParams.WEAKNESS_CLASS] for the full evidence.
     *
     * Null when [weaknessClass] is null, and for the same reasons.
     */
    val combatStyle: CombatStyle? get() = weaknessClass?.beats

    fun damageFor(style: CombatStyle): Int? = when (style) {
        CombatStyle.MELEE -> meleeDamage
        CombatStyle.RANGED -> rangedDamage
        CombatStyle.MAGIC -> magicDamage
    }

    fun accuracyFor(style: CombatStyle): Int? = when (style) {
        CombatStyle.MELEE -> meleeAccuracy
        CombatStyle.RANGED -> rangedAccuracy
        CombatStyle.MAGIC -> magicAccuracy
    }

    /**
     * Builds runtime [CombatStats] for a spawn of this NPC.
     *
     * [lifepoints] is required and has no default on purpose: this cache does
     * not contain NPC lifepoints (see [NpcCombat] for the measurement), so the
     * number has to come from somewhere else and the caller is the only one who
     * knows where. A default here would be an invention wearing the costume of a
     * loaded value.
     */
    fun toCombatStats(lifepoints: Int): CombatStats = CombatStats(lifepoints)

    override fun toString() =
        "NpcCombatDefinition($id, ${name ?: "unnamed"}, level=$combatLevel, size=$size, params=${params.size})"
}
