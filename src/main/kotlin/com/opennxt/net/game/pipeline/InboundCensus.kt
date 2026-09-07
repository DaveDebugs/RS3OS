package com.opennxt.net.game.pipeline

import com.opennxt.net.Side
import com.opennxt.net.game.PacketRegistry
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * INBOUND CENSUS - the counterpart to the outbound SEND log, so that a silent
 * run becomes impossible.
 */
object InboundCensus {

    /** Per-frame lines. Absent/false = only the one-line summary. */
    const val SYSTEM_PROPERTY = "opennxt.experiment.net.census"

    /** Per-session summary. Absent = ON; set to false/0/no to silence it. */
    const val SUMMARY_PROPERTY = "opennxt.experiment.net.census.summary"

    /** Payload bytes shown per frame line. The rest is summarised as a count. */
    const val HEX_LIMIT = 64

    /**
     * Per-connection cap on PER-FRAME lines. The summary is never capped: it is
     * one line and it counts every frame regardless of this budget.
     */
    val LINE_BUDGET: Int =
        System.getProperty("opennxt.experiment.net.census.maxLines")?.toIntOrNull() ?: 50_000

    private val logger = KotlinLogging.logger { }

    private val ATTR: AttributeKey<Session> = AttributeKey.valueOf("opennxt-inbound-census")

    @Volatile
    private var frameLines = readFlag(SYSTEM_PROPERTY, default = false)

    @Volatile
    private var summaryLine = readFlag(SUMMARY_PROPERTY, default = true)

    private fun readFlag(property: String, default: Boolean): Boolean {
        val raw = System.getProperty(property) ?: return default
        if (raw.isEmpty()) return true // -Dprop with no value reads as "on"
        return raw.equals("true", true) || raw == "1" || raw.equals("yes", true)
    }

    /** True when per-frame lines are on. */
    val enabled: Boolean
        get() = frameLines

    /** True when the per-session summary is on (the default). */
    val summaryEnabled: Boolean
        get() = summaryLine

    /**
     * Re-reads both properties. Only for tools that flip them inside a running
     * JVM; the server reads them once at class init.
     */
    fun refreshFromSystemProperties(): Boolean {
        frameLines = readFlag(SYSTEM_PROPERTY, default = false)
        summaryLine = readFlag(SUMMARY_PROPERTY, default = true)
        return frameLines
    }

    /** Per-connection tallies. Touched only from that channel's event loop. */
    class Session {
        val counts = Int2IntOpenHashMap()
        val unframeable = Int2IntOpenHashMap()
        var frames = 0L
        var payloadBytes = 0L
        var lines = 0
        var truncated = false
        var fault: String? = null
        val startNanos = System.nanoTime()
    }

    private fun session(channel: Channel): Session {
        val attr = channel.attr(ATTR)
        val existing = attr.get()
        if (existing != null) return existing
        val fresh = Session()
        return attr.setIfAbsent(fresh) ?: fresh
    }

