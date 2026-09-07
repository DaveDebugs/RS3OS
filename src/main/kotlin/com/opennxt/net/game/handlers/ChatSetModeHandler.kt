package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.ChatMode
import com.opennxt.net.game.clientprot.ChatSetMode
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * `CHAT_SETMODE` (949 ClientProt 21) - which channel the next public line goes
 * to. Opcode 35 carries no destination, so **21 is the channel**.
 */
object ChatSetModeHandler : GamePacketHandler<BasePlayer, ChatSetMode> {

    private val logger = KotlinLogging.logger { }

    /** Distinct unnamed modes already reported, so the warning fires once each. */
    private val reported = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** Test hook; the server never calls it. */
    fun resetReported() = reported.clear()

    /** Unnamed modes seen since boot, in the order they were first seen. */
    fun unnamedSeen(): Set<Int> = synchronized(reported) { LinkedHashSet(reported) }

    override fun handle(context: BasePlayer, packet: ChatSetMode) {
        context.chatMode = ChatMode(packet.mode, packet.arg)

        val name = ChatMode.nameOf(packet.mode)
        if (name != null) {
            logger.info { "${context.name} set chat mode ${packet.mode}/$name (arg ${packet.arg})" }
            return
        }

        if (reported.add(packet.mode)) {
            logger.info {
                "${context.name} set chat mode ${packet.mode} (arg ${packet.arg}) - this build names only " +
                    "mode ${ChatMode.CLAN_AFFINED}, so" +
                    "${packet.mode} is recorded as an opaque value and NOT guessed at"
            }
        }
    }
}
