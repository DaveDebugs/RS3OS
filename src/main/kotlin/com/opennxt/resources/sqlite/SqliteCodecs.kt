package com.opennxt.resources.sqlite

import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.FilesystemResourceCodec
import com.opennxt.resources.sqlite.RsDatabase.intOrNull
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap
import java.sql.ResultSet

/**
 * Definition codecs backed by `data/rs3.sqlite` instead of the cache.
 *
 * The `Filesystem` parameter is part of the interface and is deliberately unused
 * here - see RsDatabase for why. Everything is read-only; `store` throws rather
 * than silently doing nothing, because a caller that thinks it saved a
 * definition and did not is worse than one that gets an error.
 */

private fun ResultSet.str(column: String): String? = getString(column)

private fun ResultSet.bool(column: String): Boolean {
    val v = getInt(column)
    return !wasNull() && v != 0
}

/** actions_0..4 / widget_actions_0..4 as a 5-slot array, nulls preserved. */
private fun ResultSet.actions(prefix: String, count: Int = 5): Array<String?> =
    Array(count) { i ->
        try {
            getString("${prefix}_$i")
        } catch (e: Exception) {
            null // the column only exists if the field cleared builddb's coverage threshold
        }
    }

/**
 * `"Open"` -> `Open`. Attr values are JSON strings; escapes are handled anyway.
 */
private fun unquoteAttr(raw: String): String {
    if (raw.length < 2 || raw[0] != '"' || raw[raw.length - 1] != '"') return raw
    val body = raw.substring(1, raw.length - 1)
    if ('\\' !in body) return body
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            i++
            when (val e = body[i]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'u' -> {
                    sb.append(body.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(e)
            }
        } else sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * Fills the null slots of [base] from [extra]. A COLUMN VALUE ALWAYS WINS; attr fills nulls only.
 *
 * There is never a conflict to resolve, and that is measured rather than assumed: across all eight
 * action indices that have a column, `<table>_attr` carries zero rows for that same field. The
 * precedence rule is therefore documentation of an invariant, not arbitration of a clash.
 */
private fun mergeActions(base: Array<String?>, extra: List<Pair<Int, String>>): Array<String?> {
    if (extra.isEmpty()) return base
    val out = base.copyOf()
    for ((slot, value) in extra) if (slot in out.indices && out[slot] == null) out[slot] = value
    return out
}

abstract class SqliteResourceCodec<T : Any>(
    private val table: String,
    private val columns: String
) : FilesystemResourceCodec<T> {

    protected abstract fun map(rs: ResultSet): T

    /**
     * The same lookup as [load], for callers with no Filesystem to hand. The
     * Filesystem argument was never read (see the file comment), and model code
     * that only wants a definition should not have to open a cache to get one.
     */
    open fun load(id: Int): T? =
        RsDatabase.queryOne("SELECT $columns FROM \"$table\" WHERE id = ?", id) { map(it) }

    /** [list] without a Filesystem. */
    open fun listAll(): Map<Int, T> {
        val out = Int2ObjectAVLTreeMap<T>()
        RsDatabase.queryAll("SELECT $columns FROM \"$table\"") { rs -> rs.getInt("id") to map(rs) }
            .forEach { (id, def) -> out[id] = def }
        return out
    }

    override fun load(fs: Filesystem, id: Int): T? = load(id)

    override fun list(fs: Filesystem): Map<Int, T> = listAll()

    override fun getMaxId(fs: Filesystem): Int = RsDatabase.maxId(table)

    override fun store(fs: Filesystem, id: Int, data: T) {
        throw UnsupportedOperationException(
            "$table is read-only; regenerate rs3.sqlite with buildall.sh instead of writing to it"
        )
    }
}

object SqliteItemCodec : SqliteResourceCodec<ItemDefinition>(
    "items",
    "id, name, members, tradeable, stackable_1, equipSlotId, equipId, buy_limit, category, dummyItem, " +
        "widget_actions_0, widget_actions_1, widget_actions_2, widget_actions_4"
) {
    override fun map(rs: ResultSet) = ItemDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        members = rs.bool("members"),
        tradeable = rs.bool("tradeable"),
        // The config field is `stackable_1`: present means stackable, absent means not.
        stackable = rs.bool("stackable_1"),
        equipSlotId = rs.intOrNull("equipSlotId"),
        equipId = rs.intOrNull("equipId"),
        buyLimit = rs.intOrNull("buy_limit"),
        category = rs.intOrNull("category"),
        dummyItem = rs.intOrNull("dummyItem"),
        inventoryActions = rs.actions("widget_actions")
    )

    /**
     * THE FIFTH BACKPACK MENU ROW, WHICH THIS CODEC COULD NOT SEE UNTIL.
     */
    private const val ATTR_WHERE = "FROM items_attr WHERE field LIKE 'widget_actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    /**
     * `widget_actions_3` -> 3, or -1 for anything that is not a plain action slot.
     *
     * The LIKE above also matches `widget_actions_cursor_2` and `widget_actions_cursor_3` (566 rows
     * between them). Those are CURSOR ids, not option names, and they land on -1 here and are
     * filtered out - which is why the filter is a positive test on the slot number rather than a
     * negative test on the field name.
     */
    private fun slotOf(field: String): Int =
        field.removePrefix("widget_actions_").toIntOrNull() ?: -1

    override fun load(id: Int): ItemDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def
        else def.copy(inventoryActions = mergeActions(def.inventoryActions, extra))
    }

    override fun listAll(): Map<Int, ItemDefinition> {
        val base = super.listAll()
        // One sweep of the attr rows rather than a query per item, the same bulk path the loc and
        // npc codecs take - and the one any full-corpus scan actually goes through.
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(1) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = Int2ObjectAVLTreeMap<ItemDefinition>()
        for ((id, def) in base) {
            val rows = extra[id]
            out[id] = if (rows == null) def
            else def.copy(inventoryActions = mergeActions(def.inventoryActions, rows))
        }
        return out
    }

    /** How many items gained a menu row from the attr sweep. Observable so a check can pin it. */
    fun attrActionRowCount(): Int =
        RsDatabase.queryAll(ATTR_ALL) { slotOf(it.getString("field")) }.count { it >= 0 }
}

