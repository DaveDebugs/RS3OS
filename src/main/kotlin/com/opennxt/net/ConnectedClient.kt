package com.opennxt.net

import com.opennxt.model.proxy.PacketDumper
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.PacketRegistry
import com.opennxt.net.game.clientprot.ObservedClientPacket
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.ProxyChannelAttributes
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles incoming packets on the lowest possible level. This is usually called directly from the Netty pipeline, and
 *   the [incomingQueue] is polled from the main thread. This way packets are received and decoded async, and handled
 *   sync.
 *
 * This also handles sending packets to the other side of the channel.
 *
 * This class can be used for both the server (Where clients connect to) and the client (Which connects to a server).
 *   It is, for example, used in the proxy as well.
 *
 * [side] represents the side of the remote. This means the server uses side "Client".
 */
class ConnectedClient(
    val side: Side,
    val channel: Channel,
    var processUnidentifiedPackets: Boolean = false,
    var dumper: PacketDumper? = null
) {

    val logger = KotlinLogging.logger { }

    val incomingQueue = ConcurrentLinkedQueue<GamePacket>()

    var initedPlayerList = false

    /** Occurrence count per unregistered inbound opcode, for the rate limit in [receive]. */
    private val unregisteredSeen = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /**
     * Set the first time this connection sends us a game packet.
     *
     * "The client has spoken" is a much better readiness signal than any timer,
     * and it is now known to matter. Measured across four runs: given only
     * interface packets the client answers 0.45s after login and lives 50s+;
     * given the 1,385-varp replay it answers NEVER and resets 5.7-6.2s after
     * login - the same deadline whether it received 516 varps or 1,385, so it
     * is a timeout on the client's side, not a bad varp and not the burst rate.
     *
     * Waiting for this flag before pushing bulk state is therefore the
     * principled order: say nothing large until the far end has proved it is
     * listening.
     */
    @Volatile
    var clientHasSpoken: Boolean = false
        private set

    fun receive(pair: OpcodeWithBuffer) {
        try {
            clientHasSpoken = true
            dumper?.dump(pair.opcode, pair.buf)

            // Diagnostic only (opt-in, -Dopennxt.diag=true): first decoded game
            // frame proves this connection reached the game pipeline.
            DiagnosticLog.markGameStage(channel)

            val registration = PacketRegistry.getRegistration(side, pair.opcode)
            if (registration == null) {
                // Diagnostic only: this packet is about to be dropped silently
                // (the proxy-mode branch below is the only thing that ever saw it).
                // Record its shape - opcode, size, first bytes - rate limited per
                // opcode, so an unknown 2026 protocol is legible instead of absent.
                // The drop itself is unchanged.
                DiagnosticLog.unregisteredOpcode(channel, side, pair.opcode, pair.buf)

                // ...and say so in the DEFAULT configuration as well.
                //
                // Until this line existed, an inbound frame with no registered
                // codec was discarded with ZERO output at any level: the
                // diagnostic call above returns immediately unless
                // -Dopennxt.diag=true, and nothing else logged anything. On the
                // current build that is not an edge case - the boot log says
                // "0 client packets registered" while framing works
                // (clientProtSizes has 154 entries), so everything the client
                // sends after login is correctly delimited and then thrown away
                // in silence. A run without the diagnostic flag could not
                // distinguish "the client sent nothing" from "the client sent
                // 400 packets and we dropped every one" - and that distinction
                // is the whole question this server is being run to answer.
                //
                // Rate limited per opcode (first three, then every 500th) so a
                // chatty unknown opcode cannot bury the rest of the log, which
                // is the failure the send budgets elsewhere exist to avoid.
                val seen = unregisteredSeen.merge(pair.opcode, 1, Int::plus) ?: 1
                if (seen <= 3 || seen % 500 == 0) {
                    logger.warn {
                        "Dropping inbound packet with no registered codec: opcode=${pair.opcode} " +
                            "size=${pair.buf.readableBytes()} side=$side (occurrence $seen). " +
                            "Run with -Dopennxt.diag=true to record its bytes."
                    }
                }

                if (processUnidentifiedPackets)
                    incomingQueue.add(UnidentifiedPacket(OpcodeWithBuffer(pair.opcode, pair.buf.copy())))
                return
            }

            // TODO How can we do the following in a better way? This is getting very spaghetti.
            // TODO Clean up the following code...
            if (registration.name == "REBUILD_NORMAL" && !initedPlayerList) {
                val copy = UnidentifiedPacket(OpcodeWithBuffer(pair.opcode, pair.buf.copy()))
                incomingQueue.add(copy)

                val playerIndex = channel.attr(ProxyChannelAttributes.PLAYER_INDEX).get()
                println("RECEIVED REBUILD NORMAL -- DECODE PLAYER LIST FIRST - PLAYER INDEX IS $playerIndex")
                initedPlayerList = true

                val reader = GamePacketReader(pair.buf)
                reader.switchToBitAccess()
                reader.getBits(0x1e)
                for (i in 1 until 2048) {
                    if (i == playerIndex) {
                        println("I IS PLAYER INDEX @ $i")
                        continue
                    }

                    reader.getBits(0x14)
                }
                reader.switchToByteAccess()

                val decoded = registration.codec.decode(reader)
                if (pair.buf.readableBytes() != 0 ){
                    logger.warn { "Readable bytes in packet ${registration.name}: ${pair.buf.readableBytes()}" }
                }

                logger.info { decoded.toString() }
                return
            }

            val decoded = registration.codec.decode(GamePacketReader(pair.buf))
            if (pair.buf.readableBytes() != 0 ){
                logger.warn { "Readable bytes in packet ${registration.name}: ${pair.buf.readableBytes()}" }
            }

            incomingQueue.add(decoded)
        } catch (e: Exception) {
            // Was e.printStackTrace(): stderr, no channel, no peer, no opcode,
            // no correlation to anything else in the log - in a tree where every
            // other error path was deliberately rewritten to carry that context.
            // The opcode is the one fact that makes this actionable.
            logger.error(e) {
                "Failed to decode inbound packet opcode=${pair.opcode} side=$side " +
                    "from ${channel.remoteAddress()}; packet dropped"
            }
        } finally {
            pair.buf.release()
        }
    }

    fun write(pair: OpcodeWithBuffer) {
        channel.write(pair)
    }

    fun write(packet: GamePacket) {
        if (packet is UnidentifiedPacket) {
            write(packet.packet)
            return
        }

        // Observed packets carry their OWN opcode and are written back
        // verbatim, bypassing the class->registration lookup below.
        //
        // That lookup would be wrong here, not merely redundant: fourteen
        // opcodes share the single ObservedClientPacket class, so a class map
        // entry could name only one of them and the proxy would forward every
        // observed packet under that one opcode. This path matters because
        // ConnectedProxyClient.tick forwards every decoded packet to the far
        // side - before these opcodes were registered they went through the
        // UnidentifiedPacket branch above and were forwarded raw, and that
        // behaviour has to survive them becoming registered.
        if (packet is ObservedClientPacket) {
            write(OpcodeWithBuffer(packet.opcode, Unpooled.wrappedBuffer(packet.payload)))
            return
        }

        try {
            val registration =
                PacketRegistry.getRegistration(if (side == Side.CLIENT) Side.SERVER else Side.CLIENT, packet::class)

            if (registration == null) {
                logger.warn("Registration not found for packet $packet side $side")
                return
            }

            val buffer = Unpooled.buffer()
            @Suppress("UNCHECKED_CAST")
            (registration.codec as GamePacketCodec<GamePacket>).encode(packet, GamePacketBuilder(buffer))

            channel.write(OpcodeWithBuffer(registration.opcode, buffer))
        } catch (e: Exception) {
            // Was e.printStackTrace(). Same reason as in receive(): name the
            // packet that failed to encode and the connection it was going to,
            // otherwise a stderr trace floats free of every other line in the run.
            logger.error(e) {
                "Failed to encode outbound packet ${packet::class.simpleName} side=$side " +
                    "to ${channel.remoteAddress()}; packet dropped"
            }
        }
    }

    fun flush() {
        channel.flush()
    }
}