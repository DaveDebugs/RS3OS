package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

import com.opennxt.content.interfaces.InterfaceSlot
import com.opennxt.model.InterfaceHash
import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.serverprot.ifaces.*
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import mu.KotlinLogging

class InterfaceManager(val player: BasePlayer) {

    companion object {
        val logger = KotlinLogging.logger {  }

        /** The 949 world gameframe root. Every side panel mounts into this. */
        const val GAMEFRAME_949 = 1477

        /**
         * The IF_SETEVENTS bits that are NOT right-click options: 18, 19, 21, 23.
         *
         * Bits 1..10 are ops 1..10. Everything above them is other interaction --
         * dragging, drag targets, and the close/resize affordances. These four are
         * the ones the recorded login actually uses, ranked by how often it sends
         * them: 23 (356 uses), 19 (323), 18 (311), 21 (287). They outrank every
         * option bit; the busiest option bit, 10, has 282.
         *
         * Used both to CLASSIFY an outgoing mask (see [dragArmedIds]) and, via
         * [PANEL_INTERACT_MASK], to arm panels the protocol never covered.
         */
        const val PANEL_INTERACT_BITS =
            (1 shl 18) or (1 shl 19) or (1 shl 21) or (1 shl 23)   // 0xAC0000

        /**
         * The single most common mask in the recorded login: bits 18, 19 and 23,
         * sent 299 times, more than any other mask and with no option bits at all.
         * Taken verbatim rather than assembled, so it is the real server's own
         * expression of "this panel is interactive".
         */
        const val PANEL_INTERACT_MASK = 9175040                     // bits 18,19,23

        /** [observedPanelRemap] value meaning "this panel opens nothing on 949". */
        const val DROPPED = -1

        /**
         * THE BACKPACK GATE - why every side panel drew "<name> currently
         * unavailable" with a padlock instead of its contents.
         */
        private val panelMount949: Map<Int, Int> = mapOf(
            // pinned by the replay's own RunClientScript(8862, [panel, 1]) adjacency
            98 to 103,     // Backpack                 panel 2    (replay opens 1473)
            109 to 114,    // Worn Equipment           panel 3    (1464)
            284 to 300,    // Skills                   panel 0    (1466)
            295 to 311,    // Music Player             panel 10   (1416)
            327 to 343,    // Metrics                  panel 28   (1588)
            338 to 354,    // Drops                    panel 29   (1678)
            360 to 376,    // Quests                   panel 31   (190)
            371 to 387,    // Activity Tracker         panel 32   (1854)
            382 to 398,    // Achievement Paths        panel 41   (1894)
            393 to 409,    // Emotes                   panel 9    (590)
            475 to 501,    // Friends List             panel 14   (550)
            486 to 512,    // Clan Chat List           panel 16   (1110)
            497 to 523,    // Group Chat List          panel 27   (1519)
            508 to 545,    // Notes                    panel 11   (1417)
            519 to 556,    // Friends Chat List        panel 15   (1427)
            // the chat block: bracketed by Emotes (393->409) above and Friends List
            // (475->501) below, both pinned. 919 has seven blocks where 949 has
            // eight; the missing one is Group Ironman Chat (struct 50664, later
            // than Necromancy's 48237), so these seven map in order. Every value
 // re-derived from the cache and unchanged.
            404 to 420,    // All Chat                 panel 18   (137)
            415 to 431,    // Private Chat             panel 19   (1467)
            425 to 441,    // Friends Chat             panel 20   (1472)
            435 to 451,    // Clan Chat                panel 21   (1471)
            445 to 461,    // Guest Clan Chat          panel 22   (1470)
            455 to 471,    // Requests                 panel 23   (464)
            465 to 481,    // Group Chat               panel 25   (1529)
            // the ability block: KEY is that panel's 949 param 3509 and VALUE its
            // param 3505, so VALUE = KEY - 6 for all twelve and the row names the
            // same panel panelRemap949 substitutes at the same key. Corroborated
            // by clientscript 2141's per-panel case blocks; 175 (Magic) is pinned
 // outright by the replay's 8862(5, 1)., unchanged.
            142 to 136,    // Prayers                  panel 4
            153 to 147,    // Melee Abilities          panel 6
            164 to 158,    // Ranged Abilities         panel 7
            175 to 169,    // Magic                    panel 5
            186 to 180,    // Magic Abilities          panel 33
            197 to 191,    // Magic Spells (Combat)    panel 34
            208 to 202,    // Magic Spells (Teleport)  panel 35
            219 to 213,    // Magic Spells (Skilling)  panel 36
            230 to 224,    // Necromancy               panel 42
            241 to 235,    // Necromancy Abilities     panel 43
            252 to 246,    // Necromancy Incantations  panel 44
            263 to 257,    // Defensive                panel 8
        )

        /** -Dopennxt.experiment.backpackGate=false to send the raw 919 mounts. */
        private val backpackGateEnabled: Boolean =
            System.getProperty("opennxt.experiment.backpackGate") != "false"

        /**
         * The 949 mount for a 919 replay component of gameframe 1477, or the
         * component unchanged. Public so
         * can assert the table against the cache without duplicating it.
         */
        /**
         * Every component id [mountFor] treats as a REPLAY id and translates - the union of
         * the backpack-gate table's keys and the hudMount rows' `from` seats. A 949-native
         * id that lands in this set gets silently corrupted by the replay-path open/close,
         * tables (PanelCloseWiring) stay clear of it.
         */
        fun remapKeys949(): Set<Int> = panelMount949.keys + hudRows.map { it.from }

        fun mountFor(parent: Int, component: Int): Int {
            if (!backpackGateEnabled || parent != GAMEFRAME_949) return component
            return panelMount949[component] ?: hudMountFor(component)
        }

        /**
         * `-Dopennxt.experiment.hudMount=slot|dock|off`. **DEFAULT dock** (was slot until the 13:37 run below).
         */
        val hudMountMode: String =
            (System.getProperty("opennxt.experiment.hudMount") ?: "dock").trim().lowercase()

        private data class HudRow(val name: String, val from: Int, val slot: Int, val dock: Int, val iface: Int)

        private val hudRows: List<HudRow> = listOf(
            HudRow("ribbon", 59, 61, 64, 1431),
            HudRow("mainbar", 65, 67, 70, 1430),
            HudRow("bars", 70, 72, 75, 1670),
            HudRow("bars", 75, 77, 80, 1671),
 // 1672 at 85: REFERENCE. The row this table never had - see the block above.
            HudRow("bars", 80, 82, 85, 1672),
            HudRow("bars", 85, 87, 90, 1673),
            HudRow("minimap", 90, 94, 95, 1465), // dock 94 -> 95: REFERENCE. This is why the raster never painted.
            HudRow("camera", 91, 98, 96, 1919), // dock 98 -> 96: REFERENCE. // split from the minimap so each move is one switch
            HudRow("debuff", 568, 611, 613, 291),
            HudRow("slayer", 627, 674, 676, 1639),
 // evening, from the interfaces' OWN onloads (read from the cache unless noted):
            //   8420(frame, content, x, y, title, chromeStruct, PANEL) and 3421(self, arg, PANEL) both
            //   name the panel; control: every 8420 declaring panel 1017 is exactly the Bank set.
            HudRow("death", 576, 619, 621, 1483),     // 1483:1 8420[...,1010]; 1483:8 3421[...,1010]  Death Status
            HudRow("area", 589, 632, 634, 745),       // 745:7/:13 3421[...,1014]                       Area Status
            HudRow("buff", 572, 615, 617, 284),       // 2141 case 1009 = 10822(284:18), parallel to 1038/291 (STRONG)
            HudRow("xp", 619, 598, 668, 1213), // dock 600 -> 668: REFERENCE. // 1213:0 onload 5658->5659 sethides 1477:600 on varbit 19964 (STRONG)
            HudRow("target", 760, 594, 814, 1488), // dock 596 -> 814: REFERENCE. // 5/7702 if_opensub(1488:4, 1490) bracketed by panel-2008 helpers (STRONG)
        )

        /** `-Dopennxt.experiment.hudMount.announce=false` to re-seat without the 8310 announcement. */
        val hudAnnounce: Boolean = System.getProperty("opennxt.experiment.hudMount.announce") == "true"

        /**
         * PANELS THIS SERVER REFUSES TO OPEN AT LOGIN, keyed by `(interface << 16) or component`
         * on gameframe 1477, with the reason each one is refused.
         */
        val loginSuppressed: Map<Int, String> = linkedMapOf(
            ((568 shl 16) or 691) to "toplevel_v2_ribbon_extra - popup, behind the 'popups' row gate",
            ((598 shl 16) or 638) to "repoverlay_farming - the farming-requests overlay. It belongs at " +
                "the player-owned farm, not on every login; the reference client opens it at 46.1s and closes it at " +
                "138.7s, i.e. it is transient there too. Reported live.",
            ((634 shl 16) or 739) to "Premier Pass - a Central Interface window, not a HUD panel",
            ((635 shl 16) or 615) to "Undead Army - a Necromancy Central Interface window; it sat on the Buff Bar slot",
            ((653 shl 16) or 797) to "event_crafting / Travelling Artisan - IT COVERS THE SCREEN AND ITS " +
                "CLOSE BUTTON HAS NO EVENT MASK, so a player who gets it cannot shut it"
        )

        /** Whether an open of [iface] at 1477:[component] is one this server refuses; the reason, or null. */
        fun loginSuppressionReason(iface: Int, component: Int): String? =
            loginSuppressed[(iface shl 16) or component]

        /** `bars` implies `mainbar`; `all` is everything. */
        val hudRowsEnabled: Set<String> = run {
 // REVIEW: default "all,popups" - every record since 08-29 (panel_interfaces.tsv, UI-A2A3 step 2,
            // UI-C1 Q6, PROTOCOL-SESSION 16 #1) puts the HUD block in its 3505 docks, and the windowed pack hides
            // whatever sits on the 919 replay seats. RUN4a (rows=all + 8884[15]) is the best HUD state measured.
            val raw = (System.getProperty("opennxt.experiment.hudMount.rows") ?: "all,popups").trim().lowercase()   // popups: verified 15:01 - the three windows simply stop appearing
            val given = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            // "all" = every table row (popups stays a separate, explicit word); it may
 // be combined, e.g. rows=all,popups. 15:01: "all,popups" parsed
            // as two unknown names and re-seated NOTHING - that run tested only the
            // popup drops and the 8110 re-run.
            val names = if ("all" in given) given - "all" + hudRows.map { it.name }.toSet() else given
            if ("bars" in names) names + "mainbar" else names
        }

        /** The HUD-block table for the current mode and row selection; public so a check can assert it. */
        fun hudMountTable(): Map<Int, Int> = when (hudMountMode) {
            "slot" -> hudRows.filter { it.name in hudRowsEnabled }.associate { it.from to it.slot }
            "dock" -> hudRows.filter { it.name in hudRowsEnabled }.associate { it.from to it.dock }
            else -> emptyMap()
        }

        fun hudRowIfaces(): Map<Int, Int> = hudRows.associate { it.iface to it.dock }

        /** Every row regardless of selection, for the check to reason about the full design. */
        fun hudMountTableAllRows(): Map<Int, Int> = when (hudMountMode) {
            "slot" -> hudRows.associate { it.from to it.slot }
            "dock" -> hudRows.associate { it.from to it.dock }
            else -> emptyMap()
        }

        private fun hudMountFor(component: Int): Int = hudMountTable()[component] ?: component

        /** The whole table, for the check tool. */
        fun panelMountTable(): Map<Int, Int> = panelMount949

        // =================================================================
        // THE PANEL REMAP
        // =================================================================

        /**
         * 919 -> 949 gameframe panel remap, keyed by component of root 1477.
         *
         * The 919 replay in `WorldPlayer.added` does not merely name three
         * dead interface ids - the WHOLE ability/prayer panel block is shifted
         * by one slot, because two panel families were DELETED between the two
         * builds and three Necromancy panels inserted. Opening the raw 919
         * layout is what scatters the operator's interface.
         */
        val panelRemap949: Map<Int, Int> = mapOf(
            142 to 1458,   // Prayers                  (919 opened 1460 here)
            153 to 1460,   // Melee Abilities   fam 1  (919: 1881, deleted fam 6)
            164 to 1452,   // Ranged Abilities  fam 2  (919: 1888, deleted fam 7)
            175 to 1461,   // Magic             fam 3  (919: 1452)
            186 to 1884,   // Magic Abilities   fam 8  (919: 1461)
            197 to 1885,   // Magic Spells (Combat)    fam 9  (919: 1884)
            208 to 1887,   // Magic Spells (Teleport)  fam 10 (919: 1885)
            219 to 1886,   // Magic Spells (Skilling)  fam 11 (919: 1887)
            230 to 1219,   // Necromancy        fam 4  (919: 1886)
            241 to 1220,   // Necromancy Abilities     fam 14 (919: 1883)
            252 to 1221,   // Necromancy Incantations  fam 15 (919: 1449)
            263 to 1883,   // Defensive         fam 12 - see the tier note above
        )

        /** Components of 1477 whose 919 interface has no 949 equivalent at all. */
        val panelDrop949: Set<Int> = setOf(131)   // Familiar - new panel, nothing to open

        /** -Dopennxt.world.panelRemap=false to send the raw 919 layout instead. */
        val panelRemapEnabled: Boolean
            get() = System.getProperty("opennxt.world.panelRemap") != "false"

        val mountArmEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.ui.mountArm") == "true"

        /** Non-zero so the hit walk descends; bit 0 only so no op can fire. */
        const val MOUNT_WALKABLE_MASK = 1

        /**
         * Components of 1477 that ALREADY carry a non-zero cache optmask, and
         * are therefore left alone.
         *
         * Arming these would be worse than useless. The walk already descends
         * into them, so there is nothing to fix - and because the client's
         * event map has no removal path, writing mask 1 over a cache optmask of
         * 254 would PIN it at 1 for the session and break a component that
         * works today (1477:8 is the backpack toggle, and it is the one that
         * still received clicks while the dialogue mount did not).
         *
         * Measured, not assumed - re-derive with:
         *   SELECT component, optmask FROM interfaces
         *    WHERE iface=1477 AND optmask!=0
         * which returns exactly these 7 of 923.
         */
        val MOUNT_ARM_SKIP_1477: Set<Int> = setOf(8, 24, 25, 47, 793, 798, 809)

        /** 0xffff on an IF_SETEVENTS slot means -1, "the component itself". */
        const val MOUNT_SLOT_SELF = 65535


        /**
         * `-Dopennxt.experiment.defensivePanel1880=true`. **DEFAULT OFF.**
         *
         * Swaps row 1477:263 from 1883 to 1880. Changes exactly one row and
         * nothing else; the substitution count stays 12.
         */
        val defensivePanel1880: Boolean
            get() = System.getProperty("opennxt.experiment.defensivePanel1880") == "true"

        const val DEFENSIVE_PANEL_PROVENANCE: String =
            "1477:263 is struct param 3509 of enum-7716 panel 8, name 'Defensive' (cache). " +
                "interface 1883 carries onload script 8422 with FAMILY argument 12, and the " +
                "group rule that reproduces 11 of the other 11 checkable rows of panelRemap949 assigns " +
                "family 12 to 'Defence Abilities' at 1477:274, one slot below. family 5, the " +
                "index the same rule assigns to Defensive, has exactly ONE member in the whole 949 index " +
                "3 - interface 1880 - and 1880 is what Defensive's struct param 3514 points at, which in " +
                "all four checkable groups is the group's other member and never the body the server " +
                "opens. AUTHORED: nothing; both candidates are cache values and neither is invented. " +
                "SHIPPED DEFAULT 1883 because that is the value the test run server-20260816-135312 " +
                "exercised (13 'Panel remap:' lines, clean close, no crash record); 1880 is one flag " +
                "away via -Dopennxt.experiment.defensivePanel1880=true. WHAT WOULD SETTLE IT: one live " +
                "session with the Defensive panel opened and read on screen - the panel draws its own " +
                "name, so 'Defensive' vs 'Defence Abilities' is visible. Failing that, decoding " +
                "clientscript 8422 and 564 against a true 949 cache snapshot - and that IS available: " +
                "re-measured, the served cache decodes 21,037 of 21,110 scripts exactly " +
                "(99.65%) with the 949 command table. The '109 of 21,110' this string used to end on " +
                "was stale."

        /** The remap actually in force, after [defensivePanel1880]. */
        fun panelRemapTable(): Map<Int, Int> =
            if (defensivePanel1880) panelRemap949 + (263 to 1880) else panelRemap949

        /**
         * What the 919 replay in `WorldPlayer.added` opens at each component
         * of the shifted block. Transcribed from that file, 13 rows.
         */
        val panelReplay919: Map<Int, Int> = mapOf(
            131 to 1458,   // Familiar slot in 949 - dropped
            142 to 1460,
            153 to 1881,   // deleted family 6 - 0 components in 949
            164 to 1888,   // deleted family 7 - 0 components in 949
            175 to 1452,
            186 to 1461,
            197 to 1884,
            208 to 1885,
            219 to 1887,
            230 to 1886,
            241 to 1883,
            252 to 1449,
            263 to 1882,
        )

        /**
         * THE FAILURE MODE THIS EXISTS TO PREVENT, and it has happened once:
         * the remap was applied in [open] but NOT in [events], so all twelve
         * panels were opened on the corrected interface and then ARMED on the
         * 919 one - an interface that either is not open or, for 1881 and 1888,
         * does not exist in this cache at all. The panels come up and nothing in
         * them can be clicked.
         *
         * The mapping is per-SLOT, not per-id: the replay says "open X at
         * 1477:c, then arm component k of X", so if 949 puts Y at 1477:c the
         * arming belongs to Y's component k. Hence
         * `panelReplay919[c] -> panelRemapTable()[c]`, and any id the replay
         * opened only at a DROPPED component is armed on nothing and is skipped.
         *
         * Derived, never written out by hand, so it cannot disagree with [open].
         * With the shipped tables that is 12 substitutions and 1 drop (1458),
         * mirroring open()'s 12 substitutions and 1 drop.
         */
        fun panelEventRemap(): Map<Int, Int> {
            val remap = panelRemapTable()
            val out = LinkedHashMap<Int, Int>()
            for ((slot, replayId) in panelReplay919) {
                val corrected = remap[slot] ?: continue
                if (corrected != replayId) out[replayId] = corrected
            }
            return out
        }

        /** 919 interface ids the replay opened only at a dropped slot. */
        fun panelEventDrop(): Set<Int> {
            val remap = panelRemapTable()
            return panelReplay919.entries
                .filter { it.key in panelDrop949 }
                .map { it.value }
                .filter { id -> panelReplay919.none { e -> e.value == id && remap.containsKey(e.key) } }
                .toSet()
        }

        /**
         * The 949 interface an IF_SETEVENTS naming [id] belongs on, or null if
         * it belongs on nothing and must be dropped. Unlisted ids pass through.
         */
        fun eventInterfaceFor(id: Int): Int? {
            if (!panelRemapEnabled) return id
            if (id in panelEventDrop()) return null
            return panelEventRemap()[id] ?: id
        }

        /**
         * `-Dopennxt.experiment.panelBarComponent=true`. **DEFAULT OFF.**
         *
         * Also moves the COMPONENT number when a substituted panel's ability
         * bar sits somewhere else on the 949 interface. See
         * [PANEL_BAR_PROVENANCE] for why this cannot be defaulted on.
         */
        val panelBarComponentEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.panelBarComponent") == "true"

        /**
         * The `(bar, minus, plus)` triple each ability interface's onload
         * `script 8422` names, read from index 3. Census over the 26 carriers:
         * 24 use `(7, 8, 11)`, 1450 uses `(0, 1, 7)` and 1460 uses `(5, 6, 7)`.
         * Only 1460 differs among anything this table can reach, and 1460 is
         * also the one whose component parent vector differs from the other 23.
         *
         * The 919 sources 1881 and 1888 have ZERO components in 949 so their
         * triples cannot be read; `[5, 6, 7]` for them is AUTHORED, from the
         * replay arming them at component 5 with the same mask pair (7..16
         * mask 2) that it uses on 1460's component 5 while every `(7, 8, 11)`
         * interface gets component 7. That authored pair is the entire reason
         * this correction defaults OFF.
         */
        private val abilityTriple: Map<Int, IntArray?> = mapOf(
            // from index 3, script 8422's first three arguments.
            1460 to intArrayOf(5, 6, 7),
            1449 to intArrayOf(7, 8, 11),
            1452 to intArrayOf(7, 8, 11),
            1461 to intArrayOf(7, 8, 11),
            1882 to intArrayOf(7, 8, 11),
            1883 to intArrayOf(7, 8, 11),
            1884 to intArrayOf(7, 8, 11),
            1885 to intArrayOf(7, 8, 11),
            1886 to intArrayOf(7, 8, 11),
            1887 to intArrayOf(7, 8, 11),
            1219 to intArrayOf(7, 8, 11),
            1220 to intArrayOf(7, 8, 11),
            1221 to intArrayOf(7, 8, 11),
            1880 to intArrayOf(7, 8, 11),     // reachable via defensivePanel1880
            // ABSENT: 1458 has 54 components and carries no script 8422,
            // so it is not in this family and NOTHING may be mapped onto it. An
            // earlier draft let it fall through to the (7, 8, 11) default and
            // silently moved 1460:5 to 1458:7 - a component of a panel that has
            // no ability bar at all. null is load-bearing, not padding.
            1458 to null,
            // AUTHORED - zero components in 949, triple unreadable. See
            // [PANEL_BAR_PROVENANCE]; this pair is why the switch defaults OFF.
            1881 to intArrayOf(5, 6, 7),
            1888 to intArrayOf(5, 6, 7),
        )

        /** null = "not an ability-family interface", and nothing is mapped onto it. */
        private fun tripleOf(id: Int): IntArray? = abilityTriple[id]

        const val PANEL_BAR_PROVENANCE: String =
            "26 interfaces in 949 index 3 carry an onload script 8422; its first three " +
                "arguments are component hashes on the interface itself, and the triples are " +
                "(7,8,11) x24, (5,6,7) for 1460 and (0,1,7) for 1450. of the 12 remap rows, " +
                "exactly ONE changes the triple - 1477:164, where the replay arms 919 interface 1888 at " +
                "component 5 and the 949 target 1452 puts that control at component 7. AUTHORED: that " +
                "1888's own triple is (5,6,7), which cannot be measured because 1888 has zero components " +
                "in this cache; it is inferred from the replay arming 1888:5 and 1881:5 with the same " +
                "from=7 to=16 mask=2 pair it uses on 1460:5, while every (7,8,11) interface gets " +
                "component 7. WHAT WOULD SETTLE IT: a 919 cache snapshot, or one live session with " +
                "-Dopennxt.experiment.panelBarComponent=true in which the Ranged Abilities bar responds " +
                "to a click. DEFAULT OFF: with it off the events land on 1452:5, which exists but is a " +
                "16x32 control rather than the 190x32 bar - inert, not harmful."

        /**
         * The component an IF_SETEVENTS for [component] of 919 interface [from]
         * belongs on once that panel is served by 949 interface [to].
         */
        fun barComponentFor(from: Int, to: Int, component: Int): Int {
            if (!panelBarComponentEnabled || from == to) return component
            val src = tripleOf(from) ?: return component
            val dst = tripleOf(to) ?: return component
            val i = src.indexOf(component)
            return if (i < 0) component else dst[i]
        }
    }

