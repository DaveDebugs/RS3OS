package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * The handler every GENERATED client->server packet gets until a gameplay
 * handler exists for it (see GeneratedRegistrations.installClientHandlers).
 *
 * It does one thing: log the DECODED packet - the data class's own toString,
 * which names every field - so a frame that used to be an observed hex blob now
 * reads as `Oploct(y=3222, x=3200, selobj=995, ctrl=0, selhash=..., loc=..., selsub=-1)`.
 * That is the evidence the next step (a real handler) needs, and it is exactly
 * what InboundCensus.noHandler would have said, minus the "UNHANDLED" alarm,
 * because being received and logged IS this handler's job.
 *
 * Rate limited per class: the first [FULL] occurrences at INFO, then one in
 * [EVERY]. Both are overridable with -Dopennxt.prot.decodedFull /
 * -Dopennxt.prot.decodedEvery so a live test can log everything.
 */
object DecodedPacketLogHandler : GamePacketHandler<BasePlayer, GamePacket> {
    private val logger = KotlinLogging.logger { }

    val FULL: Int = System.getProperty("opennxt.prot.decodedFull")?.toIntOrNull() ?: 25
    val EVERY: Int = (System.getProperty("opennxt.prot.decodedEvery")?.toIntOrNull() ?: 100).coerceAtLeast(1)

    private val seen = ConcurrentHashMap<String, Int>()

    /** Test seam. */
    fun count(clazz: Class<*>): Int = seen[clazz.simpleName] ?: 0

    override fun handle(context: BasePlayer, packet: GamePacket) {
        val name = packet::class.java.simpleName
        val n = seen.merge(name, 1, Int::plus) ?: 1
        if (n <= FULL || n % EVERY == 0) {
            logger.info { "[decoded] $packet (occurrence $n, from ${context.javaClass.simpleName})" }
        }
    }
}
