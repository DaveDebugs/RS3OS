package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket

/**
 * RESET_ANIMS - ServerProt 46, size 0 (`serverProtSizes.toml`), a body-less packet.
 */
object ResetAnims : GamePacket {
    override fun toString(): String = "ResetAnims"
}
