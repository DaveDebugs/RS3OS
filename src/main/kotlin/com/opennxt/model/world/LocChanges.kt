package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.net.game.serverprot.LocAddChange
import com.opennxt.net.game.serverprot.LocDel
import com.opennxt.net.game.serverprot.UpdateZonePartialFollows
import com.opennxt.net.game.serverprot.ZoneCoord
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * Dynamic scenery: the server changing a loc, and every client in range being
 * told.
 *
 * The archetype is a door that opens when it is clicked, and that is what
 * [com.opennxt.net.game.handlers.OpLocHandler] drives. Nothing here is
 * door-specific though - it is "replace the loc of shape S on tile T with loc
 * id N", which is exactly what `LOC_ADD_CHANGE` (62) says.
 */
object LocChanges {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.locChanges") != "false"

    // ============================================================
    // THE FLAG STATE, SAID OUT LOUD
    // ============================================================

    /**
     * One line naming every switch this file and [DoorSwing] answer to, in the
     * `property=VALUE` form [com.opennxt.model.combat.PlayerCombat.flagStateLines]
     * uses.
     */
    fun swingStateLine(): String =
        "doors.flags[state]: opennxt.experiment.locChanges=${if (enabled) "ON" else "OFF"} " +
            "opennxt.experiment.doors.swing=${DoorSwing.mode.id.uppercase()} " +
            "(${DoorSwing.mode.why}). Every door opened by this JVM names its mode on the OPLOC " +
            "line, as swing[<MODE>] with MODE upper-cased."

    /**
     * Prints [swingStateLine] exactly once per JVM.
     *
     * NOT at boot, and this says so rather than implying otherwise: nothing
     * touches this object until a player exists. [syncFor] runs once per player
     * per tick, so in practice the line lands at the first tick after the first
     * LOGIN - well before any door can be clicked, and in the same log. A
     * headless boot that never accepts a login will not print it, which is why
     * the per-open `swing[...]` token on the OPLOC line is the primary witness
     * and this is the corroborating one.
     */
    fun logSwingStateOnce() {
        if (swingStateLogged) return
        synchronized(this) {
            if (swingStateLogged) return
            swingStateLogged = true
            logger.info { swingStateLine() }
        }
    }

    @Volatile
    private var swingStateLogged = false

    /** Test seam: re-arms [logSwingStateOnce]. The server never calls this. */
    internal fun rearmSwingState() {
        swingStateLogged = false
    }

    /**
     * A loc this server has changed: which shape on which tile now shows which
     * id.
     *
     * The key is `(plane, x, y, shape)` and not `(plane, x, y)` because a tile
     * can carry one loc per shape and the client's own replace is keyed the
     * same way - `LOC_DEL` carries a shape and no id precisely because
     * `(tile, shape)` identifies the loc.
     */
    data class Key(val plane: Int, val x: Int, val y: Int, val shape: Int)

