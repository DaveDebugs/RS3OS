package com.opennxt.content.impl

/**
 * EVERY SKILLING RATE THIS PROJECT HAS ACTUALLY and nothing else.
 */
object SkillingRates {

    // ================================================================================ woodcutting
    /**
     * Logs by item name. Every row measured; the sample count is in the comment.
     *
     * TWO OF THESE CONTRADICT THE PUBLISHED RATES [Skilling.WOODCUTTING_TABLE] was built from, and the
     * measurement wins because it came off this build's own wire:
     *
     * Magic logs reconstructed 250.0 measured 365.0
     * Elder logs reconstructed 325.0 measured 425.0
     *
     * Willow, in the same session, matched its reconstructed 67.5 to the tenth over 11 samples - so
     * this is not a bonus-xp multiplier inflating the account. A multiplier would have moved willow
     * too. The two disagreeing rows are simply rates this build does not share with the published
     * table, and the reconstruction was wrong about them.
     *
     * Magic logs rests on a SINGLE sample and is flagged as such; elder is four, all exactly 425.
     */
    val WOODCUTTING_XP_TENTHS: Map<String, Int> = mapOf(
        "Logs" to 250,          // 7 samples across both accounts, every one exactly 25
        "Oak logs" to 375,      // 3 samples, alternating 38/37/38
        "Willow logs" to 675,   // 11 samples, 743 total -> [67.50, 67.59]
        "Magic logs" to 3650,   // ONE sample of 365. Provisional - get a second before trusting it.
        "Elder logs" to 4250    // 4 samples, all exactly 425
    )

    // =================================================================================== fishing
    /** Raw fish by item name. Five rows, all whole numbers, no fraction to recover in any of them. */
    val FISHING_XP_TENTHS: Map<String, Int> = mapOf(
        "Raw shrimps" to 100,   // 13 samples, all exactly 10
        "Raw mackerel" to 400,  // 2 samples, both exactly 40
        "Raw cod" to 900,       // 4 samples, all exactly 90
        "Raw lobster" to 1800,  // 3 samples, all exactly 180
        "Raw shark" to 2200     // 4 samples, all exactly 220
    )

    // =================================================================================== cooking
    /**
     * Cooked food by the item PRODUCED.
     *
     * THIS TABLE KILLED A HYPOTHESIS, which is the reason it is worth having. After two sessions,
     * Shrimps and Cooked meat had both paid exactly 33, and the note here read "whether that is a flat
     * low-tier rate or these two foods happen to share one is NOT established". A third observation
     * settled it: Mackerel 120, Cod 150, Lobster 240, Shark 420. Cooking is per-food and the shared 33
     * was a coincidence of two cheap foods.
     *
     * A BURN PAYS NOTHING. Burn messages arrive with no UPDATE_STAT beside them, so failure is 0 xp
     * rather than a reduced award.
     */
    val COOKING_XP_TENTHS: Map<String, Int> = mapOf(
        "Shrimps" to 330,       // 9 samples, all exactly 33
        "Cooked meat" to 330,   // 2 samples, both exactly 33
        "Mackerel" to 1200,     // 2 samples, both exactly 120
        "Cod" to 1500,          // 4 samples, all exactly 150
        "Lobster" to 2400,      // 3 samples, all exactly 240
        "Shark" to 4200         // 4 samples, all exactly 420
    )

    // ==================================================================================== prayer
    /** Bones buried, by item name. Both fractional, both bounded - see the class doc on Bat bones. */
    val BURY_XP_TENTHS: Map<String, Int> = mapOf(
        "Bones" to 45,          // 6 samples over two sets, alternating 4/5
        "Bat bones" to 53       // 8 samples, 42 total -> [5.19, 5.31]. 5.5 is EXCLUDED by the interval.
    )

    // ==================================================================================== mining
    /**
     * Tin ore (item 438). 9 samples in one run, alternating 8/7 -> 7.5.
     *
     * Still the only rock anybody has mined on camera. The maxed account's mining drops in the first
     * observation ranged 41..104 on rocks whose ids were never established, and a spread with no rock
     * attached to it is not a rate.
     */
    val MINING_XP_TENTHS: Map<String, Int> = mapOf("Tin ore" to 75)

