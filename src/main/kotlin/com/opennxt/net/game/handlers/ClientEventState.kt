package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import java.util.Collections
import java.util.WeakHashMap

/**
 * Per-player client-side state reported by the EVENT_* packets: window focus
 * (949 opcode 135), camera orientation (949 opcode 12) and the raw pointer
 * report (949 opcode 2, EVENT_MOUSE_CLICK).
 *
 * ## Why this is not three fields on BasePlayer
 *
 * It should be, and WINDOW_STATUS's `windowMode`/`windowWidth`/`windowHeight`
 * are exactly that. This is a FILE OWNERSHIP decision, not a design one: the
 * pass that added these packets did not own `BasePlayer.kt`, two other passes
 * were editing the same tree at the time, and a three-line append to a file
 * someone else is rewriting is how work gets silently lost. Folding these into
 * BasePlayer next to the window fields is a mechanical change and is the right
 * end state.
 *
 * The map is WEAK-KEYED, so a logged-out player's entry disappears with the
 * player instead of pinning it. It is synchronized because packet handling runs
 * on the world tick thread while nothing stops a tool or a future admin command
 * reading it from another.
 */
object ClientEventState {

    /**
     * What the client last told us about itself.
     *
     * Every field starts at -1 / null, meaning NOT REPORTED. A default of 0
     * would be indistinguishable from a measured 0, and "focused = 0" (window
     * in the background) is a real value the client does send.
     */
    data class State(
        /** 1 = our window holds keyboard focus, 0 = it does not, -1 = never reported. */
        var focused: Int = -1,
        /** 11-bit camera angle, or -1 when never reported. Yaw/pitch NOT established - see EventCameraPosition. */
        var cameraAngle1: Int = -1,
        var cameraAngle2: Int = -1,
        /** How many EVENT_CAMERA_POSITION packets this player has sent. Used to rate-limit logging. */
        var cameraPackets: Long = 0,

        /**
         * Last cursor position EVENT_MOUSE_CLICK (949 opcode 2) reported, in
         * CLIENT PIXELS. These are screen coordinates, not world tiles, and
         * nothing in this repository may compare them to a [com.opennxt.model.Tile].
         * -1 means never reported.
         */
        var mouseX: Int = -1,
        var mouseY: Int = -1,
        /**
         * True when the last click carried bit 15 of `buttondelta`. The
         * declaration file's reading of that bit is "not the left button", and
         * it rests on the bit agreeing with opcode 16's raw-input tag (0x88 vs
         * 0x82) on every pair ever recorded - nothing in the binary names it.
         */
        var mouseNotLeftButton: Boolean = false,
        /** How many EVENT_MOUSE_CLICK packets this player has sent. Rate-limits logging. */
        var mousePackets: Long = 0
    )

    private val states: MutableMap<BasePlayer, State> =
        Collections.synchronizedMap(WeakHashMap<BasePlayer, State>())

    /** This player's state, created empty on first use. */
    fun of(player: BasePlayer): State = synchronized(states) { states.getOrPut(player) { State() } }
}
