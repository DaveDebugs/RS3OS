package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.generated.IfButtond
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * IF_BUTTOND (ClientProt 25) - the interface manager's DRAG report: source component (A, the thing
 * picked up) dropped on target component (B), with the item slot/id of each side when they are
 * inventory cells (65535 / 16777215 = none).
 */
object IfButtondHandler : GamePacketHandler<BasePlayer, IfButtond> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: IfButtond) {
        val srcIf = (packet.sourcehash ushr 16) and 0xffff
        val srcC = packet.sourcehash and 0xffff
        val tgtIf = (packet.targethash ushr 16) and 0xffff
        val tgtC = packet.targethash and 0xffff
        logger.info {
            "IF_BUTTOND drag: $srcIf:$srcC (slot ${packet.sourceslot}, obj ${packet.sourceobj}) -> " +
                "$tgtIf:$tgtC (slot ${packet.targetslot}, obj ${packet.targetobj})" +
                (if (tgtIf == 1477) " [gameframe component - a window frame/slot drop]" else "")
        }
        DecodedPacketLogHandler.handle(context, packet)
    }
}
