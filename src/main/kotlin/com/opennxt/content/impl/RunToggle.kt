package com.opennxt.content.impl

import com.opennxt.model.entity.movement.RunEnergy
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.generated.UpdateRunenergy
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging

/**
 * The run orb: the inbound click, the outbound varp, and the outbound reserve.
 */
object RunToggle {
    private val logger = KotlinLogging.logger { }

    /** `toplevel_v2_run_energy`. */
    const val ORB_INTERFACE = 326

    /** The only component of 326 with a menu; its op 6 is "Toggle run mode". */
    const val ORB_COMPONENT = 1

    /** The menu row, 0-based index 5 in `opts`, i.e. IF_BUTTON**6**. */
    const val TOGGLE_OP = 6

    /**
     * Would this frame toggle run? Pure, so a check can ask without a `WorldPlayer`.
     *
     * Deliberately NOT `packet.mid == -1`-tolerant in either direction: the orb has no sub-slots,
     * so the trailing fields carry nothing here, and matching on them would be matching on a field
 * this server has never seen a value of for this component.
     */
    fun install() {
        logProvenanceOnce()
    }

    @Volatile
    private var provenanceLogged = false

    /**
     * Print [RunEnergy.PROVENANCE] once per process.
     *
     * [install] is the boot-time hook, in the shape `Skilling.install()` and `Dialogue.install()`
     * use, for `OpenNXT.reloadContent` to call. It is ALSO called from [sendLogin], and that is
     * deliberate rather than redundant: this object was written without touching `OpenNXT.kt`
     * (another stream owns it), so until the install line is wired the provenance would otherwise
     * never reach a log at all - and a rate whose provenance is only in a KDoc is a rate nobody
     * running the server can audit. The guard makes the double call harmless; wiring `install()`
     * only moves the line earlier.
     */
    private fun logProvenanceOnce() {
        if (provenanceLogged) return
        provenanceLogged = true
        logger.info { RunEnergy.PROVENANCE }
    }

    fun isToggleFrame(interfaceId: Int, component: Int, buttonOp: Int): Boolean =
        interfaceId == ORB_INTERFACE && component == ORB_COMPONENT && buttonOp == TOGGLE_OP

    /**
     * The IF_BUTTON2..10 hook. True when this frame was ours and must not fall through.
     *
     * Wire it into `IfButtonNHandler.handle` beside the other content hooks.
     */
    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!isToggleFrame(packet.interfaceId, packet.component, packet.buttonOp)) return false
        set(player, !player.entity.runEnergy.toggled, "orb click 326:1 op 6")
        return true
    }

    /**
     * Set the toggle and tell the client, in that order.
     *
     * Sends the varp UNCONDITIONALLY rather than only on a change. The client's own listener fires
     * on value change only (measured, run 113625 - see the cs2 skill), so an unchanged re-send
     * costs one 4-byte frame and redraws nothing; but a change this server thinks is a no-op
     * because its own bookkeeping drifted would leave the orb lying, and a lying orb is the defect
     * this whole module exists to close.
     *
     * @return the new toggle state.
     */
    fun set(player: WorldPlayer, on: Boolean, why: String): Boolean {
        val energy = player.entity.runEnergy
        val before = energy.toggled
        energy.setToggled(on)
        sendVarp(player)
        logger.info {
            "run toggle: ${if (before) "run" else "walk"} -> ${if (on) "run" else "walk"} " +
                "($why); reserve ${energy.tenths} tenths = ${energy.displayed}%"
        }
        return on
    }

    /** Writes varp [RunEnergy.RUN_VARP] with the player's current toggle. */
    fun sendVarp(player: WorldPlayer) {
        player.client.write(VarpSmall(RunEnergy.RUN_VARP, if (player.entity.runEnergy.toggled) 1 else 0))
    }

    /**
     * The login send: the reserve the player actually has, and the toggle they actually left on.
     *
     * Called from `WorldPlayer.tick`'s existing `runEnergyCountdown` block, which is where the reference client's
     * position for UPDATE_RUNENERGY was already reproduced. [RunEnergy.markSent] records it so the
     * per-tick sender does not immediately repeat it.
     */
    fun sendLogin(player: WorldPlayer) {
        logProvenanceOnce()
        val energy = player.entity.runEnergy
        val value = energy.displayed
        player.client.write(UpdateRunenergy(energy = value))
        energy.markSent(value)
        sendVarp(player)
    }

    /**
     * One tick of the model for one player, and the frame it produces.
     *
     * @param ranThisTick whether the avatar advanced two tiles - `Movement.currentSpeed == RUN`.
     * @return the value put on the wire, or null when the client already had it.
     */
    fun tick(player: WorldPlayer, ranThisTick: Boolean): Int? {
        val energy = player.entity.runEnergy
        energy.tick(ranThisTick)
        val send = energy.takeSend() ?: return null
        player.client.write(UpdateRunenergy(energy = send))
        return send
    }
}
