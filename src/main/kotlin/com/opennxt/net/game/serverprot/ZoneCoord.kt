package com.opennxt.net.game.serverprot

/**
 * The two packed bytes every member of the zone content family carries, in one
 * place so no call site packs them by hand.
 *
 * Both were read off the client, not assumed - see each function.
 */
object ZoneCoord {

    /**
     * The per-tile byte: which of the 64 tiles inside the current 8x8 zone.
     */
    fun coord(worldX: Int, worldY: Int): Int = ((worldX and 7) shl 4) or (worldY and 7)

    /**
     * The packed shape/rotation byte shared by LOC_ADD_CHANGE, LOC_DEL and
     * LOC_ANIM: `(shape shl 2) or rotation`, with BIT 7 CLEAR.
     */
    fun shapeRotation(shape: Int, rotation: Int): Int {
        require(shape in 0..31) { "loc shape must be 0..31 (5 bits at (v shr 2) and 0x1f), was $shape" }
        require(rotation in 0..3) { "loc rotation must be 0..3 (2 bits at v and 3), was $rotation" }
        val packed = (shape shl 2) or rotation
        // Unreachable given the two requires; asserted anyway because a set bit
        // 7 is the one failure here that reads past the frame instead of
        // rendering something wrong, and it must never be reachable by edit.
        check(packed and 0x80 == 0) { "shapeRotation bit 7 must be clear (extra-block grammar unrecovered): $packed" }
        return packed
    }
}
