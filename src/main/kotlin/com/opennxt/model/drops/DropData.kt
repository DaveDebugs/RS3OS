package com.opennxt.model.drops

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Documented drop tables for a small set of monsters, with per-line provenance.
 *
 * ONE provenance only: DOCUMENTED. Every line in this loader was read off the
 * runescape.wiki article named in [DropLine.source] on [DropLine.retrieved]
 * (data/seed/drops_documented.json, schema opennxt.seed.drops_documented/1).
 * Nothing here comes from the cache and nothing is invented.
 *
 * VERBATIM vs PARSED, stated per field so callers know what they can trust:
 *  - [DropLine.quantity] and [DropLine.rarity] are the wiki's strings, kept
 *    verbatim ("5", "2-12", "64/128", "97.8/1000", "Always").
 *  - [DropLine.quantityRange] is parsed from the quantity string when it is a
 *    plain integer or an integer range; null when the string is empty or in
 *    any other shape. The verbatim string always survives alongside.
 *  - [DropLine.rarityNum] / [DropLine.rarityDen] are the fraction parsed by
 *    the build tool; null when the wiki string was not a fraction. Again the
 *    verbatim string is always there to audit against.
 *
 * SUB-TABLES ARE REFERENCES, NOT EXPANSIONS. A wiki row that points at a
 * shared table (herb table, gem table, rare drop table) or at an explicit
 * "Nothing" roll is kept as a [DropLine] whose item starts with "@"
 * ("@herb_table", "@nothing"), with [DropLine.isTableRef] = true and empty
 * [DropLine.itemIds]. Consumers can see that the roll exists and at what
 * rarity, without this layer pretending to know the sub-table's contents.
 * "@nothing" is a table ref like the others: a documented roll that yields
 * no item.
 *
 * THE GOBLIN DISTINCTION: a monster we asked the wiki about and got no drops
 * section back (Goblin -- two fetches returned combat stats but no drops
 * content) is NOT the same thing as a monster this file never covered.
 * [dropsForName]("Goblin") returns a [MonsterDrops] with an empty [MonsterDrops.drops]
 * list and a [MonsterDrops.note] saying exactly why it is empty;
 * [dropsForName]("Zilyana") returns null because nothing was ever fetched for
 * that name. Callers must not collapse the two.
 *
 * ID SPACE: npc_ids are game ids (npcs.id == game id in the unified item/npc
 * database this seed was built against). Names were matched to ids by NAME,
 * so one monster covers many ids; [MonsterDrops.npcMatchAmbiguous] flags that.
 * Item names were matched the same way; [DropLine.itemMatchAmbiguous] flags
 * multi-id matches and an EMPTY [DropLine.itemIds] on a non-table-ref line
 * means the wiki name did not resolve in the items table at all -- those
 * lines are enumerated by [unresolvedItemLines], never hidden.
 */
enum class DropCategory { ALWAYS, MAIN, TERTIARY, TABLE_REF }

/**
 * One documented drop-table row. There is no way to get a drop out of this
 * loader without its source URL and retrieval date riding along.
 */
