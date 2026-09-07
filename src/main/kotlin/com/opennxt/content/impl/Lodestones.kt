package com.opennxt.content.impl

import com.opennxt.Constants
import com.opennxt.model.vars.SqliteVarBits
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.generated.SynthSound
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.file.Files

/**
 * THE LODESTONE NETWORK: why every lodestone in this server says only "Info", and what
 * activating one actually is on the wire.
 */
object Lodestones {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.lodestones") != "false"

    /**
     * Ticks from the OPLOC click to the varbit write. once (t1269 -> t1276,
     * cannot separate a fixed delay from the walk the player was doing at the time.
     */
    val DELAY_TICKS: Int
        get() = System.getProperty("opennxt.lodestone.ticks")?.toIntOrNull()?.takeIf { it in 0..100 } ?: 7

    /**
     * The activation sound. `SYNTH_SOUND {sound:14278, count:1, delay:0,
     * volume:127, extra:256}` and an identical VORBIS_SOUND on the same tick as the bit.
     * Only the SYNTH form is sent here - this repository has a `SynthSound` codec and no
     * `VorbisSound` sender on the content path, and sending one of a measured pair is a
     * smaller lie than inventing a second packet class.
     */
    const val SOUND = 14278
    const val SOUND_VOLUME = 127
    const val SOUND_EXTRA = 256

    /**
     * The row-1 action strings a lodestone morph can carry. DERIVED, not typed: the
     * check re-runs the SQL that produced it and fails if the cache disagrees. 'Info'
     * is deliberately NOT here - it is row 5 of the already-activated branch and is a
     * read-only option.
     */
    val ACTIONS: Set<String> = setOf("Activate", "Power-up", "Charge", "Unlock", "Build")

    /**
     * One lodestone, as the cache states it.
     *
     * @param parentLocId the loc `map_loc` places and the client clicks
     * @param varbit the varbit `morphs_1` branches on
     * @param activateLocId `options[0]` - the branch shown while the bit is 0
     * @param activatedLocId `default` - the branch shown for every other value
     * @param action row 1 of [activateLocId]; the option string a click must carry
     * @param name the branch's loc name, e.g. "Varrock lodestone"
     */
    data class Lodestone(
        val parentLocId: Int,
        val varbit: Int,
        val activateLocId: Int,
        val activatedLocId: Int,
        val action: String,
        val name: String
    )

    /**
     * Every lodestone in the cache, by the loc id the client actually clicks.
     *
     * The SQL asks for morph rows whose branches are ALL named `% lodestone`, which is
     * what makes this a derivation and not a list: adding a lodestone to the cache adds it
     * here, and a loc that merely has "lodestone" in its name (the 137-strong decorative
     * family - 'Inactive lodestone', 'Red lodestone', ...) is excluded because its parent
     * has no morph row at all.
     */
    val lodestones: Map<Int, Lodestone> by lazy { load() }

    /** Lodestones whose activation this server can express: those whose varbit is well formed. */
    val usable: List<Lodestone> by lazy {
        lodestones.values.filter { SqliteVarBits.definition(it.varbit)?.isWellFormed == true }
    }

    /** Every loc whose name ends " lodestone", by id, with its row-1 action. The population §3 counts. */
    val namedLodestoneLocs: Map<Int, Pair<String, String?>> by lazy {
        if (!RsDatabase.available) emptyMap()
        else RsDatabase.queryAll(
            "SELECT id, name, actions_0 FROM locs WHERE lower(name) LIKE '% lodestone'"
        ) { rs -> rs.getInt("id") to (rs.getString("name") to rs.getString("actions_0")) }.toMap()
    }

