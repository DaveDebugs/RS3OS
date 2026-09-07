package com.opennxt.model.combat

import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

/**
 * NPC animations on the wire, attributed to npc ids.
 */
object NpcAnimObservations {

    private val logger = KotlinLogging.logger { }

    const val DEATH_SWITCH = "opennxt.experiment.combat.anim.death"
    const val FILE = "npc_anims_observed.tsv"

    /** One attributed ANIMATION block. [anims] is the four smart32 slots as sent. */
    data class Observation(
        val observation: String, val packet: Int, val npcIndex: Int, val npcId: Int, val name: String?,
        val anims: IntArray, val delay: Int, val animationGroup: Int?,
        val deathCandidate: Boolean, val killCandidate: Boolean
    ) {
        /** the one id the four slots carry when they agree (they did on every observed row). */
        val sequence: Int? get() = anims.filter { it >= 0 }.distinct().singleOrNull()
    }

    /** A death animation with its provenance spelled out. */
    data class DeathAnimation(
        val sequence: Int,
        /** / -BY-GROUP / OPERATOR */
        val provenance: String,
        /** the animation group the extension went through, null when the npc itself was seen. */
        val viaAnimationGroup: Int?,
        val detail: String
    )

    private val byNpc = HashMap<Int, MutableList<Observation>>()
    private val killByNpc = HashMap<Int, LinkedHashSet<Int>>()
    private val killByGroup = HashMap<Int, LinkedHashSet<Int>>()
    private val groupCache = HashMap<Int, Int?>()

    var loaded: Boolean = false
        private set
    var rows: Int = 0
        private set
    var path: Path? = null
        private set