data class DropLine(
    val category: DropCategory,
    /** Verbatim wiki item name; starts with "@" when [isTableRef]. */
    val itemName: String,
    /** Item ids the name matched in the items table. Empty for table refs, and for the unresolved names. */
    val itemIds: List<Int>,
    /** true when the item name matched more than one item id. */
    val itemMatchAmbiguous: Boolean,
    /** Verbatim wiki quantity string, e.g. "5", "2-12", "" for table refs. */
    val quantity: String,
    /** Parsed from [quantity]: "5" -> 5..5, "2-12" -> 2..12; null when empty or unparseable. */
    val quantityRange: IntRange?,
    /** Verbatim wiki rarity string, e.g. "64/128", "Always". */
    val rarity: String,
    /** Parsed fraction numerator, or null when [rarity] was not a fraction. */
    val rarityNum: Double?,
    /** Parsed fraction denominator, or null when [rarity] was not a fraction. */
    val rarityDen: Double?,
    /** Per-line wiki caveat ("1/512 on-task; pre-roll"), or null. */
    val note: String?,
    /** true for "@..." rows: a reference to a shared sub-table (or "@nothing"), never expanded here. */
    val isTableRef: Boolean,
    /**
     * The id that grounds when this line drops: the canonical id for the
     * few names that have one ([DropData.CANONICAL_ITEM_IDS] - Coins is 995, the id the starter
     * kit, `::item` and the client's coin pouch use, not 617 which is merely the lowest "Coins"),
     * else the lowest matched id (the documented tie-break). Null when nothing matched.
     */
    val itemId: Int? = DropData.chosenItemId(itemName, itemIds),
    /** Wiki article URL this line was read from. Never blank. */
    val source: String,
    /** Date the article was fetched. Never blank. */
    val retrieved: String
) {
    /** Sub-table name without the "@", e.g. "herb_table", "nothing"; null for real items. */
    val tableRefName: String? get() = if (isTableRef) itemName.removePrefix("@") else null

    override fun toString(): String =
        "${category.name.lowercase()} $itemName x$quantity @ $rarity " +
            "[documented: $source, retrieved $retrieved]"
}

/**
 * Everything documented for one wiki monster name: the id set the name maps
 * to, every drop line, and the monster-level note (empty-drops explanations
 * and fetch-truncation caveats live here).
 */
data class MonsterDrops(
    val name: String,
    val wikiPage: String,
    /** Game ids this name matched. One name usually covers many ids. */
    val npcIds: List<Int>,
    /** true when the name matched more than one npc id. */
    val npcMatchAmbiguous: Boolean,
    val drops: List<DropLine>,
    /** Monster-level caveat. NON-NULL whenever [drops] is empty -- it says why. */
    val note: String?,
    val source: String,
    val retrieved: String,
    val layer: String = "documented"
) {
    override fun toString(): String =
        "$name (${npcIds.size} npc ids, ${drops.size} drop lines) [$layer: $source]"
}

/** Counts as DECLARED by the json's counts block, for cross-checking against what actually loaded. */
data class DeclaredCounts(val monsters: Int, val dropLines: Int, val unresolvedItemNames: Int)

/**
 * One documented row of a shared sub-table (data/seed/drop_subtables.json,
 * schema opennxt.seed.drop_subtables/1). Same verbatim-vs-parsed contract as
 * [DropLine], and the same provenance guarantee: no line without its source
 * URL and retrieval date.
 */
data class SubTableLine(
    /** Which section of the wiki page the row sits in ("members", "f2p", "gem", "rare", "super_rare", "f2p_rare"). */
    val section: String,
    /** Verbatim item name; starts with "@" when [isTableRef] (a row that is itself a table reference). */
    val itemName: String,
    /** Item ids the name matched in the items table. Empty for table refs and unresolved names. */
    val itemIds: List<Int>,
    val itemMatchAmbiguous: Boolean,
    /** Verbatim quantity string, e.g. "1", "250-500", "45-55". */
    val quantity: String,
    /** Parsed from [quantity] like [DropLine.quantityRange]; null when unparseable. */
    val quantityRange: IntRange?,
    /** Verbatim rarity string, e.g. "32/128", "8/64-8/52", "~1/6400", "Common". */
    val rarity: String,
    /** Parsed fraction, null when [rarity] was not a plain fraction. */
    val rarityNum: Double?,
    val rarityDen: Double?,
    /** Per-row caveat ("members", "F2P; noted"), or null. */
    val note: String?,
    /** true for "@..." rows: this row points at another table, never expanded in place. */
    val isTableRef: Boolean,
    val source: String,
    val retrieved: String
) {
    val tableRefName: String? get() = if (isTableRef) itemName.removePrefix("@") else null

    override fun toString(): String =
        "[$section] $itemName x$quantity @ $rarity [documented: $source, retrieved $retrieved]"
}

