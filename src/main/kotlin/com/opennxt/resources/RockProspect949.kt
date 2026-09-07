package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

/**
 * WHAT THE REFERENCE CLIENT ITSELF SAYS A ROCK IS, read off the wire of a live 949 session.
 */
object RockProspect949 {

    private val logger = KotlinLogging.logger { }

    data class Rock(
        /** The loc id that was prospected. */
        val loc: Int,
        val name: String,
        /** Mining level required. Independently confirmed against dbtable 34 for all three rows. */
        val level: Int,
        /** Rock health. This is what governs depletion, and nothing else in this server has it. */
        val hitpoints: Int,
        val hardness: Int,
        val xpMultiplier: Int,
    )

    private val loaded: List<Rock> by lazy { load() }

    private fun load(): List<Rock> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("rock_prospect_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "RockProspect949: no ${file.path}. Rock hitpoints/hardness are unavailable; " +
                    "Prospect a rock during an observation and add the row."
            }
            return emptyList()
        }
        val rows = ArrayList<Rock>()
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("loc\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 6) return@forEachLine
                val loc = p[0].toIntOrNull() ?: return@forEachLine
                val lvl = p[2].toIntOrNull() ?: return@forEachLine
                val hp = p[3].toIntOrNull() ?: return@forEachLine
                val hard = p[4].toIntOrNull() ?: return@forEachLine
                val mult = p[5].toIntOrNull() ?: return@forEachLine
                rows.add(Rock(loc = loc, name = p[1], level = lvl, hitpoints = hp,
                              hardness = hard, xpMultiplier = mult))
            }
        } catch (e: Exception) {
            logger.warn(e) { "RockProspect949: could not read ${file.path}" }
            return emptyList()
        }
        logger.info { "RockProspect949: ${rows.size} rock(s) stated by the reference client's own Prospect readout" }
        return rows
    }

    fun all(): List<Rock> = loaded

    /** By the loc id that was prospected. */
    fun byLoc(loc: Int): Rock? = loaded.firstOrNull { it.loc == loc }

    /** By rock name, as the Prospect readout spelled it. */
    fun byName(name: String): Rock? = loaded.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The rocks whose level this INDEPENDENTLY CONFIRMS against [MiningTable949]'s cache-derived
     * table, and the ones it contradicts. Used by the check; a contradiction means one of the two
     * derivations is wrong and neither should be trusted until it is settled.
     */
    fun agreementWithCache(): Pair<List<String>, List<String>> {
        val levels = MiningTable949.levelsByRock()
        val agree = ArrayList<String>()
        val differ = ArrayList<String>()
        for (r in loaded) {
            val cached = levels[r.name] ?: continue
            if (cached == r.level) agree.add(r.name) else differ.add("${r.name} the reference client=${r.level} cache=$cached")
        }
        return agree to differ
    }

    fun describe(): String =
        if (loaded.isEmpty()) "RockProspect949: empty"
        else loaded.joinToString("; ") { "${it.name} lvl${it.level} hp${it.hitpoints} hard${it.hardness} x${it.xpMultiplier}" }
}
