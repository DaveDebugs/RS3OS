package com.opennxt.model.combat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Seed data for NPC lifepoints and combat stats, with per-value provenance.
 *
 * Three provenances, never mixed:
 *
 * - [Provenance.CACHE] decoded from the user's own RS3 cache
 * (data/seed/npc_cache.json, produced by
 * tools/seed_from_cache.py). Highest trust.
 * - [Provenance.DOCUMENTED] read off runescape.wiki
 * (data/seed/npc_documented.json, produced by
 * tools/build_documented.py). Carries a source URL
 * and a retrieval date.
 * - [Provenance.AUTHORED] invented to fill a gap. Loaded from an OPTIONAL
 * data/seed/npc_authored.json; every value from it is
 * tagged AUTHORED and counted separately in
 * [countsByProvenance].
 */
enum class Provenance { CACHE, DOCUMENTED, AUTHORED }

/** A value plus where it came from. There is no way to get a bare number out of this loader. */
data class SeededValue(
    val value: Int,
    val provenance: Provenance,
    /** table+prop for CACHE, URL for DOCUMENTED, free text for AUTHORED. */
    val source: String,
    /** retrieval date for DOCUMENTED, null otherwise. */
    val retrieved: String? = null,
    /** mapping caveats: name-match ambiguity, multi-struct maxima, wiki variant spread. */
    val note: String? = null,
    /**
     * Non-null ONLY on the variant-disambiguation layer: says which cache field
     * chose between the wiki's several published values. The value itself is
     * still the wiki's; this string is the part that is ours.
     */
    val disambiguation: String? = null
) {
    override fun toString(): String =
        "$value [${provenance.name.lowercase()}: $source" +
            (retrieved?.let { ", retrieved $it" } ?: "") + "]"
}

/**
 * Cache and documented both had an opinion for this npc id and they differed.
 *
 * [genuine] separates two very different things:
 *
 *  - genuine = true: the two SOURCES disagree. No documented value for this
 *    name equals any cache value for this name. This is a real finding.
 *  - genuine = false: a mapping artifact. The wiki publishes one number per
 *    *name* while the cache has one row per *npc id*, and a name covers many
 *    ids with different values (all the Hill giants from level 14 to 138 share
 *    the name "Hill giant"). Somewhere in that id set the two sources DO agree.
 *    Reported for completeness, but it is not evidence that anyone is wrong.
 */
data class SeedConflict(
    val npcId: Int,
    val name: String,
    val field: String,
    val cache: SeededValue,
    val documented: SeededValue,
    /** true when the two sources' whole value sets for this name are disjoint. */
    val genuine: Boolean,
    /** every value the cache has for this name. */
    val cacheValuesForName: List<Int>,
    /** every value the wiki documents for this name. */
    val documentedValuesForName: List<Int>
) {
    val winner: SeededValue get() = cache
    override fun toString(): String =
        "npc $npcId ($name) $field: cache=${cache.value} documented=${documented.value} " +
            "-> cache wins " +
            (if (genuine) "[GENUINE SOURCE DISAGREEMENT: cache$cacheValuesForName vs wiki$documentedValuesForName] "
             else "[mapping artifact: name covers cache$cacheValuesForName, wiki$documentedValuesForName] ") +
            "(${documented.source})"
}

/**
 * Which precedence layer produced a winning lifepoints value.
 *
 * [Provenance] answers "who published this number" and VARIANT values are
 * [Provenance.DOCUMENTED] because the number is the wiki's. This answers the
 * different question "which rule handed it over", so a caller can tell an
 * unambiguous wiki value apart from one this loader picked out of a variant
 * table. Precedence is declaration order: CACHE > WIKI > BESTIARY > DOCUMENTED > VARIANT > AUTHORED.
 */
enum class LifepointsLayer { CACHE, WIKI, BESTIARY, DOCUMENTED, VARIANT, AUTHORED }

/**
 * One runescape.wiki page that publishes a variant table and NO canonical
 * lifepoints value (`lifepoints: null`, `value_ambiguous: true`).
 *
 * [levelToLifepoints] is the page's own table collapsed to combat level -> the
 * distinct lifepoints values published at that level. A level with more than
 * one value is what the rule refuses; there are 8 such npc ids across the 21
 * pages, and they are refused rather than broken by a tiebreak.
 */
data class VariantPage(
    val name: String,
    val wikiPage: String?,
    val source: String,
    val retrieved: String?,
    val levelToLifepoints: Map<Int, List<Int>>,
    val npcIds: List<Int>
)

/**
 * The outcome of applying the rule to one npc id. Every id on a variant page
 * lands in exactly one of these and every one is counted - a refusal is a
 * result, not a gap.
 */
enum class VariantOutcome {
    /** the npc's combat level names exactly one lifepoints value */
    RESOLVED,
    /** npcs.combat is 0 or absent: not a combat npc. THE LEVEL-ZERO TRAP. */
    NOT_A_COMBAT_NPC,
    /** the page publishes no variant at this combat level */
    LEVEL_NOT_IN_VARIANTS,
    /** the page publishes several different lifepoints at this level */
    LEVEL_MULTI_VALUED
}

