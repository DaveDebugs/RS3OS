package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.WindowStatus
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Stores the client's reported window mode and size on the player.
 *
 * Typed on [BasePlayer] rather than WorldPlayer on purpose: the client sends
 * this from the moment it has a window, which is before the world stage, so the
 * lobby needs the same handler. That is also why the fields live on BasePlayer.
 *
 * It only RECORDS. Nothing resizes or re-lays-out an interface off the back of
 * it, because no code here knows what the client does with a mode change, and a
 * guessed reaction would be behaviour nobody can trace to a decision.
 */
object WindowStatusHandler : GamePacketHandler<BasePlayer, WindowStatus> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: WindowStatus) {
        val changed = context.windowMode != packet.mode ||
            context.windowWidth != packet.width ||
            context.windowHeight != packet.height

        context.windowMode = packet.mode
        context.windowWidth = packet.width
        context.windowHeight = packet.height

        // Log on change only. The client repeats this packet, and a per-packet
        // line would be noise saying the same thing.
        if (changed) {
            logger.info {
                "WINDOW_STATUS ${context.name}: mode=${packet.mode} " +
                    "${packet.width}x${packet.height} (trailing=${packet.trailing})"
            }
        }
    }
}
