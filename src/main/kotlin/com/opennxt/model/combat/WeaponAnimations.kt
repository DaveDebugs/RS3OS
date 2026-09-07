package com.opennxt.model.combat

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.CacheVarTypes
import com.opennxt.resources.sqlite.RsDatabase

/**
 * Player combat animations, via the path three earlier searches could not see.
 *
 * ```
 * item e.g. 1277 Bronze sword
 * -> param 686 (vartype 73 = STRUCT) = 14922
 * -> weapon-class struct 14922
 * -> its params typed vartype 6 = SEQUENCE
 * ```
 *
 * Item param 686 references **32 distinct weapon-class structs across 6,237
 * items**, the largest being 14923 (608 items), 14937 (571), 14922 (509). Those
 * 32 structs carry eleven distinct sequence-typed params between them.
 */
object WeaponAnimations {

    /** `item -> weapon-class struct`. Established: vartype 73 = STRUCT. */
    const val WEAPON_CLASS_PARAM = 686

    /**
     * `npc -> combat-class struct`. The npc-side twin of [WEAPON_CLASS_PARAM],
     * and it lands on the SAME struct family: param 2816 is declared vartype 73
     * (struct) and its 27 distinct values all resolve to struct rows carrying
     * the same sequence-typed params.
     */
    const val NPC_CLASS_PARAM = 2816

    /**
     * The three sequence-typed slots on a combat struct that an earlier analysis put
     * forward as attack / block / block, in that order.
     *
     * ALL THREE SHIP. Not one of them is named "the attack" in code, because
     * nothing measured below promotes one to that name - see [NPC_PROVENANCE].
     * The operator picks a slot; the default is unset and nothing is emitted.
     */
    val NPC_SLOTS = listOf(4385, 4386, 4387)

