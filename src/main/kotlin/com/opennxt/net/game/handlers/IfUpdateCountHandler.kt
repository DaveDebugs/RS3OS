package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.IfUpdateCount
import com.opennxt.net.game.pipeline.GamePacketHandler

object IfUpdateCountHandler : GamePacketHandler<BasePlayer, IfUpdateCount> {
    override fun handle(context: BasePlayer, packet: IfUpdateCount) {
        if (context is WorldPlayer && com.opennxt.content.impl.PanelCloseWiring.handlePanelClose(context, packet.count)) return
        DecodedPacketLogHandler.handle(context, packet)
    }
}