object SqliteNpcCodec : SqliteResourceCodec<NpcDefinition>(
    "npcs",
    "id, name, boundSize, combat, movementType, animation_group, drawMapDot, actions_0, actions_1, actions_2"
) {
    override fun map(rs: ResultSet) = NpcDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        size = rs.intOrNull("boundSize") ?: 1,
        combatLevel = rs.intOrNull("combat"),
        movementType = rs.intOrNull("movementType"),
        animationGroup = rs.intOrNull("animation_group"),
        drawMapDot = rs.bool("drawMapDot"),
        actions = rs.actions("actions")
    )

    /**
     * Only `actions_0..2` are columns on `npcs`. `actions_3` (997 rows) and
     * `actions_4` (424 rows) live in `npcs_attr` as `(id, 'actions_N', json)`
     * rows, exactly the situation [SqliteLocCodec] documents for locs, with the
     * same consequence: a dispatcher validating against the column view alone
     * rejects definition-declared options. Measured on `data/rs3.sqlite`, that
     * hid 210 of the 756 npc shop-opening options from the registry. So the
     * attr rows are merged in here, both per-id and in the bulk sweep, under
     * the loc codec's rule: a column value always wins; attr fills nulls.
     *
     * What is deliberately NOT merged: the `members_actions_*` rows (154 across
     * slots 0..4). [NpcDefinition.actions] is the plain 5-slot actions array
     * and has no members-actions storage; folding a members-only option into
     * the same slots would erase the members flag and misdeclare the option as
     * free-to-play. Measured at Shops.install, that remainder is exactly 7 of
     * the 756 shop opener (id, option) pairs, and those stay reachable only
     * through ShopData's wider window (Shops.openForNpc's members seam).
     */
    private const val ATTR_WHERE = "FROM npcs_attr WHERE field LIKE 'actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    /**
     * `actions_3` -> 3, or -1 for a field name that is not a plain action slot.
     * `members_actions_*` never reaches here (the SQL LIKE is anchored at
     * 'actions'), and would map to -1 if it did.
     */
    private fun slotOf(field: String): Int =
        field.removePrefix("actions_").toIntOrNull() ?: -1

    override fun load(id: Int): NpcDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def else def.copy(actions = mergeActions(def.actions, extra))
    }

    override fun listAll(): Map<Int, NpcDefinition> {
        val base = super.listAll()
        // One sweep of the attr rows rather than 32687 per-id queries - the
        // same bulk path SqliteLocCodec takes, and the one SqliteDefinitions
        // (and therefore Shops.install) actually goes through.
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(2) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = LinkedHashMap<Int, NpcDefinition>(base.size)
        base.forEach { (id, def) ->
            val e = extra[id]
            out[id] = if (e == null) def else def.copy(actions = mergeActions(def.actions, e))
        }
        return out
    }
}

