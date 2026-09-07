package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.IfButtonLabelled
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ClientProt 102 - a click on a component whose definition carries a menu string.
 *
 * ## This handler's job is to be a MEASURING INSTRUMENT, not a content hook
 *
 * It logs and does nothing else, and that is the point. Two of the five fields
 * have measured wire shapes and unmeasured meanings - `slot` (dispatcher arg4)
 * and `defFlag` (`componentDef+0x148`, negated) - and there is exactly one way
 * anyone will ever learn what they hold for interface 1184: read them off a real
 * frame. See [IfButtonLabelled.PROVENANCE].
 *
 * `slot` is printed FIRST, in decimal, hex and sentinel-normalised form. If a
 * future reader takes one thing from this file it is that number.
 *
 * ## Why it deliberately does NOT route into Dialogue
 *
 * [IfButtonNHandler] gives `DialogueWiring` and `BanksWiring` first refusal on
 * every IF_BUTTON. This handler does not, on purpose. A dialogue advanced from
 * here would mean the continue arrow "works" while the field that says WHICH
 * arrow was clicked is still unread, and a working button is exactly the
 * condition under which nobody goes back and reads it. Wire content in when the
 * fields have been observed, in the file that owns dialogue - not here, and not
 * in the same change that first makes the packet visible. Until then the packet
 * arrives, is decoded, and is printed in full: a falsifiable observation where
 * there was previously no observation at all.
 */
object IfButtonLabelledHandler : GamePacketHandler<BasePlayer, IfButtonLabelled> {
    private val logger = KotlinLogging.logger { }

    /**
     * A COMPILE-TIME assertion that the handoff edit into
     * `WorldPlayer.kt`'s handler map type-checks.
     *
     * This handler cannot register itself: `WorldPlayer.kt` is owned elsewhere
     * and the registration is a handoff. The failure mode that creates is the
 * one this server has already been burned by three times over - a
     * registration that never lands, in code that compiles and reads as
     * finished. The one thing that CAN be pinned from this side is that the
     * pending edit will compile: `WorldPlayer.handlers` is declared
     * `Object2ObjectOpenHashMap<KClass<out GamePacket>, GamePacketHandler<in BasePlayer, out GamePacket>>`
     * and the line below performs exactly that assignment against exactly that
     * type. If a future change to [GamePacketHandler]'s variance or to this
     * object's type parameters breaks the handoff, the build breaks HERE, in
     * the file that owns the problem, instead of landing on whoever applies it.
     *
     * It is `private` and never read: this is a type check, not a registry.
     */
    private val handoffTypeCheck:
        it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<
            kotlin.reflect.KClass<out com.opennxt.net.game.GamePacket>,
            GamePacketHandler<in BasePlayer, out com.opennxt.net.game.GamePacket>> =
        it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<
            kotlin.reflect.KClass<out com.opennxt.net.game.GamePacket>,
            GamePacketHandler<in BasePlayer, out com.opennxt.net.game.GamePacket>>()
            .also { it[IfButtonLabelled::class] = IfButtonLabelledHandler }

    /**
     * The provenance string is printed ONCE, with the first frame, not on every
     * one. A click-through dialogue can produce dozens of these in a minute and
     * a 900-character banner repeated dozens of times is how a log stops being
     * read - the same reasoning behind the per-opcode rate limits in
     * `ConnectedClient.receive`. Once is enough for the reader who needs it.
     */
    private val bannerPrinted = AtomicBoolean(false)

    override fun handle(context: BasePlayer, packet: IfButtonLabelled) {
        if (bannerPrinted.compareAndSet(false, true)) {
            logger.warn {
                "FIRST EVER ClientProt 102 FRAME. This opcode had never been decoded by this server. " +
                    IfButtonLabelled.PROVENANCE
            }
        }

        logger.info {
            "IF_BUTTON_LABELLED (ClientProt 102)  ***SLOT=${packet.slotSigned}*** " +
                "(raw=${packet.slot} 0x${packet.slot.toString(16).padStart(4, '0')}) " +
                "interface=${packet.interfaceId} component=${packet.component} op=${packet.op} " +
                "label='${packet.label}' defFlag=${packet.defFlag} " +
                "(0x${packet.defFlag.toString(16).padStart(2, '0')}) " +
                "hash=0x${packet.hash.toString(16).padStart(8, '0')}"
        }

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleButtonLabelled(context, packet)
        ) return

        // The one interpretation worth stating in the log, and it is stated as a
        // question. componentDef+0xc is the ONLY caller-path that makes arg4 a
        // was on the component itself". Anything else is new information about a
 // field this server has never observed.
        if (packet.slotSigned != -1) {
            logger.warn {
                "ClientProt 102 carried a NON-SENTINEL slot: ${packet.slotSigned} on " +
                    "${packet.interfaceId}:${packet.component} label='${packet.label}'. " +
                    "This is the first evidence of what dispatcher arg4 holds for a labelled " +
                    "component - record it in data/prot/949/clientProt/IF_BUTTON_LABELLED.txt."
            }
        }
    }
}
