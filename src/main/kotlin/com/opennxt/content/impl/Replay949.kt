package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import java.io.File

/**
 * RAW REPLAY of a recorded build-949 login, supplied by the operator via
 * `-Dopennxt.experiment.replay949=<file>`. Nothing is shipped with it.
 */
object Replay949 {
    private val logger = KotlinLogging.logger { }

    data class Rec(val seq: Int, val op: Int, val name: String, val bytes: ByteArray)

    val path: String? = System.getProperty("opennxt.experiment.replay949")
    val enabled: Boolean get() = path != null
    val mode: String = (System.getProperty("opennxt.experiment.replay949.mode") ?: "after").lowercase()
    val only: Boolean get() = mode == "only"

    /**
     * `mode=ui` - REPLAY THE REFERENCE CLIENT'S INTERFACE LAYER AND NOTHING ELSE.
     */
    private val UI_PACKET_NAMES = listOf(
        "IF_OPENTOP", "IF_OPENSUB", "IF_CLOSESUB", "IF_SETEVENTS", "IF_SETHIDE", "IF_SETTEXT",
        "IF_SETGRAPHIC", "IF_SETCOLOUR", "IF_SETANGLE", "IF_SETSCROLLPOS", "IF_MOVESUB",
        "RUNCLIENTSCRIPT", "SETDRAWORDER"
        // ---------------------------------------------------------------------------------
 // THE SIX CLIENT_SETVARC* OPCODES WERE REMOVED FROM THIS SET on, hours
        // after being added to it, and the reason is that they stopped being this file's job.
        //
        // When `ui` was written the server had no varc store at all, so replaying the protocol's
        // varcs was the only way the HUD came up arranged. It now has one:
        // VarcTransmit decodes what the client pushes, PlayerSave.varcs persists it, and
        // VarcRestore replays the CHARACTER'S OWN state at login - seeding a first login from
        // data/config/interface-defaults.tsv, which is these same values extracted once.
        //
        // Leaving them here made the two fight, and the replay won. Measured in
        // server-20260904-135521, in this order:
        //     t+0   ui.varcRestore  replayed 228 stored setting(s) for aq
        // t+6 replay949[ui] 100 varcbits + 50 varcstrs from the recorded login, on top
        // i.e. every character got the recorded account's layout no matter what they had set,
        // which is the exact opposite of the point of the save loop.
        //
        // What stays is the STRUCTURAL half - the mounts, the arming, the scripts - which is
        // the same for every player and is not state anyone can change. What goes is the
        // per-character half, which now has an owner.
        // ---------------------------------------------------------------------------------
    )

    val uiOnly: Boolean get() = mode == "ui"

    /** [UI_PACKET_NAMES] resolved against this build's ServerProt table. Empty until the protocol is loaded. */
    private val uiOps: Set<Int> by lazy {
        val resolved = LinkedHashMap<String, Int>()
        val missing = ArrayList<String>()
        val table = com.opennxt.OpenNXT.protocol.serverProtNames.values
        for (n in UI_PACKET_NAMES) {
            if (table.containsKey(n)) resolved[n] = table.getInt(n) else missing.add(n)
        }
        if (missing.isNotEmpty()) {
            logger.warn { "replay949[ui]: ${missing.size} name(s) do not resolve in this build's " +
                "serverProtNames.toml and their packets will NOT be replayed: ${missing.joinToString()}" }
        }
        logger.warn { "replay949[ui]: interface layer = ${resolved.size} opcode(s): " +
            resolved.entries.joinToString { "${it.key}=${it.value}" } }
        resolved.values.toSet()
    }
    val delayTicks: Int = System.getProperty("opennxt.experiment.replay949.delayTicks")?.toIntOrNull() ?: (if (mode == "only") 1 else 6)
    val perTick: Int = System.getProperty("opennxt.experiment.replay949.perTick")?.toIntOrNull() ?: 200
    private val fromWorld = (System.getProperty("opennxt.experiment.replay949.from") ?: "world") != "all"
    /** The reference client WORLD/session state - never replayed: rebuild, player/npc info, tick end, timeout, zones, objs, locs, map flag, logout, lobby, worldlist, hint arrow, projectiles, camera. */
    private val worldSkip = setOf(64, 45, 90, 180, 227, 126, 109, 28, 41, 14, 12, 202, 186, 157, 211, 62, 1, 47, 57, 2, 58, 102, 136, 172, 83, 132, 163, 123)
    /** In `after` mode also skip what OUR login already sent or owns: varcache reset, inventories, stats, last-login, welcome message. */
    private val builtinSkip: Set<Int> get() = if (only) worldSkip else worldSkip + setOf(20, 8, 43, 10, 4, 77, 21)
    private val extraSkip: Set<Int> = System.getProperty("opennxt.experiment.replay949.skip")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    private val onlyOps: Set<Int>? = System.getProperty("opennxt.experiment.replay949.only")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()

    private val records: List<Rec> by lazy { load() }

