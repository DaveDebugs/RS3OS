package com.opennxt.net.game.handlers

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.EventKeyboard
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Handles keyboard events from the client (949 opcode 77, var-short).
 *
 * PRIMARY PURPOSE: intercept ESC key presses and close open interfaces.
 *
 * In live RS3, the ESC key is bound to a gameframe component via the CS2
 * `setopkey` command during interface load. When ESC is pressed, the client
 * sends an IF_BUTTON on that component, and the server handles the close.
 * However, the CS2 keybind chain is not fully functional in this build
 * (likely due to missing varcs that the onload scripts depend on), so ESC
 * arrives here as a raw keyboard event instead of as an IF_BUTTON.
 *
 * This handler checks the key code byte and, for VK_ESCAPE (0x1B = 27),
 * closes any open modal interfaces or dialogues. All other key events are
 * logged at DEBUG level for future reference.
 *
 * Typed on [WorldPlayer] because keyboard input is only meaningful in-game.
 */
object EventKeyboardHandler : GamePacketHandler<WorldPlayer, EventKeyboard> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: WorldPlayer, packet: EventKeyboard) {
        // Log every keyboard event at debug level for protocol research.
        logger.debug { "EVENT_KEYBOARD ${context.name}: $packet" }

        if (packet.isEscape) {
            logger.info { "EVENT_KEYBOARD ${context.name}: ESC pressed, closing modals" }

            // Close any open modal sub-interfaces (dialogues, settings overlays, etc.)
            val closedAny = context.interfaces.closeModals()
            
            // If nothing was closed, open the options/settings menu
            if (!closedAny) {
                logger.info { "EVENT_KEYBOARD ${context.name}: No modals were open, opening Settings (1433)" }
                context.interfaces.open(com.opennxt.content.interfaces.InterfaceSlot.CENTRAL_INTERFACE, 1433, walkable = false)
            }
        }
    }
}