/**
 * The documented contents of one shared sub-table. A table that appears here
 * was actually fetched; a table this seed does not contain (herb_seed_table,
 * uncommon_seed_table, seed_table, allotment_seed_table, imp tables,
 * "nothing") simply is not present, and [DropData.subTable] returns null for
 * it -- the honest answer, not a guess. Had a fetch failed twice, the table
 * would be present with an empty [lines] list and a [note] saying exactly
 * what happened (same rule as the Goblin distinction for monsters).
 */
data class SubTable(
    val name: String,
    val lines: List<SubTableLine>,
    /** Table-level caveat; NON-NULL whenever [lines] is empty -- it says why. */
    val note: String?,
    val source: String,
    val retrieved: String
) {
    override fun toString(): String = "@$name (${lines.size} lines) [documented: $source]"
}

/** Counts as DECLARED by drop_subtables.json's counts block. */
data class DeclaredSubTableCounts(val tables: Int, val entries: Int, val unresolvedItemNames: Int)

object DropData {

    private const val SCHEMA = "opennxt.seed.drops_documented/1"
    private const val SUB_SCHEMA = "opennxt.seed.drop_subtables/1"

    private val byName = LinkedHashMap<String, MonsterDrops>()
    private val byNpcId = HashMap<Int, MonsterDrops>()
    private val unresolved = ArrayList<Pair<String, DropLine>>()

 // ---- the bulk wiki layer: BELOW the hand file. An npc id the
    // hand file covers keeps the hand file's lines; every other id the wiki names gets the wiki's.
    private const val WIKI_SCHEMA = "opennxt.seed.drops_wiki/1"
    private val wikiByName = LinkedHashMap<String, MonsterDrops>()
    private val wikiByNpcId = HashMap<Int, MonsterDrops>()
    private val wikiUnresolved = ArrayList<Pair<String, DropLine>>()
    private var wikiDeclared = DeclaredCounts(0, 0, 0)
    private var declared = DeclaredCounts(0, 0, 0)

    private val subTablesByName = LinkedHashMap<String, SubTable>()
    private val subUnresolved = ArrayList<Pair<String, SubTableLine>>()
    private var subDeclared = DeclaredSubTableCounts(0, 0, 0)

    /** true when drop_subtables.json was present and parsed. false = no sub-table contents at all. */
    var subTablesLoaded: Boolean = false
        private set

    var loaded: Boolean = false
        private set

    var seedDir: Path = Paths.get(
        System.getProperty("opennxt.seed.dir") ?: "data/seed"
    )
        private set

    // ------------------------------------------------------------------ load

    @Synchronized
    fun load(dir: Path = seedDir): DropData {
        // tolerate being launched from somewhere other than the repo root
        seedDir = if (Files.isDirectory(dir)) dir
        else Paths.get("/tmp/work-drops/data/seed").let { if (Files.isDirectory(it)) it else dir }
        byName.clear(); byNpcId.clear(); unresolved.clear()

        val path = seedDir.resolve("drops_documented.json")
        val root = read(path) ?: throw IllegalStateException(
            "documented drops seed missing: $path"
        )
        val schema = root.strOrNull("_schema")
        if (schema != SCHEMA) throw IllegalStateException(
            "drops seed schema mismatch: expected $SCHEMA got $schema"
        )
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException(
            "drops seed has no counts block -- refusing to load uncountable data"
        )
        declared = DeclaredCounts(
            counts.get("monsters").asInt,
            counts.get("drop_lines").asInt,
            counts.get("unresolved_item_names").asInt
        )

        for (el in root.getAsJsonArray("monsters")) {
            val monster = parseMonster(el.asJsonObject, "documented", unresolved) ?: continue
            byName[monster.name] = monster
            for (id in monster.npcIds) byNpcId[id] = monster
        }
        loadWiki()
        loadSubTables()
        loaded = true
        return this
    }

