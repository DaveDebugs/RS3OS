package com.opennxt.model.world

import com.opennxt.api.stat.Stat
import com.opennxt.model.combat.NpcDeathTransmission
import com.opennxt.model.combat.AttackDelay
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.model.combat.Provenance
import com.opennxt.model.combat.SeededValue
import com.opennxt.model.combat.SeedData
import com.opennxt.model.combat.LifepointsLayer
import com.opennxt.model.drops.DropCategory
import com.opennxt.model.drops.DropData
import com.opennxt.model.drops.DropLine
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.map.CollisionMap
import java.util.Random

/**
 * The world's NPC population: every cache spawn record ([NpcSpawnData.spawns],
 * 1,388 of them over 0.39% of the world -- that limit is the spawn layer's,
 * recorded there, and this class does not work around it) instantiated as a
 * [WorldNpc] and ticked.
 *
 * EVERY record produces an entity, including the ones whose game id has no
 * seeded lifepoints -- those stand in the world with `lifepoints = null`,
 * visibly, rather than being dropped or defaulted. The provenance breakdown
 * ([lifepointProvenanceBreakdown]) makes the split auditable at a glance.
 *
 * Death handling ([applyDamage]) rolls the DOCUMENTED drop table via
 * [DropData]: always-drops always drop, the main table is rolled ONCE using
 * the wiki's own parsed rarities, and sub-table references ("@nothing",
 * "@herb_table", ...) are counted as UNEXPANDED rolls -- never invented into
 * items. All randomness comes from a caller-supplied [java.util.Random], so a
 * death is replayable from a seed.
 */
class WorldNpcs {

    companion object {
        /** For [spawnAt]; the rest of this class logs through its callers. */
        private val logger = mu.KotlinLogging.logger("WorldNpcs")

        /**
         * First NPC_INFO slot index handed to a cache-spawned npc.
         */
        const val WORLD_INDEX_BASE = 2

        /**
         * Cache-driven population switch.
         *
         * `-Dopennxt.experiment.npcs.spawns=false` makes [populate] instantiate
         * NOTHING, so the world holds no npcs at all and the only thing
         * NPC_INFO can carry is the demo spawn. Default ON.
         *
         * It is a separate switch from `-Dopennxt.experiment.npcs` (which kills
         * the packet outright) and from `-Dopennxt.experiment.npcs.wander`
         * (which only stops them moving) so that "1,388 entities exist",
         * "they are transmitted" and "they move" can each be taken off the
         * table on their own, without a rebuild.
         */
        val spawnsEnabled: Boolean = System.getProperty("opennxt.experiment.npcs.spawns") != "false"

        /**
         * The tile the combat demo npc stands on: [DEFAULT_COMBAT_DEMO_TILE]
         * unless the operator names another with
         * `-Dopennxt.experiment.combat.demospawn=<x>,<y>[,<plane>]`, and null
         * only for `=off` / `=none` / `=false`.
         */
        val combatDemoSpawn: TileLocation?
            get() {
                val raw = System.getProperty("opennxt.experiment.combat.demospawn")?.trim()
                    ?: return DEFAULT_COMBAT_DEMO_TILE
                if (raw.equals("off", true) || raw.equals("none", true) || raw.equals("false", true)) return null
                val parts = raw.split(',').map { it.trim().toIntOrNull() }
                if (parts.size < 2 || parts[0] == null || parts[1] == null) return null
                return TileLocation(parts[0]!!, parts[1]!!, parts.getOrNull(2)?.let { it } ?: 0)
            }

        /**
         * Where the combat demo npc stands when the operator names no tile.
         *
         * It is [com.opennxt.model.entity.updating.NpcInfoEncoder]'s own
         * DEMO_X/DEMO_Y/DEMO_PLANE, referenced rather than copied so the two can
         * never drift: two tiles east of the login tile, inside the first screen
         * and not underneath the player.
         *
         * INVENTED PLACEMENT. See [combatDemoSpawn] and
         * [COMBAT_DEMO_PROVENANCE]. It is the SAME invented tile the synthetic
         * decoration used, which is the point - nothing new was placed.
         */
        val DEFAULT_COMBAT_DEMO_TILE: TileLocation
            get() = TileLocation(
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_X,
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_Y,
                com.opennxt.model.entity.updating.NpcInfoEncoder.DEMO_PLANE
            )

        /**
         * 1 when [populate] will append the combat demo npc, 0 when it will not.
         *
         * Exists so a check can say "the cache layer plus the demo" without
         * re-deriving the switch, and so the demo cannot quietly inflate a count
         * that is supposed to be the cache's.
         */
        val combatDemoSpawnCount: Int get() = if (combatDemoSpawn == null) 0 else 1

        /**
         * Whether the combat demo npc wanders like every other npc.
         */
        val combatDemoWander: Boolean
            get() = System.getProperty("opennxt.experiment.combat.demospawn.wander") != "false"

        /**
         * The npc id the combat demo spawn uses.
         *
         * 41, the Chicken, and the choice is grounded rather than sentimental:
         * it is the one npc for which every input this loop needs is present and
         * checkable in this repository. `npcs.actions_1` = "Attack" and
         * `attackCursor` = 42 (so it is attackable, by both markers);
         * `combat` = 1; param 14 = 3, 641 = 150, 29 = 10, 2865 = 110 (a full
         * param set); [SeedData] gives it 250 lifepoints at DOCUMENTED
         * provenance; and [com.opennxt.model.drops.DropData] has a documented
         * 6-line drop table for it. It is also the id
         * [com.opennxt.model.entity.updating.NpcInfoEncoder]'s own demo already
         * uses, so nothing new appears on screen.
         */
        const val COMBAT_DEMO_NPC = 41

        /**
         * The provenance of the combat demo spawn as a runtime string, for the
         * same reason [World.AUTOSAVE_PROVENANCE] is one: a KDoc is invisible at
 * runtime, and this server's rule is that an invented value is labelled
         * where it is declared and cannot pass itself off as measured.
         */
        const val COMBAT_DEMO_PROVENANCE =
            "INVENTED PLACEMENT: the tile of the combat demo npc ($COMBAT_DEMO_NPC) is authored here (or the " +
                "operator's, via -Dopennxt.experiment.combat.demospawn); no cache field places an npc there. " +
                "It exists because the map square holding the login tile (3222,3222) carries no npc spawn " +
                "file at all, so 0 of the 1388 cache spawns fall within 40 tiles and the nearest is 42 - " +
                "three times NPC_INFO's 14-tile reach. It stands on the tile the SYNTHETIC decoration used " +
                "to occupy, so this replaces an invented placement rather than adding one. Everything ELSE " +
                "about it is cache/documented: definition, lifepoints (SeedData provenance) and drops."
    }

