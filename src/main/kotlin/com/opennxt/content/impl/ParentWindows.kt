package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * THE RIBBON'S CATEGORY WINDOWS - Hero, Powers, Adventures, Customisations, Settings, and the rest.
 */
object ParentWindows {
    private val logger = KotlinLogging.logger { }

    /** toplevel_v2_parent - the frame every category window is drawn inside. */
    const val PARENT_IFACE = 1448

    /** 1448:1 - the "Loading..." overlay (its own children are the dark panel and the text). */
    const val LOADING_LAYER = 1

    /** The five panel layers, children of 1448:0, in x order. Each is followed by its holder (4/6/8/10/12). */
    val TAB_LAYERS = intArrayOf(3, 5, 7, 9, 11)

    /** Every child of 1448:0 that a category open addresses - the layers and their holders. */
    private val ALL_LAYER_COMPONENTS = (3..12).toList()

    /** toplevel_v2, and the component 1448 is mounted on in the reference login (see the decoded transcript). */
    const val TOPLEVEL = 1477
    const val PARENT_MOUNT = 715

    /** The window chrome the reference client opens beside the parent frame; 1893 goes here. */
    const val CHROME_MOUNT = 711
    const val CHROME_IFACE = 1893

    /** skillguide (1218) and the loader it hosts at :0 - the guide refreshes by re-opening 1217. */
    const val SKILLGUIDE_IFACE = 1218
    const val SKILLGUIDE_LOADER = 1217

    /**
     * varc 2911 - the parent (ribbon category) the client is showing. The reference client sets it to the category's
     * index on every open and to -1 when the window closes. in the click observation:
     * Hero -> 0, Adventures -> 3, and -1 as the window went away.
     */
    const val VARC_OPEN_PARENT = 2911

    /**
     * varc 1753 - WHICH SKILL the skill guide draws, paired with `RunClientScript(5682, [skill])`.
     * two skill clicks in the same session sent 1753=1 then 1753=16, each followed by
     * 5682 with the same number. This is the selector that could not be found by reading the cache.
     *
     * **THE VALUE IS NOT THE SKILL ID AND IT IS NOT THE CLICK'S SLOT.** See [skillSlots].
     */
    const val VARC_SKILLGUIDE_SKILL = 1753
    const val SCRIPT_SHOW_SKILLGUIDE = 5682

    // ---- struct params, read off the cache (see the class KDoc) ----
    /** A category struct is one whose 3444 is [PARENT_IFACE]. */
    private const val P_PARENT_IFACE = 3444
    /** 3448..3453: the category's tab structs, in ribbon order. */
    private const val P_TAB_FIRST = 3448
    private const val P_TAB_LAST = 3453
    /** The tab the category opens on. */
    private const val P_DEFAULT_TAB = 3454
    /** Per-category varbit holding the last tab the player had open (7700..7706, 10875). */
    private const val P_TAB_VARBIT = 3481
    /** The category's display name. */
    private const val P_NAME = 3493

    /**
     * 6318 - the BANNER interface the reference client mounts at [CHROME_MOUNT] for this category, when it has one.
     */
    private const val P_BANNER_IFACE = 6318

    /** A tab struct's display name. */
    private const val T_NAME = 3455
    /** First panel group; groups are five params wide: interface, x, y, width, height. */
    private const val T_PANEL_FIRST = 3456
    private const val T_PANEL_STRIDE = 5
    private const val T_PANEL_GROUPS = 5

    /**
     * Script 11145 - component geometry. Its argument order is read off the protocol, where
     * the login sends `11145 [1067, 600, 0, 0, 96797466]` and 96797466 is the hash 1477:794:
     * (width, height, x, y, componentHash).
     */
    private const val SCRIPT_SET_COMPONENT_SIZE = 11145

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.parentWindows") != "false"