    /** What a changed loc now is. [originalId] is kept so a change can be undone. */
    data class Change(
        val key: Key,
        val originalId: Int,
        val currentId: Int,
        val rotation: Int,
        val forcedSwingMode: DoorSwing.Mode? = null,
        /**
         * Send `LOC_DEL` (opcode 1) instead of `LOC_ADD_CHANGE` (62), i.e. take the loc OUT of
         * the scene rather than restate it.
         */
        val removal: Boolean = false,
        /**
         * Send this change to a player standing on a DIFFERENT plane.
         *
         * Default false, so nothing that existed before this field changed: a door is on the
         * player's own floor and is dropped for anyone who is not.
         *
         * A tree's canopy is not. It is a loc on plane+1 above a trunk on plane 0, and the reference client
         * sends its `LOC_DEL` to a player standing on the ground - every canopy frame
         * is wrapped in an `UPDATE_ZONE_PARTIAL_ENCLOSED` whose `level` byte is
         * 0xff (`u128byte`, so level 1) while the trunk's is 0x00, in the same tick, to a player
         * who never left the ground. `UPDATE_ZONE_PARTIAL_FOLLOWS` carries the level itself
         * ([UpdateZonePartialFollows]), so the frame is unambiguous about which floor it means.
         */
        val crossPlane: Boolean = false
    ) {

        /**
         * WHERE the open leaf stands, resolved ONCE at the moment the change is
         * recorded.
         */
        val swing: DoorSwing.Placement = if (forcedSwingMode != null) {
            DoorSwing.Placement(key.x, key.y,
                when (forcedSwingMode) {
                    DoorSwing.Mode.TURN -> (rotation + 1) and 3
                    DoorSwing.Mode.TURN_BACK -> (rotation + 3) and 3
                    else -> rotation
                }, forcedSwingMode)
        } else {
            DoorSwing.placementFor(key.shape, key.x, key.y, rotation)
        }

        /** True when [swing] actually put the leaf somewhere else. */
        val swingMoved: Boolean
            get() = swing.x != key.x || swing.y != key.y || swing.rotation != rotation

        /**
         * The one token that says which mode produced [swing], for the log.
         *
         * UPPERCASE deliberately, and never the words true/false: the operator
         * launches with `-Dopennxt.experiment.doors.swing=left` in lower case,
         * so `swing[LEFT]` cannot be matched by a command line echoed into the
         * same log. That is the trap [com.opennxt.model.combat.PlayerCombat]'s
 * flag block already dodges, and this server has been bitten by a
         * pattern matching the wrong text four times.
         */
        val swingToken: String
            get() = "swing[${swing.mode.id.uppercase()}]"
    }

    private val changes = LinkedHashMap<Key, Change>()

    /** Every loc change currently in force, world-wide. */
    fun all(): List<Change> = synchronized(changes) { changes.values.toList() }

    fun changeAt(plane: Int, x: Int, y: Int, shape: Int): Change? =
        synchronized(changes) { changes[Key(plane, x, y, shape)] }

    /** Test seam: drops every recorded change. Only checks need this. */
    internal fun clear() {
        synchronized(changes) { changes.clear() }
        sent.clear()
    }

    // ============================================================
    // THE OPENED VARIANT
    // ============================================================

    /**
     * Which of the two measured rules named a loc's opened variant.
     *
     * The distinction is not cosmetic: [OFFSET] pairs a loc with its immediate
     * id neighbour, [TWIN] pairs it with a loc that can be anywhere in the same
     * cache group and is selected by name AND an identical model list. A caller
     * that wants to know how much to trust a pairing wants to know which one
     * produced it, and the log says so.
     */
    enum class OpenVariantRule { OFFSET, TWIN }

    /**
     * A resolved shut -> open pairing, with everything the caller needs to say
     * how it was reached and what it will and will not do on screen.
     *
     * [candidates] is how many locs the rule accepted before the nearest-id
     * tie-break picked one; 1 means no choice was made. [modelsIdentical] is
     * true when the two locs list the SAME models, i.e. the id swap alone
     * changes the right-click menu and nothing visible - see [openVariant].
     */
    data class OpenVariant(
        val shutId: Int,
        val openId: Int,
        val rule: OpenVariantRule,
        val candidates: Int,
        val modelsIdentical: Boolean
    )

    /**
     * The loc id a shut [locId] becomes when it opens, or null if the cache
     * does not say.
     */
    fun openVariant(locId: Int): OpenVariant? {
        if (!RsDatabase.available) return null
        return variantMemo.getOrPut(locId) { resolveOpenVariant(locId) ?: UNRESOLVED }
            .takeIf { it !== UNRESOLVED }
    }

    /** [openVariant]'s id alone, for callers that only need the number. */
    fun openVariantOf(locId: Int): Int? = openVariant(locId)?.openId

