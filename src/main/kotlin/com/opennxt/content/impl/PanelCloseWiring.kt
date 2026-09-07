package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

/**
 * The window close button evening.
 */
object PanelCloseWiring {
    private val logger = KotlinLogging.logger { }

    /** panel id -> 1477 component the panel's window docks on (enum 7716 x struct param 3505). */
    val PANEL_DOCK: Map<Int, Int> = mapOf(
        0 to 300, // Skills
        2 to 103, // Backpack
        3 to 114, // Worn Equipment
        4 to 136, // Prayers
        5 to 169, // Magic
        6 to 147, // Melee Abilities
        7 to 158, // Ranged Abilities
        8 to 257, // Defensive
        9 to 409, // Emotes
        10 to 311, // Music Player
        11 to 545, // Notes
        12 to 125, // Familiar
        14 to 501, // Friends List
        15 to 556, // Friends Chat List
        16 to 512, // Clan Chat List
        17 to 290, // Minigames
        18 to 420, // All Chat
        19 to 431, // Private Chat
        20 to 441, // Friends Chat
        21 to 451, // Clan Chat
        22 to 461, // Guest Clan Chat
        23 to 471, // Requests
        24 to 333, // Twitch Chat
        25 to 481, // Group Chat
        26 to 322, // Twitch Stream
        27 to 523, // Group Chat List
        28 to 343, // Metrics
        29 to 354, // Drops
        30 to 365, // Graphs
        31 to 376, // Quests
        32 to 387, // Activity Tracker
        33 to 180, // Magic Abilities
        34 to 191, // Magic Spells (Combat)
        35 to 202, // Magic Spells (Teleport)
        36 to 213, // Magic Spells (Skilling)
        39 to 268, // Defence Abilities
        40 to 279, // Constitution Abilities
        41 to 398, // Achievement Paths
        42 to 224, // Necromancy
        43 to 235, // Necromancy Abilities
        44 to 246, // Necromancy Incantations
        45 to 534, // Group Ironman List
        46 to 491, // Group Ironman Chat
        1000 to 30, // Game View
        1001 to 715, // Management Windows
        1002 to 64, // Ribbon
        1003 to 70, // Main Action Bar
        1004 to 94, // Minimap
        1005 to 640, // HUD Overlays
        1006 to 750, // Dialogue Box
        1007 to 735, // Central Interface
        1008 to 740, // ?
        1009 to 617, // Buff Bar
        1010 to 621, // Death Status
        1012 to 627, // Task Complete
        1013 to 609, // Gravestone Items
        1014 to 634, // Area Status
        1015 to 600, // XP Tracker
        1016 to 822, // Subscribe
        1017 to 695, // Bank
        1018 to 630, // Crafting Progress
        1019 to 648, // Split Private Chat
        1021 to 656, // Boss Kill Timer
        1023 to 720, // Group Invitations
        1024 to 700, // Player Inspect
        1025 to 664, // Clock
        1026 to 668, // XP Popups
        1027 to 672, // JMod Toolbox
        1028 to 705, // Loot
        1029 to 604, // Debug text
        1030 to 680, // Challenge Gem
        1031 to 676, // Slayer Counter
        1032 to 75, // Additional Action Bar 1
        1033 to 80, // Additional Action Bar 2
        1034 to 85, // Additional Action Bar 3
        1035 to 90, // Additional Action Bar 4
        1036 to 644, // BXP countdown
        1037 to 592, // Events
        1038 to 613, // Debuff Bar
        1040 to 730, // Central Interface (Overlay)
        1041 to 588, // Dungeoneering Map
        1045 to 584, // Extra Action Button
        1047 to 726, // Central Interface (Large)
        1049 to 576, // Combat Mode Icon
        1050 to 652, // Boss Instance Timer
        1051 to 572, // Boss Health & Activity Progress Bar
        1052 to 684, // Info Box
        1053 to 568, // Channel Bar
        2008 to 596, // Combat Target
    )