    // =====================================================================================
    // THE WORLD-MAP TELEPORT: which confirm-dialog slot is which lodestone
    // =====================================================================================
    //
    // observation in the corpus), twice:
    //
    // ```
    //   t165  c2s IF_BUTTON1 1465:11                       (the minimap's "Open World Map")
    //   t166  s2c the map opens; among it IF_OPENSUB 1612 -> 1422:76 and
    //             IF_SETEVENTS 1612:11 [1..35] mask 2
    //   t176  c2s IF_BUTTON1 1612:11 arg2=14               <- THE LODESTONE CHOICE
    //   t177  s2c IF_CLOSESUB x4 + IF_OPENSUB 1482 -> 1477:31   (the map closes, 3D view back)
    //   t196  s2c REBUILD_NORMAL chunk(401,422) + CAM_RESET + MINIMAP_TOGGLE + MIDI_SONG
    // ```
    //
    // and again t380 / t395 (`arg2=12`) / t396 / t415 chunk(336,435).
    //
    // **The delay is 20 ticks, 2 of 2.** Not by counting tick indices - by the clock:
    // 12,215 ms and 12,156 ms from the client's send to the rebuild, i.e. 20.36 and 20.26
 // ticks, so 20 server ticks plus the round trip. Tier . [TELEPORT_TICKS].
    //
    // **The slot.** 1612 is `worldmap_v2_confirm`, a generic confirmation dialog, and
    // clientscript **11281** builds dynamic children 0..N on 1612:11 and puts the "Select" op
    // on the LAST one - so `arg2` is that N, and the wire slots are 1-based because the server
    // arms `[1..35]`. The ORDER is the client's own and is in no table in `rs3.sqlite`: enum
    // 5726 holds the same 33 destinations in a different order (it leads with Al Kharid; the
    // list leads with Bandit Camp and Lunar Isle), so reading the enum's row order would be
    // wrong by two for most rows and by fourteen for the first two.
    //
    // It is therefore DERIVED OFFLINE, by `tools/949/lodestone_slots.py`, from clientscript
    // **14999** - the world map's lodestone icon builder, identified by its own string pool
    // ("Click to teleport to<br>this lodestone.<br>", "This lodestone is not yet active.") -
    // which pushes each destination's packed coordinate in list order. The seed is
    // `data/seed/lodestone_slots.tsv` and it is not an authored list: re-run the tool and it
    // regenerates.
    //
    // **Three independent controls, all of which fire:**
    //  1. every one of the 33 coords is a key of enum 5726, and the name is the enum's;
    //  2. **24 of the 33** are exactly one tile north-east of a placed lodestone morph parent
    //     in `map_loc`, same plane (the 25th, Menaphos, agrees in x and y and differs in
    //     plane) - two unrelated cache sources agreeing to an object-anchor offset;
    //  3. slot 12 -> (2689,3483) -> chunk **(336,435)** and slot 14 -> (3214,3377) -> chunk
    // **(401,422)** are exactly the two `REBUILD_NORMAL` chunks the protocol carries.
    //
    // Note what control 3 corrects: the working hypothesis when this was handed over read
    // `arg2=12` as **Taverley**. It is **Seers' Village** - Taverley is slot 13. The
    // alphabetical-order lead died with it (Seers' 19th and Varrock 22nd alphabetically
    // against slots 12 and 14 is not a constant offset).

    /**
     * One row of `data/seed/lodestone_slots.tsv`.
     *
     * @param slot the 1-based index the client sends in `IF_BUTTON1 1612:11 arg2`
     * @param coord the packed `(plane shl 28) or (x shl 14) or y` the clientscript pushes
     * @param name enum 5726's own name for that coord
     * @param varbit the lodestone morph's varbit, or -1 when this destination has no morph
     *   parent in this cache (Bandit Camp, Lunar Isle, Fort Forinthry and the five event ones)
     * @param locId the morph parent, or -1
     */
    data class Slot(
        val slot: Int,
        val coord: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        val name: String,
        val varbit: Int,
        val locId: Int
    ) {
        val destination: TileLocation get() = TileLocation(x, y, plane)
    }

    /** `data/seed/lodestone_slots.tsv`, written by `tools/949/lodestone_slots.py`. */
    val slotSeedPath: java.nio.file.Path = Constants.DATA_PATH.resolve("seed").resolve("lodestone_slots.tsv")

