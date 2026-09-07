package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.RunClientScript
import mu.KotlinLogging

/**
 * The Display Windows list inside Edit Layout Mode - ticking a panel on or off.
 */
object PanelToggles {
    private val logger = KotlinLogging.logger { }

    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.panelToggles") != "false"

    /** The layout editor's Display Windows list. Slot carries the panel id. */
    const val LAYOUT_IFACE = 1475
    const val DISPLAY_WINDOWS_LIST = 3

    /** The reference client re-opens the main bar's own frame alongside every additional bar. */
    private const val MAIN_BAR_IFACE = 1430
    private const val MAIN_BAR_DOCK = 70
    private const val GAMEFRAME = 1477

    /**
     * panel id -> (interface, dock component). Docks are the 3505 seats, the same ones
     * [com.opennxt.model.entity.player.InterfaceManager.hudRows] resolves the login mounts to,
     * and they agree with the reference client's own IF_OPENSUB parents above.
     */
    val BARS: Map<Int, Pair<Int, Int>> = linkedMapOf(
        1032 to (1670 to 75),
        1033 to (1671 to 80),
        1034 to (1672 to 85),
        1035 to (1673 to 90),
    )

    /**
     * "Additional Action Bar N (bar M in game)" - both numbers, because both are real and they
     * differ by one.
     *
     * The CACHE names interfaces 1670-1673 "Additional Action Bar 1..4", and that is the label on
     * the Edit Layout Mode checkbox, so a message using anything else would not match what the
     * player is clicking. The GAME badges those same bars 2..5, because the main action bar -
     * interface 1430, the one carrying lifepoints, prayer and summoning - is bar 1 and is always
     * open, unconditionally, exactly as the reference client opens it at 1477:70.
     */
    fun describe(panel: Int): String =
        "Additional Action Bar ${panel - 1031} (bar ${panel - 1030} in game)"

    /**
     * panel 1032 -> varp 10092, and so on up.
     *
     * Guarded because it is public and the arithmetic is happy to extrapolate: `varpFor(1013)`
     * used to return 10073, which is a real varp id belonging to something else entirely. Every
     * caller today is constrained to [BARS] or to a [panelForBarMount] result, so this was latent
     * rather than live - but a silent wrong varp is precisely the class of bug this file exists
     * to have fixed.
     */
    fun varpFor(panel: Int): Int {
        require(panel in BARS) { "panel $panel is not an Additional Action Bar (expected one of ${BARS.keys})" }
        return 10092 + (panel - 1032)
    }

    /** True when this player has the bar behind [panel] switched on. */
    fun isBarEnabled(player: WorldPlayer, panel: Int): Boolean =
        player.varpOverride(varpFor(panel)) == 1

    /**
     * The panel id for an (interface, dock) pair that is one of the four bars, or null.
     *
     * Used by [Replay949] to recognise a bar open inside the protocol, so an observation recorded
     * from an account with four bars cannot hand four bars to a player who chose one.
     */
    fun panelForBarMount(iface: Int, dock: Int): Int? =
        BARS.entries.firstOrNull { it.value.first == iface && it.value.second == dock }?.key