    /**
     * ========================================================================
     * WHAT THE SLOTS ARE, AS FAR AS MEASUREMENT GOES
     * ========================================================================
     *
     * The earlier claim was: **4385 is the ATTACK, 4386/4387 are the two
     * defend/block slots.** Three tests were run against it. Two support the
     * weaker half of it, one is a dead end and is reported as such, and none
     * of them establishes the word "attack".
     *
     * ------------------------------------------------------------------------
     * TEST 1 - the pairing. 4386 and 4387 are a PAIR; 4385 is not in it.
     * ------------------------------------------------------------------------
     *
     * Over the 24 combat structs that carry sequence params:
     *
     * ```
     *   4386 == 4387        9 / 24        <- two slots that are often the SAME
     *   4385 == 4386        0 / 24
     *   4385 == 4387        0 / 24
     *   2914 == 4385/6/7    0 / 24  each
     * ```
     *
     * Nine structs give 4386 and 4387 the identical sequence id (Sir Owen's
     * 14936 is {4385:422, 4386:424, 4387:424}; 14937..14942 and 19415 do the
     * same with 420/424). 4385 never coincides with either. Two slots that
     * collapse onto one id in 37.5% of families are two variants of one thing;
     * the slot that never joins them is a different thing. **That much is
     * solid. Which of the two things is the swing is not in this test.**
     *
     * ------------------------------------------------------------------------
     * TEST 2 - the sequence's OWN class field. Independent, and not vacuous.
     * ------------------------------------------------------------------------
     *
     * `sequences.unknown_05` is a decoded column on the sequence record itself,
     * nothing to do with params. Its base rate over the whole table is what
     * makes it evidence rather than a membership test:
     *
     * ```
     *   all 37,853 sequences:   null 30,414 (80.35%)   35 -> 1,639 (4.33%)
     *                              6 -> 1,548 (4.09%)  10 -> 1,220 (3.22%)
     *                              0 ->   391 (1.03%)
     * ```
     *
     * Resolved through it, the slots separate cleanly - npc combat structs
     * first, the 32 weapon-class structs second:
     *
     * ```
     *   slot   class 35        class 6         class 0     null
     *   4385   22/24, 28/30          2, 2            -        -
     *   4386        -           24/24, 27/31         2      2
     *   4387        -           12/24, 11/31         2     11/24, 17/31
     *   2914   24/24, 30/30           -              -        -
     *   2831   12/12, 16/16           -              -        -
     *   4264   23/24, 30/31           -              -        -
     *   2916        -                 -         24/24, 31/31  -
     *   2917        -                 -         24/24, 31/31  -
     *   2918        -                 -              -    24/24, 31/31
     * ```
     *
     * 4385 is class 35 on 22 of 24 against a 4.33% base rate; 4386 is class 6
     * on 24 of 24 against 4.09%. This is the [com.opennxt.resources.sqlite.CacheVarTypes]
     * lesson applied: a field with an 80% null and three ~4% classes CAN
     * discriminate, where "is this a valid sequence id" (86% real vs 91% random)
     * cannot. The check re-derives the base rate and would fail if it were
     * uniform.
     *
     * **AND IT CUTS AGAINST THE THREE-SLOT FRAME.** Class 35 is not
     * 4385's alone: **2914, 2831 and 4264 are class 35 too**, on 24/24, 12/12
     * and 23/24. So the sequence's own class puts 4385 in a family of FOUR
     * candidates, not in a triple of three. If class 35 is "a full-body action
     * with a hit frame", the attack is one of {4385, 2914, 2831, 4264}, and the
     * audit's triple {4385, 4386, 4387} was the wrong set to choose from.
     *
     * ------------------------------------------------------------------------
     * TEST 3 - the art batch. 4385 AND 4387, not 4385 alone.
     * ------------------------------------------------------------------------
     *
     * The second pin was: struct 14924's {4385:15071, 4387:15074} are two
     * of the three ids left unused inside the block animgroup 2554 occupies.
     * Verified, and it is real: 14924 is the scimitar class (88 items; 33 of
     * them carry animgroup param 644 = 2554), animgroup 2554 uses sequences
     * {15069 idle, 15070 run, 15073 walk, 15075/15076/15077 walk back/left/
     * right} plus 12024 twice for the turns, so 15069..15077 has exactly three
     * holes - **15071, 15072, 15074** - and the struct fills two of them.
     *
     * Generalised into a test that could fail, over the 24 weapon-class structs
     * that have a modal item animgroup: how often is a slot's value within +-8
     * of one of its own animgroup's sequence ids, against 1,000 shuffles of the
     * struct -> animgroup assignment?
     *
     * ```
     *   slot    real     shuffled mean    p(shuffle >= real)
     *   4385    3/24         0.28              0.000
     *   4387    4/24         0.36              0.001
     *   4386    1/24         0.10              0.098
     *   2914    0/24         0.00              1.000
     * ```
     *
     * The control fired: shuffling destroys it. But it names **two** slots, not
     * one - 4385 and 4387 are both drawn from the weapon's own art batch, which
     * is what you would expect of an attack and a block animated together by the
     * same artist for the same weapon. It cannot tell them apart, and 2914 is
     * untouchable by it (its values live in a separate 37,3xx..37,4xx batch that
     * no weapon animgroup is near, so this test is silent about it rather than
     * negative).
     *
     * ------------------------------------------------------------------------
     * TEST 4 - the npc-side animgroup span. A DEAD END, reported as one.
     * ------------------------------------------------------------------------
     *
     * The same idea on the npc side: the combat structs carry animgroup-typed
     * params 2954/2955 (vartype 44). Every one of them resolves to a tiny
     * generic group (2698 -> 18015..18017 on 15 of the 24 structs), and **0 of
     * 24** slot values of any of 4385/4386/4387/2914 fall inside that span -
     * with the shuffle control also scoring 0.000. Zero against zero
     * discriminates nothing. Run, negative, kept.
     *
     * ------------------------------------------------------------------------
     * VERDICT
     * ------------------------------------------------------------------------
     *
     * 4385 is clearly the ODD ONE OUT of {4385, 4386, 4387} - never equal to
     * either of the other two, a different sequence class from both. That is
     * what an attack slot would look like against two block slots, and it is
     * also what a death slot, a special-attack slot or a stance slot would look
     * like. **Nothing measured says the word "attack".** Per the instruction
     * this work was given: 4385 is NOT clearly the attack, so all three slots
     * ship behind one switch and none is named in code.
     *
     *     -Dopennxt.experiment.combat.anim.npcslot=4385
     *
     * Default unset -> [npcSelected] returns null, no block is queued, the wire
     * is byte-for-byte unchanged. One fight settles it; nothing in this cache
     * does.
     */
    val NPC_PROVENANCE: String =
        "npc combat animations: the CHAIN is measured (npc param 2816, vartype 73 = struct, reaches 27 " +
            "structs over 502 npcs, 24 of the 27 carrying sequence-typed params). COVERAGE IS 452 of " +
            "7,914 attackers = 5.71%, and Chicken 41 / Goblin 8638 / Fungi 3344 / Skeleton 93 carry " +
            "none - this animates one attacker in eighteen and none of the ones near Lumbridge. " +
            "SLOT ROLES ARE NOT ESTABLISHED. Measured: 4386 == 4387 on 9/24 structs while 4385 equals " +
            "neither on 0/24, so 4386/4387 are a pair and 4385 is not in it; sequences.unknown_05 puts " +
            "4385 in class 35 (22/24, base rate 4.33% over 37,853 sequences) and 4386 in class 6 " +
            "(24/24, base 4.09%); and 4385 (3/24, p<0.001) AND 4387 (4/24, p=0.001) both draw from " +
            "their weapon's own animgroup art batch under a 1,000-shuffle control. RETRACTION of the " +
            "audit's frame: class 35 also covers 2914 (24/24), 2831 (12/12) and 4264 (23/24), so the " +
            "candidate set is FOUR slots and not the triple {4385,4386,4387} chosen earlier. " +
            "NEGATIVE, run and kept: the npc-side animgroup-span test scores 0/24 real against 0/24 " +
            "shuffled and discriminates nothing. AUTHORED the moment a slot is chosen: set " +
            "-Dopennxt.experiment.combat.anim.npcslot=<param>, default unset, and report what the npc " +
            "did. One fight settles it and nothing in this cache does."

