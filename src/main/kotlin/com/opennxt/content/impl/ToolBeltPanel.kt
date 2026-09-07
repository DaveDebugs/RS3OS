package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfOpenSub
import com.opennxt.net.game.serverprot.ifaces.IfSetevents
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import com.opennxt.net.game.serverprot.ifaces.IfSettext
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * INTERFACE 1944 - THE TOOL BELT PANEL. The operator cannot open his belt to see what is on it.
 */
object ToolBeltPanel {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.toolbelt.panel") != "false"

    // ---- the measured constants. Every one of these is decoded above, none is chosen. ----

    /** The belt interface. */
    const val IFACE = 1944

    /** toplevel_v2. */
    const val TOPLEVEL = 1477

    /** 1477:728 - the mount, from parent hash 96797400. */
    const val MOUNT = 728

    /** 1944:7 - the slot grid. */
    const val SLOT_GRID = 7

    /** 1944:18 - hidden on the open. */
    const val HIDE_COMPONENT = 18

    /** 1944:29 - armed with mask 2 and no slot range. */
    const val SINGLE_COMPONENT = 29

    /** 1944:27 - the description field a slot click fills. */
    const val DESCRIPTION_COMPONENT = 27

    /**
     * 1944:102 - the panel's CLOSE button, t855
     * the open block's two `IF_SETEVENTS` cover 1944:[SLOT_GRID] and 1944:[SINGLE_COMPONENT] and
     * neither reaches 102, so 102's clickability comes from the cache's own optmask. We therefore
     * do not have to arm it - but we do have to HANDLE it, which until nothing did.
     */
    const val CLOSE_COMPONENT = 102

    /** `IF_SETEVENTS 1944:7 fromSlot 0 toSlot 77` - the grid holds 78 slots. */
    const val FIRST_SLOT = 0
    const val LAST_SLOT = 77
    const val SLOT_MASK = 6
    const val SINGLE_MASK = 2

    /** The two scripts, in the order reference runs them, with their measured arguments. */
    const val SCRIPT_LAYOUT = 14150
    const val SCRIPT_LAYOUT_ARG = 6
    const val SCRIPT_FILL = 14097
    const val SCRIPT_FILL_ARG = 0

    /**
     * VARP_SMALL 9412 = 0, sent first.
     *
     * Its meaning is UNKNOWN and it is replayed, not interpreted - and as of the 06-16-19 observation
     * that refusal is vindicated rather than merely cautious. The reference client sends this varp, with the
     * value 0, on the CLOSE as well as the open, and **no observation in the corpus ever sets it to
     * anything but 0**. So it is not an "is the belt open" gate and the name below is a misnomer
     * kept only because renaming a measured constant loses the tie to the wire; read it as
     * "the varp the reference client sends when it touches this panel, in either direction".
     */
    const val VARP_ON_OPEN = 9412
    const val VARP_ON_OPEN_VALUE = 0

    /** VARP_LARGE 7863 = <struct id>, the selected belt slot. */
    const val VARP_SELECTED_STRUCT = 7863

    /** 1464:19 - the worn panel's icon strip while the panel is DOCKED (11-50-01 t52). */
    const val OPEN_BUTTON_IFACE = 1464
    const val OPEN_BUTTON_COMPONENT = 19

    /**
     * 1462:35 - the SAME icon strip while the worn panel is inside a window
     * (`toplevel_v2_parent_suboverlay_worn`, 13-03-01 t139). Missing this pair is why the KDoc's
     * old "2 of 2 sessions on 1464:19" was wrong; see section 1b.
     */
    const val OPEN_BUTTON_IFACE_WINDOWED = 1462
    const val OPEN_BUTTON_COMPONENT_WINDOWED = 35

    /**
     * The slot that IS the tool belt, on both host components. The DISCRIMINATOR - slot 1 on the
     * same components opens the window layout instead (4 negative samples, section 1b).
     */
    const val OPEN_BUTTON_SLOT = 4353

    /** mask 2046 = 0x7fe = ops 1.10, exactly what the reference client arms the strip with. */
    const val STRIP_MASK = 2046

    /**
     * The five two-slot ranges the reference client arms on the strip, in the reference client's order. Sent as `fromSlot`
     * only; each range is `from..from+1`. Derived from the wire, not chosen: any observation that
     * mounts the worn panel sends these five and no others.
     */
    val STRIP_RANGES: IntArray = intArrayOf(0, 4096, 4352, 4608, 4864)

