package com.opennxt.tools.impl

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.opennxt.Constants
import com.opennxt.resources.config.enums.EnumFilesystemCodec
import com.opennxt.resources.config.params.ParamFilesystemCodec
import com.opennxt.resources.config.items.ItemFilesystemCodec
import com.opennxt.resources.config.locs.LocFilesystemCodec
import com.opennxt.resources.config.npcs.NpcFilesystemCodec
import com.opennxt.resources.config.structs.StructFilesystemCodec
import com.opennxt.tools.Tool
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Builds data/rs3.sqlite from a game cache.
 *
 * run-tool db-builder
 * run-tool db-builder --output data/rs3.sqlite
 *
 * The server reads its definitions out of this database rather than straight from the cache, so
 * without it nothing resolves by name and the ribbon windows have nothing to open. Nobody can ship
 * the file, so everyone builds their own.
 *
 * Filled: enums (index 17), params (11), structs (26), items (19), npcs (18) and locs (16), along
 * with the attribute tables that hang off the last three. Every one of those is checked field for
 * field against a known-good database by ItemDecodeVerify, NpcDecodeVerify and LocDecodeVerify.
 *
 * Empty: varbits, quests, sequences, spotanims, animgroups and achievements. These are ordinary
 * opcode records and would decode the same way as the ones above; nothing here reads them yet. The
 * tables are still created, empty, because the readers run raw SQL and a missing table throws where
 * an empty one returns nothing.
 *
 * The map tables - terrain, collision, object placements and npc spawns - are filled separately by
 * [MapBuilder]. Run `run-tool map-builder` after this tool; without it the world has no collision
 * and nothing standing in it.
 */
class DbBuilder : Tool("db-builder", "Builds data/rs3.sqlite from the game cache") {

    private val output by option(help = "Where to write the database (default: data/rs3.built.sqlite - NOT the live data/rs3.sqlite)")
        .default(Constants.DATA_PATH.resolve("rs3.built.sqlite").toString())

    private val force by option(help = "Overwrite an existing, non-empty target database").flag(default = false)

    /** Tables this tool actually fills; the row-count sanity check below runs over exactly these. */
    private val FILLED = listOf("enums", "params", "structs", "items", "npcs", "locs")

