package com.opennxt.model.vars

import com.opennxt.api.vars.VarDomain
import com.opennxt.resources.sqlite.SqliteVarBitCodec
import com.opennxt.resources.sqlite.VarBitDefinition
import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntMaps
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSortedSet

/**
 * Where varbit definitions come from.
 *
 * Split out so a caller can hand [VarPlayerState] a fixed table - a test, a
 * fixture, a future cache-backed decoder - without the class knowing that
 * rs3.sqlite exists.
 */
fun interface VarBitSource {
    fun definition(id: Int): VarBitDefinition?
}

/**
 * The varbit table out of `data/rs3.sqlite`, read once.
 *
 * All 61872 rows are pulled on first use rather than queried per id: a varbit
 * write is on the hot path of nearly every piece of content, and 61872 rows of
 * four ints is a few megabytes. If the database is absent every lookup returns
 * null, matching the rest of the sqlite package.
 */
object SqliteVarBits : VarBitSource {
    private val byId: Map<Int, VarBitDefinition> by lazy { SqliteVarBitCodec.listAll() }

    val size: Int get() = byId.size

    /** Every definition, for callers that want to sweep the table. */
    fun all(): Collection<VarBitDefinition> = byId.values

    override fun definition(id: Int): VarBitDefinition? = byId[id]
}

/** The [VarDomain] half of [VarBitDefinition.varId]. See that class for the evidence. */
val VarBitDefinition.domainId: Int get() = varId ushr 16

/** The varp half of [VarBitDefinition.varId] - the number the varp is known by inside its domain. */
val VarBitDefinition.varpId: Int get() = varId and 0xFFFF

/** Whether this varbit lives in a player varp, and so belongs in a [VarPlayerState]. */
val VarBitDefinition.isPlayerDomain: Boolean get() = domainId == VarDomain.PLAYER.id

/**
 * The varp values of one player, and the varbits packed inside them.
 *
 * Storage is sparse. A player touches a few hundred of the 12573 player varps in
 * a session, so absent means zero and only what was written is kept; writing a
 * varp back to zero drops the entry again.
 *
 * ## Rejecting rather than truncating
 *
 * [setVarbit] throws [IllegalArgumentException] when the value does not fit the
 * field. Masking it down instead would write a value the caller never asked for
 * into this varbit, and - because a varp is shared - would look to the player
 * like an unrelated setting changing on its own. A field that is too narrow for
 * the value is a bug in the caller or a wrong varbit id, and both are worth an
 * exception. Values are unsigned: a 3-bit field takes 0..7 and nothing else, so
 * -1 is rejected too. The single exception is a field spanning all 32 bits,
 * which is the whole varp and therefore holds any Int, negative included.
 *
 * ## Foreign domains
 *
 * 10774 of the 61872 varbit rows describe varps belonging to some other domain
 * (npc, client, clan, ...) - see [VarBitDefinition]. Passing one of those ids
 * here is rejected by default: the alternative is to write into a "player varp"
 * numbered e.g. 327680, which no player has and no client would ever be sent.
 * Construct with `allowForeignDomains = true` to switch that off, which is only
 * useful for measuring the bit arithmetic over the whole table.
 *
 * ## Dirty tracking
 *
 * [dirtyVarps] reports the varps whose value has *changed* since the last
 * [clearDirty]. A write of the value a varp already holds is not a change and
 * does not dirty it - the set exists so a network layer can send deltas, and a
 * no-op write has no delta to send. Writing a varbit dirties the varp it lives
 * in, once, whatever the varbit; the wire unit is the varp.
 *
 * A freshly constructed state has an empty dirty set, so the first flush to a
 * client that has just logged in has to send the state it cares about rather
 * than relying on this - "changed since last flush" cannot express "you have
 * never been told anything".
 *
 * Not thread safe; one instance belongs to one player and is touched from that
 * player's tick.
 */
class VarPlayerState(
    private val source: VarBitSource = SqliteVarBits,
    private val allowForeignDomains: Boolean = false
) {
    private val values = Int2IntOpenHashMap().apply { defaultReturnValue(0) }
    private val dirty = IntOpenHashSet()

    /** The varps holding a non-zero value. Varps never written, or written back to 0, are not here. */
    fun varps(): Int2IntMap = Int2IntMaps.unmodifiable(values)

    fun getVarp(id: Int): Int {
        require(id >= 0) { "varp id must not be negative: $id" }
        return values.get(id)
    }

    /**
     * Sets a varp outright. Returns whether the value changed - and therefore
     * whether this dirtied the varp.
     */
    fun setVarp(id: Int, value: Int): Boolean {
        require(id >= 0) { "varp id must not be negative: $id" }
        val old = values.get(id)
        if (old == value) return false
        if (value == 0) values.remove(id) else values.put(id, value)
        dirty.add(id)
        return true
    }

    /** The definition, or null if no varbit has that id. Does not throw on an unknown id. */
    fun definition(id: Int): VarBitDefinition? = source.definition(id)

    /**
     * The value of a varbit, or null if no varbit has that id. The nullable form
     * exists so "is there such a varbit" does not have to be asked by catching
     * an exception.
     */
    fun getVarbitOrNull(id: Int): Int? {
        val def = source.definition(id) ?: return null
        return usable(def).read(getVarp(def.varpId))
    }

    fun getVarbit(id: Int): Int {
        val def = required(id)
        return usable(def).read(getVarp(def.varpId))
    }

    /**
     * Writes a varbit, leaving every other bit of its varp alone. Returns
     * whether the value changed.
     *
     * @throws IllegalArgumentException if [value] does not fit the field, if no
     *   varbit has that id, or if the varbit belongs to another domain.
     */
    fun setVarbit(id: Int, value: Int): Boolean {
        val def = usable(required(id))
        require(def.fits(value)) {
            "varbit $id is ${def.bitCount} bit(s) wide (0..${def.maxValueUnsigned}); " +
                "refusing to write $value - truncating it would silently change varp ${def.varpId}"
        }
        val varp = def.varpId
        return setVarp(varp, def.write(getVarp(varp), value))
    }

    /** Whether [value] can be written to varbit [id] at all. False for an unknown id. */
    fun accepts(id: Int, value: Int): Boolean {
        val def = source.definition(id) ?: return false
        if (!def.isWellFormed) return false
        if (!allowForeignDomains && !def.isPlayerDomain) return false
        return def.fits(value)
    }

    /** The varps changed since the last [clearDirty], ascending. A snapshot, not a view. */
    fun dirtyVarps(): IntSortedSet = IntAVLTreeSet(dirty)

    fun isDirty(varp: Int): Boolean = dirty.contains(varp)

    fun dirtyCount(): Int = dirty.size

    /** Forgets what changed. The values stay; only the delta bookkeeping resets. */
    fun clearDirty() = dirty.clear()

    private fun required(id: Int): VarBitDefinition =
        source.definition(id) ?: throw IllegalArgumentException(
            "no varbit $id (use definition(id) or getVarbitOrNull(id) if the id may not exist)"
        )

    private fun usable(def: VarBitDefinition): VarBitDefinition {
        require(def.isWellFormed) {
            "varbit ${def.id} has an impossible bit range ${def.bitStart}..${def.bitEnd}; " +
                "a 32-bit varp has no such bits"
        }
        require(allowForeignDomains || def.isPlayerDomain) {
            "varbit ${def.id} lives in domain ${def.domainId} varp ${def.varpId}, not a player varp; " +
                "writing it into player state would change a varp no client is sent"
        }
        return def
    }
}
