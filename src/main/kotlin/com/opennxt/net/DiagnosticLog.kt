package com.opennxt.net

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.net.handshake.HandshakeType
import com.opennxt.net.js5.Js5Session
import com.opennxt.net.js5.packet.Js5Packet
import com.opennxt.net.js5.packet.Js5PacketCodec
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.AttributeKey
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * FIRST-CONTACT DIAGNOSTIC RECORDER.
 */
object DiagnosticLog {

    /** Property that turns the recorder on. Absent/false = completely inert. */
    const val SYSTEM_PROPERTY = "opennxt.diag"

    /** Bytes shown per hex dump. The rest is summarised as "(N more bytes)". */
    const val DUMP_LIMIT = 256

    /**
     * The build this run EXPECTS the client to announce, measured from the
     * binary rather than discovered during the run.
     *
     * Having it here turns the headline of this log from an open question into
     * a checked prediction: a mismatch would mean the version the client
     * REPORTS and the version it SPEAKS are different things, which is a real
     * finding.
     *
     * It is read from the client binary that is actually configured to launch.
     * It used to be the literal 947, measured by `tools/clientinfo.py` from the
     * `"Client Version: %i-%i"` call site and the `RS2Engine-947-NXT-3` tag -
     * correct at the time. After the move to 949 that constant made the
     * recorder announce "PREDICTION FAILED: the binary reads 947, the wire
     * announced 949" on a run where client and server agreed perfectly: a stale
     * literal manufacturing a false finding, in the one component whose job is
     * to tell the truth about builds. Overridable if the binary cannot be read:
     *
     *     -Dopennxt.diag.predictedBuild=949
     */
    val PREDICTED_CLIENT_BUILD: Int =
        System.getProperty("opennxt.diag.predictedBuild")?.toIntOrNull()
            ?: readBuildFromStagedClient()
            ?: OpenNXT.config.build

    /**
     * The build number in the PE version resource of the client this server is
     * configured to serve. Null if it cannot be read, in which case the caller
     * falls back to the configured build rather than to a stale literal.
     */
    private fun readBuildFromStagedClient(): Int? = try {
        val exe = Constants.CLIENTS_PATH
            .resolve(OpenNXT.config.build.toString())
            .resolve("win64").resolve("original").resolve("rs2client.exe")
        if (!java.nio.file.Files.exists(exe)) null else {
            // FileVersion sits in the PE resource as UTF-16LE; "949-5" etc.
            val text = String(java.nio.file.Files.readAllBytes(exe), Charsets.UTF_16LE)
            val i = text.indexOf("FileVersion")
            if (i < 0) null else Regex("(\\d{3,4})").find(text, i)?.groupValues?.get(1)?.toIntOrNull()
        }
    } catch (t: Throwable) {
        null
    }

    /** Full log lines per opcode before an unregistered opcode is only counted. */
    const val UNKNOWN_OPCODE_SAMPLES = 3

    /** Full JS5 request/serve lines per connection before switching to counting. */
    const val JS5_LINE_LIMIT = 2000

    /**
     * Outbound recording budget, per connection, for everything except JS5.
     *
     * 8 KB and 200 writes comfortably covers the whole login exchange that was
     * previously invisible (measured: 8,028 bytes) without any risk of a chatty
     * game session filling the disk. Both are overridable at run time:
     *
     *     -Dopennxt.diag.outDumpBytes=32768 -Dopennxt.diag.outWrites=1000
     */
    val OUT_DUMP_LIMIT: Int = System.getProperty("opennxt.diag.outDumpBytes")?.toIntOrNull() ?: 8192
    val OUT_WRITE_LIMIT: Int = System.getProperty("opennxt.diag.outWrites")?.toIntOrNull() ?: 200

    /**
     * Named SEND lines per connection.
     *
     * Was 500, which truncated the first run that used it - and truncated the
     * interesting part. The login burst spent all 500 lines in 87 ms on
     * VARP_SMALL / VARP_LARGE / UPDATE_STAT, so the log appeared to show a
     * server that sends nothing but variable state. Arithmetic said otherwise:
     * those 500 packets account for ~2.7 KB of a measured bytesOut of 8,028, so
     * roughly 5 KB of packets happened after the cutoff - including, per
     * LobbyPlayer, the IF_OPENTOP / IF_OPENSUB interface opens that actually
     * build the lobby screen.
     *
     * A budget that hides the tail of a burst is worse than no budget, because
     * the visible part looks like the whole story. One line per packet is cheap;
     * 20,000 of them is a few MB at worst.
     */
    val SEND_LINE_LIMIT: Int = System.getProperty("opennxt.diag.sendLines")?.toIntOrNull() ?: 20000

    /** Cap on remembered unserved JS5 requests (bounded memory on a cache pull). */
    private const val JS5_PENDING_LIMIT = 4096

    private val logger = KotlinLogging.logger { }

    private val ATTR: AttributeKey<Connection> = AttributeKey.valueOf("opennxt-diagnostic-connection")

    private val ids = AtomicInteger(0)
    private val processStart = System.nanoTime()

    @Volatile
    private var enabledFlag = readProperty()

    private var writer: Writer? = null
    private var file: Path? = null
    private var writeFailed = false