    // BUILD-THEN-PUBLISH, and the four are published TOGETHER.
    //
    // These were `ArrayList`/`HashMap` mutated in place by populateFrom. That
    // threw for real, on a real client run (logs/server-20260817-093031):
    //
    //   java.util.ConcurrentModificationException
    //     at java.util.ArrayList$Itr.checkForComodification
    //     at com.opennxt.model.world.WorldNpcs.tick(WorldNpcs.kt:233)
    //     at com.opennxt.model.world.World.tick(World.kt:165)
    //
    // OpenNXT.run() submitted `world` to the tick engine BEFORE calling
    // populate(), so the tick thread iterated this list every 600 ms while the
    // main thread appended 1,388 entries to it. populate() reads the spawn layer
    // AND parses ~10 MB of SeedData json, so the window is seconds wide, not
    // microseconds. The boot ordering is fixed too (see OpenNXT.run), but the
    // ordering fix ALONE would leave an implicit "nobody may tick me while I
    // populate" invariant with nothing enforcing it.
    //
    // `bySquare`/`byId` were the worse half, and nothing had noticed: a HashMap
    // resizing under a concurrent reader can return null for a key that IS
    // present, and NpcInfoEncoder reaches them through npcsInSquare(). That was
    // survivable only because the network binds after populate() -- i.e. by yet
    // another implicit ordering invariant.
    //
    // So populateFrom builds private locals and publishes all three with
    // volatile writes at the end. A reader sees either the whole old population
    // or the whole new one, never a half-built one, and the read path stays
    // lock-free: tick() iterates 1,388 entries every 600 ms and must not pay for
    // a lock, nor for CopyOnWriteArrayList's per-add array copy (1,388 adds
    // would copy ~1M references at boot).
    @Volatile private var npcs: List<WorldNpc> = emptyList()
    @Volatile private var bySquare: Map<Int, List<WorldNpc>> = emptyMap()
    @Volatile private var byId: Map<Int, List<WorldNpc>> = emptyMap()

 // FOURTH MAP: NPC_INFO slot -> npc. Published under exactly the
    // discipline above (built into a LOCAL by publishIndexes, written with a
    // volatile store, never mutated once visible), because it is read from the
    // network thread on every npc click and from the tick thread twice per
    // fishing player per tick. See [byInfoIndex] for what it replaced.
    @Volatile private var byIndex: Map<Int, WorldNpc> = emptyMap()

    /**
     * The entity [populate] built from [combatDemoSpawns], or null when the
     * demo spawn is switched off or the world was populated some other way.
     *
     * It is an IDENTITY, not a tile test, on purpose: [NpcWander] moves this npc
     * like any other, so "is anything standing on (3224,3222)?" would answer no
     * the moment it took a step, and
     * [com.opennxt.model.entity.updating.NpcInfoEncoder] would put the synthetic
     * decoration back on top of a real npc that had merely wandered.
     *
     * @Volatile for the same reason [npcs] is: written by the boot thread,
     * read by the tick thread and by every encode.
     */
    @Volatile private var demoNpc: WorldNpc? = null

    /** @see demoNpc */
    fun combatDemoNpc(): WorldNpc? = demoNpc

    /**
     * Ticks this population has run. It is the ONLY clock [NpcWander] consults,
     * so a wander is a pure function of (spawn tile, slot index, tick) and two
     * runs of the same world produce byte-identical motion. Reset by [populate].
     */
    var tickCount = 0L
        private set

    /** Ticks completed by [tick] since the last [populate]. */
    fun ticks(): Long = tickCount

    /**
     * Instantiates one [WorldNpc] per spawn record. Returns how many entities
     * exist afterwards -- by construction
     * `NpcSpawnData.spawns.size + combatDemoSpawnCount`, and
     * asserts exactly that.
     */
    fun populate(): Int {
        val demo = combatDemoSpawns()
        // No clear() in the spawns=false path any more: populateFrom REPLACES
        // all three structures wholesale, so clearing first would only widen
        // the window in which a reader sees an empty world.
        // The combat demo spawn is INDEPENDENT of the cache spawn layer on
        // purpose: it is the only attackable npc within reach of the login
        // tile (see combatDemoSpawn), so switching the 1,388 cache spawns
        // off must not also switch off the ability to test combat.
        val count = populateFrom(if (spawnsEnabled) NpcSpawnData.spawns() + demo else demo)
        // populateFrom cleared it; the demo is appended LAST, so it is the last
        // entity built. Asserted rather than assumed - if the ordering above
        // ever changes, this throws at boot instead of leaving combatDemoNpc()
        // pointing at some cache npc that happens to be last.
        if (demo.isNotEmpty()) {
            val last = npcs.last()
            check(last.gameId == COMBAT_DEMO_NPC &&
                last.location.x == demo[0].x && last.location.y == demo[0].y) {
                "populate() appended the combat demo spawn last but npcs.last() is $last"
            }
            demoNpc = last
        }
        return count
    }

    /**
     * The gated combat demo spawn as a 0- or 1-element list, so [populate] can
     * append it with `+` and there is no second code path through
     * [populateFrom].
     *
     * [NpcSpawnData.Spawn.squareId] is computed with the layer's own packing
     * ((x/64) | (y/64) << 7, stated on that field) rather than left at 0, so
     * this npc lands in the right [npcsInSquare] bucket like any other.
     */
    private fun combatDemoSpawns(): List<NpcSpawnData.Spawn> {
        val tile = combatDemoSpawn ?: return emptyList()
        return listOf(
            NpcSpawnData.Spawn(
                npcId = COMBAT_DEMO_NPC,
                x = tile.x, y = tile.y, plane = tile.plane,
                squareId = (tile.x / 64) or ((tile.y / 64) shl 7),
                // The cache's own name for the id, not a label invented here.
                name = com.opennxt.resources.sqlite.SqliteNpcCodec.load(COMBAT_DEMO_NPC)?.name
            )
        )
    }