    /** True when this frame is the tool-belt icon on either host component. */
    fun isOpenButton(iface: Int, component: Int, slot: Int): Boolean =
        slot == OPEN_BUTTON_SLOT &&
            ((iface == OPEN_BUTTON_IFACE && component == OPEN_BUTTON_COMPONENT) ||
                (iface == OPEN_BUTTON_IFACE_WINDOWED && component == OPEN_BUTTON_COMPONENT_WINDOWED))

    // ---- struct params, read off the cache (see the class KDoc) ----
    /** The belt slot's item id. The param that DEFINES the family. */
    const val P_ITEM = 6980
    /** 1 when the slot has an upgrade path (9 of 85). The discriminator behind the upgrade clause. */
    const val P_UPGRADEABLE = 6981
    /** How the tool is obtained; present on 46 of 85. */
    const val P_OBTAINED = 6983

    /** The second half of the description, measured on both samples. */
    const val OBTAINED_DEFAULT = "Obtained automatically."
    const val UPGRADE_CLAUSE =
        " To upgrade, select the 'Add to tool belt' menu option on the upgrade item from your backpack."

    /** One belt slot as the cache states it. */
    data class Slot(val structId: Int, val itemId: Int, val upgradeable: Boolean, val obtained: String?)

    /**
     * Every struct carrying [P_ITEM], in struct-id order. 85 on this cache.
     *
     * The ORDER is not a finding - see the class KDoc. The MEMBERSHIP is: the SQL asks the
     * cache which structs carry the param, so a cache with a new tool has a new slot here.
     */
    val slots: List<Slot> by lazy { load() }

    private fun load(): List<Slot> {
        if (!RsDatabase.available) return emptyList()
        val byStruct = HashMap<Int, MutableMap<Int, Pair<Int?, String?>>>()
        RsDatabase.queryAll(
            "SELECT struct_id, prop, intvalue, stringvalue FROM struct_param " +
                " WHERE prop IN ($P_ITEM, $P_UPGRADEABLE, $P_OBTAINED) " +
                "   AND struct_id IN (SELECT struct_id FROM struct_param WHERE prop = $P_ITEM)"
        ) { rs ->
            val sid = rs.getInt("struct_id")
            val prop = rs.getInt("prop")
            val iv: Int? = rs.getInt("intvalue").let { if (rs.wasNull()) null else it }
            val sv: String? = rs.getString("stringvalue")
            byStruct.getOrPut(sid) { HashMap() }[prop] = iv to sv
            sid
        }
        return byStruct.entries.sortedBy { it.key }.mapNotNull { (sid, props) ->
            val item = props[P_ITEM]?.first ?: return@mapNotNull null
            Slot(
                structId = sid,
                itemId = item,
                upgradeable = (props[P_UPGRADEABLE]?.first ?: 0) == 1,
                obtained = props[P_OBTAINED]?.second
            )
        }
    }

    /** By struct id, for the reverse lookup a slot click needs. */
    val slotsByStruct: Map<Int, Slot> by lazy { slots.associateBy { it.structId } }

    /**
     * The slot -> struct order. A DEFAULT (struct-id order), not a measurement - the corpus
     * has one click per observation and cannot name a second slot. Overridable so the run that
     * settles it does not need a rebuild.
     */
    val slotMap: Map<Int, Int> by lazy {
        val override = System.getProperty("opennxt.toolbelt.panel.slotmap")
        if (override != null) {
            override.split(',').mapNotNull {
                val p = it.split(':')
                val slot = p.getOrNull(0)?.trim()?.toIntOrNull()
                val struct = p.getOrNull(1)?.trim()?.toIntOrNull()
                if (slot != null && struct != null) slot to struct else null
            }.toMap()
        } else slots.take(LAST_SLOT - FIRST_SLOT + 1).mapIndexed { i, s -> i to s.structId }.toMap()
    }

    fun structForSlot(slot: Int): Slot? = slotMap[slot]?.let { slotsByStruct[it] }

    /**
     * The description the reference client's server sends for a belt slot, minus the item's examine
     * sentence (which is not in this cache - see the class KDoc). The reference client's own text for the
     * two measured slots is `"<examine><br><br>" + this`.
     */
    fun descriptionFor(slot: Slot): String =
        (slot.obtained ?: OBTAINED_DEFAULT) + (if (slot.upgradeable) UPGRADE_CLAUSE else "")

    @Volatile
    var opens: Int = 0
        private set

    @Volatile
    var slotClicks: Int = 0
        private set

    @Volatile
    var arms: Int = 0
        private set

    /** Clicks on 1944:[CLOSE_COMPONENT] that reached [close]. */
    @Volatile
    var closes: Int = 0
        private set

    internal fun resetCounters() { opens = 0; slotClicks = 0; arms = 0; closes = 0 }

