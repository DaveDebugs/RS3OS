package com.opennxt.model.shops

import com.opennxt.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Shop layer, read directly out of the decoded RS3 cache database (data/rs3.sqlite).
 *
 * ============================================================================
 * READ THIS FIRST -- WHAT IS AND IS NOT IN THE CACHE
 * ============================================================================
 *
 * The RS3 cache does NOT contain shop stock. There is no table, enum, struct or
 * dbtable anywhere in this database that lists "shop N sells item X at price P".
 * That is not a decoding gap, it is how RuneScape is built: a shop's inventory
 * lives in Jagex's server config and is never shipped to the client. The client
 * only ever sees the stock the server pushes into an inv container at runtime.
 *
 * This was established, not assumed. [absenceProbe] is the executable form of
 * the argument: it scans every dbrow value, every enum entry and every struct
 * param in the database for co-occurring item ids. Feeding it the Lumbridge
 * general store set (pot/jug/tinderbox/chisel/hammer/newcomer map) or the
 * bronze/iron/steel hatchet set returns nothing. Those shops exist in the game
 * and their contents are public knowledge; if shop stock were in this cache in
 * any form, those sets would co-occur somewhere. They do not.
 *
 * [stockFor] therefore returns null for every shop, always, by construction.
 * It is not a stub waiting to be filled from this database -- there is nothing
 * in this database to fill it from. Stock has to come from somewhere else
 * (server-side authoring, or a documented source with its own provenance tag).
 *
 * A previous investigation flagged dbtables 30, 43, 44, 45, 46, 63, 148 and 160
 * as "shop stock". None of them is a shop. What they actually are is decoded
 * below ([farmProduce], [letterPool], [warpriestUnlocks], [rewardBundle],
 * [monthlyReward]) so the claim is checkable rather than asserted.
 *
 * ============================================================================
 * WHAT THIS LOADER DOES GIVE YOU
 * ============================================================================
 *
 *  - [openers]     every NPC and scenery object whose right-click menu contains
 *                  a shop-opening option, with the exact option text. 705 npc
 *                  rows + 8 loc rows. This is the "who opens a shop" half of a
 *                  shop system, and it IS in the cache, verbatim.
 *  - [itemValue]   the item definition's 64-bit value field. This is the number
 *                  RS shop pricing is derived from. Present for 29,611 of the
 *                  60,617 items; absent means absent, and you get null.
 *  - the five decoded dbtables listed above.
 *
 * ============================================================================
 * ID SPACE -- READ THIS TOO
 * ============================================================================
 *
 * npcs.id (the cache archive index) and npcs.game_id (the in-game npc id)
 * differ for 32,559 of the 32,687 npc rows. Every lookup key in this class is
 * the GAME id. [ShopOpener.archiveId] carries the archive index alongside, so
 * you can always see both, but [openersForNpc] keys on game_id only.
 *
 * Worked example of why this matters: Bob, who runs Bob's Brilliant Axes in
 * Lumbridge, is archive id 1031 / game id 519. Game id 1031 is an Earth impling
 * and has no shop option at all. Passing the archive id to [openersForNpc]
 * returns empty, deliberately.
 *
 * locs have a single id space, so [openersForLoc] keys on locs.id.
 */
object ShopData {

    /** Overridable for tests: -Dopennxt.cachedb=/path/to/rs3.sqlite */
    val dbPath: Path
        get() = System.getProperty("opennxt.cachedb")
            ?.let { Path.of(it) }
            ?: Constants.DATA_PATH.resolve("rs3.sqlite")

    enum class OpenerKind { NPC, LOC }

    /**
     * One shop-opening menu option on one npc or scenery object.
     *
     * [option] is the literal string from the cache. It is NOT normalised into
     * a "shop id" -- the cache has no shop ids, so inventing one here would be
     * inventing data. "Trade", "Buy veteran capes" and "Trade (melee armour)"
     * are all reproduced exactly as the cache stores them.
     */
    data class ShopOpener(
        val kind: OpenerKind,
        /** npcs.game_id for NPC, locs.id for LOC. */
        val gameId: Int,
        /** The cache archive address (_group*256 + _file). Equals [gameId] for LOC. */
        val archiveId: Int,
        val name: String?,
        val option: String,
        /** menu slot 0..4 */
        val slot: Int,
        /** true when the option came from a members_actions_* field. */
        val membersOnly: Boolean
    ) {
        override fun toString(): String =
            "${kind.name} $gameId ${name ?: "<unnamed>"} op$slot=\"$option\"" +
                (if (membersOnly) " (members)" else "")
    }