    private fun resolveOpenVariant(locId: Int): OpenVariant? {
        val shut = SqliteLocCodec.load(locId) ?: return null
        if (shut.actions.none { it == OPEN }) return null

        // Rule 1, unchanged: the immediate id neighbour, same name, declares Close.
        val offsetId = locId + openOffset
        val offsetDef = SqliteLocCodec.load(offsetId)
        if (offsetDef != null && offsetDef.name == shut.name && offsetDef.actions.any { it == CLOSE }) {
            return OpenVariant(locId, offsetId, OpenVariantRule.OFFSET, 1, modelsOf(locId) == modelsOf(offsetId))
        }

        // Rule 2: the same-name, same-models, same-group, never-placed twin.
        val shutKey = twinKey(locId) ?: return null
        if (shutKey.name == null || shutKey.models == null) return null
        val candidates = closeTwins[shutKey]
            ?.filter { it != locId && it !in placedLocIds }
            // The SQL that built the index prefilters on actions_0/locs_attr; the
            // authoritative answer to "does this declare Close" is the codec, which
            // merges both. Asked here so the index can never widen the rule.
            ?.filter { SqliteLocCodec.load(it)?.actions?.any { a -> a == CLOSE } == true }
            ?: return null
        if (candidates.isEmpty()) return null
        val chosen = candidates.minWithOrNull(
            compareBy({ Math.abs(it - locId) }, { if (it > locId) 0 else 1 }, { it })
        ) ?: return null
        return OpenVariant(locId, chosen, OpenVariantRule.TWIN, candidates.size, true)
    }

    /** The `models` attribute exactly as stored, or null when the loc has none. */
    private fun modelsOf(locId: Int): String? =
        RsDatabase.queryOne("SELECT value FROM locs_attr WHERE id = ? AND field = 'models'", locId) {
            it.getString(1)
        }

    /** The key TWIN pairs on: same name, same cache group, same models. */
    private data class TwinKey(val name: String?, val group: Int, val models: String?)

    private fun twinKey(locId: Int): TwinKey? = RsDatabase.queryOne(
        "SELECT l.name AS name, l._group AS grp, " +
            "(SELECT value FROM locs_attr WHERE id = l.id AND field = 'models') AS models " +
            "FROM locs l WHERE l.id = ?", locId
    ) { TwinKey(it.getString("name"), it.getInt("grp"), it.getString("models")) }

    /**
     * Every loc id `map_loc` places at least once.
     *
     * TWIN requires its target to be absent from this set, because an open-door
     * loc that the map itself places is a door someone built open, not a swap
     * target. 88,676 of the 139,587 locs are placed; one `SELECT DISTINCT` over
     * the 6,867,629-row table builds it in well under a second, which is why
     * this is a set rather than a `NOT EXISTS` per lookup.
     */
    private val placedLocIds: Set<Int> by lazy {
        if (!RsDatabase.available) emptySet()
        else HashSet(RsDatabase.queryAll("SELECT DISTINCT loc_id FROM map_loc") { it.getInt(1) })
    }