    /**
     * The confirm dialog's slot -> destination table, by wire slot.
     *
     * Empty when the seed is missing, which is a REFUSAL and not a fallback: an invented
     * ordering here would teleport a player to the wrong continent, and one wrong row is
     * indistinguishable from a working table until somebody clicks it.
     */
    val slots: Map<Int, Slot> by lazy { loadSlots() }

    private fun loadSlots(): Map<Int, Slot> {
        if (!Files.exists(slotSeedPath)) {
            logger.warn {
                "lodestones: $slotSeedPath is absent - the world map's lodestone teleport is " +
                    "DISABLED. Regenerate it with `python tools/949/lodestone_slots.py`; nothing " +
                    "here will guess an ordering."
            }
            return emptyMap()
        }
        val out = LinkedHashMap<Int, Slot>()
        runCatching {
            Files.readAllLines(slotSeedPath).forEach { line ->
                if (line.startsWith("#") || line.startsWith("slot\t") || line.isBlank()) return@forEach
                val f = line.split('\t')
                if (f.size < 8) return@forEach
                val s = Slot(
                    slot = f[0].toInt(), coord = f[1].toInt(), x = f[2].toInt(), y = f[3].toInt(),
                    plane = f[4].toInt(), name = f[5], varbit = f[6].toInt(), locId = f[7].toInt()
                )
                out[s.slot] = s
            }
        }.onFailure {
            logger.error(it) { "lodestones: $slotSeedPath is unreadable - refusing to substitute defaults" }
            return emptyMap()
        }
        return out
    }

    /** The destination for a `1612:11` slot, or null when this build has no row for it. */
    fun slotFor(slot: Int): Slot? = slots[slot]

    /**
     * Ticks from the `1612:11` click to `REBUILD_NORMAL`.
     *
     *across two sessions and three different destinations:
     */
    val TELEPORT_TICKS: Int
        get() = System.getProperty("opennxt.lodestone.teleport.ticks")?.toIntOrNull()
            ?.takeIf { it in 0..200 } ?: 20

    /**
     * Whether a teleport to a lodestone this player has not activated is refused.
     *
     * **A DECISION, not a measurement, and it defaults to OFF.** Clientscript 14999 gates the
     * icon client-side ("This lodestone is not yet active."), so the reference client's client never sends the
     * click at all and no observation can say what the reference client's SERVER does with one. Refusing by default
     * would make the feature unusable on this server, whose fresh login activates only Burthorpe
     * ([lodestoneStateOf] over `DefaultVariables`'s varp 3). `-Dopennxt.lodestone.teleport
     * .requireactive=true` turns the server-authoritative check on; the state is logged either way.
     */
    val requireActivated: Boolean
        get() = System.getProperty("opennxt.lodestone.teleport.requireactive") == "true"

    @Volatile
    var teleportsAccepted: Int = 0
        private set

    @Volatile
    var teleportsRefusedUnknownSlot: Int = 0
        private set

    @Volatile
    var teleportsRefusedInactive: Int = 0
        private set

    internal fun resetTeleportCounters() {
        teleportsAccepted = 0; teleportsRefusedUnknownSlot = 0; teleportsRefusedInactive = 0
    }