    private fun loadWiki() {
        wikiByName.clear(); wikiByNpcId.clear(); wikiUnresolved.clear()
        val root = read(seedDir.resolve("drops_wiki.json")) ?: return
        val schema = root.strOrNull("_schema")
        if (schema != WIKI_SCHEMA) throw IllegalStateException("drops_wiki.json schema mismatch: expected $WIKI_SCHEMA got $schema")
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException("drops_wiki.json has no counts block")
        wikiDeclared = DeclaredCounts(counts.get("monsters").asInt, counts.get("drop_lines").asInt, counts.get("unresolved_item_names").asInt)
        for (el in root.getAsJsonArray("monsters")) {
            val monster = parseMonster(el.asJsonObject, "wiki", wikiUnresolved) ?: continue
            wikiByName[monster.name] = monster
            for (id in monster.npcIds) wikiByNpcId[id] = monster
        }
    }

    /** One `monsters[]` entry, either file - the same strict rules (source and retrieval date required). */
    private fun parseMonster(o: JsonObject, layer: String, unresolvedSink: MutableList<Pair<String, DropLine>>): MonsterDrops? {
        run {
            val name = o.strOrNull("name") ?: return null
            val src = o.strOrNull("source") ?: throw IllegalStateException(
                "monster \"$name\" has no source URL -- refusing to load unsourced drops"
            )
            val retrieved = o.strOrNull("retrieved") ?: throw IllegalStateException(
                "monster \"$name\" has no retrieval date -- refusing to load unsourced drops"
            )
            val npcIds = o.getAsJsonArray("npc_ids").map { it.asInt }
            val lines = ArrayList<DropLine>()
            for (dEl in o.getAsJsonArray("drops")) {
                val d = dEl.asJsonObject
                val item = d.strOrNull("item") ?: continue
                // "@..." rows are table refs whether or not the file flagged them
                val isRef = (d.has("is_table_ref") && !d.get("is_table_ref").isJsonNull
                        && d.get("is_table_ref").asBoolean) || item.startsWith("@")
                val quantity = d.strOrNull("quantity") ?: ""
                if (isInvertedRange(quantity)) invertedQuantityRanges++
                val rawCategory = parseCategory(d.strOrNull("category"), name, item)
                val num = d.dblOrNull("rarity_num"); val den = d.dblOrNull("rarity_den")
 // (audit E-02): the wiki files "Always" lines under whatever heading they
                // sit beneath, so 233 of them arrived as MAIN with p = 1/1 and, being first in the
                // cumulative roll, took EVERY roll - 1,063 later lines could never drop. A main line
                // whose fraction is >= 1 is an always-drop by definition; re-classed here, visibly.
                // (table references at p >= 1 are re-classed too - 75 of the 233 - and applyDamage
                // reports an always-ref as an unexpanded roll rather than letting it swallow the main roll)
                val reclass = rawCategory == DropCategory.MAIN && num != null && den != null && den != 0.0 && num / den >= 1.0
                if (reclass) reclassedAlways++
                val line = DropLine(
                    category = if (reclass) DropCategory.ALWAYS else rawCategory,
                    itemName = item,
                    itemIds = (d.getAsJsonArray("item_ids") ?: com.google.gson.JsonArray())
                        .map { it.asInt },
                    itemMatchAmbiguous = d.has("item_match_ambiguous")
                            && !d.get("item_match_ambiguous").isJsonNull
                            && d.get("item_match_ambiguous").asBoolean,
                    quantity = quantity,
                    quantityRange = parseQuantity(quantity),
                    rarity = d.strOrNull("rarity") ?: "",
                    rarityNum = d.dblOrNull("rarity_num"),
                    rarityDen = d.dblOrNull("rarity_den"),
                    note = (d.strOrNull("note") ?: "").let { n ->
                        if (!reclass) d.strOrNull("note")
                        else (if (n.isEmpty()) "" else "$n; ") + "re-classed MAIN -> ALWAYS at load: fraction >= 1"
                    },
                    isTableRef = isRef,
                    source = src,
                    retrieved = retrieved
                )
                lines.add(line)
                if (!line.isTableRef && line.itemIds.isEmpty()) unresolvedSink.add(name to line)
            }
            return MonsterDrops(
                name = name,
                wikiPage = o.strOrNull("wiki_page") ?: name,
                npcIds = npcIds,
                npcMatchAmbiguous = o.has("npc_match_ambiguous")
                        && !o.get("npc_match_ambiguous").isJsonNull
                        && o.get("npc_match_ambiguous").asBoolean,
                drops = lines,
                note = o.strOrNull("note"),
                source = src,
                retrieved = retrieved,
                layer = layer
            )
        }
    }

