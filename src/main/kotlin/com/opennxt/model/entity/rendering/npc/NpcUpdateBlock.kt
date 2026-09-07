package com.opennxt.model.entity.rendering.npc

import com.opennxt.net.buf.GamePacketBuilder

/**
 * One NPC extended-info block.
 *
 * Deliberately NOT a subclass of
 * [com.opennxt.model.entity.rendering.UpdateBlock]: that type's `encode` takes
 * a viewer and an `Entity`, because the player side needs both, and this side
 * needs neither -- an npc block is a pure function of the npc's queued state.
 * More importantly, [com.opennxt.model.entity.rendering.UpdateBlockType]
 * carries the PLAYER mask, which is a different mask on this build (its third
 * continuation is bit 17, the npc's is bit 18), and that file is owned by the
 * confirmed appearance path. Sharing the type would invite exactly the
 * assumption the client refutes.
 *
 * Every block is INDIVIDUALLY DISABLEABLE through [NpcUpdates.blockEnabled],
 * so a suspect block can be taken off the wire without disabling npcs and
 * without a rebuild.
 */
abstract class NpcUpdateBlock(val type: NpcUpdateBlockType) {

    /** Writes this block's payload. The mask byte(s) are written by the caller. */
    abstract fun encode(buffer: GamePacketBuilder)
}
