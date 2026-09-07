package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.worldlist.WorldList
import com.opennxt.net.game.clientprot.WorldlistFetch
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Answers WORLDLIST_FETCH (949 opcode 125) with WORLDLIST_FETCH_REPLY (server
 * 172), in the LOBBY and - since - in the WORLD.
 */
object WorldlistFetchHandler : GamePacketHandler<BasePlayer, WorldlistFetch> {
    private val logger = KotlinLogging.logger { }

    /** `-Dopennxt.experiment.worldlist.world=false` to stop replying in the world. */
    const val WORLD_REPLY_PROPERTY = "opennxt.experiment.worldlist.world"

    /**
     * Read once, at class init, like every other experiment flag here. False
     * only when the property is explicitly set to something that is not "true".
     */
    val worldReplyEnabled: Boolean =
        System.getProperty(WORLD_REPLY_PROPERTY)?.toBoolean() ?: true

    override fun handle(context: BasePlayer, packet: WorldlistFetch) {
        if (context is LobbyPlayer) {
            context.worldList.handleRequest(packet.checksum, context.client)
            return
        }

        if (!worldReplyEnabled) {
            logger.warn {
                "WORLDLIST_FETCH from ${context.name} in the WORLD stage: consumed and NOT answered, " +
                    "because -$WORLD_REPLY_PROPERTY is off. The client's world list stays empty."
            }
            return
        }

        val list = WorldList(WorldList.demoEntries())
        list.handleRequest(packet.checksum, context.client)
        logger.info {
            "WORLDLIST_FETCH from ${context.name} in the WORLD stage: answered with " +
                "${list.entries.size} world(s); the client's checksum was ${packet.checksum}, " +
                "ours is ${list.hashCode()} (equal = the client already had it and got the " +
                "no-change reply). Turn this off with -$WORLD_REPLY_PROPERTY=false."
        }
    }
}
