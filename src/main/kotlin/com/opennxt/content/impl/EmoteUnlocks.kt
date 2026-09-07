package com.opennxt.content.impl

import com.opennxt.model.vars.SqliteVarBits
import com.opennxt.resources.sqlite.RsDatabase

/**
 * THE EMOTE UNLOCK VARBITS, derived from the cache.
 */
object EmoteUnlocks {
    data class Unlock(val emoteName: String, val varbit: Int)

    /** Every (emote name, varbit id) pair the cache states. Empty when rs3.sqlite is absent. */
    val unlocks: List<Unlock> by lazy {
        if (!RsDatabase.available) emptyList()
        else RsDatabase.queryAll(
            "SELECT n.stringvalue AS name, s.intvalue AS vb FROM struct_param s " +
                "JOIN struct_param n ON n.struct_id = s.struct_id AND n.prop = 1419 " +
                "WHERE s.prop = 1421 ORDER BY s.struct_id"
        ) { Unlock(it.getString("name") ?: "?", it.getInt("vb")) }
    }

    /**
     * The varp writes that unlock every emote: varp id -> new value, computed over [current]
     * (the player's present value of a varp, so other bits of a shared varp are kept). Varbits
     * the cache does not define, or that will not take a 1, are returned in the second list.
     */
    fun varpsForAll(current: (Int) -> Int): Pair<Map<Int, Int>, List<Unlock>> {
        val out = LinkedHashMap<Int, Int>()
        val skipped = ArrayList<Unlock>()
        for (u in unlocks) {
            val def = SqliteVarBits.definition(u.varbit)
            if (def == null || !def.isWellFormed || !def.fits(1)) { skipped += u; continue }
            val varp = def.varId and 0xffff
            val base = out[varp] ?: current(varp)
            out[varp] = def.write(base, 1)
        }
        return out to skipped
    }
}
