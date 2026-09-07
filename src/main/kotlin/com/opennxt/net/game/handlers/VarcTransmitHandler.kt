package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.VarcTransmit
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.net.game.serverprot.variables.StoreServerpermVarcsAck
import mu.KotlinLogging

/**
 * Receives the client's interface state and acknowledges it.
 */
object VarcTransmitHandler : GamePacketHandler<BasePlayer, VarcTransmit> {
    private val logger = KotlinLogging.logger { }

    /**
     * Re-read per call, matching the ~30 other experiment switches in this repository rather than the
     * eager form this used to have.
     *
     * The eager form is a genuine hazard in an `object`: the property is recorded the first time
     * the class is touched, so a check that does `System.setProperty(...)` and THEN drives a
     * packet through gets whatever was set at class-init - usually nothing - and silently tests
     * the default. A system-property read per inbound packet costs nothing measurable next to
     * decoding one.
     */
    private val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.ui.varcStore") != "false"

    /**
     * Per-player varc state, this session only. Weak-keyed so a logout cannot leak it.
     *
     * THE synchronizedMap WRAPPER DOES NOT MAKE THIS THREAD-SAFE, and it is worth saying so
     * rather than letting the type imply otherwise. [MutableMap.getOrPut] below is a Kotlin
     * extension that does a `get` and then a `put` - two separately synchronised calls with a gap
     * between them - so two threads racing a first packet for the same player could each build a
     * map and one would be discarded. Iterating it would need external synchronisation too.
     *
     * It is safe anyway, for a different reason: every handler runs on `tick-engine` (see
     * [com.opennxt.model.world.WorldPlayer.varpOverrides] for the same argument spelled out), so
     * there is no second thread to race. The wrapper predates that reasoning and is left alone as
     * cheap insurance against the weak-map's own internal state - but it is NOT the reason this
     * is correct, and anyone who later moves packet handling off the tick thread must not read it
     * as one.
     */
    private val store = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<BasePlayer, MutableMap<Int, Any>>()
    )

    /** The interface state this player has pushed so far, or an empty map. */
    fun stateOf(player: BasePlayer): Map<Int, Any> =
        store[player]?.toMap() ?: emptyMap()

    override fun handle(context: BasePlayer, packet: VarcTransmit) {
        if (!enabled) return

        val held = store.getOrPut(context) { LinkedHashMap() }
        val before = held.size

 // WHICH VALUES CHANGED, not just how many ids are new. for one
        // question this handler could not answer: when the player turns an Additional Action
        // Bar off, does the client REPORT it? The bar goes away on screen and comes back at the
        // next login, and the varp side is provably fine (PlayerSave.varps holds the right four
        // values), so the suspect is the varc set we replay - which is only a suspect if the
        // client actually revises it. A count of new IDS cannot see a revision to an existing
        // one, which is exactly the shape of a layout change.
        val changed = LinkedHashMap<Int, Pair<Any?, Any>>()
        for ((id, value) in packet.values) {
            val old = held[id]
            if (old != value) changed[id] = old to value
        }

        held.putAll(packet.values)

        // The ack is what lets the client stop re-sending. See the class note on why it is sent
        // while storage is still in-memory.
        context.client.write(StoreServerpermVarcsAck)

        // One line per packet would be noise on a 1 s timer; this reports only when the set grows,
        // which is the event that actually means something.
        // Report on a CHANGE, not only on growth. The old condition was `held.size != before`,
        // which stayed silent for every packet that revised existing values - i.e. for every
        // layout edit after the first login. That silence was itself a finding waiting to be
        // missed.
        if (changed.isNotEmpty()) {
            logger.info {
                val revised = changed.count { it.value.first != null }
                val sample = changed.entries.take(12).joinToString {
                    "${it.key}:${it.value.first ?: "-"}->${it.value.second}"
                }
                "varcStore ${context.name}: ${changed.size} varc(s) changed from VARC_TRANSMIT " +
                    "(${held.size - before} new id(s), $revised revised; ${packet.values.size} in " +
                    "this packet, allSent=${packet.allSent}); holding ${held.size} for this " +
                    "session. [$sample${if (changed.size > 12) ", ..." else ""}] " +
                    "Persisted to PlayerSave.varcs on logout and replayed at the next login by " +
                    "VarcRestore. -Dopennxt.experiment.ui.varcStore=false disables."
            }
        }
    }
}