    /**
     * Arm the worn panel's icon strip the way the reference client arms it, so the tool-belt icon can be
     * clicked at all.
     *
     * THE DEFECT THIS CLOSES. `WorldPlayer.kt:1521` arms `1464:19` over slots `0..6`; the belt
     * is slot **4353**. The client sends nothing for an unarmed slot, so the icon was inert on
     * this server while its tooltip still drew. The reference client's five ranges are in [STRIP_RANGES] and
     * the mask in [STRIP_MASK], both read off the wire (section 1c).
     *
     * `IF_SETEVENTS` is **replace-per-range**, not merge (InterfaceManager.kt:1670), so sending
     * the five after the existing `0..6` leaves `0..6` armed and ADDS the four high ranges; the
     * `0.1` range is re-sent because the reference client sends it and because it must not be the odd one out
     * if the login arming is ever changed. Nothing here removes an arming.
     *
     * [component] defaults to the docked strip; pass [OPEN_BUTTON_COMPONENT_WINDOWED] with
     * [OPEN_BUTTON_IFACE_WINDOWED] for the windowed twin.
     *
     * Returns the number of `IF_SETEVENTS` frames handed to the interface manager (5), or 0 when
     * the module is off or the channel is dead.
     */
    fun armOpenButton(
        player: WorldPlayer,
        iface: Int = OPEN_BUTTON_IFACE,
        component: Int = OPEN_BUTTON_COMPONENT
    ): Int {
        if (!enabled) return 0
        if (!player.client.channel.isActive) return 0
        for (from in STRIP_RANGES) {
            player.interfaces.events(id = iface, component = component, from = from, to = from + 1, mask = STRIP_MASK)
        }
        arms++
        logger.info {
            "toolbelt panel: armed $iface:$component over ${STRIP_RANGES.joinToString { "$it..${it + 1}" }} " +
                "mask $STRIP_MASK for ${player.name} - the reference client's five ranges, on every session that " +
                "mounts the worn panel. The tool belt is slot $OPEN_BUTTON_SLOT, which the login arming's " +
                "single 0..6 range did NOT cover, which is why the icon was inert."
        }
        return STRIP_RANGES.size
    }

    /**
     * Open the belt, exactly as measured.
     *
     * ## The nine questions
     *  1. this tick - all seven packets go out now; there is no deferred part.
     *  2. next tick - nothing. The client assembles the grid from scripts 14150/14097.
     * 3. the actor moves - a panel is not a world interaction; the reference client does not close it.
     *  4. the target disappears - not applicable, the target is an interface.
     * 5. the actor dies - not modelled here; nothing closes the panel, as in the reference client.
     *  6. logout / disconnect - guarded on `channel.isActive`; a dead channel sends nothing.
     * 7. the request repeats - a second open re-sends the same block. The reference client's own block is
     *     idempotent (an `IF_OPENSUB` of an already-open sub replaces it), so a double click
     *     costs seven packets and changes nothing. Not throttled, and said so.
     *  8. persistence fails - nothing is persisted by an open.
     *  9. two actors - per-session interface state; there is no shared object.
     */
    fun open(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (!player.client.channel.isActive) return false
        val w = player.client
        w.write(VarpSmall(VARP_ON_OPEN, VARP_ON_OPEN_VALUE))
        w.write(IfSethide(InterfaceHash(IFACE, HIDE_COMPONENT), true))
        w.write(IfOpenSub(IFACE, true, InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(SCRIPT_LAYOUT_ARG)))
        w.write(IfSetevents(InterfaceHash(IFACE, SLOT_GRID), FIRST_SLOT, LAST_SLOT, SLOT_MASK))
        w.write(IfSetevents(InterfaceHash(IFACE, SINGLE_COMPONENT), 65535, 65535, SINGLE_MASK))
        w.write(RunClientScript(SCRIPT_FILL, arrayOf(SCRIPT_FILL_ARG)))
        opens++
        logger.info {
            "toolbelt panel: opened 1944 at $TOPLEVEL:$MOUNT for ${player.name} - the seven-packet " +
                "block t53 and T13-03-01 t139. The grid " +
                "1944:$SLOT_GRID is armed over slots $FIRST_SLOT.$LAST_SLOT; the reference client sends NO slot " +
                "contents and neither does this. The player's stored belt is " +
                "${ToolBelt.storedIdsFor(player.contentPlayer).size} id(s) plus the base tier."
        }
        return true
    }

