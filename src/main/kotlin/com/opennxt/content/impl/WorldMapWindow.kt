package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * The WORLD MAP - `worldmap_v2` (1421) and `worldmap_v2_ui` (1422).
 */
object WorldMapWindow {
    private val logger = KotlinLogging.logger { }

    // -------------------------------------------------------------- the numbers

    /** `toplevel_v2_minimap`, and the component that carries "Open World Map". */
    const val MINIMAP_IFACE = 1465
    const val MAP_BUTTON = 11

    /** Menu row 1 of [MAP_BUTTON]'s five cache options. */
    const val OPEN_MAP_ROW = 1

    /** The cache's own optmask for 1465:11: bits 1..5 for its five menu rows. */
    const val MAP_BUTTON_MASK = 62

    const val GAMEFRAME = 1477

    /** `worldmap_v2`, mounted where the 3D game view normally is. */
    const val MAP_IFACE = 1421
    const val MAP_DOCK = 31

    /** What lives at [MAP_DOCK] the rest of the time, and is restored on close. */
    const val GAME_VIEW_IFACE = 1482

    /** `worldmap_v2_ui`, the chrome and the search panel. Opened NOT walkable (flag 0). */
    const val MAP_UI_IFACE = 1422
    const val MAP_UI_DOCK = 800

    /** `worldmap_v2_upsell` at 1422:75 and `worldmap_v2_confirm` at 1422:76. */
    const val UPSELL_IFACE = 698
    const val UPSELL_DOCK = 75
    const val CONFIRM_IFACE = 1612
    const val CONFIRM_DOCK = 76

    /** 1422's Close X - one of the two clicks that precede the reference client close. */
    const val MAP_CLOSE_BUTTON = 111

    /** The confirm dialog's button layer; the other click that precedes the close. */
    const val CONFIRM_BUTTON_LAYER = 11

    /**
     * The slot range the reference client arms on [CONFIRM_BUTTON_LAYER], `IF_SETEVENTS 1612:11 [1.35] mask 2`
     * (11 of 11 map opens across the corpus, and twice in the members observation at t166 and t380).
     * **1-based**, which is what makes the measured `arg2` values 12 and 14 one-based row indices
     * into [Lodestones.slots] rather than zero-based.
     */
    const val CONFIRM_ARM_FROM = 1
    const val CONFIRM_ARM_TO = 35

    /** RUNCLIENTSCRIPTs, in the order reference runs them. */
    const val SCRIPT_BUTTON = 8060   // args [1465:11 hash, -1]
    const val SCRIPT_BUILD = 9332    // args []
    const val SCRIPT_TEARDOWN = 8105 // args [], on the close

    /** Both varcs the reference client writes the map centre into, in the order it writes them. */
    val CENTRE_VARCS = listOf(674, 622)

    /**
     * 1422 components the reference client arms at `[2.2]` mask 2. Eleven of them; **30 is
     * skipped** and that gap is the reference client's, reproduced rather than tidied away.
     */
    val MAP_UI_ARMED_COMPONENTS = listOf(20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31)

    /** Gameframe components the reference client hides while the map is up, and shows again after. */
    val HIDDEN_WHILE_OPEN = listOf(42, 44, 45, 824, 638, 43, 743, 728)

    /**
     * Master switch. Default ON.
     * `-Dopennxt.experiment.worldMap=false` restores "the button does nothing".
     */
    val enabled: Boolean get() = System.getProperty("opennxt.experiment.worldMap") != "false"