    /**
     * The body of [populate], over an explicit spawn list.
     *
     * Split out so a harness can build a SMALL, fully determined world (one npc
     * on a known tile) and drive the same code the server runs, rather than a
     * parallel implementation. The slot allocation, the indices and the clock
     * reset are identical -- there is no test-only path through this class.
     */
    fun populateFrom(spawns: List<NpcSpawnData.Spawn>): Int {
        // Built into LOCALS. Nothing reachable by another thread is touched
        // until the three volatile writes at the bottom -- see the field
        // declarations for the ConcurrentModificationException this fixes.
        val newNpcs = ArrayList<WorldNpc>(spawns.size)
        val newBySquare = HashMap<Int, MutableList<WorldNpc>>()
        val newById = HashMap<Int, MutableList<WorldNpc>>()
        for (spawn in spawns) {
            val npc = WorldNpc(spawn)
            // NPC_INFO slot. Handed out here, once, densely from
            // WORLD_INDEX_BASE, so every populated npc has a stable 16-bit
            // identity the client can key its own map on. It is NOT the game
            // id and NOT the spawn's position -- two npcs of the same type on
            // the same square still need different slots.
            npc.infoIndex = WORLD_INDEX_BASE + newNpcs.size
            newNpcs.add(npc)
            newBySquare.getOrPut(spawn.squareId) { ArrayList() }.add(npc)
            newById.getOrPut(spawn.npcId) { ArrayList() }.add(npc)
        }
 // The walking-pose lookup is two SQLite reads on first use ,
        // [NpcWalkPose]). Resolved HERE, on the thread that populates the world,
        // so the tick thread never pays for it: after this the gate in
        // NpcWander.queueStep is a ConcurrentHashMap get. Cheap and idempotent -
        // only ids not already memoised are looked up.
        NpcWalkPose.warm(spawns.map { it.npcId }.toSet())
        // PUBLISH. tickCount is reset BEFORE the population is visible, so no
        // tick can ever read the new npcs against the old clock -- NpcWander is
        // a pure function of (spawn tile, slot, tick) and a torn pair there
        // would make npcs jump.
        tickCount = 0L
        // Cleared here, not in populate(), so a harness that drives populateFrom
        // directly can never inherit a stale demo npc from a previous world.
        // populate() sets it again immediately after this returns.
        demoNpc = null
        publishIndexes(newNpcs)
        npcs = newNpcs          // last: this is the one tick() iterates
        return newNpcs.size
    }

    /**
     * Builds and publishes the three lookup maps for [list], in the order the
     * field declarations require, and leaves [npcs] alone.
     */
    private fun publishIndexes(list: List<WorldNpc>) {
        val newBySquare = HashMap<Int, MutableList<WorldNpc>>()
        val newById = HashMap<Int, MutableList<WorldNpc>>()
        // Sized for the population so the build does no rehashing under a reader
        // -- irrelevant for correctness here (it is a local until the store
        // below) but it is the same 1,388-entry build the boot path pays.
        val newByIndex = HashMap<Int, WorldNpc>(list.size * 2)
        var collisions = 0
        for (n in list) {
            newBySquare.getOrPut(n.spawn.squareId) { ArrayList() }.add(n)
            newById.getOrPut(n.spawn.npcId) { ArrayList() }.add(n)
            // FIRST WINS, deliberately: the linear scans this map replaced were
            // `firstOrNull { it.infoIndex == index }` over `all()`, so keeping the
            // first occurrence in list order makes `byIndex[i]` equal to that scan
            // ENTRY FOR ENTRY, duplicates or not. `put` would keep the last and
            // silently disagree with the code it replaced.
            val prev = newByIndex.putIfAbsent(n.infoIndex, n)
            if (prev != null) {
                // Slots are handed out densely and NEVER reused (see
                // WORLD_INDEX_BASE and WorldNpc.infoIndex), so this is
                // unreachable by construction -- which is why it is REPORTED
                // rather than assumed away. It is not thrown: a duplicate slot
                // is a wire-level accounting defect, and taking the tick thread
                // down inside a publish would replace a visible-and-counted
                // fault with an invisible one.
                collisions++
                logger.error {
                    "infoIndex collision: slot ${n.infoIndex} is held by npc ${prev.gameId} " +
                        "(${prev.name ?: "unnamed"}) AND npc ${n.gameId} (${n.name ?: "unnamed"}); " +
                        "byInfoIndex keeps the first, exactly as the linear scan it replaced did. " +
                        "This should be impossible - slots are dense and never reused."
                }
            }
        }
        bySquare = newBySquare
        byId = newById
        byIndex = newByIndex
        infoIndexCollisions = collisions
    }

    /**
     * NPC_INFO slots that two or more live npcs claimed at the last publish. **0
     * by construction**; see [publishIndexes]. Exposed so a check can assert the
     * 1:1-ness of [byIndex] over the derived population instead of assuming it.
     */
    @Volatile
    var infoIndexCollisions: Int = 0
        private set

    /**
     * The npc holding NPC_INFO slot [index], or null. **No alive/dead filtering**
     * -- the map is keyed on [WorldNpc.infoIndex] alone and every caller applies
     * its own predicate, because the three callers want three different ones.
     *
     * ## What this replaced, and what it cost
     *
     * Three call sites resolved a slot by scanning the WHOLE population:
     *
     *  - `FishingWiring.npcAtIndex` (FishingWiring.kt:58), called from BOTH
     *    `Fishing.spotAlive` and `Fishing.spotTile` -- i.e. up to twice per
     *    fishing player PER TICK, on the tick thread. At the live population of
     *    ~1,388 npcs that is ~2,800 `infoIndex` comparisons per fisher per tick.
     *  - `OpNpcHandler.npcAtIndex` (OpNpcHandler.kt:79), per npc click.
     *  - `OpNpcHandler.corpseAtIndex` (OpNpcHandler.kt:85), same click, for the
     *    lingering-corpse case.
     *
     * All three are now a single hash lookup. The map is rebuilt only where the
     * population changes -- [populateFrom], [spawnAt], [despawn] -- and NOT on
     * any per-tick path, so the per-tick cost went from O(population) to O(1) and
     * the rebuild cost is paid once per world change.
     *
     * ## Exact equivalence to the scan
     *
     * [publishIndexes] keeps the FIRST npc at a slot, in list order, so this is
     * `all().firstOrNull { it.infoIndex == index }` for every possible list --
     * including one that violated the never-reused-slot invariant. The
     * `&& it.alive` / `&& !it.alive && transmissible(it)` halves of the old
     * predicates stayed at the call sites, unchanged.
     */
    fun byInfoIndex(index: Int): WorldNpc? = byIndex[index]

