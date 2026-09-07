package com.opennxt.net.game.handlers

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.MoveGameClick
import com.opennxt.net.game.clientprot.MoveMinimapClick
import com.opennxt.net.game.pipeline.GamePacketHandler

object MoveMinimapClickHandler : GamePacketHandler<WorldPlayer, MoveMinimapClick> {
    override fun handle(context: WorldPlayer, packet: MoveMinimapClick) {
        MoveGameClickHandler.handle(context, MoveGameClick(packet.x, packet.y, packet.flags))
    }
}
