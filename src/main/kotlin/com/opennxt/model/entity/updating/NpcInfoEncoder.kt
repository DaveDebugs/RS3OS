package com.opennxt.model.entity.updating

import com.opennxt.OpenNXT
import com.opennxt.model.combat.NpcDeathTransmission
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.rendering.npc.NpcUpdates
import com.opennxt.model.entity.rendering.npc.blocks.NpcForceMovementBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * NPC_INFO (build 949-5 ServerProt opcode 90, size -2) -- the packet that puts
 * NPCs in the world.
 */
object NpcInfoEncoder {

    private val logger = KotlinLogging.logger { }

    /**
     * Master switch. `-Dopennxt.experiment.npcs=false` disables NPC_INFO
     * COMPLETELY: [writeTo] returns before touching the viewport state, so the
     * session is byte-for-byte the world that ships today (no NPC_INFO has ever
     * been sent by this server). Same shape as
     * `opennxt.experiment.appearance`; the point is that a crash caused by this
     * packet can be backed out without a rebuild.
     */
    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs") != "false"

    /**
     * The demo spawn. `-Dopennxt.experiment.npcs.demo=false` turns it off and
     * leaves only whatever [com.opennxt.model.world.WorldNpcs] holds (which,
     * for the login area, is nothing -- the cache spawn layer covers 0.39% of
     * the world and Lumbridge castle is not in it).
     */
    val demoEnabled: Boolean = System.getProperty("opennxt.experiment.npcs.demo") != "false"

    /**
     * The npc GAME id the demo spawn uses, overridable with
     * `-Dopennxt.experiment.npcs.demoId`.
     *
     * 41 = Chicken. Chosen because every link in the chain is checkable in this
     * install, not because it is famous:
     * - data/rs3.sqlite (decoded from THIS client's cache) has npcs row
     * id=41, _group=0, _file=41, game_id=41, name='Chicken', boundSize=1,
     * animation_group=3206 -- and 0*128+41 = 41, so archive id and game id
     * coincide here and the 16-bit type field is unambiguous.
     * - its npcs_attr `models` is [97090], and model group 97090 IS PHYSICALLY
     * PRESENT in the client's own model store (js5-47.jcache, 42,615 bytes;
     */
    val demoNpcId: Int = System.getProperty("opennxt.experiment.npcs.demoId")?.toIntOrNull() ?: 41

    /**
     * Where the demo npc stands: **two tiles east of the new-player spawn**, i.e. inside the first
     * screen without standing in the player.
     */
    const val DEMO_X = com.opennxt.model.account.PlayerSave.SPAWN_X + 2
    const val DEMO_Y = com.opennxt.model.account.PlayerSave.SPAWN_Y
    const val DEMO_PLANE = 0

    /** Slot index the demo npc occupies. See [com.opennxt.model.world.WorldNpcs.WORLD_INDEX_BASE]. */
    const val DEMO_INDEX = 1

    /** 0xFFFF terminates pass B, so it can never be an npc index. */
    const val TERMINATOR = 0xFFFF

    const val MAX_LOCAL = 255

    /**
     * Adds per packet. Arbitrary and cheap; the client has no limit of its own.
     *
     * It bounds the packet, not the population: an npc not added this tick is
     * added on the next one, so a dense area fills in over a few ticks instead
     * of arriving in one large packet.
     */
    const val MAX_ADDS_PER_TICK = 32

    /**
     * The widest the bit section can get, in bytes, for a given [npcBits].
     *
     * Worked out from the field widths above, not measured after the fact:
     *
     *   count                     8 bits
     *   worst pass A record      11 bits  (1 flag + 2 opcode + 1 sub + 3 + 3 dir
     *                                      + 1 ext -- the RUN record)
     *   worst add record   36 + 2*npcBits (16 index + 16 type + 1 ext + 2 plane
     *                                      + 3 facing + 1 trailing + dx + dy)
     *   terminator               16 bits
     *
     * so `8 + 255*11 + 32*(36 + 2*npcBits) + 16`. At npcBits 7 (what
     * `Viewport.sceneRadius` sends today) that is 4,429 bits = 554 bytes; at
     * the 16-bit ceiling, 5,005 bits = 626 bytes. Both are far inside
     * NPC_INFO's size -2 (a 2-byte length, 65,535 max), which is why this is a
     * check rather than a cap -- there is nothing to truncate.
     *
     * [createBufferFor] asserts the bit section it actually produced is no
     * larger. Extended info is NOT counted here: it is byte-aligned, appended
     * after, and its size belongs to the blocks.
     */
    fun maxBitSectionBytes(npcBits: Int): Int =
        (8 + MAX_LOCAL * 11 + MAX_ADDS_PER_TICK * (36 + 2 * npcBits) + 16 + 7) / 8

