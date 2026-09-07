package com.opennxt.model.lobby

import com.opennxt.OpenNXT
import com.opennxt.model.account.AccountStore
import com.opennxt.model.tick.Tickable
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

class Lobby: Tickable {
    private val logger = KotlinLogging.logger {  }

    private val players = HashSet<LobbyPlayer>()
    private val toAdd = ConcurrentLinkedQueue<LobbyPlayer>()

    /**
     * Where the leave-cull writes. Null means the server-wide
     * [AccountStore.instance]; resolved lazily through [accountStore] so merely
     * constructing a Lobby does not open data/accounts.sqlite. Mirrors
     * [com.opennxt.model.world.World.useAccountStore], and exists for the same
     * reason: a verification harness has to be able to drive a REAL cull against
     * a scratch database instead of the live one.
     */
    private var storeOverride: AccountStore? = null

    /** The account store this lobby persists into. */
    val accountStore: AccountStore get() = storeOverride ?: AccountStore.instance

    /** Points this lobby's persistence at [store]. For tools and checks. */
    fun useAccountStore(store: AccountStore) {
        storeOverride = store
    }

    /** Saves the cull SKIPPED because a world session owned the account. */
    private var deferredToWorld = 0

    /** @see deferredToWorld */
    fun savesDeferredToWorld(): Int = deferredToWorld

    /**
     * Contains one lobby player's throw so it cannot abort the whole lobby tick.
     */
    private inline fun perPlayer(player: LobbyPlayer, phase: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val seen = ++perPlayerFailures
            if (seen <= 3 || seen % 500 == 0) {
                logger.error(t) {
                    "Uncaught throwable in $phase for lobby player '${player.name}' (occurrence $seen) - " +
                        "contained; the rest of this lobby tick continues. Without this catch it would abort " +
                        "the add loop, every other player's packet handling and flush, and the cull that " +
                        "persists a leaving player's save."
                }
            }
        }
    }

    /** Contained per-player failures since boot. Observable so a check can assert on it. */
    private var perPlayerFailures = 0

    /** @see perPlayer */
    fun containedPlayerFailures(): Int = perPlayerFailures

    override fun tick() {
        while (true) {
            val player = toAdd.poll() ?: break
            players += player
            // Contained INSIDE the loop, not around it: the point is that the
            // NEXT queued player is still added when this one's login burst
            // throws. A try/catch around the whole while() would stop the add
            // loop at the first casualty, which is the bug.
            perPlayer(player, "added") { player.added() }
        }

        players.forEach { perPlayer(it, "handleIncomingPackets") { it.handleIncomingPackets() } }
        players.forEach { perPlayer(it, "tick") { it.tick() } }
        players.forEach { perPlayer(it, "flush") { it.client.flush() } }

        // Cull players whose channel died, persisting their state at cull
        // time - the lobby's only leave hook (no logout packet is handled
        // yet), and the first time a fresh account's save reaches the store.
        // Runs AFTER the add/handle phase so a same-tick join+drop is still
        // added() first and then persisted rather than lost.
        val iterator = players.iterator()
        while (iterator.hasNext()) {
            val player = iterator.next()
            if (player.client.channel.isActive) continue
            iterator.remove()

            // DO NOT overwrite a save the WORLD currently owns.
            //
            // THE DEFECT THIS EXISTS FOR,. A LobbyPlayer sessions its
            // [LobbyPlayer.save] once, at construction, and never updates it -
            // its own doc says the lobby "does not modify" position, backpack or
            // varps. [LobbyPlayer.toSave] then copies that LOGIN-TIME SNAPSHOT
            // forward. The lobby and the world are separate objects with separate
            // player sets and separate culls, and an account can hold a session
            // in both at once (that is the normal lobby -> "Play" hand-off: two
            // connections, and nothing closes the lobby one on the server side).
            //
            // So when the lobby cull finally runs it writes the account's state
            // AS IT WAS WHEN THEY ENTERED THE LOBBY over everything the world
            // session has done since - position, xp, backpack, varps - because
            // both write the same `saves` row and the latest write wins.
            // reports the tiles and the xp that get rolled back.
            //
            // The refusal costs nothing: if a world session holds the account,
            // that session's own autosave and cull persist it, from LIVE state
            // rather than from a snapshot. Skipping is strictly more informed
            // than writing.
            val world = runCatching { OpenNXT.world }.getOrNull()
            if (world != null && world.isOnline(player.name)) {
                deferredToWorld++
                logger.info {
                    "Lobby player '${player.name}' disconnected; NOT storing the lobby's login-time snapshot - " +
                        "that account holds a WORLD session, which owns its save. Writing here would roll the " +
                        "account back to where it stood when it entered the lobby."
                }
                continue
            }

            try {
                accountStore.storeSave(player.name, player.toSave())
                logger.info { "Lobby player '${player.name}' disconnected; stored save on leave" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to store save for lobby player '${player.name}' at cull" }
            }
        }
    }

    fun addPlayer(player: LobbyPlayer) {
        toAdd += player
    }

    // Counts both the live set and the not-yet-ticked pending queue so a caller can
    // observe an addPlayer() without having to drive a tick first.
    fun playerCount(): Int = players.size + toAdd.size
}