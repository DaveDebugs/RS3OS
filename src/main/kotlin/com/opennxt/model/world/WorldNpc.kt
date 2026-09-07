package com.opennxt.model.world

import com.opennxt.model.combat.NpcCombat
import com.opennxt.model.combat.NpcCombatDefinition
import com.opennxt.model.combat.SeedData
import com.opennxt.model.combat.SeededValue
import com.opennxt.model.entity.LivingEntity
import com.opennxt.model.entity.movement.Movement
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.entity.rendering.EntityRenderer
import com.opennxt.model.entity.rendering.npc.NpcUpdates

/**
 * One NPC standing in the world, instantiated from one cache spawn record.
 *
 * ## Where every field comes from -- and where none of them come from
 *
 * - Identity and position come from the [spawn] record ([NpcSpawnData.Spawn]):
 *   npc GAME id and an absolute tile. That is the WHOLE record -- 4 bytes,
 *   both spent (see [NpcSpawnData.ABSENT_SPAWN_FIELDS]).
 * - [lifepoints] is the full [SeededValue] from [SeedData], provenance and
 *   source riding along (CACHE / DOCUMENTED / AUTHORED). When NO seed source
 *   has an opinion for this game id, [lifepoints] is null and stays null:
 *   there is no default, and [currentLifepoints] is null too. An NPC without
 *   seeded lifepoints is visibly health-less rather than quietly "100 LP".
 * - [combat] is the cache combat definition via [NpcCombat.load] -- nullable,
 *   because a game id can be absent from the definitions.
 * - [RESPAWN_TICKS] is RECONSTRUCTED and arbitrary; see its own doc.
 *
 * Damage/death/respawn state transitions are driven by
 * [com.opennxt.model.world.WorldNpcs], which owns the collection; this class
 * only holds the per-instance state and the local arithmetic.
 */