    const val ADD_TRAILING_BIT = 1

    /** Facing sent on an add. 3 bits, `value * 45 degrees`. */
    const val ADD_FACING = 0

    /**
     * Which of pass A's three movement records a step goes on, and the speed
     * the client will interpolate it at.
     */
    enum class Gait(val opcode: Int, val subBit: Int?, val steps: Int, val speed: Int) {
        /** opcode 1, one getBits(3), speed marker = 1. */
        WALK(opcode = 1, subBit = null, steps = 1, speed = 1),

        /** opcode 2 sub-bit 1, TWO getBits(3), marker = 2. */
        RUN(opcode = 2, subBit = 1, steps = 2, speed = 2),

        /** opcode 2 sub-bit 0, one getBits(3), marker = 0. */
        CRAWL(opcode = 2, subBit = 0, steps = 1, speed = 0)
    }

    /**
     * One npc as the wire needs it: a slot index, a game id and an absolute
     * tile. That is genuinely all NPC_INFO carries for a spawn -- the client
     * builds everything else (model, name, options, size) out of its own cache
     * from the type id, so this deliberately does NOT require a
     * [com.opennxt.model.world.WorldNpc], which would drag rs3.sqlite,
     * lifepoint seeds and combat defs into the packet path.
     */
    data class NpcView(
        val index: Int,
        val npcId: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        /**
         * The extended-info blocks pending for this npc, or null when there
         * are none. Nullable rather than an empty holder so that "this npc has
         * nothing to say" is one reference compare on the hot path.
         */
        val updates: NpcUpdates? = null,
        /**
         * The death sequence a lingering corpse is holding, or null
         * for a live npc. Consulted ONLY when this viewer ADDs the npc: a viewer that walked
         * into range mid-linger, or whose scene was rebuilt, is given the pose on the add;
         * viewers that saw the death already got it from the shared queue on the death tick.
         */
        val corpsePose: Int? = null,
        /**
         * The compass directions ([com.opennxt.model.entity.movement.CompassPoint.id],
         * which is the client's 3-bit field verbatim) this npc stepped in THIS
         * TICK, in order. Empty for an npc that did not move.
         *
         * This is a description of a move that has ALREADY happened server-side
         * -- [x] and [y] are the tile after the steps, not before -- so the
         * encoder can, and does, verify the two against each other.
         */
        val steps: List<Int> = emptyList(),
        /** Which pass A record [steps] goes on. Ignored when [steps] is empty. */
        val gait: Gait = Gait.WALK
    ) {
        /** True when at least one ENABLED block would actually be written. */
        fun hasExtendedInfo(): Boolean = updates != null && !updates.isEmpty()
    }

    /**
     * Per-player mirror of the client's local npc array (`NL+0xa0a0`), in the
     * client's order. It lives here rather than in
     * [com.opennxt.model.entity.player.Viewport] because this pass does not own
     * that file; it is keyed weakly so a logged-out player's state is collected
     * with them.
     */
    class State {
        val local = ArrayList<Int>()

        /**
         * Slot index -> the tile the CLIENT believes that npc is standing on.
         */
        val believed = HashMap<Int, Triple<Int, Int, Int>>()

        var sceneBaseX: Int? = null
        var sceneBaseY: Int? = null
    }

