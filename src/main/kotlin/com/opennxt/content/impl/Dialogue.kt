package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.NpcContext
import com.opennxt.content.interfaces.InterfaceSlot
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * Talking to an NPC: the first dialogue this server has ever put on a screen.
 *
 * `Talk to` is the most common NPC action in this cache - **8,797 npc ids
 * declare it** (8,772 in the plain `actions_0..2` columns plus 25 in an
 * `npcs_attr` `actions_3` row that [com.opennxt.resources.sqlite.SqliteNpcCodec]
 * merges), against `Attack`'s 7,914 - and until this file existed a click
 * on it walked the player over and stopped. This module opens a real chat box,
 * writes real text into it, lets the player click through it and pick an
 * option, and takes it away again.
 *
 * Everything below is split into what the CACHE says and what WE made up, and
 * the split is not decorative: the interfaces, the mount, the components and
 * the option rows are all read out of the 949 cache and the client's own
 * scripts; **every word of speech is invented**, and the measurement behind
 * that verdict is in [DIALOGUE_TEXT_IS_NOT_IN_THE_CACHE].
 */
object Dialogue {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON - see the class doc for why. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.dialogue") != "false"

    /** IF_SETHIDE writes only. Default ON - see the class doc for why. */
    val sethideEnabled: Boolean = System.getProperty("opennxt.experiment.dialogue.sethide") != "false"

    /**
     * The chathead experiment. Default ON.
     *
     * ============ WHAT THE FIRST TEST RUN ALREADY SETTLED =================
     * Run `server-20260818-105622`, four conversations with Fred the Farmer
     * (npc 758). The server sent `IF_MODEL_K1` (opcode 101, kind 1) with
     * **value = 758** and, **41 ms later**, the client opened a JS5 connection
     * and asked for **index 47 archive 758**:
     */
    val headExperiment: String =
        (System.getProperty("opennxt.experiment.dialogue.head") ?: "speaker").lowercase()

    /** Which cycle slot the next conversation gets. See [headExperiment]. */
    private val headCycle = java.util.concurrent.atomic.AtomicInteger(0)

    /** The cycle slots, in the order a player meets them. */
    private val HEAD_CANDIDATES = intArrayOf(1, 2, 3, 5)

    /** The action string this module binds. Exact and case-sensitive, like every other binding. */
    const val TALK_TO = "Talk to"

    // ---- what the cache says -------------------------------------------

    /** The world gameframe. */
    const val GAMEFRAME = 1477

    /** Panel id whose struct param 3493 is the string "Dialogue Box". */
    const val DIALOGUE_PANEL = 1006

    /** The struct enum 7716 maps [DIALOGUE_PANEL] onto. */
    const val DIALOGUE_STRUCT = 21303

    /** The param clientscript 8071 reads to find the mount. */
    const val MOUNT_PARAM = 3505

    /** `struct_param(21303, 3505)` = (1477 shl 16) or 750. The DEFAULT mount. */
    const val MOUNT_COMPONENT = 750

    /**
     * Every component pointer struct 21303 ("Dialogue Box") carries, param ->
     * component of [GAMEFRAME].
     */
    val MOUNT_PARAM_COMPONENTS: Map<Int, Int> = linkedMapOf(
        6121 to 746,
        3503 to 747,
        3504 to 748,
        3505 to 750,
        3506 to 751,
    )

    /**
     * Which component of [GAMEFRAME] the dialogue interfaces are mounted at.
     *
     * `-Dopennxt.experiment.dialogue.mountParam=<n>` takes EITHER a param id
     * from [MOUNT_PARAM_COMPONENTS] (6121|3503|3504|3505|3506) OR a raw
     * component number, so an operator who wants to try 749 - the one component
     * of that group the struct skips - can say `=749` and get it. Anything
     * unparseable falls back to [MOUNT_COMPONENT], and the resolved value is
     * printed at boot either way.
     *
     * Default is UNCHANGED: 3505 -> 750.
     */
    fun mountComponent(): Int {
        val raw = System.getProperty("opennxt.experiment.dialogue.mountParam")?.trim()
            ?: return MOUNT_COMPONENT
        val n = raw.toIntOrNull() ?: return MOUNT_COMPONENT
        MOUNT_PARAM_COMPONENTS[n]?.let { return it }
        return if (n in 0..0xffff) n else MOUNT_COMPONENT
    }

    /** The param id [mountComponent] resolved through, or -1 when it was handed a raw component. */
    fun mountParamResolved(): Int =
        MOUNT_PARAM_COMPONENTS.entries.firstOrNull { it.value == mountComponent() }?.key ?: -1

    /**
     * The mount [render] and [close] will ACTUALLY use, resolved exactly the
     * way they resolve it - not the way [mountComponent] alone resolves it.
     */
    fun effectiveMount(): Pair<Int, Int> {
        val parent = if (InterfaceSlot.GAME_DIALOG.parent != -1) InterfaceSlot.GAME_DIALOG.parent else GAMEFRAME
        // An EXPLICIT operator override beats the resolved slot. Without this
        // the -Dopennxt.experiment.dialogue.mountParam flag was dead code:
        // InterfaceSlot.GAME_DIALOG resolves to 1477:747 (param 3503) and won
        // every time, so the run that would settle the dispute in
        // InterfaceSlot.mountParamFor - 'a live session that opens 1184 at
        // 1477:750 and one that opens it at 1477:747, and a look at which one
        // draws' - could not actually be performed.
        val override = System.getProperty("opennxt.experiment.dialogue.mountParam") != null
        val comp = if (!override && InterfaceSlot.GAME_DIALOG.component != -1)
            InterfaceSlot.GAME_DIALOG.component else mountComponent()
        return parent to comp
    }

    /**
     * The struct param that points at [effectiveMount]'s component, or -1 when
     * nothing in [MOUNT_PARAM_COMPONENTS] does.
     *
     * Prefers the param `InterfaceSlot` itself resolved through when the slot
     * is live, because that is the read the server actually performed; the
     * reverse lookup is the fallback for the un-booted case and for a raw
     * component override.
     */
    fun effectiveMountParam(): Int {
        val (_, comp) = effectiveMount()
        if (System.getProperty("opennxt.experiment.dialogue.mountParam") == null &&
            InterfaceSlot.GAME_DIALOG.component == comp && InterfaceSlot.GAME_DIALOG.mountParam != -1
        ) return InterfaceSlot.GAME_DIALOG.mountParam
        return MOUNT_PARAM_COMPONENTS.entries.firstOrNull { it.value == comp }?.key ?: -1
    }

    /** "NPC says" - chathead on the left. */
    const val NPC_CHAT = 1184

    /** "Player says" - 1184 mirrored. */
    const val PLAYER_CHAT = 1191

    /** The five-option choice box. */
    const val OPTIONS = 1188

    /** Every interface clientscript 7800 expects at [MOUNT_COMPONENT], in its own order. */
    val MOUNT_FAMILY: IntArray = intArrayOf(1188, 1193, 1184, 1186, 835, 1189, 1191, 1187, 1192)

    /** The two clientscript 7800 expects at the OTHER dialogue slot, 1477:746 (param 6121). */
    val ALT_MOUNT_FAMILY: IntArray = intArrayOf(387, 327)

    /** The head-less message boxes, 1..5 lines. Recorded, not used. */
    val MESSAGE_BOXES: IntArray = intArrayOf(210, 211, 212, 213, 214)