    /**
     * Adds npcs to the LIVE world without disturbing the ones already in it.
     *
     * [populateFrom] cannot do this: it REPLACES the population, resets [tickCount] and hands out fresh
     * info slots, so using it to add one goblin would re-seat every npc on the map and restart the
     * wander clock for all of them. This appends instead, using the same copy-then-publish discipline
     * the field declarations require - the three maps are rebuilt as locals and published last, so a
     * tick in flight either sees the old world or the new one and never a half-built list.
     *
     * Info slots continue from the current high-water mark rather than from the list size, because a
     * slot is an identity the client keys its own npc map on: reusing one that a still-visible npc
     * holds would make the client redraw that npc as this one.
     */
    fun spawnAt(npcId: Int, tile: TileLocation, count: Int = 1): Int {
        if (count <= 0) return 0
        val def = com.opennxt.resources.sqlite.SqliteNpcCodec.load(npcId)
        val current = npcs
        val newList = ArrayList<WorldNpc>(current.size + count)
        newList.addAll(current)
        var nextIndex = (current.maxOfOrNull { it.infoIndex } ?: (WORLD_INDEX_BASE - 1)) + 1
        val added = ArrayList<WorldNpc>(count)
        repeat(count) {
            val npc = WorldNpc(
                NpcSpawnData.Spawn(
                    npcId = npcId,
                    x = tile.x, y = tile.y, plane = tile.plane,
                    squareId = (tile.x / 64) or ((tile.y / 64) shl 7),
                    name = def?.name
                )
            )
            npc.infoIndex = nextIndex++
            newList.add(npc)
            added.add(npc)
        }
        publishIndexes(newList)
        npcs = newList          // last, exactly as populateFrom publishes
        logger.warn {
            "spawnAt: ${added.size} x npc $npcId (${def?.name ?: "unnamed"}) at " +
                "(${tile.x}, ${tile.y}, ${tile.plane}); population is now ${newList.size}"
        }
        return added.size
    }

    /**
     * One world tick: advance every dead NPC's respawn countdown, and retire
     * the NPC_INFO extended-info blocks that have already gone out.
     *
     * `World.tick()` calls this BEFORE the player phase, so a block queued
     * during a player phase would be destroyed before the next player phase
     * could carry it if this cleared unconditionally. Hence the
     * [com.opennxt.model.entity.rendering.npc.NpcUpdates.offered] gate: a block
     * survives until it has actually been written into somebody's packet, and
     * is dropped on the first tick after that. A block therefore appears on the
     * wire exactly once per player, which is what an animation or a hit splat
     * is -- an event, not a state.
     *
     * It ALSO runs the movement phase: [NpcWander] may queue one step, and
     * [com.opennxt.model.entity.movement.Movement.process] consumes it. That
     * ordering matters and is fixed by `World.tick()`, which calls this BEFORE
     * the player phase: an npc's tile and its `nextWalkDirection` are settled
     * for the tick before any NPC_INFO reads them, so every viewer of a moving
     * npc is told about the same step in the same tick.
     */
    var containedNpcFailures: Int = 0
        private set

    fun tick() {
        try {
            tickFault?.invoke()
            for (npc in npcs) {
                // review #5: one npc's throw must not skip the npcs after it (their respawn countdowns
                // would freeze) nor leave tickCount un-advanced (the ones before it would re-run the
                // same wander decision every tick).
                try {
                    tickOne(npc)
                } catch (t: Throwable) {
                    containedNpcFailures++
                    logger.error(t) { "npc ${npc.gameId} (${npc.name}) threw in the npc phase; contained, the other npcs still ticked" }
                }
            }
        } finally {
            tickCount++
        }
    }

    private fun tickOne(npc: WorldNpc) {
        val frozen = if (combatDemoWander) null else demoNpc
        run {
            if (npc.tickRespawn()) {
                // still in every viewer's scene, in its death pose. The reference client resets it with an
                // ANIMATION -1 at respawn (observed: +65.103 packets after the kill); so do we.
                if (npc.deathAnimation != null) {
                    npc.pendingUpdates.animate(com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationBlock.stop())
                }
                npc.deathAnimation = null                       // the next death resolves afresh
            }
            // `npc !== frozen`: see [combatDemoWander]. It is null by DEFAULT
 // since - the demo npc wanders like everything else,
            // because PlayerCombat now chases. The branch survives only for the
            // `=false` arm, which lets a check ask the stationary-target question
            // separately from the moving-target one.
            if (npc.alive && npc !== frozen) NpcWander.queueStep(npc, tickCount)
            // Always processed, wander or not: process() is what clears last
            // tick's nextWalkDirection, and a stale direction would be re-sent
            // as a second step the npc never took.
            npc.movement.process()
            // ...or the npc is a corpse past its transmission window still
            // holding blocks that can never be offered to anyone.
            if (npc.pendingUpdates.offered || NpcDeathTransmission.shouldRetire(npc)) npc.pendingUpdates.clear()
        }
    }

    /**
     * DESPAWN: removes [npc] from the world
     * for good - out of the list and both indexes, dead, never respawning, never transmitted
     * ([NpcDeathTransmission.transmissible] refuses a despawned npc) - and tells
     * [com.opennxt.model.combat.PlayerCombat] to forget it (engagements, aggro, pursuit).
     */
    fun despawn(npc: WorldNpc): Boolean {
        val current = npcs
        if (current.none { it === npc }) return false
        val newList = ArrayList<WorldNpc>(current.size - 1)
        for (n in current) if (n !== npc) newList.add(n)
        npc.markDespawned()
        npc.pendingUpdates.clear()
        npc.movement.reset()
        if (demoNpc === npc) demoNpc = null
        com.opennxt.model.combat.PlayerCombat.forgetNpc(npc)
        // ...and out of byIndex with the other two: newList excludes it, so its
        // slot is simply absent afterwards and, because slots are never reused,
        // stays absent forever.
        publishIndexes(newList)
        npcs = newList
        despawned++
        logger.info { "despawn: npc ${npc.gameId} (${npc.name ?: "unnamed"}) index ${npc.infoIndex} removed; population is now ${newList.size}" }
        return true
    }
    /** Npcs despawned since boot. */
    var despawned: Int = 0
        private set

    // ------------------------------------------------------------- queries

    fun all(): List<WorldNpc> = npcs

    fun count(): Int = npcs.size

    fun aliveCount(): Int = npcs.count { it.alive }

    fun deadCount(): Int = npcs.count { !it.alive }

    /** NPCs instantiated from spawns in map square [squareId] ((x/64) | (y/64)<<7). */
    fun npcsInSquare(squareId: Int): List<WorldNpc> = bySquare[squareId] ?: emptyList()

