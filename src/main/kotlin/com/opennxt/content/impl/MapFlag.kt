package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.SetMapFlag
import mu.KotlinLogging

/**
 * The walk destination marker - the yellow X under the cursor's target tile.
 */
object MapFlag {

    private val logger = KotlinLogging.logger { }

    /** Constant on all 34 recorded frames. Meanings unknown; bytes reproduced. */
    private const val UNK1 = 255
    private const val UNK4 = 0
    private const val UNK5 = 255
    private const val UNK6 = 255
    private const val NO_TARGET = -1

    /** The local pair the reference client sends when there is no destination. Out of scene by construction. */
    const val CLEAR_LOCAL = 255

    /** Scene side length in tiles at mapSize 256, and therefore the local coordinate range. */
    const val SCENE = 256

    /**
     * The world tile the loaded scene starts at, mirroring
     * [com.opennxt.model.entity.player.Viewport.createPacket]'s chunk arithmetic.
     * Kept as a pure function of the base tile so the check can exercise it
     * without a player.
     */
    fun base(baseTileX: Int, baseTileY: Int): Pair<Int, Int> =
        (baseTileX / 8 - 16) * 8 to (baseTileY / 8 - 16) * 8

    /** The frame the reference client sends for a destination at ([localX], [localY]) in scene coordinates. */
    fun packet(localX: Int, localY: Int) = SetMapFlag(
        target = NO_TARGET,
        unk1 = UNK1,
        localY = localY,
        localX = localX,
        unk4 = UNK4,
        unk5 = UNK5,
        unk6 = UNK6
    )

    /**
     * Show the marker on world tile ([x], [y]).
     *
     * Returns false and sends nothing when the tile is outside the scene the
     * client has loaded. That is not defensive padding: the local pair is one
     * unsigned byte per axis, so an out-of-scene tile does not merely look
     * wrong, it WRAPS - and a marker 200 tiles from where the player clicked is
     * worse than no marker, because it looks like a coordinate bug in the
     * decoders that were just confirmed.
     */
    fun set(player: WorldPlayer, x: Int, y: Int): Boolean {
        val bt = player.viewport.baseTile
        val (bx, by) = base(bt.x, bt.y)
        val lx = x - bx
        val ly = y - by
        if (lx !in 0 until SCENE || ly !in 0 until SCENE) {
            logger.debug {
                "map flag NOT sent for ($x,$y): local ($lx,$ly) is outside the loaded scene " +
                    "based at ($bx,$by). The client's scene has moved, or the click was not in it."
            }
            return false
        }
        player.client.write(packet(lx, ly))
        return true
    }

    /** Remove the marker. See the class note - nothing calls this on arrival yet. */
    fun clear(player: WorldPlayer) {
        player.client.write(packet(CLEAR_LOCAL, CLEAR_LOCAL))
    }
}