    /** Component 0 of any of these interfaces: the interface's own root layer. */
    const val ROOT_COMPONENT = 0

    /**
     * 1184:11 / 1191:11 - the component REFERENCE names in its per-page IF_SETHIDE.
     */
    const val CHAT_HIDE_COMPONENT = 11

    /** 1184:4 / 1191:4 - default text "Name". The speaker's name. */
    const val CHAT_NAME_COMPONENT = 4

    /** 1184:10 / 1191:10 - 156x60, font 26, default text "". The words. */
    const val CHAT_TEXT_COMPONENT = 10

    /**
     * 1184:8 / 1191:8 - the chathead slot, and the ONLY model component either
     * box has.
     *
     * Measured, not remembered: across the whole decoded interface dump
     * (`tools/data/interfaces_full.jsonl`, 104,601 component records) interface
     * 1184 contains exactly ONE component of type 6 (model) and it is component
     * 8, with `modelid` -1 and `mode` 1. 1191 - the mirrored player-side box -
     * contains exactly one too, also component 8, also `modelid` -1. A model
     * component whose configured model id is -1 is a slot the server is
     * expected to fill, and it is the empty circle beside the words.
     */
    const val CHAT_HEAD_COMPONENT = 8

    /**
     * 1184:15 / 1191:15 - the only optmask-carrying component on either, and the
     * one component of the box that CANNOT deliver a click to this server.
     *
     * It is still addressed on every page, but with [NO_EVENTS_MASK], and the
     * reason is the whole of DEFECT 1. See [CHAT_CONTINUE_COMPONENTS] and
     * `data/prot/949/clientProt/UNMAPPED_102.txt`.
     */
    const val CHAT_CONTINUE_BLOCKER = 15

    /**
     * The components a `Say` page actually arms for op 1.
     *
     * 5 is the 624x134 box body; 11 is the 80x20 continue-arrow group and
     * 12/13/14 are its three children (the arrow's own sprite layers, each
     * running clientscript 11645 with a different frame index). All five carry
     * an EMPTY menu string in index 3, which is the property that decides
     * whether a click becomes IF_BUTTON1 or the unmapped ClientProt 102.
     */
    val CHAT_CONTINUE_COMPONENTS: IntArray = intArrayOf(15)