    private val open: MutableSet<WorldPlayer> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<WorldPlayer, Boolean>()))

    fun isOpen(player: WorldPlayer): Boolean = player in open

    /** `(plane shl 28) or (x shl 14) or y` - the packing the two varcs carry. */
    fun packCoord(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

    /** The hash the client puts in `arg1`, and script 8060's first argument. */
    fun buttonHash(): Int = (MINIMAP_IFACE shl 16) or MAP_BUTTON

    // -------------------------------------------------------------- the open

    /**
     * Opens the world map, in the reference client's own packet order.
     *
     * Idempotent: a second call while it is open closes it instead, because
     * 1465:11 is a single button and the operator's only way back is that same
     * button until the map's own Close X is routed. That toggle is a DECISION,
     * not a measurement - the reference client's sessions show the button clicked while the
     * map was shut, never while it was open.
     */
    fun open(player: WorldPlayer): Boolean {
        if (!enabled) return false
        if (player in open) {
            close(player, "the world-map button was clicked again")
            return true
        }
        val here = player.entity.location
        val centre = packCoord(here.x, here.y, here.plane)

        // Guarded for the reason [LootWindow.open]'s mount is: this runs on the
        // packet-decode path, and InterfaceManager throws when a player has no
        // root interface. A half-opened map must not also leave `open` claiming
        // it is up, or the close would send IF_CLOSESUBs for nothing.
        try {
        player.client.write(RunClientScript(SCRIPT_BUTTON, arrayOf(buttonHash(), -1)))
        player.client.write(ClientSetvarcLarge(CENTRE_VARCS[0], centre))

        player.interfaces.open(id = MAP_IFACE, parent = GAMEFRAME, component = MAP_DOCK,
            walkable = true, native949 = true)
        player.interfaces.open(id = MAP_UI_IFACE, parent = GAMEFRAME, component = MAP_UI_DOCK,
            walkable = false, native949 = true)
        player.interfaces.open(id = UPSELL_IFACE, parent = MAP_UI_IFACE, component = UPSELL_DOCK,
            walkable = true, native949 = true)

        for (c in MAP_UI_ARMED_COMPONENTS) {
            player.interfaces.events(id = MAP_UI_IFACE, component = c, from = 2, to = 2, mask = 2)
        }
        player.client.write(RunClientScript(SCRIPT_BUILD, emptyArray()))

        player.interfaces.open(id = CONFIRM_IFACE, parent = MAP_UI_IFACE, component = CONFIRM_DOCK,
            walkable = true, native949 = true)
        player.interfaces.events(id = CONFIRM_IFACE, component = CONFIRM_BUTTON_LAYER,
            from = CONFIRM_ARM_FROM, to = CONFIRM_ARM_TO, mask = 2)

        player.client.write(ClientSetvarcLarge(CENTRE_VARCS[1], centre))
        player.interfaces.events(id = MAP_UI_IFACE, component = 54, from = 0, to = 19, mask = 2)
        player.interfaces.hide(id = MAP_UI_IFACE, component = 33, hidden = true)
        for (c in HIDDEN_WHILE_OPEN) player.interfaces.hide(id = GAMEFRAME, component = c, hidden = true)
        } catch (t: Throwable) {
            open.remove(player)
            logger.error(t) { "worldmap ${player.name}: could not open the map; nothing is mounted." }
            return false
        }

        open.add(player)
        logger.info {
            "worldmap ${player.name}: opened, centred on (${here.x},${here.y},p${here.plane}) " +
                "= packed $centre; ${HIDDEN_WHILE_OPEN.size} gameframe component(s) hidden"
        }
        return true
    }

    /**
     * Closes it and puts the 3D game view back.
     *
     * The re-open of [GAME_VIEW_IFACE] is not optional housekeeping: 1421 mounts
     * at the game view's own dock, so a close that only closed would leave the
     * player looking at nothing. The reference client sends it, and so does this.
     */
    fun close(player: WorldPlayer, reason: String) {
        if (!open.remove(player)) return
        player.interfaces.close(id = GAMEFRAME, component = MAP_UI_DOCK, native949 = true)
        player.client.write(RunClientScript(SCRIPT_TEARDOWN, emptyArray()))
        player.interfaces.close(id = GAMEFRAME, component = MAP_DOCK, native949 = true)
        player.interfaces.close(id = MAP_UI_IFACE, component = UPSELL_DOCK, native949 = true)
        player.interfaces.close(id = MAP_UI_IFACE, component = CONFIRM_DOCK, native949 = true)
        player.interfaces.open(id = GAME_VIEW_IFACE, parent = GAMEFRAME, component = MAP_DOCK,
            walkable = true, native949 = true)
        for (c in HIDDEN_WHILE_OPEN) player.interfaces.hide(id = GAMEFRAME, component = c, hidden = false)
        logger.info { "worldmap ${player.name}: closed ($reason)" }
    }

    /**
     * Arms 1465:11 explicitly with the mask the cache already declares for it.
     *
     * NOT called by default and NOT needed if the cache optmask is doing its job
     * - it exists because "the click does not arrive" and "the click arrives and
     * nothing answers" are different faults and this separates them. The mask is
     * [MAP_BUTTON_MASK], the cache's own 62, so arming cannot renumber the ops.
     */
    fun arm(player: WorldPlayer) {
        player.interfaces.events(id = MINIMAP_IFACE, component = MAP_BUTTON,
            from = 65535, to = 65535, mask = MAP_BUTTON_MASK)
    }

    // -------------------------------------------------------------- the clicks

    /**
     * The minimap's world-map button, and the two clicks that close the map.
     * True when this object has dealt with the click.
     *
     * Wire it into `IfButtonNHandler.handle` beside the other content hooks.
     */
    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId == MINIMAP_IFACE && packet.component == MAP_BUTTON) {
            // The op->row rule, read back through the mask this player was armed
            // with rather than assumed. With no explicit arming the cache's
            // optmask 62 is in force and row N == op N, so op 1 is row 1
            // ("Open World Map"); the other four rows are cache options this
            // server does not implement and are refused rather than guessed at.
            val row = ItemOps.rowForOp(player, MINIMAP_IFACE, MAP_BUTTON, packet.buttonOp)
            if (row != OPEN_MAP_ROW - 1) {
                logger.info {
                    "worldmap ${player.name}: 1465:11 op ${packet.buttonOp} maps to row " +
                        "${row?.plus(1) ?: "nothing"}, which is not 'Open World Map' - ignored."
                }
                return true
            }
            return open(player)
        }
        if (packet.interfaceId == MAP_UI_IFACE && packet.component == MAP_CLOSE_BUTTON) {
            close(player, "the map's Close was clicked")
            return true
        }
        if (packet.interfaceId == CONFIRM_IFACE && packet.component == CONFIRM_BUTTON_LAYER) {
            // THE LODESTONE CHOICE. 1612:11 is a container of dynamic children (clientscript 11281
            // builds 0..N and puts "Select" on the last), so `arg2` is the row - not a Yes/No.
            // twice: arg2=14 -> chunk(401,422) = Varrock, arg2=12 -> chunk(336,435) =
            // Seers' Village, both 20 ticks after the click. [Lodestones.handleConfirmClick] owns
            // the slot table and the refusal; this only closes the window, which the reference client does on the
            // tick after the click (t177 and t396) and BEFORE the rebuild lands.
            val teleported = Lodestones.handleConfirmClick(player, packet.arg2)
            close(player, if (teleported) "a lodestone was chosen (slot ${packet.arg2})"
            else "the map's confirm dialog was answered")
            return true
        }
        return false
    }
}