    /** First ALIVE npc currently standing on the tile, or null. */
    fun npcAt(x: Int, y: Int, plane: Int = 0): WorldNpc? =
        npcs.firstOrNull {
            it.alive && it.location.x == x && it.location.y == y && it.location.plane == plane
        }

    /** Every instance of a game id. Game-id space -- see [NpcSpawnData]. */
    fun byGameId(gameId: Int): List<WorldNpc> = byId[gameId] ?: emptyList()

    /**
     * How many entities' lifepoints came from each provenance, with the
     * no-source case as an explicit "null" bucket. The four numbers sum to
     * [count] -- nothing is dropped, nothing is defaulted into a bucket it
     * did not earn.
     */
    fun lifepointProvenanceBreakdown(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        for (p in Provenance.values()) m[p.name] = 0
        m["null"] = 0
        for (npc in npcs) {
            val key = npc.lifepoints?.provenance?.name ?: "null"
            m[key] = m[key]!! + 1
        }
        return m
    }

    /**
     * The SAME entities as [lifepointProvenanceBreakdown], split by which
     * PRECEDENCE LAYER answered rather than by the provenance the answer
     * carries. The two are not the same question, and reporting only the
     * first is what made the variant layer invisible: a variant-disambiguated
     * value keeps [Provenance.DOCUMENTED] (the NUMBER is the wiki's; only the
     * CHOICE of variant is ours -- see [SeedData.VARIANT_PROVENANCE]), so the
     * 280 ids it adds were being counted inside DOCUMENTED with nothing in the
     * boot line to distinguish them.
     *
     * Keyed by [LifepointsLayer] name plus an explicit "null" bucket for
     * entities no layer has an opinion on. Sums to [count].
     */
    fun lifepointLayerBreakdown(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        for (l in LifepointsLayer.values()) m[l.name] = 0
        m["null"] = 0
        for (npc in npcs) {
            val key = SeedData.layerOf(npc.gameId)?.name ?: "null"
            m[key] = m[key]!! + 1
        }
        return m
    }

    // ------------------------------------------------------------- combat

    /**
     * Applies [dmg] to [npc]. Returns null while the NPC survives; on the
     * killing blow, marks it dead (respawning after [WorldNpc.RESPAWN_TICKS]
     * -- a RECONSTRUCTED, arbitrary delay, documented there), rolls its
     * documented drops with [random], and returns the [DeathResult].
     */
    fun applyDamage(npc: WorldNpc, dmg: Int, random: Random): DeathResult? =
        applyDamage(npc, dmg, random, groundItems = null, owner = null)

    /**
     * As above, and on a kill ALSO grounds the death's resolvable drops
     * through [GroundItems.spawn] on the tile the NPC died on. The spawn's
 * full result -- including the typed [SkippedUnresolved] list for lines
     * whose item name or quantity the data could not ground -- rides back on
     * [DeathResult.groundSpawn]. The [DeathResult.unexpandedTableRolls]
     * reporting is untouched: sub-table references are still rolls that
     * happened and items that did not.
     *
     * [owner] names the player whose kill this was (for the ground items'
     * ownership field), or null for an ownerless death.
     */
    /** The most recent death animation [applyDamage] queued, with its provenance; null if none yet. */
    var lastDeathAnimation: com.opennxt.model.combat.NpcAnimObservations.DeathAnimation? = null
        private set

    /**
     * A per-npc fault the tick phase runs before anything else for that npc ,
     * that a throw in the npc phase is contained by World.tick rather than skipping the
     * player phases. Internal so nothing outside the module can reach it.
     */
    internal var tickFault: (() -> Unit)? = null

    /**
     * A damage observer: called from [applyDamage]
     * with the lifepoints BEFORE and AFTER every applied hit, before the death branch, so an
     * encounter can fire a health threshold exactly once by keeping its own fired-set (I-16) -
     * a heal that re-crosses the line sees the same (before, after) shape and the set says no.
     */
    fun interface NpcDamageListener {
        fun onDamage(npc: WorldNpc, before: Int, after: Int, attacker: String?)
    }
    private val damageListeners = java.util.concurrent.CopyOnWriteArrayList<NpcDamageListener>()
    fun addDamageListener(listener: NpcDamageListener) { damageListeners.add(listener) }
    fun removeDamageListener(listener: NpcDamageListener) { damageListeners.remove(listener) }
    fun damageListenerCount(): Int = damageListeners.size
    var containedListenerFailures: Int = 0
        private set

