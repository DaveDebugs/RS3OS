package com.opennxt.content

import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object ActionSlot {

    private val logger = KotlinLogging.logger { }

    /**
     * A module that can hold a player's one action slot.
     *
     * [cancelSlot] must be the module's OWN stop path - the same one a cull or a logout uses - so
     * that displacement and every other kind of stop do identical cleanup. It must not throw; if it
     * does, [claim] contains it and logs, and the new action still starts.
     */
    interface Owner {
        /** What this module calls its action, for the log line and for the check. */
        val actionName: String

        /** Stop this player's action in this module. Called by [claim] when another module takes over. */
        fun cancelSlot(player: ContentPlayer, why: String)
    }

    /** `-Dopennxt.content.actionslot=off` restores the five-independent-maps behaviour. */
    val enabled: Boolean = System.getProperty("opennxt.content.actionslot") != "off"

    /**
     * Who holds each player's slot.
     *
     * A WeakHashMap for the reason every other per-player map in these modules is one: a
     * disconnected player's [ContentPlayer] must stay collectable, and this class must never be the
     * reference that keeps a session alive. Identity keying is what ContentPlayer gives (it does not
     * override equals) and it is what is wanted - one live ContentPlayer per WorldPlayer.
     */
    private val holder: MutableMap<ContentPlayer, Owner> =
        Collections.synchronizedMap(WeakHashMap<ContentPlayer, Owner>())

    private val lock = Any()

    private var displacements = 0
    private var claims = 0
    private var releases = 0
    private var cancelFailures = 0

    /** Actions stopped because another module took the slot. */
    fun displacements(): Int = synchronized(lock) { displacements }
    fun claims(): Int = synchronized(lock) { claims }
    fun releases(): Int = synchronized(lock) { releases }

    /** Throws contained out of a displaced owner's own cancel path. Never silent; each one logs at ERROR. */
    fun cancelFailures(): Int = synchronized(lock) { cancelFailures }

    fun holderOf(player: ContentPlayer): Owner? = synchronized(lock) { holder[player] }

    fun heldCount(): Int = synchronized(lock) { holder.size }

    /**
     * Takes the slot for [owner], stopping whoever held it.
     *
     * Returns the displaced owner, or null when the slot was free or already this owner's -
     * re-claiming a slot you already hold is a no-op, which is what lets a module call this on every
     * re-arm of a running action without thinking about it.
     *
     * The displaced owner's [Owner.cancelSlot] runs AFTER the new owner is installed. See the class
     * doc: that order is what makes the re-entrant [release] harmless.
     */
    fun claim(player: ContentPlayer, owner: Owner): Owner? {
        if (!enabled) return null
        val previous = synchronized(lock) {
            claims++
            val prev = holder[player]
            holder[player] = owner
            if (prev != null && prev !== owner) displacements++
            prev
        }
        if (previous == null || previous === owner) return null
        // Contained, and never silent. A module whose stop path throws must not stop the module that
        // is taking over from starting - that would leave the player with NO action and a slot held
        // by the new owner, which is the worst of both.
        runCatching { previous.cancelSlot(player, "started ${owner.actionName}") }.onFailure { t ->
            synchronized(lock) { cancelFailures++ }
            logger.error(t) {
                "action slot: ${previous.actionName}'s cancel threw while ${owner.actionName} took " +
                    "${player.name}'s slot - CONTAINED, ${owner.actionName} still starts"
            }
        }
        logger.info {
            "action slot: ${player.name} stopped ${previous.actionName} and started ${owner.actionName}"
        }
        return previous
    }

    /**
     * Gives the slot up, but only if [owner] is the one holding it.
     *
     * The identity test is load-bearing, not defensive: a displaced module calls this from inside
     * [claim]'s own call to its cancel path, by which time the holder is the NEW owner. Returning
     * false there - and changing nothing - is what keeps the new action registered.
     */
    fun release(player: ContentPlayer, owner: Owner): Boolean = synchronized(lock) {
        if (!enabled) return false
        if (holder[player] !== owner) return false
        holder.remove(player)
        releases++
        true
    }

    /** Drops [player]'s slot whoever holds it. For a cull: the modules' own stop paths run there. */
    fun clearFor(player: ContentPlayer): Owner? = synchronized(lock) { holder.remove(player) }

    /** Test seam, and the reset a check needs between sections. */
    internal fun clear() = synchronized(lock) {
        holder.clear()
        displacements = 0; claims = 0; releases = 0; cancelFailures = 0
    }
}