    data class Panel(val interfaceId: Int, val x: Int, val y: Int, val width: Int, val height: Int)
    data class Tab(val structId: Int, val name: String?, val panels: List<Panel>)
    data class Category(
        val structId: Int,
        val name: String?,
        val tabStructIds: List<Int>,
        val defaultTabStructId: Int?,
        val tabVarbit: Int?,
        /** Struct param [P_BANNER_IFACE]; null when this category mounts nothing at [CHROME_MOUNT]. */
        val bannerIface: Int? = null
    )

    /** Every category in the cache, by struct id, loaded once. */
    val categories: Map<Int, Category> by lazy { loadCategories() }

    /**
     * Ribbon slot -> category struct. A DEFAULT, not a finding - see the class KDoc on why the
     * cache cannot answer this. The order is the reference client's usual left-to-right ribbon.
     */
    val SLOT_TO_PARENT: Map<Int, Int> by lazy {
        val override = System.getProperty("opennxt.experiment.parentWindows.slots")
        if (override != null) {
            override.split(',').mapNotNull {
                val parts = it.split(':')
                val slot = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val parent = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (slot != null && parent != null) slot to parent else null
            }.toMap()
        } else mapOf(
 //:
            // the number in the slot field is the ribbon BUTTON's id, not its position - the same
            // observation carries ids 27, 30 and 31 on a fifteen-icon ribbon - and the reference client answered
            //   id 0 -> 1893@1477:711, 1218@1448:3, 1217@1218:0   Hero (its Skills tab, last used)
            //   id 1 -> 1844@1477:711, 1843@1448:3 + eight 18xx   Customisations
            //   id 3 -> 1284@1448:3                                Adventures (Calendar, its first tab)
 // The defaults said 1 = Powers and 3 = Customisations: both REFUTED by the
            // observation and removed. Powers / Community / Grand Exchange / Settings-by-ribbon /
            // Marketplace / RuneMetrics / Reputation / Leagues have NOT been measured - a click on
            // one of those ids logs the id and opens nothing, which is what the next observation needs.
            0 to 21142, // Hero
            1 to 32482, // Customisations
            3 to 21159  // Adventures
        )
    }

    /**
     * Ribbon id 7 is the LOGOUT button: the client sends
     * WORLDLIST_FETCH and ClientProt 67 with it, and reference answers WORLDLIST_FETCH_REPLY,
     * varc 2911 = -1 and scripts 187 / 8320[1001] - the category window closing. Not a category.
     */
    const val RIBBON_LOGOUT_ID = 7

    /**
     * Ribbon ids that select a SIDE PANEL rather than open a category window:
     * the reference client's whole server-side answer is one VARP_SMALL 9778 with the value below (the client
     * does the panel itself and reports its state back in VARC_TRANSMIT, which we ack). Ids 27, 30
     * and 31 in the same observation drew NO server reply at all and are left unhandled.
     */
    const val VARP_SIDE_PANEL = 9778
    /** The ribbon interface the category buttons live on (mounted at 1477:64 by the login). */
    const val RIBBON_IFACE = 1431
    /** Layers this player has a panel in right now; 0 when no category window is open. */
    fun openPanelCount(player: WorldPlayer): Int = openPanels[player]?.size ?: 0
    val RIBBON_SIDE_PANEL: Map<Int, Int> = mapOf(8 to 1, 9 to 3, 10 to 4, 12 to 6)