    private fun readProperty(): Boolean {
        val raw = System.getProperty(SYSTEM_PROPERTY) ?: return false
        return raw.equals("true", true) || raw == "1" || raw.equals("yes", true)
    }

    /** True when `-Dopennxt.diag=true` was set. Checked before every recording. */
    val enabled: Boolean
        get() = enabledFlag

    /**
     * Re-reads [SYSTEM_PROPERTY]. Only for tools that flip the property inside a
     * process); the server itself reads the property once at class init.
     */
    fun refreshFromSystemProperty(): Boolean {
        enabledFlag = readProperty()
        return enabledFlag
    }

    /**
     * Path of this run's file, or null if nothing has been recorded yet.
     *
     * Drains the queue first: the file is opened lazily by the writer thread on
     * its first line, so without this a caller that recorded an event and
     * immediately asked where it went could be told "nowhere".
     */
    fun currentFile(): Path? {
        flushPending()
        return synchronized(this) { file }
    }

    /** Directory the per-run files live in. */
    fun directory(): Path = Constants.DATA_PATH.resolve("diag")

    /**
     * Opens the log at server start, before any client has connected, and writes
     * a line saying the recorder is armed and waiting.
     *
     * This exists because of a real ambiguity hit on the first live attempt. The
     * file used to be created lazily on the first recorded event, so a server
     * that had been running for an hour with nothing connecting to it left NO
     * diag directory at all - and "there is no log" then means either
     *
     *   (a) no client ever reached the server, or
     *   (b) the recorder is broken / was not enabled,
     *
     * with nothing to tell them apart. That is an absence claim with no positive
 * control, which is exactly the thing this server refuses to accept
     * anywhere else, and it cost a round-trip to resolve by other means.
     *
     * With this, the file exists from boot. An empty-but-for-the-header log is
     * then a MEASUREMENT - the recorder was armed and nothing arrived - rather
     * than an absence of evidence.
     */
    fun arm() {
        if (!enabled) return
        emit(null, null, "RECORDER ARMED - waiting for a connection. If this file still ends here, " +
            "nothing ever reached the server: no http, no handshake, no js5, no login.")
    }

    /**
     * Records an HTTP request on the launcher path.
     *
     * The HTTP side used to be entirely uninstrumented - not logged, not
     * recorded - so "the diagnostic file is empty" could not distinguish a
     * client that never called from one that fetched its config and then died.
     * The launcher path runs BEFORE any game socket, so without this the first
     * thing the client does is also the one thing we could not see.
     */
    fun http(channel: Channel, line: String) {
        if (!enabled) return
        try {
            emit(of(channel), null, "[HTTP] $line")
        } catch (t: Throwable) {
            internalError("http", t)
        }
    }

    enum class Stage { HANDSHAKE, JS5, LOGIN, GAME }

    // ------------------------------------------------------------------ output

    private fun stamp(): String = String.format("%9.3fs", (System.nanoTime() - processStart) / 1_000_000_000.0)

    private fun emit(conn: Connection?, stage: Stage?, line: String) {
        val prefix = if (conn == null) "[${stamp()}] [-] " else "[${stamp()}] [${conn.peer} #${conn.id}] "
        val text = prefix + (if (stage == null) "" else "[$stage] ") + line
        write(text)
        logger.info { "[diag] $text" }
    }

    /**
     * Lines waiting to reach the file. Handed to [writerLoop], never written by
     * the thread that produced them.
     *
     * THE INSTRUMENT WAS PERTURBING WHAT IT MEASURES. [write] used to hold a
     * global `synchronized(this)` and call `w.flush()` PER LINE, from whichever
     * thread produced the event - which is the Netty event loop for almost all
     * of them. With JS5_LINE_LIMIT = 2000 inbound request lines and
     * SEND_LINE_LIMIT = 20,000 send lines that is up to 22,000 synchronous
     * fsync-class flushes on the I/O threads, serialised through one lock shared
     * by every connection, inside the exact window whose timing is the point of
     * the run.
     *
     * Flushing per line stays - it is the right call, and the reason is still
     * that a first-contact run ending in a crash must leave its evidence on disk
     * - it just happens on `diag-writer` now instead of on the event loop. The
     * durability that costs: lines queued but not yet written are lost if the
     * JVM is SIGKILLed. An orderly exit, including one via an uncaught
     * exception, runs the shutdown hook installed below, which drains the queue.
     */
    private val pending = java.util.concurrent.LinkedBlockingQueue<String>()

    @Volatile
    private var writerThread: Thread? = null

    /** Producer side: never touches the file, never blocks on I/O. */
    private fun write(text: String) {
        if (writeFailed) return
        ensureWriterThread()
        pending.add(text)
    }

    private fun ensureWriterThread() {
        if (writerThread != null) return
        synchronized(this) {
            if (writerThread != null) return
            val t = Thread({ writerLoop() }, "diag-writer")
            t.isDaemon = true
            writerThread = t
            t.start()
            Runtime.getRuntime().addShutdownHook(Thread({ drainPending() }, "diag-writer-shutdown"))
        }
    }

    private fun writerLoop() {
        while (true) {
            val line = try {
                pending.take()
            } catch (e: InterruptedException) {
                drainPending()
                return
            }
            writeLine(line)
        }
    }

    private fun drainPending() {
        while (true) writeLine(pending.poll() ?: return)
    }