    /**
     * Loads data/seed/drop_subtables.json when it exists. An ABSENT file is
     * a valid state -- it means no sub-table contents were ever fetched, and
     * [subTable] answers null for everything, which is the honest answer. A
     * PRESENT file is parsed strictly: schema, counts, and per-table
     * source/retrieved are all required, same rules as the monster seed.
     */
    private fun loadSubTables() {
        subTablesByName.clear(); subUnresolved.clear()
        subDeclared = DeclaredSubTableCounts(0, 0, 0)
        subTablesLoaded = false

        val root = read(seedDir.resolve("drop_subtables.json")) ?: return
        val schema = root.strOrNull("_schema")
        if (schema != SUB_SCHEMA) throw IllegalStateException(
            "drop sub-tables seed schema mismatch: expected $SUB_SCHEMA got $schema"
        )
        val counts = root.getAsJsonObject("counts") ?: throw IllegalStateException(
            "drop sub-tables seed has no counts block -- refusing to load uncountable data"
        )
        subDeclared = DeclaredSubTableCounts(
            counts.get("tables").asInt,
            counts.get("entries").asInt,
            counts.get("unresolved_item_names").asInt
        )

        val tables = root.getAsJsonObject("tables") ?: throw IllegalStateException(
            "drop sub-tables seed has no tables object"
        )
        for ((key, el) in tables.entrySet()) {
            val o = el.asJsonObject
            val src = o.strOrNull("source") ?: throw IllegalStateException(
                "sub-table \"$key\" has no source URL -- refusing to load unsourced contents"
            )
            val retrieved = o.strOrNull("retrieved") ?: throw IllegalStateException(
                "sub-table \"$key\" has no retrieval date -- refusing to load unsourced contents"
            )
            val note = o.strOrNull("note")
            val lines = ArrayList<SubTableLine>()
            for (eEl in o.getAsJsonArray("entries")) {
                val e = eEl.asJsonObject
                val item = e.strOrNull("item") ?: continue
                val isRef = (e.has("is_table_ref") && !e.get("is_table_ref").isJsonNull
                        && e.get("is_table_ref").asBoolean) || item.startsWith("@")
                val quantity = e.strOrNull("quantity") ?: ""
                val line = SubTableLine(
                    section = e.strOrNull("section") ?: throw IllegalStateException(
                        "sub-table \"$key\" row \"$item\" has no section"
                    ),
                    itemName = item,
                    itemIds = (e.getAsJsonArray("item_ids") ?: com.google.gson.JsonArray())
                        .map { it.asInt },
                    itemMatchAmbiguous = e.has("item_match_ambiguous")
                            && !e.get("item_match_ambiguous").isJsonNull
                            && e.get("item_match_ambiguous").asBoolean,
                    quantity = quantity,
                    quantityRange = parseQuantity(quantity),
                    rarity = e.strOrNull("rarity") ?: "",
                    rarityNum = e.dblOrNull("rarity_num"),
                    rarityDen = e.dblOrNull("rarity_den"),
                    note = e.strOrNull("note"),
                    isTableRef = isRef,
                    source = src,
                    retrieved = retrieved
                )
                lines.add(line)
                if (!line.isTableRef && line.itemIds.isEmpty()) subUnresolved.add(key to line)
            }
            if (lines.isEmpty() && note == null) throw IllegalStateException(
                "sub-table \"$key\" is empty with no note saying why -- refusing to load"
            )
            subTablesByName[key] = SubTable(key, lines, note, src, retrieved)
        }
        subTablesLoaded = true
    }

    private fun ensure() { if (!loaded) load() }

    private fun read(path: Path): JsonObject? {
        if (!Files.exists(path)) return null
        // gson 2.8.0 -- JsonParser.parseReader() only exists from 2.8.6
        Files.newBufferedReader(path).use { return JsonParser().parse(it).asJsonObject }
    }

