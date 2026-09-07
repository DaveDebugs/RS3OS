package com.opennxt.content.impl

import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

/**
 * What a resource loc gives you, and where every number in that sentence came
 * from.
 */
object ResourceNodes {

    private val logger = KotlinLogging.logger { }

    /** Which skill a node belongs to. Decided by the ACTION the cache declares, never by the name. */
    enum class Kind { WOODCUTTING, MINING, GATHERING }

    /**
     * The literal option strings this module binds, with the loc-id counts they
     * were selected on. Chop/Chop down are the same skill; the cache spells it
     * both ways and both are bound.
     */
    const val CHOP_DOWN = "Chop down"   // 345 loc ids
    const val CHOP = "Chop"             // 262 loc ids
    const val MINE = "Mine"             // 777 loc ids

    /**
     * The HYPHENATED spelling. 39 loc ids declare `Chop-down`
     */
    const val CHOP_DOWN_HYPHEN = "Chop-down"   // 39 loc ids

    /**
     * Whether [action] is one of the three strings this module binds - i.e. one
     * of the ones `OpLocHandler.gatherIfResource` dispatches ITSELF, rather than
     * leaving to [LocWiring.routeOther].
     */
    fun isGatherAction(action: String): Boolean =
        action == CHOP_DOWN || action == CHOP_DOWN_HYPHEN || action == CHOP || action == MINE

    /**
     * Whether [action] is one of the three WOODCUTTING spellings - the chop subset of
     * [isGatherAction], with [MINE] taken out.
     */
    fun isChopAction(action: String): Boolean =
        action == CHOP_DOWN || action == CHOP_DOWN_HYPHEN || action == CHOP

    /**
     * A resolved yield: which item, chosen from which candidates, by which rule.
     *
     * [itemName] is the CACHE's item name that matched, so a log line can say
     * "Oak -> 'Oak logs' (1521)" and the reader can check both halves against
     * the database themselves.
     */
    data class Resolved(
        val locId: Int,
        val locName: String?,
        val kind: Kind,
        val itemId: Int,
        val itemName: String,
        val candidates: List<Int>
    ) {
        val ambiguous: Boolean get() = candidates.size > 1
        override fun toString() =
            "Resolved(loc $locId '${locName}' -> $itemId '$itemName'" +
                (if (ambiguous) " [AMBIGUOUS, ${candidates.size} ids: $candidates, lowest wins]" else "") + ")"
    }

    /** Why a loc yields nothing. Typed, so "not implemented" and "no such loc" never look alike. */
    sealed class Refusal {
        object NoDatabase : Refusal()
        data class UnknownLoc(val locId: Int) : Refusal()
        data class Unnamed(val locId: Int) : Refusal()
        data class NoMatchingItem(val locId: Int, val locName: String, val tried: List<String>) : Refusal() {
            override fun toString() =
                "NoMatchingItem(loc $locId '$locName': no item in the cache is named ${tried.joinToString(" or ") { "'$it'" }})"
        }
    }

    // ================================================================
    // ITEM NAME -> IDS, out of the cache
    // ================================================================

    /**
     * Every item name in the database to the ids carrying it, lowest first.
     *
     * Built once, in full - 44,232 rows indexing 34,465 distinct names - for the
     * same reason
     * [com.opennxt.content.SqliteDefinitions] builds its reverse index in full:
     * the question asked is "which id is named X", and answering it with a
     * `LIKE` per lookup would put a table scan inside a click handler.
     */
    private val itemIdsByName: Map<String, List<Int>> by lazy {
        if (!RsDatabase.available) {
            emptyMap()
        } else {
            RsDatabase.queryAll("SELECT id, name FROM items WHERE name IS NOT NULL AND name != ''") {
                it.getString("name") to it.getInt("id")
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.sorted() }
                .also { logger.info { "skilling: indexed ${it.size} distinct item names" } }
        }
    }

    /** Cache item names, exposed so a check can assert on the index rather than on a re-derivation. */
    fun itemIdsNamed(name: String): List<Int> = itemIdsByName[name] ?: emptyList()

    // ================================================================
    // THE AUTHORED NAME RULE
    // ================================================================