    /**
     * Blocks until everything queued so far is on disk (or [timeoutMs] elapses).
     */
    fun flushPending(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Consumer side. Only [writerLoop] and the shutdown hook call this. */
    private fun writeLine(text: String) {
        synchronized(this) {
            if (writeFailed) return
            try {
                var w: Writer? = writer
                if (w == null) {
                    val dir = directory()
                    Files.createDirectories(dir)
                    val name = "diag-" +
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) +
                        "-" + ProcessHandle.current().pid() + ".log"
                    val path = dir.resolve(name)
                    w = Files.newBufferedWriter(
                        path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
                    )
                    writer = w
                    file = path
                    w.write("# OpenNXT diagnostic log - opened ${LocalDateTime.now()}\n")
                    w.write("# server build: ${serverBuild() ?: "unknown (config not loaded)"}\n")
                    w.write("# PREDICTION: the client will announce build $PREDICTED_CLIENT_BUILD.\n")
                    w.write("# Measured from the binary BEFORE this run, two ways that agree - the\n")
                    w.write("#   immediates at the \"Client Version: %i-%i\" call site in .text, and the\n")
                    w.write("#   RS2Engine tag in .rdata. Reproduce with tools/clientinfo.py.\n")
                    w.write("#   A mismatch against the SERVER build below is the premise of this run.\n")
                    w.write("#   A mismatch against the PREDICTION would be a genuine finding: it would\n")
                    w.write("#   mean the version the client reports is not the version it speaks.\n")
                    w.write("# enabled by -D$SYSTEM_PROPERTY; dumps capped at $DUMP_LIMIT bytes per event\n")
                    w.flush()
                    logger.warn { "Diagnostic recording is ON; writing to $path" }
                }
                w!!.write(text)
                w.write("\n")
                // Flushed per line on purpose: a first-contact run that ends in a
                // crash must still leave the evidence that led up to it on disk.
                // This now runs on `diag-writer`, not on the event loop that
                // produced the event - see [pending].
                w.flush()
            } catch (t: Throwable) {
                writeFailed = true
                logger.error(t) { "Diagnostic log write failed; disabling file output (protocol unaffected)" }
            }
        }
    }

    private fun serverBuild(): Int? = try {
        OpenNXT.config.build
    } catch (t: Throwable) {
        null
    }