    /**
     * A click on a belt slot: reply with the struct id and the description, as the reference client does.
     *
     * Returns false when the slot maps to no struct - which, with the default [slotMap], is
     * every slot from 85 upward and any slot at all if the cache has no struct_param table.
     * Nothing is sent in that case; a wrong description is worse than none.
     */
    /**
     * A click on the panel's own close button, 1944:[CLOSE_COMPONENT].
     *
     * whole (06-16-19 t855): the reference client's entire reply is three frames, and it is the open
     * block's mirror with the two `IF_SETEVENTS` and the fill script dropped.
     *
     * ```
     * VARP_SMALL      9412 = 0
     * IF_CLOSESUB     1477:728
     * RUNCLIENTSCRIPT 14150 [6]
     * ```
     *
     * The `14150[6]` is deliberate and is NOT copied out of [open] by mistake: it runs in BOTH
     * directions, which is what identifies it as the panel-slot refresh rather than an open
     * script. Treating it as an open script - which reading [open] alone invites - would be
     * reading the callee and not the call site.
     *
     * THE DEFECT THIS CLOSES. Nothing in the tree handled 1944:102 at all: `IfButtonNHandler` had
     * a branch for the open button and one for the slot grid, and the close fell through to the
     * generic path and reached no content. The panel therefore stayed mounted on 1477:[MOUNT] for
     * the rest of the session once opened, with no way for the player to dismiss it.
     *
     * The nine questions. (1) the three frames go out this tick; (2) nothing next tick - the
     * client tears its own layout down off the CLOSESUB. (3) movement does not close a panel in
     * the reference client and does not here. (4) no target. (5) death is not modelled for panels, as in the reference client.
     * (6) `channel.isActive` is checked before the first write, as [open] does. (7) a repeat close
     * is harmless and is what the reference client does - it re-sends the same three frames; we do the same
     * rather than tracking an open flag the wire gives us no way to verify (see [VARP_ON_OPEN]).
     * (8) nothing is persisted. (9) per-player packets only; no shared state is touched.
     */
    fun close(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (!player.client.channel.isActive) return false
        val w = player.client
        w.write(VarpSmall(VARP_ON_OPEN, VARP_ON_OPEN_VALUE))
        w.write(IfClosesub(InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(SCRIPT_LAYOUT_ARG)))
        closes++
        logger.info {
            "toolbelt panel: closed 1944 at $TOPLEVEL:$MOUNT for ${player.name} - the three-packet " +
                "block t855. Script $SCRIPT_LAYOUT" +
                "[$SCRIPT_LAYOUT_ARG] runs on the close too, which is why it is the slot refresh " +
                "and not an open script."
        }
        return true
    }

    fun handleSlotClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val s = structForSlot(slot) ?: run {
            logger.info {
                "toolbelt panel: ${player.name} clicked belt slot $slot, which the DEFAULT slot map " +
                    "(struct-id order over ${slots.size} structs) does not cover. Nothing sent - the " +
                    "slot -> struct order is UNMEASURED, see ToolBeltPanel's KDoc."
            }
            return false
        }
        if (!player.client.channel.isActive) return false
        player.client.write(VarpLarge(VARP_SELECTED_STRUCT, s.structId))
        player.client.write(IfSettext(InterfaceHash(IFACE, DESCRIPTION_COMPONENT), descriptionFor(s)))
        slotClicks++
        logger.info {
            "toolbelt panel: ${player.name} belt slot $slot -> struct ${s.structId} (item ${s.itemId}" +
                "${if (s.upgradeable) ", upgradeable" else ""}). varp $VARP_SELECTED_STRUCT and " +
                "1944:$DESCRIPTION_COMPONENT sent. The item's EXAMINE sentence is not prefixed - this " +
                "cache has no examine text."
        }
        return true
    }

    fun describe(): String =
        "toolbelt panel: interface $IFACE mounts at $TOPLEVEL:$MOUNT, grid $IFACE:$SLOT_GRID over " +
            "slots $FIRST_SLOT.$LAST_SLOT. ${slots.size} belt structs " +
            "derived from struct param $P_ITEM. It is opened by SLOT $OPEN_BUTTON_SLOT on the worn " +
            "panel's icon strip - $OPEN_BUTTON_IFACE:$OPEN_BUTTON_COMPONENT docked, " +
            "$OPEN_BUTTON_IFACE_WINDOWED:$OPEN_BUTTON_COMPONENT_WINDOWED windowed - and the strip needs " +
            "armOpenButton()'s five ranges ${STRIP_RANGES.joinToString { "$it..${it + 1}" }} mask $STRIP_MASK, " +
            "not the login path's single 0..6. The slot -> struct ORDER is a DEFAULT and is not " +
            "measured; the reference client sends no slot contents at all, and neither does this."
}