    /** panel id -> 1477 SLOT component (enum 7716 x struct param 3503) - the component whose
     * dynamic children are the window frame 8411 builds (title bar, tabs, the X). */
    val PANEL_SLOT: Map<Int, Int> = mapOf(
        0 to 298, // Skills
        2 to 101, // Backpack
        3 to 112, // Worn Equipment
        4 to 134, // Prayers
        5 to 167, // Magic
        6 to 145, // Melee Abilities
        7 to 156, // Ranged Abilities
        8 to 255, // Defensive
        9 to 407, // Emotes
        10 to 309, // Music Player
        11 to 543, // Notes
        12 to 123, // Familiar
        14 to 499, // Friends List
        15 to 554, // Friends Chat List
        16 to 510, // Clan Chat List
        17 to 288, // Minigames
        18 to 418, // All Chat
        19 to 429, // Private Chat
        20 to 439, // Friends Chat
        21 to 449, // Clan Chat
        22 to 459, // Guest Clan Chat
        23 to 469, // Requests
        24 to 331, // Twitch Chat
        25 to 479, // Group Chat
        26 to 320, // Twitch Stream
        27 to 521, // Group Chat List
        28 to 341, // Metrics
        29 to 352, // Drops
        30 to 363, // Graphs
        31 to 374, // Quests
        32 to 385, // Activity Tracker
        33 to 178, // Magic Abilities
        34 to 189, // Magic Spells (Combat)
        35 to 200, // Magic Spells (Teleport)
        36 to 211, // Magic Spells (Skilling)
        39 to 266, // Defence Abilities
        40 to 277, // Constitution Abilities
        41 to 396, // Achievement Paths
        42 to 222, // Necromancy
        43 to 233, // Necromancy Abilities
        44 to 244, // Necromancy Incantations
        45 to 532, // Group Ironman List
        46 to 489, // Group Ironman Chat
        1000 to 28, // Game View
        1001 to 708, // Management Windows
        1002 to 61, // Ribbon
        1003 to 67, // Main Action Bar
        1004 to 92, // Minimap
        1005 to 638, // HUD Overlays
        1006 to 747, // Dialogue Box
        1007 to 732, // Central Interface
        1008 to 737, // ?
        1009 to 615, // Buff Bar
        1010 to 619, // Death Status
        1012 to 624, // Task Complete
        1013 to 607, // Gravestone Items
        1014 to 632, // Area Status
        1015 to 598, // XP Tracker
        1016 to 820, // Subscribe
        1017 to 693, // Bank
        1018 to 623, // Crafting Progress
        1019 to 646, // Split Private Chat
        1021 to 654, // Boss Kill Timer
        1023 to 718, // Group Invitations
        1024 to 698, // Player Inspect
        1025 to 662, // Clock
        1026 to 666, // XP Popups
        1027 to 670, // JMod Toolbox
        1028 to 703, // Loot
        1029 to 602, // Debug text
        1030 to 678, // Challenge Gem
        1031 to 674, // Slayer Counter
        1032 to 72, // Additional Action Bar 1
        1033 to 77, // Additional Action Bar 2
        1034 to 82, // Additional Action Bar 3
        1035 to 87, // Additional Action Bar 4
        1036 to 642, // BXP countdown
        1037 to 590, // Events
        1038 to 611, // Debuff Bar
        1040 to 728, // Central Interface (Overlay)
        1041 to 586, // Dungeoneering Map
        1045 to 582, // Extra Action Button
        1047 to 724, // Central Interface (Large)
        1049 to 574, // Combat Mode Icon
        1050 to 650, // Boss Instance Timer
        1051 to 570, // Boss Health & Activity Progress Bar
        1052 to 682, // Info Box
        1053 to 566, // Channel Bar
        2008 to 594, // Combat Target
    )

    val PANEL_FRAME: Map<Int, Int> = mapOf(
        0 to 304, // Skills
        2 to 107, // Backpack
        3 to 118, // Worn Equipment
        4 to 140, // Prayers
        5 to 173, // Magic
        6 to 151, // Melee Abilities
        7 to 162, // Ranged Abilities
        8 to 261, // Defensive
        9 to 413, // Emotes
        10 to 315, // Music Player
        11 to 549, // Notes
        12 to 129, // Familiar
        14 to 505, // Friends List
        15 to 560, // Friends Chat List
        16 to 516, // Clan Chat List
        17 to 294, // Minigames
        18 to 424, // All Chat
        19 to 434, // Private Chat
        20 to 444, // Friends Chat
        21 to 454, // Clan Chat
        22 to 464, // Guest Clan Chat
        23 to 474, // Requests
        25 to 484, // Group Chat
        26 to 326, // Twitch Stream
        27 to 527, // Group Chat List
        28 to 351, // Metrics
        29 to 362, // Drops
        30 to 373, // Graphs
        31 to 384, // Quests
        32 to 395, // Activity Tracker
        33 to 184, // Magic Abilities
        34 to 195, // Magic Spells (Combat)
        35 to 206, // Magic Spells (Teleport)
        36 to 217, // Magic Spells (Skilling)
        39 to 272, // Defence Abilities
        40 to 283, // Constitution Abilities
        41 to 406, // Achievement Paths
        42 to 228, // Necromancy
        43 to 239, // Necromancy Abilities
        44 to 250, // Necromancy Incantations
        45 to 538, // Group Ironman List
        46 to 494, // Group Ironman Chat
        1001 to 717, // Management Windows
        1002 to 65, // Ribbon
        1003 to 71, // Main Action Bar
        1004 to 99, // Minimap
        1008 to 742, // ?
        1032 to 76, // Additional Action Bar 1
        1033 to 81, // Additional Action Bar 2
        1034 to 86, // Additional Action Bar 3
        1035 to 91, // Additional Action Bar 4
    )

