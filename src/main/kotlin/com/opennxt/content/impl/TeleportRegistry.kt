package com.opennxt.content.impl

import com.opennxt.model.world.TileLocation
import mu.KotlinLogging

/**
 * A registry for authored loc destinations.
 *
 * In RS, destinations for Stairs and Ladders are not stored in the cache; they are
 * manually authored server-side scripts. This registry provides a place to map
 * specific loc interactions to specific tile destinations.
 */
object TeleportRegistry {
    private val logger = KotlinLogging.logger { }

    /**
     * Authored manual teleports.
     * Key: `"<action> <x>_<y>_<plane>"` (e.g., `"Climb-up 3204_3207_0"`)
     * Value: Destination `TileLocation`
     */
    private val manualDestinations = mapOf(
        // Lumbridge Castle Entrance Stairs
        "Climb-up 3204_3207_0" to TileLocation(3205, 3209, 0),
        "Climb-down 3205_3209_0" to TileLocation(3205, 3206, 0)
    )

    /**
     * Checks if there is a manually authored teleport destination for the clicked loc.
     */
    fun getDestination(action: String, locX: Int, locY: Int, plane: Int): TileLocation? {
        val key = "$action ${locX}_${locY}_$plane"
        val destination = manualDestinations[key]
        if (destination != null) {
            logger.info { "TeleportRegistry: routed $key to $destination" }
        }
        return destination
    }
}
