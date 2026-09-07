package com.opennxt.net.game.handlers

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MessagePublic
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * `MESSAGE_PUBLIC` (949 ClientProt 35) - somebody typed a line.
 *
 * VALIDATES BEFORE RELAYING, against the two ranges the client's own tag parser
 * enforces rather than against a convention:
 */
object MessagePublicHandler : GamePacketHandler<WorldPlayer, MessagePublic> {

    private val logger = KotlinLogging.logger { }

    override fun handle(context: WorldPlayer, packet: MessagePublic) {
        if (!PublicChat.enabled) {
            logger.debug { "public chat is disabled (-Dopennxt.experiment.chat=false); dropping $packet" }
            return
        }

        if (packet.colour !in MessagePublic.COLOURS.indices) {
            logger.warn {
                "Dropping public chat from ${context.name}: colour ${packet.colour} is outside " +
                    "0.${MessagePublic.COLOURS.size - 1}"
            }
            return
        }

        if (packet.effect !in 0..MessagePublic.EFFECTS.size) {
            logger.warn {
                "Dropping public chat from ${context.name}: effect ${packet.effect} is outside " +
                    "0.${MessagePublic.EFFECTS.size}"
            }
            return
        }

        PublicChat.relay(context, packet)
    }
}