    /**
     * The current opened root interface
     */
    private var root: OpenedInterface? = null

    /**
     * Checks if an interface is opened or not
     */
    /**
     * How many interfaces [open] has been called for on this player, successfully
     * enough to be recorded. Read-only so the panel-visibility
     * apply can state on the log how many panels existed when it fired, which is
     * the only number that says whether that apply could have done anything.
     */
    fun openedCount(): Int = openedIds.size

    fun isOpened(id: Int): Boolean {
        return root?.findChild(id) != null
    }

    /**
     * Opens a root interface, closing the previous root interface if necessary
     *
     * Overriding a previous root interface is undefined behaviour as of now, and is discouraged.
     */
    fun openTop(id: Int) {
        if (interfaceMode == "none") {
            logger.info("interfaces=none: skipping ROOT interface $id")
            return
        }
        logger.info("opening ROOT interface ${com.opennxt.resources.Names949.iface(id)}")
        if (root != null) {
            // TODO Fire on_interface_close event
        }

        root = OpenedInterface(id, walkable = true)
        player.client.write(IfOpenTop(id))
    }

    fun adoptTop(id: Int) {
        logger.info("adopting ROOT interface ${com.opennxt.resources.Names949.iface(id)} (already on the client from the raw replay)")
        root = OpenedInterface(id, walkable = true)
    }

