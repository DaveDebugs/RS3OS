package com.opennxt.model.combat

/**
 * Combat experience per lifepoint of damage - the one owner of the rate.
 */
object CombatXp {

    /** The wiki's ratio on 80% of monsters (p10 = p90 = 0.05 over 3,764 infoboxes). */
    const val BASE_XP_PER_LIFEPOINT = 0.05

    const val CONSTITUTION_SHARE = 0.33

    fun selectedSkills(style: CombatStyle): List<com.opennxt.api.stat.Stat> = when (style) {
        CombatStyle.MELEE -> listOf(com.opennxt.api.stat.Stat.ATTACK, com.opennxt.api.stat.Stat.STRENGTH, com.opennxt.api.stat.Stat.DEFENCE)
        CombatStyle.RANGED -> listOf(com.opennxt.api.stat.Stat.RANGED, com.opennxt.api.stat.Stat.DEFENCE)
        CombatStyle.MAGIC -> listOf(com.opennxt.api.stat.Stat.MAGIC, com.opennxt.api.stat.Stat.DEFENCE)
    }

    /** The award for [damage] applied to [npcId] in [style]: the block split over the selected skills, plus Constitution. */
    fun splitAward(npcId: Int?, damage: Int, style: CombatStyle): Map<com.opennxt.api.stat.Stat, Double> {
        if (damage <= 0) return emptyMap()
        val block = styleXp(npcId, damage)
        val skills = selectedSkills(style)
        val out = LinkedHashMap<com.opennxt.api.stat.Stat, Double>()
        for (stat in skills) out[stat] = block / skills.size
        out[com.opennxt.api.stat.Stat.CONSTITUTION] = constitutionXp(block)
        return out
    }

    data class Rate(val xpPerLifepoint: Double, val provenance: String, val source: String) {
        override fun toString() = "%.4f xp/lp [%s: %s]".format(xpPerLifepoint, provenance, source)
    }

    val BASE = Rate(BASE_XP_PER_LIFEPOINT, "DOCUMENTED-BASE",
        "runescape.wiki Infobox Monster: experience / lifepoints is 0.05 on 80% of 3,764 monsters")

    /** The rate for damage dealt to npc [npcId]: the wiki's own figure for that id, else [BASE]. */
    fun rateFor(npcId: Int): Rate {
        val combat = SeedData.wikiCombat(npcId) ?: return BASE
        val xp = combat.experience ?: return BASE
        val lp = SeedData.wikiLifepoints(npcId)?.value ?: return BASE
        if (xp <= 0.0 || lp <= 0) return BASE
        return Rate(xp / lp, "DOCUMENTED",
            "${SeedData.wikiLifepoints(npcId)?.source}: experience $xp over $lp lifepoints")
    }

    /** Style-skill xp for [damage] lifepoints dealt to [npcId] (null npc: the base rate). */
    fun styleXp(npcId: Int?, damage: Int): Double =
        damage * (npcId?.let { rateFor(it) } ?: BASE).xpPerLifepoint

    /** Constitution's share of a style award. */
    fun constitutionXp(styleXp: Double): Double = styleXp * CONSTITUTION_SHARE

    val PROVENANCE: String =
        "DOCUMENTED: combat xp per lifepoint of damage is runescape.wiki's per-monster experience / lifepoints " +
            "(0.05 on 80% of 3,764 monsters, more on high-level ones - 'the experience earned per life point is not " +
            "constant'), paid ON THE KILL by damage share and split equally over the trained skills, with " +
            "Constitution at 0.33 of the block. on the way " +
            "in: the 0.4 xp/lp (4 tenths per damage) this server awarded previously was the RS2 hitpoint " +
            "rate applied to lifepoints - eight times the published figure - and the per-hit, 'a third' award " +
            "that replaced it earlier the same day paid a third too much and paid it early."
}