    /**
     * The components a `Say` page arms, default [CHAT_CONTINUE_COMPONENTS].
     *
     * `-Dopennxt.experiment.dialogue.continueComponents=a,b,c`; an empty value
     * arms nothing, which is the only way to test "1184:15 alone" without the
     * other five in the walk (set this empty and `blockerMask=2`).
     */
    fun continueComponents(): IntArray {
        val raw = System.getProperty("opennxt.experiment.dialogue.continueComponents")
            ?: return CHAT_CONTINUE_COMPONENTS
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..0xffff }.toIntArray()
    }

    /**
     * The mask sent to [continueComponents]. Default [OP1_MASK] = 2 = op 1.
     */
    fun armMask(): Int =
        System.getProperty("opennxt.experiment.dialogue.armMask")
            ?.trim()?.toIntOrNull()?.takeIf { it in 0..2047 } ?: CONTINUE_MASK

    /**
     * 1188's five clickable rows, in clientscript 5589's order.
     *
     * DEFAULT UNCHANGED: 8/13/18/23/28, which are the five VISIBLE bars -
     * 100x18 at y = 2, 21, 40, 59, 78, on a period of 19. That geometry is not
     * in doubt and is why these were chosen.
     *
     * BUT THE CLIENT DISAGREES ABOUT WHERE THE OPS LIVE. Run
     * server-20260826-110119: with the Choose page open and 8/13/18/23/28
     * armed, a click on an option row arrived as
     * `IF_BUTTON1 interface=1188 component=4` - component **4**, which is
     * 0x0 with aspectwidthtype 1, i.e. a container that takes its size at
     * runtime. 4/9/14/19/24 sit on the same period of 5 as the armed set and
     * are the containers wrapping each visible bar. [onButton] refused the
     * click because 4 was not armed, so the conversation did not branch.
     *
     * The reading that fits both facts is that the ART is on the bar and the
     * OPS are on the container. That is one observation of one row, so it is a
     * switch and not a new constant:
     *
     *     -Dopennxt.experiment.dialogue.optionRows=4,9,14,19,24
     *
     * If a run with that set branches the conversation - and names which
     * option was chosen - the constant should change and this comment becomes
     * the evidence for it. Exactly five entries are required; anything else
     * falls back to the default rather than silently arming a short list.
     */
    val OPTION_ROWS: IntArray =
        System.getProperty("opennxt.experiment.dialogue.optionRows")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 5 }?.toIntArray()
            ?: intArrayOf(8, 13, 18, 23, 28)

    /**
     * The container that wraps each row in [OPTION_ROWS], armed alongside it.
     *
     * WHY BOTH. Two runs settled this by flipping the armed set and watching
     * which component the client named:
     *
     *   armed 8,13,18,23,28  -> click on option 1 reported `1188:4`
     *   armed 4,9,14,19,24   -> click on option 1 reported `1188:8`
     *
     * It reports the one we did NOT arm, in both directions, which rules out an
     * off-by-one in our IF_SETEVENTS and leaves one reading: BOTH components are
     * live - they carry ops from the cache regardless of what we send - and the
     * hit walk returns whichever is under the cursor. 8/13/18/23/28 are the
     * visible 100x18 bars; 4/9/14/19/24 are their 0x0 aspect-1 containers, on
     * the same period of 5. Picking a winner is not possible and not necessary:
     * arm both, and resolve either to the same option index in [onButton].
     *
     * Override with `-Dopennxt.experiment.dialogue.optionRowAliases=a,b,c,d,e`.
     * Exactly five entries, positionally paired with [OPTION_ROWS].
     */
    val OPTION_ROW_ALIASES: IntArray =
        System.getProperty("opennxt.experiment.dialogue.optionRowAliases")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 5 }?.toIntArray()
            ?: intArrayOf(9, 14, 19, 24, 29)

    /*
     *RUN6c (REFERENCE HUD): the aliases are ROW+1, not ROW-4.
     * Under the replayed reference login the operator clicked option 1 ("I'm looking for a quest.")
     */

    /** 1188's five option text slots, positionally paired with [OPTION_ROWS] by script 5589. */
    val OPTION_TEXTS: IntArray = intArrayOf(6, 33, 35, 37, 39)

    // ================================================================================
 // WHAT THE REFERENCE CLIENT PUTS ON THE WIRE - and it is not what this file used to
    // ================================================================================
    //
 // reference session of 13:23 UTC. The Turael conversation runs from tick
    // 6 (OPNPC1) to tick 115, and the player-head page is at tick 627. Every byte
    // `data/prot/949/serverProt/` (IF_SETHIDE, IF_SETANIM, IF_SETTEXT,
    // IF_SETNPCHEAD, IF_SETPLAYERHEAD, IF_OPENSUB) plus the RUNCLIENTSCRIPT layout
    // this pass wrote down for the first time (`RUNCLIENTSCRIPT.txt`).
    //
    // ONE npc page = SEVEN packets, in exactly this order, nine times out of nine:
    //
    //     IF_SETHIDE        1184:11  hidden=false
    //     IF_SETANIM        1184:8   the head animation for THIS line
    //     IF_SETTEXT        1184:4   the speaker's name
    //     IF_SETTEXT        1184:10  "<p=N>" + the line
    //     IF_SETNPCHEAD     1184:8   the npc id
    //     IF_OPENSUB        1184  -> 1477:750, flag 0
    //     RUNCLIENTSCRIPT   8178    no arguments
    //
    // A player page is the same on 1191 with IF_SETPLAYERHEAD (opcode 24, which
    // carries NO id - the client uses the local player) and anim 37905.
    //
    // An option page is THREE packets and no IF_SETTEXT at all:
    //
    //     IF_OPENSUB        1188  -> 1477:750, flag 0
    //     RUNCLIENTSCRIPT   8178    no arguments
    //     RUNCLIENTSCRIPT   5589    "sisssss": title, count, five option strings
    //
    // A close is ONE packet: IF_CLOSESUB 1477:750. No blanking IF_SETTEXTs, no
    // re-hide, no disarm.
    //
    // THE THREE THINGS THIS FILE HAD WRONG, each now fixed and each pinned:
    // 1. ORDER. It opened the sub FIRST and filled the contents after. The reference client
    //      fills the contents and opens LAST, then runs 8178.
    //   2. IF_SETEVENTS. It armed the continue component (and ten masks per option
    // page) on every page. **The reference client sends NO IF_SETEVENTS for 1184, 1188 or
 // 1191 anywhere in either play observation** - the click comes back
    //      re-derives from index 3 on every gate run) and script 8178 hangs the
    //      handler on it. The arming was never load-bearing on the wire.
    //   3. The head, the head ANIMATION and the `<p=N>` expression prefix were
    //      absent; component 11, not the root, is what is unhidden.
    //
    // argument list (all read off the bytes, all agreeing across nine pages and two
    // defaults - they are real reference values, but the reference client picks one per LINE and
    // this module has no per-line source for them.

    /**
     * `-Dopennxt.experiment.dialogue.reference=off` puts the pre- sequence
     * back, byte for byte. Default ON.
     */
    fun referenceSequence(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.reference") != "off"

    /**
     * The CS2 script reference runs immediately after the IF_OPENSUB of every
     * dialogue page - npc, player and option box alike, with NO arguments.
     */
    const val PAGE_SCRIPT = 8178

    /**
     * The CS2 script that FILLS the option box 1188 - title, count and five strings.
     *
     * three frames in the same observation (ticks 27, 126, 141, 177) with
     * types `sisssss` and the arguments on the wire in REVERSE, then the script id
     * last. Decoded:
     *
     * tick 27 "What do you seek?" 3 ["Adventure!", "Something to kill.",
     * "Fortune and glory.", "", ""]
     * tick 126 "Choose an option:" 5 ["Tell me about Burthorpe.",
     * "Tell me about the trolls.",
     * "Where is the nearest bank?",
     * "I need guidance.", "Farewell."]
     */
    const val OPTIONS_SCRIPT = 5589

    /**
     * The title the reference client puts in argument 0 of [OPTIONS_SCRIPT] for a generic list.
     *
     * the literal string of the ticks 126/141/177 frames. Turael's own
     * first list uses "What do you seek?" instead, so this is the DEFAULT and not
     * the only value; [Page.Choose.title] carries a per-page one.
     */
    const val DEFAULT_OPTIONS_TITLE = "Choose an option:"

    /**
     * The six head animations the reference client sent over the Turael conversation's npc pages,
     * in page order, and the two later ones.
     */
    val REFERENCE_NPC_HEAD_ANIMS: IntArray = intArrayOf(9827, 9843, 9809, 9840, 9808, 9833)

    /**
     * The head animation an npc page uses when [Page.Say] does not name one.
     *
     * [REFERENCE_NPC_HEAD_ANIMS]`[0]` - the first thing Turael's head did on the wire.
     */
    const val DEFAULT_NPC_HEAD_ANIM = 9827

    /**
     * The head animation a PLAYER page uses when [Page.Say] does not name one.
     */
    const val DEFAULT_PLAYER_HEAD_ANIM = 37905

    /**
     * The `<p=N>` code the reference client prefixes to the line in IF_SETTEXT on 1184:10.
     *
     * values over the conversation, in page order: 3, 310, 2, 7, 2, 6,
     * 310, 2, and 1 on the player page at tick 627. It is NOT the animation id (the
     * two move independently: page 3 and page 5 are both `<p=2>` with anims 9809
     * and 9808), so it is a second expression channel the client reads out of the
     * text. 1 is the player page's own value and is the default here.
     */
    const val DEFAULT_EXPRESSION = 1

    /** bit 1 of the IF_SETEVENTS mask = op 1 = IF_BUTTON1. */
    const val OP1_MASK = 2

    const val CONTINUE_MASK = 1

    /** No bit set: the click gate `(mask >> op) & 1` can never pass. */
    const val NO_EVENTS_MASK = 0

    /**
     * The events mask sent to [CHAT_CONTINUE_BLOCKER] (1184:15), and the one
     * number standing between the operator and a conversation that advances.
     */
    fun blockerMask(): Int =
        System.getProperty("opennxt.experiment.dialogue.blockerMask")
            ?.trim()?.toIntOrNull()?.takeIf { it in 0..2047 } ?: 2

    /** True when the operator set a VALID `blockerMask`, as opposed to taking the default. */
    private fun blockerMaskIsExplicit(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.blockerMask")
            ?.trim()?.toIntOrNull()?.let { it in 0..2047 } == true

    /** True when the operator set a VALID `armMask`, as opposed to taking the default. */
    private fun armMaskIsExplicit(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.armMask")
            ?.trim()?.toIntOrNull()?.let { it in 0..2047 } == true

    /**
     * The events mask [render] actually writes on [comp] - the ONE resolution
     * both the wire and the boot line read.
     */
    fun maskFor(comp: Int): Int =
        if (comp == CHAT_CONTINUE_BLOCKER && blockerMaskIsExplicit() && !armMaskIsExplicit())
            blockerMask() else armMask()

    /**
     * The mask 1184:15 actually carries after [render], whichever switch put it
     * there. This is what [experimentLine] reports, so the boot line's "blocker"
     * clause names a byte that is really sent.
     */
    fun effectiveBlockerMask(): Int =
        if (CHAT_CONTINUE_BLOCKER in continueComponents()) maskFor(CHAT_CONTINUE_BLOCKER) else blockerMask()

    /**
     * The ops an IF_SETEVENTS [mask] can actually deliver on a component,
     * given the client's own control flow.
     *
     * @param str2NonEmpty whether the target component carries a baked menu
     */
    fun deliverableOps(mask: Int, str2NonEmpty: Boolean = false): List<Int> =
        (if (str2NonEmpty) 0..10 else 1..10).filter { (mask ushr it) and 1 == 1 }

    /**
     * Does [component] of [interfaceId] carry a non-empty `str2` in the cache?
     *
     * Read straight out of `interfaces_attr.menu`, so [deliverableOps] can be
     * asked the right question instead of assuming the answer. Returns false
     * when the database is absent or the row has no menu - the conservative
     * arm, matching [deliverableOps]'s default.
     */
    fun hasBakedMenuString(interfaceId: Int, component: Int): Boolean =
        runCatching {
            if (!com.opennxt.resources.sqlite.RsDatabase.available) return@runCatching false
            val hash = (interfaceId shl 16) or component
            val menu = com.opennxt.resources.sqlite.RsDatabase.queryAll(
                "SELECT value FROM interfaces_attr WHERE id = $hash AND field = 'menu'"
            ) { it.getString(1) }.firstOrNull() ?: return@runCatching false
            // The column is a JSON blob; str2 is the baked menu string. Matching
            // the empty case explicitly rather than parsing: "str2":"" is the
            // ONLY shape that means empty, and a parser here would be a second
            // opinion about a format the check already pins.
            val i = menu.indexOf("\"str2\":\"")
            if (i < 0) false else menu.substring(i + 8).takeWhile { it != '"' }.isNotEmpty()
        }.getOrDefault(false)

    /**
     * The IF_OPENSUB type flag used to mount the dialogue interfaces.
     */
    fun openWalkable(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.walkable")?.toBoolean()
            ?: false

    /** 0xffff on an IF_SETEVENTS slot means -1, "the component itself". */
    const val SLOT_SELF = 65535

    // ---- provenance ----------------------------------------------------

    const val PROVENANCE =
        "PROVENANCE: GROUNDED = the interface ids (1184 npc / 1191 player / 1188 options) and the " +
            "mount, both read out of clientscript 7800 + struct 21303 in the 949 cache. THE MOUNT " +
            "IS 1477:747 ON A RUNNING SERVER (struct param 3503, resolved by InterfaceSlot.reload at " +
            "boot); 1477:750 (param 3505) is the pre-reload fallback and a documented experiment " +
            "value. " +
            "The boot line above states the resolved value for the process you are actually running; the component numbers inside them (4 name, 10 text, 15 continue; option rows " +
            "8/13/18/23/28 paired with texts 6/33/35/37/39 by clientscript 5589); the npc's NAME and " +
            "the fact that 8,797 npc ids declare 'Talk to' (8,772 in actions_0..2 plus 25 in an " +
            "npcs_attr actions_3 row; a further 24 declare it only in members_actions_*, which the " +
            "npc codec cannot represent and this module therefore does not cover). " +
            "SPEECH, CORRECTED: it is no longer true that every word is " +
            "invented, and this string said so flatly until DialogueSeed landed. There are now " +
            "THREE layers. (a) DOCUMENTED - 4,514 conversations over 6,356 npc ids and 1,857 npc " +
            "names come from runescape.wiki's Transcript: namespace, page and revision recorded " +
            "per conversation in data/seed/dialogue_wiki.json, built by " +
            "tools/build_dialogue_seed.py. NOT the wire - the only conversation " +
            "this server has measured (Turael) " +
            "matches the wiki on 13 of its 14 sentences and on NEITHER of its page boundaries; " +
            " section 5 is that diff. -Dopennxt.seed.dialogue=off removes the " +
            "layer. (b) INVENTED - npc 758 Fred the Farmer, the pinned ten-page example. " +
            "(c) INVENTED - the four-page fallback every other npc gets, which says on screen " +
            "that it is a fallback. Also INVENTED wherever (b) or (c) applies: the number of " +
            "pages, the branch structure, and the choice of which npc says what. This " +
            "cache carries no per-npc dialogue: of 32,687 npcs, 64 string-valued params exist in " +
            "total and exactly ONE of them (npc 32680, param 9418) is a line of speech - and of the " +
            "8,797 'Talk to' npcs, that same one is the only one with any string param at all - " +
            "which is WHY the wiki is the source for layer (a). THE CHATHEAD is now GROUNDED for the model id and UNDER TEST for the packet: " +
            "opcode 101 (attribute 4, kind 1) is confirmed to take a MODEL id - on run " +
            "server-20260818-105622 the server sent it 758 and 41ms later the client asked JS5 for " +
            "index 47 (3D models) archive 758, a number that is neither of npc 758's own model ids " +
            "- so the head this server draws is the npc's OWN headModels[0] read out of npcs_attr " +
            "(8,377 of the 8,772 'Talk to' npcs declare one; multi-part heads lose every part but " +
            "the first, which is why the npc-id packet is still worth finding). UNDER TEST: which " +
            "opcode takes an NPC id. The candidate rotates PER CONVERSATION - talk four times and " +
            "the log names each one. Kinds 3 and 5 are be the local player, from two " +
            "independent sources. Evidence: data/prot/949/serverProt/_ATTR4_COMMON.md, " +
            ". NOT MODELLED AT ALL: animations and quest state."

    /**
     * The one line that rides on every dialogue event.
     *
     * [PROVENANCE] is printed ONCE, by [install] at boot. Appending the full
     * text to every event line was a real bug in this codebase and it is not
     * repeated here.
     */
    const val PROVENANCE_SHORT =
        "[interfaces+mount from the cache; speech is the runescape.wiki transcript layer where " +
            "DialogueSeed has one and INVENTED otherwise - full " +
            "provenance at boot]"

    /**
     * The measurement behind "the dialogue text is invented", stated as a number
     * so it can be re-run and disagreed with.
     *
     * Over `data/rs3.sqlite`, built from this build's cache:
     *
     *  * 32,687 npc definitions; 12,269 of them carry params; 183 distinct
     *    param ids appear across them.
     *  * **64** of those param values are strings. 63 are names or initials
     *    (param 1376 x23 'Henrietta', 'Myfi', ...; param 1139 x20 'Scout',
     *    'Foot Soldier'; param 1140 x20 'S', 'F').
     *  * **ONE** is a line of speech: npc 32680, param 9418,
     *    `"<p,neutral>I should dismiss my other companions so Anya can join me."`
     *  * Restricted to the 8,797 npcs that declare `Talk to`: that same single
     *    row is the ONLY string param on any of them.
     *
     * Prose does exist in this cache - dbtable 40 holds 473 speech-like lines
     * ("Good people of Gielinor, thank you for coming.") - but its rows are
     * keyed by CUTSCENE, with a subtitle timing and a duration per line, and no
     * column in it is an npc id. It is cutscene narration, not `Talk to`
     * dialogue, and using it would be dressing one thing up as another.
     *
     * So: the cache does not carry NPC dialogue. Every word this module puts on
     * screen was written by us.
     */
    const val DIALOGUE_TEXT_IS_NOT_IN_THE_CACHE =
        "1 line of speech across 32,687 npcs (npc 32680 param 9418); 0 across the 8,797 that declare " +
            "'Talk to'. Every word this module renders is INVENTED."

    // ---- the seam ------------------------------------------------------

    /**
     * Where a dialogue's packets go.
     */
    interface Sink {
        fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean)
        fun setText(interfaceId: Int, component: Int, text: String)
        fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int)
        fun setHide(interfaceId: Int, component: Int, hidden: Boolean)

        /**
         * Set a component's MODEL SOURCE - the build-949 attribute-4 family.
         *
         * [kind] is the model-source constant the packet writes into component
         * field 0x150, and it selects the packet: 1 -> IF_MODEL_K1, 2 ->
         * IF_MODEL_K2, 3 -> IF_MODEL_K3_SELF, 5 -> IF_MODEL_K5_SELF. Kinds 3
         * and 5 carry no id on the wire (the client uses the local player), so
         * [value] is ignored for those two.
         *
         * See `data/prot/949/serverProt/_ATTR4_COMMON.md`.
         */
        fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int)

        /**
         * IF_SETANIM (opcode 104) - the chathead's per-line expression animation.
         */
        fun setAnim(interfaceId: Int, component: Int, anim: Int)

        /**
         * IF_SETNPCHEAD (opcode 17) - the npc chathead.
         *
         * The default body is the attribute-4 route this module has always used;
         * it exists so the ONE named operation reaches the ONE encoder rather
         * than every implementor having to know that kind 2 is the npc head.
         */
        fun setNpcHead(interfaceId: Int, component: Int, npcId: Int) =
            setModel(interfaceId, component, 2, npcId)

        /**
         * IF_SETPLAYERHEAD (opcode 24) - the LOCAL player's chathead.
         *
         * Carries no id on the wire; the client reads its own player out of its
         * state. See `data/prot/949/serverProt/IF_SETPLAYERHEAD.txt`.
         */
        fun setPlayerHead(interfaceId: Int, component: Int) =
            setModel(interfaceId, component, 3, -1)

        /** IF_CLOSESUB - un-mounts whatever is at `parent:component`. */
        fun closeSub(parent: Int, component: Int)

        /**
         * RUNCLIENTSCRIPT - executes a CS2 script on the client.
         *
         * This IS the `runScript` operation the reference sequence needs; it has been
         * on the seam since the IF_CLOSESUB pass and is not duplicated under a
         * second name.
         */
        fun runClientScript(scriptId: Int, vararg args: Any)
    }

    /** One recorded packet, as a string, so a check can assert on the exact sequence. */
    class RecordingSink : Sink {
        val sent = ArrayList<String>()
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) {
            sent += "IF_OPENSUB $interfaceId -> $parent:$component walkable=$walkable"
        }

        override fun setText(interfaceId: Int, component: Int, text: String) {
            sent += "IF_SETTEXT $interfaceId:$component = '$text'"
        }

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) {
            sent += "IF_SETEVENTS $interfaceId:$component $fromSlot..$toSlot mask=$mask"
        }

        override fun setHide(interfaceId: Int, component: Int, hidden: Boolean) {
            sent += "IF_SETHIDE $interfaceId:$component hidden=$hidden"
        }

        override fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int) {
            sent += "IF_MODEL_K$kind $interfaceId:$component value=$value"
        }

        override fun setAnim(interfaceId: Int, component: Int, anim: Int) {
            sent += "IF_SETANIM $interfaceId:$component anim=$anim"
        }

        // The two head operations are recorded under THE REFERENCE CLIENT'S OWN packet names
        // rather than under IF_MODEL_K2 / IF_MODEL_K3, so a check can assert the
        // still reach exactly the same encoders through the live sink's setModel.
        override fun setNpcHead(interfaceId: Int, component: Int, npcId: Int) {
            sent += "IF_SETNPCHEAD $interfaceId:$component npc=$npcId"
        }

        override fun setPlayerHead(interfaceId: Int, component: Int) {
            sent += "IF_SETPLAYERHEAD $interfaceId:$component"
        }

        override fun closeSub(parent: Int, component: Int) {
            sent += "IF_CLOSESUB $parent:$component"
        }

 //: strings are QUOTED and the argument list is bracketed, and an
        // empty list prints nothing rather than a trailing space. An option list
        // contains commas ("Something to kill.") and a bare joinToString made the
        // recorded line ambiguous about where one argument ended - which a pin on
        // the 5589 argument list has to be able to tell.
        override fun runClientScript(scriptId: Int, vararg args: Any) {
            sent += "RUNCLIENTSCRIPT $scriptId" + if (args.isEmpty()) "" else
                " [" + args.joinToString { if (it is String) "\"$it\"" else it.toString() } + "]"
        }

        fun clear() = sent.clear()
    }

    /**
     * The live sink for a player, or null when there is none.
     */
    var sinkSupplier: (ContentPlayer) -> Sink? = { null }

    // ---- the pages -----------------------------------------------------

    enum class Speaker { NPC, PLAYER }

    sealed class Page {
        /**
         * One chathead page: a name, some words, and a continue click.
         *
         * @param headAnim the IF_SETANIM id for THIS line, or -1 for the
         * @param expression the N of the `<p=N>` prefix the reference client puts in front of the
         */
        data class Say(
            val speaker: Speaker,
            val name: String,
            val text: String,
            val headAnim: Int = -1,
            val expression: Int = -1,
            /**
             * True when this page is the LAST page of a branch and the
             * conversation must END on its click rather than fall through into
             * whatever page happens to sit at `index + 1`.
             */
            val endsHere: Boolean = false
        ) : Page() {
            val interfaceId: Int get() = if (speaker == Speaker.NPC) NPC_CHAT else PLAYER_CHAT
        }

        /**
         * A choice. [targets] is parallel to [options]: the page index to jump
         * to, or -1 to end the conversation. At most [OPTION_ROWS].size options
         * fit, which the cache fixes at five.
         */
        data class Choose(
            val options: List<String>,
            val targets: List<Int>,
            /** Argument 0 of [OPTIONS_SCRIPT]. The reference client's generic value is [DEFAULT_OPTIONS_TITLE]. */
            val title: String = DEFAULT_OPTIONS_TITLE
        ) : Page() {
            init {
                require(options.size == targets.size) { "options and targets must be parallel" }
                require(options.isNotEmpty() && options.size <= OPTION_ROWS.size) {
                    "1188 has ${OPTION_ROWS.size} option rows; asked for ${options.size}"
                }
            }
        }
    }

    /** One player's conversation. */
    class Session(
        val npcId: Int,
        val npcName: String,
        val pages: List<Page>,
        /** Which chathead candidate kind this whole conversation uses. See [headExperiment]. */
        val headKind: Int = 0
    ) {
        var index: Int = 0
            internal set

        /** Components armed with op 1 right now, as (interfaceId, component). Disarmed on close. */
        internal val armed = ArrayList<Pair<Int, Int>>()

        /** How many clicks this conversation has consumed. Observable for the leak control. */
        var clicks: Int = 0
            internal set

        val page: Page? get() = pages.getOrNull(index)
        override fun toString() = "Session(npc $npcId '$npcName', page $index/${pages.size}, clicks $clicks)"
    }

    /**
     * ContentPlayer -> their open conversation.
     *
     * Weakly keyed so a logged-out player's session is collectable, and
     * synchronized for the reason [SkillingWiring]'s own map is: writes come
     * from whichever thread decoded the packet.
     */
    private val sessions: MutableMap<ContentPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap())

    /** The open conversation for a player, or null. */
    fun sessionOf(player: ContentPlayer): Session? = sessions[player]

    /** How many conversations are open. Observable so a check can assert on a number. */
    fun openSessions(): Int = synchronized(sessions) { sessions.size }

    /** Test seam: forget every conversation without sending anything. */
    internal fun clearSessions() = synchronized(sessions) { sessions.clear() }

    // ---- what a click produces -----------------------------------------

    sealed class ClickResult {
        /** The click advanced or ended the conversation. [ended] says which. */
        data class Consumed(val ended: Boolean, val chose: Int?) : ClickResult()

        /** No conversation is open for this player. The click is not ours. */
        object NoSession : ClickResult()

        /**
         * A conversation IS open, but the click names a component the open page
         * did not arm. **Refused**, and this is the anti-client-trust gate for
         * this module: the client chooses which hash it sends, and the only
         * thing that makes one legitimate is that the server armed it.
         */
        data class NotArmed(val interfaceId: Int, val component: Int) : ClickResult()
    }

    // ---- the script ----------------------------------------------------

    /**
     * The words, in three layers.
     */
    fun script(npcId: Int, npcName: String, playerName: String): List<Page> {
        if (npcId == 758) { // Fred the Farmer
            return listOf(
                // 0
                Page.Say(Speaker.NPC, npcName, "What are you doing on my land? You're not the one who keeps leaving my gates open and letting my sheep out are you?"),
                // 1
                Page.Choose(
                    options = listOf("I'm looking for a quest.", "I'm looking for something to kill.", "I'm lost."),
                    targets = listOf(2, 6, 8)
                ),
                // 2
                Page.Say(Speaker.PLAYER, playerName, "I'm looking for a quest."),
                // 3
                Page.Say(Speaker.NPC, npcName, "You're after a quest, you say? Actually I could do with a bit of help."),
                // 4
                Page.Say(Speaker.NPC, npcName, "My sheep are getting mighty woolly. I'd be much obliged if you could shear them. And while you're at it, spin the wool into balls for me too."),
                // 5
                Page.Say(Speaker.NPC, npcName, "Yes, 20 balls of wool should do me. I'm sure I could sort out some sort of payment."),
                // 6 (from 1)
                Page.Say(Speaker.PLAYER, playerName, "I'm looking for something to kill."),
                // 7
                Page.Say(Speaker.NPC, npcName, "What, on my land? Leave my livestock alone you scoundrel!"),
                // 8 (from 1)
                Page.Say(Speaker.PLAYER, playerName, "I'm lost."),
                // 9
                Page.Say(Speaker.NPC, npcName, "This is Lumbridge. The castle is just to the south.")
            )
        }
        
        DialogueSeed.pagesFor(npcId, npcName, playerName)?.let { return it }

        return listOf(
            Page.Say(Speaker.NPC, npcName, "Hello there."),
            Page.Choose(options = listOf("Who are you?", "Never mind."), targets = listOf(2, -1)),
            Page.Say(Speaker.NPC, npcName, "I am $npcName. This server knows my name, but doesn't have my dialogue mapped yet."),
            Page.Say(Speaker.PLAYER, playerName, "Fair enough. Good day.")
        )
    }

    // ---- driving it ----------------------------------------------------

    /**
     * Opens a conversation with [npcId], replacing any conversation already
     * open for that player.
     *
     * Replacing rather than refusing is deliberate and it is the leak control's
     * subject: a second `Talk to` must not resume the first conversation's page
     * counter, and it must not leave the first conversation's components armed.
     * [close] runs first, so both are true by construction rather than by hope.
     */
    fun start(player: ContentPlayer, npcId: Int, npcName: String): Session {
        close(player)
        val session = Session(npcId, npcName, script(npcId, npcName, player.name), nextHeadKind())
        sessions[player] = session
        render(player, session)
        logger.info {
            "dialogue: ${player.name} opened a conversation with '$npcName' (npc $npcId), " +
                "${session.pages.size} pages. $PROVENANCE_SHORT"
        }
        return session
    }

    /** The IF_SETANIM id for a page, falling back to the speaker's reference default. */
    fun headAnimOf(page: Page.Say): Int =
        if (page.headAnim >= 0) page.headAnim
        else if (page.speaker == Speaker.NPC) DEFAULT_NPC_HEAD_ANIM else DEFAULT_PLAYER_HEAD_ANIM

    /** The N of the `<p=N>` prefix for a page, falling back to [DEFAULT_EXPRESSION]. */
    fun expressionOf(page: Page.Say): Int =
        if (page.expression >= 0) page.expression else DEFAULT_EXPRESSION

    /**
     * The exact string the reference client writes into 1184:10 / 1191:10 - the expression code
     * and then the line, with no separator.
     */
    fun lineTextOf(page: Page.Say): String = "<p=${expressionOf(page)}>${page.text}"

    /**
     * Draws the session's current page, and arms exactly the components that
     * page can be clicked on.
     */
    private fun render(player: ContentPlayer, session: Session) {
        val sink = sinkSupplier(player)
        disarm(sink, session)

        // ONE resolution, shared with the boot line - see Dialogue.effectiveMount.
        val (mountParent, mountComp) = effectiveMount()
        val reference = referenceSequence()

        when (val page = session.page) {
            null -> return
            is Page.Say -> {
                val iface = page.interfaceId
                if (!reference) sink?.openSub(iface, mountParent, mountComp, walkable = openWalkable())

                hide(sink, iface, if (reference) CHAT_HIDE_COMPONENT else ROOT_COMPONENT, false)
                if (reference) sink?.setAnim(iface, CHAT_HEAD_COMPONENT, headAnimOf(page))
                sink?.setText(iface, CHAT_NAME_COMPONENT, page.name)
                sink?.setText(iface, CHAT_TEXT_COMPONENT, if (reference) lineTextOf(page) else page.text)

                head(sink, session, page, iface)

                // The click ledger. In the reference client mode nothing is sent for it; see the
                // KDoc. maskFor()/blockerMask() are only consulted when they can
                // reach a packet, so the boot line's "blocker ... mask=N" never
                // describes a write that did not happen.
                val comps = continueComponents()
                for (comp in comps) {
                    if (!reference) sink?.setEvents(iface, comp, SLOT_SELF, SLOT_SELF, maskFor(comp))
                    session.armed += iface to comp
                }
 // The blocker's OWN write - and only when
                // it is not already one of the components above, so 1184:15 is
                // never addressed twice. See [maskFor] for why this came back.
                // It joins session.armed because close() must reverse every
                // write render() made, not just the ones called "armed".
                if (CHAT_CONTINUE_BLOCKER !in comps) {
                    if (!reference) sink?.setEvents(iface, CHAT_CONTINUE_BLOCKER, SLOT_SELF, SLOT_SELF, blockerMask())
                    session.armed += iface to CHAT_CONTINUE_BLOCKER
                }

                if (reference) {
                    sink?.openSub(iface, mountParent, mountComp, walkable = openWalkable())
                    sink?.runClientScript(PAGE_SCRIPT)
                }
            }

            is Page.Choose -> {
                sink?.openSub(OPTIONS, mountParent, mountComp, walkable = openWalkable())

                if (reference) {
                    sink?.runClientScript(PAGE_SCRIPT)
                    // The whole list in one script call, exactly as the reference client sends
                    // it: title, count, and FIVE strings whether or not they are
                    // used. RunClientScript's own encoder puts them on the wire in
                    // reverse with the script id last, which is what the frames at
                    // tick 27 show - see data/prot/949/serverProt/RUNCLIENTSCRIPT.txt.
                    val args = ArrayList<Any>(2 + OPTION_ROWS.size)
                    args += page.title
                    args += page.options.size
                    for (i in OPTION_ROWS.indices) args += page.options.getOrElse(i) { "" }
                    sink?.runClientScript(OPTIONS_SCRIPT, *args.toTypedArray())
                    for (i in page.options.indices) {
                        session.armed += OPTIONS to OPTION_ROWS[i]
                        session.armed += OPTIONS to OPTION_ROW_ALIASES[i]
                    }
                } else {
                    hide(sink, OPTIONS, ROOT_COMPONENT, false)
                    // Every one of the five rows is addressed, not just the used
                    // ones: a row left alone would still be carrying the previous
                    // conversation's text and, worse, its event mask.
                    for (i in OPTION_ROWS.indices) {
                        val used = i < page.options.size
                        val mask = if (used) armMask() else NO_EVENTS_MASK
                        sink?.setText(OPTIONS, OPTION_TEXTS[i], if (used) page.options[i] else "")
                        // BOTH the visible bar and the container that wraps it. See
                        // OPTION_ROW_ALIASES: the client picks whichever is under
                        // the cursor, and arming one does not disarm the other.
                        sink?.setEvents(OPTIONS, OPTION_ROWS[i], SLOT_SELF, SLOT_SELF, mask)
                        sink?.setEvents(OPTIONS, OPTION_ROW_ALIASES[i], SLOT_SELF, SLOT_SELF, mask)
                        if (used) {
                            session.armed += OPTIONS to OPTION_ROWS[i]
                            session.armed += OPTIONS to OPTION_ROW_ALIASES[i]
                        }
                    }
                }
            }
        }
    }

    /**
     * The chathead experiment - see [headExperiment] for what it is and why it
     * is an experiment rather than a feature.
     *
     * Which candidate a page gets is a pure function of the page index and the
     * speaker, so a run is reproducible and the log line names the candidate
     * before the client draws anything.
     */
    private fun head(sink: Sink?, session: Session, page: Page.Say, iface: Int) {
 //: opcodes 17/24 are named (IF_SETNPCHEAD / IF_SETPLAYERHEAD in
        // serverProtNames.toml), so the chathead is no longer a guess. The DEFAULT is now
        // speaker-driven - an NPC page draws the npc's head (kind 2), a PLAYER page draws the
        // LOCAL PLAYER's head (kind 3, no id on the wire) - because RUN6c showed every page of a
        // conversation wearing Fred's face: one kind was being used for the whole session.
        // -Dopennxt.experiment.dialogue.head=k1|k2|k3|k5|cycle|off still forces one kind for
        // every page, which is what the experiment needs.
        val kind = if (headExperiment == "speaker") {
            if (page.speaker == Speaker.NPC) 2 else 3
        } else session.headKind
        if (kind == 0) return

        // Kinds 3 and 5 carry no id: the client uses the local player.
        // Kind 1 wants a MODEL id, and the npc's own head model is in the
        // cache - see [NpcHeadModels]. Kind 2 wants whatever kind 2 keys on,
        // which on the npc-head hypothesis is the npc id.
        val value = when (kind) {
            1 -> NpcHeadModels.firstHeadModel(session.npcId) ?: run {
                logger.info {
                    "dialogue: chathead - npc ${session.npcId} ('${session.npcName}') declares no " +
                        "headModels in this cache, so there is nothing to draw. 8,377 of the 8,772 " +
                        "'Talk to' npcs do declare one; this is one of the other 395."
                }
                return
            }
            2 -> session.npcId
            else -> -1
        }

 //: kinds 2 and 3 - the only two the speaker rule ever produces,
        // and the only two the reference client sends - go through the NAMED seam operations, so
        // the recorded sequence reads IF_SETNPCHEAD / IF_SETPLAYERHEAD, the words
        // the protocol write-up uses. The k1/k5 experiment kinds keep the generic
        // route; nothing about the encoders changes either way.
        when (kind) {
            2 -> sink?.setNpcHead(iface, CHAT_HEAD_COMPONENT, value)
            3 -> sink?.setPlayerHead(iface, CHAT_HEAD_COMPONENT)
            else -> sink?.setModel(iface, CHAT_HEAD_COMPONENT, kind, value)
        }

        logger.info {
            val what = when (kind) {
                1 -> "value=$value = npc ${session.npcId}'s OWN headModel from the cache" +
                    (NpcHeadModels.partCount(session.npcId).let {
                        if (it > 1) " (WARNING: this head has $it parts and opcode 101 sets ONE - " +
                            "the other ${it - 1} will be missing)" else ""
                    })
                2 -> "value=$value = the npc id. PREDICTION: if kind 2 is the npc head, the client " +
                    "must now fetch index 47 archive " +
                    "${NpcHeadModels.firstHeadModel(session.npcId) ?: -1} - a number this server " +
                    "never sent. Check the diag log for a JS5 REQUEST index=47."
                else -> "no id on the wire - the client uses the LOCAL PLAYER, so this should draw YOUR head"
            }
            "dialogue: chathead experiment - conversation with '${session.npcName}' uses " +
                "IF_MODEL_K$kind on $iface:$CHAT_HEAD_COMPONENT, $what"
        }
    }

    /**
     * The candidate for the next conversation.
     *
     * A plain rotating counter rather than anything derived from the player or
     * the npc: the operator's instruction is "talk four times", and a counter is
     * the only thing that makes the four runs distinguishable in the log without
     * asking them to remember which npc they used.
     */
    private fun nextHeadKind(): Int = when (headExperiment) {
        "off" -> 0
        "k1" -> 1
        "k2" -> 2
        "k3" -> 3
        "k5" -> 5
        "cycle" -> HEAD_CANDIDATES[
            Math.floorMod(headCycle.getAndIncrement(), HEAD_CANDIDATES.size)
        ]
        else -> 0
    }

    /** Test seam: put the cycle back to the start. */
    internal fun resetHeadCycle() = headCycle.set(0)

    /**
     * IF_SETEVENTS mask 0 on everything the previous page armed, then forget them.
     *
     * The armed LIST is maintained whether or not there is a sink, so the state
     * machine - and therefore [onButton]'s refusal of unarmed components -
     * behaves identically headless and live. Only the packets are conditional.
     */
    private fun disarm(sink: Sink?, session: Session) {
 //the PACKET is legacy-only. The reference client never writes an events mask
        // to a dialogue component, so it has nothing to blank; the LEDGER is
        // cleared in both modes because it is what onButton() refuses against.
        val send = !referenceSequence()
        for ((iface, component) in session.armed) {
            if (send) sink?.setEvents(iface, component, SLOT_SELF, SLOT_SELF, NO_EVENTS_MASK)
        }
        session.armed.clear()
    }

    private fun hide(sink: Sink?, interfaceId: Int, component: Int, hidden: Boolean) {
        if (!sethideEnabled) return
        sink?.setHide(interfaceId, component, hidden)
    }

    /**
     * A click came back. Returns what was done with it.
     *
     * [interfaceId] and [component] are the two halves of IF_BUTTON1's `arg1`,
     * which the one live observation in this build confirmed is
     * `(interface shl 16) or component`.
     */
    fun onButton(player: ContentPlayer, interfaceId: Int, component: Int): ClickResult {
        val session = sessions[player] ?: return ClickResult.NoSession
        if ((interfaceId to component) !in session.armed) {
            logger.warn {
                "dialogue: ${player.name} clicked $interfaceId:$component, which the open page " +
                    "(${session.page}) did not arm. Refused; the conversation is unchanged."
            }
            return ClickResult.NotArmed(interfaceId, component)
        }
        session.clicks++

        return when (val page = session.page) {
            is Page.Say -> {
 // `endsHere` jumps past the end, which `advance`
                // turns into a close. Default false = `index + 1`, unchanged.
                advance(player, session,
                    if (page.endsHere) session.pages.size else session.index + 1)
                ClickResult.Consumed(ended = sessions[player] == null, chose = null)
            }

            is Page.Choose -> {
                val choice = OPTION_ROWS.indexOf(component)
                    .let { if (it >= 0) it else OPTION_ROW_ALIASES.indexOf(component) }
                val target = page.targets.getOrElse(choice) { -1 }
                logger.info {
                    "dialogue: ${player.name} chose option ${choice + 1} " +
                        "('${page.options.getOrNull(choice)}') -> " +
                        (if (target < 0) "end" else "page $target") + ". $PROVENANCE_SHORT"
                }
                advance(player, session, if (target < 0) session.pages.size else target)
                ClickResult.Consumed(ended = sessions[player] == null, chose = choice)
            }

            null -> {
                close(player)
                ClickResult.Consumed(ended = true, chose = null)
            }
        }
    }

    private fun advance(player: ContentPlayer, session: Session, next: Int) {
        session.index = next
        if (session.page == null) {
            close(player)
            return
        }
        render(player, session)
    }

    /**
     * Ends the conversation and leaves nothing armed.
     */
    fun close(player: ContentPlayer): Boolean {
        val session = sessions.remove(player) ?: return false
        val sink = sinkSupplier(player)
        disarm(sink, session)
        if (!referenceSequence()) {
            for (iface in intArrayOf(NPC_CHAT, PLAYER_CHAT)) {
                sink?.setText(iface, CHAT_NAME_COMPONENT, "")
                sink?.setText(iface, CHAT_TEXT_COMPONENT, "")
            }
            for (slot in OPTION_TEXTS) sink?.setText(OPTIONS, slot, "")
            for (iface in intArrayOf(NPC_CHAT, PLAYER_CHAT, OPTIONS)) {
                hide(sink, iface, ROOT_COMPONENT, true)
            }
        }
        // And now it really is closed. IF_CLOSESUB (949 opcode 107) is sent LAST
        // and exactly once, naming the MOUNT rather than the interface, because
        // ONE resolution, shared with the boot line - see Dialogue.effectiveMount.
        val (mountParent, mountComp) = effectiveMount()

        sink?.closeSub(mountParent, mountComp)
        logger.info { "dialogue: ${player.name} closed $session. $PROVENANCE_SHORT" }
        return true
    }

    // ---- registration --------------------------------------------------

    /**
     * Binds [TALK_TO] across every npc that declares it. Returns the count.
     *
     * The binding is on the ACTION STRING, not on an option index, and that is
     * load-bearing here for the same reason it is in
     * [com.opennxt.model.combat.PlayerCombat]: 'Talk to' sits in `actions_0`
     * for 8,240 npcs and in `actions_2` for 532, so a binding on "row 1" would
     * silently miss 532 npcs while a binding on the string cannot.
     * [ContentRegistry] then refuses any (npc, action) pair the definition does
     * not declare, so a client that sends OPNPC1 for an npc whose slot 0 is
     * 'Attack' reaches nothing here.
     *
     * **The measured hole**: 24 npc ids declare `Talk to` only in a
     * `members_actions_*` row, which [com.opennxt.resources.sqlite.NpcDefinition]
     * has no storage for (folding it into the plain slots would misdeclare a
     * members option as free-to-play). Those 24 - npc 44 'Banker' among them -
     * are NOT covered, and a `Talk to` click on one of them is Rejected by the
     * registry rather than silently mishandled. [Shops] carries the same
     * remainder and solves it with a wider seam; nothing here does.
     */
    fun install(): Int {
        if (!enabled) {
            logger.warn { "dialogue: DISABLED (-Dopennxt.experiment.dialogue=false); 'Talk to' stays inert" }
            return 0
        }
        val bound = ContentRegistry.onNpcAction(TALK_TO) { ctx: NpcContext ->
            val name = ctx.definition.name ?: "Someone"
            start(ctx.player, ctx.npcId, name)
            OPENED
        }
        logger.info { experimentLine() }
        logger.info {
            "dialogue: bound '$TALK_TO' across $bound npc ids; npc chat $NPC_CHAT, player chat " +
                "$PLAYER_CHAT, options $OPTIONS. IF_SETHIDE is " +
                (if (sethideEnabled) "ON" else "OFF") + ". $PROVENANCE"
        }
 // WARM THE WIKI LAYER AT BOOT (F39). `DialogueSeed.seed` is a
        // `by lazy`, and the file behind it is 7.4 MB of JSON. Left cold it would
        // be parsed on the FIRST 'Talk to' click - i.e. on the packet thread, in
        // uncontrolled I/O on the tick thread) is why this line is here rather
        // than nowhere. Touching `size()` is what forces the load; the layer
        // logs its own counts and its own tier.
        logger.info { "dialogue: wiki layer warmed at boot - ${DialogueSeed.size()} conversations" }
        return bound
    }

    /**
     * The one line that tells the operator, at boot, exactly which point of the
     * experiment space this process is standing on.
     */
    fun experimentLine(): String {
        // The CALL SITE's mount, not the switch's - see [effectiveMount].
        val (mountParent, mount) = effectiveMount()
        val param = effectiveMountParam()
        val comps = continueComponents()
        // The masks that REACH THE WIRE, not the raw switch values - see [maskFor].
        val blocker = effectiveBlockerMask()
        // One armed component can carry a different mask from the others (only
        // 1184:15 can, via [maskFor]), so say so instead of picking one and
        // calling it "the" arm mask. [arm] stays the mask the ops line is
        // derived from; [armExtra] names the disagreement when there is one.
        val armMasks = comps.map { maskFor(it) }.distinct()
        val arm = if (comps.isEmpty()) armMask() else armMasks.first()
        val armExtra = if (armMasks.size > 1) " (MIXED: ${armMasks.sorted()})" else ""
        // Ask the cache per component instead of assuming. 1184:15 carries
        // str2 "Continue" and the armed set does not, and that difference
        // decides whether op 0 is range-checked - see deliverableOps.
        val blockerStr2 = hasBakedMenuString(NPC_CHAT, CHAT_CONTINUE_BLOCKER)
        val armStr2 = comps.any { hasBakedMenuString(NPC_CHAT, it) }
        val blockerOps = deliverableOps(blocker, blockerStr2)
        val armOps = deliverableOps(arm, armStr2)
 //. Everything after "blocker ..." on this line describes
        // IF_SETEVENTS writes, and in the reference client mode render makes NONE of them: the
        // click comes back off the cache's own optmask 1. Saying so here is the
        // whole point of this line - it exists so the operator is never told about
        // a byte that did not go out (see blockerMask's KDoc for the time that
        // happened for real).
        val reference = referenceSequence()
        val eventsNote = if (reference)
            " [REFERENCE SEQUENCE: contents then IF_OPENSUB then RUNCLIENTSCRIPT $PAGE_SCRIPT; " +
                "NO IF_SETEVENTS IS SENT, so every mask below is the value the LEGACY path " +
                "would use - -Dopennxt.experiment.dialogue.reference=off to send them]"
        else " [LEGACY SEQUENCE (-Dopennxt.experiment.dialogue.reference=off): IF_OPENSUB first, " +
            "IF_SETEVENTS per page; this is NOT what the reference client sends]"
        return "dialogue experiment: mount=$mountParent:$mount " +
            (if (param >= 0) "(struct $DIALOGUE_STRUCT param $param)" else "(RAW component, no struct param points here)") +
            eventsNote +
            "; IF_OPENSUB type=${if (openWalkable()) 1 else 0} " +
            "(close-policy tag, NOT click-through - see Dialogue.openWalkable); " +
            "blocker $NPC_CHAT:$CHAT_CONTINUE_BLOCKER mask=$blocker str2=" +
            (if (blockerStr2) "NON-EMPTY (op 0 is NOT range-checked here)" else "empty") +
            " -> ops " +
            (if (blockerOps.isEmpty()) "NONE (this component cannot send anything)" else blockerOps.toString()) +
            "; armed=${comps.joinToString(",")} mask=$arm$armExtra str2=" +
            (if (armStr2) "NON-EMPTY" else "empty") + " -> ops " +
            (if (armOps.isEmpty()) "NONE (nothing armed can send anything)" else armOps.toString())
    }

    /** What a handled `Talk to` returns, so a caller can tell it apart from null. */
    const val OPENED = "dialogue-opened"
}
