package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.EventCameraPosition
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Records the client's reported camera orientation (949 opcode 12).
 *
 * It only RECORDS, into [ClientEventState]. Nothing reads the angles yet, and
 * in particular nothing treats [EventCameraPosition.angle1] as yaw: which of
 * the two is yaw and which is pitch was NOT established from the client (see
 * that class's doc), so acting on either would be acting on a guess.
 *
 * ## Rate limiting is not cosmetic here
 *
 * This packet comes out of the client's per-frame event pump, not off a change
 * edge like EVENT_APPLET_FOCUS. One line per packet would be tens of lines per
 * second per player and would bury everything else in the log - which is the
 * same failure the observed-packet sampler exists to prevent. So the first
 * packet is logged (that one is worth seeing: it says the decode works), and
 * after that one in [LOG_EVERY]. The running total is printed with it, so the
 * log never implies the rate is lower than it is.
 */
object EventCameraPositionHandler : GamePacketHandler<BasePlayer, EventCameraPosition> {
    private val logger = KotlinLogging.logger { }

    /** Log the 1st, then every 512th. At ~50 packets/s that is a line every ten seconds. */
    private const val LOG_EVERY = 512L

    override fun handle(context: BasePlayer, packet: EventCameraPosition) {
        val state = ClientEventState.of(context)
        state.cameraAngle1 = packet.angle1
        state.cameraAngle2 = packet.angle2
        state.cameraPackets++

        val n = state.cameraPackets
        if (n == 1L || n % LOG_EVERY == 0L) {
            logger.info {
                "EVENT_CAMERA_POSITION ${context.name}: angle1=${packet.angle1} angle2=${packet.angle2} " +
                    "(packet #$n; angles are 11-bit, yaw/pitch order NOT established)"
            }
        }
    }
}
