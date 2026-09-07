package com.opennxt.net

import mu.KotlinLogging

/**
 * The per-tick ceiling on how many inbound packets one player may cost the world.
 */
object InboundDrainLimit {
    private val logger = KotlinLogging.logger { }

    /**
     * Packets one player may have handled in a single tick.
     *
     * `-Dopennxt.net.inboundPerTick=<n>` overrides it. A value below 1 is refused rather than
     * honoured - a cap of zero is a server that never reads anything, which no operator means.
     */
    val MAX_PER_TICK: Int = run {
        val raw = System.getProperty("opennxt.net.inboundPerTick")?.toIntOrNull()
        if (raw != null && raw >= 1) raw else 128
    }

    /** Backlogs already reported, so a flooding client cannot own the log. */
    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Called when a player still had packets queued after the cap was reached.
     *
     * Once per player per session: a client that trips this is either flooding or badly broken,
     * and in both cases the second line tells nobody anything the first did not.
     */
    fun reportBacklog(name: String, handled: Int, remaining: Int) {
        if (!warned.add(name)) return
        logger.warn {
            "inbound cap: '$name' had more than $MAX_PER_TICK packet(s) ready in one tick " +
                "(handled $handled, $remaining still queued). The rest are KEPT and run next tick - " +
                "nothing is dropped. A real 949 client's worst measured tick is 37, so this is " +
                "either a flood or a broken client. -Dopennxt.net.inboundPerTick=<n> raises the cap. " +
                "Reported once per player per session."
        }
    }

    /** Lets a session be reported again after a reconnect. */
    fun forget(name: String) = warned.remove(name)
}
