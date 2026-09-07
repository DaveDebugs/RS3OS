package com.opennxt.net.game.handlers

import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.CookingWiring
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.Oploct
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

/**
 * OPLOCT (ClientProt 116) - "use the item I have selected on that scenery object".
 */
object OpLocTHandler : GamePacketHandler<BasePlayer, Oploct> {

    private val logger = KotlinLogging.logger { }

    /** The client's "nothing selected" `selobj`. */
    const val NO_SELECTION = 0xffffff

    /** The backpack panel the four sessions select from: 1473:5. */
    const val BACKPACK_HASH = (1473 shl 16) or 5

    /**
     * The real loc id from the `loc` field the generated codec produced.
     *
     * `intv1` writes the bytes b2 b3 b0 b1 and `intv2` writes b1 b0 b3 b2, so one is the other's
     * byte reversal: reversing the intv1 value recovers the intv2 value with no byte juggling.
     */
    fun locIdOf(rawLocField: Int): Int = Integer.reverseBytes(rawLocField)

    override fun handle(context: BasePlayer, packet: Oploct) {
        if (context !is WorldPlayer) { DecodedPacketLogHandler.handle(context, packet); return }

        val locId = locIdOf(packet.loc)
        val iface = (packet.selhash ushr 16) and 0xffff
        val component = packet.selhash and 0xffff
        val plane = context.entity.location.plane
        val def = if (RsDatabase.available) SqliteLocCodec.load(locId) else null
        val itemName = com.opennxt.content.impl.Skilling.itemNameOf(packet.selobj)

        logger.info {
            "OPLOCT ${context.name} used item ${packet.selobj}${if (itemName != null) " ('$itemName')" else ""} " +
                "from $iface:$component slot ${packet.selsub} on loc $locId" +
                (if (def != null) " ('${def.name}')" else " (no definition)") +
                " at (${packet.x},${packet.y},plane $plane)" +
                (if (packet.ctrl != 0) " ctrl=${packet.ctrl}" else "") +
                " [raw loc field 0x${Integer.toHexString(packet.loc)} read as intv2]"
        }

        // ---- anti-client-trust, before anything is dispatched --------------------------------
        //
        // The client chooses all six of these numbers. Three of them can be checked against
        // something that is not the client, and all three are checked here rather than inside
        // content, because a refusal has to happen once, at the edge.

        if (packet.selobj <= 0 || packet.selobj == NO_SELECTION) {
            logger.warn { "OPLOCT: selobj ${packet.selobj} is not an item id - REFUSED" }
            return
        }
        if (packet.selhash != BACKPACK_HASH) {
            // Not a refusal: only one source has ever been observed, so anything else is news
            // rather than abuse, and saying so is how the next source gets found.
            logger.info { "OPLOCT: selection came from $iface:$component, not the backpack 1473:5 (unmeasured source)" }
        }
        // The item must ACTUALLY be in the slot the client names. This is the check that stops a
        // forged (item, slot) pair from consuming something the player does not hold: the backpack
        // here is PlayerInventory.backpackOf, the same container the save persists.
        val backpack = PlayerInventory.backpackOf(context)
        val held = if (packet.selsub in 0 until backpack.size) backpack[packet.selsub] else null
        if (held == null || held.id != packet.selobj) {
            logger.warn {
                "OPLOCT: ${context.name} claims item ${packet.selobj} in backpack slot ${packet.selsub}, " +
                    "which holds ${held?.id ?: "nothing"} - REFUSED"
            }
            return
        }
        // The loc must be where the client says it is. Same rule and same footprint allowance as
        // OpLocHandler: map_loc keeps one row per placement, at the origin, so a multi-tile loc
        // leaves its other tiles with no row.
        if (RsDatabase.available) {
            val placed = OpLocHandler.placementsAt(packet.x, packet.y, plane).any { it.locId == locId }
            val covered = placed || LocInteraction.placementsCovering(packet.x, packet.y, plane).any { it.locId == locId }
            if (!covered) {
                // A fire is placed by LOC_ADD_CHANGE at runtime and is NOT in map_loc at all, so a
                // missing row here is expected for exactly the object this handler exists for.
                // Reported, never enforced - the adjacency check below is the one that binds.
                logger.info {
                    "OPLOCT: map_loc does not place loc $locId at (${packet.x},${packet.y},plane $plane). " +
                        "Expected for a runtime-placed loc such as a fire; not a refusal."
                }
            }
        }
        // Range. No walk is armed: the reference client answered all four of these clicks on the click tick
        // itself with the player already standing beside the fire, so walking-then-acting is
        // behaviour nothing has measured. Out of range is refused rather than invented.
        val px = context.entity.location.x
        val pz = context.entity.location.y
        if (Math.abs(px - packet.x) > INTERACT_RANGE || Math.abs(pz - packet.y) > INTERACT_RANGE) {
            logger.info {
                "OPLOCT: ${context.name} is at ($px,$pz), loc $locId is at (${packet.x},${packet.y}) - " +
                    "further than $INTERACT_RANGE tiles; nothing done (no walk is armed for OPLOCT)"
            }
            return
        }

        // ---- dispatch -----------------------------------------------------------------------
        // bind, THEN dispatch, for the reason OpLocHandler.gatherIfResource states: content
        // reaches the real backpack, level and xp only through the ContentPlayer -> WorldPlayer
        // pairing, and this is the only place holding both objects.
        val content = context.contentPlayer
        CookingWiring.bind(content, context)
        content.location = com.opennxt.model.world.TileLocation(px, pz, plane)

        val result = ContentRegistry.dispatchItemOnLoc(
            player = content, itemId = packet.selobj, locId = locId, slot = packet.selsub,
            x = packet.x, z = packet.y, plane = plane
        )
        when (result) {
            is DispatchResult.Handled -> logger.info { "OPLOCT: handled - ${result.value}" }
            else -> {
                logger.info { "OPLOCT: $result" }
                DecodedPacketLogHandler.handle(context, packet)
            }
        }
    }

    /**
     * Chebyshev tiles between the player and the clicked tile that still count as "at the fire".
     * 1 would be the strict adjacency `OpLocHandler.adjacentToFootprint` uses; a fire is a 1x1
     * walkable loc the player can stand ON (`locs.walkable = 1` for 70755), so 1 covers both
     * standing on it and standing beside it, and nothing wider was measured.
     */
    const val INTERACT_RANGE = 1
}
