package com.opennxt.model.entity

import com.opennxt.api.stat.StatContainer
import com.opennxt.model.commands.CommandSender
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.messages.Message
import com.opennxt.model.tick.Tickable
import com.opennxt.net.ConnectedClient
import com.opennxt.net.game.GamePacket
import mu.KotlinLogging

abstract class BasePlayer(var client: ConnectedClient, val name: String): CommandSender, Tickable {
    abstract val interfaces: InterfaceManager
    abstract val stats: StatContainer

    var noTimeouts = 0

    /**
     * Last window state the client reported through WINDOW_STATUS (949 opcode
     * 48), written by [com.opennxt.net.game.handlers.WindowStatusHandler].
     *
     * -1 means the client has not reported yet. Deliberately NOT a plausible
     * default like 800x600: an invented resolution sitting in these fields is
     * indistinguishable from a measured one, and every consumer needs to be
     * able to tell "unknown" from "known".
     */
    var windowMode = -1
    var windowWidth = -1
    var windowHeight = -1

    /**
     * Which channel the next public line goes to, as last reported by
     * `CHAT_SETMODE` (949 ClientProt 21), written by
     * [com.opennxt.net.game.handlers.ChatSetModeHandler].
     *
     * [ChatMode.UNSET] until the client says something, for the same reason
     * [windowMode] starts at -1: an invented default is indistinguishable from a
     * measured one, and exactly ONE mode number has a name on this build.
     */
    var chatMode: ChatMode = ChatMode.UNSET

    private val logger = KotlinLogging.logger { }

    override fun message(message: Message) {
        client.write(message.createPacket())
    }

    override fun message(message: String) {
        client.write(Message.ConsoleMessage(message).createPacket())
    }

    override fun console(message: String) {
        client.write(Message.ConsoleMessage(message).createPacket())
    }

    override fun error(message: String) {
        client.write(Message.ConsoleError(message).createPacket())
    }

    /**
     * Was `return true` for every node and every player - harmless while the only commands were ::anim
     * and ::prottest, and not harmless once a command can conjure items. A world player answers from
     * their rank and their granted powers; anything else (the lobby, a headless harness) keeps the old
     * permissive answer, because nothing reachable from there is gated.
     */
    override fun hasPermissions(node: String): Boolean {
        val world = this as? com.opennxt.model.world.WorldPlayer ?: return true
        return world.hasPower(node)
    }

    override fun tick() {

    }

    fun write(message: GamePacket) {
        client.write(message)
    }
}

/**
 * A `CHAT_SETMODE` (949 ClientProt 21) report: the raw `[mode][arg]` pair, kept
 * as integers.
 */
data class ChatMode(val mode: Int, val arg: Int) {

    /** The build's name for [mode], or null - which is the usual answer. */
    val name: String? get() = nameOf(mode)

    override fun toString(): String = "ChatMode($mode${name?.let { "/$it" } ?: ""}, arg=$arg)"

    companion object {
        /** The one mode this build names, out of the error string at. */
        const val CLAN_AFFINED = 2

        private val NAMES = mapOf(CLAN_AFFINED to "CLAN_AFFINED")

        fun nameOf(mode: Int): String? = NAMES[mode]

        /** No report yet. -1 is not a mode the client can send; the field is a ubyte. */
        val UNSET = ChatMode(-1, 0)
    }
}