    @Synchronized
    fun load(dir: Path = SeedData.seedDir): NpcAnimObservations {
        byNpc.clear(); killByNpc.clear(); killByGroup.clear(); groupCache.clear()
        rows = 0
        val p = dir.resolve(FILE)
        path = p
        if (!Files.exists(p)) {
            logger.warn { "$p absent - no observed npc animations; death animations off unless -D$DEATH_SWITCH names one" }
            loaded = true
            return this
        }
        var header: List<String>? = null
        Files.readAllLines(p).forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val cols = line.split('\t')
            if (header == null) { header = cols; return@forEach }
            val h = header!!
            fun col(name: String) = cols.getOrNull(h.indexOf(name))?.takeIf { it.isNotEmpty() }
            val npcId = col("npc_id")?.toIntOrNull() ?: return@forEach
            val anims = intArrayOf(
                col("anim0")?.toIntOrNull() ?: -1, col("anim1")?.toIntOrNull() ?: -1,
                col("anim2")?.toIntOrNull() ?: -1, col("anim3")?.toIntOrNull() ?: -1
            )
            val obs = Observation(
                observation = col("observation") ?: "?", packet = col("packet")?.toIntOrNull() ?: -1,
                npcIndex = col("npc_index")?.toIntOrNull() ?: -1, npcId = npcId, name = col("name"),
                anims = anims, delay = col("delay")?.toIntOrNull() ?: 0,
                animationGroup = col("animgroup")?.toIntOrNull(),
                deathCandidate = col("death_candidate") == "1", killCandidate = col("kill_candidate") == "1"
            )
            rows++
            byNpc.getOrPut(npcId) { ArrayList() }.add(obs)
            if (obs.killCandidate) obs.sequence?.let { seq ->
                killByNpc.getOrPut(npcId) { LinkedHashSet() }.add(seq)
                animationGroup(npcId)?.let { g -> killByGroup.getOrPut(g) { LinkedHashSet() }.add(seq) }
            }
        }
        loaded = true
        logger.info {
            "npc animations: $rows rows over ${byNpc.size} npc ids; kill candidates on " +
                "${killByNpc.size} npc ids -> ${killByGroup.size} animation groups " +
                "(${killByGroup.entries.joinToString { "${it.key}:${it.value.joinToString("/")}" }})"
        }
        return this
    }

    private fun ensure() { if (!loaded) load() }

    private val lengthCache = HashMap<Int, Int?>()

    /**
     * How long sequence [sequence] plays, in game ticks, from `sequences_attr.frames`
     */
    fun sequenceLengthTicks(sequence: Int): Int? {
        // containsKey, not getOrPut: a null result must be cached too, or a sequence with
        // no frame list re-queries sqlite on the tick thread for every corpse every tick
 //.
        if (lengthCache.containsKey(sequence)) return lengthCache[sequence]
        val value: Int? = try {
            if (!RsDatabase.available) null else {
                val raw = RsDatabase.queryOne("SELECT value FROM sequences_attr WHERE id = ? AND field = 'frames'", sequence) { it.getString(1) }
                if (raw == null) null else {
                    val units = com.google.gson.JsonParser().parse(raw).asJsonArray.sumOf { it.asJsonObject.get("framelength").asInt }
                    if (units <= 0) null else (units * 20 + 599) / 600
                }
            }
        } catch (t: Exception) {
 // (audit T-04): a malformed frames row is memoised as "no length" and
            // logged once, instead of throwing out of the npc phase every tick until respawn.
            logger.warn { "sequence $sequence: frames row did not parse (${t.javaClass.simpleName}: ${t.message}); treating as no length" }
            null
        }
        lengthCache[sequence] = value
        return value
    }

    /** `npcs.animation_group` for a game id, from the definition database; null when absent. */
    fun animationGroup(npcId: Int): Int? {
        if (groupCache.containsKey(npcId)) return groupCache[npcId]      // null cached too (see sequenceLengthTicks)
        val value: Int? = if (!RsDatabase.available) null
        else RsDatabase.queryOne("SELECT animation_group FROM npcs WHERE game_id = ?", npcId) { rs ->
            val v = rs.getInt(1); if (rs.wasNull()) null else v
        }
        groupCache[npcId] = value
        return value
    }

    fun observed(npcId: Int): List<Observation> { ensure(); return byNpc[npcId] ?: emptyList() }
    fun observedNpcIds(): Set<Int> { ensure(); return byNpc.keys }
    fun killSequences(npcId: Int): Set<Int> { ensure(); return killByNpc[npcId] ?: emptySet() }
    fun killSequencesByGroup(): Map<Int, Set<Int>> { ensure(); return killByGroup }

    /** What the switch reads as: (absent), OFF, or the operator's sequence id. */
    val mode: String
        get() {
            val raw = System.getProperty(DEATH_SWITCH)?.trim() ?: return ""
            if (raw.equals("off", ignoreCase = true)) return "OFF"
            return raw.toIntOrNull()?.toString() ?: ""
        }

    /**
     * THE RULE, as a pure function so a check can drive it with maps the file
     * cannot produce (a group seen dying two ways, for instance).
     */
    fun resolve(npcId: Int, group: Int?, byId: Map<Int, Set<Int>>, byGroup: Map<Int, Set<Int>>): DeathAnimation? {
        byId[npcId]?.let { seen ->
            return if (seen.size == 1) DeathAnimation(seen.first(), "", null,
                "the reference client sent sequence ${seen.first()} as npc $npcId died on camera")
            else null                                       // seen dying two ways: refuse
        }
        val g = group ?: return null
        val seen = byGroup[g] ?: return null
        return if (seen.size == 1) DeathAnimation(seen.first(), "-BY-GROUP", g,
            "the reference client sent sequence ${seen.first()} as an npc sharing animation group $g died on camera")
        else null
    }

    /**
     * The death animation to queue for [npcId], or null - a refusal, never a default.
     * Precedence: the switch's `off` / operator id, then the observed rows by npc id,
     * then by animation group.
     */
    fun deathAnimation(npcId: Int): DeathAnimation? {
        val raw = System.getProperty(DEATH_SWITCH)?.trim()
        if (raw != null && raw.equals("off", ignoreCase = true)) return null
        raw?.toIntOrNull()?.let {
            return DeathAnimation(it, "OPERATOR", null, "-D$DEATH_SWITCH=$it names it for every npc")
        }
        ensure()
 //, later: Jagex's Bestiary sits between the id observation and the
        // group extension - official and per id, but not the wire. An id the protocol
        // saw dying keeps its observed value (the two agree on all seven anyway).
        if (killByNpc[npcId]?.size == 1) return resolve(npcId, null, killByNpc, emptyMap())
        NpcBestiary.deathAnimation(npcId)?.let {
            return DeathAnimation(it, "BESTIARY", null, "Jagex's Bestiary API lists sequence $it as npc $npcId's death animation")
        }
        return resolve(npcId, animationGroup(npcId), killByNpc, killByGroup)
    }
}