    /** Every bar this player currently has on. What [ActionBarArm.arm] must be given. */
    fun enabledPanels(player: WorldPlayer): List<Int> = BARS.keys.filter { isBarEnabled(player, it) }

    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LAYOUT_IFACE) return false
        if (packet.component != DISPLAY_WINDOWS_LIST) return false

        // ---------------------------------------------------------------------------------
 // TRUST BOUNDARY. after a security pass; neither guard was here when
        // this handler was written, and both are cheap.
        //
        // (a) THE PANEL MUST ACTUALLY BE OPEN. `packet` is client-supplied and nothing about
        //     arriving on 1475:3 proves the player ever opened Edit Layout Mode - the whole
        // OptionsMenu -> 1433:22 -> 1475 route can simply be skipped. The reference client cannot send this
        //     without the panel, so refusing it costs a legitimate client nothing. `isOpened` is
        //     the same guard GlobalCloseWiring and ParentWindows already use.
        //
        // (b) ONE TOGGLE PER TICK. This is the expensive-request check, and the amplification is
        //     not small: one 9-byte IF_BUTTON1 makes the server send 64 common + 28 per enabled
        //     bar + 3 = up to 179 packets, a ~179x fan-out with no cost to the sender. These are
        //     human clicks on a checkbox; more than one per 600 ms tick is not a player. The cap
        //     drops the extra rather than disconnecting, because a double-click from a real
        //     client is a plausible cause and losing the second one is harmless.
        // ---------------------------------------------------------------------------------
        if (!player.interfaces.isOpened(LAYOUT_IFACE)) {
            logger.info {
                "panelToggle: refused a toggle for ${player.name} - interface $LAYOUT_IFACE " +
                    "(Edit Layout Mode) is not open for this player. The click cannot have come " +
                    "from the panel it claims to be on."
            }
            return true
        }
        val panel = packet.arg2
        val seat = BARS[panel]
        if (seat == null) {
            // NOT silently swallowed. An unhandled toggle that returns false falls through to
            // IfButtonNHandler's generic log, which prints the raw component and slot; this line
            // adds the one thing that log cannot know - that the slot is a PANEL id, and which
            // panel. See the scope note in this class's KDoc.
            logger.info {
                "panelToggle: display window $panel is not one this server can open or close. " +
                    "Only the four Additional Action Bars (1032-1035) are wired; the rest need a " +
                    "observation of the reference client answering that tick. See PanelToggles' KDoc."
            }
            return false
        }

        // THE THROTTLE GOES HERE, not above the whitelist, and that placement is the fix to a
        // bug the first version had. Sitting before `BARS[panel]` it was consumed by ANY click on
        // 1475:3 - including the panel ids this server does not handle, which cost one log line
        // and nothing else. So a client spamming panel 1038 burned the budget and locked out the
        // player's own next real toggle for that tick, while the expensive path it was meant to
        // protect was never involved.
        if (!player.allowPanelToggleThisTick()) {
            logger.info {
                "panelToggle: dropped a second bar toggle from ${player.name} in one tick - each " +
                    "one costs up to 179 outbound packets and these are checkbox clicks."
            }
            return true
        }

        val (iface, dock) = seat
        val varp = varpFor(panel)
        val turningOn = !isBarEnabled(player, panel)

        // The varp FIRST, so that a failure to open cannot leave the stored state claiming the
        // bar is on. setVarpOverride both sends it and makes it durable - see WorldPlayer.
        player.setVarpOverride(varp, if (turningOn) 1 else 0)

        if (turningOn) {
            // The reference client sends the main bar's frame with every additional bar.
            player.interfaces.open(
                id = MAIN_BAR_IFACE, parent = GAMEFRAME, component = MAIN_BAR_DOCK,
                walkable = true, native949 = true
            )
            player.interfaces.open(
                id = iface, parent = GAMEFRAME, component = dock,
                walkable = true, native949 = true
            )
            player.client.write(RunClientScript(script = 8310, args = arrayOf(panel)))
        } else {
 // THE OFF PATH, TRANSCRIBED from
 // at t=95.747 (bar 4), 106.550 (bar 3) and
            // 114.348 (bar 2). It is a MIRROR of the ON path, not an absence:
            //
            //     VARP_SMALL      10095 = 0
            //     IF_OPENSUB      1430 -> 1477:70          the main bar, exactly as on the way in
            //     IF_CLOSESUB     1477:90                  the bar's own dock
            //     RUNCLIENTSCRIPT 8320 [1035]              8320 on the way OUT, 8310 on the way IN
            //     139 x IF_SETEVENTS                       the whole block again, cumulative
            //     varcs, and RUNCLIENTSCRIPT 15997
            //
            // sent the varp and NOTHING else, and said so in a long comment. That was an
            // INFERENCE from seven display windows which are NOT action bars (panels 1038, 1014,
            // 1024, 1013, 1053, 1045, 1051), every one of which reference answers with zero frames.
            // The inference was reasonable and it was WRONG. Bars are special on the way out for
            // the same reason they are special on the way in: their contents come from the server.
            // The lesson is not "trust the first guess" - the first version of this branch had an
            // IF_CLOSESUB by luck, not by evidence. The lesson is that seven siblings do not
            // settle a case whose subject is already known to behave unlike its siblings.
            player.interfaces.open(
                id = MAIN_BAR_IFACE, parent = GAMEFRAME, component = MAIN_BAR_DOCK,
                walkable = true, native949 = true
            )
            // native949 = true IS LOAD-BEARING, and its absence was a live defect from 16:19 to
 // 16:35 on. InterfaceManager.close runs the component through mountFor
            // unless told otherwise, and every dock here is also a hudRows `from` key, so the
            // remap fired on the way out and closed the NEXT bar along:
            //
            //     untick bar 1  close(1477,75) -> 80   closed bar 2's dock
            //     untick bar 2  close(1477,80) -> 85   closed bar 3's dock
            //     untick bar 3  close(1477,85) -> 90   closed bar 4's dock
            //     untick bar 4  close(1477,90) -> 95   THE MINIMAP's dock
            //
            // close() returns silently when nothing sits at the translated slot, so the symptom
            // was either "the wrong panel disappeared" or "nothing happened and no packet was
            // sent" - never an error. The `open` two lines above had the flag from the start;
            // this line was written in the same edit and did not.
            player.interfaces.close(id = GAMEFRAME, component = dock, native949 = true)
            player.client.write(RunClientScript(script = 8320, args = arrayOf(panel)))
        }

        // AND THEN THE ARMING, which is 83 of the 99 frames the reference client sends here and was the whole
        // of what the first version of this handler missed. Opening the interface puts it on
        // screen; without this it responds to nothing. Passed the FULL enabled set, not the bar
        // just toggled - the reference client re-arms every open bar on each tick. See ActionBarArm.
        //
        // ON *AND* OFF. This was briefly ON-only, on the same withdrawn inference as the close
        // above. The reference client's untick carries 139 IF_SETEVENTS - the block rebuilt for the bars that
        // REMAIN - so the arm belongs on both branches. enabledPanels() is read AFTER the varp is
        // written, so on an OFF it already excludes the bar just removed.
        ActionBarArm.arm(player, enabledPanels(player))

        // The two branches do genuinely different amounts, so the line says which - a message
        // that names a dock the OFF path never touches is the kind of small untruth that gets
        // believed later.
        logger.info {
            "panelToggle: ${describe(panel)} (panel $panel) " +
                "${if (turningOn) "ON" else "OFF"} for ${player.name} - varp $varp = " +
                "${if (turningOn) 1 else 0}" +
                (if (turningOn) ", opened $iface at $GAMEFRAME:$dock"
                 else ", closed $GAMEFRAME:$dock") +
                " and re-armed the bar block (, both directions)." +
                " Persisted in PlayerSave.varps and re-applied at the next login."
        }
        return true
    }

    /**
     * Open, at login, exactly the bars this player has switched on - and send all four varps so
     * the Edit Mode checkboxes render the truth whether they are on or off.
     */
    fun sendLoginState(player: WorldPlayer) {
        if (!enabled) return
        var opened = 0
        for ((panel, seat) in BARS) {
            val on = isBarEnabled(player, panel)
            player.setVarpOverride(varpFor(panel), if (on) 1 else 0, store = false)
            if (!on) continue
            val (iface, dock) = seat
            player.interfaces.open(
                id = iface, parent = GAMEFRAME, component = dock,
                walkable = true, native949 = true
            )
            player.client.write(RunClientScript(script = 8310, args = arrayOf(panel)))
            opened++
        }
        // Arm at login too, and only if something is open. A player with no additional bars gets
        // nothing here, which is what the reference client's fresh account looks like.
        if (opened > 0) ActionBarArm.arm(player, enabledPanels(player))
        logger.info {
            "panelToggle: $opened of ${BARS.size} Additional Action Bar(s) restored for " +
                "${player.name} from PlayerSave.varps. The reference client opens none on a fresh account; " +
                "this server used to open all four. -Dopennxt.experiment.ui.panelToggles=false " +
                "restores that."
        }
    }
}