    // ================================================================================ firemaking
    /** Logs burnt on the ground, by log. The two rates differ by the same ratio the WC pair does. */
    val FIREMAKING_XP_TENTHS: Map<String, Int> = mapOf(
        "Logs" to 400,          // 3 samples, all exactly 40
        "Oak logs" to 600       // 2 samples, both exactly 60
    )

    // ================================================================================= fletching
    /** By the item produced. Elder shafts come 15 at a time and the 22.5 is for the whole action. */
    val FLETCHING_XP_TENTHS: Map<String, Int> = mapOf(
        "Oak longbow (unstrung)" to 145,  // 3 samples, alternating 14/15/15
        "Elder shaft" to 225              // 4 samples of "cut the wood into 15 shafts" -> [22.38, 22.63]
    )

    // =================================================================================== farming
    /**
     * Pulling one weed from an allotment (Weeds, item 6055). 4.0 exactly.
     *
     * Measured TWICE on different accounts - 9 samples at Farming 1, 19 samples on the maxed account -
     * and both were 4.0 with no variance at all. That agreement is what makes it flat rather than
     * merely observed.
     */
    const val PULL_WEED_XP_TENTHS = 40

    /** Planting 3 onion seeds in an allotment. 2 samples: 10 and 9, so a fraction near 9.5. */
    const val PLANT_ONION_SEEDS_XP_TENTHS = 95

    // ================================================================================ divination
    //
    // Two energy tiers were recorded, on two accounts, and the whole loop was measured for each:
    // harvest a wisp, then convert what it gave you one of three ways.

    /** PALE tier - the low-level account. Harvest: 30 samples, 39 total, 1.3 exactly. */
    const val DIVINATION_PALE_HARVEST_XP_TENTHS = 13
    /** Pale memory -> experience. 12 samples, all exactly 4. */
    const val DIVINATION_PALE_MEMORY_TO_XP_TENTHS = 40
    /** Pale memory -> energy. 5 samples, all exactly 1. */
    const val DIVINATION_PALE_MEMORY_TO_ENERGY_XP_TENTHS = 10
    /** Pale memory + energy -> enhanced xp. 8 samples, all exactly 5. */
    const val DIVINATION_PALE_ENHANCED_XP_TENTHS = 50

    /** GLOWING tier - the maxed account. Harvest: 10 samples, 91 total -> [9.05, 9.15]. */
    const val DIVINATION_GLOWING_HARVEST_XP_TENTHS = 91
    /**
     * Harvesting an ENRICHED glowing memory instead. 5 samples, 93 total -> [18.50, 18.70].
     *
     * Almost exactly double the plain harvest (9.1 -> 18.6), and the enriched harvests are identifiable
     * because "Enriched glowing memory" (item 29398) lands in the backpack instead of the plain one.
     */
    const val DIVINATION_GLOWING_ENRICHED_HARVEST_XP_TENTHS = 186
    /** Glowing memory -> energy. 14 samples, all exactly 2. */
    const val DIVINATION_GLOWING_MEMORY_TO_ENERGY_XP_TENTHS = 20

    /**
     * Energy consumed by one enhanced conversion, at the pale tier.
     *
     * Read off the backpack rather than inferred: across eight consecutive conversions the Pale energy
     * stack ran 46, 41, 36, 31, 26, 21, 16, 11. Five per conversion, every time.
     */
    const val DIVINATION_ENHANCED_ENERGY_COST = 5

    // =============================================================================== archaeology
    /**
     * One excavation tick at a hotspot. 10 samples, 62 total -> [6.15, 6.25], so 6.2.
     *
     * A tick that YIELDS a material pays 59 instead - three of those at Ikovian memorial, each landing
     * a Third Age iron, White oak, Stormguard steel or Wings of War in the backpack. Both numbers are
     * for that one hotspot at Archaeology 93; whether either scales with the hotspot's level
     * requirement (this one states 70) is NOT established from one site.
     */
    const val ARCHAEOLOGY_EXCAVATE_TICK_XP_TENTHS = 62
    /** An excavation tick that yields a material. 3 clean samples, all exactly 59. */
    const val ARCHAEOLOGY_MATERIAL_FOUND_XP_TENTHS = 590