    /**
     * The ui.fillPanels set - name-matched 949-native content for panels the replay never
     * fills (evidence per pair in WorldPlayer's fillPanels KDoc; names from
     * dock ids stay DISJOINT from the 919->949 remap keys
     * and WorldPlayer just iterates. (dock, interface, label).
     */
    val FILL_PANELS: List<Triple<Int, Int, String>> = listOf(
        Triple(290, 1344, "Minigames <- minigames_main"),
        Triple(365, 1732, "Graphs <- graph"),
        Triple(664, 1234, "Clock <- clock_wrapper"),
        Triple(652, 1591, "Boss Instance Timer <- boss_instance"),
        Triple(705, 1622, "Loot <- toplevel_v2_loot"),
        Triple(684, 1177, "Info Box <- info_box"),
        Triple(125, 722, "Familiar <- summoning_side"),
        Triple(644, 1731, "BXP countdown <- bxp_countdown"),
 // split_pm (648 <- 182) removed: broke chat window placement (run 105307).
        Triple(534, 1299, "Group Ironman List <- group_ironman_child")
    )

    fun armFrameOps(world: WorldPlayer): Int {
        // Mask 2 = op-1 only. The first arming used 15 and its bit 0 put a stray
        // "Continue" row in every frame menu - bit 0 is the dialogue-continue flag
        // (MENU_DEFAULT_OP_949.md section 4: op N is mask bit N; the continue row is bit 0),
 // measured live: the X's right-click menu read
        // "Walk here / Continue / Close Window".
        var n = 0
        for ((_, slot) in PANEL_SLOT) {
            world.interfaces.events(id = 1477, component = slot, from = 0, to = 127, mask = 2)
            n++
        }
        // The component that actually parents the frame buttons - see PANEL_FRAME's KDoc.
        for ((_, frame) in PANEL_FRAME) {
            world.interfaces.events(id = 1477, component = frame, from = 0, to = 127, mask = 2)
            n++
        }
 // IF_SETEVENTS is REPLACE-per-range, not merge. This pass
        // deliberately overwrites child masks 0..127 on every slot/frame - that replace IS
        // the measured fix for the dead X (the replay's own masks on the frames lacked op 1) -
        // but it must never be silent: name exactly which login-armed components got their
        // masks replaced, so a future "why did mask M vanish from 1477:C" has its answer here.
        val overwritten = (PANEL_SLOT.values + PANEL_FRAME.values)
            .filter { it in world.interfaces.armed1477Components }.sorted()
        logger.warn { "armFrames: sent IF_SETEVENTS(1477, slot/frame, 0..127, mask 2) for $n components - " +
            "frame ops (Close Window) act on the op path. REPLACED the login sequence's earlier masks on " +
            "${overwritten.size} of them: $overwritten" }
        return n
    }

    fun handlePanelClose(world: WorldPlayer, panelId: Int): Boolean {
 // REFUTED, run 152843, and DEFAULT OFF since: opcode 89 is NOT a close
        // request. At login, before any click, the client sent a burst of 89 frames stepping
        // 2, 102, 202 ... 1402, 1447, 1449, 1450 - a load/progress sequence - and this
        // handler answered "panel 2" by CLOSING THE BACKPACK during login. The int=2 seen
 // beside a backpack X-click on was co-occurrence read as mechanism -
        // the same trap the ClientProt-67 note in GlobalCloseWiring documents.
        // `-Dopennxt.experiment.ui.panelClose=true` re-enables for experiments only.
        if (System.getProperty("opennxt.experiment.ui.panelClose") != "true") return false
        val dock = PANEL_DOCK[panelId]
        if (dock == null) {
            logger.info { "panelClose: ClientProt 89 named panel $panelId, which has no 1477 dock in enum 7716 - ignored" }
            return false
        }
        logger.info { "panelClose: ClientProt 89 = close request for panel $panelId -> IF_CLOSESUB 1477:$dock" }
        world.interfaces.close(1477, dock, native949 = true)
        return true
    }
}