    /** Records a replayed IF_OPENSUB in the tree; false (and a warning) when the parent is unknown. */
    fun adoptSub(id: Int, parent: Int, component: Int, walkable: Boolean): Boolean {
        val root = root ?: run { logger.warn("adoptSub($id on $parent:$component) before any root - ignored"); return false }
        val host = root.findChild(parent) ?: run { logger.warn("adoptSub($id on $parent:$component): parent not in the tree - ignored"); return false }
        host.children[component] = OpenedInterface(id, walkable = walkable)
        openedIds.add(id)
        return true
    }

    fun hasSubAt(id: Int, component: Int): Boolean {
        val base = root?.findChild(id) ?: return false
 // RAW on purpose: every caller passes a 949-native
        // component id, and mountFor would misread ids that collide with remap keys.
        // NOTE: replay-path opens store their children under the REMAPPED slot, so this
        // answers "is the real 949 mount occupied" for those too.
        return base.children.containsKey(component)
    }

    /**
     * Closes a component from an interface. This will first run all close listeners followed by sending the packet
     */
    fun close(id: Int, component: Int, native949: Boolean = false) {
        val base = root?.findChild(id) ?: return

        // open() stores the child under the 949 mount, so a close naming the 919
        // component has to go through the same map or it silently matches nothing.
        // A native-949 caller (see [open]'s native949 KDoc) already names the real
        // mount and must NOT be remapped - several 949 dock ids double as remap keys.
        val slot = if (native949) component else mountFor(id, component)

        if (!base.children.containsKey(slot)) return

        val removed = base.children.remove(slot)
        // TODO Recursively go through removed?
        // TODO Fire on_interface_close event
        logger.info("Closed interface $removed at $id:$slot")

        // 949 opcode 107. The client keys its mount map on IF_OPENSUB's own
        // `parent` hash (data/prot/949/serverProt/IF_CLOSESUB.txt traces the
        // key back to the field IF_OPENSUB writes), so the
        // hash sent here has to be the REMAPPED slot - the one open() actually
        // mounted at - and not the caller's 919 component number.
        player.client.write(IfClosesub(InterfaceHash(id, slot)))
    }