    // ==================================================================================== hunter
    /** Catching and looting an impling. 2 samples across two sessions, 25 and 225 - see below. */
    const val HUNTER_IMPLING_LOW_XP_TENTHS = 250
    /** The maxed account's impling paid 225. Different impling, almost certainly - not a scaling law. */
    const val HUNTER_IMPLING_HIGH_XP_TENTHS = 2250

    // ================================================================================== smithing
    /** One progress tick on an unfinished item. 9 samples, 30 total -> [3.28, 3.39], so 3.3. */
    const val SMITHING_PROGRESS_TICK_XP_TENTHS = 33

    // =================================================================================== agility
    /**
     * One obstacle. 4 samples at 4 exactly on the maxed account; 5 samples at 2 exactly on the
     * low-level one.
     *
     * WHICH obstacle is unknown in both cases - the outbound half of those sessions recorded that an
     * OPLOC fired and when, but not its payload, so no loc was ever identified. Two different courses
     * is the obvious explanation and it is not established. Neither number should be used for anything
     * until an observation names the obstacle.
     */
    const val AGILITY_OBSTACLE_LOW_XP_TENTHS = 20
    const val AGILITY_OBSTACLE_HIGH_XP_TENTHS = 40

 // ============================================,
    //
    // A fifth observation, and the first with BOTH halves working: outbound payloads aligned on 3,901 of
    // 3,958 rows, so for the first time a click could be named from the wire and the cache instead of
    // guessed at. Every row below is an xp drop paired with the MESSAGE_GAME that arrived within
    // 1,500 ms and names the action, so the attribution is the game's own words, not proximity alone.
    //
    // ONE LEVEL, NOT TWO. The rows above earned their "flat" label by paying the same at level 38 and
    // level 98. These did not: this was one maxed account, one session. They are exact and repeated
    // WITHIN that session, which is weaker. If any of them turns out to scale with level, it will be
    // these, and a second observation on a low-level account is what would settle it.

    // =================================================================================== fishing
    /** "You catch a lobster." 2 samples, both exactly 180, at Fishing 95. */
    const val FISHING_LOBSTER_XP_TENTHS = 180
    /** "You catch a tuna." 1 sample at Fishing 95. */
    const val FISHING_TUNA_XP_TENTHS = 160
    //
    // SWORDFISH IS DELIBERATELY ABSENT. Its one drop of 200 arrived with "Your quick reactions allow
    // you to catch TWO swordfish", so 200 is the pair and the single-fish rate is 100 only if the
    // proc pays exactly double - which nothing here establishes. Writing 100 would be an inference
    // wearing a measurement's clothes.

    // =================================================================================== cooking
    /** "You successfully cook a swordfish." 2 samples, both exactly 280, at Cooking 98. */
    const val COOKING_SWORDFISH_XP_TENTHS = 280
    /** "You roast a lobster." 2 samples, both exactly 240, at Cooking 98. */
    const val COOKING_LOBSTER_XP_TENTHS = 240

    // ================================================================================== thieving
    /**
     * "You pick the target's pocket." THIRTEEN samples, every one of them exactly 26, at Thieving 114.
     *
     * This supersedes the "one npc at 46/47, which is one npc and not a table" note that used to sit
     * in the not-recorded block below. Thirteen identical drops from one target is a rate.
     *
     * WHICH npc is still unknown, and that is the same gap Agility has: the click was OPNPC3 on local
     * INDEX 12529, and an index is not an npc id. The binding lives in the NPC_INFO update masks,
     * which nothing decodes yet. So this is the pickpocket rate for ONE unidentified npc.
     */
    const val THIEVING_PICKPOCKET_XP_TENTHS = 26
    /**
     * The coins that pickpocket paid, across 13 successes: 14,15,17,19,22,23,23,32,36,37,43,44,46.
     * A range, not a table - the distribution inside it was not sampled anywhere near enough times
     * to call it uniform, so this is the observed span and nothing more.
     */
    const val THIEVING_PICKPOCKET_COINS_MIN = 14
    const val THIEVING_PICKPOCKET_COINS_MAX = 46
    /**
     * "Your lightning-fast reactions allow you to steal double the loot." Observed once. The proc
     * exists; its rate does not follow from one sighting, and no number is written for it.
     */

