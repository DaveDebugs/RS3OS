package com.opennxt.model.combat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

/**
 * Jagex's Bestiary, per npc id.
 */
object NpcBestiary {

    private val logger = KotlinLogging.logger { }

    const val FILE = "npc_bestiary.json"

    data class Beast(
        val npcId: Int, val name: String?, val level: Int?, val lifepoints: Int?,
        val attack: Int?, val defence: Int?, val magic: Int?, val ranged: Int?,
        val xp: Double?, val weakness: String?, val size: Int?,
        val aggressive: Boolean?, val poisonous: Boolean?, val attackable: Boolean?, val members: Boolean?,
        val slayerCategory: String?, val areas: List<String>,
        val deathAnimation: Int?, val attackAnimation: Int?, val rangeAnimation: Int?,
        val cacheLevelMatch: Boolean, val cacheNameMatch: Boolean,
        val wikiLifepoints: Int?, val wikiLifepointsMatch: Boolean, val observedDeathMatch: Boolean?
    )

    private val byId = HashMap<Int, Beast>()
    private val counts = LinkedHashMap<String, Int>()
    var loaded: Boolean = false
        private set
    var retrieved: String? = null
        private set
    var path: Path? = null
        private set

    private fun JsonObject.i(k: String): Int? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asInt }.getOrNull() else null
    private fun JsonObject.d(k: String): Double? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asDouble }.getOrNull() else null
    private fun JsonObject.s(k: String): String? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.b(k: String): Boolean? = if (has(k) && !get(k).isJsonNull && get(k).isJsonPrimitive) runCatching { get(k).asBoolean }.getOrNull() else null

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): NpcBestiary {
        byId.clear(); counts.clear(); retrieved = null
        val p = dir.resolve(FILE)
        path = p
        if (!Files.exists(p)) {
            logger.warn { "$p absent - no Bestiary rows; death/attack animations fall back to the observed layer only" }
            loaded = true
            return this
        }
        val root = Files.newBufferedReader(p).use { JsonParser().parse(it).asJsonObject }
        retrieved = root.s("retrieved")
        if (root.has("counts")) for ((k, v) in root.getAsJsonObject("counts").entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) counts[k] = v.asInt
        }
        for (el in root.getAsJsonArray("npcs") ?: JsonArray()) {
            val o = el.asJsonObject
            val id = o.i("npc_id") ?: continue
            byId[id] = Beast(
                npcId = id, name = o.s("name"), level = o.i("level"), lifepoints = o.i("lifepoints"),
                attack = o.i("attack"), defence = o.i("defence"), magic = o.i("magic"), ranged = o.i("ranged"),
                xp = o.d("xp"), weakness = o.s("weakness"), size = o.i("size"),
                aggressive = o.b("aggressive"), poisonous = o.b("poisonous"), attackable = o.b("attackable"),
                members = o.b("members"), slayerCategory = o.s("slayercat"),
                areas = (o.getAsJsonArray("areas") ?: JsonArray()).map { it.asString },
                deathAnimation = o.i("death_anim"), attackAnimation = o.i("attack_anim"), rangeAnimation = o.i("range_anim"),
                cacheLevelMatch = o.b("cache_level_match") == true, cacheNameMatch = o.b("cache_name_match") == true,
                wikiLifepoints = o.i("wiki_lifepoints"), wikiLifepointsMatch = o.b("wiki_lifepoints_match") == true,
                observedDeathMatch = o.b("observed_death_match")
            )
        }
        loaded = true
        logger.info {
            "bestiary: ${byId.size} npc ids from Jagex's Bestiary API (retrieved $retrieved); " +
                "${byId.values.count { it.deathAnimation != null }} with a death animation, " +
                "${byId.values.count { it.attackAnimation != null }} with an attack animation"
        }
        return this
    }

    private fun ensure() { if (!loaded) load() }

    fun beast(npcId: Int): Beast? { ensure(); return byId[npcId] }
    fun ids(): Set<Int> { ensure(); return byId.keys }
    fun counts(): Map<String, Int> { ensure(); return counts }
    fun deathAnimation(npcId: Int): Int? { ensure(); return byId[npcId]?.deathAnimation }
    fun attackAnimation(npcId: Int): Int? { ensure(); return byId[npcId]?.attackAnimation }
    fun aggressive(npcId: Int): Boolean? { ensure(); return byId[npcId]?.aggressive }
}
