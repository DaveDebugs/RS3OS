package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.generated.IfSetgraphic
import com.opennxt.net.game.serverprot.ifaces.IfSethide
import mu.KotlinLogging

/**
 * The Options menu (interface 1433), and the one button on it that opens a panel.
 */
object OptionsMenu {
    private val logger = KotlinLogging.logger { }

    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.optionsMenu") != "false"

    const val OPTIONS_IFACE = 1433
    const val EDIT_LAYOUT_BUTTON = 22

    /** The layout editor. The reference client mounts it on the gameframe at 1477:752. */
    const val LAYOUT_IFACE = 1475
    const val LAYOUT_PARENT = 1477
    const val LAYOUT_MOUNT = 752

    /** Cache menu option "Save & Exit" on 1475:44, "Close" on 1475:20 - both optmask 2. */
    const val LAYOUT_SAVE_AND_EXIT = 44
    const val LAYOUT_CLOSE = 20

    const val SAVE_CONFIRM_IFACE = 26
    const val SAVE_CONFIRM_MOUNT = 880
    const val SAVE_CONFIRM_BUTTON = 11
    const val VARP_SAVING_LAYOUT = 3813
    const val VARP_LAYOUT_IN_USE = 10096
    val VARP_CUSTOM_SLOT_SAVED = intArrayOf(12578, 12579, 12580, 12581)
    const val SCRIPT_LAYOUT_SAVED = 8743
    const val SCRIPT_LEAVE_EDITOR = 8745
    const val VARCSTR_EDITOR = 8264
    const val CHAT_IFACE = 590

    /** The 81 IF_SETEVENTS rows the reference client arms on 590, read once from login-mounts.tsv. */
    private val chatArmRows: List<IntArray> by lazy {
        val path = com.opennxt.Constants.DATA_PATH.resolve("config").resolve("login-mounts.tsv")
        if (!java.nio.file.Files.isRegularFile(path)) return@lazy emptyList()
        java.nio.file.Files.readAllLines(path).mapNotNull { line ->
            val f = line.trim().split('\t')
            if (f.size >= 6 && f[0] == "events" && f[1].toIntOrNull() == CHAT_IFACE)
                intArrayOf(f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt()) else null
        }
    }
    fun chatArmRowCount(): Int = chatArmRows.size