object SqliteLocCodec : SqliteResourceCodec<LocDefinition>(
    "locs",
    "id, name, width, length, blocks_movement, walkable, allows_lineofsight, animation, is_members, actions_0"
) {
    override fun map(rs: ResultSet) = LocDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        width = rs.intOrNull("width") ?: 1,
        length = rs.intOrNull("length") ?: 1,
        blocksMovement = rs.bool("blocks_movement"),
        walkable = rs.bool("walkable"),
        allowsLineOfSight = rs.bool("allows_lineofsight"),
        animation = rs.intOrNull("animation"),
        isMembers = rs.bool("is_members"),
        actions = rs.actions("actions")
    )

    /**
     * Only `actions_0` is a column on `locs`. Slots 1..4 cleared builddb's
     * coverage threshold on too few records to earn one, so all 11985 of them
     * live in `locs_attr` as `(id, 'actions_N', json)` rows and the base class's
     * SELECT cannot see them.
     *
     * That matters beyond completeness. 17 loc ids declare "Open" only in a slot
     * above 0, and of the 687 that declare "Close", 176 are in a slot above 0.
     */
    private const val ATTR_WHERE = "FROM locs_attr WHERE field LIKE 'actions_%'"
    private const val ATTR_ONE = "SELECT field, value $ATTR_WHERE AND id = ?"
    private const val ATTR_ALL = "SELECT id, field, value $ATTR_WHERE"

    /** `actions_3` -> 3, or -1 for a field name that is not an action slot. */
    private fun slotOf(field: String): Int =
        field.removePrefix("actions_").toIntOrNull() ?: -1

    override fun load(id: Int): LocDefinition? {
        val def = super.load(id) ?: return null
        val extra = RsDatabase.queryAll(ATTR_ONE, id) {
            slotOf(it.getString("field")) to unquoteAttr(it.getString("value"))
        }.filter { it.first >= 0 }
        return if (extra.isEmpty()) def else def.copy(actions = mergeActions(def.actions, extra))
    }

    override fun listAll(): Map<Int, LocDefinition> {
        val base = super.listAll()
        // One sweep of the 11985 attr rows rather than 137079 per-id queries.
        val extra = HashMap<Int, MutableList<Pair<Int, String>>>()
        RsDatabase.queryAll(ATTR_ALL) {
            Triple(it.getInt("id"), slotOf(it.getString("field")), unquoteAttr(it.getString("value")))
        }.forEach { (id, slot, value) ->
            if (slot >= 0) extra.getOrPut(id) { ArrayList(2) }.add(slot to value)
        }
        if (extra.isEmpty()) return base
        val out = LinkedHashMap<Int, LocDefinition>(base.size)
        base.forEach { (id, def) ->
            val e = extra[id]
            out[id] = if (e == null) def else def.copy(actions = mergeActions(def.actions, e))
        }
        return out
    }
}

object SqliteVarBitCodec : SqliteResourceCodec<VarBitDefinition>(
    "varbits",
    "id, varid, bit_start, bit_end"
) {
    override fun map(rs: ResultSet) = VarBitDefinition(
        id = rs.getInt("id"),
        varId = rs.getInt("varid"),
        bitStart = rs.getInt("bit_start"),
        bitEnd = rs.getInt("bit_end")
    )
}

// Named separately because loadByGameId needs the same column list as the base
// class's own query, and duplicating it is how the two drift apart.
private const val SEQUENCE_COLUMNS =
    "id, game_id, skeletal_animation, unknown_02, unknown_05, left_hand_item, right_hand_item, " +
        "unknown_09, unknown_0A, unknown_0B, unknown_0F, unknown_12, unknown_18"

object SqliteSequenceCodec : SqliteResourceCodec<SequenceDefinition>("sequences", SEQUENCE_COLUMNS) {
    override fun map(rs: ResultSet) = SequenceDefinition(
        id = rs.getInt("id"),
        gameId = rs.getInt("game_id"),
        skeletalAnimation = rs.intOrNull("skeletal_animation"),
        unknown_02 = rs.intOrNull("unknown_02"),
        unknown_05 = rs.intOrNull("unknown_05"),
        leftHandItem = rs.intOrNull("left_hand_item"),
        rightHandItem = rs.intOrNull("right_hand_item"),
        unknown_09 = rs.intOrNull("unknown_09"),
        unknown_0A = rs.intOrNull("unknown_0A"),
        unknown_0B = rs.intOrNull("unknown_0B"),
        unknown_0F = rs.intOrNull("unknown_0F"),
        unknown_12 = rs.intOrNull("unknown_12"),
        unknown_18 = rs.intOrNull("unknown_18")
    )

