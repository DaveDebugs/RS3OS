package com.opennxt.model.combat

import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.generated.SetTarget
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfOpensubActiveNpc
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall

/**
 * ## THE TARGET INFORMATION HUD -- "Cow / 2 / [==== ] 479 / 35%"
 */
object TargetHud {

    /**
     * `SET_TARGET`'s high byte. The wire for npc 3740 is `9c 0e 01`, i.e.
     * `umediumle = 0x010e9c`: the low 16 bits are the NPC_INFO index and the
     * top byte is 1 on every one of the 68 SET_TARGETs, all of
     * which were npcs. What the byte means for a player target was not
     * measured, so it is named for what it was seen doing.
     */
    const val TARGET_TYPE_NPC = 1

    /** "no target" -- the whole field zero, sent on the tick the npc dies. */
    const val NO_TARGET = 0

    /** The target's lifepoints as a whole percentage, 0..100. */
    const val VARC_TARGET_HEALTH_PERCENT = 2059

    /** The clientscript that fills in the panel's name and combat level. */
    const val SCRIPT_TARGET_INFO = 82

    /** `-Dopennxt.experiment.combat.targethud=false` takes the whole HUD off the wire. */
    val enabled: Boolean
        get() = System.getProperty("opennxt.experiment.combat.targethud") != "false"

    /** `floor(100 * current / maximum)`, the value varc 2059 carries. Clamped to 0..100. */
    fun healthPercent(current: Int, maximum: Int): Int {
        if (maximum <= 0) return 0
        return (100 * current.coerceIn(0, maximum) / maximum).coerceIn(0, 100)
    }

    /** `SET_TARGET`'s field for an npc at NPC_INFO index [infoIndex]. */
    fun targetField(infoIndex: Int): Int = (TARGET_TYPE_NPC shl 16) or (infoIndex and 0xffff)

    /**
     * RUNCLIENTSCRIPT 82 in the form the reference client sends on the tick AFTER the mount:
     * `"siiiii" [name, combatLevel, -1, -1, -1, 1]`.
     */
    fun nameAndLevel(name: String, combatLevel: Int): RunClientScript = RunClientScript(
        script = SCRIPT_TARGET_INFO,
        args = arrayOf(name, combatLevel, -1, -1, -1, 1)
    )

    /**
     * The packets that open the panel for [npc], in the reference client's order.
     *
     * Pure: no player, no I/O, so a check can assert the whole sequence
     * without a socket. Empty when [enabled] is false or when the npc has no
     * NPC_INFO slot (the client would have no entity to bind).
     */
    fun showFrames(npc: WorldNpc): List<GamePacket> {
        if (!enabled || npc.infoIndex < 0) return emptyList()
        val out = ArrayList<GamePacket>(4)
        out.add(SetTarget(targetField(npc.infoIndex)))
        val current = npc.currentLifepoints
        val maximum = npc.lifepoints?.value
        if (current != null && maximum != null) {
            out.add(ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, healthPercent(current, maximum)))
        }
        out.add(IfOpensubActiveNpc.targetInfo(npc.infoIndex))
        out.add(nameAndLevel(npc.name ?: "", npc.combat?.combatLevel ?: 0))
        return out
    }

    /** The one packet the reference client sends on a damage tick, or empty when the npc has no lifepoints. */
    fun updateFrames(npc: WorldNpc): List<GamePacket> {
        if (!enabled) return emptyList()
        val current = npc.currentLifepoints ?: return emptyList()
        val maximum = npc.lifepoints?.value ?: return emptyList()
        return listOf(ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, healthPercent(current, maximum)))
    }

    /** The teardown the reference client sends when the target dies or the fight ends. */
    fun hideFrames(): List<GamePacket> {
        if (!enabled) return emptyList()
        return listOf(
            SetTarget(NO_TARGET),
            ClientSetvarcSmall(VARC_TARGET_HEALTH_PERCENT, 0),
            IfClosesub(IfOpensubActiveNpc.TARGET_INFO_PARENT)
        )
    }

    // ------------------------------------------------------------- the writes

    /**
     * Frames that could not be written, ever, since boot -- and the reason for
     * the first of them. A HUD is decoration; a throw here must not cost a hit,
     * so every write is contained the way [PlayerCombat.awardExperience]
     * contains an xp award. The counter exists so the failure is not silent:
     */
    @Volatile
    var containedWriteFailures: Int = 0
        private set

    @Volatile
    var firstWriteFailure: String? = null
        private set

    /**
     * Frames handed to a client since boot.
     */
    @Volatile
    var framesSent: Int = 0
        private set

    fun resetCounters() {
        framesSent = 0
        containedWriteFailures = 0
        firstWriteFailure = null
    }

    private fun write(player: WorldPlayer, frames: List<GamePacket>) {
        for (frame in frames) {
            try {
                player.client.write(frame)
                framesSent++
            } catch (t: Throwable) {
                containedWriteFailures++
                if (firstWriteFailure == null) firstWriteFailure = "${frame.javaClass.simpleName}: $t"
            }
        }
    }

    fun show(player: WorldPlayer, npc: WorldNpc) = write(player, showFrames(npc))

    fun update(player: WorldPlayer, npc: WorldNpc) = write(player, updateFrames(npc))

    fun hide(player: WorldPlayer) = write(player, hideFrames())
}
