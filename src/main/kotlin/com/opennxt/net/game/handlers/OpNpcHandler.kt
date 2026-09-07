package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.BanksWiring
import com.opennxt.content.impl.Dialogue
import com.opennxt.content.impl.DialogueWiring
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.model.combat.EngageResult
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.updating.NpcInfoEncoder
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpNpc
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteNpcCodec
import mu.KotlinLogging

/**
 * A click on an NPC: OPNPC1..6 (949 opcodes 60, 78, 39, 53, 75, 65).
 *
 * ONE handler for six opcodes - they differ only in which menu row was chosen,
 * and [OpNpc.option] carries that. Six packet CLASSES because
 * [com.opennxt.net.game.PacketRegistry]'s map is KClass-keyed; see [OpNpc].
 */
object OpNpcHandler : GamePacketHandler<WorldPlayer, OpNpc> {
    private val logger = KotlinLogging.logger { }

    /**
     * The world npc occupying NPC_INFO slot [index], or null.
     */
    internal fun npcAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)?.takeIf { it.alive }
    }

    /**
     * A lingering corpse at slot [index] (dead, still transmitted), or null. Audit T-02: a click on
     * one is not a slot bug. Same [WorldNpcs.byInfoIndex] lookup as [npcAtIndex] ,
     * replacing a second whole-population scan); the dead-and-transmissible predicate stays here.
     */
    internal fun corpseAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)
            ?.takeIf { !it.alive && com.opennxt.model.combat.NpcDeathTransmission.transmissible(it) }
    }

    /** Clicks refused because the npc was beyond NPC_INFO reach or on another plane (audit T-05); observable for the check. */
    var farClicksRefused: Int = 0
        private set

    /** `actions_0..4` slot [option] - 1, or null when the definition leaves it empty. */
    private fun optionName(def: NpcDefinition?, option: Int): String? =
        def?.actions?.getOrNull(option - 1)

    /**
     * The switch that routes every non-`Talk to`, non-`Attack` npc option at
     * [ContentRegistry]. **DEFAULTS OFF.** See [dispatchOther].
     *
     * Duplicated as a literal in [com.opennxt.content.impl.Banks.NPC_DISPATCH_SWITCH]
     */
    const val DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"

    /** True only for an explicit `-Dopennxt.experiment.npc.dispatch=true`. */
    val dispatchEnabled: Boolean get() = System.getProperty(DISPATCH_SWITCH) == "true"

    override fun handle(context: WorldPlayer, packet: OpNpc) {
        val npc = npcAtIndex(packet.index)

        // The demo spawn is not a WorldNpc - NpcInfoEncoder emits it directly -
        // so it has to be recognised separately or a click on the only npc most
        // sessions can see would look like a bad index.
        val isDemo = npc == null && packet.index == NpcInfoEncoder.DEMO_INDEX && NpcInfoEncoder.demoEnabled
        val gameId = npc?.gameId ?: if (isDemo) NpcInfoEncoder.demoNpcId else -1

        if (gameId < 0) {
            corpseAtIndex(packet.index)?.let { corpse ->
                logger.info {
                    "OPNPC${packet.option} ${context.name} clicked ${corpse.name ?: "npc"} (${corpse.gameId}, slot " +
                        "${packet.index}) which is DEAD and respawns in ${corpse.respawnTicksRemaining} tick(s). Nothing acts on a corpse."
                }
                return
            }
            logger.warn {
                "OPNPC${packet.option} ${context.name} clicked NPC_INFO index ${packet.index}, which this " +
                    "server has not assigned to any live npc. Either the client sent an index we never sent, " +
                    "or this server's NPC_INFO slot accounting is wrong. Not walking; nothing acts on it."
            }
            return
        }

        val def = if (RsDatabase.available) SqliteNpcCodec.load(gameId) else null
        val name = def?.name ?: npc?.name ?: "unknown npc"
        val action = optionName(def, packet.option)

        val x = npc?.location?.x ?: NpcInfoEncoder.DEMO_X
        val y = npc?.location?.y ?: NpcInfoEncoder.DEMO_Y
        val plane = npc?.location?.plane ?: NpcInfoEncoder.DEMO_PLANE

        logger.info {
            "OPNPC${packet.option} ${context.name} clicked $name (npc $gameId, slot ${packet.index}) at " +
                "($x,$y,plane $plane) option ${packet.option}" +
                (if (action != null) " = '$action'" else " = <no action in that slot>") +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (isDemo) " [demo spawn]" else "") +
                (if (RsDatabase.available) "" else " [no rs3.sqlite - name unverified]")
        }

 // (audit T-05 / C-01, I-25 / I-38 / I-40): the client can only have clicked an
        // npc this server transmitted to it, and NPC_INFO reaches `NpcInfoEncoder.reachFor`
        // tiles on the player's own plane. A click naming an npc beyond that - any of the 1,389
        // slots, anywhere in the world - used to run a full 128-window pathfind (plus up to 8
        // probes) on the tick thread before anything checked it, 128 times a tick if a client
        // chose to. Refused here, before the walk, with the distance in the log.
        // and a far client naming it walked the same 128-window path); #8: one tile of slack, because
        // the npc the client was shown at tick N-1 may have stepped one tile before the click drained.
        run {
            val me = context.entity.location
            val reach = NpcInfoEncoder.reachFor(context)
            val far = maxOf(Math.abs(x - me.x), Math.abs(y - me.y))
            if (plane != me.plane || far > reach + 1) {
                farClicksRefused++
                logger.warn {
                    "OPNPC${packet.option} ${context.name} clicked $name (npc $gameId, slot ${packet.index}) at " +
                        "($x,$y,plane $plane) from (${me.x},${me.y},plane ${me.plane}): $far tiles, NPC_INFO reach is $reach. " +
                        "The client cannot have been shown this npc; not walking, nothing acts on it."
                }
                return
            }
        }

        // The same walk MOVE_GAMECLICK performs, called rather than copied. An
        // npc's own tile may be blocked, in which case PathFinder's
        // walk-as-close-as-possible puts the player next to it - which is the
        // behaviour RS has, out of existing pathing rather than out of anything
        // invented here. The walk runs for EVERY option, before any dispatch
        // and whatever the flag is set to, so switching routing on cannot change
        // where a click puts the player.
        // an in-reach but unroutable npc (a cow behind its fence) costs a small BFS, not a 129x129 one.
        // the player's own reach (a bow's 7), or nowhere when already within it - not to the npc's
        // tile, which walked an archer all the way in while it fired. The destination is recorded
        // on the engagement below so the in-range "stop walking" rule owns it.
        var clickWalk: IntArray? = null
        run {
            val me = context.entity.location
            val attackClick = npc != null && PlayerCombat.shouldEngage(action) && PlayerCombat.enabled
            if (attackClick) {
                val range = PlayerCombat.playerAttackRange(context)
                val d = PlayerCombat.footprintDistance(npc!!, me.x, me.y)
                if (d <= range) return@run                       // in reach: stand and fight
                val tile = PlayerCombat.nearestAttackTile(npc, me.x, me.y, range)
                if (tile != null) {
                    clickWalk = tile
                    val far = maxOf(Math.abs(tile[0] - me.x), Math.abs(tile[1] - me.y))
                    MoveGameClickHandler.walk(context, tile[0], tile[1], "OPNPC${packet.option}", search = (2 * far + 2).coerceIn(8, PlayerCombat.CHASE_SEARCH_CAP))
                    return@run
                }
            }
            val far = maxOf(Math.abs(x - me.x), Math.abs(y - me.y))
            MoveGameClickHandler.walk(context, x, y, "OPNPC${packet.option}", search = (2 * far + 2).coerceIn(8, PlayerCombat.CHASE_SEARCH_CAP))
        }

        // ---------------------------------------------------------------- talk
        //
        // 'Talk to' is the most common npc action in this cache - 8,797 npc ids
        // declare it against Attack's 7,914 - and until Dialogue existed this
        // handler walked the player over and stopped.
        //
        // say 8,772. That is the COLUMN-ONLY count (npcs.actions_0..2).
        // SqliteNpcCodec merges the npcs_attr actions_3/actions_4 rows, and that
        // merged view is what optionName reads, so the number this handler
        // actually operates over is 8,797 - 25 npc ids declare 'Talk to' in attr
        // three numbers on every run so neither spelling can rot again.
        //
        // The test is the CACHE's
        // action string for the clicked row, never the option index, for the
        // same reason the Attack test below is: 'Talk to' is in actions_0 for
        // 8,240 npcs and in actions_2 for 532, so gating on an index would
        // silently miss 532 npcs.
        //
        // ContentRegistry then validates the same (npc, action) pair against the
        // same definition a second time, so a client that sends OPNPC1 for an
        // npc whose slot 0 is something else reaches nothing.
        if (action == Dialogue.TALK_TO) talkTo(context, packet, gameId, name, action)

        // ------------------------------------------------------------ everything else
        //
        // The largest dead surface this server had. See [dispatchOther] for the
        // measurement and for what the flag does. DEFAULT OFF: without
        // -Dopennxt.experiment.npc.dispatch=true this call returns null without
 // touching the registry, so every measurement taken before
        // still describes this handler exactly.
        else if (action != null && !PlayerCombat.shouldEngage(action))
            dispatchOther(context, packet, gameId, name, action, x, y, plane)

        // ---------------------------------------------------------------- attack
        //
        // The only gameplay this handler starts ON ITS OWN - dispatch above hands
        // work to content that registered itself - and only when the CACHE says
        // the row the player clicked is an Attack row: [PlayerCombat.shouldEngage]
        // tests `action` - the string SqliteNpcCodec read out of
        // `actions_<option-1>` - and NOT the option index. That distinction is
        // load-bearing rather than fastidious: "Attack" sits in actions_1
        // (OPNPC2) for 7,840 npcs but in actions_0 for 71 and actions_2 for 3, so
        // gating on an index would silently mis-handle 74 npcs, while gating on
        // the string cannot.
        //
        // The walk above is left exactly as it was and still runs first. (Until
 // that was the whole approach; since then PlayerCombat.chase
        // re-paths an engaged player toward a moving target on its own, bounded,
 // and this walk is the first step of it., audit T-14.)
        if (!PlayerCombat.shouldEngage(action)) return

        if (npc == null) {
            // THIS BRANCH SHOULD NOW BE UNREACHABLE ON A POPULATED SERVER, and
            // it is logged at WARN rather than INFO because reaching it means
            // something is wrong on this side, not that the player misclicked.
            //
 // Until it was the NORMAL outcome of the only click most
            // sessions could make: NpcInfoEncoder emitted a synthetic NpcView
            // beside the login tile, that view was not a WorldNpc, and so it had
            // no lifepoints, no drop table and no update queue to put a splat
            // into. Ten Attack clicks in one measured session, ten no-ops
            // (logs/server-20260817-125433). WorldNpcs now places a REAL
            // Chicken on that tile by default and the encoder suppresses the
            // decoration whenever it is there, so a clickable-but-dead npc
            // should no longer exist.
            //
            // Kept rather than deleted, because "the client sent a slot we never
            // assigned" is still possible and must not fail silently.
            logger.warn {
                "OPNPC${packet.option} ${context.name} chose '$action' on npc $gameId (slot ${packet.index})" +
                    (if (isDemo)
                        ", which is NpcInfoEncoder's SYNTHETIC decoration - not a WorldNpc, so it has no " +
                            "lifepoints, no drop table and no update queue. Nothing to fight. This should " +
                            "not be reachable any more: it means WorldNpcs.combatDemoNpc() is null, i.e. " +
                            "either -Dopennxt.experiment.combat.demospawn=off is set or the world was " +
                            "never populated (rs3.sqlite missing?). ${WorldNpcs.COMBAT_DEMO_PROVENANCE}"
                    else ", but no WorldNpc backs that slot.")
            }
            return
        }

        val engageResult = PlayerCombat.engage(context, npc)
        clickWalk?.let { PlayerCombat.recordClickWalk(context, it[0], it[1]) }
        when (val result = engageResult) {
            is EngageResult.Disabled -> logger.info {
                "OPNPC${packet.option} ${context.name} chose '$action' on ${npc.name ?: "npc$gameId"} " +
                    "($gameId) - combat is OFF. Enable with -Dopennxt.experiment.combat=true."
            }

            is EngageResult.Refused -> logger.warn {
                "OPNPC${packet.option} ${context.name} cannot fight ${npc.name ?: "npc$gameId"} " +
                    "($gameId): ${result.reason}"
            }

            is EngageResult.Engaged -> logger.info {
                "OPNPC${packet.option} ${context.name} ENGAGED ${npc.name ?: "npc$gameId"} ($gameId, slot " +
                    "${npc.infoIndex}) at ($x,$y,plane $plane) - lp ${npc.currentLifepoints}/" +
                    "${npc.lifepoints?.value}, " +
                    (if (PlayerCombat.realDamageEnabled)
                        "damage ROLLED per swing (accuracy vs armour; -Dopennxt.experiment." +
                            "combat.realdamage is ON, so the flat ${PlayerCombat.damage()} is NOT in use)"
                     else "${PlayerCombat.damage()} damage") +
                    " every ${PlayerCombat.interval()} tick(s). ${PlayerCombat.PROVENANCE_SHORT}"
            }
        }
    }

    /**
     * Dispatches a `Talk to` click at [Dialogue], through [ContentRegistry].
     *
     * ## Why the ContentPlayer is bound first
     *
     * [Dialogue] reaches the wire through one seam that takes a
     * [com.opennxt.content.ContentPlayer], and [DialogueWiring.bind] is the one
     * call that records which [WorldPlayer] that content view belongs to. This
     * is the only place in the tree with both objects in hand. Without it the
     * conversation still runs - it just sends nothing, which is a silent
     * nothing-happens rather than a crash. Hence: bind, then dispatch.
     *
     * The same call also makes the inbound half work: the conversation is keyed
     * on the ContentPlayer, and [DialogueWiring.handleButton] finds it by going
     * from the WorldPlayer to the same stable ContentPlayer.
     */
    /**
     * EVERY OTHER NPC OPTION, at [ContentRegistry]. Behind [dispatchEnabled],
     * default OFF.
     *
     * @return the registry's answer, or null when the flag is off.
     */
    internal fun dispatchOther(
        context: WorldPlayer,
        packet: OpNpc,
        gameId: Int,
        name: String,
        action: String,
        x: Int,
        y: Int,
        plane: Int
    ): DispatchResult? {
        if (!dispatchEnabled) {
            logger.info {
                "OPNPC${packet.option} '$action' on npc $gameId ('$name') was NOT dispatched: npc action " +
                    "routing is OFF (-D$DISPATCH_SWITCH=true to route it at ContentRegistry). This is the " +
                    "earlier behaviour, kept as the default on purpose."
            }
            return null
        }

        val content = context.contentPlayerAt()
        DialogueWiring.bind(content, context)
        BanksWiring.bind(content, context)
        SkillingWiring.bind(content, context)

        val dispatched = try {
            ContentRegistry.dispatchNpc(content, gameId, action, packet.index, x, y, plane)
        } catch (t: Throwable) {
            logger.error(t) {
                "OPNPC${packet.option} ${context.name}: the content handler bound to '$action' on npc " +
                    "$gameId ('$name') THREW. That is a bug in the handler, not in the client; the click " +
                    "is dropped so the session survives it."
            }
            return null
        }

        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "OPNPC${packet.option} ${context.name}: content handled '$action' on npc $gameId " +
                    "('$name') at ($x,$y,plane $plane) -> ${dispatched.value}"
            }
            // The ordinary state of affairs for most of the 930 option names:
            // the cache declares the option, nothing is written for it yet.
            is DispatchResult.NoHandler -> logger.info {
                "OPNPC${packet.option}: nothing is bound to '$action' on npc $gameId ('$name') - " +
                    "content gap, not abuse"
            }
            else -> logger.warn {
                "OPNPC${packet.option}: '$action' on npc $gameId was refused by the registry " +
                    "($dispatched). optionName read that string out of the same definition the registry " +
                    "validates against, so this is a disagreement between the two and should not be possible."
            }
        }
        return dispatched
    }

    private fun talkTo(context: WorldPlayer, packet: OpNpc, gameId: Int, name: String, action: String) {
        val content = context.contentPlayerAt()
        DialogueWiring.bind(content, context)

        when (val dispatched = ContentRegistry.dispatchNpc(content, gameId, action, packet.index)) {
            is DispatchResult.Handled -> logger.info {
                "OPNPC${packet.option} ${context.name}: content handled '$action' on npc $gameId " +
                    "('$name') -> ${dispatched.value}. ${Dialogue.PROVENANCE_SHORT}"
            }
            // The ordinary state of affairs when dialogue is switched off. A
            // content gap, not abuse - ContentRegistry's own doc says so.
            is DispatchResult.NoHandler -> logger.info {
                "OPNPC${packet.option}: nothing is bound to '$action' on npc $gameId ('$name') - " +
                    "dialogue is ${if (Dialogue.enabled) "enabled but unbound" else "DISABLED"}"
            }
            else -> logger.warn {
                "OPNPC${packet.option}: '$action' on npc $gameId was refused by the registry " +
                    "($dispatched). The definition said the clicked row carried that action, so this " +
                    "is a disagreement between optionName and the registry and should not be possible."
            }
        }
    }
}