    /**
     * Lookup by `game_id`. On the database as it stands this is a synonym for
     * [load], and it is kept anyway - see WHY IT SURVIVES below.
     */
    fun loadByGameId(gameId: Int): SequenceDefinition? =
        RsDatabase.queryOne("SELECT $SEQUENCE_COLUMNS FROM \"sequences\" WHERE game_id = ?", gameId) { map(it) }
}

object SqliteSpotAnimCodec : SqliteResourceCodec<SpotAnimDefinition>(
    "spotanims",
    "id, ambient, contrast, model, sequence, unk0a, unk2e"
) {
    override fun map(rs: ResultSet) = SpotAnimDefinition(
        id = rs.getInt("id"),
        ambient = rs.intOrNull("ambient"),
        contrast = rs.intOrNull("contrast"),
        model = rs.getInt("model"),
        sequence = rs.intOrNull("sequence"),
        unk0a = rs.intOrNull("unk0a"),
        unk2e = rs.intOrNull("unk2e")
    )
}

object SqliteAnimGroupCodec : SqliteResourceCodec<AnimGroupDefinition>(
    "animgroups",
    "id, run, turnonspot1, turnonspot2, unknown_32, unknown_33, walk_back, walk_left, walk_right"
) {
    override fun map(rs: ResultSet) = AnimGroupDefinition(
        id = rs.getInt("id"),
        run = rs.intOrNull("run"),
        turnonspot1 = rs.intOrNull("turnonspot1"),
        turnonspot2 = rs.intOrNull("turnonspot2"),
        unknown_32 = rs.intOrNull("unknown_32"),
        unknown_33 = rs.intOrNull("unknown_33"),
        walkBack = rs.intOrNull("walk_back"),
        walkLeft = rs.intOrNull("walk_left"),
        walkRight = rs.intOrNull("walk_right")
    )
}

/**
 * What each [QuestDefinition] field is called in `quests`, and where the value
 * ultimately comes from. The first candidate that the table actually has wins;
 * a field with no candidate present is selected as `NULL AS <alias>` so [map]
 * keeps reading one stable set of names.
 */
private val QUEST_FIELDS = listOf(
    "id" to listOf("id"),
    "name" to listOf("name"),
    "members" to listOf("members"),
    "quest_difficulty" to listOf("quest_difficulty", "difficulty"),
    "quest_points" to listOf("quest_points", "points"),
    "quest_item_sprite" to listOf("quest_item_sprite", "item_sprite"),
    "quest_list_name" to listOf("quest_list_name", "list_name")
)

private fun questSelect(present: Set<String>): String =
    QUEST_FIELDS.joinToString(", ") { (alias, candidates) ->
        val found = candidates.firstOrNull { it in present }
        if (found == null) "NULL AS \"$alias\"" else "\"$found\" AS \"$alias\""
    }

object SqliteQuestCodec : SqliteResourceCodec<QuestDefinition>(
    "quests",
    questSelect(RsDatabase.columnsOf("quests"))
) {
    /**
     * The [QuestDefinition] fields this database has no column for, by their
     * canonical alias. Empty is the healthy state. Anything in here reads back
     * null for every quest, and a caller that cares must say so out loud rather
     * than treat null as data.
     */
    val unsourcedFields: List<String> by lazy {
        val present = RsDatabase.columnsOf("quests")
        QUEST_FIELDS.filter { (_, candidates) -> candidates.none { it in present } }.map { it.first }
    }

    override fun map(rs: ResultSet) = QuestDefinition(
        id = rs.getInt("id"),
        name = rs.str("name"),
        members = rs.bool("members"),
        questDifficulty = rs.intOrNull("quest_difficulty"),
        questItemSprite = rs.intOrNull("quest_item_sprite"),
        // Declared NUMERIC, holds text - see QuestDefinition.
        questListName = rs.str("quest_list_name"),
        questPoints = rs.intOrNull("quest_points")
    )
}
