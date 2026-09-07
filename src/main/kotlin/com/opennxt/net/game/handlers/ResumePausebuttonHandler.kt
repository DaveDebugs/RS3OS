package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.ResumePausebutton
import com.opennxt.net.game.pipeline.GamePacketHandler

/**
 * RESUME_PAUSEBUTTON (ClientProt 127) - the dialogue "click to continue" click:
 * int component hash (iface<<16 | comp) + ushort128 sub-component.
 */
object ResumePausebuttonHandler : GamePacketHandler<BasePlayer, ResumePausebutton> {
    override fun handle(context: BasePlayer, packet: ResumePausebutton) {
        val interfaceId = (packet.component ushr 16) and 0xffff
        val component = packet.component and 0xffff
        if (context is WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleObservedComponentClick(context, interfaceId, component)
        ) return
 // The make-X panel's CONFIRM. 1370:30 carries optmask 1 in the cache - bit 0,
        // the pausebutton bit, which is not an op - so its click can only ever arrive here and
        // never as an IF_BUTTON. Ten frames over six sessions, and zero IF_BUTTON* on 1370:30
        // anywhere in the corpus. After the dialogue, which owns this opcode's other use.
        if (context is WorldPlayer &&
            interfaceId == com.opennxt.content.impl.MakeXPanel.IFACE &&
            component == com.opennxt.content.impl.MakeXPanel.CONFIRM &&
            com.opennxt.content.impl.MakeXPanel.handleConfirm(context)
        ) return
        DecodedPacketLogHandler.handle(context, packet)
    }
}