    /**
     * One click on the world map's confirm dialog, `IF_BUTTON1 1612:11 arg2=<slot>`.
     *
     * Returns true when the click was consumed - which includes every refusal, because a
     * lodestone slot this build cannot serve must not fall through to the generic
     * "unrouted click" log as though nothing owned it.
     *
     * ## The nine questions
     *  1. **this tick** - the destination is resolved and [Teleports.schedule] arms it; the map
     * is closed by the caller ([WorldMapWindow.handleButton]), which is what the reference client does on
     *     the tick after the click (t177, t396).
     *  2. **next tick** - [Teleports.tick] counts down and the teleport lands on
     *     click + [TELEPORT_TICKS].
     * 3. **the actor moves** - allowed, exactly as the reference client's 20 ticks allow it; the teleport
     *     cancels the walk when it fires (`Movement.teleport` empties the queue).
     *  4. **mid-action** - [Teleports.schedule] installs nothing; the action is cancelled at the
     *     moment the teleport FIRES, by `Teleports.actionCanceller`, so a gather keeps paying for
     * the 20 ticks the player is standing there and then stops. That is the same shape the reference client
     *     shows (nothing in the 20-tick window is sent at all).
     *  5. **the actor dies** - not modelled; a death does not clear a pending teleport, and the
     * teleport will move the corpse's owner. Stated, not defended: no observation covers it.
     *  6. **logout / disconnect** - [Teleports.tick] drops a pending entry whose channel closed.
     *  7. **the request repeats** - [Teleports.schedule] REFUSES a second destination while one is
     *     armed and RE-ARMS the same one, so a double-click cannot queue two teleports.
     *  8. **persistence fails** - nothing new; the tile rides in `PlayerSave` as a walked one does.
     *  9. **two players, one destination** - both arrive; RS does not block player tiles.
     */
    fun handleConfirmClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val dest = slots[slot]
        if (dest == null) {
            teleportsRefusedUnknownSlot++
            logger.warn {
                "lodestone teleport: ${player.name} chose 1612:11 slot $slot, which this build has " +
                    "no row for (${slots.size} rows, 1..${slots.keys.maxOrNull() ?: 0}). REFUSED. " +
                    "The next observation needs: one IF_BUTTON1 1612:11 with THIS arg2 and the " +
                    "REBUILD_NORMAL chunk that follows it, which names the destination outright."
            }
            return true
        }
        val lode = if (dest.locId >= 0) lodestones[dest.locId] else null
        val active = lode != null && isActivated(player, lode)
        if (requireActivated && lode != null && !active) {
            teleportsRefusedInactive++
            logger.info {
                "lodestone teleport: ${player.name} chose ${dest.name} (slot $slot) but varbit " +
                    "${lode.varbit} is 0 - refused because opennxt.lodestone.teleport.requireactive=true."
            }
            return true
        }
        val armed = Teleports.schedule(
            player, dest.destination, TELEPORT_TICKS,
            "lodestone ${dest.name} (1612:11 slot $slot)"
        )
        if (armed) teleportsAccepted++
        logger.info {
            "lodestone teleport: ${player.name} chose ${dest.name} - slot $slot -> " +
                "(${dest.x},${dest.y},plane ${dest.plane}) = chunk(${dest.x / 8},${dest.y / 8}); " +
                "${if (armed) "armed for $TELEPORT_TICKS tick(s) (MEASURED 2/2)" else "REFUSED, a teleport is already armed"}" +
                "; the lodestone reads ${if (lode == null) "no morph parent in this cache" else if (active) "ACTIVATED" else "not activated"}."
        }
        return true
    }

    private fun load(): Map<Int, Lodestone> {
        if (!RsDatabase.available) return emptyMap()
        // The filtering is done here rather than in SQL on purpose: `morphs_1` is a JSON blob
        // and this repository's sqlite build is not guaranteed to carry the JSON1 extension, so a
        // json_extract() join would be a silent empty result on some machines - which reads
        val named = namedLodestoneLocs
        val rows = RsDatabase.queryAll(
            "SELECT id, value FROM locs_attr WHERE field = 'morphs_1'"
        ) { rs -> rs.getInt("id") to rs.getString("value") }
        val out = LinkedHashMap<Int, Lodestone>()
        for ((parent, morph) in rows) {
            if (morph == null) continue
            val varbit = jsonInt(morph, "varbit") ?: continue
            val opt = jsonFirstOption(morph) ?: continue
            val dflt = jsonInt(morph, "default") ?: continue
            // BOTH branches must be a place lodestone. One-sided would admit any morph that
            // happens to fall back on a lodestone, and there is no such row - but the check
            // asserts the two-sided count, so the rule has to be the one that produced it.
            val activate = named[opt] ?: continue
            named[dflt] ?: continue
            val name = activate.first
            val action = activate.second ?: continue
            if (action !in ACTIONS) {
                // Not a refusal to load - a signal. A lodestone whose row-1 string is not in
                // ACTIONS means the cache grew one this module cannot route, and saying so is
                // the difference between "no lodestones" and "one lodestone we do not know".
                logger.warn {
                    "lodestones: loc $parent -> $opt ('$name') declares row 1 '$action', which is " +
                        "not one of $ACTIONS. It is loaded, but a click carrying that string is the " +
                        "only thing that will reach it."
                }
            }
            out[parent] = Lodestone(parent, varbit, opt, dflt, action, name)
        }
        return out
    }

    // Minimal readers over the morph JSON. The blob is written by DbBuilder.morphJson and has
    // a fixed shape; a full parser would be a dependency for four integers.
    private fun jsonInt(blob: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(blob)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonFirstOption(blob: String): Int? =
        Regex("\"options\"\\s*:\\s*\\[\\s*(-?\\d+)").find(blob)?.groupValues?.get(1)?.toIntOrNull()

    /** True when [locId] is a loc the client can click and this module owns. */
    fun isLodestone(locId: Int): Boolean = enabled && lodestones.containsKey(locId)

    /**
     * The loc the CLIENT will be drawing for [locId], given this player's varps - i.e. which
     * of the two branches the morph resolves to. Null when [locId] is not a lodestone.
     *
     * This is the whole of the morph rule and it is the function the check exercises, because
     * getting it backwards is exactly the mistake that would make an activated lodestone offer
     * "Activate" for ever.
     */
    fun resolvedLocFor(player: WorldPlayer, locId: Int): Int? {
        val lode = lodestones[locId] ?: return null
        return if (isActivated(player, lode)) lode.activatedLocId else lode.activateLocId
    }

    /** Whether this player's varps say [lode] is already activated (its varbit is non-zero). */
    fun isActivated(player: WorldPlayer, lode: Lodestone): Boolean {
        val def = SqliteVarBits.definition(lode.varbit) ?: return false
        if (!def.isWellFormed) return false
        return def.read(player.varpValue(def.varId and 0xFFFF)) != 0
    }

    /**
     * Which lodestones this player's varp state says are activated, and which are not.
     * Pure over a varp reader so a check can ask it of a value rather than of a session -
     * this is what recomputes §1(b) instead of restating it.
     */
    fun lodestoneStateOf(varp: (Int) -> Int): Pair<List<Lodestone>, List<Lodestone>> {
        val on = ArrayList<Lodestone>()
        val off = ArrayList<Lodestone>()
        for (lode in usable) {
            val def = SqliteVarBits.definition(lode.varbit) ?: continue
            if (def.read(varp(def.varId and 0xFFFF)) != 0) on.add(lode) else off.add(lode)
        }
        return on to off
    }

    // ------------------------------------------------------------------ the pending activation

    /** A click that has been accepted and is waiting out [DELAY_TICKS]. */
    private data class Pending(val lode: Lodestone, var ticksLeft: Int)

    private val pending = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WorldPlayer, Pending>()
    )

    @Volatile
    var accepted: Int = 0
        private set

    @Volatile
    var activated: Int = 0
        private set

    @Volatile
    var refusedAlreadyActive: Int = 0
        private set

    internal fun resetCounters() {
        accepted = 0; activated = 0; refusedAlreadyActive = 0; pending.clear()
    }

    /** How many ticks [player] has left on a pending activation, or null if none is armed. */
    fun pendingTicks(player: WorldPlayer): Int? = pending[player]?.ticksLeft

    /**
     * Handle one OPLOC on a lodestone.
     *
     * `OpLocHandler` cannot route this on its own and that is not a bug in it: the client sends
     * the MORPH PARENT id (measured - loc 69839 above), and the parent carries no
     * `actions` at all, so `optionName(def, option)` is null and the click is dropped before any
     * dispatch. This function is the morph resolver that has to sit in front of that.
     *
     * Returns true when the click was consumed, so a caller's "nothing handled this" logging is
     * unchanged for every other loc - the same contract [LocWiring.routeOther] has.
     *
     * ## The nine questions
     *  1. **this tick** - the click is validated and a [Pending] is armed; nothing is sent.
     *  2. **next tick** - [tick] counts down; the varbit lands on tick + [DELAY_TICKS].
     * 3. **the actor moves** - deliberately allowed. The reference client's own 7 ticks contain a walk, and
     * refusing on movement would make the measured sequence unreproducible.
     *  4. **the target disappears** - a loc cannot; the morph parent is map data, not world state.
     *  5. **the actor dies** - the pending entry survives and fires; a lodestone unlock is not
     *     an item and there is nothing to duplicate. Stated rather than defended.
     *  6. **logout / disconnect** - the map is a `WeakHashMap` keyed on the `WorldPlayer`, and
     *     [tick] skips a player whose channel is closed, so a disconnected pending never writes.
     *  7. **the request repeats** - a second click while one is armed REPLACES the pending entry
     *     for the same lodestone (idempotent: the bit is already going to be set) and is refused
     *     outright for a different one, so a double-click can never arm two.
     *  8. **persistence fails** - the write goes through `setVarpOverride(store = true)`, which is
     *     the same path every other saved varp uses; a save failure is `PlayerSave`'s contract.
     *  9. **two actors, same tile, same tick** - a lodestone is per-player state. Two players
     *     activating the same lodestone in the same tick write their own varp overrides and
     *     never touch each other's.
     */
    fun handleLocClick(player: WorldPlayer, locId: Int, option: Int, x: Int, y: Int, plane: Int): Boolean {
        if (!enabled) return false
        val lode = lodestones[locId] ?: return false

        if (isActivated(player, lode)) {
            refusedAlreadyActive++
            logger.info {
                "lodestone: ${player.name} clicked ${lode.name} (parent $locId) at ($x,$y,plane $plane) " +
                    "option $option, but varbit ${lode.varbit} is already set - the client is drawing " +
                    "loc ${lode.activatedLocId}, whose only row is 'Info'. Nothing to do."
            }
            return false
        }

        val already = pending[player]
        if (already != null && already.lode.parentLocId != locId) {
            logger.info {
                "lodestone: ${player.name} clicked ${lode.name} while ${already.lode.name} was still " +
                    "${already.ticksLeft} tick(s) from activating - refused, one at a time."
            }
            return false
        }

        pending[player] = Pending(lode, DELAY_TICKS)
        accepted++
        logger.info {
            "lodestone: ${player.name} '${lode.action}' on ${lode.name} - morph parent $locId resolves " +
                "to loc ${lode.activateLocId} while varbit ${lode.varbit} is 0. Varbit lands in " +
                "${DELAY_TICKS} tick(s)."
        }
        return true
    }

    /**
     * One world tick of pending activations. Safe to call with no players.
     *
     * Returns how many activations completed on this tick, which is what the check counts.
     */
    @Volatile
    var ticksWithPending: Int = 0
        private set

    /** How many activations are armed right now, across all players. */
    fun pendingCount(): Int = synchronized(pending) { pending.size }

    fun tick(): Int {
        // BEFORE the enabled gate, deliberately. The teleport queue rides this phase (see the
        // block at the end of this function) and `-Dopennxt.experiment.lodestones=false` must
        // switch OFF lodestone activation, not silently freeze every ring teleport too.
        runCatching { Teleports.tick() }.onFailure {
            logger.error(it) { "lodestones: the teleport queue threw - contained; activations are unaffected" }
        }
        if (!enabled) return 0
        var done = 0
        if (pendingCount() > 0) {
            ticksWithPending++
            if (ticksWithPending <= 3 || ticksWithPending % 25 == 0) {
                logger.info { "lodestone: tick() sees ${pendingCount()} pending activation(s) " +
                    "(tick #$ticksWithPending with work); accepted=$accepted activated=$activated" }
            }
        }
        val ready = ArrayList<Pair<WorldPlayer, Pending>>()
        synchronized(pending) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (player, p) = it.next()
                if (!player.client.channel.isActive) { it.remove(); continue }
                if (--p.ticksLeft > 0) continue
                ready.add(player to p)
                it.remove()
            }
        }
        for ((player, p) in ready) {
            if (activate(player, p.lode)) done++
        }
        // THE TELEPORT PHASE RIDES THIS ONE, and the reason is written down rather than hidden.
        //
 // `Teleports` grew a pending queue on (the lodestone's measured 20-tick delay
        // and the ring's 5) and needs one call per world tick. `World.tick()` is owned by another
        // stream this session and could not be edited, so it is driven from here - the phase that
        // is already in the loop, immediately beside it, and whose own KDoc records what happened
        // the last time a content phase was wired into `World.tick()` carelessly (it landed inside
        // the previous phase's catch block and never ran).
        //
        // Contained separately and LAST so that a throw in the teleport queue cannot cost a
        // lodestone activation, which is the older and more load-bearing of the two.
        //
        // THIS IS A PIGGYBACK AND IT WANTS ITS OWN PHASE. When `World.tick()` gains
        // `com.opennxt.content.impl.Teleports.tick()` as a sibling of `Lodestones.tick()`, delete
        // the call at the top of this function - `Teleports.tick()` is idempotent within one world
        // tick ([Teleports.lastWorldTick]) so the changeover cannot double-count, but leaving both
        // in is still a lie about where the phase lives.
        return done
    }

    /**
     * Write the varbit and play the sound. Public so `::lodestone` and the check can drive it
     * without waiting out the delay.
     *
     * Returns false when the varbit is unusable (absent from this cache or malformed) - the
     * lodestone is then left alone rather than half-activated.
     */
    fun activate(player: WorldPlayer, lode: Lodestone): Boolean {
        val def = SqliteVarBits.definition(lode.varbit)
        if (def == null || !def.isWellFormed || !def.fits(1)) {
            logger.warn {
                "lodestone: ${lode.name} branches on varbit ${lode.varbit}, which this cache " +
                    "${if (def == null) "does not define" else "defines as $def"} - NOT activating."
            }
            return false
        }
        val varp = def.varId and 0xFFFF
        val before = player.varpValue(varp)
        val after = def.write(before, 1)
        player.setVarpOverride(varp, after)
        if (player.client.channel.isActive) {
            player.client.write(SynthSound(SOUND, 1, 0, SOUND_VOLUME, SOUND_EXTRA))
        }
        activated++
        logger.info {
            "lodestone: ${player.name} activated ${lode.name} - varbit ${lode.varbit} " +
                "(varp $varp bits ${def.bitStart}..${def.bitEnd}) $before -> $after, sound $SOUND. " +
                "The client will now morph loc ${lode.parentLocId} to ${lode.activatedLocId} " +
                "('Info' only), which is what the reference client does."
        }
        return true
    }

    /** The boot line. Says what is derived, what is measured and what is not sent. */
    fun describe(): String =
        "lodestones: ${lodestones.size} morph parents derived from locs_attr.morphs_1 " +
            "(${usable.size} with a well-formed varbit); actions $ACTIONS; " +
            "activation = varbit := 1 + SYNTH_SOUND $SOUND after ${DELAY_TICKS} tick(s) " +
            "(the varbit and the sound are; the delay " +
            "is one sample). NOT sent: the achievement MESSAGE_GAME, SPOTANIM_SPECIFIC, CAM_MOVETO, " +
            "HINT_ARROW and varp 12351 - none of those is a lodestone fact this module can state. " +
            "WORLD-MAP TELEPORT: ${slots.size} slot(s) from $slotSeedPath " +
            "(clientscript 14999's icon order + enum 5726; 24 of 33 cross-checked against map_loc), " +
            "${TELEPORT_TICKS} tick(s) click->rebuild (2/2 by the clock: 20.36 and 20.26 " +
            "ticks), slot 12=Seers' Village and slot 14=Varrock are the two measured rows" +
            "${if (slots.isEmpty()) " - THE SEED IS MISSING, so every slot is refused" else ""}. " +
            "Activation is ${if (requireActivated) "REQUIRED" else "NOT required (a DECISION: the client " +
                "gates the icon itself, so nothing says what a server should do; " +
                "-Dopennxt.lodestone.teleport.requireactive=true)"}."
}
