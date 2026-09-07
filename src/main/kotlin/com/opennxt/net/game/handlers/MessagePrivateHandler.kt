package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.MessagePrivate
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * `MESSAGE_PRIVATE` (949 ClientProt 71) - a private message.
 *
 * There is no friend list, no account directory and no cross-world routing in
 * this repository, so this DOES NOT DELIVER ANYTHING. It records the attempt and tells
 * the sender out loud, because a private message that vanishes with no feedback
 * is indistinguishable from a broken codec - which is exactly the ambiguity the
 * rest of this pass exists to remove.
 *
 * The codec is registered anyway: unregistered opcode 71 is a var-short packet
 * dropped with a warning per occurrence, and that reads as a protocol gap rather
 * than as a missing feature.
 */
object MessagePrivateHandler : GamePacketHandler<BasePlayer, MessagePrivate> {

    private val logger = KotlinLogging.logger { }

    /** Attempts seen since boot. Observable so a check can assert the handler ran. */
    var attempts = 0
        private set

    fun resetCounters() {
        attempts = 0
    }

    override fun handle(context: BasePlayer, packet: MessagePrivate) {
        attempts++
        if (!PublicChat.enabled) {
            logger.debug { "chat is disabled (-Dopennxt.experiment.chat=false); dropping $packet" }
            return
        }

        logger.info {
            "${context.name} -> ${packet.target} (private): \"${packet.text}\" - NOT DELIVERED, this server " +
                "has no friend list or account directory"
        }
        context.console("Private messaging is not implemented on this server; '${packet.target}' was not notified.")
    }
}