    /**
     * Attempts to open an interface on another interface. This requires a root interface to be present, as well as the
     * interface that this interface opens.
     *
     * If this action were to override another interface, the following would happen:
     * - The previous interface would be overwritten
     * - The [IfOpenSubPacket] would be sent again
     * - No on_interface_close listener would be fired
     * - A warning would be logged to the console, regardless of the debug mode
     *
     * If [parent] is set to -1, the interface will be opened on the root interface
     */
    /**
     * Interfaces to NOT open, by id: -Dopennxt.world.skipInterfaces=1449,1452
     *
     * WorldPlayer.added() replays 62 hardcoded interface opens at login. One of
     * them is a full-screen modal ("TRAVELLING ARTISAN") that covers the game
     * view, and it CANNOT BE CLOSED: closing needs a click event on its close
     * component, the client only reports clicks on components the server has
     * enabled via IF_SETEVENTS, and that one was never enabled. A run where the
     * user clicked it produced ZERO inbound IF_BUTTON1 - the client never sent
     * anything, so there is nothing to handle.
     *
     * Skipping the open is therefore the only lever that works from here, and
     * it is data rather than code so it can be bisected without a rebuild.
     *
     * Knock-on effect worth knowing: with the panel covering the screen the only
     * clickable ground was a thin strip at the bottom, so every MOVE_GAMECLICK
     * landed far to the south and walking "kept going one way". That was the
     * interface, not the movement code.
     */
    private val skipInterfaces: Set<Int> =
        (System.getProperty("opennxt.world.skipInterfaces") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /**
     * all (default) | hud (root only, no sub-interfaces) | none
     *
     * `hud` gives a clear game view for movement and world testing at the cost
     * of the minimap and side panels. It is a diagnostic setting, not a fix.
     */
    private val interfaceMode: String =
        (System.getProperty("opennxt.world.interfaces") ?: "all").lowercase()

    /**
     * Interface ids this build's CACHE actually defines (index 3 archive ids).
     *
     * WorldPlayer replays 61 sub-interface opens recorded from build 919. An
     * audit of that replay against 949's decoded index 3 (1,869 interfaces,
     * 104,601 components) found 58 of them still valid - so the replay is
     * mostly right - but THREE interface ids no longer exist on 949:
     *
     *     1881 -> gameframe 1477 component 153   (4 IF_SETEVENTS aimed at it)
     *     1888 -> gameframe 1477 component 164   (4 IF_SETEVENTS aimed at it)
     *     1680 -> gameframe 1477 component  39
     *
     * All three have dense neighbours present (1880/1882, 1887/1890,
     * 1679/1681), which is the signature of renumbering between builds rather
     * than removal of a feature.
     *
     * Opening one sends IF_OPENSUB naming an interface the client cannot
     * resolve, and the 8 IF_SETEVENTS that follow configure components on a
     * panel that was never opened. That is a plausible source of the
     * half-old/half-new gameframe: three slots left empty and eight event
     * bindings landing nowhere.
     *
     * Read from the CACHE, not from rs3.sqlite - a collision-only database has
     * an empty `interfaces` table and a db-driven guard would then block every
     * interface. Failure to read the table leaves this null and the guard
     * disables itself rather than blocking everything, because the failure mode
     * of "block all interfaces" is a black screen (measured: interfaces=hud
     * dropped JS5 from 694 archives to 16).
     */
    private val definedInterfaceIds: IntOpenHashSet? by lazy {
        try {
            val table = OpenNXT.filesystem.getReferenceTable(3)
                ?: throw IllegalStateException("no reference table for index 3")
            val ids = IntOpenHashSet(table.archives.keys)
            if (ids.isEmpty()) throw IllegalStateException("index 3 has no archives")
            logger.info("Interface guard: ${ids.size} interfaces defined in the cache")
            ids
        } catch (t: Throwable) {
            logger.warn("Interface guard: could not read index 3 (${t.message}); " +
                "guard DISABLED - undefined interfaces will be sent as before")
            null
        }
    }

    /**
     * Substitutions [open] actually performed for THIS player, 919 id -> 949 id,
     * plus dropped ids mapped to [DROPPED]. Ground truth for [events]: it is
     * recorded from the opens that really happened rather than predicted, so a
     * change in `WorldPlayer`'s replay cannot desynchronise the two paths.
     * The static [panelEventRemap] is the fallback for an IF_SETEVENTS that
     * arrives before the panel was opened, and the two are compared out loud.
     */
    private val observedPanelRemap = Int2ObjectOpenHashMap<Int>()

    /** Every interface id actually opened for this player, for [armAllOpenComponents]. */
    private val openedIds = IntOpenHashSet()

    /** Every interface id this player has had ANY IF_SETEVENTS sent for. */
    private val armedIds = IntOpenHashSet()

    /**
     * Interface ids armed with at least one bit from [PANEL_INTERACT_BITS] -- i.e.
     * armed for something beyond plain right-click options.
     */
    private val dragArmedIds = IntOpenHashSet()

    /** false only when the cache is readable AND does not define this id. */
    private fun interfaceExists(id: Int): Boolean =
        definedInterfaceIds?.contains(id) ?: true

    fun open(
        id: Int,
        parent: Int = -1,
        component: Int,
        walkable: Boolean = false,
        /**
         * The caller asserts [id] and [component] are BUILD-949-NATIVE - taken from this
         * cache's own structs/enums, not from the 919 replay. Skips the panel remap and
         * [mountFor], both of which exist to translate REPLAY ids and silently corrupt a
         * native id that happens to collide with a remap key review finding 1:
         */
        native949: Boolean = false
    ) {
        if (interfaceMode == "hud" || interfaceMode == "none") {
            logger.info("interfaces=$interfaceMode: skipping interface $id (parent $parent component $component)")
            return
        }
        if (id in skipInterfaces) {
            logger.info("skipInterfaces: skipping interface $id (parent $parent component $component)")
            return
        }
        // Remap the shifted panel block BEFORE the existence check, so the
        // substituted id is what gets validated and sent.
        var effectiveId = id
        if (!native949 && panelRemapEnabled && parent == 1477) {
            if (component in panelDrop949) {
                logger.info("Panel remap: 1477:$component has no 949 equivalent for 919 " +
                    "interface $id - not opening (949's panel there is new).")
                // Remember the drop so events() disarms it instead of arming an
                // interface that is not on screen. This is the half that was
                // missing when the remap regressed.
                observedPanelRemap[id] = DROPPED
                return
            }
            panelRemapTable()[component]?.let { corrected ->
                if (corrected != id) {
                    logger.info("Panel remap: 1477:$component was opening 919 interface $id; " +
                        "949 mounts $corrected there - substituting.")
                    effectiveId = corrected
                    observedPanelRemap[id] = corrected
                    // Drift alarm: the static table events() falls back on is a
                    // transcription of WorldPlayer's replay. If it ever stops
                    // matching what open() really did, say so with numbers.
                    val predicted = panelEventRemap()[id]
                    if (predicted != corrected) {
                        logger.warn("Panel remap: panelReplay919 predicted 919 interface $id -> " +
                            "$predicted but open() substituted $corrected at 1477:$component - " +
                            "the transcription of WorldPlayer.added() has drifted.")
                    }
                }
            }
        }

        if (!interfaceExists(effectiveId)) {
            logger.warn("Interface guard: interface $effectiveId is NOT defined in this cache - " +
                "skipping the open on parent $parent component $component.")
            return
        }

        // The backpack gate: the 919 replay names the 919 mount component, and
        // 949's is a different one. See [panelMount949] - the client's own
        // availability predicate (clientscript 8863) asks if_hassub() about the
        // panel's struct param 3505, so opening one component off means the
        // padlock, not the panel.
        val effectiveComponent = if (native949) component else mountFor(parent, component)
        if (effectiveComponent != component) {
            logger.info("Backpack gate: 949 mounts this panel at 1477:$effectiveComponent, " +
                "not 1477:$component - moving interface $effectiveId.")
        } else if (parent == 1477) {
            val dummyMount = when (component) {
                98 -> 103
                109 -> 114
                284 -> 300
                else -> -1
            }
            if (dummyMount != -1) {
                player.client.write(com.opennxt.net.game.serverprot.ifaces.IfOpenSub(1448, walkable, com.opennxt.model.InterfaceHash(1477, dummyMount)))
                logger.info("Padlock bypass: mounted dummy interface 1448 on 1477:$dummyMount for panel $component")
            }
        }

        // Log every open so the ids can be matched to what appears on screen -
        // that is how the offending panel gets identified instead of guessed at.
        // Print what is ACTUALLY SENT, and name the substitution when there was
        // one. This line used to read `interface $id ... component
        // $effectiveComponent` - the id from BEFORE the panel remap next to the
        // component from AFTER it, a pair that never existed on the wire.
        //
 // It cost real time on. A reading of these logs concluded
        // "the server opens interfaces 1881 and 1888, which have zero components
        // in this cache", and I confirmed the premise from the same line before
        // catching it. What actually happens is
        //     Panel remap: 1477:153 was opening 919 interface 1881;
        //                  949 mounts 1460 there - substituting.
        // so 1460 goes out and 1881 never reaches the wire at all. The guard
        // above was working correctly the whole time; only the log was lying.
        logger.info(
            "opening interface ${com.opennxt.resources.Names949.iface(effectiveId)}" +
                (if (effectiveId != id) " (919 replay said $id; remapped)" else "") +
                " on parent ${if (parent == -1) "<root>" else parent} component $effectiveComponent" +
                (if (effectiveComponent != component) " (replay said $component)" else "")
        )

        val root = root
            ?: throw IllegalStateException("Attempted to open interface before opening root interface for player ${player.name}")

        val parentId = if (parent == -1) root.id else parent

        val child = root.findChild(parentId)
            ?: throw IllegalStateException("Attempting to open interface $id on parent $parent, but parent interface was not found for player ${player.name}")

        if (child.children[effectiveComponent] != null)
            logger.warn("Overriding an interface on ${child.id}:$effectiveComponent with interface $id for player ${player.name}")
            
        // Hide the padlocks manually by setting their components to hidden,
        // rather than corrupting the interface hierarchy by mounting the
        // Backpack inside them.
        //
        // ------------------------------------------------------------------
 //: THE LINE BELOW WAS COMMENTED OUT, AND NOT ON PURPOSE.
        //
        // It had been appended to the end of the comment line above with no
        // newline between them:
        //
        //     // ...by mounting the Backpack inside them!child.children[...] = ...
        //
        // so the compiler saw prose. open() therefore wrote IF_OPENSUB to the
        // client and added to openedIds, but never recorded the interface in
        // the tree. Everything that asks the tree a question was consequently
        // wrong for every sub-interface ever opened:
        //
 // * isOpened answered false for all of them. The session
        //     logged 923 "IF_SETEVENTS for interface N which is NOT open"
        //     warnings across 57 distinct interfaces - every one of them a
        //     panel that HAD been opened.
        //   * close() could not find what to close, which is the "X does not
        //     close anything" symptom.
        //   * findChild(parentId) above throws when the parent is itself a
        //     sub-interface, so only interfaces mounted DIRECTLY on the root
        //     could ever open. Nothing could nest two deep.
        //
        // Restoring it makes the tree match what the client was actually told.
        // Expect the "Overriding an interface" warning above to start firing
        // where it never could before: that is the guard working, not a new
        // fault.
        // ------------------------------------------------------------------
        child.children[effectiveComponent] = OpenedInterface(effectiveId, walkable = walkable)
        openedIds.add(effectiveId)

        player.client.write(IfOpenSub(effectiveId, walkable, InterfaceHash(parentId, effectiveComponent)))

        // ARM THE MOUNT, or nothing inside this sub-interface can be clicked.
        // See [mountArmEnabled] for the hit-walk instructions and the live
        // measurement. This arms the PARENT's component, not the child's, and
        // never disarms it - the client has no removal path for an event entry.
        if (mountArmEnabled && !(parentId == 1477 && effectiveComponent in MOUNT_ARM_SKIP_1477)) {
            events(parentId, effectiveComponent, MOUNT_SLOT_SELF, MOUNT_SLOT_SELF,
                MOUNT_WALKABLE_MASK)
        }
        // TODO Fire on_interface_open listener
    }

    /**
     * Arm EVERY component of every interface this player currently has open.
     *
     * WHY THIS EXISTS
     *
     * The client transmits nothing for a component the server has not armed
     * with IF_SETEVENTS. The login sequence is a replayed observation that arms a
     * subset -- enough for the panels it happened to exercise -- so the rest of
     * the UI is inert: clicking produces no packet at all, which reads as "the
     * interface is broken" when in fact the client was told to stay silent.
     *
     * This does not make the UI WORK. A button that transmits still does
     * nothing until something server-side answers it, and much of this client's
 * panel behaviour lives in clientscripts (index 12) that this server does
     * not run. What it does is turn silence into evidence: every click now
     * arrives as IF_BUTTON with its interface and component, which
     * [com.opennxt.net.game.handlers.IfButtonNHandler] logs, and that log is how
     * the component map gets built. Its own KDoc says as much.
     *
     * Mask 2046 (0x7FE) is ops 1-10. It is not invented -- it is one of the
     * masks the recorded login already uses, on interface 1464.
     *
     * DEFAULT OFF: -Dopennxt.experiment.ui.armAll=true
     */
    fun armAllOpenComponents(mask: Int = 2046) {
        val table = try {
            OpenNXT.filesystem.getReferenceTable(3)
        } catch (t: Throwable) {
            logger.warn("armAll: cannot read index 3 (${t.message}); nothing armed")
            return
        } ?: return

        var interfaces = 0
        var components = 0
        for (id in openedIds.toIntArray().sortedArray()) {
            // Components are the files of the interface's own archive, so the
            // set is the cache's, never a guessed range.
            val archive = try { table.loadArchive(id) } catch (t: Throwable) { null } ?: continue
            val comps = archive.files.keys
            if (comps.isEmpty()) continue
            interfaces++
            for (c in comps) {
                events(id = id, component = c, from = 65535, to = 65535, mask = mask)
                components++
            }
        }
        logger.info(
            "armAll: IF_SETEVENTS sent for $components component(s) across $interfaces open " +
                "interface(s), mask $mask (ops 1-10). Clicks now REACH the server and are logged " +
                "by IfButtonNHandler. They still do nothing until a handler answers them."
        )
    }

    /**
     * Arm open panels for the interactions that are NOT right-click options --
     * dragging, resizing, and the close button.
     *
     * ## Why this exists
     *
     * The login sequence is a transcription of an observation, so it arms exactly the
     * components that observation happened to exercise and nothing else. Measured
     * against what login actually opens:
     *
     *     interfaces opened at login                : 60
     *     interfaces armed at all                   : 43
     *     ...armed with any bit from PANEL_INTERACT : 24
     *
     * So 17 open panels are never armed at all -- a click on them transmits
     * nothing -- and 19 more are armed with option bits only. 36 of 60 open
     * panels therefore have no drag/resize/close capability, which is exactly the
     * reported symptom: panels draw, but will not move, resize, or close on X.
     *
     * ## Why these bits
     *
     * [PANEL_INTERACT_BITS] is not invented. Across every IF_SETEVENTS the
     * recorded login sends, the four most-used bits are 23 (356 uses), 19 (323),
     * 18 (311) and 21 (287), and the single most common mask it sends --
     * [PANEL_INTERACT_MASK], 299 uses, more than any other -- is exactly bits
     * 18/19/23 with NO option bits at all. That is the shape of "this panel is
     * interactive" as the real server expresses it.
     *
     * ## Two modes, because they carry different risk
     *
     *   `gap` (default) arms only interfaces that were never armed at all. It
     *         cannot disturb a working panel because it only touches panels that
     *         had no event entries whatsoever.
     *   `all` additionally tops up interfaces armed with options only. This is
     *         the more likely fix for panels like Skills (1466, armed mask 30 =
     *         ops 1-4), but it re-sends over interfaces that already have
     *         hand-transcribed per-component masks, so it is opt-in.
     *
     * Neither mode makes a panel DO anything the server does not answer; it makes
     * the client transmit. If the X button still does not close after this, the
     * click will at least arrive and be logged, which is the next piece of
     * evidence either way.
     *
     * DEFAULT OFF: -Dopennxt.experiment.ui.armPanels=gap (or =all)
     */
    fun armPanelInteractivity(mode: String) {
        val table = try {
            OpenNXT.filesystem.getReferenceTable(3)
        } catch (t: Throwable) {
            logger.warn("armPanels: cannot read index 3 (${t.message}); nothing armed")
            return
        } ?: return

        val topUp = mode == "all"
        var interfaces = 0
        var components = 0
        val touched = ArrayList<Int>()

        var notReallyOpen = 0
        for (id in openedIds.toIntArray().sortedArray()) {
            // openedIds records that open() was CALLED; isOpened() asks the tree
            // whether it landed. Those disagreed for every sub-interface until
            // the tree insertion in [open] was un-commented, and this armed 8
            // panels that were not on screen. Ask the tree, not the intent.
            if (!isOpened(id)) { notReallyOpen++; continue }

            val neverArmed = !armedIds.contains(id)
            val optionsOnly = armedIds.contains(id) && !dragArmedIds.contains(id)
            if (!neverArmed && !(topUp && optionsOnly)) continue

            // Components are the files of the interface's own archive, so the set
            // is the cache's rather than a guessed range.
            val archive = try { table.loadArchive(id) } catch (t: Throwable) { null } ?: continue
            val comps = archive.files.keys
            if (comps.isEmpty()) continue

            // A never-armed panel gets the options too, since nothing else will
            // give them to it. A top-up already has its options from the replay,
            // so it gets only the interaction bits and its existing per-component
            // entries are left to stand.
            val mask = if (neverArmed) PANEL_INTERACT_MASK or 2046 else PANEL_INTERACT_MASK

            interfaces++
            touched.add(id)
            for (c in comps) {
                events(id = id, component = c, from = 65535, to = 65535, mask = mask)
                components++
            }
        }

        logger.info(
            "armPanels[$mode]: armed $components component(s) across $interfaces panel(s) " +
                "with mask bits 18/19/21/23 - drag, resize and close. Panels touched: $touched. " +
                "Skipped $notReallyOpen id(s) that open() was called for but the tree does not " +
                "hold - that count should be 0; anything else means open() and the tree have " +
                "desynchronised again. Panels left alone: ${openedIds.size - interfaces - notReallyOpen} " +
                if (topUp) "(already interaction-armed)." else "(already interaction-armed, or options-only - use =all)."
        )
    }

    /**
     * Opens a game interface. The positions of these interfaces are stored in the cache.
     */
    fun open(slot: InterfaceSlot, id: Int, offset: Int = 0, walkable: Boolean = false) {
        this.open(id, slot.parent, slot.component + offset, walkable)
    }

    /**
     * Closes a game interface. The position of these interfaces are stored in the cache
     */
    fun close(slot: InterfaceSlot, offset: Int = 0) {
        this.close(slot.parent, slot.component + offset)
    }

    /**
     * Applies a clickmask to a range of slots of a component.
     */
    fun events(id: Int, component: Int, from: Int, to: Int, mask: Int) {
        // ------------------------------------------------------------------
        // THE OTHER HALF OF THE PANEL REMAP.
        //
        // This used to be a log line and nothing else, and that is the recorded
        // regression: [open] substituted the interface id for all twelve
        // shifted panels and events() did not, so every panel came up on the
        // 949 interface and was then armed on the 919 one. For 1881 and 1888
        // that interface has ZERO components in this cache; for the other ten
        // it is simply not the interface on screen. The client keys its event
        // map on the interface id alone, so
        // the entries are inert rather than harmful - which is exactly why the
        // regression read as plausible and survived a build.
        //
        // Resolution order, both paths asserted by
 // :
        //   1. what [open] ACTUALLY did for this player ([observedPanelRemap]),
        //   2. failing that the static [eventInterfaceFor], derived from
        //      [panelReplay919] + [panelRemapTable] and never hand-written.
        // ------------------------------------------------------------------
        var effectiveId = id
        var effectiveComponent = component
        if (panelRemapEnabled) {
            val observed = observedPanelRemap[id]
            val corrected = if (observed != null) {
                if (observed == DROPPED) null else observed
            } else {
                eventInterfaceFor(id)
            }
            if (corrected == null) {
                logger.info("Panel remap: dropping IF_SETEVENTS for 919 interface $id " +
                    "(component $component) - 949 opens nothing for that panel.")
                return
            }
            if (corrected != id) {
                effectiveId = corrected
                effectiveComponent = barComponentFor(id, corrected, component)
                logger.info("Panel remap: IF_SETEVENTS named 919 interface $id:$component; " +
                    "949 has $effectiveId:$effectiveComponent there - substituting.")
            }
        }

        if (!interfaceExists(effectiveId)) {
            logger.warn("Interface guard: IF_SETEVENTS for undefined interface $effectiveId skipped " +
                "(component $effectiveComponent) - the panel it configures was never opened.")
            return
        }
        // Now a real defect rather than expected noise: after the substitution
        // above, an IF_SETEVENTS for an interface this player has not opened
        // means open() and events() disagree, which is the regression itself.
        if (!isOpened(effectiveId)) {
            logger.warn("IF_SETEVENTS for interface $effectiveId (component $effectiveComponent) " +
                "which is NOT open on this player - the entry will sit unused. If $effectiveId is a " +
                "gameframe panel this is the open()/events() remap desynchronising again.")
        }
        armedIds.add(effectiveId)
        if (mask and PANEL_INTERACT_BITS != 0) dragArmedIds.add(effectiveId)
        // Record every gameframe component this player has armed, so later re-arming
        // passes (PanelCloseWiring.armFrameOps) can SAY what they overwrite instead of
        // silently replacing masks - IF_SETEVENTS is replace-per-range, not merge
 // (review finding 2; the login sequence arms ~170 distinct 1477
        // components, measured by transcription of this file).
        if (effectiveId == 1477) armed1477Components.add(effectiveComponent)
 // THE MASK IS REMEMBERED. The client's OP NUMBERING is a function of it, so a
        // handler that needs to know which item menu row an inbound op refers to must read the mask
        // THIS player was actually sent - not a constant, which would drift the moment the arming
        // changed. See [armedMaskFor].
        armedMasks[(effectiveId.toLong() shl 32) or effectiveComponent.toLong()] = mask
        player.client.write(IfSetevents(InterfaceHash(effectiveId, effectiveComponent), from, to, mask))
    }

    /**
     * Sets the text on an interface
     */
    fun text(id: Int, component: Int, text: String) {
        player.client.write(IfSettext(InterfaceHash(id, component), text))
    }

    /**
     * (Un)hides an interface by the interface id + component of the target interface
     */
    fun hide(id: Int, component: Int, hidden: Boolean) {
        player.client.write(IfSethide(InterfaceHash(id, component), hidden))
    }

    /**
     * Sets a component's MODEL SOURCE - the build-949 attribute-4 family.
     *
     * [kind] is the model-source constant the client writes into component
     * field 0x150 and is what picks the opcode. Kinds 3 and 5 carry no id on
     * the wire; the client uses the local player. Evidence, including why the
     * names are structural rather than Jagex's, is in
     * `data/prot/949/serverProt/_ATTR4_COMMON.md`.
     */
    fun model(id: Int, component: Int, kind: Int, value: Int) {
        val hash = InterfaceHash(id, component)
        when (kind) {
            1 -> player.client.write(IfModelK1(hash, value))
            2 -> player.client.write(IfModelK2(hash, value))
            3 -> player.client.write(IfModelK3Self(hash))
            5 -> player.client.write(IfModelK5Self(hash))
            else -> throw IllegalArgumentException("no attribute-4 opcode for model kind $kind")
        }
    }

    /**
     * The CLIENT already closed its interfaces (ClientProt 67 - the
     * notification CS2 command 784 sends after walking the client's own
     * interface store and closing every qualifying entry; see
     * GlobalCloseWiring's 1477:8 KDoc for the capstone evidence). This brings
     * the SERVER's model back in line WITHOUT sending IF_CLOSESUB - the
     * windows are already gone client-side, and echoing closes for them would
     * be wire noise at best.
     *
     * Same walk and same predicate as [closeModals] (non-walkable children);
     */
    /** Gameframe components this player has received IF_SETEVENTS for - see [events]. */
    val armed1477Components: MutableSet<Int> = HashSet()

    /**
     * The events mask this player was SENT, keyed by (interface shl 32) or component.
     *
     * IF_SETEVENTS is replace-per-range, so the last mask armed for a component is the one in force.
     */
    private val armedMasks = HashMap<Long, Int>()

    /**
     * The mask last armed on (interface, component) for this player, or null if never armed.
     */
    fun armedMaskFor(id: Int, component: Int): Int? =
        armedMasks[(id.toLong() shl 32) or component.toLong()]

    fun markModalsClosedByClient(): List<String> {
        val root = root ?: return emptyList()
        val dropped = mutableListOf<String>()

        fun iterateOver(iface: OpenedInterface) {
            val toClose = it.unimi.dsi.fastutil.ints.IntArrayList()
            iface.children.forEach { (id, child) ->
                if (!child.walkable) {
                    toClose.add(id)
                } else {
                    iterateOver(child)
                }
            }
            for (i in 0 until toClose.size) {
                val slot = toClose.getInt(i)
                val removed = iface.children.remove(slot)
                dropped.add("${removed?.id} at ${iface.id}:$slot")
            }
        }

        iterateOver(root)
        return dropped
    }

    /**
     * Closes every modal
     *
     * TODO: Are all walkable interfaces modals? I do think so, but I am not sure.
     */
 // NOTE: this walk feeds ALREADY-STORED slots back
    // through close() -> mountFor - a slot that coincides with a remap key would be
    // re-translated. Harmless today (stored slots are mountFor OUTPUTS and the gate's
    // section 4 pins the overlaps), but if a variant is ever added, route it through
    // close(native949 = true) like markModalsClosedByClient conceptually does.
    fun closeModals(): Boolean {
        val root = root ?: return false
        var closedAny = false

        fun iterateOver(iface: OpenedInterface) {
            val toClose = it.unimi.dsi.fastutil.ints.IntArrayList()
            iface.children.forEach { (id, child) ->
                if (!child.walkable) {
                    toClose.add(id)
                } else {
                    iterateOver(child)
                }
            }
            for (i in 0 until toClose.size) {
                close(iface.id, toClose.getInt(i))
                closedAny = true
            }
        }

        iterateOver(root)
        return closedAny
    }

    /**
     * Represents an interface that the client has opened. This can be used to validate button presses.
     */
    private data class OpenedInterface(
        val id: Int,
        val children: Int2ObjectOpenHashMap<OpenedInterface> = Int2ObjectOpenHashMap(),
        val walkable: Boolean
    ) {
        /**
         * Finds a child interface of this interface
         */
        fun findChild(id: Int): OpenedInterface? {
            if (this.id == id) return this
            for (child in children) {
                return child.value.findChild(id) ?: continue
            }
            return null
        }
    }

}