    fun applyDamage(
        npc: WorldNpc,
        dmg: Int,
        random: Random,
        groundItems: GroundItems?,
        owner: String? = null,
        style: com.opennxt.model.combat.CombatStyle? = null
    ): DeathResult? {
        val before = npc.currentLifepoints ?: 0
        val applied = npc.damage(dmg)
        val after = npc.currentLifepoints!!
 // THE LIFEPOINTS BLOCK. NPC_INFO mask bit 16 carries {current,
        // maximum} and is what draws the number and the bar on interface 1490
        // `toplevel_v2_target_info`. The reference client sends it on exactly the ticks the value
 // changes - 1,535 records, every one of them
        // agreeing with `maximum - sum(splats)` (NpcLifepointsBlock's KDoc).
        // Queued HERE rather than at the hit site so that retaliation, aggression,
        // an encounter script and a plain ::hit all reach it: this is the one funnel
        // through which an npc's current lifepoints can fall.
        if (applied > 0) npc.queueLifepointsBlock()
        // the ledger (loot rights, retaliation target, the kill's xp split) and the observers, before the death branch
        if (owner != null) npc.recordDamage(owner, applied, style)
        if (damageListeners.isNotEmpty() && applied > 0) {
            for (l in damageListeners) {
                try { l.onDamage(npc, before, after, owner) } catch (t: Throwable) {
                    containedListenerFailures++
                    logger.error(t) { "npc damage listener threw on npc ${npc.gameId}; contained" }
                }
            }
        }
        // ran the death branch) or heal (the npc is no longer at 0) - decide death on the state the
        // listeners LEFT, never on `after`.
        if (!npc.alive || npc.despawned) return null
        if (npc.currentLifepoints!! > 0) return null
        // LOOT RIGHTS BY DAMAGE SHARE (finding 3): the top of the ledger owns the drops, not the
        // killing blow. `owner` stands in only when nobody was recorded (an ownerless kill).
        val lootOwner = npc.topDamageDealer() ?: owner

        val respawnTicks = WorldNpc.RESPAWN_TICKS
        npc.die(respawnTicks)

 //the death animation the reference client was sending for this npc, or for an
        // npc sharing its animation group (NpcAnimObservations - provenance on the value).
        // Null is a refusal and queues nothing, which is what every kill did before today.
        // NpcDeathTransmission already carries a queued animation to the client on the
        // death tick; whether the corpse stays long enough to play it is its linger switch.
        npc.deathAnimation = null
        com.opennxt.model.combat.NpcAnimObservations.deathAnimation(npc.gameId)?.let {
            npc.pendingUpdates.animate(it.sequence)
            npc.deathAnimation = it
            lastDeathAnimation = it
        }

        // The tile the NPC died on, snapshotted BEFORE any respawn bookkeeping
        // can move the (mutable) location back to the spawn tile.
        val deathTile = TileLocation(npc.location.x, npc.location.y, npc.location.plane)

        // lifepoints is non-null here: damage() would have thrown otherwise.
        val provenance = npc.lifepoints!!
        val table = DropData.monsterForNpc(npc.gameId)
        if (table == null) {
            // No documented monster covers this game id at all. That is a
            // recorded gap, not an empty table: dropped nothing, and said so.
            val death = DeathResult(
                npcGameId = npc.gameId, npcName = npc.name,
                dropped = emptyList(), unexpandedTableRolls = emptyList(),
                unrolledLines = emptyList(), tertiaryLinesNotRolled = 0,
                lifepointsProvenance = provenance,
                respawnTicks = npc.respawnTotal,
                dropTableNote = "no documented drop table covers game id ${npc.gameId}" +
                    (npc.name?.let { " ($it)" } ?: ""),
                lootOwner = lootOwner, damageLedger = LinkedHashMap(npc.damageBy), styleLedger = LinkedHashMap(npc.styleBy)
            )
            // Nothing dropped, but the spawn still runs (and returns an empty
            // result) so the caller sees "grounded nothing" rather than null.
            return death.copy(groundSpawn = groundItems?.spawn(death, deathTile, lootOwner))
        }

        val dropped = ArrayList<DroppedItem>()
        val unexpanded = ArrayList<DropLine>()
        val unrolled = ArrayList<DropLine>()

        // Always-drops: every line drops, quantity picked from the parsed range. A table
 // REFERENCE filed as always is an
        // always-roll of a sub-table: reported unexpanded, never invented into items.
        for (line in table.drops.filter { it.category == DropCategory.ALWAYS }) {
            if (line.isTableRef) unexpanded.add(line) else dropped.add(toDrop(line, random))
        }

        // Main table: ONE roll over the lines whose wiki rarity parsed as a
        // fraction. u is uniform in [0,1); the first line whose cumulative
        // probability exceeds it wins. Lines whose rarity did not parse are
        // excluded from the roll and reported in [DeathResult.unrolledLines]
        // -- excluded loudly, not silently.
        val main = table.drops.filter { it.category == DropCategory.MAIN }
        val rollable = main.filter { it.rarityNum != null && it.rarityDen != null && it.rarityDen != 0.0 }
        unrolled += main.filterNot { it in rollable }
        if (rollable.isNotEmpty()) {
            val u = random.nextDouble()
            var cum = 0.0
            for (line in rollable) {
                cum += line.rarityNum!! / line.rarityDen!!
                if (u < cum) {
                    if (line.isTableRef) {
                        // "@nothing" / "@herb_table" / ...: the roll happened
                        // and is reported, but its contents are not invented.
                        unexpanded.add(line)
                    } else {
                        dropped.add(toDrop(line, random))
                    }
                    break
                }
            }
            // u >= total cumulative probability: the wiki table's fractions do
            // not sum to 1 and this roll fell in the gap -- no main drop.
        }

        val death = DeathResult(
            npcGameId = npc.gameId, npcName = npc.name,
            dropped = dropped, unexpandedTableRolls = unexpanded,
            unrolledLines = unrolled,
            tertiaryLinesNotRolled = table.drops.count { it.category == DropCategory.TERTIARY },
            lifepointsProvenance = provenance,
            respawnTicks = npc.respawnTotal,
            dropTableNote = table.note,
            lootOwner = lootOwner, damageLedger = LinkedHashMap(npc.damageBy), styleLedger = LinkedHashMap(npc.styleBy)
        )
        return death.copy(groundSpawn = groundItems?.spawn(death, deathTile, lootOwner))
    }

    private fun toDrop(line: DropLine, random: Random): DroppedItem {
        // Quantity: deterministic given the caller's Random. When the wiki
        // string did not parse to a range, quantity is null -- reported as
        // unknown rather than invented as 1.
        // audit E-03: an inverted range ("45000-5500") is refused by the parser now; the guard
        // stays so a bad range can never turn into a negative nextInt bound inside a kill.
        val qty = line.quantityRange?.takeIf { it.last >= it.first }?.let { r -> r.first + random.nextInt(r.last - r.first + 1) }
        return DroppedItem(
            itemName = line.itemName,
            itemIds = line.itemIds,
            quantity = qty,
            category = line.category,
            rarity = line.rarity,
            source = line.source
        )
    }
}

/**
 * One item that actually dropped. [quantity] is null when the wiki quantity
 * string did not parse -- the drop is real, the amount undocumented.
 */
data class DroppedItem(
    val itemName: String,
    /** Item ids the wiki name matched; can be several (ambiguous) or empty (unresolved). */
    val itemIds: List<Int>,
    /** The id that grounds: the canonical id for names that have one (Coins -> 995), else the lowest. */
    val itemId: Int? = com.opennxt.model.drops.DropData.chosenItemId(itemName, itemIds),
    val quantity: Int?,
    val category: DropCategory,
    /** Verbatim wiki rarity string of the line that produced this drop. */
    val rarity: String,
    /** Wiki article URL the line came from. */
    val source: String
) {
    override fun toString() = "$itemName x${quantity?.toString() ?: "?"} ($rarity) ids=$itemIds"
}

/**
 * What one NPC death produced, with the provenance of the lifepoints that
 * governed the fight riding along.
 *
 * [unexpandedTableRolls] are main-table rolls that landed on a sub-table
 * reference ("@nothing", "@herb_table", ...): the roll is reported, its
 * contents are NOT invented into items. [unrolledLines] are main-table lines
 * whose wiki rarity string did not parse as a fraction and therefore could
 * not participate in the roll. [tertiaryLinesNotRolled] counts tertiary lines
 * this model does not roll at all (tertiary drops are independent per-kill
 * rolls in RS3; modelling them is future work, and the count keeps the
 * omission visible).
 */