    /**
     * Close-declaring locs indexed by [TwinKey].
     *
     * The SQL is a PREFILTER, not the rule: it selects the 698 locs whose
     * `actions_0` or whose `locs_attr` action slot is "Close", and
     * [resolveOpenVariant] re-asks [SqliteLocCodec] before believing any of
     * them. Built once - 698 rows - so a lookup is a hash probe rather than the
     * 86 ms self-join the same question costs in SQL.
     */
    private val closeTwins: Map<TwinKey, List<Int>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else RsDatabase.queryAll(
            "SELECT l.id AS id, l.name AS name, l._group AS grp, " +
                "(SELECT value FROM locs_attr WHERE id = l.id AND field = 'models') AS models " +
                "FROM locs l WHERE l.id IN (" +
                "SELECT id FROM locs WHERE actions_0 = 'Close' " +
                "UNION SELECT id FROM locs_attr WHERE field LIKE 'actions_%' AND value = '\"Close\"')"
        ) { TwinKey(it.getString("name"), it.getInt("grp"), it.getString("models")) to it.getInt("id") }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.sorted() }
    }

    /**
     * Every loc whose merged action list contains [action].
     *
     * Exists so a check can walk the whole population through the real
     * [openVariant] instead of a re-derivation of the same SQL. The `LIKE` is
     * anchored at `actions` so `members_action_*` never reaches here.
     */
    fun locsDeclaring(action: String): List<Int> {
        if (!RsDatabase.available) return emptyList()
        val quoted = "\"$action\""
        return RsDatabase.queryAll(
            "SELECT id FROM locs WHERE actions_0 = '$action' " +
                "UNION SELECT id FROM locs_attr WHERE field LIKE 'actions_%' AND value = '$quoted' " +
                "ORDER BY id"
        ) { it.getInt(1) }
    }

    /** Test seam: forgets every memoised pairing. Only checks need this. */
    internal fun clearVariantMemo() = variantMemo.clear()

    private val UNRESOLVED = OpenVariant(-1, -1, OpenVariantRule.OFFSET, 0, false)
    private val variantMemo = java.util.concurrent.ConcurrentHashMap<Int, OpenVariant>()

    const val OPEN = "Open"
    const val CLOSE = "Close"

    val openOffset: Int = System.getProperty("opennxt.locChanges.openOffset")?.toIntOrNull() ?: 1

    // ============================================================
    // CHANGING A LOC
    // ============================================================

    /**
     * Records that the loc of [shape] on ([x], [y], [plane]) is now [newId],
     * and tells every player who can see it.
     *
     * Returns the [Change], or null when [enabled] is off or the protocol table
     * has no opcode - so a caller can say "nothing was sent" without guessing.
     *
     * The change is recorded BEFORE it is transmitted, so a player whose scene
     * rebuilds on the same tick picks it up through [syncFor] rather than
     * missing it.
     */
    fun change(
        plane: Int, x: Int, y: Int, shape: Int, rotation: Int, originalId: Int, newId: Int,
        forcedSwingMode: DoorSwing.Mode? = null
    ): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        logSwingStateOnce()
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes[key] }
        val change = Change(key, existing?.originalId ?: originalId, newId, rotation, forcedSwingMode)
        synchronized(changes) { changes[key] = change }

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return change
        world.forEachPlayer { player -> sendTo(player, change) }
        return change
    }

    /**
     * Takes the loc of [shape] on ([x], [y], [plane]) OUT of the scene with `LOC_DEL`, and
     * records it so [revert] can put it back.
     */
    fun remove(
        plane: Int, x: Int, y: Int, shape: Int, rotation: Int, originalId: Int,
        crossPlane: Boolean = false
    ): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        if (!removalOpcodeAvailable()) return null
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes[key] }
        val change = Change(
            key, existing?.originalId ?: originalId, REMOVED, rotation,
            forcedSwingMode = null, removal = true, crossPlane = crossPlane
        )
        synchronized(changes) { changes[key] = change }

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return change
        world.forEachPlayer { player -> sendTo(player, change) }
        return change
    }

    const val REMOVED = -1

    /** Whether this build's protocol table has `LOC_DEL`. Separate from [opcodesAvailable] so a
     *  build without it loses removals only, and doors keep working. */
    private fun removalOpcodeAvailable(): Boolean {
        if (OpenNXT.protocol.serverProtNames.values["LOC_DEL"] != null) return true
        if (!locDelWarned) {
            locDelWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for LOC_DEL - no loc can be " +
                    "removed from the scene, so a chopped tree keeps its canopy. (Warned once.)"
            }
        }
        return false
    }

    @Volatile
    private var locDelWarned = false

    /**
     * Puts the loc of [shape] on ([x], [y], [plane]) back to the id it had before anything changed
     * it, and FORGETS the change.
     */
    fun revert(plane: Int, x: Int, y: Int, shape: Int): Change? {
        if (!enabled) return null
        if (!opcodesAvailable()) return null
        val key = Key(plane, x, y, shape)
        val existing = synchronized(changes) { changes.remove(key) } ?: return null
        // Send the ORIGINAL id back with the original rotation, as a change in its own right, so
        // every client in range redraws it now rather than at their next rebuild.
        // `removal = false` deliberately and always: a restore is an ADD, whatever took the loc
        // away. That is what the reference client does too - the canopy comes back as LOC_ADD_CHANGE on the
 // respawn tick, in the same frame pair as the trunk.
        // `crossPlane` is carried forward, because a loc that had to be sent to a player on
        // another floor has to be put back the same way.
        val restored = Change(
            key, existing.originalId, existing.originalId, existing.rotation, existing.forcedSwingMode,
            removal = false, crossPlane = existing.crossPlane
        )
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return restored
        world.forEachPlayer { player -> sendTo(player, restored) }
        return restored
    }

    /**
     * The other leaf of a double door, if this placement is half of one.
     */
    fun doubleDoorPartner(
        plane: Int, x: Int, y: Int, shape: Int, locId: Int, rotation: Int
    ): Triple<Int, Int, Int>? {
 //. This guard was dropped by the rewrite that replaced
        // the `loc_id <> $locId ... LIMIT 1` query with the all-hits loop below,
        // and dropping it is a live defect rather than a tidy-up.
        //
        // WITHOUT IT the rule stops being about doors. The SQL filters on
        // `type = $shape`, so calling this with shape 10 asks "is there another
        // type-10 placement of a different id with the same name and rotation
        // next door" - and there are 36,108 such adjacencies in this cache.
        // [com.opennxt.net.game.handlers.OpLocHandler] calls this for ANY loc whose
        // clicked option is "Open", passing the placement's real shape, so a chest
        // or a cupboard reaches here with shape 10. EIGHT of those adjacencies
        // are between two Open-DECLARING type-10 locs - four distinct pairs, each
        // counted from both ends; it read "Ten" until the re-derivation of
 // - e.g. 'Ardougne wall door' 9738 at
        // (2558,3299) beside 9330 at (2558,3300): opening one would have opened the
        // other's collision and sent it a LOC_ADD_CHANGE.
        //
 // 3.3 exists to assert exactly this
        // and could not see it - its fixture passed shape 10 at a tile with no
        // type-10 neighbour at all, so it read null for want of a candidate rather
        // than because of this line. 3.3b now uses the Ardougne pair, where the
        // guard is the only thing saying no.
        if (shape !in WALL_SHAPES) return null
        val def = SqliteLocCodec.load(locId) ?: return null
        val name = def.name ?: return null
        if (name.isEmpty()) return null

        for ((dx, dy) in NEIGHBOURS) {
            val nx = x + dx
            val ny = y + dy
            
            val squareId = ((ny / 64) shl 7) or (nx / 64)
            val sql = "SELECT loc_id, type, rot FROM map_loc WHERE square_id = $squareId " +
                "AND x = ${nx % 64} AND y = ${ny % 64} AND plane = $plane " +
                "AND type = $shape AND rot = $rotation"
                
            val hits = RsDatabase.queryAll(sql) { it.getInt("loc_id") }

            for (hitLocId in hits) {
                if (hitLocId == locId) continue // A fence run, not a pair
                val hDef = SqliteLocCodec.load(hitLocId) ?: continue
                if (hDef.name == name) return Triple(nx, ny, hitLocId)
            }
        }
        return null
    }

    /** The four orthogonal neighbours a partner leaf can occupy. */
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** Wall shapes, the same set [com.opennxt.content.impl.Doors.WALL_TYPES] uses. */
    val WALL_SHAPES = setOf(0, 1, 2, 3, 9)

    /**
     * Re-sends every recorded change [player] can see, if their scene has been
     * rebuilt since the last time.
     */
    fun syncFor(player: WorldPlayer) {
        if (!enabled) return
        if (!opcodesAvailable()) return
        logSwingStateOnce()
        val epoch = player.viewport.sceneEpoch
        val last = sent[player]
        if (last == epoch) return
        sent[player] = epoch
        val list = all()
        if (list.isEmpty()) return
        var n = 0
        for (change in list) if (sendTo(player, change)) n++
        if (n > 0) logger.info { "Re-sent $n loc change(s) to ${player.name} after scene epoch $epoch" }
    }

    /** Returns whether the change was actually in [player]'s scene and sent. */
    private fun sendTo(player: WorldPlayer, change: Change): Boolean {
        val key = change.key
        val v = player.viewport
        // `crossPlane` defaults false, so a door is still dropped for a player on another floor -
        // byte-for-byte the behaviour this line has always had. A tree's canopy sets it, because
        // it lives one plane above the trunk and the reference client sends it to a player on the ground
        // (see Change.crossPlane).
        if (!change.crossPlane && player.entity.location.plane != key.plane) return false
        if (!v.containsTile(key.x, key.y)) return false
        return try {
            // The open leaf's tile and rotation, resolved once when the change
            // was RECORDED (see Change.swing) rather than recomputed here, so
            // that the OPLOC line and the wire cannot disagree about the mode.
            // Under the default DoorSwing.Mode.OFF this is the shut placement
            // unchanged, so the call is a no-op until an operator sets
            // -Dopennxt.experiment.doors.swing. See DoorSwing for what is
            // measured (that a transform exists), what is not (its sign), and
            // the unadjudicated dispute over which direction it goes.
            val open = change.swing
            // A moved tile must be in the viewport too, and its OWN zone is what
            // UPDATE_ZONE_PARTIAL_FOLLOWS has to point at - sending the shut
            // tile's zone with a moved coord would place the leaf in the wrong
            // zone whenever the move crosses an 8x8 boundary.
            //
            // THIS USED TO BE A SILENT `return false`. A door clicked at the far
            // edge of the viewport under a tile-moving mode then sent nothing at
            // all, printed nothing at all, and looked exactly like "the swing is
 // not wired up" - which is the reading the run reached.
            // Whatever else was going on that day, a drop this path can take must
            // never again be indistinguishable from a missing call.
            if (open.mode != DoorSwing.Mode.OFF && !v.containsTile(open.x, open.y)) {
                logger.warn {
                    "door swing [${open.mode.id}] DROPPED for ${player.name}: the shut tile " +
                        "(${key.x},${key.y}) is in view but the leaf's tile (${open.x},${open.y}) is " +
                        "NOT, so LOC_ADD_CHANGE was not sent and this door did not open on their " +
                        "screen. Nothing is wrong with the mode; the player is at the edge of the " +
                        "scene. Re-run with -Dopennxt.experiment.doors.swing=off to see the door " +
                        "open without the transform."
                }
                return false
            }
            player.client.write(UpdateZonePartialFollows(key.plane, v.zoneX(open.x), v.zoneY(open.y)))
            if (change.removal) {
                // Opcode 1. No id on the wire - `(tile, shape)` identifies the loc, which is why
                // the shape here has to be the shape the loc was PLACED with and not the shape of
                // whatever is replacing it. The reference client's own field order is coord first (LocDel.kt).
                player.client.write(
                    LocDel(
                        coord = ZoneCoord.coord(open.x, open.y),
                        shapeRotation = ZoneCoord.shapeRotation(key.shape, open.rotation)
                    )
                )
            } else {
                player.client.write(
                    LocAddChange(
                        shapeRotation = ZoneCoord.shapeRotation(key.shape, open.rotation),
                        loc = change.currentId,
                        coord = ZoneCoord.coord(open.x, open.y)
                    )
                )
            }
            if (open.mode != DoorSwing.Mode.OFF) logger.info {
                "door swing [${open.mode.id}]: loc ${change.currentId} placed at " +
                    "(${open.x},${open.y}) rot ${open.rotation} instead of (${key.x},${key.y}) " +
                    "rot ${change.rotation} for ${player.name}. ${DoorSwing.PROVENANCE}"
            }
            true
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "LOC_ADD_CHANGE failed for ${player.name} at $key; run with " +
                        "-Dopennxt.experiment.locChanges=false to take it out of the picture. (Warned once.)"
                }
            }
            false
        }
    }

    private fun opcodesAvailable(): Boolean {
        val names = OpenNXT.protocol.serverProtNames.values
        val missing = listOf("UPDATE_ZONE_PARTIAL_FOLLOWS", "LOC_ADD_CHANGE").filter { names[it] == null }
        if (missing.isEmpty()) return true
        if (!unmappedWarned) {
            unmappedWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for ${missing.joinToString(", ")} - " +
                    "no loc change can be transmitted, so doors will not open on any client. (Warned once.)"
            }
        }
        return false
    }

    /** Last scene epoch each player was brought up to date at. */
    private val sent: MutableMap<WorldPlayer, Int> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Int>())

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false
}