    /**
     * Open a census session for [channel]. Called when the game pipeline is
     * installed, so that a connection which reaches the game stage and then
     * says NOTHING still produces a frames=0 summary. That is the whole point:
     * silence has to be reported, not inferred from an absent line.
     */
    fun begin(channel: Channel) {
        try {
            if (!summaryLine && !frameLines) return
            session(channel)
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    /**
     * Record one framed inbound packet. [payload] is NOT consumed - every read
     * below is absolute.
     */
    fun frame(channel: Channel, side: Side?, opcode: Int, payload: ByteBuf) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            val len = payload.readableBytes()
            s.frames++
            s.payloadBytes += len.toLong()
            s.counts.addTo(opcode, 1)

            if (!frameLines) return
            if (s.lines >= LINE_BUDGET) {
                if (!s.truncated) {
                    s.truncated = true
                    logger.warn {
                        "[census] LINE BUDGET $LINE_BUDGET REACHED on ${channel.remoteAddress()} - per-frame " +
                            "lines stop here. The SUMMARY at disconnect still counts EVERY frame. Raise with " +
                            "-Dopennxt.experiment.net.census.maxLines=N"
                    }
                }
                return
            }
            s.lines++
            val name = PacketRegistry.describeOpcode(side ?: Side.CLIENT, opcode)
            logger.info {
                "[census] RECV op=$opcode name=$name len=$len side=$side conn=${channel.remoteAddress()} " +
                    "hex=${hex(payload)}"
            }
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    /**
     * Record an opcode the framer could not frame at all (no entry in the size
     * table), which is fatal to the connection. It never reaches [frame], so
     * without this the summary would report the silence and not its cause.
     */
    fun unframeable(channel: Channel, side: Side?, opcode: Int) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            s.unframeable.addTo(opcode, 1)
            if (s.fault == null) s.fault = "no size-table entry for opcode $opcode (side=$side)"
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    /** Record why the frame stream died, for the summary line. */
    fun framingFault(channel: Channel, reason: String) {
        try {
            if (!summaryLine && !frameLines) return
            val s = session(channel)
            if (s.fault == null) s.fault = reason
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    /**
     * ONE line describing everything this connection sent. Printed on
     * disconnect, printed even when nothing arrived.
     */
    fun summarise(channel: Channel, side: Side?) {
        try {
            val s = channel.attr(ATTR).getAndSet(null) ?: return
            if (!summaryLine) return
            val durationMs = (System.nanoTime() - s.startNanos) / 1_000_000L
            val histogram = renderHistogram(s.counts, side)
            val extra = StringBuilder()
            if (!s.unframeable.isEmpty()) {
                extra.append(" unframeable=").append(renderHistogram(s.unframeable, side))
            }
            s.fault?.let { extra.append(" fault='").append(it).append('\'') }
            if (s.truncated) extra.append(" perFrameLinesTruncatedAt=").append(LINE_BUDGET)
            val silence =
                if (s.frames == 0L) "  -- NOTHING ARRIVED ON THIS CONNECTION AFTER THE GAME PIPELINE WAS INSTALLED"
                else ""
            logger.info {
                "[census] INBOUND SUMMARY conn=${channel.remoteAddress()} side=$side frames=${s.frames} " +
                    "payloadBytes=${s.payloadBytes} durationMs=$durationMs " +
                    "distinctOpcodes=${s.counts.size} opcodes=$histogram$extra$silence"
            }
        } catch (t: Throwable) {
            reportSelfFailure(t)
        }
    }

    /** `{102(IF_BUTTON_LABELLED)x4, 0(NO_TIMEOUT)x49}`, busiest opcode first. */
    private fun renderHistogram(counts: Int2IntOpenHashMap, side: Side?): String {
        if (counts.isEmpty()) return "{}"
        val entries = counts.keys.toIntArray().toTypedArray()
        entries.sortWith(compareByDescending<Int> { counts.get(it) }.thenBy { it })
        val sb = StringBuilder("{")
        for ((i, op) in entries.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append(op).append('(').append(PacketRegistry.describeOpcode(side ?: Side.CLIENT, op))
                .append(")x").append(counts.get(op))
        }
        return sb.append('}').toString()
    }

    // ---------------------------------------------------------------- unhandled

    /** Occurrences per decoded-but-unhandled packet class, for the rate limit. */
    private val noHandlerSeen = ConcurrentHashMap<String, Int>()

    /**
     * A packet that framed, decoded, reached the incoming queue and found NO
     * handler.
     *
     * This is the third and last place an inbound frame could vanish without a
     * word. It used to be `logger.info { "TODO: Handle incoming $packet" }` in
     * WorldPlayer.handleIncomingPackets - which is at least a line, but it is
     * an INFO line phrased as a developer's note, does not name the class in a
     * way anything can grep for, and is unbounded. A world session that
     * receives 50 unhandled NO_TIMEOUTs a minute drowns the log with it.
     *
     * Rate limited per class the same way ConnectedClient rate limits its
     * unregistered-opcode warning: first three, then every 500th. Returns the
     * occurrence number so a test can assert the limiter, not just the line.
     */
    fun noHandler(packet: Any): Int {
        val name = packet::class.java.simpleName ?: packet::class.java.name
        val seen = noHandlerSeen.merge(name, 1, Int::plus) ?: 1
        if (seen <= 3 || seen % 500 == 0) {
            logger.warn {
                "[census] DECODED BUT UNHANDLED: $name - it framed, it decoded and NO GamePacketHandler is " +
                    "registered for it, so the server received it and did nothing (occurrence $seen). $packet"
            }
        }
        return seen
    }

    /** Test seam: forget the per-class occurrence counts. */
    fun resetNoHandlerCounts() = noHandlerSeen.clear()

    // ------------------------------------------------------------------ helpers

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Lowercase space-separated hex of [buf]'s readable bytes, capped at
     * [HEX_LIMIT]. ABSOLUTE getters only: this runs before the codec decodes
     * the same buffer, so moving the reader index would corrupt the packet the
     * census is supposed to be silently watching.
     */
    fun hex(buf: ByteBuf): String {
        val n = buf.readableBytes()
        val shown = if (n < HEX_LIMIT) n else HEX_LIMIT
        val base = buf.readerIndex()
        val sb = StringBuilder(shown * 3 + 24)
        for (i in 0 until shown) {
            if (i > 0) sb.append(' ')
            val v = buf.getByte(base + i).toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
        }
        if (n > shown) sb.append(" ...(").append(n - shown).append(" more)")
        return sb.toString()
    }

    /**
     * The census failing is a bug in the census, and it must say so ONCE rather
     * than either throwing into the pipeline or hiding.
     */
    @Volatile
    private var selfFailureReported = false

    private fun reportSelfFailure(t: Throwable) {
        if (selfFailureReported) return
        selfFailureReported = true
        logger.error(t) { "[census] the inbound census itself threw; census output is unreliable for this run" }
    }
}