/** A resolution or a refusal, with the level that drove it. */
data class VariantResult(val outcome: VariantOutcome, val lifepoints: Int?, val level: Int?)

data class WikiCombat(
    val defence: Int?, val attack: Int?, val ranged: Int?, val magic: Int?, val necromancy: Int?,
    val maxMelee: Int?, val maxRanged: Int?, val maxMagic: Int?, val maxNecromancy: Int?, val maxSpec: Int?,
    val accMelee: Int?, val accRanged: Int?, val accMagic: Int?, val accNecromancy: Int?,
    val affWeakness: Int?, val affMelee: Int?, val affRanged: Int?, val affMagic: Int?,
    /** combat xp per kill as the wiki publishes it (Chicken 12.5). */
    val experience: Double?,
    val slayerLevel: Int?, val slayerXp: Double?, val slayerCategory: String?,
    /** the wiki's weakness text, e.g. "Fire", "Arrows", "Nothing" - enum 7733's label for param 2848. */
    val weakness: String?,
    val style: String?, val primaryStyle: String?,
    /** true/false for a plain yes/no; null when blank or qualified ("no, unless ..."). */
    val aggressive: Boolean?, val aggressiveRaw: String?,
    val poisonous: Boolean?,
    val immuneToPoison: Boolean?, val immuneToStun: Boolean?, val immuneToDeflect: Boolean?, val immuneToDrain: Boolean?
)

object SeedData {

    private val cacheLp = HashMap<Int, SeededValue>()
    private val docLp = HashMap<Int, SeededValue>()
    private val authoredLp = HashMap<Int, SeededValue>()

 // ------------------------------------------------ wiki-by-id layer
    /** npc id -> the lifepoints value the wiki's infobox published FOR THAT ID. */
    private val wikiLp = HashMap<Int, SeededValue>()
    /** npc ids the generator REFUSED: several wiki rows, several values, the cache could not choose. */
    private val wikiRefused = LinkedHashSet<Int>()
    /** npc id -> every lifepoints value any wiki row naming that id published (the chosen one first). */
    private val wikiValuesOf = HashMap<Int, List<Int>>()
    /** cache and wiki both had an opinion for the id and they differed; the cache wins. */
    private val wikiConflicts = ArrayList<SeedConflict>()
    /** npc id -> Jagex's Bestiary lifepoints (layer BESTIARY, below WIKI). */
    private val bestiaryLp = HashMap<Int, SeededValue>()
    /** the generator's own `counts` block, verbatim, for the boot line and the check. */
    private val wikiCounts = LinkedHashMap<String, Int>()
    /** npc id -> the rest of its infobox row (schema 2); empty for a schema-1 file. */
    private val wikiCombat = HashMap<Int, WikiCombat>()
    /** npc id -> the generator's per-field cross-check against the cache params (null = one side missing). */
    private val wikiCross = HashMap<Int, Map<String, Boolean?>>()
    /** the `retrieved` date of the wiki pull, or null when the file is absent. */
    var wikiRetrieved: String? = null
        private set

    private val cacheCb = HashMap<Int, SeededValue>()
    private val docCb = HashMap<Int, SeededValue>()
    private val authoredCb = HashMap<Int, SeededValue>()

    private val names = HashMap<Int, String>()

    /** npc id -> the documented entry's name, for name-level conflict grouping. */
    private val docNameOf = HashMap<Int, String>()
    /** documented name -> every lifepoints / combat value the wiki lists for it. */
    private val docLpVariants = HashMap<String, List<Int>>()
    private val docCbVariants = HashMap<String, List<Int>>()
    /** npc id -> every boss-struct maximum the cache has for that npc's name group. */
    private val cacheLpSetOf = HashMap<Int, List<Int>>()

    private val lpConflicts = ArrayList<SeedConflict>()
    private val cbConflicts = ArrayList<SeedConflict>()

    /** Encounter-level rows that could not be attached to any npc id at all. */
    private val unattachedBosses = ArrayList<String>()

    // --------------------------------------------- variant-disambiguation layer
    /** npc id -> the wiki value picked for it by [resolveVariant]. */
    private val variantLp = HashMap<Int, SeededValue>()
    /** the 21 pages that publish a variant table and no canonical value. */
    private val variantPages = ArrayList<VariantPage>()
    /** every npc id on those pages, resolved or refused, with its outcome. */
    private val variantOutcomes = LinkedHashMap<Int, VariantResult>()
    /** the 15 ids where the cache also has an opinion; the cache wins. */
    private val variantShadowed = ArrayList<Int>()

    var loaded: Boolean = false
        private set

    var seedDir: Path = Paths.get(
        System.getProperty("opennxt.seed.dir") ?: "data/seed"
    )
        private set

    // ------------------------------------------------------------------ load