    override fun runTool() {
        val path = java.nio.file.Paths.get(output)
        Files.createDirectories(path.toAbsolutePath().parent)

        // Guard 1: never silently overwrite. A 1.4 GB database that took weeks of migration work is
 // not something a default should be able to empty.
        if (Files.exists(path) && Files.size(path) > 0 && !force) {
            throw IllegalStateException(
                "$path exists (${Files.size(path)} bytes). This tool REPLACES the tables it fills and\n" +
                    "  writes only the columns it decodes. Build to a new file (the default), or pass --force\n" +
                    "  if you mean to overwrite this one."
            )
        }

        // Guard 2: an absent cache directory is created empty by SqliteFilesystem rather than failing.
        // Zero indices means zero decoded rows - refuse before a single DELETE runs.
        val indices = filesystem.numIndices()
        if (indices == 0) {
            throw IllegalStateException(
                "the cache at ${Constants.CACHE_PATH} holds no indices - nothing would be decoded and every\n" +
                    "  table this tool fills would be emptied. Pass -Dopennxt.cache=<the js5 cache directory>."
            )
        }
        logger.info { "Building $path from the cache at ${Constants.CACHE_PATH} ($indices indices)" }

        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db ->
            db.autoCommit = false
            createSchema(db)

            val enums = fillEnums(db)
            val params = fillParams(db)
            val structs = fillStructs(db)
            val items = fillItems(db)
            val npcs = fillNpcs(db)
            val locs = fillLocs(db)

            // Guard 3: a fill that decoded nothing is a broken decoder or the wrong cache, not an
            // empty game. Roll back rather than commit a database that is emptier than it was.
            val empty = FILLED.filter { t ->
                db.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM \"$t\"").use { rs -> rs.next(); rs.getLong(1) == 0L } }
            }
            if (empty.isNotEmpty()) {
                db.rollback()
                throw IllegalStateException("decoded zero rows for ${empty.joinToString(", ")}; rolled back, nothing written")
            }

            db.commit()

            logger.info { "" }
            logger.info { "Done. $path" }
            logger.info { "  enums     $enums" }
            logger.info { "  params    $params" }
            logger.info { "  structs   $structs" }
            logger.info { "  items     $items" }
            logger.info { "  npcs      $npcs" }
            logger.info { "  locs      $locs" }
            logger.info { "" }
            logger.info { "  varbits, quests, sequences, spotanims and animgroups are still empty -" }
            logger.info { "  nothing reads them yet." }
            logger.info { "" }
            logger.info { "  NEXT: run-tool map-builder, which fills the world itself - terrain," }
            logger.info { "  collision, object placements and npc spawns. Without it the world is empty." }
        }
    }

    /**
     * Every table the SQLite readers touch, whether or not this tool can fill it.
     *
     * Column names match what the readers select, which is why several of them are odd (`stackable_1`,
     * `widget_actions_0`): they follow the decoded cache's own column names so a question about where a
     * value comes from is one grep away. Types are loose on purpose - SQLite does not enforce them and
     * a stricter schema here would only argue with whatever fills these later.
     */
    private fun createSchema(db: Connection) {
        db.createStatement().use { st ->
            for (sql in SCHEMA) st.executeUpdate(sql)
            for (sql in INDEXES) st.executeUpdate(sql)
        }
    }

    private fun wipe(db: Connection, vararg tables: String) {
        db.createStatement().use { st -> tables.forEach { st.executeUpdate("DELETE FROM \"$it\"") } }
    }

    private fun fillItems(db: Connection): String {
        wipe(db, "items", "items_attr")
        val ins = db.prepareStatement(
            "INSERT INTO items (id, game_id, name, members, tradeable, untradeable, stackable_1, " +
                "neverStackable, equipSlotId, equipId, buy_limit, category, dummyItem, " +
                "widget_actions_0, widget_actions_1, widget_actions_2, widget_actions_4, " +
                "ground_actions_cursor_2, widget_actions_cursor_0, widget_actions_cursor_1) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        val insAttr = db.prepareStatement("INSERT INTO items_attr (id, field, value) VALUES (?, ?, ?)")
        var count = 0
        var params = 0
        for (id in 0..ItemFilesystemCodec.getMaxId(filesystem)) {
            val r = ItemFilesystemCodec.load(filesystem, id) ?: continue
            count++
            ins.setInt(1, id); ins.setInt(2, id)
            ins.setString(3, r.name)
            setFlag(ins, 4, r.members); setFlag(ins, 5, r.tradeable); setFlag(ins, 6, r.untradeable)
            setFlag(ins, 7, r.stackable); setFlag(ins, 8, r.neverStackable)
            setInt(ins, 9, r.equipSlotId); setInt(ins, 10, r.equipId)
            setInt(ins, 11, r.buyLimit)
            if (r.category == null) ins.setNull(12, java.sql.Types.VARCHAR) else ins.setString(12, r.category.toString())
            setInt(ins, 13, r.dummyItem)
            // Slot 3 lives in the attr table, because the column list follows the decoded cache's.
            ins.setString(14, r.widgetActions[0])
            ins.setString(15, r.widgetActions[1])
            ins.setString(16, r.widgetActions[2])
            ins.setString(17, r.widgetActions[4])
            setInt(ins, 18, r.groundActionCursors[2])
            setInt(ins, 19, r.widgetActionCursors[0])
            setInt(ins, 20, r.widgetActionCursors[1])
            ins.addBatch()
            r.widgetActions[3]?.let {
                insAttr.setInt(1, id); insAttr.setString(2, "widget_actions_3")
                insAttr.setString(3, quote(it)); insAttr.addBatch()
            }
            for (slot in 0..4) {
                val a = r.groundActions[slot] ?: continue
                insAttr.setInt(1, id); insAttr.setString(2, "ground_actions_$slot")
                insAttr.setString(3, quote(a)); insAttr.addBatch()
            }
            fun attr(field: String, value: String) {
                insAttr.setInt(1, id); insAttr.setString(2, field)
                insAttr.setString(3, value); insAttr.addBatch()
            }
            r.baseModels?.let { if (it.isNotEmpty()) attr("baseModelList", intList(it)) }
            r.bigValue?.let { attr("big_value", "[${it.first},${it.second}]") }
            if (r.colourReplacements.isNotEmpty()) attr("color_replacements", pairList(r.colourReplacements))
            if (r.materialReplacements.isNotEmpty()) attr("material_replacements", pairList(r.materialReplacements))
            // Slot 0 carries a type alongside the id; slot 1 is a bare model id.
            r.maleModels[0]?.let { attr("maleModels_0", "{\"id\":$it,\"type\":0}") }
            r.femaleModels[0]?.let { attr("femaleModels_0", "{\"id\":$it,\"type\":0}") }
            r.maleModels[1]?.let { attr("maleModels_1", it.toString()) }
            r.femaleModels[1]?.let { attr("femaleModels_1", it.toString()) }
            for (i in 0..1) {
                r.maleHeads[i]?.let { attr("maleHeads_$i", it.toString()) }
                r.femaleHeads[i]?.let { attr("femaleHeads_$i", it.toString()) }
            }
            for (i in r.stackInfo.indices) {
                val si = r.stackInfo[i] ?: continue
                attr("stack_info_$i", "{\"icon\":${si.first},\"count\":${si.second}}")
            }
            // Slots that the reference keeps as real columns are written above, not here.
            for (i in 0..4) if (i != 2) r.groundActionCursors[i]?.let { attr("ground_actions_cursor_$i", it.toString()) }
            for (i in 2..4) r.widgetActionCursors[i]?.let { attr("widget_actions_cursor_$i", it.toString()) }
            if (r.params.isNotEmpty()) {
                attr("extra", paramsJson(r.params))
                params += r.params.size
            }
        }
        ins.executeBatch(); insAttr.executeBatch()
        ins.close(); insAttr.close()
        return "$count items, $params params"
    }

    private fun fillNpcs(db: Connection): String {
        wipe(db, "npcs", "npcs_attr")
        val ins = db.prepareStatement(
            "INSERT INTO npcs (id, game_id, name, boundSize, combat, movementType, movementCapabilities, " +
                "animation_group, attackCursor, drawMapDot, actions_0, actions_1, actions_2, " +
                "action_cursors_0) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        val insAttr = db.prepareStatement("INSERT INTO npcs_attr (id, field, value) VALUES (?, ?, ?)")
        var count = 0
        var params = 0
        for (id in 0..NpcFilesystemCodec.getMaxId(filesystem)) {
            val r = NpcFilesystemCodec.load(filesystem, id) ?: continue
            count++
            ins.setInt(1, id); ins.setInt(2, id)
            ins.setString(3, r.name)
            setInt(ins, 4, r.boundSize); setInt(ins, 5, r.combat)
            setInt(ins, 6, r.movementType); setInt(ins, 7, r.movementCapabilities)
            setInt(ins, 8, r.animationGroup); setInt(ins, 9, r.attackCursor)
            if (r.drawMapDot == false) ins.setInt(10, 0) else ins.setNull(10, java.sql.Types.INTEGER)
            for (slot in 0..2) ins.setString(11 + slot, r.actions[slot])
            setInt(ins, 14, r.actionCursors[0])
            ins.addBatch()
            for (slot in 3..4) {
                val a = r.actions[slot] ?: continue
                insAttr.setInt(1, id); insAttr.setString(2, "actions_$slot")
                insAttr.setString(3, quote(a)); insAttr.addBatch()
            }
            for (slot in 0..4) {
                val a = r.membersActions[slot] ?: continue
                insAttr.setInt(1, id); insAttr.setString(2, "members_actions_$slot")
                insAttr.setString(3, quote(a)); insAttr.addBatch()
            }
            r.models?.let {
                insAttr.setInt(1, id); insAttr.setString(2, "models")
                insAttr.setString(3, it.joinToString(",", "[", "]")); insAttr.addBatch()
            }
            r.headModels?.let {
                insAttr.setInt(1, id); insAttr.setString(2, "headModels")
                insAttr.setString(3, it.joinToString(",", "[", "]")); insAttr.addBatch()
            }
            fun attr(field: String, value: String) {
                insAttr.setInt(1, id); insAttr.setString(2, field)
                insAttr.setString(3, value); insAttr.addBatch()
            }
            if (r.colourReplacements.isNotEmpty()) attr("color_replacements", pairList(r.colourReplacements))
            if (r.materialReplacements.isNotEmpty()) attr("material_replacements", pairList(r.materialReplacements))
            r.headIconData?.let { attr("head_icon_data", it.toString()) }
            r.ambientSound?.let {
                attr("ambient_sound", "{\"unk1\":${it[0]},\"unk2\":${it[1]},\"unk3\":${it[2]}," +
                    "\"unk4\":${it[3]},\"unk45\":${it[4]}}")
            }
            r.morph1?.let { attr("morphs_1", morphJson(it.varbit, it.varp, null, it.options, it.default, it.trailing)) }
            r.morph2?.let { attr("morphs_2", morphJson(it.varbit, it.varp, it.extra, it.options, it.default, it.trailing)) }
            for (i in 1..4) r.actionCursors[i]?.let { attr("action_cursors_$i", it.toString()) }
            if (r.params.isNotEmpty()) {
                attr("extra", paramsJson(r.params))
                params += r.params.size
            }
        }
        ins.executeBatch(); insAttr.executeBatch()
        ins.close(); insAttr.close()
        return "$count npcs, $params params"
    }

    private fun fillLocs(db: Connection): String {
        wipe(db, "locs", "locs_attr")
        val ins = db.prepareStatement(
            "INSERT INTO locs (id, game_id, name, width, length, blocks_movement, walkable, " +
                "allows_lineofsight, obstructs_ground, is_members, animation, actions_0, " +
                "action_cursors_0) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        val insAttr = db.prepareStatement("INSERT INTO locs_attr (id, field, value) VALUES (?, ?, ?)")
        var count = 0
        var params = 0
        for (id in 0..LocFilesystemCodec.getMaxId(filesystem)) {
            val r = LocFilesystemCodec.load(filesystem, id) ?: continue
            count++
            ins.setInt(1, id); ins.setInt(2, id)
            ins.setString(3, r.name)
            setInt(ins, 4, r.width); setInt(ins, 5, r.length)
            setFlag(ins, 6, r.blocksMovement); setFlag(ins, 7, r.walkable)
            setFlag(ins, 8, r.allowsLineOfSight); setFlag(ins, 9, r.obstructsGround)
            setFlag(ins, 10, r.isMembers)
            setInt(ins, 11, r.animation)
            ins.setString(12, r.actions[0])
            setInt(ins, 13, r.actionCursors[0])
            ins.addBatch()
            for (slot in 1..4) {
                val a = r.actions[slot] ?: continue
                insAttr.setInt(1, id); insAttr.setString(2, "actions_$slot")
                insAttr.setString(3, quote(a)); insAttr.addBatch()
            }
            fun attr(field: String, value: String) {
                insAttr.setInt(1, id); insAttr.setString(2, field)
                insAttr.setString(3, value); insAttr.addBatch()
            }
            if (r.models.isNotEmpty()) {
                attr("models", r.models.joinToString(",", "[", "]") { (type, values) ->
                    "{\"type\":$type,\"values\":${intList(values)}}"
                })
            }
            if (r.colourReplacements.isNotEmpty()) attr("color_replacements", pairList(r.colourReplacements))
            if (r.materialReplacements.isNotEmpty()) attr("material_replacements", pairList(r.materialReplacements))
            r.sound?.let { attr("sound", "{\"sound\":${it.first},\"unk\":${it.second}}") }
            r.quests?.let { if (it.isNotEmpty()) attr("quests", intList(it)) }
            r.morph1?.let { attr("morphs_1", morphJson(it.varbit, it.varp, null, it.options, it.default, null)) }
            r.morph2?.let { attr("morphs_2", morphJson(it.varbit, it.varp, it.extra, it.options, it.default, null)) }
            for (i in 1..4) r.actionCursors[i]?.let { attr("action_cursors_$i", it.toString()) }
            if (r.params.isNotEmpty()) {
                attr("extra", paramsJson(r.params))
                params += r.params.size
            }
        }
        ins.executeBatch(); insAttr.executeBatch()
        ins.close(); insAttr.close()
        return "$count locs, $params params"
    }

    private fun intList(values: IntArray): String = values.joinToString(",", "[", "]")

    private fun pairList(pairs: List<Pair<Int, Int>>): String =
        pairs.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }

    private fun morphJson(
        varbit: Int, varp: Int, extra: Int?, options: IntArray, default: Int, trailing: Int?
    ): String = buildString {
        append("{\"varbit\":").append(varbit).append(",\"varp\":").append(varp)
        if (extra != null) append(",\"unk2\":").append(extra)
        append(",\"options\":").append(intList(options))
        append(",\"default\":").append(default)
        if (trailing != null) append(",\"unk5\":").append(trailing)
        append("}")
    }

    private fun setInt(ps: java.sql.PreparedStatement, index: Int, value: Int?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setInt(index, value)
    }

    private fun setFlag(ps: java.sql.PreparedStatement, index: Int, value: Boolean?) {
        if (value == true) ps.setInt(index, 1) else ps.setNull(index, java.sql.Types.INTEGER)
    }

    /**
     * The readers expect action strings in the attr tables to be quoted, the way the columns are not.
     * Anything outside printable ASCII goes out as a \uXXXX escape, which is what the rest of the
     * decoded data does - a raw n-tilde in a JSON blob reads back fine but does not compare equal.
     */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c.code in 0x20..0x7e -> append(c)
            else -> append("\\u%04x".format(c.code))
        }
        append('"')
    }

    private fun paramsJson(params: List<Pair<Int, Any>>): String =
        params.joinToString(",", "[", "]") { (prop, value) ->
            if (value is String) "{\"prop\":$prop,\"intvalue\":null,\"stringvalue\":${quote(value)}}"
            else "{\"prop\":$prop,\"intvalue\":$value,\"stringvalue\":null}"
        }

    private fun fillEnums(db: Connection): String {
        wipe(db, "enums", "enum_entry")   // not enums_attr: nothing here refills it
        val all = EnumFilesystemCodec.list(filesystem)
        var entries = 0
        val ins = db.prepareStatement(
            "INSERT INTO enums (id, intValue, stringValue, key_type2, value_type2) VALUES (?, ?, ?, ?, ?)"
        )
        val insEntry = db.prepareStatement("INSERT INTO enum_entry (enum_id, key, value) VALUES (?, ?, ?)")
        for ((id, def) in all) {
            ins.setInt(1, id)
            ins.setInt(2, def.defaultInt)
            ins.setString(3, def.defaultString)
            ins.setInt(4, def.keyType.ordinal)
            ins.setInt(5, def.valueType.ordinal)
            ins.addBatch()
            for ((key, value) in def.values) {
                insEntry.setInt(1, id)
                insEntry.setString(2, key.toString())
                insEntry.setString(3, value.toString())
                insEntry.addBatch()
                entries++
            }
        }
        ins.executeBatch(); insEntry.executeBatch()
        ins.close(); insEntry.close()
        return "${all.size} enums, $entries entries"
    }

    private fun fillParams(db: Connection): String {
        wipe(db, "params", "params_attr")
        val all = ParamFilesystemCodec.list(filesystem)
        val ins = db.prepareStatement("INSERT INTO params (id) VALUES (?)")
        val insAttr = db.prepareStatement("INSERT INTO params_attr (id, field, value) VALUES (?, ?, ?)")
        for ((id, def) in all) {
            ins.setInt(1, id); ins.addBatch()
            // CacheVarTypes reads this row and parses it as an object, pulling "vartype" out of it,
            // so it has to be the type's id - not its ordinal - inside JSON, not a bare number.
            val vartype = def.unknownTypeId ?: def.type.id
            val stringParam = def.type.type == com.opennxt.resources.config.vars.BaseVarType.STRING
            val defaultInt = if (stringParam) "null" else def.defaultInt.toString()
            val defaultString = if (stringParam) quote(def.defaultString) else "null"
            insAttr.setInt(1, id); insAttr.setString(2, "type")
            insAttr.setString(3,
                "{\"vartype\":$vartype,\"defaultint\":$defaultInt,\"defaultstring\":$defaultString}")
            insAttr.addBatch()
        }
        ins.executeBatch(); insAttr.executeBatch()
        ins.close(); insAttr.close()
        return "${all.size} params"
    }

    private fun fillStructs(db: Connection): String {
        wipe(db, "structs", "struct_param")
        val all = StructFilesystemCodec.list(filesystem)
        var props = 0
        val ins = db.prepareStatement("INSERT INTO structs (id) VALUES (?)")
        val insParam = db.prepareStatement(
            "INSERT INTO struct_param (struct_id, prop, intvalue, stringvalue) VALUES (?, ?, ?, ?)"
        )
        for ((id, def) in all) {
            ins.setInt(1, id); ins.addBatch()
            for ((prop, value) in def.values) {
                insParam.setInt(1, id)
                insParam.setInt(2, prop)
                if (value is Int) {
                    insParam.setInt(3, value); insParam.setNull(4, java.sql.Types.VARCHAR)
                } else {
                    insParam.setNull(3, java.sql.Types.INTEGER); insParam.setString(4, value.toString())
                }
                insParam.addBatch()
                props++
            }
        }
        ins.executeBatch(); insParam.executeBatch()
        ins.close(); insParam.close()
        return "${all.size} structs, $props params"
    }

    companion object {
        /**
         * Every table the server's SQLite readers touch, whether or not this tool can fill it.
         *
         * Taken verbatim from a complete database rather than retyped, so the column names match what the
         * readers actually select - several of them are odd (`stackable_1`, `widget_actions_0`) because they
         * follow the decoded cache's own naming. The tables this tool cannot fill are still created: the
         * readers run raw SQL, and a missing table throws where an empty one just returns nothing.
         */
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS achievement_sub ( achievement_id INTEGER, sub_id INTEGER, PRIMARY KEY (achievement_id, sub_id))",
            "CREATE TABLE IF NOT EXISTS achievement_varbit ( achievement_id INTEGER, varbit_id INTEGER, PRIMARY KEY (achievement_id, varbit_id))",
            "CREATE TABLE IF NOT EXISTS achievements ( id INTEGER PRIMARY KEY, name TEXT, desc TEXT, reward TEXT, cat INTEGER, subcat INTEGER, sprite INTEGER, points INTEGER, hidden INTEGER, members INTEGER)",
            "CREATE TABLE IF NOT EXISTS animgroups (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"run\" NUMERIC, \"turnonspot1\" NUMERIC, \"turnonspot2\" NUMERIC, \"unknown_32\" NUMERIC, \"unknown_33\" NUMERIC, \"walk_back\" NUMERIC, \"walk_left\" NUMERIC, \"walk_right\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS animgroups_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS dbrow_value (row_id INTEGER, table_id INTEGER, column_id INTEGER, idx INTEGER, value TEXT)",
            "CREATE TABLE IF NOT EXISTS dbrows (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"table\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS dbrows_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS dbtables (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS dbtables_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS enum_entry (enum_id INTEGER, key TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS enums (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"intValue\" NUMERIC, \"key_type2\" NUMERIC, \"stringValue\" NUMERIC, \"value_type2\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS enums_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS identitykit (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"bodypart\" NUMERIC, \"headmodel\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS identitykit_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS interfaces (id INTEGER PRIMARY KEY, \"aspectheighttype\" NUMERIC, \"aspectwidthtype\" NUMERIC, \"aspectxtype\" NUMERIC, \"aspectytype\" NUMERIC, \"baseheight\" NUMERIC, \"baseposx\" NUMERIC, \"baseposy\" NUMERIC, \"basewidth\" NUMERIC, \"component\" NUMERIC, \"contenttype\" NUMERIC, \"cursor\" NUMERIC, \"flags\" NUMERIC, \"iface\" NUMERIC, \"optmask\" NUMERIC, \"parentid\" NUMERIC, \"str3\" NUMERIC, \"themeid\" NUMERIC, \"type\" NUMERIC, \"u1\" NUMERIC, \"u2\" NUMERIC, \"u3\" NUMERIC, \"v9byte\" NUMERIC, \"version\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS interfaces_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"ambiance\" NUMERIC, \"baseModel\" NUMERIC, \"bindLink_old\" NUMERIC, \"buy_limit\" NUMERIC, \"category\" TEXT, \"dummyItem\" NUMERIC, \"equipId\" NUMERIC, \"equipSlotId\" NUMERIC, \"ground_actions_cursor_2\" NUMERIC, \"members\" NUMERIC, \"modelTranslate_0\" NUMERIC, \"modelTranslate_1\" NUMERIC, \"model_zoom\" NUMERIC, \"name\" TEXT, \"neverStackable\" NUMERIC, \"noteData_old\" NUMERIC, \"noteTemplate_old\" NUMERIC, \"rotation_0\" NUMERIC, \"rotation_1\" NUMERIC, \"rotation_2\" NUMERIC, \"stack_mode_default\" NUMERIC, \"stackable_1\" NUMERIC, \"tradeable\" NUMERIC, \"untradeable\" NUMERIC, \"widget_actions_0\" NUMERIC, \"widget_actions_1\" NUMERIC, \"widget_actions_2\" NUMERIC, \"widget_actions_4\" NUMERIC, \"widget_actions_cursor_0\" NUMERIC, \"widget_actions_cursor_1\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS items_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS locs (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"action_cursors_0\" NUMERIC, \"actions_0\" NUMERIC, \"allows_lineofsight\" NUMERIC, \"ambient\" NUMERIC, \"animation\" NUMERIC, \"blocks_movement\" NUMERIC, \"contrast\" NUMERIC, \"deletable\" NUMERIC, \"is_members\" NUMERIC, \"length\" NUMERIC, \"mirror\" NUMERIC, \"morphFloor\" NUMERIC, \"name\" TEXT, \"obstructs_ground\" NUMERIC, \"occludes_2\" NUMERIC, \"scaleX\" NUMERIC, \"scaleY\" NUMERIC, \"scaleZ\" NUMERIC, \"tileplacement_related_c4\" NUMERIC, \"translateY\" NUMERIC, \"unknown_16\" NUMERIC, \"unknown_40\" NUMERIC, \"unknown_BA\" NUMERIC, \"walkable\" NUMERIC, \"width\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS locs_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS map_blocked ( square_id INTEGER, plane INTEGER, bitmap BLOB, PRIMARY KEY (square_id, plane))",
            "CREATE TABLE IF NOT EXISTS map_env ( square_id INTEGER PRIMARY KEY, sun_colour TEXT, sun_angles TEXT, intensity TEXT, fog_colour TEXT, fog_density INTEGER, fog_on INTEGER, record TEXT)",
            "CREATE TABLE IF NOT EXISTS map_keyed (square_id INTEGER, plane INTEGER, tile_x INTEGER, tile_z INTEGER, local_x INTEGER, local_y INTEGER, value INTEGER, key INTEGER)",
            "CREATE TABLE IF NOT EXISTS map_loc ( square_id INTEGER, plane INTEGER, x INTEGER, y INTEGER, loc_id INTEGER, type INTEGER, rot INTEGER, xform TEXT)",
            "CREATE TABLE IF NOT EXISTS map_square ( square_id INTEGER PRIMARY KEY, i INTEGER, j INTEGER, planes INTEGER, heights BLOB, flags BLOB, underlay BLOB, overlay BLOB, shape BLOB)",
            "CREATE TABLE IF NOT EXISTS npcs (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"aByte3076_set_0\" NUMERIC, \"action_cursors_0\" NUMERIC, \"actions_0\" NUMERIC, \"actions_1\" NUMERIC, \"actions_2\" NUMERIC, \"ambience\" NUMERIC, \"animation_group\" NUMERIC, \"attackCursor\" NUMERIC, \"boundSize\" NUMERIC, \"combat\" NUMERIC, \"drawMapDot\" NUMERIC, \"modelContract\" NUMERIC, \"movementCapabilities\" NUMERIC, \"movementType\" NUMERIC, \"name\" TEXT, \"respawnDirection\" NUMERIC, \"scaleXZ\" NUMERIC, \"scaleY\" NUMERIC, \"unknown_63\" NUMERIC, \"unknown_67\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS npcs_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS overlays (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"bleedToUnderlay\" NUMERIC, \"bleedpriority\" NUMERIC, \"material\" NUMERIC, \"material_tiling\" NUMERIC, \"unknown_0x05\" NUMERIC, \"unknown_0x08\" NUMERIC, \"unknown_0x0A\" NUMERIC, \"unknown_0x0E\" NUMERIC, \"unknown_0x10\" NUMERIC)",
            "CREATE TABLE IF NOT EXISTS overlays_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS params (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"unk04\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS params_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS quest_req ( quest_id INTEGER, requires_quest_id INTEGER, PRIMARY KEY (quest_id, requires_quest_id))",
            "CREATE TABLE IF NOT EXISTS quest_statreq ( quest_id INTEGER, skill_id INTEGER, level INTEGER, PRIMARY KEY (quest_id, skill_id))",
            "CREATE TABLE IF NOT EXISTS quests ( id INTEGER PRIMARY KEY, name TEXT, members INTEGER, difficulty INTEGER, points INTEGER, points_req INTEGER, parent INTEGER, varp INTEGER, varp_start INTEGER, varp_end INTEGER, varbit INTEGER, varbit_start INTEGER, varbit_end INTEGER, journal INTEGER, year INTEGER, length INTEGER, age INTEGER, area INTEGER, desc TEXT, start TEXT, items TEXT, combat TEXT, rewards TEXT)",
            "CREATE TABLE IF NOT EXISTS sequences (id INTEGER PRIMARY KEY, \"_group\" NUMERIC, \"_file\" NUMERIC, \"game_id\" NUMERIC, \"skeletal_animation\" NUMERIC, \"unknown_02\" NUMERIC, \"unknown_05\" NUMERIC, \"left_hand_item\" NUMERIC, \"right_hand_item\" NUMERIC, \"unknown_08\" NUMERIC, \"unknown_09\" NUMERIC, \"unknown_0A\" NUMERIC, \"unknown_0B\" NUMERIC, \"unknown_0E\" NUMERIC, \"unknown_0F\" NUMERIC, \"unknown_10\" NUMERIC, \"unknown_12\" NUMERIC, \"unknown_16\" NUMERIC, \"unknown_18\" NUMERIC, \"unknown_1B\" NUMERIC)",
            "CREATE TABLE IF NOT EXISTS sequences_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS spotanims (id INTEGER PRIMARY KEY, \"ambient\" NUMERIC, \"contrast\" NUMERIC, \"model\" NUMERIC, \"rotation\" NUMERIC, \"scaleX\" NUMERIC, \"scaleYorZ\" NUMERIC, \"sequence\" NUMERIC, \"unk0a\" NUMERIC, \"unk2c\" NUMERIC, \"unk2e\" NUMERIC)",
            "CREATE TABLE IF NOT EXISTS spotanims_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS struct_param (struct_id INTEGER, prop INTEGER, intvalue INTEGER, stringvalue TEXT)",
            "CREATE TABLE IF NOT EXISTS structs (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS structs_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS underlays (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"material\" NUMERIC, \"material_tiling\" NUMERIC, \"unknown_0x04\" NUMERIC, \"unknown_0x05\" NUMERIC)",
            "CREATE TABLE IF NOT EXISTS underlays_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS varbits (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"varid\" NUMERIC, bit_start INTEGER, bit_end INTEGER, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS varbits_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_campaign (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_campaign_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_clan (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"lifetime\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_clan_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_clansetting (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"lifetime\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_clansetting_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_client (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"domaindefault\" NUMERIC, \"lifetime\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_client_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_grp80 (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_grp80_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_npc (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_npc_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_object (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"lifetime\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_object_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_player (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"clientcode\" NUMERIC, \"lifetime\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_player_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_region (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_region_attr (id INTEGER, field TEXT, value TEXT)",
            "CREATE TABLE IF NOT EXISTS vars_world (id INTEGER PRIMARY KEY, \"_file\" NUMERIC, \"_group\" NUMERIC, \"type\" NUMERIC, game_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS vars_world_attr (id INTEGER, field TEXT, value TEXT)",
        )

        /** The lookups the server does per click, and the ones this tool's own output is queried by. */
        private val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS ix_enum_entry ON enum_entry(enum_id)",
            "CREATE INDEX IF NOT EXISTS ix_enums_attr ON enums_attr(id, field)",
            "CREATE INDEX IF NOT EXISTS ix_struct_param ON struct_param(struct_id)",
            "CREATE INDEX IF NOT EXISTS ix_struct_prop ON struct_param(prop, intvalue)",
            "CREATE INDEX IF NOT EXISTS ix_params_attr ON params_attr(id, field)",
            "CREATE INDEX IF NOT EXISTS ix_items_name ON items(name)",
            "CREATE INDEX IF NOT EXISTS ix_npcs_name ON npcs(name)",
            "CREATE INDEX IF NOT EXISTS ix_locs_name ON locs(name)",
            "CREATE INDEX IF NOT EXISTS ix_interfaces ON interfaces(iface, component)",
            "CREATE INDEX IF NOT EXISTS ix_interfaces_attr ON interfaces_attr(id, field)",
            "CREATE INDEX IF NOT EXISTS ix_map_loc ON map_loc(loc_id)"
        )
    }
}
