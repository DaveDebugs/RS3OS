package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventMouseClick
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Records the client's raw pointer report (949 opcode 2, EVENT_MOUSE_CLICK) and
 * does nothing else with it.
 *
 * ## It deliberately does not react
 *
 * This packet is a POSITION, not an interaction. Every click that means
 * something already arrives on its own opcode - IF_BUTTON1..10,
 * IF_BUTTON_LABELLED, OPLOC1..6, OPNPC/OPPLAYER/OPOBJ, MOVE_GAMECLICK, or
 * opcode 127 - and those carry the component or the entity the click resolved
 * to. Reacting to a bare (x, y) would mean the server deciding for itself what
 * was under the cursor, which is a hit test this server cannot perform and the
 * client already performed. So the position is stored on [ClientEventState] and
 * nothing reads it.
 *
 * ## Rate limiting, measured rather than guessed
 *
 * Run server-20260827-135435 carried 149 opcode-2 frames in one ~30-minute
 * session (`2x149` in that connection's census summary), and
 * they arrive in bursts of one per physical click. One line per packet is
 * survivable but pointless, so the 1st is logged - that one says the decode
 * works - and then one in [LOG_EVERY], with the running total on the line so
 * the log never implies the rate is lower than it is. Same shape as
 * [EventCameraPositionHandler], which faces the same problem an order of
 * magnitude worse.
 */
object EventMouseClickHandler : GamePacketHandler<BasePlayer, EventMouseClick> {
    private val logger = KotlinLogging.logger { }

    /** Log the 1st, then every 50th. At ~5 clicks/minute that is a line every ten minutes. */
    private const val LOG_EVERY = 50L

    override fun handle(context: BasePlayer, packet: EventMouseClick) {
        val state = ClientEventState.of(context)
        state.mouseX = packet.x
        state.mouseY = packet.y
        state.mouseNotLeftButton = packet.notLeftButton
        state.mousePackets++

        val n = state.mousePackets
        if (n == 1L || n % LOG_EVERY == 0L) {
            logger.info {
                "EVENT_MOUSE_CLICK ${context.name}: (${packet.x},${packet.y}) client px, " +
                    "button=${if (packet.notLeftButton) "NOT-LEFT" else "left"}, " +
                    "dt=${packet.deltaMs}ms${if (packet.deltaSaturated) " (saturated)" else ""} " +
                    "(packet #$n; position only - nothing acts on it)"
            }
        }
    }
}