data class DeathResult(
    val npcGameId: Int,
    val npcName: String?,
    val dropped: List<DroppedItem>,
    val unexpandedTableRolls: List<DropLine>,
    val unrolledLines: List<DropLine>,
    val tertiaryLinesNotRolled: Int,
    /** The seeded lifepoints value (with provenance/source) the fight ran against. */
    val lifepointsProvenance: SeededValue,
    /** RECONSTRUCTED respawn delay applied -- see [WorldNpc.RESPAWN_TICKS]. */
    val respawnTicks: Int,
    /** Monster-level note from the drop seed, or a gap explanation, or null. */
    val dropTableNote: String?,
    /**
     * What [GroundItems.spawn] grounded from this death, when the kill went
     * through the ground-item-aware [WorldNpcs.applyDamage] overload. Null
     * means "ground items were not asked for" (the legacy 3-argument path),
     * NOT "nothing spawned" -- an empty spawn is a non-null [SpawnResult]
     * with empty lists.
     */
    val groundSpawn: SpawnResult? = null,
    val lootOwner: String? = null,
    val damageLedger: Map<String, Int> = emptyMap(),
    val styleLedger: Map<String, com.opennxt.model.combat.CombatStyle> = emptyMap()
) {
    override fun toString() =
        "DeathResult(${npcName ?: "npc$npcGameId"}($npcGameId): dropped=$dropped, " +
            "unexpanded=${unexpandedTableRolls.map { it.itemName + " @ " + it.rarity }}, " +
            "lp=${lifepointsProvenance}, respawn=${respawnTicks}t" +
            (groundSpawn?.let { ", ground=$it" } ?: "") + ")"
}

/**
 * NPC retaliation: an attacked NPC fighting back at a [CombatDefender], each
 * its-own-attack-speed ticks, through the same RECONSTRUCTED [CombatFormulas]
 * the player attacks with -- just pointed the other way.
 */
class NpcRetaliation {

    companion object {
        /**
         * The defending player's affinity value, as seen by an attacking NPC.
         *
         * RECONSTRUCTED -- NOT IN THE CACHE. The runescape.wiki "Affinity"
         * article documents that a defending PLAYER has a flat affinity of 55
         * against all styles when not wearing an armour class that shifts it
         * (45 against the style the worn class is strong to, 65 against the
         * weak one). This model does not model worn armour (see
         * [PlayerCombatStats.defence], which states the same absence), so the
         * armourless 55 is the only value that is consistent to use. The
         * player-side affinity params, if any exist, have not been identified
         * in this cache; this number is imported from the wiki, not measured.
         */
        const val PLAYER_AFFINITY = 55
    }

    /** Internal tick clock; advanced by [tick], read by the attack-speed gate. */
    private var clock = 0

    /** Per-NPC tick at which its next attack is allowed. */
    private val nextAttackAt = HashMap<WorldNpc, Int>()
    fun forget(npc: WorldNpc) { nextAttackAt.remove(npc) }
    fun tracks(npc: WorldNpc): Boolean = nextAttackAt.containsKey(npc)

    /** One world tick of the retaliation clock. */
    fun tick() {
        clock++
    }

    /**
     * One retaliation attempt by [npc] against [player].
     *
     * Call it every tick for an NPC that has been attacked; the attack-speed
     * gate (cache param 14, in game ticks, via [CombatFormulas.tickDelay])
     * turns the extra calls into [RetaliationOutcome.NotDue]. The first call
     * for an NPC attacks immediately; each landed attempt then re-arms the
     * gate for the NPC's own speed.
     *
     * Refusal cases (typed, never defaulted):
     *  - dead NPC (a corpse does not retaliate)
     *  - null seeded lifepoints (an NPC that cannot be fought does not fight)
     *  - no combat definition / no combat-class param / no accuracy or damage
     *    param for its style / no attack-speed param
     *  - an attack-speed param outside the plausible reading
     *    ([AttackDelay.Implausible] -- a failed inference must not become a
     *    swing timer)
     */
    fun retaliate(npc: WorldNpc, player: CombatDefender, random: Random): RetaliationOutcome {
        if (!npc.alive) return RetaliationOutcome.Refused(
            npc, "npc is dead (respawns in ${npc.respawnTicksRemaining} ticks) -- corpses do not retaliate"
        )
        if (npc.lifepoints == null) return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has null seeded lifepoints -- an NPC that cannot be fought " +
                "does not enter combat, in either direction; no default"
        )
        val combat = npc.combat ?: return RetaliationOutcome.Refused(
            npc, "no combat definition for game id ${npc.gameId}"
        )
        val style = combat.combatStyle ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} has no combat-class param -- no style, so no way to pick " +
                "which accuracy and damage apply; absent is not melee-by-default"
        )
        val accuracy = combat.accuracyFor(style) ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} carries no $style accuracy param; absent is not zero"
        )
        val maxHitX10 = combat.damageFor(style) ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} carries no $style damage param; absent is not zero"
        )
        val rawSpeed = combat.attackSpeed ?: return RetaliationOutcome.Refused(
            npc, "npc ${npc.gameId} carries no attack-speed param (14); absent is not the standard 4"
        )
        val speed = when (val delay = CombatFormulas.tickDelay(rawSpeed)) {
            is AttackDelay.Ticks -> delay.ticks
            is AttackDelay.Implausible -> return RetaliationOutcome.Refused(
                npc, "npc ${npc.gameId} param-14 value ${delay.rawValue} is outside the plausible " +
                    "attack-speed reading (${CombatFormulas.PLAUSIBLE_TICKS}) -- not a swing timer"
            )
        }

        val due = nextAttackAt[npc] ?: clock
        if (clock < due) return RetaliationOutcome.NotDue(npc, ticksUntilDue = due - clock)
        nextAttackAt[npc] = clock + speed

        // Player's defending side: derived defence + the RECONSTRUCTED flat
        // affinity. Both directions of the fight use the same hitChance.
        val playerDefence = PlayerCombatStats.defence(player.level(Stat.DEFENCE))
        val chance = CombatFormulas.hitChance(accuracy, playerDefence, PLAYER_AFFINITY)
        if (random.nextDouble() >= chance) return RetaliationOutcome.Missed(npc, chance)

        // x10 -> lifepoints, divided visibly at the point of use.
        val damage = CombatFormulas.damageRoll(maxHitX10 / 10, random)
        val death = player.takeDamage(damage)
        return if (death != null) RetaliationOutcome.KilledPlayer(npc, damage, death)
        else RetaliationOutcome.Hit(npc, damage, player.currentLifepoints)
    }
}