    /** 16-byte-per-line hex + ASCII, capped at [DUMP_LIMIT]. */
    fun hexDump(bytes: ByteArray, totalLength: Int = bytes.size, indent: String = "        "): String {
        val shown = minOf(bytes.size, DUMP_LIMIT)
        val sb = StringBuilder()
        var i = 0
        while (i < shown) {
            sb.append(indent).append(String.format("%04x  ", i))
            for (j in 0 until 16) {
                if (i + j < shown) sb.append(String.format("%02x ", bytes[i + j])) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in 0 until 16) {
                if (i + j >= shown) break
                val c = bytes[i + j].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (totalLength > shown) sb.append(indent).append("... (").append(totalLength - shown).append(" more bytes)\n")
        if (sb.isNotEmpty() && sb.last() == '\n') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /**
     * Copies at most [limit] readable bytes out of [buf] WITHOUT moving its reader
     * index (absolute getter), so the caller's parsing is untouched.
     */
    fun snapshot(buf: ByteBuf, limit: Int = DUMP_LIMIT): ByteArray {
        val n = minOf(limit, buf.readableBytes())
        val out = ByteArray(n)
        buf.getBytes(buf.readerIndex(), out)
        return out
    }

    // ------------------------------------------------------------- connections

    class Connection internal constructor(val id: Int, val peer: String) {
        internal val openedNanos = System.nanoTime()
        private val stages = LinkedHashSet<Stage>()
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        private val unknownOpcodes = LinkedHashMap<Int, IntArray>()
        private val js5Pending = LinkedHashMap<Long, Int>()
        private var js5PendingTruncated = false
        private var js5Requests = 0
        private var js5Served = 0
        private var js5Lines = 0
        private var reason: String? = null
        internal val finished = AtomicBoolean(false)

        internal fun addBytesIn(n: Int) { bytesIn.addAndGet(n.toLong()) }
        internal fun addBytesOut(n: Int) { bytesOut.addAndGet(n.toLong()) }

        private var outWrites = 0
        private var outDumped = 0

        /**
         * Charge one outbound write, and say how many bytes of it may be dumped.
         *
         * Returns -1 once the budget is spent, so a chatty connection stops
         * writing hex without stopping the count.
         *
         * This exists because of a concrete blind spot: a client reached the
         * login screen, sent a login frame the server parsed correctly, received
         * 8,028 bytes, and reset the connection 6.6 seconds later. The recorder
         * recorded the INBOUND side in full detail and nothing at all of those
         * 8,028 bytes, so what the client actually choked on was pure inference.
         */
        internal fun outBudget(size: Int): Int = synchronized(this) {
            outWrites++
            if (outWrites > OUT_WRITE_LIMIT) return -1
            if (outDumped >= OUT_DUMP_LIMIT) return 0
            val allowed = minOf(size, OUT_DUMP_LIMIT - outDumped)
            outDumped += allowed
            allowed
        }

        internal fun outWriteCount(): Int = synchronized(this) { outWrites }

        private var sends = 0

        /** 1-based index of this send, or -1 once the budget is spent. */
        internal fun sendBudget(): Int = synchronized(this) {
            sends++
            if (sends > SEND_LINE_LIMIT) -1 else sends
        }

        internal fun sendCount(): Int = synchronized(this) { sends }

        internal fun stage(stage: Stage): Boolean = synchronized(this) { stages.add(stage) }
        internal fun currentStage(): Stage = synchronized(this) { stages.lastOrNull() ?: Stage.HANDSHAKE }

        internal fun noteReason(text: String) = synchronized(this) { if (reason == null) reason = text }

        /** @return the occurrence count for this opcode (1-based). */
        internal fun countUnknownOpcode(opcode: Int): Int = synchronized(this) {
            val slot = unknownOpcodes.getOrPut(opcode) { IntArray(1) }
            slot[0]++
            slot[0]
        }

        /** @return this connection's JS5 line count AFTER charging one line to it. */
        internal fun js5LineBudget(): Int = synchronized(this) {
            js5Lines++
            js5Lines
        }

        /**
         * Per-index request counts, kept OUTSIDE the line budget.
         *
         * The per-request lines are capped at JS5_LINE_LIMIT, and on a cache
         * rebuild that cap hides almost everything: one run logged 1,162 of
         * 30,609 requests. "The client never asked for index 3" was then read
         * off a log that had dropped 96% of the requests - a conclusion drawn
         * from the instrument's budget rather than from the client. A tally of
         * which INDICES were touched costs a few bytes and cannot be truncated.
         */
        private val js5ByIndex = java.util.TreeMap<Int, Int>()

        internal fun js5Requested(index: Int, archive: Int) = synchronized(this) {
            js5Requests++
            js5ByIndex[index] = (js5ByIndex[index] ?: 0) + 1
            val key = (index.toLong() shl 32) or (archive.toLong() and 0xffffffffL)
            if (js5Pending.size < JS5_PENDING_LIMIT) js5Pending[key] = (js5Pending[key] ?: 0) + 1
            else js5PendingTruncated = true
        }

        internal fun js5ServedFile(index: Int, archive: Int) = synchronized(this) {
            js5Served++
            val key = (index.toLong() shl 32) or (archive.toLong() and 0xffffffffL)
            val n = js5Pending[key]
            if (n != null) {
                if (n <= 1) js5Pending.remove(key) else js5Pending[key] = n - 1
            }
        }

        internal fun summary(): String = synchronized(this) {
            val ms = (System.nanoTime() - openedNanos) / 1_000_000
            val sb = StringBuilder()
            sb.append("SUMMARY stages=").append(if (stages.isEmpty()) "NONE" else stages.joinToString(">"))
            sb.append(" bytesIn=").append(bytesIn.get())
            sb.append(" bytesOut=").append(bytesOut.get())
            sb.append(" durationMs=").append(ms)
            sb.append(" reason=").append(reason ?: "unknown")
            if (js5Requests > 0 || js5Served > 0) {
                sb.append(" js5Requested=").append(js5Requests).append(" js5Served=").append(js5Served)
                if (js5ByIndex.isNotEmpty())
                    sb.append(" js5ByIndex={")
                        .append(js5ByIndex.entries.joinToString(",") { "${it.key}:${it.value}" })
                        .append("}")
                if (js5Pending.isNotEmpty()) {
                    sb.append(" js5Unserved=")
                    sb.append(js5Pending.keys.take(20).joinToString(",") {
                        "[${(it ushr 32).toInt()},${it.toInt()}]"
                    })
                    if (js5Pending.size > 20) sb.append(",...(${js5Pending.size} distinct)")
                    if (js5PendingTruncated) sb.append(" (pending tracking truncated)")
                }
            }
            if (unknownOpcodes.isNotEmpty()) {
                sb.append(" unregisteredOpcodes={")
                sb.append(unknownOpcodes.entries.joinToString(",") { "${it.key}x${it.value[0]}" })
                sb.append("}")
            }
            sb.toString()
        }
    }

    fun of(channel: Channel): Connection? = if (!enabledFlag) null else channel.attr(ATTR).get()

    /**
     * Starts recording for [ctx]'s channel and installs the pass-through
     * [DiagnosticTap] at the HEAD of the pipeline, so every inbound and outbound
     * byte is counted from the first one. Called from HandshakeDecoder.handlerAdded
     * (before any data can arrive). No-op when the recorder is off.
     */
    fun attach(ctx: ChannelHandlerContext) {
        if (!enabledFlag) return
        try {
            val channel = ctx.channel()
            if (channel.attr(ATTR).get() != null) return
            val conn = Connection(ids.incrementAndGet(), channel.remoteAddress()?.toString() ?: "unknown")
            channel.attr(ATTR).set(conn)
            conn.stage(Stage.HANDSHAKE)
            emit(conn, Stage.HANDSHAKE, "OPENED local=${channel.localAddress()}")
            ctx.pipeline().addFirst("diagnostic-tap", DiagnosticTap(conn))
            // Belt and braces: the tap reports on channelInactive, this fires even
            // if the tap was removed from the pipeline by something else.
            channel.closeFuture().addListener { finish(conn, "channel closed") }
        } catch (t: Throwable) {
            internalError("attach", t)
        }
    }

    /** Installs the outbound JS5 observer at the pipeline TAIL (sees served files). */
    fun attachJs5(channel: Channel) {
        if (!enabledFlag) return
        try {
            val conn = channel.attr(ATTR).get() ?: return
            if (channel.pipeline().get("diagnostic-js5-tap") != null) return
            channel.pipeline().addLast("diagnostic-js5-tap", DiagnosticJs5ResponseTap(conn))
        } catch (t: Throwable) {
            internalError("attachJs5", t)
        }
    }

    fun stage(channel: Channel, stage: Stage, detail: String = "") {
        val conn = of(channel) ?: return
        try {
            if (conn.stage(stage)) emit(conn, stage, "STAGE ENTERED${if (detail.isEmpty()) "" else " $detail"}")
        } catch (t: Throwable) {
            internalError("stage", t)
        }
    }

    fun note(channel: Channel, stage: Stage, line: String) {
        val conn = of(channel) ?: return
        try {
            emit(conn, stage, line)
        } catch (t: Throwable) {
            internalError("note", t)
        }
    }

    /** Records the reason a connection ended (shown in its SUMMARY line). */
    fun reason(channel: Channel, text: String) {
        val conn = of(channel) ?: return
        try {
            conn.noteReason(text)
        } catch (t: Throwable) {
            internalError("reason", t)
        }
    }

    fun bytes(channel: Channel, stage: Stage, label: String, data: ByteArray, totalLength: Int = data.size) {
        val conn = of(channel) ?: return
        try {
            emit(conn, stage, "$label ($totalLength bytes)\n${hexDump(data, totalLength)}")
        } catch (t: Throwable) {
            internalError("bytes", t)
        }
    }

    internal fun finish(conn: Connection, reason: String) {
        if (!conn.finished.compareAndSet(false, true)) return
        try {
            conn.noteReason(reason)
            emit(conn, conn.currentStage(), conn.summary())
        } catch (t: Throwable) {
            internalError("finish", t)
        }
    }

    private fun internalError(where: String, t: Throwable) {
        logger.warn(t) { "Diagnostic recorder failed in $where (protocol unaffected)" }
    }

    // ------------------------------------------------------- the headline line

    /**
     * THE line. Compares the build the client announced with the server's
     * configured build. A mismatch is written as an unmissable banner; a match is
     * written as a single "==" line (deliberately NOT containing "!=", so the
     * as it must).
     */
    fun buildAnnounced(channel: Channel, stage: Stage, clientBuild: Int, context: String) {
        val conn = of(channel) ?: return
        try {
            val server = serverBuild()
            if (server == null) {
                emit(conn, stage, "CLIENT BUILD $clientBuild announced; SERVER BUILD unknown (config not loaded)   ($context)")
                return
            }
            // The static prediction, checked. See PREDICTED_CLIENT_BUILD: the
            // interesting outcome here is NOT the mismatch against the server
            // (that is the premise) but whether the wire agrees with the number
            // already read out of the binary.
            emit(
                conn, stage,
                if (clientBuild == PREDICTED_CLIENT_BUILD)
                    "PREDICTION HELD: the binary read $PREDICTED_CLIENT_BUILD and the wire announced the same"
                else
                    "PREDICTION FAILED: the binary reads $PREDICTED_CLIENT_BUILD, the wire announced " +
                        "$clientBuild - reported and spoken versions differ, which is a finding in itself"
            )
            if (clientBuild != PREDICTED_CLIENT_BUILD) {
                logger.error {
                    "[diag] prediction failed: binary $PREDICTED_CLIENT_BUILD vs wire $clientBuild"
                }
                conn.noteReason("wire build $clientBuild != binary version $PREDICTED_CLIENT_BUILD")
            }

            if (clientBuild != server) {
                val bar = "!".repeat(78)
                val line = "CLIENT BUILD $clientBuild != SERVER BUILD $server   ($context)"
                emit(
                    conn, stage,
                    "BUILD MISMATCH (EXPECTED - this is the premise, not the failure)\n$bar\n!!! $line\n" +
                        "!!! The packet tables in data/prot are for build $server. Every framing,\n" +
                        "!!! opcode and field-layout expectation below is derived from that build.\n" +
                        "!!! What this run measures is what that mismatch COSTS - how far the\n" +
                        "!!! client gets anyway, and which stage is the first to actually break.\n$bar"
                )
                logger.error { "[diag] $line" }
                conn.noteReason("build mismatch: client $clientBuild vs server $server")
            } else {
                emit(conn, stage, "CLIENT BUILD $clientBuild == SERVER BUILD $server (match)   ($context)")
            }
        } catch (t: Throwable) {
            internalError("buildAnnounced", t)
        }
    }

    // ------------------------------------------------------------------- JS5

    /** Called by [DiagnosticTap] for each complete 10-byte JS5 request frame. */
    internal fun js5Frame(channel: Channel, conn: Connection, frame: ByteArray) {
        val opcode = frame[0].toInt() and 0xff
        val lines = conn.js5LineBudget()
        val verbose = lines <= JS5_LINE_LIMIT
        if (lines == JS5_LINE_LIMIT + 1) {
            emit(conn, Stage.JS5, "per-request logging suppressed after $JS5_LINE_LIMIT lines; counting only (totals in SUMMARY)")
        }
        when (opcode) {
            Js5PacketCodec.RequestFile.opcodeLow,
            Js5PacketCodec.RequestFile.opcodeHigh,
            Js5PacketCodec.RequestFile.opcodeNxtLow,
            Js5PacketCodec.RequestFile.opcodeNxtHigh1,
            Js5PacketCodec.RequestFile.opcodeNxtHigh2 -> {
                val index = frame[1].toInt() and 0xff
                val archive = ((frame[2].toInt() and 0xff) shl 24) or ((frame[3].toInt() and 0xff) shl 16) or
                    ((frame[4].toInt() and 0xff) shl 8) or (frame[5].toInt() and 0xff)
                val build = ((frame[6].toInt() and 0xff) shl 8) or (frame[7].toInt() and 0xff)
                // Priority classification mirrors Js5Decoder exactly.
                val priority = opcode != Js5PacketCodec.RequestFile.opcodeNxtLow &&
                    opcode != Js5PacketCodec.RequestFile.opcodeLow
                conn.js5Requested(index, archive)
                if (verbose) {
                    val xor = channel.attr(Js5Session.XOR_KEY).get()
                    emit(
                        conn, Stage.JS5,
                        "REQUEST index=$index archive=$archive priority=$priority opcode=$opcode " +
                            "build=$build xor=${xor ?: "unset"}"
                    )
                }
            }
            Js5PacketCodec.XorRequest.opcode -> {
                val xor = frame[1].toInt() and 0xff
                emit(conn, Stage.JS5, "XOR KEY SET xor=$xor (every following byte on this connection is XORed with it)")
            }
            Js5PacketCodec.ConnectionInitialized.opcode ->
                if (verbose) emit(conn, Stage.JS5, "CONNECTION_INITIALIZED\n${hexDump(frame)}")
            Js5PacketCodec.RequestTermination.opcode -> {
                emit(conn, Stage.JS5, "REQUEST_TERMINATION\n${hexDump(frame)}")
                conn.noteReason("client requested js5 termination")
            }
            Js5PacketCodec.LoggedIn.opcode -> if (verbose) emit(conn, Stage.JS5, "LOGGED_IN\n${hexDump(frame)}")
            Js5PacketCodec.LoggedOut.opcode -> if (verbose) emit(conn, Stage.JS5, "LOGGED_OUT\n${hexDump(frame)}")
            else -> emit(
                conn, Stage.JS5,
                "UNKNOWN JS5 OPCODE $opcode - the server will skip this frame\n${hexDump(frame)}"
            )
        }
    }

    /** Called by [DiagnosticJs5ResponseTap] for each file the server actually serves. */
    internal fun js5Serve(conn: Connection, packet: Js5Packet.RequestFileResponse) {
        conn.js5ServedFile(packet.index, packet.archive)
        if (conn.js5LineBudget() <= JS5_LINE_LIMIT) {
            emit(
                conn, Stage.JS5,
                "SERVED index=${packet.index} archive=${packet.archive} priority=${packet.priority} " +
                    "containerBytes=${packet.data.readableBytes()}"
            )
        }
    }

    // -------------------------------------------------- unregistered opcodes

    /**
     * An inbound game packet whose opcode has a size in the protocol tables but no
     * codec registration: [ConnectedClient.receive] drops it. Rate limited to
     * [UNKNOWN_OPCODE_SAMPLES] full records per opcode per connection; after that
     * the occurrences are counted and reported in the SUMMARY line.
     */
    fun unregisteredOpcode(channel: Channel, side: Side, opcode: Int, buf: ByteBuf) {
        val conn = of(channel) ?: return
        try {
            conn.stage(Stage.GAME)
            val n = conn.countUnknownOpcode(opcode)
            when {
                n <= UNKNOWN_OPCODE_SAMPLES -> {
                    val size = buf.readableBytes()
                    val data = snapshot(buf)
                    emit(
                        conn, Stage.GAME,
                        "UNREGISTERED OPCODE $opcode side=$side size=$size occurrence=$n (dropped: no codec registered)\n" +
                            hexDump(data, size)
                    )
                }
                n == UNKNOWN_OPCODE_SAMPLES + 1 ->
                    emit(
                        conn, Stage.GAME,
                        "UNREGISTERED OPCODE $opcode suppressed after $UNKNOWN_OPCODE_SAMPLES samples; counting only (total in SUMMARY)"
                    )
            }
        } catch (t: Throwable) {
            internalError("unregisteredOpcode", t)
        }
    }

    /**
     * What the server is about to send, by NAME, before ISAAC enciphers it.
     *
     * Called from GamePacketEncoder. The socket tap sees the enciphered byte and
     * can never recover this; here the opcode is still in the clear.
     *
     * Names come from the table in use, which may have been borrowed from
     * another build, so the build that supplied the name is stated on every
     * line. "The server sent SET_MAP_FLAG as opcode 33, per build 947" is a
     * measured claim; whether the 949 client agrees about 33 is a separate
     * question, and keeping them separate is the whole point.
     *
     * [resolvedName] is supplied by the caller because THE INSTRUMENT WAS
     * PERTURBING WHAT IT. This used to do
     * `names.reversedValues()[opcode]`, and Name2OpcodeConfig.reversedValues()
     * constructs a new Int2ObjectOpenHashMap and repopulates it from the entire
     * name table on every call. With SEND_LINE_LIMIT = 20,000 and a measured
     * login burst of ~1,435 packets in ~87 ms, that was ~1,435 full map builds
     * on the event loop inside the very window being timed. GamePacketEncoder
     * already caches the reversed map in a field, so it costs it nothing to pass
     * the name in. The lookup below is kept only as a fallback for callers that
     * do not have one.
     */
    fun sendingPacket(
        channel: Channel,
        side: Side,
        opcode: Int,
        payload: Int,
        declared: Int,
        resolvedName: String? = null,
        head: ByteArray? = null
    ) {
        val conn = of(channel) ?: return
        try {
            val n = conn.sendBudget()
            if (n < 0) return
            val name = resolvedName ?: run {
                val names =
                    if (side == Side.CLIENT) OpenNXT.protocol.serverProtNames else OpenNXT.protocol.clientProtNames
                names.reversedValues()[opcode]
            } ?: "(unnamed)"
            val from = OpenNXT.protocol.effectiveBuild
            val sizeNote = when (declared) {
                -1 -> "var-byte"
                -2 -> "var-short"
                else -> "fixed $declared"
            }
            val parent = parentOf(name, head)
            val parentNote = if (parent == null) "" else " parent=${parent ushr 16}:${parent and 0xffff}"
            emit(conn, conn.currentStage(),
                "SEND #$n opcode=$opcode name=$name payload=$payload$parentNote ($sizeNote, names from build $from)")
        } catch (t: Throwable) {
            internalError("sendingPacket", t)
        }
    }

    private val PARENT_ORDER: Map<String, String> = mapOf(
        "IF_SETEVENTS" to "intle",
        "IF_SETANIM" to "intle",
        "IF_SETCLICKMASK" to "intle",
        "IF_SETPLAYERHEAD" to "intle",
        "IF_SETPLAYERHEAD_SNAPSHOT" to "intle",
        "IF_SETPLAYERMODEL_SELF" to "intle",
        "IF_SETPLAYERMODEL_SNAPSHOT" to "intle",
        "IF_SETTEXTANTIMACRO" to "intle",
        "IF_CLOSESUB" to "int",
        "IF_SETHIDE" to "intv2",
        "IF_SETOBJECT64" to "intv2",
        "IF_SETMODEL" to "intv1",
    )

    fun wantsHead(channel: Channel, name: String?): Boolean =
        name != null && PARENT_ORDER.containsKey(name) && of(channel) != null

    /** The packed `(interface shl 16) or component` a packet addresses, or null when it is not a listed packet. */
    internal fun parentOf(name: String, head: ByteArray?): Int? {
        val order = PARENT_ORDER[name] ?: return null
        if (head == null || head.size < 4) return null
        val b0 = head[0].toInt() and 0xff; val b1 = head[1].toInt() and 0xff
        val b2 = head[2].toInt() and 0xff; val b3 = head[3].toInt() and 0xff
        return when (order) {
            "intle" -> (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            "int" -> (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            "intv1" -> (b0 shl 8) or b1 or (b2 shl 24) or (b3 shl 16)
            "intv2" -> (b0 shl 16) or (b1 shl 24) or b2 or (b3 shl 8)
            else -> null
        }
    }

    /** Marks a connection as having reached the game pipeline. */
    fun markGameStage(channel: Channel) {
        val conn = of(channel) ?: return
        try {
            if (conn.stage(Stage.GAME)) emit(conn, Stage.GAME, "STAGE ENTERED (first game frame decoded)")
        } catch (t: Throwable) {
            internalError("markGameStage", t)
        }
    }

    // ------------------------------------------------------------ the taps

    /**
     * Head-of-pipeline pass-through. Counts every inbound/outbound byte, and - for
     * a JS5 connection - reframes the inbound stream into the 10-byte request
     * frames Js5Decoder consumes so each request can be recorded. It NEVER consumes
     * from the buffer it is handed (absolute getters only) and always forwards.
     */
    class DiagnosticTap internal constructor(private val conn: Connection) : ChannelDuplexHandler() {

        private var firstByteSeen = false
        private var isJs5 = false
        private var js5HandshakePending = true
        private val carry = ByteArrayOutputStream()

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                conn.addBytesIn(msg.readableBytes())
                try {
                    inspect(ctx, msg)
                } catch (t: Throwable) {
                    internalError("tap.inspect", t)
                }
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf) {
                conn.addBytesOut(msg.readableBytes())
                try {
                    recordOutbound(msg)
                } catch (t: Throwable) {
                    internalError("tap.recordOutbound", t)
                }
            }
            ctx.write(msg, promise)
        }

        /**
         * Record what the SERVER sends, on everything except JS5.
         *
         * JS5 is excluded by measurement, not by taste: one run served
         * 167,239,831 bytes on that stage. Login and game together were 8,028.
         *
         * Deliberately NOT decoded into packets. The tables in use may be
         * borrowed from another build (-Dopennxt.prot.borrowBuild), so framing
         * the stream with them would produce confident, wrong packet boundaries
         * - which is the exact failure mode being investigated, reproduced in
         * the instrument meant to diagnose it. Raw bytes with sizes and timing
         * cannot lie in that way; whoever reads the log can frame them against
         * whichever table they want to test.
         */
        private fun recordOutbound(buf: ByteBuf) {
            val stage = conn.currentStage()
            if (stage == Stage.JS5) return
            val size = buf.readableBytes()
            if (size <= 0) return

            val allowed = conn.outBudget(size)
            if (allowed < 0) return
            val n = conn.outWriteCount()
            if (allowed == 0) {
                if (n == 1 || n % 20 == 0)
                    emit(conn, stage, "OUT #$n $size bytes (dump budget spent; still counting)")
                return
            }
            val bytes = ByteArray(minOf(allowed, size))
            buf.getBytes(buf.readerIndex(), bytes)
            emit(conn, stage, "OUT #$n $size bytes" +
                (if (bytes.size < size) " (first ${bytes.size} shown)" else "") + "\n" +
                hexDump(bytes, size))
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            finish(conn, "channel went inactive")
            ctx.fireChannelInactive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            conn.noteReason("exception: ${cause.javaClass.name}: ${cause.message}")
            emit(conn, conn.currentStage(), "EXCEPTION ${cause.javaClass.name}: ${cause.message}")
            ctx.fireExceptionCaught(cause)
        }

        private fun inspect(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val readable = buf.readableBytes()
            if (readable <= 0) return
            val bytes = ByteArray(readable)
            buf.getBytes(buf.readerIndex(), bytes)

            var offset = 0
            if (!firstByteSeen) {
                firstByteSeen = true
                val id = bytes[0].toInt() and 0xff
                isJs5 = id == HandshakeType.JS_5.id
                offset = 1
            }
            if (!isJs5) return
            carry.write(bytes, offset, bytes.size - offset)
            drain(ctx)
        }

        /**
         * Reframes the JS5 stream: the handshake ([size][size bytes]) then fixed
         * 10-byte frames, exactly the framing Js5Decoder consumes. Leftovers are
         * carried to the next read, so a split TCP segment cannot desynchronise it.
         */
        private fun drain(ctx: ChannelHandlerContext) {
            val data = carry.toByteArray()
            var p = 0
            while (true) {
                if (js5HandshakePending) {
                    if (data.size - p < 1) break
                    val size = data[p].toInt() and 0xff
                    if (data.size - p < 1 + size) break
                    val frame = data.copyOfRange(p, p + 1 + size)
                    emit(
                        conn, Stage.JS5,
                        "HANDSHAKE FRAME ($size bytes after the size byte)\n" +
                            hexDump(frame, 1 + size)
                    )
                    // Read the build straight out of the raw frame rather than
                    // waiting for Js5Handler to report it.
                    //
                    // This exists because of an observed failure: a malformed
                    // handshake made the js5 handler throw BEFORE it reached the
                    // build field, so the log recorded an exception and never
                    // printed the one number the whole run is for - even though
                    // the bytes were sitting in the dump directly above it.
                    // Against a client 28 builds ahead of these tables, the
                    // handler throwing early is a likely outcome, so the headline
                    // must not depend on the handler surviving.
                    //
                    // Layout: the frame is [size][major u32][minor u32][token].
                    // Guarded rather than assumed - a frame too short to hold the
                    // pair says so instead of indexing off the end.
                    if (size >= 8) {
                        val major = ((frame[1].toInt() and 0xff) shl 24) or
                            ((frame[2].toInt() and 0xff) shl 16) or
                            ((frame[3].toInt() and 0xff) shl 8) or (frame[4].toInt() and 0xff)
                        val minor = ((frame[5].toInt() and 0xff) shl 24) or
                            ((frame[6].toInt() and 0xff) shl 16) or
                            ((frame[7].toInt() and 0xff) shl 8) or (frame[8].toInt() and 0xff)
                        emit(
                            conn, Stage.JS5,
                            "BUILD FROM RAW FRAME: $major-$minor  (read here, not via the handler, " +
                                "so an early handler failure cannot cost us this number)"
                        )
                        buildAnnounced(ctx.channel(), Stage.JS5, major, "js5 handshake, raw frame")
                    } else {
                        emit(
                            conn, Stage.JS5,
                            "handshake frame is only $size byte(s) - too short to carry a build pair; " +
                                "not guessing one"
                        )
                    }
                    p += 1 + size
                    js5HandshakePending = false
                    continue
                }
                if (data.size - p < 10) break
                js5Frame(ctx.channel(), conn, data.copyOfRange(p, p + 10))
                p += 10
            }
            carry.reset()
            if (p < data.size) carry.write(data, p, data.size - p)
        }
    }

    /**
     * Tail-of-pipeline outbound pass-through: sees [Js5Packet.RequestFileResponse]
     * before Js5Encoder turns it into bytes, which is the only place that knows a
     * requested file was actually FOUND and handed to the wire. A request with no
     * matching serve shows up as js5Unserved in the SUMMARY line.
     */
    class DiagnosticJs5ResponseTap internal constructor(private val conn: Connection) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Js5Packet.RequestFileResponse) {
                try {
                    js5Serve(conn, msg)
                } catch (t: Throwable) {
                    internalError("js5Tap", t)
                }
            }
            ctx.write(msg, promise)
        }
    }
}