    private val states: MutableMap<WorldPlayer, State> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, State>())

    fun stateOf(player: WorldPlayer): State = states.getOrPut(player) { State() }

    /** Drops a player's mirrored local list. Only tools need this. */
    fun forget(player: WorldPlayer) {
        states.remove(player)
    }

    /**
     * Every npc this server would show anyone, demo spawn first.
     *
     * The world population comes from [com.opennxt.model.world.WorldNpcs],
     * which is empty unless rs3.sqlite was available at boot; the demo spawn
     * does not depend on it at all, which is the whole reason it is a
     * [NpcView] and not a [com.opennxt.model.world.WorldNpc].
     */
    fun allNpcs(): List<NpcView> = collect(null, 0, 0, 0)

    /**
     * Builds [NpcView]s for the demo spawn and every alive, slotted world npc.
     *
     * When [reach] is null nothing is filtered (that is [allNpcs]); otherwise
     * only npcs on [plane] and within [reach] tiles of ([cx], [cy]) get a view
     * built at all. The filter is deliberately INSIDE the loop rather than a
     * `.filter {}` on the result: the cache layer is 1,388 entities and this
     * runs once per player per tick, so building 1,388 objects to keep at most
     * a few dozen is 1,388 allocations a tick that buy nothing.
     */
    internal fun collect(reach: Int?, cx: Int, cy: Int, plane: Int): List<NpcView> {
        val out = ArrayList<NpcView>()
        fun inRange(x: Int, y: Int, p: Int): Boolean =
            reach == null || (p == plane && abs(x - cx) <= reach && abs(y - cy) <= reach)

        val world = runCatching { OpenNXT.world }.getOrNull()

        // THE DECORATION STANDS DOWN WHEN A REAL NPC HAS TAKEN ITS PLACE.
        //
        // This view is SYNTHETIC: it is not backed by a WorldNpc, so it has no
        // lifepoints, no drop table and no update queue, and an Attack click on
 // it reaches OpNpcHandler's dead end. Since WorldNpcs places
        // a REAL Chicken on this exact tile by default
        // (WorldNpcs.combatDemoSpawn), so emitting this as well would draw two
        // chickens and leave the fake one clickable - the trap this change
        // exists to remove. Keyed on the demo npc's IDENTITY rather than on
        // "something is standing on DEMO_X,DEMO_Y" because NpcWander moves it.
        val backedByRealNpc = world?.npcs?.combatDemoNpc() != null
        if (demoEnabled && !backedByRealNpc && inRange(DEMO_X, DEMO_Y, DEMO_PLANE)) {
            out.add(NpcView(DEMO_INDEX, demoNpcId, DEMO_X, DEMO_Y, DEMO_PLANE, demoUpdates()))
        }
        if (world == null) return out
        for (npc in world.npcs.all()) {
            // A DEAD npc is still transmitted on the one tick it died on, so the
            // killing blow's hit splat and the death animation - both queued AFTER
            // WorldNpc.die - have a viewer to ride to. Measured defect: 21 hits
            // applied, 20 HITS blocks on the wire.
            if (!npc.alive && !NpcDeathTransmission.transmissible(npc)) continue
            if (npc.infoIndex < 0) continue
            val loc = npc.location
            if (!inRange(loc.x, loc.y, loc.plane)) continue

            // The step(s) the mover already applied THIS tick. WorldNpcs.tick
            // runs the whole npc movement phase before any player is encoded,
            // so these are settled and identical for every viewer.
            val move = npc.movement
            val steps = when {
                move.nextWalkDirection == null -> emptyList()
                move.nextRunDirection == null -> listOf(move.nextWalkDirection!!.id)
                else -> listOf(move.nextWalkDirection!!.id, move.nextRunDirection!!.id)
            }
            // One tile is a WALK even when the mover was set to RUN -- the wire
            // has no one-direction run record (opcode 2's sub-bit 1 reads two
            // getBits(3), and), and Movement.process
            // degrades a last-tile run to a walk for the same reason.
            val gait = if (steps.size == 2) Gait.RUN else Gait.WALK
            out.add(
                NpcView(
                    npc.infoIndex, npc.gameId, loc.x, loc.y, loc.plane,
                    updates = npc.pendingUpdates,
                    corpsePose = if (npc.alive) null else npc.deathAnimation?.sequence,
                    steps = steps, gait = gait
                )
            )
        }
        return out
    }

    /**
     * The demo npc's extended info, which is EMPTY unless the operator asks
     * for something on the command line.
     *
     * No animation id is hardcoded here. This server has not decoded the
     * animation-group config, so it does not know which sequence ids npc 41
     * can legally play, and picking one would be inventing content -- the exact
 * failure mode this server has already had to retract twice. Instead:
     *
     *   -Dopennxt.experiment.npcs.demoAnim=<id>    play sequence <id>, forever
     *   -Dopennxt.experiment.npcs.demoSay=<text>   float <text> over its head
     *   -Dopennxt.experiment.npcs.demoFace=x,y     turn it to face tile (x, y)
     *   -Dopennxt.experiment.npcs.demoFaceEntity=npc,<i> | player,<i> | clear
     *                                              turn it to LOOK at an entity
     *   -Dopennxt.experiment.npcs.demoForce=a1,..,angle   nine operator numbers
     *
     * All three are UNSET by default, so the default behaviour is byte-for-byte
     * the packet this encoder produced before extended info existed: the
     * has-extended-info bit is 0 and no block is appended. They exist so a
     * human with a live client can confirm a block end to end without a
     * rebuild, and so the confirmation is attributable to one block at a time.
     */
    private fun demoUpdates(): NpcUpdates? {
        val anim = System.getProperty("opennxt.experiment.npcs.demoAnim")?.toIntOrNull()
        val text = System.getProperty("opennxt.experiment.npcs.demoSay")
        val face = System.getProperty("opennxt.experiment.npcs.demoFace")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 }
        // -Dopennxt.experiment.npcs.demoFaceEntity=npc,<index> | player,<index> | clear
        // The one block this pass recovered that a human can confirm by looking
        // at it: the npc's head turns, or it does not.
        val faceEntity = System.getProperty("opennxt.experiment.npcs.demoFaceEntity")
        // -Dopennxt.experiment.npcs.demoForce=a1,a2,a3,b1,b2,b3,c1,c2,angle
        val force = System.getProperty("opennxt.experiment.npcs.demoForce")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 9 }
        if (anim == null && text == null && face == null && faceEntity == null && force == null) {
            return null
        }

        val updates = NpcUpdates()
        if (anim != null) updates.animate(anim)
        if (text != null) updates.say(text)
        if (face != null) updates.faceTile(face[0], face[1])
        if (faceEntity != null) {
            val parts = faceEntity.split(',').map { it.trim() }
            when (parts[0].lowercase()) {
                "npc" -> updates.faceNpc(parts[1].toInt())
                "player" -> updates.facePlayer(parts[1].toInt())
                "clear" -> updates.faceNothing()
                else -> throw IllegalArgumentException(
                    "npcs.demoFaceEntity wants npc,<index> | player,<index> | clear"
                )
            }
        }
        if (force != null) {
            updates.forceMovement(
                NpcForceMovementBlock(
                    force[0], force[1], force[2], force[3], force[4], force[5],
                    force[6], force[7], force[8]
                )
            )
        }
        return updates
    }

    /**
     * Half the addressable coordinate range for [npcBits]: an add can describe
     * offsets in `-half .. half-1`, so anything further away is not addressable
     * and must not be added.
     */
    private fun half(npcBits: Int): Int = 1 shl (npcBits - 1)

    /**
     * The extended info an ADD of [npc] carries: the shared queue when it is non-empty and
     * the npc is alive or already holds an animation; a viewer-local copy WITH the death
     * pose when the npc is a lingering corpse whose queue lacks one; null when there is
     * nothing to say. Counted in [corpsePosesAdded].
     */
    private fun withCorpsePose(npc: NpcView): NpcUpdates? {
        val shared = npc.updates?.takeIf { !it.isEmpty() }
        val pose = npc.corpsePose ?: return shared
        if (shared?.animation != null) return shared
        corpsePosesAdded++
        val local = NpcUpdates()
        if (shared != null) {
            shared.hits?.let { local.hit(it) }
            shared.faceCoordinate?.let { local.faceTile(it) }
            shared.say?.let { local.say(it) }
            shared.animationGroup?.let { local.animationGroup(it) }
            shared.faceEntity?.let { local.faceEntity(it) }
            shared.forceMovement?.let { local.forceMovement(it) }
            shared.string17?.let { local.string17(it) }
            shared.short22?.let { local.short22(it) }
        }
        local.animate(pose)
        return local
    }

    /** ADDs that carried a viewer-local death pose since boot (audit T-02); observable for the check. */
    var corpsePosesAdded: Int = 0
        private set

    /** The npcBits REBUILD_NORMAL last told this client to use (wire offset 6). */
    fun npcBitsFor(player: WorldPlayer): Int = player.viewport.sceneRadius

    fun visible(player: WorldPlayer, npcBits: Int): List<NpcView> {
        val loc = player.entity.location
        val reach = reachFor(player, npcBits)
        return collect(reach, loc.x, loc.y, loc.plane)
    }

    /** The Chebyshev reach this encoder transmits npcs within for [player]; the click gate reads the same number. */
    fun reachFor(player: WorldPlayer, npcBits: Int = npcBitsFor(player)): Int =
        minOf(player.viewport.playerViewingDistance, half(npcBits) - 1)

    private fun deltaOf(dir: Int): Pair<Int, Int>? =
        CompassPoint.values().firstOrNull { it.id == dir }?.let { it.dx to it.dy }

    /**
     * The pass A record for one tracked npc, or null when the wire cannot
     * express the move and the npc must be REMOVEd and re-ADDed instead.
     *
     * Null for: a plane change (pass A carries no plane), an unknown direction,
     * more steps than a record holds, and -- the important one -- a step list
     * that does NOT land on the npc's actual tile when applied to what the
     * client believes. That last case is the check that keeps the client's
     * relative arithmetic and the server's absolute tile from drifting apart.
     */
    private fun movementOf(believed: Triple<Int, Int, Int>, npc: NpcView): List<Int>? {
        val (bx, by, bp) = believed
        if (bp != npc.plane) return null
        if (npc.steps.size > 2) return null
        if (npc.steps.size == 2 && npc.gait != Gait.RUN) return null
        if (npc.steps.size == 1 && npc.gait == Gait.RUN) return null

        var x = bx
        var y = by
        for (dir in npc.steps) {
            val (dx, dy) = deltaOf(dir) ?: return null
            x += dx
            y += dy
        }
        if (x != npc.x || y != npc.y) return null
        return npc.steps
    }

    /**
     * Encodes one NPC_INFO payload for [player] and ADVANCES the mirrored local
     * list, exactly as the client will advance its own.
     */
    fun createBufferFor(player: WorldPlayer): ByteBuf =
        createBufferFor(player, visible(player, npcBitsFor(player)))

    /**
     * As above, over an explicit view list.
     *
     * The seam exists so a harness can drive records the live world has no way
     * to produce -- a CRAWL, a 255-npc local list -- through the REAL encoder
     * instead of a reimplementation of it. The server itself only ever calls
     * the one-argument form.
     */
    fun createBufferFor(player: WorldPlayer, views: List<NpcView>): ByteBuf {
        val npcBits = npcBitsFor(player)
        require(npcBits in 1..16) {
            "npcBits must be 1..16 to encode a signed offset; REBUILD_NORMAL sent $npcBits " +
                "(Viewport.sceneRadius). 0 would make the client shift by -1."
        }

        val state = stateOf(player)

        // Scene re-centred since the last packet: forget what the client is
        // holding and re-add from scratch. See [State.sceneBaseX].
        val base = player.viewport.baseTile
        if (state.sceneBaseX != base.x || state.sceneBaseY != base.y) {
            state.local.clear()
            state.believed.clear()
            state.sceneBaseX = base.x
            state.sceneBaseY = base.y
        }

        val out = Unpooled.buffer()
        val buf = GamePacketBuilder(out)

        val inView = views.associateBy { it.index }
        val loc = player.entity.location
        val mask = (1 shl npcBits) - 1

        buf.switchToBitAccess()

        // The client's extended-info queue NL+0xc0f0, mirrored: pass A pushes
        // /, pass B, and
        // walks the result IN THAT ORDER. The byte section below
        // has to be written in the same order or every npc after the first
        // mismatched one reads the wrong bytes.
        //
        // The SLOT INDEX rides along, purely for [ExtendedInfoTrace]: without it
        // a log line can say a HITS block went out but not which npc it went out
        // on, and "the splat reached the wire" is only worth anything if it names
        // the entity it was attached to.
        val extended = ArrayList<Pair<Int, NpcUpdates>>()

        // ---- PASS A: one record per npc the client already tracks, in order.
        val previous = ArrayList(state.local)
        // The count is getBits(8). 256 would be written as 0 and the client
        // would silently destroy everything it holds; the cap in
        // pass B below is what keeps this true, and this is the assertion that
        // says so out loud if it ever stops being.
        check(previous.size <= MAX_LOCAL) {
            "mirrored local list is ${previous.size}, but the local count is" +
                "getBits(8) and cannot describe more than $MAX_LOCAL"
        }
        buf.putBits(8, previous.size)
        val kept = ArrayList<Int>(previous.size)
        for (index in previous) {
            val npc = inView[index]
            val believed = state.believed[index]
            val steps = if (npc == null || believed == null) null else movementOf(believed, npc)

            if (npc == null || believed == null || steps == null) {
                // op 3: index goes on the removal vector and is
                // NOT re-appended to the local array. When the npc is still in
                // view (steps == null: an inexpressible move) pass B re-adds it
                // below in this same packet, which is legal because the add
                // re-stamps the entity past the removal sweep.
                buf.putBits(1, 1)
                buf.putBits(2, 3)
                state.believed.remove(index)
                continue
            }

            val ext = npc.hasExtendedInfo()
            if (steps.isEmpty()) {
                if (ext) {
                    // op 0/: the npc is kept (it is
                    // re-appended to NL+0xa0a0, exactly like the
                    // idle branch does) and its index is pushed
                    // onto the extended-info queue. No movement is read.
                    buf.putBits(1, 1)
                    buf.putBits(2, 0)
                    extended.add(index to npc.updates!!)
                } else {
                    // The idle bit. Nothing else is read, and the
                    // npc keeps the position it already has -- so a stationary
                    // npc costs exactly one bit per tick.
                    buf.putBits(1, 0)
                }
            } else {
                // A movement record. The gait picks the opcode; see [Gait].
                val gait = if (steps.size == 2) Gait.RUN else npc.gait
                buf.putBits(1, 1)
                buf.putBits(2, gait.opcode)
                gait.subBit?.let { buf.putBits(1, it) }
                for (dir in steps) buf.putBits(3, dir)
                // The has-extended-info bit is read AFTER the directions on
                // both movement paths ( for op 1, and op 2 reaches
                // the same read through).
                buf.putBits(1, if (ext) 1 else 0)
                if (ext) extended.add(index to npc.updates!!)
            }
            state.believed[index] = Triple(npc.x, npc.y, npc.plane)
            kept.add(index)
        }

        // ---- PASS B: adds, then the 0xFFFF terminator.
        var added = 0
        for (npc in inView.values) {
            if (kept.contains(npc.index)) continue
            if (kept.size >= MAX_LOCAL) break
            if (added >= MAX_ADDS_PER_TICK) break
 // (audit T-02): an ADD of a lingering corpse carries its death pose for
            // THIS viewer - a viewer-local block, never written into the shared queue (which
            // would re-send it to everyone who already saw the death). A live npc, or a corpse
            // whose shared queue already holds an animation this tick, is unchanged.
            val addUpdates: NpcUpdates? = withCorpsePose(npc)
            val ext = addUpdates != null
            buf.putBits(16, npc.index)
            buf.putBits(npcBits, (npc.x - loc.x) and mask)
            buf.putBits(16, npc.npcId)
            buf.putBits(1, if (ext) 1 else 0) // -> queue at
            buf.putBits(2, npc.plane)
            buf.putBits(3, ADD_FACING)
            buf.putBits(npcBits, (npc.y - loc.y) and mask)
            buf.putBits(1, ADD_TRAILING_BIT)
            if (ext) extended.add(npc.index to addUpdates!!)
            // The add states an ABSOLUTE tile, so this is where the client's
            // belief is (re-)anchored; every later pass A record moves it
            // relative to this.
            state.believed[npc.index] = Triple(npc.x, npc.y, npc.plane)
            kept.add(npc.index)
            added++
        }
        buf.putBits(16, TERMINATOR)

        // rounds the bit section up to a whole byte before the
        // extended-info section starts; switchToByteAccess does the same
        // (bitIndex + 7) >> 3.
        buf.switchToByteAccess()

        val bitSectionBytes = out.readableBytes()
        check(bitSectionBytes <= maxBitSectionBytes(npcBits)) {
            "bit section is $bitSectionBytes bytes, over the ${maxBitSectionBytes(npcBits)} the " +
                "field widths allow at npcBits=$npcBits with MAX_LOCAL=$MAX_LOCAL and " +
                "MAX_ADDS_PER_TICK=$MAX_ADDS_PER_TICK"
        }

        // Every record's byte length is measured HERE, from the buffer, rather
        // than predicted from the block widths: a predicted length agrees with a
        // mis-encode, a measured one cannot.
        val traced = ArrayList<ExtendedInfoTrace.Entry>(extended.size)
        for ((index, updates) in extended) {
            val names = updates.blocks().map { it.type.name }
            val before = out.readableBytes()
            updates.offered = true
            updates.encode(buf)
            traced.add(
                ExtendedInfoTrace.Entry(
                    ExtendedInfoTrace.NPC, index, names, out.readableBytes() - before
                )
            )
        }
        lastReport = ExtendedInfoTrace.Report(
            ExtendedInfoTrace.NPC, player.name, traced, out.readableBytes() - bitSectionBytes
        )
        ExtendedInfoTrace.record(lastReport!!)

        state.local.clear()
        state.local.addAll(kept)
        // Anything the client no longer holds must not keep a belief behind:
        // otherwise a re-added index would be checked against a stale tile.
        state.believed.keys.retainAll(kept.toSet())
        return out
    }

    /**
     * The ONE call a caller needs. Does nothing at all when the experiment is
     * off, and degrades (once, with a warning) when this build's protocol table
     * has no NPC_INFO opcode -- the same shape [com.opennxt.model.world.WorldPlayer]
     * already uses for PLAYER_INFO, because an unmapped packet must never take
     * the world tick down with it.
     */
    fun writeTo(player: WorldPlayer) {
        if (!enabled) return

        val opcode = OpenNXT.protocol.serverProtNames.values["NPC_INFO"]
        if (opcode == null) {
            if (!unmappedWarned) {
                unmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for NPC_INFO - no npc will ever " +
                        "appear. Recover it into data/prot/<build>/serverProtNames.toml. (Warned once.)"
                }
            }
            return
        }

        val buf = try {
            createBufferFor(player)
        } catch (t: Throwable) {
            if (!encodeFailureWarned) {
                encodeFailureWarned = true
                logger.error(t) {
                    "NPC_INFO encode failed for ${player.name}; no npcs will be sent this session. " +
                        "Run with -Dopennxt.experiment.npcs=false to silence. (Warned once.)"
                }
            }
            return
        }

        if (sends < 3) {
            sends++
            logger.info {
                "NPC_INFO send #$sends: ${buf.readableBytes()} byte(s), local=${stateOf(player).local.size} " +
                    "npcBits=${npcBitsFor(player)} (a first send with one add is 8 bytes)"
            }
        }

        // UNCAPPED, and silent unless there is something to say. See
        // [ExtendedInfoTrace] for why the `sends < 3` cap above is not enough:
        // it answers "how big were the first three packets", and the question
        // that matters is "did a HITS block ever reach the wire", which only a
        // line printed at the moment it does can answer.
        //
        // One line per send that carries at least one block. A stationary npc
        // carries none and costs one bit, so an idle world is silent here; the
        // volume is bounded by combat events, not by ticks.
        lastReport?.let { report ->
            if (!report.isEmpty()) {
                logger.info {
                    "NPC_INFO ext-info -> ${player.name}: $report " +
                        "[packet ${buf.readableBytes()}B; cumulative ${ExtendedInfoTrace.counts()}]"
                }
            }
        }

        player.client.write(UnidentifiedPacket(OpcodeWithBuffer(opcode, buf)))
    }

    /**
     * The extended-info section of the last packet [createBufferFor] built, or
     * null before the first one.
     */
    @Volatile
    var lastReport: ExtendedInfoTrace.Report? = null
        private set

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var encodeFailureWarned = false

    @Volatile
    private var sends = 0
}