    private fun JsonObject.strOrNull(k: String): String? =
        if (has(k) && !get(k).isJsonNull) get(k).asString else null

    private fun JsonObject.dblOrNull(k: String): Double? =
        if (has(k) && !get(k).isJsonNull) get(k).asDouble else null

    private fun parseCategory(raw: String?, monster: String, item: String): DropCategory =
        when (raw) {
            "always" -> DropCategory.ALWAYS
            "main" -> DropCategory.MAIN
            "tertiary" -> DropCategory.TERTIARY
            "table_ref" -> DropCategory.TABLE_REF
            else -> throw IllegalStateException(
                "unknown drop category \"$raw\" on $monster/$item -- refusing to guess"
            )
        }

    /** "5" -> 5..5, "2-12" -> 2..12; null for "" or any other shape. Never throws, never guesses. */
    fun parseQuantity(quantity: String): IntRange? {
        val m = QUANTITY_RE.matchEntire(quantity.trim()) ?: return null
        val lo = m.groupValues[1].toIntOrNull() ?: return null
        val hi = m.groupValues[2].let { if (it.isEmpty()) lo else it.toIntOrNull() ?: return null }
 // (audit E-03): "45000-5500" (three Automaton coin lines) is refused, not
        // returned as an empty range that later turns into a negative random bound mid-kill.
        if (lo > hi) return null
        return lo..hi
    }

    /** True when [quantity] is a range whose first number exceeds its second (refused by [parseQuantity]). */
    fun isInvertedRange(quantity: String): Boolean {
        val m = QUANTITY_RE.matchEntire(quantity.trim()) ?: return false
        val lo = m.groupValues[1].toIntOrNull() ?: return false
        val hi = m.groupValues[2].let { if (it.isEmpty()) lo else it.toIntOrNull() ?: return false }
        return lo > hi
    }

    /**
     * Names whose several matching item ids have ONE canonical member.
     * Not an allowlist of drops: a tie-break override for names where "lowest id" is the wrong
     * answer, each with the reason. Everything else keeps the lowest-id rule.
     */
    val CANONICAL_ITEM_IDS: Map<String, Int> = mapOf(
        // 617 / 995 / 8890 are all named "Coins"; 995 is the currency the starter kit
        // (PlayerInventory.starterKit), ::item and the client's money pouch use.
        "coins" to 995
    )

    /** The id a drop line grounds as: the canonical id when the name has one and it matched, else the lowest. */
    fun chosenItemId(itemName: String, itemIds: List<Int>): Int? {
        val canonical = CANONICAL_ITEM_IDS[itemName.trim().lowercase()]
        if (canonical != null && canonical in itemIds) return canonical
        return itemIds.minOrNull()
    }

    /** Lines re-classed MAIN -> ALWAYS at load because their fraction is >= 1 (audit E-02). */
    var reclassedAlways: Int = 0
        private set
    /** Lines whose quantity was an inverted range and parsed to null (audit E-03). */
    var invertedQuantityRanges: Int = 0
        private set

    private val QUANTITY_RE = Regex("""(\d+)(?:-(\d+))?""")

    // ------------------------------------------------------------- accessors

    /**
     * Drop lines for a game npc id. Empty list when the id belongs to a
     * monster whose fetch returned no drops (see the Goblin note); null when
     * NO documented monster covers this id at all.
     */
    fun dropsForNpc(gameId: Int): List<DropLine>? {
        ensure(); return (byNpcId[gameId] ?: wikiByNpcId[gameId])?.drops
    }

    fun monsterForNpc(gameId: Int): MonsterDrops? { ensure(); return byNpcId[gameId] ?: wikiByNpcId[gameId] }

