package com.opennxt.tools.impl

import com.opennxt.model.combat.CombatStyle
import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatParams
import com.opennxt.model.combat.NpcWeakness
import com.opennxt.resources.sqlite.RsDatabase

/**
 * The whole-population sweeps behind [com.opennxt.model.combat.NpcCombatParams.WEAKNESS_CLASS]
 * and [com.opennxt.model.combat.NpcCombatParams.AFFINITY], recomputed at check
 * time instead of quoted from a comment.
 */
object ParamSweep {

    /** The assignment [com.opennxt.model.combat.NpcCombatDefinition.combatStyle] ships. */
    const val SHIPPED_STYLE = "deals Melee=1 Ranged=2 Magic=3"

    /** The assignment enum 16502 declares. */
    const val SHIPPED_WEAKNESS = "1=Magic 2=Melee 3=Ranged"

    class Result(
        val stylePopulation: Int,
        /** All six assignments, (label, agreeing NPCs), best first. */
        val styleScores: List<Pair<String, Int>>,
        val weaknessPopulation: Int,
        val weaknessScores: List<Pair<String, Int>>,
        /** weakness name -> how many NPCs have their unique max affinity in each of the 4 slots. */
        val affinityArgmax: List<Pair<String, IntArray>>
    )

    private val STYLES = listOf(CombatStyle.MELEE, CombatStyle.RANGED, CombatStyle.MAGIC)

    /** The three orderings-of-three, all six of them. */
    private fun permutations(): List<List<CombatStyle>> {
        val out = ArrayList<List<CombatStyle>>()
        for (a in STYLES) for (b in STYLES) for (c in STYLES) {
            if (a != b && b != c && a != c) out.add(listOf(a, b, c))
        }
        return out
    }

    /**
     * Reads every `extra` param map in one query. Deliberately not
     * [NpcCombat.loadParams] per id: that would be 32,687 queries to answer a
     * question about the same 12,269 rows.
     */
    private fun allParamMaps(): List<Map<Int, Int>> =
        RsDatabase.queryAll("SELECT value FROM npcs_attr WHERE field = 'extra'") {
            NpcCombat.parseParams(it.getString(1))
        }

    fun run(): Result {
        val maps = allParamMaps()

        // --- sweep 1: does param 26 identify the style the NPC DEALS? --------
        // "Deals X" is only unambiguous when exactly one damage param is
        // non-zero; NPCs with two are excluded rather than guessed at.
        val damageParam = mapOf(
            CombatStyle.MELEE to NpcCombatParams.MELEE_DAMAGE,
            CombatStyle.RANGED to NpcCombatParams.RANGED_DAMAGE,
            CombatStyle.MAGIC to NpcCombatParams.MAGIC_DAMAGE
        )
        val dealsAndValue = ArrayList<Pair<CombatStyle, Int>>()
        for (p in maps) {
            val raw = p[NpcCombatParams.WEAKNESS_CLASS] ?: continue
            if (raw !in 1..3) continue
            val nonZero = STYLES.filter { (p[damageParam[it]!!] ?: 0) != 0 }
            if (nonZero.size != 1) continue
            dealsAndValue.add(nonZero[0] to raw)
        }
        val styleScores = permutations().map { perm ->
            // perm[i] is the style assigned to raw value i+1
            val label = "deals %s=1 %s=2 %s=3".format(
                perm[0].name.lowercase().replaceFirstChar { it.uppercase() },
                perm[1].name.lowercase().replaceFirstChar { it.uppercase() },
                perm[2].name.lowercase().replaceFirstChar { it.uppercase() }
            )
            label to dealsAndValue.count { (deals, raw) -> perm[raw - 1] == deals }
        }.sortedByDescending { it.second }

        // --- sweep 2: does param 26 agree with param 2848's weakness band? ---
        val weakAndValue = ArrayList<Pair<CombatStyle, Int>>()
        for (p in maps) {
            val raw = p[NpcCombatParams.WEAKNESS_CLASS] ?: continue
            if (raw !in 1..3) continue
            val fine = p[NpcCombatParams.WEAKNESS]?.let(NpcWeakness::of) ?: continue
            val style = fine.style ?: continue   // None and Necromancy have no band
            weakAndValue.add(style to raw)
        }
        val weaknessScores = permutations().map { perm ->
            val label = "1=%s 2=%s 3=%s".format(
                perm[0].name.lowercase().replaceFirstChar { it.uppercase() },
                perm[1].name.lowercase().replaceFirstChar { it.uppercase() },
                perm[2].name.lowercase().replaceFirstChar { it.uppercase() }
            )
            label to weakAndValue.count { (weak, raw) -> perm[raw - 1] == weak }
        }.sortedByDescending { it.second }

        // --- sweep 3: do the affinity slots discriminate weakness? -----------
        // Restricted to NPCs carrying all four slots with a UNIQUE maximum,
        // because "which slot is largest" is the only question a tie can't
        // answer. If the assumed slot order meant anything, the largest slot
        // would move with the weakness. It does not.
        val argmax = LinkedHashMap<String, IntArray>()
        for (p in maps) {
            val fine = p[NpcCombatParams.WEAKNESS]?.let(NpcWeakness::of) ?: continue
            if (fine == NpcWeakness.NONE) continue
            val slots = NpcCombatParams.AFFINITY.map { p[it] }
            if (slots.any { it == null }) continue
            val values = slots.map { it!! }
            val max = values.max()
            if (values.count { it == max } != 1) continue
            val key = fine.style?.name ?: fine.name
            argmax.getOrPut(key) { IntArray(4) }[values.indexOf(max)]++
        }

        return Result(
            stylePopulation = dealsAndValue.size,
            styleScores = styleScores,
            weaknessPopulation = weakAndValue.size,
            weaknessScores = weaknessScores,
            affinityArgmax = argmax.entries.sortedBy { it.key }.map { it.key to it.value }
        )
    }
}
