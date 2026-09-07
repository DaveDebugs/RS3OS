package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.ObservedClientPacket
import com.opennxt.net.game.clientprot.ObservedStructure
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records opcodes whose framing is known and whose meaning is not, and does
 * nothing else with them. See [ObservedClientPacket] for why they are consumed
 * rather than dropped.
 *
 * RATE LIMIT
 *   First [FULL_SAMPLES] occurrences of an opcode are logged with their bytes,
 * then every [SAMPLE_EVERY]th. Measured reason: in the 60s run opcode 98
 *   arrived 44 times and opcode 4 arrived 31 times, ~250 bytes each. At one
 *   line per packet those two alone produce 75 log lines and ~19 KB of hex per
 *   minute, which is precisely the "everything else is invisible" failure the
 *   drop warning already had.
 *
 * LOG LEVEL
 * INFO, not DEBUG. This server logs through slf4j-simple with no
 *   configuration file, so its default level is INFO and a DEBUG line would be
 *   emitted nowhere at all - which would satisfy the letter of "low level" and
 *   destroy the entire purpose, since the bytes are the only asset these
 *   packets have. INFO with the rate limit above is a strict reduction from the
 *   WARN-per-packet this replaces. Tunable:
 *
 *       -Dopennxt.prot.observedSamples=3 -Dopennxt.prot.observedEvery=100
 */
object ObservedClientPacketHandler : GamePacketHandler<BasePlayer, ObservedClientPacket> {
    private val logger = KotlinLogging.logger { }

    val FULL_SAMPLES: Int = System.getProperty("opennxt.prot.observedSamples")?.toIntOrNull() ?: 3
    val SAMPLE_EVERY: Int = (System.getProperty("opennxt.prot.observedEvery")?.toIntOrNull() ?: 100)
        .coerceAtLeast(1)

 // RETIRED - kept as a note, not as a doc comment on the field
    // below.

    private val seen = ConcurrentHashMap<Int, AtomicInteger>()

    /** Occurrences of [opcode] this process has handled. Test/inspection hook. */
    fun count(opcode: Int): Int = seen[opcode]?.get() ?: 0

    /**
     * The one observed opcode that gets acted on rather than only recorded.
     *
     * 127 is a component click whose LAYOUT is confirmed and whose NAME is not,
     * and the sole frame ever recorded carried 1184:15 - the dialogue continue
     * arrow - from inside a dialogue window. It was dropped then and merely
     * counted since, which is why every dialogue session in every session ends
     * at `clicks 0`.
     *
     * Routing it to the dialogue is safe BECAUSE the dialogue refuses it by
     * default: `Dialogue.onButton` takes only components the OPEN PAGE armed and
     * WARNs on anything else. So this cannot turn an unrelated panel click into
     * a conversation advance, and it converts an untestable hypothesis into a
     * one-run experiment. See
     * [com.opennxt.content.impl.DialogueWiring.handleObservedComponentClick] for
     * the full evidence and the mount caveat (test with 747, not 750).
     */
    private const val COMPONENT_CLICK_OPCODE = 127

    private const val PANEL_CLOSE_OPCODE = 89

    /**
     * ClientProt 67 - the client-closed-its-interfaces notification (CS2
     * command 784). Routed to GlobalCloseWiring.handleClientClosedInterfaces,
     * which reconciles the server's interface model and sends nothing.
     */
    private const val CLIENT_CLOSED_INTERFACES_OPCODE = 67

    override fun handle(context: BasePlayer, packet: ObservedClientPacket) {
        val n = seen.computeIfAbsent(packet.opcode) { AtomicInteger() }.incrementAndGet()

        if (packet.opcode == PANEL_CLOSE_OPCODE && packet.payload.size == 4 &&
            context is com.opennxt.model.world.WorldPlayer
        ) {
            val panelId = (packet.u8(0) shl 24) or (packet.u8(1) shl 16) or (packet.u8(2) shl 8) or packet.u8(3)
            if (com.opennxt.content.impl.PanelCloseWiring.handlePanelClose(context, panelId)) return
        }

        if (packet.opcode == CLIENT_CLOSED_INTERFACES_OPCODE &&
            context is com.opennxt.model.world.WorldPlayer
        ) {
            com.opennxt.content.impl.GlobalCloseWiring.handleClientClosedInterfaces(context)
            // fall through to the census/log below - 67's occurrences stay visible
        }

        if (packet.opcode == COMPONENT_CLICK_OPCODE && context is com.opennxt.model.world.WorldPlayer) {
            // u16(0)/u16(2) are the two halves of the confirmed
            // `(interface shl 16) or component` int; both are -1 if the frame is
            // short, and a negative id can never be in an armed set.
            val interfaceId = packet.u16(0)
            val component = packet.u16(2)
            if (interfaceId >= 0 && component >= 0 &&
                com.opennxt.content.impl.DialogueWiring
                    .handleObservedComponentClick(context, interfaceId, component)
            ) return
        }

        if (n <= FULL_SAMPLES || n % SAMPLE_EVERY == 0) {
            // The STRUCTURAL reading goes on the same line as the bytes, when
            // this build has a recovered layout for the opcode - see
            // [ObservedStructure] for why those layouts live there instead of
            // in packet classes, and for the honesty rule it follows. The hex
            // stays: a reading that disagrees with the bytes it was derived
            // from has to be checkable from the log alone, and an ANOMALY line
            // is worth nothing without the frame that produced it.
            val structure = ObservedStructure.describe(packet)
            logger.info {
                "observed client opcode ${packet.opcode} (#$n, ${packet.payload.size} bytes): ${packet.hex()}" +
                    (if (structure == null) "" else "  |  $structure")
            }
        }
    }
}