    private fun load(): List<Rec> {
        val p = path ?: return emptyList()
        val f = File(p)
        if (!f.isFile) { logger.error { "replay949: $p is not a file - nothing will be replayed" }; return emptyList() }
        val out = ArrayList<Rec>()
        val opRe = Regex("\"op\"\\s*:\\s*(\\d+)")
        val seqRe = Regex("\"seq\"\\s*:\\s*(\\d+)")
        val dirRe = Regex("\"dir\"\\s*:\\s*\"([^\"]*)\"")
        val nameRe = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
        val hexRe = Regex("\"hex\"\\s*:\\s*\"([0-9a-fA-F]*)\"")
 // ONLY SERVER->CLIENT RECORDS., and it was letting real damage through.
        //
        // The Frida sessions interleave three kinds of line, all of which start with '{' and two of
        // which carry an "op": "s2c" (what we want), "c2s" (what the CLIENT sent) and "wire" (raw
        // TLS, no "op", so it was already skipped by the op parse). Nothing here filtered on "dir",
        // so every c2s record was loaded as if it were a server packet - and c2s records carry NO
        // "hex", so `bytes` came out EMPTY and [fitToWire] zero-padded it to the SERVER opcode of
 // the same number. Measured, 173 of them:
        //
        //   client NO_TIMEOUT       x97 -> server 0   UPDATE_RUNWEIGHT          (2 bytes of zero)
        //   client IF_UPDATE_COUNT  x27 -> server 89  CAM_REMOVEROOF
        //   client WINDOW_STATUS    x11 -> server 48  IF_OPENSUB_ACTIVE_PLAYER  (25 bytes of zero)
        //   client EVENT_MOUSE_MOVE  x6 -> server 4   UPDATE_STAT
        //   client IF_BUTTON1        x2 -> server 55  CAM_SHAKE
        //
        // i.e. spurious interface opens, camera ops and stat writes injected in recorded order into
        // the middle of the replay. On the 16-minute full observation it is 3,958 records, including 31
        // zero-filled IF_OPENSUB and 251 SET_MAP_FLAG. This is a replay tool whose whole purpose is
        // byte fidelity, so a silent injection of packets the protocol never contained is the worst
        // defect it could have had.
        //
        // Records with NO "dir" at all are kept: older sessions predate the field, and rejecting
        // them would silently empty the replay for exactly the files this tool was written against.
        var nonS2c = 0
        f.forEachLine { line ->
            if (!line.startsWith("{")) return@forEachLine
            val dir = dirRe.find(line)?.groupValues?.get(1)
            if (dir != null && dir != "s2c") { if (dir != "wire") nonS2c++; return@forEachLine }
            val op = opRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachLine
            val seq = seqRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: out.size
            val name = nameRe.find(line)?.groupValues?.get(1) ?: "?"
            val hex = hexRe.find(line)?.groupValues?.get(1) ?: ""
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            out.add(Rec(seq, op, name, bytes))
        }
        var start = 0
        if (fromWorld) {
            val idx = out.indexOfFirst { it.op == 64 }
            if (idx >= 0) start = idx + 1
        }
        // In `ui` the interface set is a WHITELIST and it is the whole rule: the skip sets exist to
        // subtract the reference client's world and account from a full replay, and a whitelist has already done
        // that by construction. Applying both would let a future addition to worldSkip silently
        // remove an interface packet from the UI layer for a reason that has nothing to do with it.
        val kept = out.drop(start).filter { r ->
            (onlyOps?.contains(r.op) ?: true) &&
                if (uiOnly) r.op in uiOps else (r.op !in builtinSkip && r.op !in extraSkip)
        }
        val hist = kept.groupingBy { "${it.op} ${it.name}" }.eachCount().entries.sortedByDescending { it.value }
        logger.warn {
            "replay949[$mode]: loaded ${out.size} server->client packet(s) from $p " +
                "(dropped $nonS2c client->server record(s): they carry an \"op\" but no \"hex\", so replaying " +
                "them injects zero-filled SERVER packets of the same opcode number); world part starts at $start; " +
                "${kept.size} will be replayed (${out.size - start - kept.size} skipped by opcode). Top: " +
                hist.take(12).joinToString { "${it.key} x${it.value}" }
        }
        return kept
    }

    /** Returns the number of packets still to send after this call. */
    fun step(player: WorldPlayer, cursor: Int): Int {
        val recs = records
        var i = cursor
        var n = 0
        while (i < recs.size && n < perTick) {
            val r = recs[i]
            val refused = suppressionReason(r, player)
            if (refused != null) {
                // Skipped, and SAID rather than silently dropped: a replay that quietly declines
                // to send something is exactly as hard to reason about as one that quietly adds
                // something, which is the defect this check exists to undo.
                if (suppressWarned.add(r.name + i)) logger.warn { "replay949: NOT replaying an open the server refuses - $refused" }
                i++
                continue
            }
            adoptIfOpen(player, r)
            player.client.write(UnidentifiedPacket(OpcodeWithBuffer(r.op, Unpooled.wrappedBuffer(fitToWire(r)))))
            i++; n++
        }
        if (n > 0) logger.info { "replay949: sent $n packet(s) [${cursor}..${i - 1}] of ${recs.size}" }
        return recs.size - i
    }