/**
 * What one retaliation attempt did. [Refused] carries the exact reason the
 * NPC cannot enter combat -- typed, so a caller cannot mistake "this NPC has
 * no combat data" for "this NPC missed".
 */
sealed class RetaliationOutcome {
    /** This NPC never enters combat, and here is why. */
    data class Refused(val npc: WorldNpc, val reason: String) : RetaliationOutcome()

    /** Combat-capable, but the attack-speed gate has not re-armed yet. */
    data class NotDue(val npc: WorldNpc, val ticksUntilDue: Int) : RetaliationOutcome()

    data class Missed(val npc: WorldNpc, val hitChance: Double) : RetaliationOutcome()

    data class Hit(val npc: WorldNpc, val damage: Int, val playerLifepointsLeft: Int) : RetaliationOutcome()

    /** The hit dropped the player to 0 LP; [death] is the player's typed death event. */
    data class KilledPlayer(val npc: WorldNpc, val damage: Int, val death: PlayerDeath) : RetaliationOutcome()
}

/**
 * The one piece of INVENTED behaviour in the npc stack: a slow random walk
 * inside a leash of the spawn tile.
 */
object NpcWander {

    /**
     * `-Dopennxt.experiment.npcs.wander=false` freezes every npc where it
     * stands. Default ON. Independent of `opennxt.experiment.npcs.spawns`
     * (population) and `opennxt.experiment.npcs` (the packet itself).
     */
    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs.wander") != "false"

    /**
     * How far an npc may stray from its spawn tile, in tiles, Chebyshev.
     *
     * INVENTED. 3 is small enough that an npc stays recognisably "at" the
     * place the cache put it (a chicken in the chicken farm does not walk into
     * the river) and large enough that motion is visible on one screen.
     */
    const val LEASH_RADIUS = 3

    /**
     * Ticks between step attempts for one npc. 8 ticks x 600ms = 4.8s.
     *
     * INVENTED. Deliberately slow: pass A costs 1 bit for an idle npc and 7
     * bits for a walking one, so a fast cadence multiplied by the 49 npcs the
     * densest cache square can put in view is packet volume bought for
     * nothing. Must be a power of two -- [phaseOf] uses it as a mask.
     */
    const val INTERVAL_TICKS = 8

    /**
     * The only seed. Fixed so that two runs of the same world produce the same
     * motion; see the class note. 0x4E5043 is the ASCII of "NPC" and carries
     * no other meaning.
     */
    const val SEED = 0x4E5043L

    /** SplitMix64's finalizer. Pure; no state. */
    private fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /**
     * The tick offset within [INTERVAL_TICKS] at which npc [index] attempts a
     * step. Spreads the population across the cadence so they do not all move
     * on the same tick (which would make every eighth packet the big one).
     */
    fun phaseOf(index: Int): Int = (mix(SEED + index) ushr 8).toInt() and (INTERVAL_TICKS - 1)

    /** True when npc [index] is due a step attempt on [tick]. */
    fun isDue(index: Int, tick: Long): Boolean =
        ((tick + phaseOf(index)) % INTERVAL_TICKS) == 0L

    fun directionOf(index: Int, tick: Long): Int =
        (mix(mix(SEED + index) + tick) ushr 17).toInt() and 7

    /**
     * Queues at most one step for [npc]. Returns true when a step was actually
     * queued -- false for "not enabled", "not due", "outside the leash" and
     * "blocked", which are all ordinary outcomes and not errors.
     *
     * Never queues more than one tile, so [com.opennxt.model.entity.movement.Movement.process]
     */
    fun queueStep(npc: WorldNpc, tick: Long): Boolean {
        if (!enabled) return false
        if (npc.infoIndex < 0) return false
 //: an npc the CLIENT has no walking pose for must not be given a
        // wander step - it slides its standing pose along the ground, which is what
        // "May in Varrock scoots around" is. The predicate, its evidence and the
        // 0.52% the reference client false-freeze rate that bounds it are in [NpcWalkPose];
        // -Dopennxt.experiment.npcs.wander.pose=false restores the old behaviour.
        // This gate is on the WANDER only: a combat pursuit still moves the npc,
        // because a pursuit is content deciding to move it, not this invented walk.
        if (!NpcWalkPose.mayWander(npc.gameId)) return false
        if (npc.movement.hasSteps) return false
 // (audit T-10): a fighting npc stands its ground; it does not wander
        // off mid-fight and drag the player's chase after it.
        if (com.opennxt.model.combat.PlayerCombat.isInCombat(npc)) return false
        if (!isDue(npc.infoIndex, tick)) return false

        val dir = CompassPoint.getById(directionOf(npc.infoIndex, tick)) ?: return false
        val from = npc.location
        val tx = from.x + dir.dx
        val ty = from.y + dir.dy

        val spawn = npc.spawn
        if (Math.abs(tx - spawn.x) > LEASH_RADIUS) return false
        if (Math.abs(ty - spawn.y) > LEASH_RADIUS) return false
        if (from.plane != spawn.plane) return false

        // addStep does the CollisionMap.canStep call, diagonal rule included.
        // A blocked tile is refused there, not second-guessed here.
        return npc.movement.addStep(tx, ty)
    }

    /**
     * Every tile npc [index] would occupy over [ticks] ticks starting from
     * [spawn], with collision consulted exactly as [queueStep] consults it.
     *
     * A pure projection used to prove determinism and the leash without having
     * to stand up a World. It reproduces the live path's decisions because it
     * makes the same three calls in the same order ([isDue], [directionOf],
     * [CollisionMap.canStep]); if the two ever disagree, the check that
     * compares a projected route against a live-ticked one fails.
     */
    fun project(index: Int, spawn: TileLocation, ticks: Int): List<TileLocation> {
        val out = ArrayList<TileLocation>(ticks)
        var cur = spawn
        for (t in 0 until ticks) {
            if (isDue(index, t.toLong())) {
                val dir = CompassPoint.getById(directionOf(index, t.toLong()))
                if (dir != null) {
                    val tx = cur.x + dir.dx
                    val ty = cur.y + dir.dy
                    if (Math.abs(tx - spawn.x) <= LEASH_RADIUS &&
                        Math.abs(ty - spawn.y) <= LEASH_RADIUS &&
                        CollisionMap.canStep(cur.x, cur.y, dir.dx, dir.dy, cur.plane)
                    ) {
                        cur = TileLocation(tx, ty, cur.plane)
                    }
                }
            }
            out.add(cur)
        }
        return out
    }
}