    /**
     * Every `extra` param of a cache row, as prop -> int value, or an empty map.
     */
    private fun extras(table: String, id: Int): Map<Int, Int> {
        if (!RsDatabase.available) return emptyMap()
        val raw = RsDatabase.queryOne(
            "SELECT value FROM ${table}_attr WHERE id = ? AND field = 'extra'", id
        ) { it.getString(1) } ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (el in JsonParser().parse(raw).asJsonArray) {
            val o = el.asJsonObject
            if (!o.has("prop") || !o.has("intvalue") || o.get("intvalue").isJsonNull) continue
            out[o.get("prop").asInt] = o.get("intvalue").asInt
        }
        return out
    }

    /** The combat-class struct an npc points at, or null. Usually null - see [NPC_CLASS_PARAM]. */
    fun npcClassOf(npcId: Int): Int? =
        extras("npcs", npcId)[NPC_CLASS_PARAM]?.takeIf { it >= 0 }

    /** Every sequence-typed param on an npc's combat-class struct. Usually empty. */
    fun npcSequenceParams(npcId: Int): Map<Int, Int> {
        val struct = npcClassOf(npcId) ?: return emptyMap()
        return extras("structs", struct).filterKeys { CacheVarTypes.isSequence(it) }
    }

    /**
     * `sequences.unknown_05` for [sequenceId] - the sequence record's own class
     * field, null where the column is NULL (80.35% of the table) or the row is
     * absent.
     *
     * Deliberately NOT named. It is the discriminator in TEST 2 of
     * [NPC_PROVENANCE] and its VALUES are what carry the argument; naming it
     * "priority" or "type" from the outside would be exactly the wrongly-named
 * opcode this server prefers to leave unmapped. Reading the 949 client's
     * sequence-config parser would name it.
     */
    fun sequenceClass(sequenceId: Int): Int? {
        if (!RsDatabase.available) return null
        return RsDatabase.queryOne(
            "SELECT unknown_05 FROM sequences WHERE id = ?", sequenceId
        ) { rs -> rs.getInt(1).takeIf { !rs.wasNull() } }
    }

    /**
     * The param id the operator selected with
     * `-Dopennxt.experiment.combat.anim.npcslot`, or null when unset or
     * malformed. Any sequence-typed param is accepted, not only [NPC_SLOTS] -
     * TEST 2 in [NPC_PROVENANCE] widened the candidate set to four and refusing
     * 2914 here would hide the very rival the measurement turned up.
     */
    fun npcSlotConfigured(): Int? =
        System.getProperty("opennxt.experiment.combat.anim.npcslot")?.trim()?.toIntOrNull()

    /**
     * The sequence id the npc-slot experiment selects for [npcId], or null.
     *
     * Null - never a fallback - when the switch is unset, the param is not
     * sequence-typed, the npc has no combat class, or that class does not carry
     * the param. Same reasoning as [selected]: a fallback would put SOME
     * animation on screen whatever the operator typed and the experiment could
     * then never report a negative.
     */
    fun npcSelected(npcId: Int): Int? {
        val param = npcSlotConfigured() ?: return null
        if (!CacheVarTypes.isSequence(param)) return null
        return npcSequenceParams(npcId)[param]?.takeIf { it >= 0 }
    }

    /** The weapon-class struct an item points at, or null. */
    fun weaponClassOf(itemId: Int): Int? =
        extras("items", itemId)[WEAPON_CLASS_PARAM]?.takeIf { it >= 0 }

    /**
     * Every sequence-typed param on an item's weapon-class struct.
     *
     * Filtered by [CacheVarTypes.isSequence], i.e. by the cache's OWN declared
     * type for that param, not by whether the value happens to look like a
     * sequence id - see CacheVarTypes for why that test is vacuous.
     */
    fun sequenceParamsFor(itemId: Int): Map<Int, Int> {
        val struct = weaponClassOf(itemId) ?: return emptyMap()
        return extras("structs", struct).filterKeys { CacheVarTypes.isSequence(it) }
    }

    /** The configured experiment, or null when unset or malformed. */
    fun configured(): Pair<Int, Int>? {
        val raw = System.getProperty("opennxt.experiment.combat.anim.weapon") ?: return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val item = parts[0].toIntOrNull() ?: return null
        val param = parts[1].toIntOrNull() ?: return null
        return item to param
    }

    /**
     * The sequence id the experiment selects, or null.
     *
     * Every failure path is a null rather than a fallback. A fallback would put
     * SOME animation on screen whatever the operator typed, which would make the
     * experiment unable to report a negative - and the negative is half of what
     * it is for.
     */
    fun selected(): Int? {
        val (item, param) = configured() ?: return null
        if (!CacheVarTypes.isSequence(param)) return null
        return sequenceParamsFor(item)[param]?.takeIf { it >= 0 }
    }
}