    /** Players who pressed Save & Exit and have the confirmation open. */
    private val pendingSave = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WorldPlayer, Boolean>())

    /** IF_BUTTON1 26:11 - the save confirmation's button. */
    fun handleSaveConfirm(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != SAVE_CONFIRM_IFACE || packet.component != SAVE_CONFIRM_BUTTON) return false
        if (pendingSave.remove(player) != true || !player.interfaces.isOpened(LAYOUT_IFACE) || !player.interfaces.isOpened(SAVE_CONFIRM_IFACE)) {
            logger.info { "options menu: refused save confirmation for ${player.name} - no Save & Exit is pending" }
            return true
        }
        val w = player.client
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_LAYOUT_IN_USE, 1))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_LAYOUT_IN_USE, 3))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_CUSTOM_SLOT_SAVED[0], 1))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_CUSTOM_SLOT_SAVED[1], 1))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_CUSTOM_SLOT_SAVED[2], 0))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_CUSTOM_SLOT_SAVED[3], 0))
        w.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SAVING_LAYOUT, 0))
        w.write(RunClientScript(script = SCRIPT_LAYOUT_SAVED, args = arrayOf(6)))
        player.interfaces.close(id = LAYOUT_PARENT, component = SAVE_CONFIRM_MOUNT)
        // the exit half
        player.interfaces.close(id = LAYOUT_PARENT, component = LAYOUT_MOUNT)
        w.write(RunClientScript(script = SCRIPT_LEAVE_EDITOR, args = arrayOf(0)))
        w.write(com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall(VARCSTR_EDITOR, ""))
        w.write(IfSethide(InterfaceHash(LAYOUT_IFACE, 34), true))
        w.write(IfSetgraphic(graphic = -1, component = InterfaceHash(LAYOUT_IFACE, 35).hash))
        for (r in chatArmRows) player.interfaces.events(id = CHAT_IFACE, component = r[0], from = r[1], to = r[2], mask = r[3])
        logger.info {
            "options menu: ${player.name} confirmed the layout save (26:11) - varps 10096/12578-12581/3813, script 8743[6], " +
                "closed 1477:880 and 1477:752, script 8745[0], chatbox re-armed (${chatArmRows.size} IF_SETEVENTS on 590). " +
                "Transcribed from the reference client."
        }
        return true
    }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != OPTIONS_IFACE) return false
        if (packet.component != EDIT_LAYOUT_BUTTON) return false

        // The Options menu must be open. Same reasoning as PanelToggles' guard: nothing about a
        // packet naming 1433:22 proves the player pressed ESC first, and the reference client cannot produce
        // this click without the menu on screen.
        if (!player.interfaces.isOpened(OPTIONS_IFACE)) {
            logger.info {
                "options menu: refused Edit Layout Mode for ${player.name} - interface " +
                    "$OPTIONS_IFACE is not open for this player."
            }
            return true
        }

        // The reference client's order, verbatim: the two component overrides land BEFORE the mount.
        // Both name components of 1475 itself, which is not yet open - the client keys
        // its override store on (interface << 16) | component and does not require the
        // interface to be mounted first (IF_SETHIDE's declaration file reads that key
        // convention straight off the handler).
        player.client.write(IfSethide(InterfaceHash(LAYOUT_IFACE, 34), true))
        player.client.write(IfSetgraphic(graphic = -1, component = InterfaceHash(LAYOUT_IFACE, 35).hash))

        player.interfaces.open(
            id = LAYOUT_IFACE, parent = LAYOUT_PARENT, component = LAYOUT_MOUNT,
            walkable = false, native949 = true
        )

        // The three IF_SETEVENTS the reference client sent, in its order. 1475:3's slot range really is
        // 0..2008 on the wire - it is the panel list, not a typo for 0..20.
        player.interfaces.events(id = LAYOUT_IFACE, component = 49, from = 0, to = 20, mask = 2)
        player.interfaces.events(id = LAYOUT_IFACE, component = 3, from = 0, to = 2008, mask = 2)
        player.interfaces.events(id = LAYOUT_IFACE, component = 40, from = 0, to = 20, mask = 2)

        logger.info {
            "options menu: Edit Layout Mode ($OPTIONS_IFACE:${packet.component}) - opened " +
                "$LAYOUT_IFACE at $LAYOUT_PARENT:$LAYOUT_MOUNT and armed 1475:49/3/40, " +
                ". -Dopennxt.experiment.ui.optionsMenu=false disables."
        }
        return true
    }

    /**
     * The two buttons that leave the layout editor.
     *
     * Both arrive WITHOUT the server arming them - 1475:44 and 1475:20 carry cache optmask 2
     * and menu options "Save & Exit" and "Close" - so the clicks were already reaching
     * [com.opennxt.net.game.handlers.IfButtonNHandler] and being logged and dropped. That is
     * why the button looked dead: nothing was ever bound to it.
     *
     * SAVE & EXIT is transcribed. The reference client's response to the click at t=44.716 s, in its order:
     *
     *     IF_CLOSESUB      1477:752
     *     RUNCLIENTSCRIPT  8745  [0]
     *     IF_SETHIDE       1475:34  hidden=1
     *     RUNCLIENTSCRIPT  10623 [6196, 0]
     *     RUNCLIENTSCRIPT  10623 [35804, 0]
     *     RUNCLIENTSCRIPT  10623 [35826, 0]
     *
     * The argument ORDER above is not the wire order. [RunClientScript]'s own codec writes the
     * args backwards after the type descriptor, so `6969...00008bdc...0000297f` is
     * `RunClientScript(10623, [35804, 0])` and not `[0, 35804]`. I decoded it by hand the wrong
     * way round first; reading the encoder is what settled it, and it is written down here
     * because the same trap is waiting for the next person with a hex dump.
     *
     * NOT included, deliberately: the reference client also closed 1477:880 half a second earlier. That is
     * interface 26, a panel the protocol's operator had opened separately at t=44.5 s; it is not
     * part of leaving the layout editor and reproducing it would close a panel the player may
     * have open for their own reasons.
     *
     * CLOSE (1475:20) is NOT transcribed - nothing observed isolates a click on the X. It gets the
     * IF_CLOSESUB alone, which is the minimum a Close button must do, and none of the four
     * clientscripts, because "probably the same" is not evidence. If the X turns out to need
     * them the symptom is a panel that shuts without saving, which is what an X should do anyway.
     */
    fun handleLayoutExit(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LAYOUT_IFACE) return false

        val saveAndExit = when (packet.component) {
            LAYOUT_SAVE_AND_EXIT -> true
            LAYOUT_CLOSE -> false
            else -> return false
        }

        // THE GUARD THAT WAS MISSING. Added minutes after the same guard went onto [handleButton]
        // and [PanelToggles.handleButton] and NOT onto this one - a security pass that covers two
        // of three entry points to the same panel has covered none of them. Without it a bare
        // IF_BUTTON1 naming 1475:44 makes the server send IF_CLOSESUB plus four clientscripts for
        // a panel the player never opened.
        if (!player.interfaces.isOpened(LAYOUT_IFACE)) {
            logger.info {
                "options menu: refused ${if (saveAndExit) "Save & Exit" else "Close"} for " +
                    "${player.name} - interface $LAYOUT_IFACE is not open for this player."
            }
            return true
        }

        if (saveAndExit) {
 //: Save & Exit opens the CONFIRMATION (interface 26); the editor closes on
            // 26:11, in handleSaveConfirm. See SAVE_CONFIRM_IFACE for the measurement and the
 // retraction of the direct close.
            player.client.write(com.opennxt.net.game.serverprot.variables.VarpSmall(VARP_SAVING_LAYOUT, 1))
            player.interfaces.open(
                id = SAVE_CONFIRM_IFACE, parent = LAYOUT_PARENT, component = SAVE_CONFIRM_MOUNT,
                walkable = true, native949 = true
            )
            player.interfaces.events(id = SAVE_CONFIRM_IFACE, component = 6, from = 65535, to = 65535, mask = 2)
            pendingSave[player] = true
            logger.info { "options menu: ${player.name} pressed Save & Exit (1475:44) - opened the save confirmation 26 at 1477:880 (varp 3813 = 1); waiting for 26:11. Transcribed from the reference client." }
            return true
        }
        player.interfaces.close(id = LAYOUT_PARENT, component = LAYOUT_MOUNT)
        pendingSave.remove(player)
        logger.info {
            "options menu: Close ($LAYOUT_IFACE:${packet.component}) - closed $LAYOUT_PARENT:$LAYOUT_MOUNT."
        }
        return true
    }
}
