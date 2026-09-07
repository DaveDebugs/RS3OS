package com.opennxt.net.game.serverprot

import com.opennxt.net.game.GamePacket

/**
 * CAM_RESET - ServerProt 106, size 0 (`serverProtSizes.toml`), a body-less packet.
 */
object CamReset : GamePacket {
    override fun toString(): String = "CamReset"
}