    /** The category each player has open, for the tab strip (1477:714) and the close (1477:717). */
    private val currentCategory = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Int>()
    )

    /**
     * The tab strip of the open category window: `IF_BUTTON1 1477:714` with the slot field
     * 3, 7, 11, 15, 19, 23 for tabs 0..5 (: Hero 3 -> Summary [320, 1446],
     * 11 -> Loadout [1474, 1463, 1462], 15 -> Achievements [1850, 1855, 1895]; Adventures 7 ->
     * Quests [1783, 1500], 15 -> Minigames [1344], 19 -> Beasts [753], 23 -> Activity Tracker).
     */
    fun tabIndexOf(slot: Int): Int? = if (slot >= 3 && (slot - 3) % 4 == 0) (slot - 3) / 4 else null

    fun handleTabClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val category = currentCategory[player] ?: run {
            logger.info { "parentWindows: ${player.name} clicked tab strip slot $slot with no category window open - ignored" }
            return false
        }
        val index = tabIndexOf(slot) ?: run {
            logger.info { "parentWindows: ${player.name} clicked 1477:714 slot $slot, which is not a tab (3 + 4n) - ignored" }
            return false
        }
        val tabs = categories[category]?.tabStructIds ?: return false
        val tab = tabs.getOrNull(index) ?: run {
            logger.info { "parentWindows: ${player.name} clicked tab $index of '${categories[category]?.name}', which declares ${tabs.size} tabs - ignored" }
            return false
        }
        return open(player, category, tab)
    }

    const val SCRIPT_PANEL_CLOSED = 8320
    const val PANEL_ID_MANAGEMENT_WINDOWS = 1001

    fun handleCloseClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        if (slot != 1) return false
 // I-39: a close for a window this player does not
        // have open writes nothing - the open tree is the gate, not the click.
        if (!player.interfaces.isOpened(PARENT_IFACE) || (currentCategory[player] == null && openPanels[player].isNullOrEmpty())) {
            logger.info { "parentWindows: ${player.name} sent 1477:717 with no category window open - ignored" }
            return false
        }
        val category = currentCategory.remove(player)
        player.client.write(ClientSetvarcSmall(VARC_OPEN_PARENT, -1))
        for (layer in openPanels[player].orEmpty()) player.interfaces.close(PARENT_IFACE, layer, native949 = true)
        player.interfaces.close(SKILLGUIDE_IFACE, 0, native949 = true)
        openPanels.remove(player)
        player.client.write(RunClientScript(SCRIPT_PANEL_CLOSED, arrayOf(PANEL_ID_MANAGEMENT_WINDOWS)))
        logger.info { "parentWindows: ${player.name} closed the category window" + (category?.let { " ('${categories[it]?.name}')" } ?: "") }
        return true
    }

    private fun params(structId: Int): Map<Int, Pair<Int?, String?>> {
        val out = HashMap<Int, Pair<Int?, String?>>()
        RsDatabase.queryAll(
            "SELECT prop, intvalue, stringvalue FROM struct_param WHERE struct_id = ?", structId
        ) { rs ->
            val prop = rs.getInt("prop")
            val iv = rs.getInt("intvalue").let { if (rs.wasNull()) null else it }
            val sv = rs.getString("stringvalue")
            prop to (iv to sv)
        }.forEach { (prop, v) -> out[prop] = v }
        return out
    }

    private fun loadCategories(): Map<Int, Category> {
        if (!RsDatabase.available) return emptyMap()
        val ids = try {
            RsDatabase.queryAll(
                "SELECT DISTINCT struct_id FROM struct_param WHERE prop = $P_PARENT_IFACE " +
                    "AND intvalue = $PARENT_IFACE"
            ) { it.getInt("struct_id") }
        } catch (e: Exception) {
            logger.warn(e) { "parentWindows: cannot read struct_param - the ribbon categories are unavailable" }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Category>()
        for (id in ids) {
            val p = params(id)
            val tabs = (P_TAB_FIRST..P_TAB_LAST).mapNotNull { p[it]?.first }
            out[id] = Category(
                structId = id,
                name = p[P_NAME]?.second,
                tabStructIds = tabs,
                defaultTabStructId = p[P_DEFAULT_TAB]?.first ?: tabs.firstOrNull(),
                tabVarbit = p[P_TAB_VARBIT]?.first?.takeIf { it > 0 },
                bannerIface = p[P_BANNER_IFACE]?.first?.takeIf { it > 0 }
            )
        }
        logger.info {
            "parentWindows: ${out.size} ribbon categories from the cache - " +
                out.values.joinToString { "${it.structId} '${it.name}' (${it.tabStructIds.size} tabs)" }
        }
        return out
    }

    /** A tab's panels, in the order the cache lists them. */
    fun tab(structId: Int): Tab {
        val p = params(structId)
        val panels = ArrayList<Panel>(T_PANEL_GROUPS)
        for (g in 0 until T_PANEL_GROUPS) {
            val base = T_PANEL_FIRST + g * T_PANEL_STRIDE
            val iface = p[base]?.first ?: continue
            if (iface <= 0) continue
            panels += Panel(
                interfaceId = iface,
                x = p[base + 1]?.first ?: 0,
                y = p[base + 2]?.first ?: 0,
                width = p[base + 3]?.first ?: 0,
                height = p[base + 4]?.first ?: 0
            )
        }
        return Tab(structId, p[T_NAME]?.second, panels)
    }

    /** The Hero category, and its 'Skills' tab whose only panel is the skill guide (1218). */
    private const val CATEGORY_HERO = 21142
    private const val TAB_SKILLS = 21144

    /**
     * The skills panel's button layer. The panel exists under two interface ids - 320 `stats` and
     * 1466 `stats_child` - and in both the six empty layers are built at runtime by clientscript
     * 8488 -> 8489, so a click arrives on the SIXTH of them (320:9, 1466:7) with the slot field.
     * Both are: `IF_BUTTON1 320:9 slot 9` and `IF_BUTTON1 1466:7 slot 0/2/3/17`.
     */
    const val SKILLS_IFACE = 1466
    const val SKILLS_BUTTON_LAYER = 7
    const val SKILLS_IFACE_ALT = 320
    const val SKILLS_BUTTON_LAYER_ALT = 9

    /** enum 7674 - the skills panel's slot order; index -> skill struct. 29 entries, 0..28. */
    private const val ENUM_SKILL_ORDER = 7674
    /** A skill struct's display name. */
    private const val S_SKILL_NAME = 3439
    /** A skill struct's SKILL id - the ordinary 0..28 (Attack 0, Defence 1, Strength 2, ...). */
    private const val S_SKILL_ID = 3440
    /** A skill struct's SKILL GUIDE id - the argument of varc 1753 and script 5682. NOT [S_SKILL_ID]. */
    private const val S_SKILL_GUIDE = 3441

    /** One row of the skills panel: the slot the client sends, and what it means. */
    data class SkillSlot(
        val slot: Int,
        val structId: Int,
        val name: String?,
        val skillId: Int,
        val guideId: Int
    )

    /**
     * THE SLOT THE CLIENT SENDS -> THE SKILL, AND THE SKILL GUIDE PAGE. Derived, never typed.
     */
    val skillSlots: Map<Int, SkillSlot> by lazy { loadSkillSlots() }

    val skillGuideStrip: Map<Int, Int> by lazy { loadSkillGuideStrip() }

    /** The guide page a skills-panel slot selects, or null when the slot is not one of enum 7674's. */
    fun guideIdForSlot(slot: Int): Int? = skillSlots[slot]?.guideId

    /** The guide page a click on 1218's icon strip selects, or null when the component is not one. */
    fun guideIdForStripComponent(component: Int): Int? = skillGuideStrip[component]

    private fun loadSkillSlots(): Map<Int, SkillSlot> {
        if (!RsDatabase.available) return emptyMap()
        val order = com.opennxt.resources.sqlite.CacheEnums.intMap(ENUM_SKILL_ORDER)
        val out = LinkedHashMap<Int, SkillSlot>()
        for ((slot, structId) in order) {
            val p = params(structId)
            val guide = p[S_SKILL_GUIDE]?.first ?: continue
            val skill = p[S_SKILL_ID]?.first ?: continue
            out[slot] = SkillSlot(slot, structId, p[S_SKILL_NAME]?.second, skill, guide)
        }
        logger.info {
            "parentWindows: ${out.size} skills-panel slots from enum $ENUM_SKILL_ORDER - " +
                out.values.joinToString { "${it.slot}='${it.name}'(skill ${it.skillId}, guide ${it.guideId})" }
        }
        return out
    }

    private fun loadSkillGuideStrip(): Map<Int, Int> {
        if (!RsDatabase.available) return emptyMap()
        val rows = try {
            RsDatabase.queryAll(
                "SELECT i.component AS c, a.value AS v FROM interfaces i JOIN interfaces_attr a " +
                    "ON a.id = i.id WHERE i.iface = $SKILLGUIDE_IFACE AND a.field = 'scripts' " +
                    "ORDER BY i.component"
            ) { it.getInt("c") to it.getString("v") }
        } catch (e: Exception) {
            logger.warn(e) { "parentWindows: cannot read interface $SKILLGUIDE_IFACE's scripts" }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Int>()
        for ((component, json) in rows) {
            if (json == null) continue
            val obj = try {
                com.google.gson.JsonParser().parse(json).asJsonObject
            } catch (e: Exception) {
                continue
            }
            for ((_, value) in obj.entrySet()) {
                val trigger = value as? com.google.gson.JsonObject ?: continue
                val script = trigger.get("script")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                if (script != SCRIPT_SHOW_SKILLGUIDE) continue
                val args = trigger.getAsJsonArray("args") ?: continue
                if (args.size() != 1 || !args.get(0).isJsonPrimitive) continue
                out[component] = args.get(0).asInt
            }
        }
        logger.info {
            "parentWindows: ${out.size} skill-guide strip buttons on $SKILLGUIDE_IFACE " +
                "(components ${out.keys.joinToString("/")})"
        }
        return out
    }

    /**
     * The skill guide page each player last selected, so a tab or ribbon re-open restores it the way
     * the reference client does. `IF_BUTTON1 1477:714 slot 7` (the Skills tab) at 414.35s of
     * answered `varc 1753 = 3` + `5682 [3]` - 3 being the page the
     * player had picked 39 s earlier - with no click on a skill in between.
     */
    private val currentSkillGuide = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Int>()
    )

    /**
     * A click on a skill in the Skills panel: open the Hero category's Skills tab on that skill's
     * guide page.
     *
     * The slot is translated through [skillSlots]; a slot enum 7674 does not declare sends NOTHING,
     * because a guide id this server invented would draw some other skill's page - which is exactly
     * the defect this replaced.
     */
    fun handleSkillClick(player: WorldPlayer, skillSlot: Int): Boolean {
        if (!enabled) return false
        val entry = skillSlots[skillSlot] ?: run {
            logger.info {
                "parentWindows: ${player.name} clicked skills-panel slot $skillSlot, which is not one " +
                    "of the ${skillSlots.size} slots enum $ENUM_SKILL_ORDER declares - ignored"
            }
            return false
        }
        logger.info {
            "parentWindows: ${player.name} clicked skill slot $skillSlot ('${entry.name}', skill " +
                "${entry.skillId}) - opening the skill guide on page ${entry.guideId}"
        }
        return open(player, CATEGORY_HERO, TAB_SKILLS, skill = entry.guideId)
    }

    /**
     * A click on the skill guide's OWN left-hand icon strip (interface 1218), which until now
     * reached no handler at all: `IfButtonNHandler` had one skills branch and it matched 1466 only,
     * so all eight strip clicks in the session were decoded, logged, and routed nowhere.
     */
    fun handleSkillGuideClick(player: WorldPlayer, component: Int): Boolean {
        if (!enabled) return false
        val guide = skillGuideStrip[component] ?: return false
        // I-39: a click on a guide this player does not have open writes nothing.
        if (!player.interfaces.isOpened(SKILLGUIDE_IFACE)) {
            logger.info {
                "parentWindows: ${player.name} clicked $SKILLGUIDE_IFACE:$component with the skill " +
                    "guide not open - ignored"
            }
            return false
        }
        currentSkillGuide[player] = guide
        player.interfaces.open(
            id = SKILLGUIDE_LOADER, parent = SKILLGUIDE_IFACE, component = 0,
            walkable = true, native949 = true
        )
        logger.info {
            "parentWindows: ${player.name} picked skill guide page $guide off the strip " +
                "($SKILLGUIDE_IFACE:$component) - re-opened $SKILLGUIDE_LOADER on $SKILLGUIDE_IFACE:0"
        }
        return true
    }

    /** IF_BUTTON1 on the ribbon: 1431:0, slot = which button. Returns true when it was handled. */
    fun handleRibbonClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        if (slot == RIBBON_LOGOUT_ID) {
            logger.info { "parentWindows: ${player.name} pressed the ribbon's Logout button (id 7); the client fetches the world list itself - no category to open" }
            return false
        }
        RIBBON_SIDE_PANEL[slot]?.let { value ->
            if (!player.interfaces.isOpened(RIBBON_IFACE)) {   // I-39: no ribbon open, no reply
                logger.info { "parentWindows: ${player.name} sent ribbon id $slot with $RIBBON_IFACE not open - ignored" }
                return false
            }
            player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SIDE_PANEL, value))
            logger.info { "parentWindows: ${player.name} selected side panel via ribbon id $slot -> varp $VARP_SIDE_PANEL = $value (the reference client's whole reply)" }
            return true
        }
        val parent = SLOT_TO_PARENT[slot]
        if (parent == null) {
            logger.info {
                "parentWindows: ribbon slot $slot is not in the slot map - nothing opened. " +
                    "Add it with -Dopennxt.experiment.parentWindows.slots=$slot:<categoryStructId> " +
                    "(categories: ${categories.values.joinToString { "${it.structId}=${it.name}" }})"
            }
            return false
        }
        return open(player, parent, null)
    }

    /**
     * Mounts a category's tab into 1448 and stops the loading spinner.
     *
     * Returns false - having sent nothing - when the category or tab is not in the cache, so a bad
     * slot map cannot leave the client half-built.
     */
    fun open(player: WorldPlayer, categoryStructId: Int, tabStructId: Int?, skill: Int? = null): Boolean {
        if (!enabled) return false
        val category = categories[categoryStructId] ?: run {
            logger.warn { "parentWindows: no category struct $categoryStructId in the cache" }
            return false
        }
        val chosen = tabStructId ?: category.defaultTabStructId ?: run {
            logger.warn { "parentWindows: category ${category.name} declares no tabs" }
            return false
        }
        var tab = tab(chosen)
        if (tab.panels.isEmpty()) {
            // RUN7: 'Customisations' (32482) defaults to tab 21149, which has neither a name nor a
            // single panel group - the client fills that one from elsewhere. A category whose default
            // tab is empty is not a broken category, so fall through to its first tab that has panels
            // rather than refusing and leaving the spinner up.
            val fallback = category.tabStructIds.map { tab(it) }.firstOrNull { it.panels.isNotEmpty() }
            if (fallback == null) {
                logger.warn {
                    "parentWindows: no tab of '${category.name}' declares any panels - nothing to mount"
                }
                return false
            }
            logger.info {
                "parentWindows: '${category.name}' default tab $chosen declares no panels - " +
                    "using '${fallback.name}' (${fallback.structId}) instead"
            }
            tab = fallback
        }
        if (!player.interfaces.isOpened(PARENT_IFACE)) {
            logger.warn {
                "parentWindows: $PARENT_IFACE is not open for ${player.name} - the category window " +
                    "frame has to be mounted before its panels can go into it. Nothing sent."
            }
            return false
        }

 // THE SEQUENCE BELOW IS THE REFERENCE CLIENT'S. The reference client opening
        // Adventures/Quests, in order:
        //     HIDE 1477:715 = 0            unhide the parent frame itself
        //     varc 2911 = <parent index>
        //     CLOSE 1477:711 ; HIDE 1477:711 = 1
        //     HIDE 1448:3 = 0 ; CLOSE 1448:3 ; CLOSE 1218:0      drop what the layers held
        //     OPEN 1783 -> 1448:3 ; HIDE 1448:3 = 0 ; HIDE 1448:4 = 1
        //     OPEN 1500 -> 1448:5 ; HIDE 1448:5 = 0 ; HIDE 1448:6 = 1
        //     HIDE 1448:7..12 = 1
        //     script 187 [.., ..]
        //     HIDE 1448:1 = 1              the ring goes out LAST
        // Every step here is one of those. The one deliberate omission is script 187: five samples
        // ([0,4], [0,2], [3,2], [3,3], [3,4]) do not determine its two arguments, and RUN7 showed the
        // panels draw and the ring clears without it, so it is left unsent rather than guessed at.
        player.client.write(IfSethide(InterfaceHash(TOPLEVEL, PARENT_MOUNT), false))
 //the reference client ARMS the
        // window's tab strip and close button here - 1477:714 slots 0..24, 1477:717 slot 1,
        // 1477:716 slots 0..1, all mask 2 - right after the unhide and before varc 2911. The login
        player.interfaces.events(id = TOPLEVEL, component = 714, from = 0, to = 24, mask = 2)
        player.interfaces.events(id = TOPLEVEL, component = 717, from = 1, to = 1, mask = 2)
        player.interfaces.events(id = TOPLEVEL, component = 716, from = 0, to = 1, mask = 2)
        parentIndexOf(categoryStructId)?.let {
            player.client.write(ClientSetvarcSmall(VARC_OPEN_PARENT, it))
        }

        // Whatever the previous category left behind, in every layer, plus the chrome.
        closeOpenPanels(player)

 // ================= THE CHROME IS RE-OPENED =================
        //
        // [CHROME_MOUNT] and [CHROME_IFACE] have been declared since this file was written, with
        // the KDoc "the window chrome the reference client opens beside the parent frame; 1893 goes here" - and
        // until today their ONLY two uses in the tree were the close and the hide inside
        // [closeOpenPanels]. This server closed a window chrome it never opened. A constant whose
        // every call site is a teardown is a feature that was designed and not wired.
        //
        // WHY THE TRANSCRIBED SEQUENCE ABOVE DID NOT CATCH IT. The comment on the block above is
 // an accurate transcription of the click observation, and it really does read
        // "CLOSE 1477:711 ; HIDE 1477:711 = 1" with no open. That observation caught the close and
 // stopped. The full observation (, 29,737 decoded
        // s2c packets) carries the whole cycle, and the open is in it:
        //
        //     365.0s  OPEN 1893 -> 1477:711     <- the chrome, FIRST
        //     365.0s  OPEN      -> 1448:3       <- then the tab layer
        //     365.0s  OPEN      -> 1218:0       <- then the skill guide's loader
        //     ...
        //     379.4s  CLOSE 1477:711 ; OPEN 1477:711 ; CLOSE 1448:3 ; OPEN 1448:3
        //
        // The 379.4s line is the one that settles it: the reference client closes the chrome and RE-OPENS it in
        // the same instant, before touching the layers. So close-then-open is the cycle and we
        // were doing the first half. The interface id is read off the wire at offset 13 of
        // IF_OPENSUB and is 1893 on both opens - the value [CHROME_IFACE] already held.
        //
 // THE SYMPTOM, reported live: the Hero window "isn't lined up correctly, and it
        // goes under Activity Tracker for the skills window". A panel mounted into a frame whose
        // chrome was closed and never restored has nothing to position it against.
        //
        // Ordering is the reference client's: chrome first, then the layers below.
        //
 //: WHICH banner is the CATEGORY'S, not a constant. [P_BANNER_IFACE] carries the
        // measurement; [CHROME_IFACE] is Hero's and was being opened over Adventures, Settings and
        // every other category, none of which mounts anything at 1477:711.
        val banner = category.bannerIface
        if (System.getProperty("opennxt.experiment.ui.parentChrome") != "false" && banner != null) {
            player.interfaces.open(
                id = banner, parent = TOPLEVEL, component = CHROME_MOUNT,
                walkable = true, native949 = true
            )
            hide(player, CHROME_MOUNT, false, iface = TOPLEVEL)
        }

        val used = minOf(tab.panels.size, TAB_LAYERS.size)
        for (i in 0 until used) {
            val panel = tab.panels[i]
            val layer = TAB_LAYERS[i]
            player.interfaces.open(
                id = panel.interfaceId, parent = PARENT_IFACE, component = layer,
                walkable = true, native949 = true
            )
            player.client.write(
                RunClientScript(
                    script = SCRIPT_SET_COMPONENT_SIZE,
                    args = arrayOf(
                        panel.width, panel.height, panel.x, panel.y,
                        InterfaceHash(PARENT_IFACE, layer).hash
                    )
                )
            )
            hide(player, layer, false)
            // The reference client hides each used layer's HOLDER (the even component right after it). Skipping this
            // leaves the holder drawn over the panel.
            hide(player, layer + 1, true)
            openPanels[player] = (openPanels[player] ?: emptySet()) + layer
        }
        // Every other child of 1448:0 - layers AND holders - goes away, not just the odd ones.
        val usedComponents = TAB_LAYERS.take(used).flatMap { listOf(it, it + 1) }.toSet()
        for (comp in ALL_LAYER_COMPONENTS) if (comp !in usedComponents) hide(player, comp, true)

        // The skill guide is the one panel that needs a selection and a loader. [skill] is a SKILL
        // GUIDE id (struct param 3441), never a slot and never a skill id - see [skillSlots]. When
        // the open carries no selection of its own (a tab click, a ribbon click) the reference client still sends
        // the page the player last picked, so [currentSkillGuide] supplies it.
        if (tab.panels.any { it.interfaceId == SKILLGUIDE_IFACE }) {
            val page = skill ?: currentSkillGuide[player]
            page?.let {
                player.client.write(ClientSetvarcSmall(VARC_SKILLGUIDE_SKILL, it))
                player.client.write(RunClientScript(SCRIPT_SHOW_SKILLGUIDE, arrayOf(it)))
                currentSkillGuide[player] = it
            }
            player.interfaces.open(
                id = SKILLGUIDE_LOADER, parent = SKILLGUIDE_IFACE, component = 0,
                walkable = true, native949 = true
            )
        }

        hide(player, LOADING_LAYER, true)
        currentCategory[player] = categoryStructId

        logger.info {
            "parentWindows: ${player.name} opened '${category.name}' tab '${tab.name}' - " +
                tab.panels.joinToString {
                    "${com.opennxt.resources.Names949.iface(it.interfaceId)}@(${it.x},${it.y},${it.width}x${it.height})"
                } +
                " into 1448:${TAB_LAYERS.take(used).joinToString("/")}" +
                (if (skill != null) "; skill guide page $skill selected" else "") + "; loading layer hidden."
        }
        return true
    }

    /** Which layers this player currently has a panel in, so a switch can close them. */
    private val openPanels = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Set<Int>>()
    )

    /** The reference client's own close half: unhide, close, and drop the chrome. */
    private fun closeOpenPanels(player: WorldPlayer) {
        player.interfaces.close(TOPLEVEL, CHROME_MOUNT, native949 = true)
        hide(player, CHROME_MOUNT, true, iface = TOPLEVEL)
        for (layer in openPanels[player].orEmpty()) {
            hide(player, layer, false)
            player.interfaces.close(PARENT_IFACE, layer, native949 = true)
        }
        player.interfaces.close(SKILLGUIDE_IFACE, 0, native949 = true)
        openPanels.remove(player)
    }

    /**
     * The category's index as the client counts them, for varc 2911. Derived from [SLOT_TO_PARENT],
     * because the ribbon id is the only ordering this server can observe - to agree with
     * the reference client on the three categories the protocol cover (Hero 0, Customisations 1, Adventures 3;
     */
    private fun parentIndexOf(categoryStructId: Int): Int? =
        SLOT_TO_PARENT.entries.firstOrNull { it.value == categoryStructId }?.key

    private fun hide(player: WorldPlayer, component: Int, hidden: Boolean, iface: Int = PARENT_IFACE) {
        player.client.write(IfSethide(InterfaceHash(iface, component), hidden))
    }
}
