package com.opennxt.resources.sqlite

import com.google.gson.JsonParser
import com.opennxt.resources.config.vars.ScriptVarType

/**
 * What a cache param's declared `vartype` refers to.
 *
 * `params_attr` carries one row per param id, field `type`, value
 * `{"vartype":N,"defaultint":D,"defaultstring":S}` - **5,995 params typed**.
 */
object CacheVarTypes {

    /**
     * A cache table a vartype indexes into, and the client type that denotes it.
     *
     * [table] is the `rs3.sqlite` table, or null where this database has no
     * table for it - which is not the same as the cache having no data for it,
     * and is exactly the hole vartype 23 fell through.
     *
     * [varType] is the client type that DENOTES this target, not the type of
     * every vartype mapped to it. Where two vartypes share a target only the
     * confirmed one carries that name: [MAP] sends both 44 and 131 to
     * [ANIMGROUP], and 44 is `BAS` while 131 is not named by the client at all -
     * see [FINGERPRINT_ONLY]. Read `varType` off the Target for an assertion
     * about the mapping; read [scriptVarTypeOf] for a param's actual type.
     */
    enum class Target(val table: String?, val varType: ScriptVarType) {
        SEQUENCE("sequences", ScriptVarType.SEQ),
        ANIMGROUP("animgroups", ScriptVarType.BAS),
        SPOTANIM("spotanims", ScriptVarType.SPOTANIM),
        STRUCT("structs", ScriptVarType.STRUCT),
        ENUM("enums", ScriptVarType.ENUM),
        NPC("npcs", ScriptVarType.NPC),
        ITEM("items", ScriptVarType.OBJ),
        LOC("locs", ScriptVarType.LOC),

        /** Cache index 8. `rs3.sqlite` has no sprites table; see the class KDoc. */
        SPRITE(null, ScriptVarType.GRAPHIC),
    }

    /**
     * vartype -> what it indexes. Tier for every entry except
     * [FINGERPRINT_ONLY]: the cache declares the type in param opcode 101 and
     * that id is [ScriptVarType]`.id`.
     */
    private val MAP: Map<Int, Target> = mapOf(
        6 to Target.SEQUENCE,     // SEQ
        23 to Target.SPRITE,      // GRAPHIC (previously mis-typed as SEQUENCE)
        44 to Target.ANIMGROUP,   // BAS
        131 to Target.ANIMGROUP,  // not named by the client; see FINGERPRINT_ONLY
        37 to Target.SPOTANIM,    // SPOTANIM
        73 to Target.STRUCT,      // STRUCT
        26 to Target.ENUM,        // ENUM
        32 to Target.NPC,         // NPC
        33 to Target.ITEM,        // OBJ
        30 to Target.LOC          // LOC
    )

    /**
     * The entries [ScriptVarType] cannot confirm, and which therefore still rest
     * on the range fingerprint alone.
     *
 * 131 decodes as `TYPE_131` - the client's table has the id but this server
     * has no name for it. The fingerprint puts its 67 values under `animgroups`
     */
    val FINGERPRINT_ONLY: Set<Int> = setOf(131)

    /**
     * Max-fit per vartype where the range fingerprint still AGREES with the
     * client, kept so a check can assert what was measured.
     */
    val MAX_FIT: Map<Int, Double> = mapOf(
        32 to 1.0000, 33 to 1.0000, 73 to 0.9999, 131 to 0.9974,
        26 to 0.9994, 37 to 0.9982, 30 to 0.9714,
        6 to 1.0000, 44 to 0.9980
    )

    /**
     * vartype -> (the table the range fingerprint picks over `rs3.sqlite`, its
     * max-fit there). Both entries are WRONG about the vartype and right about
     * the table, which is the only honest way to record them.
     */
    val FINGERPRINT_DISAGREES: Map<Int, Pair<String, Double>> = mapOf(
        23 to ("sequences" to 0.9556)
    )

    /**
     * Max-fit for vartype 23 against the table the client actually names - cache
     * index 8, 36,379 groups, max id 36,474, vartype-23 max 36,391.
     *
     * Not derivable from `rs3.sqlite`, which is the whole point: this is the
     * number the fingerprint could not compute, because its candidate set was
     * the set of tables that happened to exist in this database.
     */
    const val SPRITE_MAX_FIT: Double = 0.9977

    val PROVENANCE: String =
        "cache vartypes: 9 of 10, read out of the client. A param config's opcode 101 " +
            "carries a small-smart ScriptVarType id; decoded off the served cache through " +
            "ParamFilesystemCodec it reproduces params_attr's vartype on 5,983 of the 5,993 params both " +
            "sources type (99.83%), against 25.40% for a shuffled control. The range fingerprint " +
 // 8 of 10 -> 9 of 10. The served-vintage migration RESOLVED two of the
            // fingerprint's disagreements by itself: vt6 now fits sequences at 1.0000 (was structs
            // 0.7143) and vt44 fits animgroups at 0.9980 (was spotanims 0.5404), because the
            // tables they fit are the ones that just moved to the served vintage. 33 was already
 // stale on and is no longer a disagreement either; 23 remains the one real
            // miss. MAX_FIT's own KDoc predicted this outcome in writing before the migration ran.
            "that named these first is kept as corroboration: it agrees on 9 of 10 and is WRONG on " +
            "23 alone (named sequences at max-fit 0.9556 " +
            "because rs3.sqlite has no sprites table; the client says GRAPHIC, index 8, fit 0.9809). " +
            "vartype 131 is the one " +
            "entry the client does not name and rests on the fingerprint alone. The membership test people " +
            "reach for first is VACUOUS and is documented as such - 77.2% real against a 91.3% random control."

    private val cache = HashMap<Int, Int?>()

    /** The declared vartype of a param id, or null when the cache does not type it. */
    @Synchronized
    fun vartypeOf(paramId: Int): Int? = cache.getOrPut(paramId) {
        if (!RsDatabase.available) return@getOrPut null
        RsDatabase.queryOne(
            "SELECT value FROM params_attr WHERE id = ? AND field = 'type'", paramId
        ) { JsonParser().parse(it.getString(1)).asJsonObject.get("vartype")?.asInt }
    }

    /**
     * The client's type for a param, or null when the param is untyped or its
     * type id is absent from [ScriptVarType].
     */
    fun scriptVarTypeOf(paramId: Int): ScriptVarType? =
        vartypeOf(paramId)?.let { ScriptVarType.getById(it) }

    /** What a param's values index into, or null when the vartype is unnamed here. */
    fun targetOf(paramId: Int): Target? = vartypeOf(paramId)?.let { MAP[it] }

    /**
     * True when this param's values are sequence ids.
     */
    fun isSequence(paramId: Int): Boolean = targetOf(paramId) == Target.SEQUENCE

    /** The named vartype map, for checks and for auditing. */
    fun named(): Map<Int, Target> = MAP
}
