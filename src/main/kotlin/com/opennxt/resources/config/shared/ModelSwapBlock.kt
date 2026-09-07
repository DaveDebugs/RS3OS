package com.opennxt.resources.config.shared

import com.opennxt.ext.getSmartInt
import com.opennxt.ext.skip
import java.nio.ByteBuffer

/**
 * The per-value replacement block that npcs (opcode 186) and locs (opcode 205) both carry.
 *
 * A varbit or varp selects a value, and each value can swap in different models, heads, textures or
 * colours. The leading flag is a bitmask over those four lists, and the lists do not all have the
 * same entry shape - the body-model list carries a count of extra models per entry, the head list
 * does not, and the texture list pairs each texture with its replacement. Two bytes close the block.
 *
 * Nothing in the server reads any of this. It is parsed only so the rest of the record stays in
 * sync, but it has to be parsed exactly, because a byte out here corrupts every field after it.
 */
object ModelSwapBlock {
    fun ByteBuffer.readModelSwapBlock() {
        skip(2)                                   // a counter that varies per record
        skip(2); skip(2)                          // varbit, varp
        val lists = get().toInt() and 0xff
        if ((lists and 1) != 0) list(extras = true, pairs = false)
        if ((lists and 2) != 0) list(extras = false, pairs = false)
        if ((lists and 4) != 0) list(extras = false, pairs = true)
        if ((lists and 8) != 0) list(extras = false, pairs = false)
        skip(2)
    }

    private fun ByteBuffer.list(extras: Boolean, pairs: Boolean) {
        repeat(get().toInt() and 0xff) {
            skip(1)                               // the value this entry applies to
            repeat(get().toInt() and 0xff) {
                skip(2); skip(2)
                getSmartInt()
                if (pairs) getSmartInt()
                if (extras) repeat(get().toInt() and 0xff) { getSmartInt() }
            }
        }
    }
}
