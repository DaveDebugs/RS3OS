package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventAppletFocus
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Records whether the client's window has keyboard focus (949 opcode 135).
 *
 * It only RECORDS, into [ClientEventState]. Nothing pauses, throttles, logs the
 * player out or changes anything else when focus is lost, because no evidence in
 * this repository says what the real server does with this and a guessed reaction is
 * behaviour nobody can trace back to a decision. The same rule
 * [WindowStatusHandler] follows.
 *
 * Typed on [BasePlayer]: the client sends this as soon as it has a window, which
 * is before the world stage, so the lobby would use the very same object.
 *
 * The packet is edge-triggered by the client (see [EventAppletFocus]), so every
 * arrival is a genuine change and logging each one is a handful of lines per
 * session, not a flood. It is logged only when the value actually differs from
 * what is stored anyway, so a client that repeats it cannot repeat the log.
 */
object EventAppletFocusHandler : GamePacketHandler<BasePlayer, EventAppletFocus> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: EventAppletFocus) {
        val state = ClientEventState.of(context)
        val changed = state.focused != packet.focused
        state.focused = packet.focused

        if (changed) {
            logger.info {
                "EVENT_APPLET_FOCUS ${context.name}: window ${if (packet.hasFocus) "GAINED" else "LOST"} " +
                    "keyboard focus (raw ${packet.focused})"
            }
        }
    }
}