    // =================================================================================== farming
    /** "You pick some dwellberries." 4 samples, all exactly 12, at Farming 99. */
    const val FARMING_PICK_DWELLBERRIES_XP_TENTHS = 12
    /** "You examine the bush ... in perfect health." 1 sample. A different action from picking. */
    const val FARMING_CHECK_BUSH_HEALTH_XP_TENTHS = 178

    // ================================================================================= invention
    /** One disassembly tick, beside "Materials gained: 1 x Junk". 3 samples, all exactly 1. */
    const val INVENTION_DISASSEMBLE_TICK_XP_TENTHS = 1

    // ==================================================================================== hunter
    /**
     * A THIRD impling value: 113, beside the existing 250 and 2250.
     *
     * That retires the LOW/HIGH reading of those two constants. Three values across three sessions is
     * not a scaling law with two ends; it is three different implings. The names below are kept so
     * nothing breaks, but they should be read as "impling A / B / C", not as a range.
     */
    const val HUNTER_IMPLING_MID_XP_TENTHS = 113

    // ===================================================================================== mining
    /**
     * MINING IS NOT FLAT AND NO CONSTANT IS WRITTEN FOR IT.
     *
     * Twelve drops at Mining 104 on Copper and Mithril: 44, 45, 45, 49, 53, 53, 57, 59, 60, 60, 73,
     * 314. Not a rate with rounding noise - a spread. RS3 mining pays per progress tick against a
     * rock's health, and the same observation handed over the inputs to that formula: see
     * [com.opennxt.resources.RockProspect949], where the reference client's own Prospect readout states each
     * rock's hitpoints, hardness and xp multiplier. The formula joining those to a per-tick number
     * is NOT yet derived, so nothing is asserted here.
     *
     * The 314 is presumably the depletion award rather than a tick. Presumably is not measured.
     */

    // ------------------------------------------------------------------ deliberately NOT recorded
    //
    // So that nothing here can be mistaken for complete:
    //
    // Combat every hit pays several stats at once. The sessions show the shape - a shared-xp
    //                 style pays Attack/Strength/Defence/Constitution together; a Ranged style pays
    //                 Defence/Constitution/Ranged; a Strength style pays Strength roughly double the
    //                 others (33 against 16/17). That is a FORMULA driven by damage, and the damage it
    // scales from was never observed beside it. No numbers are written down.
    //   Farming       the Player-Owned Farm actions - curing an animal (146, 450, 3), mucking out
    //                 (500, 500, 500, 500, 120) - vary hugely per event and depend on the animal and
    // its state, none of which was observed. Only the flat weed and seed rows are here.
    //   Crafting      one drop of 3 xp, and it arrived beside "you mis-hit the chisel and smash the gem
    //                 to pieces" - so it is not even established whether that xp was for the failure.
    //   Runecrafting  attempted and REFUSED twice: "You need a Water talisman to be able to access the
    //                 Water altar." The GATE is confirmed to exist; the rate behind it is not.
 // Thieving SUPERSEDED - see THIEVING_PICKPOCKET_XP_TENTHS above, 13 samples.
    //                 The npc behind it is still unidentified (an index, not an id).
    //   Mining        no flat rate exists to record; see the mining block above and RockProspect949.
    //   Everything else: Slayer, Summoning, Construction, Herblore, Magic, Dungeoneering, Invention
    // beyond a handful of disassembly ticks - no measured rate here.
}