    /**
     * The candidate item names a woodcutting loc's name is tried against, in
     * order. AUTHORED - see the class doc. Returned as a list so a refusal can
     * report exactly what was looked for.
     */
    internal fun woodcuttingCandidateNames(locName: String): List<String> {
        var base = locName.trim()
        for (suffix in listOf(" tree", " Tree")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
                break
            }
        }
        val out = ArrayList<String>()
        out.add("$base logs")
        // The one irregular form in the corpus: 111 of the locs that declare
        // "Chop down" are named exactly "Tree", and their item is "Logs", not
        // "Tree logs". Stated here rather than smuggled in as a general
        // lowercase/plural rule. (111 is the count within the Chop-down
        // population, which is the only population this rule sees; 434 locs in
        // the whole cache are named "Tree". Both are unchanged by the complete
        // cache import - the count read 111 and 433 on the truncated one.)
        if (base == "Tree") out.add("Logs")
        return out
    }

    /**
     * The candidate item names a mining loc's name is tried against, or EMPTY
     * when the name does not end in a rock suffix.
     *
     * The suffix requirement is not cosmetic: without it the "the name is
     * itself an item name" fallback matched 61-71 arbitrary scenery locs per
     * 777-loc control sample. With it, the same control returns 0 five times
     * out of five.
     */
    internal fun miningCandidateNames(locName: String): List<String> {
        var base = locName.trim()
        var stripped = false
        for (suffix in listOf(" rock", " rocks", " Rock", " Rocks")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
                stripped = true
                break
            }
        }
        if (!stripped) {
            // The one other admitted form: the loc is named after the ore
            // itself. All 10 Daemonheim ore locs are - 82247 is called 'Novite
            // ore', not 'Novite rock' - and they are exactly the locs whose
            // level requirement the cache DOES state (param 23), so excluding
            // them would have thrown away the only grounded numbers here.
            //
            // Requiring the " ore" ending is what keeps this from becoming the
            // general "the loc name is an item name" fallback that leaked
            // 61-71 arbitrary scenery locs per 777-loc control sample. Measured
            // with the ending: resolution rises 297 -> 352 of 777 and the same
            // control still returns 0, five samples out of five.
            return if (base.endsWith(" ore")) listOf(base) else emptyList()
        }
        // "<base> ore" first: 'Copper rock' -> 'Copper ore', not the 'Copper'
        // that is also an item. Then the bare base, which is what 'Clay rock'
        // -> 'Clay' and 'Coal rock' -> 'Coal' need.
        return listOf("$base ore", base)
    }

    internal fun gatheringCandidateNames(locName: String): List<String> {
        val base = locName.trim()
        return listOf(base)
    }

    /**
     * The yield of [locId] under [kind], or a typed [Refusal].
     *
     * Does NOT check that the loc declares the action - that is
     * [com.opennxt.content.ContentRegistry]'s job and doing it twice in two
     * places is how the two come to disagree.
     */
    fun resolve(locId: Int, kind: Kind): Any {
        if (!RsDatabase.available) return Refusal.NoDatabase
        val def = SqliteLocCodec.load(locId) ?: return Refusal.UnknownLoc(locId)
        val name = def.name ?: return Refusal.Unnamed(locId)
        val tried = when (kind) {
            Kind.WOODCUTTING -> woodcuttingCandidateNames(name)
            Kind.MINING -> miningCandidateNames(name)
            Kind.GATHERING -> gatheringCandidateNames(name)
        }
        for (candidate in tried) {
            val ids = itemIdsByName[candidate] ?: continue
            if (ids.isEmpty()) continue
            return Resolved(locId, name, kind, ids.first(), candidate, ids)
        }
        return Refusal.NoMatchingItem(locId, name, tried)
    }

    /** [resolve]'s success case only, for callers that treat every refusal the same. */
    fun yieldOf(locId: Int, kind: Kind): Resolved? = resolve(locId, kind) as? Resolved

    // ================================================================
    // THE ONE GROUNDED NUMBER: PARAM 23
    // ================================================================

    /**
     * The cache-stated level requirement of [locId], or null.
     *
     * Read from `locs_attr` field `extra`, which stores the loc's param list as
     * JSON (`[{"prop":23,"intvalue":20,...}]`). Param 23's own row in `params`
     * declares `vartype 0`, i.e. integer - so the value is read as an int and
     * not as a string that happens to parse.
     *
     * 199 locs carry it. Where it is absent this returns null and
     * [SkillingTable] supplies an AUTHORED level that says so; it never
     * substitutes a guess under this function's name.
     */
    fun levelFromCache(locId: Int): Int? {
        if (!RsDatabase.available) return null
        return cachedParam23[locId]
    }

    private val cachedParam23: Map<Int, Int> by lazy {
        if (!RsDatabase.available) {
            emptyMap()
        } else {
            val out = HashMap<Int, Int>()
            RsDatabase.queryAll("SELECT id, value FROM locs_attr WHERE field = 'extra'") {
                it.getInt("id") to (it.getString("value") ?: "")
            }.forEach { (id, json) ->
                // Deliberately a narrow scan rather than a JSON parser: this is
                // the only param this file reads, and pulling a parser in for
                // one integer would hide the shape of the data being trusted.
                val marker = "\"prop\":23,"
                var idx = json.indexOf(marker)
                while (idx >= 0) {
                    val valueKey = json.indexOf("\"intvalue\":", idx)
                    if (valueKey >= 0) {
                        val start = valueKey + "\"intvalue\":".length
                        val end = json.indexOfFirst(start) { c -> !c.isDigit() && c != '-' }
                        val text = json.substring(start, end)
                        text.toIntOrNull()?.let { v -> out[id] = v }
                    }
                    idx = json.indexOf(marker, idx + 1)
                }
            }
            logger.info { "skilling: ${out.size} locs carry the cache's level param (23)" }
            out
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < length) {
            if (predicate(this[i])) return i
            i++
        }
        return length
    }

    // ================================================================
    // POPULATION QUERIES, for checks and for the install log
    // ================================================================

    /**
     * Every loc id whose merged action list contains [action].
     *
     * Delegates to [com.opennxt.model.world.LocChanges.locsDeclaring] rather
     * than issuing a second copy of the same SQL, so there is exactly one
     * statement in the tree that answers "which locs declare X".
     */
    fun locsDeclaring(action: String): List<Int> =
        com.opennxt.model.world.LocChanges.locsDeclaring(action)
}
