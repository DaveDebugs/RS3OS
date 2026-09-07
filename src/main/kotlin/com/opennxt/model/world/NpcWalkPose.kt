package com.opennxt.model.world

import com.google.gson.JsonParser
import com.opennxt.resources.sqlite.RsDatabase
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Does the CLIENT have a walking animation for this npc?
 */
object NpcWalkPose {

    private val logger = KotlinLogging.logger { }

    /**
     * `-Dopennxt.experiment.npcs.wander.pose=false` turns the gate off and every
     * npc wanders again, exactly as before. Default ON.
     */
    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs.wander.pose") != "false"

    /** What the cache says about an npc's ability to be seen walking. */
    enum class Pose(val walks: Boolean) {
        /** The group's `baseAnims.walk` is a real sequence id. */
        WALK(true),

        /** `baseAnims.walk` is -1, but the group carries opcode 2 (walk-family). */
        OPCODE2(true),

        /** `baseAnims.walk` is -1 and there is no opcode 2: the client has no walking pose. */
        NONE(false),

        /** The npc record carries no animation group at all (opcode 127 absent). */
        NO_GROUP(false),

        /** The database does not answer. Fail-open; see the class doc. */
        UNKNOWN(true)
    }

    /** A row exists but declares no animation group. Distinct from "no row at all". */
    private const val NO_GROUP_DECLARED = -1

    /**
     * npc game id -> its animation group, or [NO_GROUP_DECLARED] when the row exists and
     * declares none. ONE query, and it answers both questions the predicate asks: is this
     * id in the database at all (absent key = no), and what group does it name.
     *
     * `Int2IntOpenHashMap` rather than `HashMap<Int, Int>`: 32,762 rows of boxed keys and
     * values is a few megabytes of `Integer` for a table that is read once and never
     * written. `defaultReturnValue` is not used - `containsKey` is the membership test,
     * because 0 and -1 are both meaningful here.
     */
    private val groupOfNpc: Int2IntOpenHashMap by lazy {
        val out = Int2IntOpenHashMap()
        RsDatabase.queryAll("SELECT id, animation_group FROM npcs") { rs ->
            // wasNull() reports on the LAST getter called, so both have to be read and
            // tested before anything else touches the row. Reading the id inside the
            // put() call put a getInt(1) between getInt(2) and wasNull() and silently
            // turned all 176 no-animation-group npcs into group 0; the pinned census
            // below caught it.
            val group = rs.getInt(2)
            val absent = rs.wasNull()
            val id = rs.getInt(1)
            out.put(id, if (absent) NO_GROUP_DECLARED else group)
        }
        out
    }

    /** animation group -> the pose the group itself declares. */
    private val poseOfGroup: Map<Int, Pose> by lazy {
        val walkOf = HashMap<Int, Int>()
        val hasOpcode2 = HashSet<Int>()
        RsDatabase.queryAll(
            "SELECT id, field, value FROM animgroups_attr WHERE field IN ('baseAnims', 'unknown_02')"
        ) { rs ->
            val id = rs.getInt(1)
            when (rs.getString(2)) {
                "unknown_02" -> hasOpcode2.add(id)
                "baseAnims" -> runCatching {
                    val o = JsonParser().parse(rs.getString(3)).asJsonObject
                    val w = o.get("walk")
                    if (w != null && w.isJsonPrimitive && w.asJsonPrimitive.isNumber) walkOf[id] = w.asInt
                }.onFailure {
                    // A malformed row must not decide the question either way; leaving it out
                    // of walkOf lands the group on opcode 2 or on NONE, both of which the
                    // caller can see in poseOf().
                    logger.warn { "animgroups_attr baseAnims for group $id did not parse: ${it.message}" }
                }
                else -> Unit
            }
        }
        val out = HashMap<Int, Pose>()
        for (id in walkOf.keys + hasOpcode2) {
            val w = walkOf[id]
            out[id] = when {
                w != null && w >= 0 -> Pose.WALK
                id in hasOpcode2 -> Pose.OPCODE2
                w != null -> Pose.NONE
                else -> Pose.OPCODE2      // opcode 2 present, no baseAnims row: still a walk-family anim
            }
        }
        out
    }

    private val memo = ConcurrentHashMap<Int, Pose>()

    /** The animation group [gameId] declares, or null when it declares none or is unknown. */
    fun groupOf(gameId: Int): Int? {
        if (!RsDatabase.available || !groupOfNpc.containsKey(gameId)) return null
        val g = groupOfNpc.get(gameId)
        return if (g == NO_GROUP_DECLARED) null else g
    }

    /** What the cache says about [gameId]'s walking pose. Memoised; no I/O after the first call. */
    fun poseOf(gameId: Int): Pose = memo.getOrPut(gameId) {
        if (!RsDatabase.available) return@getOrPut Pose.UNKNOWN
        if (!groupOfNpc.containsKey(gameId)) return@getOrPut Pose.UNKNOWN
        val group = groupOfNpc.get(gameId)
        if (group == NO_GROUP_DECLARED) return@getOrPut Pose.NO_GROUP
        poseOfGroup[group] ?: Pose.UNKNOWN
    }

    /**
     * May [gameId] be given a wander step?
     *
     * True whenever the gate is off, and otherwise exactly when the cache does
     * NOT positively say the client has no walking pose.
     */
    fun mayWander(gameId: Int): Boolean = !enabled || poseOf(gameId).walks

    /**
     * Resolves the pose of every id in [gameIds] now, so the SQLite reads happen
     * on whatever thread is populating the world rather than on the tick thread
     * (kit rule 5). Returns the number of ids that were not already memoised.
     */
    fun warm(gameIds: Collection<Int>): Int {
        // Realise the two lazy tables even for an EMPTY id list. Without this a world
        // with no spawns leaves the SQLite reads for whatever calls poseOf() first,
        // and that caller is the tick thread (kit rule 5).
        if (RsDatabase.available) { groupOfNpc.size; poseOfGroup.size }
        var cold = 0
        for (id in gameIds) if (memo[id] == null) { cold++; poseOf(id) }
        return cold
    }

    /** A per-pose census over [gameIds]. Diagnostics and checks only. */
    fun census(gameIds: Collection<Int>): Map<Pose, Int> {
        val out = LinkedHashMap<Pose, Int>()
        for (p in Pose.values()) out[p] = 0
        for (id in gameIds) out[poseOf(id)] = (out[poseOf(id)] ?: 0) + 1
        return out
    }

    /** Drops the memo. Tools only -- the live server resolves each id once and keeps it. */
    fun forget() = memo.clear()
}