    // ------------------------------------------------------ the wiki layer
    fun wikiMonsterForNpc(gameId: Int): MonsterDrops? { ensure(); return wikiByNpcId[gameId] }
    fun wikiMonsterByName(name: String): MonsterDrops? { ensure(); return wikiByName[name] }
    fun wikiMonsters(): Collection<MonsterDrops> { ensure(); return wikiByName.values }
    fun wikiMonsterCount(): Int { ensure(); return wikiByName.size }
    fun wikiDropLineCount(): Int { ensure(); return wikiByName.values.sumOf { it.drops.size } }
    fun wikiNpcIdCount(): Int { ensure(); return wikiByNpcId.size }
    fun wikiUnresolvedItemLines(): List<Pair<String, DropLine>> { ensure(); return wikiUnresolved }
    fun wikiDeclaredCounts(): DeclaredCounts { ensure(); return wikiDeclared }
    /** npc ids answered by the wiki layer only (the hand file does not cover them). */
    fun wikiOnlyNpcIdCount(): Int { ensure(); return wikiByNpcId.keys.count { it !in byNpcId } }

    /**
     * The documented record for a wiki monster name (exact match). Null means
     * this seed never covered the name; a non-null record with empty drops
     * means the fetch came back without a drops section -- read the note.
     */
    fun dropsForName(name: String): MonsterDrops? { ensure(); return byName[name] }

    fun monsters(): Collection<MonsterDrops> { ensure(); return byName.values }

    /**
     * Every non-table-ref line whose wiki item name resolved to NO item id,
     * as (monster name, line). These are known gaps, reported not hidden.
     */
    fun unresolvedItemLines(): List<Pair<String, DropLine>> { ensure(); return unresolved }

    // ------------------------------------------------------------ sub-tables

    /**
     * The documented contents of a shared sub-table, by name with or without
     * the "@" ("herb_table" or "@herb_table"). Null means the contents were
     * never fetched -- that includes "@nothing" (a roll that yields no item
     * has no contents to fetch) and every table drops_documented.json only
     * knows as an unexpanded reference. Null is the honest answer; callers
     * must not substitute a default table for it.
     */
    fun subTable(name: String): SubTable? {
        ensure(); return subTablesByName[name.removePrefix("@")]
    }

    /**
     * Resolves a table-ref [DropLine] (or a table-ref [SubTableLine] via
     * [subTable]) to the documented contents of the table it points at.
     * Returns null when [line] is not a table ref at all, and null when the
     * referenced table was never fetched ("@nothing", "@herb_seed_table",
     * "@uncommon_seed_table", ...). The null is kept deliberately: an
     * unfetched table resolving to anything else would be invention.
     */
    fun resolveTableRef(line: DropLine): SubTable? {
        val ref = line.tableRefName ?: return null
        return subTable(ref)
    }

    fun subTables(): Collection<SubTable> { ensure(); return subTablesByName.values }

    /** Sub-table rows whose item name resolved to NO item id, as (table name, line). */
    fun unresolvedSubTableLines(): List<Pair<String, SubTableLine>> { ensure(); return subUnresolved }

    /** Counts declared by drop_subtables.json's counts block. */
    fun declaredSubTableCounts(): DeclaredSubTableCounts { ensure(); return subDeclared }

    /** Sub-tables actually loaded. Must equal [declaredSubTableCounts].tables. */
    fun subTableCount(): Int { ensure(); return subTablesByName.size }

    /** Sub-table lines actually loaded. Must equal [declaredSubTableCounts].entries. */
    fun subTableLineCount(): Int { ensure(); return subTablesByName.values.sumOf { it.lines.size } }

    // ---------------------------------------------------------------- counts

    /** Counts as declared by the json's counts block. */
    fun declaredCounts(): DeclaredCounts { ensure(); return declared }

    /** Monsters actually loaded. Must equal [declaredCounts].monsters. */
    fun monsterCount(): Int { ensure(); return byName.size }

    /** Drop lines actually loaded. Must equal [declaredCounts].dropLines. */
    fun dropLineCount(): Int { ensure(); return byName.values.sumOf { it.drops.size } }

    /** Table-ref lines actually loaded (a subset of [dropLineCount]). */
    fun tableRefCount(): Int { ensure(); return byName.values.sumOf { m -> m.drops.count { it.isTableRef } } }

    /** Game npc ids some documented monster covers. */
    fun npcIdCount(): Int { ensure(); return byNpcId.size }
}