    fun size(): Int = records.size

    /**
     * Mirror every replayed IF_OPENTOP / IF_OPENSUB into the player's InterfaceManager: the
     * raw bytes bypass it, so without this the tree has no root and every server-side open throws. Decoded with the
     * registered 949 codecs by NAME (opcodes come from serverProtNames.toml, not hard-coded).
     */
    /**
     * The reason this record is an open the server refuses, or null.
     */
    private fun suppressionReason(r: Rec, player: WorldPlayer): String? {
        val reg = com.opennxt.net.game.PacketRegistry.getRegistration(com.opennxt.net.Side.SERVER, r.op) ?: return null
        if (reg.name != "IF_OPENSUB") return null
        return try {
            val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes)))
                as com.opennxt.net.game.serverprot.ifaces.IfOpenSub
            if (p.parent.parent != 1477) return null

 // THE ADDITIONAL ACTION BARS BELONG TO THE PLAYER, NOT TO THE RECORDING.
            //
 // opens all four - 1670@75, 1671@80, 1672@85,
            // 1673@90 - because the reference client account it was recorded from had all four switched on.
            // Replaying that verbatim re-opened every bar at every login on top of whatever this
            // player had actually chosen, which is why a bar that was turned off and saved came
            // back. The varps were right, the save was right, and the replay overrode both.
            //
            // Measured: with the client's own Settings.jcache moved aside (-FreshClient) and
            // PanelToggles opening 1 of 4, two bars still appeared. That ruled out both the
            // client's layout store and the varc replay, which is what left this.
            //
            // A observation is evidence about what the reference client sent to SOMEONE ELSE. Where this server has
            // its own per-character state - and PanelToggles now does - that state wins.
            com.opennxt.content.impl.PanelToggles.panelForBarMount(p.id, p.parent.component)?.let { panel ->
                if (!com.opennxt.content.impl.PanelToggles.isBarEnabled(player, panel)) {
                    return "${com.opennxt.content.impl.PanelToggles.describe(panel)} (interface ${p.id} at 1477:" +
                        "${p.parent.component}) - ${player.name} has it switched OFF " +
                        "(varp ${com.opennxt.content.impl.PanelToggles.varpFor(panel)}). The observation's " +
                        "account had it on; this player's own choice wins."
                }
            }

            com.opennxt.model.entity.player.InterfaceManager.loginSuppressionReason(p.id, p.parent.component)
        } catch (e: Exception) {
            null   // an undecodable record is the fitToWire path's problem, not this one's
        }
    }

    private fun adoptIfOpen(player: WorldPlayer, r: Rec) {
        val reg = com.opennxt.net.game.PacketRegistry.getRegistration(com.opennxt.net.Side.SERVER, r.op) ?: return
        when (reg.name) {
            "IF_OPENTOP" -> {
                val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes))) as com.opennxt.net.game.serverprot.ifaces.IfOpenTop
                player.interfaces.adoptTop(p.id)
            }
            "IF_OPENSUB" -> {
                val p = reg.codec.decode(com.opennxt.net.buf.GamePacketReader(Unpooled.wrappedBuffer(r.bytes))) as com.opennxt.net.game.serverprot.ifaces.IfOpenSub
                if (player.interfaces.adoptSub(p.id, p.parent.parent, p.parent.component, p.flag))
                    logger.info {
                        "replay949: adopted open ${com.opennxt.resources.Names949.iface(p.id)} at " +
                            com.opennxt.resources.Names949.component(p.parent.parent, p.parent.component) +
                            " (walkable=${p.flag})"
                    }
            }
        }
    }

    private val fitWarned = HashSet<Int>()
    /** Warn once per refused open, not once per packet. */
    private val suppressWarned = HashSet<String>()

    /**
     * The observation records what the client's DECODE HANDLER consumed (cursor delta), which is not always what the
     * client's size table pulls off the wire. RUN6: opcode 5 is fixed 35 in the client's own table
     * (serverprot_949_dispatch.tsv) but its handler advanced the cursor 36 - it over-reads one byte past the frame.
     */
    private fun fitToWire(r: Rec): ByteArray {
        val size = com.opennxt.OpenNXT.protocol.serverProtSizes.values.getOrDefault(r.op, Int.MIN_VALUE)
        if (size < 0 || size == r.bytes.size) return r.bytes
        if (fitWarned.add(r.op)) logger.warn { "replay949: opcode ${r.op} ${r.name} recorded ${r.bytes.size} byte(s) but the wire size is $size - ${if (r.bytes.size > size) "truncating" else "zero-padding"} every one" }
        return r.bytes.copyOf(size)
    }
}