class WorldNpc(val spawn: NpcSpawnData.Spawn) :
    LivingEntity(TileLocation(spawn.x, spawn.y, spawn.plane)) {

    override val renderer = EntityRenderer(this)

    /**
     * Tile movement, over the SAME [Movement] and the same
     * [com.opennxt.model.map.CollisionMap] the player uses -- not an
     * npc-specific copy.
     */
    override val movement = Movement(this).also { it.speed = MovementSpeed.WALK }

    /**
     * NPC_INFO extended-info blocks queued for this npc.
     */
    val pendingUpdates = NpcUpdates()

    /** npcs.game_id -- NOT the cache archive index. See [NpcSpawnData]. */
    val gameId: Int = spawn.npcId

    /** npcs.name in game-id space, or null when the id is unnamed/unknown. */
    val name: String? = spawn.name

    var infoIndex: Int = -1
        internal set

    /**
     * Seeded lifepoints WITH provenance, or null when no seed source (cache,
     * wiki, authored) has an opinion. Kept as the whole [SeededValue] so the
     * provenance survives into the running world -- a caller can always ask a
     * live NPC where its health number came from.
     */
    val lifepoints: SeededValue? = SeedData.lifepoints(gameId)

    /** Cache combat definition (params 29/641/2865/... under their inferred readings), or null. */
    val combat: NpcCombatDefinition? = NpcCombat.load(gameId)

    /**
     * Current health. Null exactly when [lifepoints] is null -- an NPC whose
     * lifepoints no source seeded has no current health number either, ever.
     */
    var currentLifepoints: Int? = lifepoints?.value
        private set

    /**
     * The death animation queued when this npc last died, with its provenance, or
     * null. Set by `WorldNpcs.applyDamage`, cleared on respawn. Read by
     * [com.opennxt.model.combat.NpcDeathTransmission.lingerTicksFor]: a corpse with
     * a death animation is held for the animation's length.
     */
    var deathAnimation: com.opennxt.model.combat.NpcAnimObservations.DeathAnimation? = null

    var alive: Boolean = true
        private set

    /**
     * Ticks until respawn while dead; -1 while alive. Bookkeeping only --
     * [WorldNpcs.tick] decrements it and performs the respawn.
     */
    var respawnTicksRemaining: Int = -1
        private set

    /**
     * The respawn countdown this npc's last death was armed with, so that
     * `NpcDeathTransmission.ticksDead` can subtract from the real total rather
     * than assuming [RESPAWN_TICKS]. Set by [die]; [RESPAWN_TICKS] until then.
     */
    var respawnTotal: Int = RESPAWN_TICKS

    /**
     * THE DAMAGE LEDGER: damage applied
     * per attacker NAME since this life began. Read at death for loot rights ([topDamageDealer]:
     */
    val damageBy: LinkedHashMap<String, Int> = LinkedHashMap()

    /** The style each attacker last hit with, for the kill award's skill split. */
    val styleBy: LinkedHashMap<String, com.opennxt.model.combat.CombatStyle> = LinkedHashMap()

    /** Records [amount] lifepoints of applied damage against [attacker], in [style] when known. */
    internal fun recordDamage(attacker: String, amount: Int, style: com.opennxt.model.combat.CombatStyle? = null) {
        if (amount <= 0) return
        damageBy[attacker] = (damageBy[attacker] ?: 0) + amount
        if (style != null) styleBy[attacker] = style
    }

    /** The attacker with the most applied damage this life, earliest on a tie; null when nobody hit it. */
    fun topDamageDealer(): String? {
        var best: String? = null; var bestAmount = 0
        for ((name, amount) in damageBy) if (amount > bestAmount) { best = name; bestAmount = amount }
        return best
    }

    /** Damage share of [attacker] this life, 0..1 (0 when nobody has hit it). */
    fun damageShare(attacker: String): Double {
        val total = damageBy.values.sum()
        return if (total <= 0) 0.0 else (damageBy[attacker] ?: 0).toDouble() / total
    }

    /**
     * True once [WorldNpcs.despawn] removed this npc: it is
     * out of the world list, never transmitted, never respawned. Adds a boss spawned for a phase
     * are the reason it exists; a spawn-record npc is never despawned.
     */
    var despawned: Boolean = false
        private set

    /** [WorldNpcs.despawn] only: dead, never respawning, gone. */
    internal fun markDespawned() {
        despawned = true
        alive = false
        respawnTicksRemaining = -1
        damageBy.clear(); styleBy.clear()
    }

    /** The tile this NPC spawned on and respawns to. */
    val spawnTile: TileLocation get() = TileLocation(spawn.x, spawn.y, spawn.plane)

    /**
     * Subtracts damage from [currentLifepoints], flooring at 0. Returns the
     * amount actually applied.
     *
     * Throws for an NPC whose [lifepoints] is null: with no seeded maximum
     * there is no health to subtract from, and inventing one here is exactly
     * the silent default this class exists to refuse.
     */
    internal fun damage(amount: Int): Int {
        val lp = currentLifepoints ?: throw IllegalStateException(
            "npc $gameId (${name ?: "unnamed"}) has null lifepoints -- no seed source " +
                "(cache/documented/authored) covers this game id, and there is no default. " +
                "It cannot take damage until a seed source gives it a maximum."
        )
        check(alive) { "npc $gameId (${name ?: "unnamed"}) is dead; damage after death is a caller bug" }
        if (amount <= 0) return 0
        val applied = minOf(amount, lp)
        currentLifepoints = lp - applied
        return applied
    }

    /**
     * Heals [amount] lifepoints, clamped to the seeded maximum : the encounter primitive
     * a phase heal or a regeneration needs; threshold listeners see the next hit's (before, after)
     * from the healed value, which is why they keep a fired-set). Returns the amount restored.
     */
    fun heal(amount: Int): Int {
        val lp = currentLifepoints ?: return 0
        val max = lifepoints?.value ?: return 0
        if (!alive || amount <= 0) return 0
        val restored = minOf(amount, max - lp)
        currentLifepoints = lp + restored
        if (restored > 0) queueLifepointsBlock()
        return restored
    }

    /**
     * Queues the NPC_INFO lifepoints block (mask bit 16) for this npc's current
     * health, or does nothing when no seed source gave it a maximum -- the same
     * refusal [damage] makes, for the same reason: a bar with an invented
     * maximum is a lie drawn at 30 frames a second.
     *
     * Called by [WorldNpcs.applyDamage] and by [heal]; those are the only two
     * places [currentLifepoints] can move while the npc is alive.
     */
    fun queueLifepointsBlock() {
        val current = currentLifepoints ?: return
        val maximum = lifepoints?.value ?: return
        if (maximum <= 0) return
        pendingUpdates.lifepoints(current.coerceIn(0, maximum), maximum)
    }

    /** Marks this NPC dead and starts the respawn countdown. Called by [WorldNpcs] only. */
    internal fun die(respawnTicks: Int) {
        alive = false
        respawnTicksRemaining = respawnTicks
        respawnTotal = respawnTicks
        // aggression pursuit queued - the corpse is transmitted for its linger, steps and all.
        movement.reset()
    }

    /**
     * One tick of respawn bookkeeping. Returns true on the tick the NPC came
     * back: at its spawn tile, at full seeded lifepoints, alive.
     */
    internal fun tickRespawn(): Boolean {
        if (despawned) return false   // gone for good, never revived
        if (alive) return false
        if (respawnTicksRemaining > 0) {
            respawnTicksRemaining--
            if (respawnTicksRemaining > 0) return false
        }
        // Respawn: back on the spawn tile with full seeded LP.
        location = spawnTile
        previousLocation = location
        movement.reset()
        currentLifepoints = lifepoints?.value
        alive = true
        respawnTicksRemaining = -1
        damageBy.clear(); styleBy.clear()   // a new life owes nobody (I-27)
        return true
    }

    override fun clean() {
        // Nothing to release: no viewport, no client, no pooled resources.
    }

    override fun toString(): String =
        "WorldNpc(${name ?: "npc$gameId"}($gameId) @ (${location.x},${location.y},${location.plane}) " +
            "lp=${currentLifepoints?.toString() ?: "null"}/${lifepoints?.toString() ?: "null"} alive=$alive)"

    companion object {
        /**
         * Ticks between death and respawn.
         *
         * RECONSTRUCTED -- AND ARBITRARY, stated plainly. The cache spawn
         * record is 4 bytes (key + npc id) and carries NO respawn timer; that
         * absence is measured and recorded in
         * [NpcSpawnData.ABSENT_SPAWN_FIELDS]. Real RS3 respawn times vary per
         * NPC and per world population, and none of that data is available to
 * this server. 50 ticks (30 seconds at the 600ms tick) was picked
         * because it is in the middle of the publicly remembered range for
         * low-level monsters -- a plausible magnitude, nothing more. It is one
         * shared constant, loudly labelled, rather than a per-NPC table
         * pretending to precision the data cannot support.
         */
        const val RESPAWN_TICKS = 50
    }
}