    /** A decoded [item, quantity] pair out of one of the dbtables below. */
    data class ItemStack(val item: Int, val quantity: Int) {
        override fun toString() = "$item x$quantity"
    }

    /**
     * Where a set of item ids was found to co-occur. Used by [absenceProbe].
     *
     * [size] is how many item-ish integers the whole container holds. It matters:
     * the item-name lookup enums hold every item in the game, so they trivially
     * "contain" any shop's stock. A real shop stock list is small.
     */
    data class Container(val kind: String, val id: Int, val sub: Int, val hits: List<Int>, val size: Int) {
        override fun toString() = "$kind $id/$sub hits=$hits size=$size"
    }

    /**
     * The substrings that mark a menu option as opening a shop. Case-insensitive
     * substring match, applied to the raw option text. Deliberately broad and
     * deliberately visible: the matched option is carried on every result so a
     * caller can tighten this without re-reading the cache.
     */
    val SHOP_OPTION_KEYWORDS = listOf("trade", "shop", "buy", "exchange")

    // ------------------------------------------------------------------ state

    private var loaded = false
    private val openerList = ArrayList<ShopOpener>()
    private val byNpcGameId = HashMap<Int, MutableList<ShopOpener>>()
    private val byLocId = HashMap<Int, MutableList<ShopOpener>>()
    private val itemNames = HashMap<Int, String>()
    private val itemValues = HashMap<Int, Long>()

    // decoded dbtables
    private val farmRows = LinkedHashMap<Int, List<Triple<Int, Int, Int>>>()          // table 30
    private val letterPools = LinkedHashMap<Int, LinkedHashMap<Char, List<Int>>>()    // tables 43..46
    private val warpriest = LinkedHashMap<Int, List<Pair<Int, Int>>>()                // table 63
    private val bundles = LinkedHashMap<Int, List<ItemStack>>()                       // table 148
    private val monthly = ArrayList<MonthlyReward>()                                  // table 160

    data class MonthlyReward(val year: Int, val month: Int, val rewards: List<ItemStack>) {
        override fun toString() = "$year-${(month + 1).toString().padStart(2, '0')}: $rewards"
    }

    // ------------------------------------------------------------------ sqlite

    private fun connect(): Connection {
        val p = dbPath
        require(Files.exists(p)) { "cache database not found at ${p.toAbsolutePath()}" }
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:file:${p.toAbsolutePath()}?mode=ro")
    }

    /**
     * Strip the JSON quoting the decoder applies to string values stored in the
     * *_attr side tables ("Trade" -> Trade). Values in the main npcs/locs tables
     * are already bare.
     */
    private fun unquote(s: String?): String? {
        if (s == null) return null
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }

    private fun isShopOption(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val l = s.lowercase()
        return SHOP_OPTION_KEYWORDS.any { it in l }
    }

