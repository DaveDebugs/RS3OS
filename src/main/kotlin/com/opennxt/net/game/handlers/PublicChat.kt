package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MessagePublic
import com.opennxt.net.game.serverprot.MessagePublicOut
import com.opennxt.util.HuffmanCodec
import mu.KotlinLogging

/**
 * Turning one received line into what every viewer sees.
 *
 * TWO SEPARATE MECHANISMS, both driven from here, because they do different jobs
 * and either one alone looks broken:
 */
object PublicChat {

    private val logger = KotlinLogging.logger { }

    /** `-Dopennxt.experiment.chat=false` disables inbound handling and outbound sends. */
    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.chat") != "false"

    /**
     * How many lines this server has relayed, and how many viewer-copies it put
     * on the wire. Observable so can assert on
     * the fan-out instead of on a log line.
     */
    var linesRelayed = 0
        private set
    var copiesSent = 0
        private set

    fun resetCounters() {
        linesRelayed = 0
        copiesSent = 0
    }

    /**
     * Whether [viewer] currently has [speakerIndex] in its player registry.
     *
     * The client-side test this mirrors is `[[client+0x19910]+0x10] + index*8`
     */
    fun canSee(viewer: WorldPlayer, speakerIndex: Int): Boolean {
        if (speakerIndex < 1 || speakerIndex >= viewer.viewport.localPlayers.size) return false
        return viewer.viewport.localPlayers[speakerIndex] != null
    }

    /**
     * Relays [packet] from [speaker]. Returns the number of viewers written to,
     * or 0 if nothing was sent - so a caller can tell "nobody could see them"
     * from "chat is off" only by asking [enabled] as well, which is why both are
     * logged here rather than inferred.
     */
    fun relay(speaker: WorldPlayer, packet: MessagePublic): Int {
        if (!enabled) return 0

        val index = speaker.entity.index
        if (index < 1) {
            // A player whose entity is not in the world's EntityList yet has no
            // index, and index 0 is not addressable by the client's registry.
            logger.warn { "Dropping public chat from ${speaker.name}: entity index is $index" }
            return 0
        }

        if (HuffmanCodec.instance() == null) {
            logger.warn { "Dropping public chat from ${speaker.name}: no huffman table to re-encode it with" }
            return 0
        }

        // Overhead text: set once on the speaker, fanned out by the player-info
        // encoder, cleared by World.tick. The SAY block class is called, never
        // edited - it owns its own NUL rule.
        PlayerUpdates.say(speaker.entity, packet.text)

        var sent = 0
        OpenNXT.world.forEachPlayer { viewer ->
            if (!canSee(viewer, index)) return@forEachPlayer
            viewer.write(
                MessagePublicOut(
                    index = index,
                    colour = packet.colour,
                    effect = packet.effect,
                    // Row 0 of the crown table - sprite id -1,
                    // i.e. no crown. This server has no rank model to read.
                    rights = 0,
                    text = packet.text
                )
            )
            sent++
        }

        linesRelayed++
        copiesSent += sent
        logger.info {
            "public chat from ${speaker.name} (index $index) -> $sent viewer(s): " +
                "colour=${packet.colour}${packet.colourName()?.let { "/$it" } ?: ""} " +
                "effect=${packet.effect}${packet.effectName()?.let { "/$it" } ?: ""} \"${packet.text}\""
        }
        return sent
    }
}