    @Synchronized
    fun load(dir: Path = seedDir): SeedData {
        // tolerate being launched from somewhere other than the repo root
        seedDir = if (Files.isDirectory(dir)) dir
        else Paths.get("/tmp/opennxt/data/seed").let { if (Files.isDirectory(it)) it else dir }
        cacheLp.clear(); docLp.clear(); authoredLp.clear()
        cacheCb.clear(); docCb.clear(); authoredCb.clear()
        names.clear(); lpConflicts.clear(); cbConflicts.clear(); unattachedBosses.clear()
        docNameOf.clear(); docLpVariants.clear(); docCbVariants.clear(); cacheLpSetOf.clear()
        variantLp.clear(); variantPages.clear(); variantOutcomes.clear(); variantShadowed.clear()
        wikiLp.clear(); wikiRefused.clear(); wikiValuesOf.clear(); wikiConflicts.clear()
        wikiCounts.clear(); wikiCombat.clear(); wikiCross.clear(); wikiRetrieved = null
        bestiaryLp.clear()

        loadCache(seedDir.resolve("npc_cache.json"))
        if (wikiEnabled) { loadWiki(seedDir.resolve("npc_wiki.json")); loadBestiary() }
        loadDocumented(seedDir.resolve("npc_documented.json"))
        loadAuthored(seedDir.resolve("npc_authored.json"))
        disambiguateVariants()
        detectConflicts()
        loaded = true
        return this
    }

    private fun ensure() { if (!loaded) load() }

    private fun read(path: Path): JsonObject? {
        if (!Files.exists(path)) return null
        // gson 2.8.0 -- JsonParser.parseReader() only exists from 2.8.6
        Files.newBufferedReader(path).use { return JsonParser().parse(it).asJsonObject }
    }

    private fun JsonObject.intOrNull(k: String): Int? =
        if (has(k) && !get(k).isJsonNull) get(k).asInt else null

    private fun JsonObject.strOrNull(k: String): String? =
        if (has(k) && !get(k).isJsonNull) get(k).asString else null

    private fun loadCache(path: Path) {
        val root = read(path) ?: throw IllegalStateException(
            "cache seed missing: $path (run tools/seed_from_cache.py)"
        )

        // ---- boss health-bar maxima, grouped by name so multi-struct names collapse once
        data class Group(val lps: MutableList<Int>, val structs: MutableList<Int>,
                         val npcIds: MutableSet<Int>, var ambiguous: Boolean)

        val groups = LinkedHashMap<String, Group>()
        for (el in root.getAsJsonArray("boss_encounters")) {
            val o = el.asJsonObject
            val name = o.strOrNull("name") ?: continue
            val lp = o.intOrNull("lifepoints") ?: continue
            val g = groups.getOrPut(name) { Group(ArrayList(), ArrayList(), LinkedHashSet(), false) }
            g.lps.add(lp)
            o.intOrNull("struct_game_id")?.let { g.structs.add(it) }
            val matches = o.getAsJsonArray("npc_name_matches") ?: JsonArray()
            for (m in matches) {
                val mo = m.asJsonObject
                mo.intOrNull("npc_id")?.let { g.npcIds.add(it) }
            }
            if (o.has("npc_match_ambiguous") && o.get("npc_match_ambiguous").asBoolean) g.ambiguous = true
        }

        for ((name, g) in groups) {
            if (g.npcIds.isEmpty()) { unattachedBosses.add(name); continue }
            val value = g.lps.max()
            val notes = StringBuilder("boss health-bar maximum for encounter struct(s) ")
            notes.append(g.structs.joinToString(",")).append(" (structs.game_id)")
            if (g.lps.distinct().size > 1) {
                notes.append("; this name has ").append(g.lps.size)
                    .append(" structs with maxima ").append(g.lps.sorted().joinToString(","))
                    .append(" -- MAX chosen by the loader, not a cache fact")
            }
            if (g.ambiguous) {
                notes.append("; attached by NAME to ").append(g.npcIds.size)
                    .append(" npc ids that share the name \"").append(name).append("\"")
            }
            val sv = SeededValue(value, Provenance.CACHE, "struct_param prop 8850",
                note = notes.toString())
            val lpSet = g.lps.distinct().sorted()
            for (id in g.npcIds) { cacheLp[id] = sv; cacheLpSetOf[id] = lpSet }
        }

        // ---- combat levels: exact per-npc, no name matching involved
        for (el in root.getAsJsonArray("npc_stats")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            o.strOrNull("name")?.let { names[id] = it }
            val cl = if (o.has("combat_level")) o.getAsJsonObject("combat_level") else null
            if (cl != null) {
                cacheCb[id] = SeededValue(cl.get("value").asInt, Provenance.CACHE,
                    cl.strOrNull("source") ?: "npcs.combat")
            }
        }
    }

