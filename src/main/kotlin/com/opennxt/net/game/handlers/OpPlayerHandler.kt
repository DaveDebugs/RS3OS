package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpPlayer
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * A click on another player: OPPLAYER1..10 (949 opcodes 130, 79, 140, 103, 59,
 * 96, 26, 122, 80, 54).
 *
 * ONE handler for ten opcodes; [OpPlayer.option] carries which row was chosen.
 *
 * ## What it does, and what it deliberately does not
 *
 *  1. **Says who was clicked, by name**, by resolving the PLAYER_INFO slot
 *     index against the players this server is actually rendering.
 *  2. **Walks the clicking player toward the target's CURRENT tile**, through
 *     [MoveGameClickHandler.walk].
 *
 * It does **NOT** invent gameplay: no trade, no follow (a real follow tracks a
 * moving target every tick - this walks once, to where the target was when the
 * click arrived, and the log says so), no duel, no report, no PvP, no
 * `ContentRegistry` dispatch.
 *
 * ## The index is untrusted, and a self-click is called out
 *
 * The server knows which indices it handed out; an index with no player behind
 * it is a WARN and nothing else. A click on the clicker's own index is logged
 * and does not walk - walking to where you already are is a no-op that would
 * otherwise look like a successful interaction.
 *
 * ## Note on evidence
 *
 * No OPPLAYER frame has ever been observed on the wire - this server has never
 * had two players in one place - so this handler has never run against real
 * bytes. The decode it depends on is proven from the client binary only; see
 * [OpPlayer].
 */
object OpPlayerHandler : GamePacketHandler<WorldPlayer, OpPlayer> {
    private val logger = KotlinLogging.logger { }

    /**
     * The world player occupying PLAYER_INFO slot [index], or null.
     */
    internal fun playerAtIndex(index: Int): WorldPlayer? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        // World.getPlayer is the SAME EntityList slot map PLAYER_INFO indexes,
        // so this asks the exact structure whose numbering the packet is
        // quoting, rather than scanning a parallel collection that could drift
        // from it.
        return world.getPlayer(index)?.controllingPlayer
    }

    override fun handle(context: WorldPlayer, packet: OpPlayer) {
        val target = playerAtIndex(packet.index)

        if (target == null) {
            logger.warn {
                "OPPLAYER${packet.option} ${context.name} clicked PLAYER_INFO index ${packet.index}, which " +
                    "this server has not assigned to any live player. Either the client sent an index we " +
                    "never sent, or this server's PLAYER_INFO slot accounting is wrong. Not walking."
            }
            return
        }

        val loc = target.entity.location
        logger.info {
            "OPPLAYER${packet.option} ${context.name} clicked player '${target.name}' (slot ${packet.index}) " +
                "at (${loc.x},${loc.y},plane ${loc.plane}) option ${packet.option}" +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                " - option NAMES for players are server-set and this server sets none, so the row's meaning " +
                "is not known here"
        }

        if (target === context) {
            logger.info {
                "OPPLAYER${packet.option}: that is ${context.name}'s own PLAYER_INFO slot. Not walking."
            }
            return
        }

        // One walk, to where the target was when the click arrived. Deliberately
        // NOT a follow: a follow re-targets every tick and that is gameplay this
        // pass has no mandate to invent.
        MoveGameClickHandler.walk(context, loc.x, loc.y, "OPPLAYER${packet.option}")
    }
}