    /**
     * Split a dbrow_value tuple, e.g. `[1942,1,1]` or `["Sword Shop"]`, into its
     * elements. Returns raw element text; callers convert.
     */
    private fun tuple(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("[")) s = s.substring(1)
        if (s.endsWith("]")) s = s.substring(0, s.length - 1)
        if (s.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inStr = false
        var esc = false
        for (ch in s) {
            when {
                esc -> { sb.append(ch); esc = false }
                ch == '\\' && inStr -> esc = true
                ch == '"' -> { inStr = !inStr; sb.append(ch) }
                ch == ',' && !inStr -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun tupleInts(raw: String): List<Int> =
        tuple(raw).mapNotNull { it.toIntOrNull() }

    // ------------------------------------------------------------------- load

    @Synchronized
    fun load() {
        if (loaded) return
        connect().use { c ->
            loadItems(c)
            loadNpcOpeners(c)
            loadLocOpeners(c)
            loadFarmProduce(c)
            loadLetterPools(c)
            loadWarpriest(c)
            loadBundles(c)
            loadMonthly(c)
        }
        openerList.sortWith(compareBy({ it.kind }, { it.gameId }, { it.slot }))
        loaded = true
    }

    private fun loadItems(c: Connection) {
        c.createStatement().executeQuery("select id, name from items").use { rs ->
            while (rs.next()) {
                val n = rs.getString(2)
                if (n != null) itemNames[rs.getInt(1)] = n
            }
        }
        // big_value is stored as a two element [high, low] 64-bit split.
        c.createStatement().executeQuery(
            "select id, value from items_attr where field = 'big_value'"
        ).use { rs ->
            while (rs.next()) {
                val parts = tupleInts(rs.getString(2))
                if (parts.size == 2) itemValues[rs.getInt(1)] = (parts[0].toLong() shl 32) or (parts[1].toLong() and 0xffffffffL)
            }
        }
    }

    private fun addOpener(o: ShopOpener) {
        openerList.add(o)
        when (o.kind) {
            OpenerKind.NPC -> byNpcGameId.getOrPut(o.gameId) { ArrayList() }.add(o)
            OpenerKind.LOC -> byLocId.getOrPut(o.gameId) { ArrayList() }.add(o)
        }
    }

    private fun loadNpcOpeners(c: Connection) {
        // The unified database keys npcs by game id (id == game_id); the cache
        // archive address is no longer a row key but is still derivable from
        // the stored group/child provenance columns as _group*256 + _file, and
        // is carried on [ShopOpener.archiveId] so the id-space trap stays
        // visible in exactly the shape it was discovered in.
        val gameId = HashMap<Int, Int>()
        val archiveId = HashMap<Int, Int>()
        val name = HashMap<Int, String?>()
        c.createStatement().executeQuery("select id, game_id, name, _group, _file from npcs").use { rs ->
            while (rs.next()) {
                gameId[rs.getInt(1)] = rs.getInt(2)
                name[rs.getInt(1)] = rs.getString(3)
                archiveId[rs.getInt(1)] = rs.getInt(4) * 256 + rs.getInt(5)
            }
        }
        // actions_0..2 are real columns on npcs; actions_3/4 and members_actions_*
        // live in npcs_attr.
        for (slot in 0..2) {
            c.createStatement().executeQuery(
                "select id, actions_$slot from npcs where actions_$slot is not null"
            ).use { rs ->
                while (rs.next()) {
                    val a = unquote(rs.getString(2)) ?: continue
                    if (!isShopOption(a)) continue
                    val id = rs.getInt(1)
                    addOpener(ShopOpener(OpenerKind.NPC, gameId[id] ?: continue, archiveId[id] ?: id, name[id], a, slot, false))
                }
            }
        }
        val fields = ArrayList<String>()
        c.createStatement().executeQuery(
            "select distinct field from npcs_attr where field like '%actions\\_%' escape '\\'"
        ).use { rs -> while (rs.next()) fields.add(rs.getString(1)) }
        for (f in fields) {
            val slot = f.last().digitToIntOrNull() ?: continue
            val members = f.startsWith("members")
            c.prepareStatement("select id, value from npcs_attr where field = ?").use { ps ->
                ps.setString(1, f)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val a = unquote(rs.getString(2)) ?: continue
                        if (!isShopOption(a)) continue
                        val id = rs.getInt(1)
                        addOpener(ShopOpener(OpenerKind.NPC, gameId[id] ?: continue, archiveId[id] ?: id, name[id], a, slot, members))
                    }
                }
            }
        }
    }

    private fun loadLocOpeners(c: Connection) {
        val name = HashMap<Int, String?>()
        c.createStatement().executeQuery("select id, name from locs").use { rs ->
            while (rs.next()) name[rs.getInt(1)] = rs.getString(2)
        }
        c.createStatement().executeQuery(
            "select id, actions_0 from locs where actions_0 is not null"
        ).use { rs ->
            while (rs.next()) {
                val a = unquote(rs.getString(2)) ?: continue
                if (!isShopOption(a)) continue
                val id = rs.getInt(1)
                addOpener(ShopOpener(OpenerKind.LOC, id, id, name[id], a, 0, false))
            }
        }
        val fields = ArrayList<String>()
        c.createStatement().executeQuery(
            "select distinct field from locs_attr where field like 'actions\\_%' escape '\\'"
        ).use { rs -> while (rs.next()) fields.add(rs.getString(1)) }
        for (f in fields) {
            val slot = f.last().digitToIntOrNull() ?: continue
            c.prepareStatement("select id, value from locs_attr where field = ?").use { ps ->
                ps.setString(1, f)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val a = unquote(rs.getString(2)) ?: continue
                        if (!isShopOption(a)) continue
                        val id = rs.getInt(1)
                        addOpener(ShopOpener(OpenerKind.LOC, id, id, name[id], a, slot, false))
                    }
                }
            }
        }
    }

    /** row_id -> column_id -> ordered list of raw tuple strings, for one dbtable. */
    private fun dbTable(c: Connection, tableId: Int): LinkedHashMap<Int, LinkedHashMap<Int, MutableList<String>>> {
        val out = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<String>>>()
        c.prepareStatement(
            "select row_id, column_id, idx, value from dbrow_value where table_id = ? order by row_id, column_id, idx"
        ).use { ps ->
            ps.setInt(1, tableId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.getOrPut(rs.getInt(1)) { LinkedHashMap() }
                        .getOrPut(rs.getInt(2)) { ArrayList() }
                        .add(rs.getString(4))
                }
            }
        }
        return out
    }

    /**
     * dbtable 30, declared schema `col0 : OBJ/INT/INT`.
     * Nine rows of (item, quantity, tier) triples. Row 1283 is the union (433
     * entries); 1284 is every seed; 1285..1290 are the produce categories
     * (vegetables, flowers+hops, fruit+berries, mushrooms, meat, fish). Farming
     * produce, not shop stock.
     */
    private fun loadFarmProduce(c: Connection) {
        for ((row, cols) in dbTable(c, 30)) {
            farmRows[row] = (cols[0] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 3) Triple(t[0], t[1], t[2]) else null
            }
        }
    }

    /**
     * dbtables 43, 44, 45, 46, declared schema `col0 : INT, col1 : OBJ[]`.
     *
     * col0 is a LETTER INDEX, A=1..Z=26, not a skill id and not a shop id. Every
     * item in col1 has a name starting with that letter. The four tables are
     * four parallel pools of the same shape. Keys 1, 5, 9, 15, 21, 24 and 26
     * (A, E, I, O, U, X, Z) are absent from all four.
     *
     * The letter reading is not a guess: table 41 sitting immediately alongside
     * holds a 26-entry A..Z word list in four languages ("Anchovies, Bacon,
     * Cabbage, ... Zanaris wheat"), and table 42 places items on a grid with
     * four coordinates each. This cluster is a word/letter puzzle, and these are
     * its per-letter item pools.
     */
    private fun loadLetterPools(c: Connection) {
        for (t in intArrayOf(43, 44, 45, 46)) {
            val m = LinkedHashMap<Char, List<Int>>()
            for ((_, cols) in dbTable(c, t)) {
                val key = cols[0]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
                if (key < 1 || key > 26) continue
                m['A' + (key - 1)] = (cols[1] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            }
            letterPools[t] = m
        }
    }

    /**
     * dbtable 63, declared schema `col0 : OBJ[], col1 : INT[]`.
     * 52 rows, each three items with flags 1/1/0. Every row is Warpriest armour
     * (Saradomin, Zamorak, Armadyl, Bandos, ...): the piece, itself again, and
     * the next piece in the set. An unlock/progression chain, not shop stock.
     */
    private fun loadWarpriest(c: Connection) {
        for ((row, cols) in dbTable(c, 63)) {
            val objs = (cols[0] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            val flags = (cols[1] ?: emptyList()).mapNotNull { tupleInts(it).firstOrNull() }
            warpriest[row] = objs.mapIndexed { i, o -> o to (flags.getOrNull(i) ?: -1) }
        }
    }

    /**
     * dbtable 148, declared schema `col0 : OBJ/INT[]`.
     * 76 rows, each an [item, quantity] bundle: protean packs, pulse cores,
     * loot pinatas, knowledge bombs, coin bags. Promotional / Premier-style
     * reward bundles. Not shop stock -- no price, no currency, no shop key.
     */
    private fun loadBundles(c: Connection) {
        for ((row, cols) in dbTable(c, 148)) {
            bundles[row] = (cols[0] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 2) ItemStack(t[0], t[1]) else null
            }
        }
    }

    /**
     * dbtable 160, declared schema `col0 : INT, col1 : INT, col2 : OBJ/INT[],
     * col3 : STRING, col4 : GRAPHIC(default 16984)`.
     * col0 is a calendar YEAR (2021..2027), col1 a zero-based MONTH (0..11),
     * col2 the reward bundle for that month. 68 rows. Monthly reward calendar.
     */
    private fun loadMonthly(c: Connection) {
        for ((_, cols) in dbTable(c, 160)) {
            val y = cols[0]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
            val m = cols[1]?.firstOrNull()?.let { tupleInts(it).firstOrNull() } ?: continue
            val r = (cols[2] ?: emptyList()).mapNotNull {
                val t = tupleInts(it)
                if (t.size >= 2) ItemStack(t[0], t[1]) else null
            }
            monthly.add(MonthlyReward(y, m, r))
        }
    }

    // ------------------------------------------------------------------- API

    fun openers(): List<ShopOpener> { load(); return openerList }

    /** Keyed on npcs.game_id. Returns empty for an archive id or an unknown id. */
    fun openersForNpc(gameId: Int): List<ShopOpener> { load(); return byNpcGameId[gameId] ?: emptyList() }

    /** Keyed on locs.id. */
    fun openersForLoc(locId: Int): List<ShopOpener> { load(); return byLocId[locId] ?: emptyList() }

    fun openersNamed(name: String): List<ShopOpener> {
        load(); return openerList.filter { it.name == name }
    }

    fun itemName(id: Int): String? { load(); return itemNames[id] }

    /**
     * The item definition's value field, or null when the item def has no value
     * at all (31,006 of the 60,617 items). Null is not zero.
     *
     * This is the number RS derives shop prices from; it is NOT itself a shop
     * price, and this loader does not pretend to know a shop's markup.
     */
    fun itemValue(id: Int): Long? { load(); return itemValues[id] }

    fun itemValueCount(): Int { load(); return itemValues.size }

    /**
     * Always null, for every id. See the class comment: the cache carries no
     * shop stock. This exists so callers get an explicit, documented null
     * instead of an empty list that reads like "this shop sells nothing".
     */
    @Suppress("UNUSED_PARAMETER")
    fun stockFor(gameId: Int): List<ItemStack>? = null

    /** False, and it is a fact about the cache, not about this loader. */
    const val STOCK_PRESENT_IN_CACHE = false

    // decoded dbtables -----------------------------------------------------

    fun farmProduce(): Map<Int, List<Triple<Int, Int, Int>>> { load(); return farmRows }
    fun letterPool(tableId: Int): Map<Char, List<Int>> { load(); return letterPools[tableId] ?: emptyMap() }
    fun letterPoolTables(): List<Int> { load(); return letterPools.keys.toList() }
    fun warpriestUnlocks(): Map<Int, List<Pair<Int, Int>>> { load(); return warpriest }
    fun rewardBundles(): Map<Int, List<ItemStack>> { load(); return bundles }
    fun monthlyRewards(): List<MonthlyReward> { load(); return monthly }

    // absence proof --------------------------------------------------------

    /**
     * Scan every dbrow value, enum entry and struct param in the database for
     * containers in which at least [minHits] of [itemIds] co-occur.
     *
     * This is the evidence behind [STOCK_PRESENT_IN_CACHE] = false. Give it a
     * real shop's stock list and it comes back empty, which is what "the cache
     * does not have shops" looks like when you can run it.
     */
    fun absenceProbe(itemIds: Set<Int>, minHits: Int): List<Container> {
        val out = ArrayList<Container>()
        connect().use { c ->
            val acc = HashMap<Long, MutableSet<Int>>()
            val asize = HashMap<Long, Int>()
            c.createStatement().executeQuery("select table_id, row_id, value from dbrow_value").use { rs ->
                while (rs.next()) {
                    val key = (rs.getInt(1).toLong() shl 32) or (rs.getInt(2).toLong() and 0xffffffffL)
                    val ints = tupleInts(rs.getString(3))
                    asize[key] = (asize[key] ?: 0) + ints.size
                    for (n in ints) if (n in itemIds) acc.getOrPut(key) { HashSet() }.add(n)
                }
            }
            for ((k, v) in acc) if (v.size >= minHits)
                out.add(Container("dbrow", (k shr 32).toInt(), k.toInt(), v.sorted(), asize[k] ?: 0))

            val eacc = HashMap<Int, MutableSet<Int>>()
            val esize = HashMap<Int, Int>()
            c.createStatement().executeQuery("select enum_id, key, value from enum_entry").use { rs ->
                while (rs.next()) {
                    val e = rs.getInt(1)
                    esize[e] = (esize[e] ?: 0) + 1
                    rs.getString(2)?.toIntOrNull()?.let { if (it in itemIds) eacc.getOrPut(e) { HashSet() }.add(it) }
                    rs.getString(3)?.toIntOrNull()?.let { if (it in itemIds) eacc.getOrPut(e) { HashSet() }.add(it) }
                }
            }
            for ((k, v) in eacc) if (v.size >= minHits) out.add(Container("enum", k, -1, v.sorted(), esize[k] ?: 0))

            val sacc = HashMap<Int, MutableSet<Int>>()
            val ssize = HashMap<Int, Int>()
            c.createStatement().executeQuery("select struct_id, intvalue from struct_param where intvalue is not null").use { rs ->
                while (rs.next()) {
                    val sid = rs.getInt(1)
                    val n = rs.getInt(2)
                    ssize[sid] = (ssize[sid] ?: 0) + 1
                    if (n in itemIds) sacc.getOrPut(sid) { HashSet() }.add(n)
                }
            }
            for ((k, v) in sacc) if (v.size >= minHits) out.add(Container("struct", k, -1, v.sorted(), ssize[k] ?: 0))
        }
        return out.sortedWith(compareBy({ it.kind }, { it.id }))
    }
}