    private fun loadDocumented(path: Path) {
        val root = read(path) ?: throw IllegalStateException(
            "documented seed missing: $path (run tools/build_documented.py)"
        )
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val name = o.strOrNull("name") ?: continue
            val ids = o.getAsJsonArray("npc_ids") ?: continue
            val src = o.strOrNull("source") ?: "runescape.wiki"
            val retrieved = o.strOrNull("retrieved")
            val ambiguousIds = o.has("match_ambiguous") && o.get("match_ambiguous").asBoolean
            val variants = o.getAsJsonArray("distinct_documented_lifepoints")
            val note = buildString {
                append("wiki infobox, variant \"").append(o.strOrNull("primary_variant")).append('"')
                if (variants != null && variants.size() > 1) {
                    append("; wiki also documents ").append(variants.joinToString(",") { it.asString })
                }
                if (ambiguousIds) append("; name maps to ").append(ids.size()).append(" npc ids")
            }
            val lp = o.intOrNull("lifepoints")
            val cb = o.intOrNull("combat_level")
            docLpVariants[name] = (o.getAsJsonArray("distinct_documented_lifepoints")
                ?: JsonArray()).map { it.asInt }
            docCbVariants[name] = (o.getAsJsonArray("distinct_documented_combat_levels")
                ?: JsonArray()).map { it.asInt }

            // A page qualifies for the variant layer when it recorded NO canonical
            // lifepoints and at least one variant row carries both a combat level
 // and a lifepoints value.: 26 pages have
            // lifepoints == null, 21 qualify, and those 21 are exactly the pages
            // flagged value_ambiguous. The 5 that do not qualify are the 3 whose
            // fetch returned no combat infobox plus Croesus and Avatar of Amascut,
            // whose infobox lifepoints field is literally '?'.
            if (lp == null) {
                val table = LinkedHashMap<Int, MutableList<Int>>()
                for (vEl in (o.getAsJsonArray("variants") ?: JsonArray())) {
                    val v = vEl.asJsonObject
                    val vl = v.intOrNull("combat_level") ?: continue
                    val vlp = v.intOrNull("lifepoints") ?: continue
                    val bucket = table.getOrPut(vl) { ArrayList() }
                    if (!bucket.contains(vlp)) bucket.add(vlp)
                }
                if (table.isNotEmpty()) {
                    variantPages.add(VariantPage(
                        name = name,
                        wikiPage = o.strOrNull("wiki_page"),
                        source = src,
                        retrieved = retrieved,
                        levelToLifepoints = table.mapValues { it.value.sorted() },
                        npcIds = ids.map { it.asInt }
                    ))
                }
            }

            for (idEl in ids) {
                val id = idEl.asInt
                names.putIfAbsent(id, name)
                docNameOf[id] = name
                if (lp != null) docLp[id] = SeededValue(lp, Provenance.DOCUMENTED, src, retrieved, note)
                if (cb != null) docCb[id] = SeededValue(cb, Provenance.DOCUMENTED, src, retrieved, note)
            }
        }
    }

 // ------------------------------------------------ wiki-by-id layer

    /**
     * `-Dopennxt.seed.wiki=off` leaves `npc_wiki.json` unread, which restores the
     * pre- precedence exactly (CACHE > DOCUMENTED > VARIANT > AUTHORED).
     */
    val wikiEnabled: Boolean
        get() = System.getProperty("opennxt.seed.wiki") != "off"

    private fun loadWiki(path: Path) {
        val root = read(path) ?: return
        wikiRetrieved = root.strOrNull("retrieved")
        if (root.has("counts")) for ((k, v) in root.getAsJsonObject("counts").entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) wikiCounts[k] = v.asInt
        }
        for (el in root.getAsJsonArray("refused") ?: JsonArray()) {
            el.asJsonObject.intOrNull("npc_id")?.let { wikiRefused.add(it) }
        }
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            val lp = o.intOrNull("lifepoints") ?: continue
            val page = o.strOrNull("wiki_page") ?: "?"
            val version = o.strOrNull("version_name")?.takeIf { it.isNotBlank() }
            val note = buildString {
                append("wiki infobox names this npc id on page \"").append(page).append('"')
                if (version != null) append(", version \"").append(version).append('"')
                append("; cross-check vs cache: level ")
                append(if (o.has("cache_level_match") && o.get("cache_level_match").asBoolean) "match" else "MISMATCH")
                append(", armour ")
                append(if (o.has("cache_armour_match") && o.get("cache_armour_match").asBoolean) "match" else "MISMATCH")
            }
            val others = (o.getAsJsonArray("other_values") ?: JsonArray()).map { it.asInt }
            wikiValuesOf[id] = listOf(lp) + others
            if (o.has("combat") && o.get("combat").isJsonObject) {
                val cb = o.getAsJsonObject("combat")
                fun d(k: String): Double? = if (cb.has(k) && !cb.get(k).isJsonNull) cb.get(k).asDouble else null
                fun yn(k: String): Boolean? = when (cb.strOrNull(k)?.trim()?.lowercase()) {
                    "yes" -> true; "no" -> false; else -> null
                }
                wikiCombat[id] = WikiCombat(
                    defence = cb.intOrNull("defence"), attack = cb.intOrNull("attack"), ranged = cb.intOrNull("ranged"),
                    magic = cb.intOrNull("magic"), necromancy = cb.intOrNull("necromancy"),
                    maxMelee = cb.intOrNull("max_melee"), maxRanged = cb.intOrNull("max_ranged"),
                    maxMagic = cb.intOrNull("max_magic"), maxNecromancy = cb.intOrNull("max_necromancy"),
                    maxSpec = cb.intOrNull("max_spec"),
                    accMelee = cb.intOrNull("acc_melee"), accRanged = cb.intOrNull("acc_ranged"),
                    accMagic = cb.intOrNull("acc_magic"), accNecromancy = cb.intOrNull("acc_necromancy"),
                    affWeakness = cb.intOrNull("aff_weakness"), affMelee = cb.intOrNull("aff_melee"),
                    affRanged = cb.intOrNull("aff_ranged"), affMagic = cb.intOrNull("aff_magic"),
                    experience = d("experience"), slayerLevel = cb.intOrNull("slaylvl"), slayerXp = d("slayxp"),
                    slayerCategory = cb.strOrNull("slayercat"),
                    weakness = cb.strOrNull("weakness"), style = cb.strOrNull("style"),
                    primaryStyle = cb.strOrNull("primarystyle"),
                    aggressive = yn("aggressive"), aggressiveRaw = cb.strOrNull("aggressive"),
                    poisonous = yn("poisonous"),
                    immuneToPoison = yn("immune_to_poison"), immuneToStun = yn("immune_to_stun"),
                    immuneToDeflect = yn("immune_to_deflect"), immuneToDrain = yn("immune_to_drain")
                )
            }
            if (o.has("cross_check") && o.get("cross_check").isJsonObject) {
                val m = LinkedHashMap<String, Boolean?>()
                for ((k, v) in o.getAsJsonObject("cross_check").entrySet()) m[k] = if (v.isJsonNull) null else v.asBoolean
                wikiCross[id] = m
            }
            o.strOrNull("cache_name")?.let { names.putIfAbsent(id, it) }
            wikiLp[id] = SeededValue(
                lp, Provenance.DOCUMENTED, o.strOrNull("source") ?: "runescape.wiki",
                o.strOrNull("retrieved") ?: wikiRetrieved, note,
                disambiguation = o.strOrNull("disambiguation")
            )
        }
    }

    /** Jagex's Bestiary lifepoints, from [NpcBestiary] (optional file, empty layer when absent). */
    private fun loadBestiary() {
        NpcBestiary.load(seedDir)
        for (id in NpcBestiary.ids()) {
            val b = NpcBestiary.beast(id) ?: continue
            val lp = b.lifepoints ?: continue
            if (lp <= 0) continue
            names.putIfAbsent(id, b.name ?: continue)
            bestiaryLp[id] = SeededValue(
                lp, Provenance.DOCUMENTED,
                "https://secure.runescape.com/m=itemdb_rs/bestiary/beastData.json?beastid=$id",
                NpcBestiary.retrieved,
                note = "Jagex's Bestiary API row for npc $id" +
                    (if (b.wikiLifepoints != null && b.wikiLifepoints != lp) "; the wiki says ${b.wikiLifepoints} and outranks this" else "")
            )
        }
    }

    /** Optional. Absent in this repo -- see the class doc. */
    private fun loadAuthored(path: Path) {
        val root = read(path) ?: return
        for (el in root.getAsJsonArray("npcs")) {
            val o = el.asJsonObject
            val id = o.intOrNull("npc_id") ?: continue
            val why = o.strOrNull("reason") ?: "AUTHORED -- invented to fill a gap"
            o.strOrNull("name")?.let { names.putIfAbsent(id, it) }
            o.intOrNull("lifepoints")?.let {
                authoredLp[id] = SeededValue(it, Provenance.AUTHORED, why, note = "NOT from cache or wiki")
            }
            o.intOrNull("combat_level")?.let {
                authoredCb[id] = SeededValue(it, Provenance.AUTHORED, why, note = "NOT from cache or wiki")
            }
        }
    }

 // ------------------------------------------- variant disambiguation

    /**
     * `-Dopennxt.experiment.seed.variantDisambiguation=off` turns the layer off,
     * which restores the pre- behaviour exactly: 280 npc ids lose their
     * only lifepoints opinion and [lifepoints] returns null for them.
     */
    val variantDisambiguationEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.seed.variantDisambiguation") != "off"

    /**
     * What is measured, what is chosen, and what would settle it. Printed once
     * at boot by callers rather than per npc - see the PROVENANCE-essay bug in
     * [PlayerCombat]'s history.
     */
    val VARIANT_PROVENANCE: String =
        "variant disambiguation (-Dopennxt.experiment.seed.variantDisambiguation, DEFAULT ON): " +
            "- the lifepoints number itself, published by runescape.wiki on one of the 21 pages " +
            "that list several variants and name no canonical value, and the npc's combat level, read from " +
            "npcs.combat in the user's own cache. OURS - only the CHOICE of which published variant applies " +
            "to which npc id, by matching npcs.combat against the page's variant table and REFUSING any " +
            "level that maps to more than one lifepoints value. SCORE: 295 of the 659 candidate ids (44.8%); " +
            "8 refused multi-valued, 356 refused level-not-in-variants. CONTROLS THAT FIRED: npcs.combat+1 " +
            "resolves 62 (9.4%); a level drawn from the pool of 42 distinct variant levels resolves 74.0 on " +
            "average (sd 8.4, 50 draws); the 659 real levels permuted across the 659 ids resolve 49.4 (sd 6.6). " +
            "INDEPENDENT VALIDATION against a field the rule never reads (npcs_attr extra props 641/643/965, " +
            "the npc's max hit): r(ln lifepoints, ln max hit) = +0.896 over n=291, versus +0.005 for the same " +
            "assignment shuffled. GUARD: npcs.combat must be > 0 - it means 'not a combat npc', not 'level 0', " +
            "and the Zemouregal page publishes a level-0 variant with 32,400 lifepoints, so without the guard " +
            "41 non-combat npcs seed as 32,400-health monsters (336 unguarded vs 295 guarded). PROVENANCE " +
            "STAYS DOCUMENTED. WHAT WOULD SETTLE IT: a cache field that states lifepoints per npc id - there " +
            "is none, prop 8850 lives on encounter structs that carry no npc reference - or a wiki dump that " +
            "publishes the npc id next to each variant row. THAT DUMP NOW EXISTS - " +
            "data/seed/npc_wiki.json (layer WIKI, -Dopennxt.seed.wiki=off to drop it) reads the npc ids " +
            "the infobox itself lists and OUTRANKS this layer; this rule now only answers for ids the " +
            "wiki pull refused or does not name."

    /**
     * THE RULE, as a pure function, so the check can drive it with levels this
     * loader's own inputs cannot produce - in particular `0`.
     *
     * `level` is `npcs.combat`. It is null when the npc has no combat level at
     * all. Both null and 0 mean the same thing and are refused the same way:
     * measured over `npc_cache.json`, all 8,404 rows that carry a combat_level
     * agree exactly with `npcs.combat`, and all 990 that do not have
     * `npcs.combat = 0` - the two spellings of "not a combat npc" are the same
     * set, so treating them alike is not a convenience.
     *
     * THE `> 0` BELOW IS THE LEVEL-ZERO GUARD. Remove it and this resolves 336
     * instead of 295, the 41 extras all being non-combat npcs handed
     * Zemouregal's 32,400.
     */
    fun resolveVariant(level: Int?, table: Map<Int, List<Int>>): VariantResult {
        if (level == null || level <= 0) return VariantResult(VariantOutcome.NOT_A_COMBAT_NPC, null, level)
        val values = table[level]
            ?: return VariantResult(VariantOutcome.LEVEL_NOT_IN_VARIANTS, null, level)
        if (values.size != 1) return VariantResult(VariantOutcome.LEVEL_MULTI_VALUED, null, level)
        return VariantResult(VariantOutcome.RESOLVED, values[0], level)
    }

    /**
     * Applies [resolveVariant] to every npc id on every [VariantPage], using the
     * npc's own cache combat level. Populates [variantLp] and records an outcome
     * for every id including the refusals.
     *
     * Runs AFTER [loadCache] on purpose: the combat level it reads is
     * [cacheCb], i.e. `npcs.combat` decoded into `npc_cache.json`, and it must
     * not be the documented combat level - that would make the rule circular
     * (it would be matching the wiki's levels against the wiki's own table).
     */
    private fun disambiguateVariants() {
        if (!variantDisambiguationEnabled) return
        for (page in variantPages) {
            for (id in page.npcIds) {
                val level = cacheCb[id]?.takeIf { it.provenance == Provenance.CACHE }?.value
                val r = resolveVariant(level, page.levelToLifepoints)
                variantOutcomes[id] = r
                val lp = r.lifepoints ?: continue
                val published = page.levelToLifepoints.values.flatten().distinct().sorted()
                variantLp[id] = SeededValue(
                    lp, Provenance.DOCUMENTED, page.source, page.retrieved,
                    note = "wiki page \"${page.name}\" publishes ${published.size} lifepoints values " +
                        "${published.joinToString(",")} and names no canonical one",
                    disambiguation = "chosen by npcs.combat = $level against the page's variant table; " +
                        "a level mapping to more than one value would have been refused"
                )
                if (cacheLp.containsKey(id)) variantShadowed.add(id)
            }
        }
    }

    private fun detectConflicts() {
        // Every cache combat level the wiki's name group covers, so a per-id
        // mismatch can be told apart from a real source disagreement.
        val cbSetForName = HashMap<String, MutableSet<Int>>()
        for ((id, dn) in docNameOf) cacheCb[id]?.let {
            cbSetForName.getOrPut(dn) { LinkedHashSet() }.add(it.value)
        }

        for ((id, c) in cacheLp) {
            val d = docLp[id] ?: continue
            if (c.value == d.value) continue
            val dn = docNameOf[id]
            val cacheSet = cacheLpSetOf[id] ?: listOf(c.value)
            val docSet = (dn?.let { docLpVariants[it] } ?: listOf(d.value)).ifEmpty { listOf(d.value) }
            lpConflicts.add(SeedConflict(id, names[id] ?: "?", "lifepoints", c, d,
                genuine = cacheSet.intersect(docSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = docSet.sorted()))
        }
        for ((id, c) in cacheCb) {
            val d = docCb[id] ?: continue
            if (c.value == d.value) continue
            val dn = docNameOf[id]
            val cacheSet = (dn?.let { cbSetForName[it] }?.sorted()) ?: listOf(c.value)
            val docSet = (dn?.let { docCbVariants[it] } ?: listOf(d.value)).ifEmpty { listOf(d.value) }
            cbConflicts.add(SeedConflict(id, names[id] ?: "?", "combat_level", c, d,
                genuine = cacheSet.intersect(docSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = docSet.sorted()))
        }
        lpConflicts.sortBy { it.npcId }
        cbConflicts.sortBy { it.npcId }

        // cache vs the wiki-by-id layer. `genuine` here means: no value the cache
        // has for this id's name group equals ANY value the wiki published for the id.
        for ((id, c) in cacheLp) {
            val w = wikiLp[id] ?: continue
            if (c.value == w.value) continue
            val cacheSet = cacheLpSetOf[id] ?: listOf(c.value)
            val wikiSet = wikiValuesOf[id] ?: listOf(w.value)
            wikiConflicts.add(SeedConflict(id, names[id] ?: "?", "lifepoints", c, w,
                genuine = cacheSet.intersect(wikiSet.toSet()).isEmpty(),
                cacheValuesForName = cacheSet, documentedValuesForName = wikiSet.sorted()))
        }
        wikiConflicts.sortBy { it.npcId }
    }

    // ------------------------------------------------------------- accessors

    /**
     * PRECEDENCE, in one line: cache > wiki-by-id > unambiguous documented >
     * variant-disambiguated > authored. Null when no layer has an opinion.
     */
    fun lifepoints(npcId: Int): SeededValue? {
        ensure()
        return cacheLp[npcId] ?: wikiLp[npcId] ?: bestiaryLp[npcId] ?: docLp[npcId] ?: variantLp[npcId] ?: authoredLp[npcId]
    }

    /** Which precedence layer [lifepoints] would answer from. Null when none has an opinion. */
    fun layerOf(npcId: Int): LifepointsLayer? {
        ensure()
        return when {
            cacheLp.containsKey(npcId) -> LifepointsLayer.CACHE
            wikiLp.containsKey(npcId) -> LifepointsLayer.WIKI
            bestiaryLp.containsKey(npcId) -> LifepointsLayer.BESTIARY
            docLp.containsKey(npcId) -> LifepointsLayer.DOCUMENTED
            variantLp.containsKey(npcId) -> LifepointsLayer.VARIANT
            authoredLp.containsKey(npcId) -> LifepointsLayer.AUTHORED
            else -> null
        }
    }

    /** How many npc ids each precedence layer WINS. Sums to [size]. */
    fun layerCounts(): Map<LifepointsLayer, Int> {
        ensure()
        val m = LinkedHashMap<LifepointsLayer, Int>()
        for (l in LifepointsLayer.values()) m[l] = 0
        for (id in allIds()) layerOf(id)?.let { m[it] = m[it]!! + 1 }
        return m
    }

    private fun allIds(): Set<Int> {
        val ids = HashSet<Int>()
        ids.addAll(cacheLp.keys); ids.addAll(wikiLp.keys); ids.addAll(bestiaryLp.keys); ids.addAll(docLp.keys)
        ids.addAll(variantLp.keys); ids.addAll(authoredLp.keys)
        return ids
    }

    // --------------------------------------------------- wiki layer accessors

    /** The wiki-by-id value for this id, ignoring precedence. Null when the pull did not name it or refused it. */
    fun wikiLifepoints(npcId: Int): SeededValue? { ensure(); return wikiLp[npcId] }

    /** npc ids the wiki layer answers for (before precedence). */
    fun wikiIds(): Set<Int> { ensure(); return wikiLp.keys }

    /** npc ids the generator refused: several wiki rows, several values, and the cache could not choose. */
    fun wikiRefusedIds(): Set<Int> { ensure(); return wikiRefused }

    /** every lifepoints value any wiki row naming the id published, chosen value first. */
    fun wikiValuesFor(npcId: Int): List<Int> { ensure(); return wikiValuesOf[npcId] ?: emptyList() }

    /** the generator's `counts` block: pages, rows, accepted, refused, cross-check tallies. */
    fun wikiCounts(): Map<String, Int> { ensure(); return wikiCounts }

    /** Jagex's Bestiary lifepoints for this id, ignoring precedence. */
    fun bestiaryLifepoints(npcId: Int): SeededValue? { ensure(); return bestiaryLp[npcId] }
    fun bestiaryIds(): Set<Int> { ensure(); return bestiaryLp.keys }

    /** the rest of the wiki's infobox row for this id (schema 2); null for a refused or unnamed id. */
    fun wikiCombat(npcId: Int): WikiCombat? { ensure(); return wikiCombat[npcId] }

    /** the generator's per-field cross-check of the wiki row against the cache params, verbatim. */
    fun wikiCrossCheck(npcId: Int): Map<String, Boolean?>? { ensure(); return wikiCross[npcId] }

    /** cache and wiki disagree on the id; the cache wins and the wiki value is kept for audit. */
    fun wikiLifepointsConflicts(): List<SeedConflict> { ensure(); return wikiConflicts }
    fun genuineWikiLifepointsConflicts(): List<SeedConflict> { ensure(); return wikiConflicts.filter { it.genuine } }

    // ------------------------------------------------- variant layer accessors

    /** The 21 wiki pages that publish a variant table and no canonical value. */
    fun variantPages(): List<VariantPage> { ensure(); return variantPages }

    /** The variant-disambiguated value for this id, ignoring precedence. */
    fun variantLifepoints(npcId: Int): SeededValue? { ensure(); return variantLp[npcId] }

    /** Every npc id on a variant page, resolved or refused, with its outcome. */
    fun variantOutcomes(): Map<Int, VariantResult> { ensure(); return variantOutcomes }

    /** Outcome -> how many npc ids landed there. Refusals are results and are counted. */
    fun variantOutcomeCounts(): Map<VariantOutcome, Int> {
        ensure()
        val m = LinkedHashMap<VariantOutcome, Int>()
        for (o in VariantOutcome.values()) m[o] = 0
        for (r in variantOutcomes.values) m[r.outcome] = m[r.outcome]!! + 1
        return m
    }

    fun variantResolvedIds(): Set<Int> { ensure(); return variantLp.keys }

    /**
     * The 15 ids where the variant layer produced a value AND the cache already
     * had one. The cache wins; the variant value stays reachable via
     * [variantLifepoints] for audit. 13 Nomad + 2 Zemouregal.
     */
    fun variantShadowedByCache(): List<Int> { ensure(); return variantShadowed }

    fun combatLevel(npcId: Int): SeededValue? {
        ensure(); return cacheCb[npcId] ?: docCb[npcId] ?: authoredCb[npcId]
    }

    /** Every source's opinion, strongest first. Useful for auditing a single npc. */
    fun allLifepoints(npcId: Int): List<SeededValue> {
        ensure()
        return listOfNotNull(cacheLp[npcId], wikiLp[npcId], bestiaryLp[npcId], docLp[npcId], variantLp[npcId], authoredLp[npcId])
    }

    fun name(npcId: Int): String? { ensure(); return names[npcId] }

    fun lifepointsConflicts(): List<SeedConflict> { ensure(); return lpConflicts }
    fun combatLevelConflicts(): List<SeedConflict> { ensure(); return cbConflicts }

    /** Only the conflicts where the two SOURCES actually disagree. */
    fun genuineLifepointsConflicts(): List<SeedConflict> { ensure(); return lpConflicts.filter { it.genuine } }
    fun genuineCombatLevelConflicts(): List<SeedConflict> { ensure(); return cbConflicts.filter { it.genuine } }

    fun conflictFor(npcId: Int): SeedConflict? {
        ensure(); return lpConflicts.firstOrNull { it.npcId == npcId }
    }

    /** Boss encounter names whose lifepoints could not be attached to any npc id. */
    fun unattachedBossEncounters(): List<String> { ensure(); return unattachedBosses }

    /** How many npc ids get their winning lifepoints value from each provenance. */
    fun countsByProvenance(): Map<Provenance, Int> {
        ensure()
        val ids = allIds()
        val m = LinkedHashMap<Provenance, Int>()
        for (p in Provenance.values()) m[p] = 0
        for (id in ids) lifepoints(id)?.let { m[it.provenance] = m[it.provenance]!! + 1 }
        return m
    }

    fun combatCountsByProvenance(): Map<Provenance, Int> {
        ensure()
        val ids = HashSet<Int>().apply { addAll(cacheCb.keys); addAll(docCb.keys); addAll(authoredCb.keys) }
        val m = LinkedHashMap<Provenance, Int>()
        for (p in Provenance.values()) m[p] = 0
        for (id in ids) combatLevel(id)?.let { m[it.provenance] = m[it.provenance]!! + 1 }
        return m
    }

    fun size(): Int { ensure(); return allIds().size }
}
