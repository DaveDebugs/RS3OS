package com.opennxt.model.world

import com.opennxt.model.account.AccountStore
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.EntityList
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.tick.Tickable
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class World : Tickable {
    private val logger = KotlinLogging.logger { }

    companion object {
        /**
         * How often every live world player is written back to the account
         * store, in ticks.
         *
         * RECONSTRUCTED, not extracted: no cache table carries a server-side
         * autosave cadence - it lives at Jagex, and Jagex has never published
         * it. 100 is chosen for one property that can be stated exactly: the
         * engine ticks every [TICK_INTERVAL_MS] ms, so 100 ticks is 60,000 ms -
         * one minute. That is the bound this constant buys: a crash, a kill -9
         * or a power cut loses at most one minute of a player's progress,
         * instead of everything since they logged in (which is what the
         * leave-only save gives you, since a crash runs no cull).
         *
         * Raising it trades crash-loss for database writes; lowering it the
         * other way. Nothing derives this number - if you change it, change
         * the minute claim in [AUTOSAVE_PROVENANCE] with it.
         */
        const val AUTOSAVE_INTERVAL_TICKS = 100

        /**
         * The engine's tick period in milliseconds. MIRRORS the literal in
         * [com.opennxt.model.tick.TickEngine.submit]
         * (`scheduleAtFixedRate(tickable::tick, 0, 600, MILLISECONDS)`), which
         * declares no constant to reference. Duplicated rather than guessed,
         * and named here so [AUTOSAVE_INTERVAL_TICKS]' one-minute claim is
         * arithmetic a check can re-run rather than a comment to believe.
         */
        const val TICK_INTERVAL_MS = 600

        /**
         * The provenance label of [AUTOSAVE_INTERVAL_TICKS], as a string,
 * because a KDoc comment is invisible at runtime and this server's
         * rule is that a RECONSTRUCTED number is labelled where it is declared
         * AND cannot pass itself off as measured. A check asserts this says
         * RECONSTRUCTED and that the arithmetic in it holds.
         */
        const val AUTOSAVE_PROVENANCE =
            "RECONSTRUCTED: $AUTOSAVE_INTERVAL_TICKS ticks x ${TICK_INTERVAL_MS}ms = 60000ms = one minute; " +
                "no cache table carries a server-side autosave cadence. The bound it buys: a crash loses at " +
                "most a minute of progress, since a crash runs no leave-cull."
    }

    private val playerEntities = EntityList<PlayerEntity>(2000)
    private val players = HashSet<WorldPlayer>()

    /**
     * The world's npc population. Empty until [WorldNpcs.populate] is called
     * at boot (OpenNXT does so only when rs3.sqlite is available, the same
     * guard the content modules use).
     */
    val npcs = WorldNpcs()

    /**
     * Items lying on the world's tiles (NPC drops awaiting pickup). Ticked
     * here so despawn timers advance with the world; spawned into by the
     * ground-item-aware [WorldNpcs.applyDamage] overload.
     */
    val groundItems = GroundItems()

    private val toAdd = ConcurrentLinkedQueue<WorldPlayer>()

    // =====================================================================
    // ONE WORLD SESSION PER ACCOUNT
    // =====================================================================
    //
    // THE DEFECT THIS EXISTS FOR,.
    //
    // Nothing anywhere refused a SECOND login for an account that was already
    // in the world. Two `GAMELOGIN_CONTINUE`s for one name built two
    // [WorldPlayer]s, and because [com.opennxt.content.impl.Banks] keys its
    // per-account bank on the NAME, both of them share ONE [Bank] object.
    // Constructing the second one runs [com.opennxt.content.impl.Banks.restoreBank],
    // which is a WHOLESALE REPLACE of that shared bank with whatever the
    // DATABASE last stored - so everything the first session banked since its
    // last autosave is destroyed in memory, and the next autosave (either
    // session's - they write the same row) makes that permanent on disk.
    //
 // section (a) drives exactly
    // that sequence against a real [AccountStore], a real [Bank] and real
    // deposits, and reports the number of coins that stop existing. It is not
    // small: the autosave interval is 100 ticks, so the window is a full
    // minute of banking per relog.
    //
    // The guard is a per-WORLD registry rather than a process-wide one on
    // purpose: checks stand up several [World]s in one JVM with the same
    // account names, and a static registry would make the second world refuse
    // players for reasons that have nothing to do with the server.
    //
    // WHAT IT DOES NOT COVER, stated rather than implied:
    // - the LOBBY. [com.opennxt.model.lobby.LobbyPlayer] restores and sessions
    //    the same shared bank, so two lobby sessions clobber each other the same
    //    way. That is a second guard in a second class and it is NOT added here;
    //    see the report of this pass.
    //  - a half-open TCP connection. The reservation is released by
    //    [cullDisconnected], which needs `channel.isActive` to go false. Until
    //    it does, that account cannot log in - which is the same trade every
    //    "you are already logged in" screen in this genre makes, and it is the
    //    safe direction: refusing a login loses nothing, allowing a duplicate
    //    one loses items.

    /** [sessions]' placeholder between the reservation and the [WorldPlayer]. */
    private object Reserved

    /**
     * account key -> the [WorldPlayer] holding this world's session for it, or
     * [Reserved] while the login handler is still building one.
     *
     * CONCURRENT because the two ends are on different threads, which is the
     * whole reason this is a map with an atomic claim rather than a `contains`
     * followed by an `add`: [reserveSession] and [addPlayer] run on the netty
     * event loop (from `LoginServerHandler`), [releaseSession] runs on the tick
     * thread (from [cullDisconnected]). Two simultaneous logins for one account
     * are two netty threads, and a get-then-put would let both through.
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * The account identity a world session is claimed under.
     *
     * FOLDED, and it must stay folded: [com.opennxt.model.account.AccountStore]
     * makes usernames unique case-insensitively (`COLLATE NOCASE`) and
     * [com.opennxt.content.impl.Banks.bankKey] folds for exactly that reason.
     * A guard that did not fold would let 'Alice' log in alongside 'alice'
     * and share one bank and one save row with it - i.e. it would guard nothing
     * in precisely the case that costs items.
     */
    fun sessionKey(name: String): String = name.lowercase()

    /** Whether [name] holds (or is reserving) a world session here. */
    fun isOnline(name: String): Boolean = sessions.containsKey(sessionKey(name))

    /** Accounts holding a world session, reservations included. */
    fun onlineAccounts(): Int = sessions.size

    /**
     * ATOMICALLY claims the world session for [name]. False means that account
     * is already in this world and the caller must NOT build a [WorldPlayer]
     * for it - the constructor alone is destructive (it restores the shared
     * bank), so the refusal has to happen before construction, not after.
     */
    fun reserveSession(name: String): Boolean = sessions.putIfAbsent(sessionKey(name), Reserved) == null

    /**
     * Drops [name]'s claim. Called by [cullDisconnected] on the way out, and by
     * the login handler when a reserved login fails before the player joins.
     */
    fun releaseSession(name: String): Boolean = sessions.remove(sessionKey(name)) != null

    /** Ticks since this world started. */
    private var tickCount = 0L

    /**
     * Ticks since this world started, read-only.
     */
    val currentTick: Long get() = tickCount

    /** The tick [autosave] last ran on, or -1 if it never has. */
    private var lastAutosaveTick = -1L

    /** Ticks completed. Observable so the autosave cadence can be verified. */
    fun ticks(): Long = tickCount

    /** The tick the periodic [autosave] last ran on, -1 if never. */
    fun lastAutosaveTick(): Long = lastAutosaveTick

    /**
     * Where the leave-save and the autosave write. Null means the server-wide
     * [AccountStore.instance] - resolved lazily through [accountStore] rather
     * than stored here, so merely constructing a World does not open
     * data/accounts.sqlite. Set by [useAccountStore] so a verification harness
     * can drive a REAL cull against a scratch database instead of the live one.
     */
    private var storeOverride: AccountStore? = null

    /** The account store this world persists into. */
    val accountStore: AccountStore get() = storeOverride ?: AccountStore.instance

    /**
     * Points this world's persistence at [store]. For tools and checks; the
     * server never calls it, and the default is the real store.
     */
    fun useAccountStore(store: AccountStore) {
        storeOverride = store
    }

    /**
     * Runs one player's phase of the tick and CONTAINS anything it throws.
     *
     * This is the same fix as [com.opennxt.model.tick.TickEngine.submitTickable]'s
     * try/catch, one frame lower down, and it is needed for the same measured
     * reason. Without it a single player's throw propagates out of [tick] and
     * takes the whole world's tick with it: every player after them in the
     * iteration order is not ticked and not flushed, [cullDisconnected] does not
     * run, and [tickCount] does not advance - so the autosave cadence stops too.
     * TickEngine's catch stops the world being UNSCHEDULED, but it cannot make
     * the aborted phases happen.
     */
    private inline fun perPlayer(player: WorldPlayer, phase: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val seen = ++perPlayerFailures
            if (seen <= 3 || seen % 500 == 0) {
                logger.error(t) {
                    "Uncaught throwable in $phase for world player '${player.name}' (occurrence $seen) - " +
                        "contained; the rest of this tick continues. Without this catch it would abort the " +
                        "whole world tick, permanently if it repeats."
                }
            }
        }
    }

    /** Contained per-player failures, for the rate limit in [perPlayer]. */
    private var perPlayerFailures = 0

    /** Contained per-player failures since boot. Observable so a check can assert on it. */
    fun containedPlayerFailures(): Int = perPlayerFailures

    private var phaseFailures = 0
    fun containedPhaseFailures(): Int = phaseFailures

    private var combatFailures = 0
    fun containedCombatFailures(): Int = combatFailures

    override fun tick() {
 // (audit T-04): the npc and ground-item phases were the only two with no
        // containment - a throw in either skipped every later phase for every player until the
        // throwing npc respawned. Contained the same way the skilling phase below is.
        try {
            npcs.tick()
        } catch (t: Throwable) {
            phaseFailures++
            logger.error(t) { "Uncaught throwable in the npc phase - contained; the rest of this tick continues" }
        }
        try {
            groundItems.tick()
        } catch (t: Throwable) {
            phaseFailures++
            logger.error(t) { "Uncaught throwable in the ground-item phase - contained; the rest of this tick continues" }
        }

        // Resource respawns: a chopped tree turning back from a stump.
        //
        // Positioned with npcs.tick() and groundItems.tick() because it is the
        // same kind of phase - a world-owned timer sweep that must run before
        // any player is encoded, so a tree that respawns this tick is a tree in
        // this tick's scene rather than the next one's.
        //
        // Contained, for the reason perPlayer is: this is the newest phase in
        // the loop and an escaping throw here would abort every phase after it,
        // permanently if it repeated. With
        // -Dopennxt.experiment.skilling=false nothing is ever depleted, so this
        // is a counter increment and an empty-list walk.
        try {
            com.opennxt.content.impl.Skilling.tick()
        } catch (t: Throwable) {
            logger.error(t) {
                "Uncaught throwable in the skilling respawn phase - contained; the rest of this tick continues"
            }
        }

 //the three skills built, each contained the same way
        // and for the same reason as Skilling above. They are in THIS phase, not a later one,
        // because each publishes into the scene on the tick it acts: a fire that catches this tick
        // has to be in this tick's LOC set, and a caught fish has to be in this tick's backpack.
        try {
            com.opennxt.content.impl.Fishing.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the fishing phase - contained; the rest of this tick continues" }
        }
        try {
            com.opennxt.content.impl.Firemaking.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the firemaking phase - contained; the rest of this tick continues" }
        }
        try {
            com.opennxt.content.impl.Cooking.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the cooking phase - contained; the rest of this tick continues" }
        }
        try {
            com.opennxt.content.impl.Smithing.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the smithing phase - contained; the rest of this tick continues" }
        }
 // THESE TWO WERE NESTED INSIDE THE SMITHING CATCH, and so only ran when
        // Smithing.tick() THREW - i.e. never. The insertion that added them anchored on the first
        // `}` after `logger.error`, which closes the error LAMBDA, not the catch block. Result:
        // fletching never advanced a cut and no lodestone activation ever completed, while both
        // modules' own checks stayed green because every check drives `tick()` directly.
        //
        // Found by the counter added to Lodestones.tick for exactly this question: three clicks
        // were ACCEPTED and the "tick() sees N pending" line never printed once.
        try {
            com.opennxt.content.impl.Fletching.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the fletching phase - contained; the rest of this tick continues" }
        }
        try {
            com.opennxt.content.impl.Lodestones.tick()
        } catch (t: Throwable) {
            logger.error(t) { "Uncaught throwable in the lodestone phase - contained; the rest of this tick continues" }
        }

        drainPending()

        players.forEach { perPlayer(it, "handleIncomingPackets") { it.handleIncomingPackets() } }

        // The combat phase. Its POSITION between these two loops is the whole of
        // its wiring, and both sides of it are load-bearing:
        //
        //  - AFTER handleIncomingPackets, so an OPNPC "Attack" that arrived this
        //    tick engages and can land its first hit on the same tick instead of
        //    a tick later for no reason.
        //  - BEFORE the per-player tick loop, because that loop is what encodes
        //    NPC_INFO. A hit splat or a face-entity block queued here rides out
        //    on this tick; queued after it, it would sit unsent and then be
        //    swept by npcs.tick() at the top of the next one.
        //
        // It disturbs nothing that was already load-bearing: PLAYER_INFO and
        // NpcInfoEncoder.writeTo still happen inside WorldPlayer.tick in that
        // order, the PlayerUpdates.clear sweep below is untouched, and no
        // viewport state is read or written here.
        //
        // With -Dopennxt.experiment.combat unset, PlayerCombat.tick returns on
        // its first line, so this is a no-op by default.
        //
        // Contained like the per-player phases, for the same measured reason
        // (see perPlayer): an escaping throw here would abort every phase after
        // it - the encode loop, the clear sweep, the flush, the cull and the
        // tick counter - permanently if it repeated.
        try {
            PlayerCombat.tick(npcs, groundItems, players.toList())
        } catch (t: Throwable) {
            combatFailures++
            logger.error(t) {
                "Uncaught throwable in the combat phase - contained; the rest of this tick continues"
            }
        }

        players.forEach { perPlayer(it, "tick") { it.tick() } }

        // Retire this tick's player update blocks, and do it HERE - after every
        // player has been encoded, not inside WorldPlayer.tick().
        //
        // A block is queued once and must reach every viewer exactly once. Clear
        // it inside a player's own tick and the players encoded after them never
        // see it; never clear it and it re-sends forever, so one emote plays on
        // a loop for the rest of the session. The encode loop above is the only
        // point where "all viewers have seen it" is true, so this is the only
        // correct place for it.
        //
        // This mirrors what the npc side already does from WorldNpcs.tick; the
        // player side had no owner at all, which is why the demo blocks are
        // one-shot latches rather than relying on being cleared.
        players.forEach { PlayerUpdates.clear(it.entity) }

        players.forEach { perPlayer(it, "flush") { it.client.flush() } }

        cullDisconnected()

        tickCount++
        if (tickCount % AUTOSAVE_INTERVAL_TICKS == 0L) {
            lastAutosaveTick = tickCount
            autosave()
        }
    }

    /**
     * Moves everything queued by [addPlayer] into the live world: the player
     * set, an [EntityList] index (which is where [PlayerEntity.index] comes
     * from, and why [WorldPlayer.added] can only run here), then [WorldPlayer.added].
     * Returns how many joined.
     */
    fun drainPending(): Int {
        var added = 0
        while (true) {
            val player = toAdd.poll() ?: break

            // THE INDEX FIRST, AND ITS RESULT HONOURED.
            //
            // [EntityList.add] returns FALSE when the list is full and assigns
            // no index - and this loop used to ignore that. The player went
            // into the world anyway carrying index -1, and [WorldPlayer.added]
            // reaches `viewport.init`, which throws
            // IllegalStateException("Player index must be between 1 and 2047")
            // on any index below 1. Out of added(), out of drainPending, out of
            // tick() - so the 2001st player's join skipped the packet phase, the
            // combat phase, the encode loop, the flush, the cull and the tick
            // counter FOR EVERYONE, and left a ghost in the player set that is
            // ticked forever and can never be encoded.
            //
            // Refusing is the only honest answer: there is no index to give
            // them, so there is no player to be.
            if (!playerEntities.add(player.entity)) {
                logger.error {
                    "World is FULL (${playerEntities.size()}/${playerEntities.capacity} entity slots): " +
                        "REFUSING '${player.name}'. Admitting them would put a player with no entity index " +
                        "into the world and throw out of the world tick."
                }
                sessions.remove(sessionKey(player.name), player)
                runCatching { player.client.channel.close() }
                continue
            }

            players += player

            // CONTAINED, per player, inside the loop - the same fix
            // [com.opennxt.model.lobby.Lobby.tick]'s add loop already has, for
            // the same measured reason, and this is the world's identical loop
            // which did not get it. added() sends the game login response,
            // awaits it, swaps the pipeline, builds the viewport and encodes
            // REBUILD_NORMAL; any throw in there aborted the WHOLE world tick
            // rather than one player's join. Inside the loop, not around it, so
            // the next queued player still joins.
            perPlayer(player, "added") { player.added() }
            added++
        }
        return added
    }

    /**
     * Drops players whose channel died and stores their state on the way out -
     * the same cull [com.opennxt.model.lobby.Lobby.tick] runs, and for the same
     * reason: no logout packet is handled yet, so a dead channel is the only
     * leave signal there is. Until this existed the world's player set only
     * ever GREW: a disconnected player kept an [EntityList] index, kept being
     * ticked, and never had a single thing about them written down.
     *
     * Two removals, both required: out of [players] (so they stop being ticked)
     * and out of [playerEntities] (so the index is freed and they stop being
     * visible to everyone else). Storing happens after both, so a save that
     * throws still leaves a consistent world - it does not leave a half-removed
     * player behind.
     *
     * Runs AFTER the add/handle/tick phase, exactly like the lobby's, so a
     * player who joins and drops inside one tick is still [WorldPlayer.added]
     * first and then persisted, rather than lost.
     *
     * Returns the names culled - a list rather than a count, so a caller can
     * say WHO left; nothing about a leaving player is silent.
     */
    fun cullDisconnected(): List<String> {
        val culled = ArrayList<String>()
        val iterator = players.iterator()
        while (iterator.hasNext()) {
            val player = iterator.next()
            if (player.client.channel.isActive) continue
            iterator.remove()
            playerEntities.remove(player.entity)
            // action stayed in Skilling's action map and ran a ghost cycle every CYCLE_TICKS - SQL
            // on the tick thread, sends to a dead channel - until the backpack filled, or for ever
            // when it never could. Stop it here, before the save, with the player.
            runCatching { com.opennxt.content.impl.Skilling.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s skilling action at cull" } }
 //: the same ghost-cycle hazard for each new skill. Fishing and Cooking run the
            // identical 4-tick cycle; Firemaking holds a pending light that would otherwise catch,
            // place a loc and pay xp for a player who is gone.
            runCatching { com.opennxt.content.impl.Fishing.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s fishing action at cull" } }
            runCatching { com.opennxt.content.impl.Firemaking.cancelFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not cancel ${player.name}'s pending fire at cull" } }
            runCatching { com.opennxt.content.impl.Cooking.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s cooking action at cull" } }
            runCatching { com.opennxt.content.impl.Smithing.cull(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s smithing project at cull" } }
            runCatching { com.opennxt.content.impl.Fletching.stopFor(player.contentPlayer, "logout (culled)") }
                .onFailure { logger.warn(it) { "could not stop ${player.name}'s fletching action at cull" } }
 // THE ACTION SLOT. Each of the five stop calls above already releases the
            // slot on its own way out, so this is a BACKSTOP and not the primary path: it is what
            // stops a module added later - one whose stop call somebody forgets to add here - from
            // leaving a culled player holding the slot. Last, so it cannot free a slot before the
            // module that owns it has done its own cleanup (Firemaking's cull is what gives the
            // escrowed logs back).
            runCatching { com.opennxt.content.ActionSlot.clearFor(player.contentPlayer) }
                .onFailure { logger.warn(it) { "could not release ${player.name}'s action slot at cull" } }
            // Release the account's world session, and ONLY if this player is
            // still the holder: the two-argument remove is what stops a cull
            // from freeing a claim that a newer session has already taken.
            sessions.remove(sessionKey(player.name), player)
            culled += player.name
            val location = player.entity.location
            // Built ONCE, outside the try. The dead-letter below used to call toSave() a second
            // time, so if toSave() itself was what failed - not the database - the recovery path
            // failed for the same reason and recorded nothing. Now a toSave() failure is caught
            // here with nothing to dead-letter, and a STORE failure still has the blob in hand.
            val blob = try {
                player.toSave()
            } catch (e: Exception) {
                logger.error(e) {
                    "Could not even BUILD the save for world player '${player.name}' at cull - " +
                        "this session's progress is LOST, and the fault is in toSave(), not the database."
                }
                continue
            }
            try {
                accountStore.storeSave(player.name, blob)
                logger.info {
                    "World player '${player.name}' disconnected; removed from the world and stored save on leave " +
                        "at (${location.x}, ${location.y}, ${location.plane})"
                }
            } catch (e: Exception) {
                // A FAILED SAVE HERE IS PERMANENT, and that is why this branch does more than
                // log. By the time it runs the player is already out of `players`, out of
                // `playerEntities` and has released its session claim - so nothing will retry it.
                // The autosave path can afford a bare catch because the player is still live and
                // the next pass (100 ticks) tries again; this one cannot.
                //
                // The ordering is deliberate and is NOT changed here: saving before the removal
                // would let an unsaveable disconnected player linger in the world forever, which
                // is a worse failure than a recoverable one. Instead the blob is written to a
                // dead-letter file so the session is recoverable by hand.
                //
 // Reviewed against "what happens if the DB is temporarily
                // unavailable?" - previously the answer was "that session is gone, with one ERROR
                // line naming no data".
                logger.error(e) { "Failed to store save for world player '${player.name}' at cull" }
                // BLOCKING FILE I/O ON THE TICK THREAD, deliberately. `cullDisconnected` runs
                // inside `World.tick`, so this write stalls the world while it happens. It is
                // accepted here because the path is EXCEPTIONAL (only after a save has already
                // failed), BOUNDED (one file, one player), and the alternative is losing the
                // session outright. Note the tick loop already does blocking SQLite writes on
                // this thread every 100 ticks in `autosave` - a far larger exposure than this,
                // and not one introduced here.
                try {
                    val dir = com.opennxt.Constants.DATA_PATH.resolve("diag").resolve("unsaved")
                    java.nio.file.Files.createDirectories(dir)
                    val f = dir.resolve("${player.name}-${System.currentTimeMillis()}.json")
                    java.nio.file.Files.writeString(f, blob.toJson())
                    logger.error {
                        "DEAD-LETTERED the save for '${player.name}' to $f - the database refused it " +
                            "and the player is already out of the world, so nothing will retry. " +
                            "Restore it by hand or the session is lost."
                    }
                } catch (t: Throwable) {
                    // Two failures in a row means the state really is unrecoverable. Say so in
                    // those words rather than leaving a second stack trace to be read as noise.
                    logger.error(t) {
                        "DEAD-LETTER ALSO FAILED for '${player.name}'. This session's progress is " +
                            "LOST - not delayed, lost."
                    }
                }
            }
        }
        return culled
    }

    /**
     * Writes every live world player back to the account store. Called every
     * [AUTOSAVE_INTERVAL_TICKS] ticks - see that constant for why 100 and what
     * bound it buys.
     *
     * Returns how many players were stored. A player whose save throws is
     * logged and SKIPPED rather than aborting the sweep: one unstorable
     * account must not stop everyone else's progress from being written.
     */
    fun autosave(): Int {
        if (players.isEmpty()) return 0
        var stored = 0
        players.forEach { player ->
            try {
                accountStore.storeSave(player.name, player.toSave())
                stored++
            } catch (e: Exception) {
                logger.error(e) { "Autosave failed for world player '${player.name}'; continuing with the rest" }
            }
        }
        logger.info { "Autosave (tick $tickCount, every $AUTOSAVE_INTERVAL_TICKS ticks): stored $stored of ${players.size} world player(s)" }
        return stored
    }

    fun getPlayer(index: Int): PlayerEntity? = playerEntities[index]

    /**
     * Live world players plus the not-yet-ticked pending queue, so a caller can
     * observe an [addPlayer] - or a cull - without driving a tick first. Mirrors
     * [com.opennxt.model.lobby.Lobby.playerCount].
     */
    fun playerCount(): Int = players.size + toAdd.size

    /**
     * Runs [action] for every LIVE world player.
     *
     * Live only - the [toAdd] queue is excluded on purpose. A pending player
     * has no [PlayerEntity.index] yet (that is assigned in [drainPending]) and
     * their game pipeline is not swapped in until [WorldPlayer.added] runs, so
     * writing a packet to one is at best dropped and at worst encoded into a
     * login-stage pipeline. Anything they need at join time comes from
     * [WorldPlayer.added], not from a broadcast.
     *
     * Iterates a COPY. The callers are broadcasts - a loc change reaching
     * everyone who can see it - and a broadcast that provokes a cull would
     * otherwise throw ConcurrentModificationException out of the middle of the
     * world tick.
     */
    fun forEachPlayer(action: (WorldPlayer) -> Unit) {
        players.toList().forEach(action)
    }

    /**
     * Queues [player] to join on the next [drainPending].
     *
     * Returns FALSE, adding nothing, when a DIFFERENT live player already holds
     * this account's session - the duplicate-login refusal, enforced here as
     * well as at [reserveSession] so a caller that skipped the reservation
     * cannot get two sessions either. A caller that did reserve finds
     * [Reserved] and takes ownership of it.
     *
     * Existing callers ignore the result; that is deliberate and safe - a tool
     * that adds one player per name gets exactly the behaviour it always had.
     */
    fun addPlayer(player: WorldPlayer): Boolean {
        val key = sessionKey(player.name)
        val previous = sessions.putIfAbsent(key, player)
        if (previous != null && previous !== Reserved && previous !== player) {
            logger.error {
                "REFUSED a second world session for account '${player.name}': it is already held by a live " +
                    "player. Allowing it would give both sessions the SAME Banks entry, and the second " +
                    "player's bank restore would wipe whatever the first banked since its last autosave."
            }
            return false
        }
        sessions[key] = player
        toAdd += player
        return true
    }
}