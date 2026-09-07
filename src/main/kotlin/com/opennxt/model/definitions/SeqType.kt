package com.opennxt.model.definitions

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * One record of cache index 20 (sequences / animations), build 949.
 */
class SeqType(
    val id: Int,
    /** opcode 1, first parallel array. */
    val frames: List<SeqFrame>? = null,
    /** opcode 2. */
    val unknown02: Int? = null,
    /** opcode 3 — smart count then smart values, read and discarded by the client. */
    val unknown03: IntArray? = null,
    /** opcode 5. */
    val unknown05: Int? = null,
    /** opcode 6 (db column `left_hand_item`). */
    val unknown06: Int? = null,
    /** opcode 7 (db column `right_hand_item`). */
    val unknown07: Int? = null,
    /** opcode 8. */
    val unknown08: Int? = null,
    /** opcode 9. postDecode rewrites 4 to 0 or 2. */
    val unknown09: Int? = null,
    /** opcode 10. postDecode rewrites 3 to 0 or 2. */
    val unknown0A: Int? = null,
    /** opcode 11. */
    val unknown0B: Int? = null,
    /** opcode 12 — u8 count then two parallel u16 arrays. Same store as 112. */
    val unknown0C: List<SeqIntPair>? = null,
    /** opcode 13 — one entry per frame; `sub+0x10`. */
    val unknown0D: List<SeqSound>? = null,
    /** opcodes 14/15/16/17/18 — bare flags, no payload at all. */
    val unknown0E: Boolean = false,
    val unknown0F: Boolean = false,
    val unknown10: Boolean = false,
    val unknown11: Boolean = false,
    val unknown12: Boolean = false,
    /** opcode 19, LAST occurrence, as `[frameIndex, value]`. See [frameFlags]. */
    val unknown13: IntArray? = null,
    /** opcode 20, LAST occurrence, as `[frameIndex, a, b]`. See [framePairs]. */
    val unknown14: IntArray? = null,
    /** opcode 22, `sub+0x60`. */
    val unknown16: Int? = null,
    /** opcode 23 — two bytes the client skips. */
    val unknown17: IntArray? = null,
    /** opcode 24 — u16 id the client resolves through a config lookup. */
    val unknown18: Int? = null,
    /** opcode 25. */
    val skeletalAnimation: Int? = null,
    /**
     * opcode 26 and. postDecode overwrites the second
     * with `sum(framelength[])`, i.e. the animation's total length in ticks.
     */
    val skeletalRange: IntArray? = null,
    /** opcode 27. SIGNED in the client (`movsx`); stored unsigned here. */
    val unknown1B: Int? = null,
    /** opcode 112 — u16 count then two parallel u16 arrays. */
    val unknown70: List<SeqIntPair>? = null,
    /** opcode 119, LAST occurrence, as `[frameIndex, value]`. See [frameFlags]. */
    val unknown77: IntArray? = null,
    /** opcode 120, LAST occurrence, as `[frameIndex, a, b]`. See [framePairs]. */
    val unknown78: IntArray? = null,
    /** opcode 249 — the standard param map. */
    val extra: List<SeqParam>? = null,
    /**
     * Every opcode 19 / 119 write, resolved the way the client resolves them:
     * a per-frame byte array (init 0xff) indexed by the opcode's first operand,
     * so a later write to the same frame wins and writes to OTHER frames are
     * kept, not discarded.
     *
     * The reference extract and `rs3.sqlite` keep only the last occurrence —
     * that is what [unknown13]/[unknown77] hold, so this codec reproduces them
     * exactly — but the last occurrence is NOT the record's content. 22,854
     * opcode-119 writes live in 8,550 records; a last-write-wins scalar throws
     * 14,304 of them away.
     */
    val frameFlags: Map<Int, Int> = emptyMap(),
    /** Every opcode 20 / 120 write: frame index -> `[a, b]`. See [frameFlags]. */
    val framePairs: Map<Int, IntArray> = emptyMap()
) {
    /**
     * opcode 1's `framelength[]`, which is what the previous reader called
     * `delays` and is the only field of this record the running server consumes.
     * It was accidentally right: reading the first of three parallel arrays and
     * skipping `4n` gives the same values as reading `framelength[i]` — but only
     * for THIS field, and only because it is the first array.
     */
    val delays: IntArray? get() = frames?.map { it.framelength }?.toIntArray()

    val durationInTicks: Int
        get() {
            val d = delays ?: return 0
            val totalClientTicks = d.sum()
            // In RS, 30 client ticks (or usually a multiple of 30) = 1 server tick.
            // 0.6 seconds / 0.02 = 30 frames. Round up.
            // The client computes the same sum in postDecode and
            // stores it over skeletal_range[1] at.
            return (totalClientTicks + 15) / 30
        }

    override fun equals(other: Any?) = other is SeqType && other.id == id
    override fun hashCode() = id

    /**
     * The record as the reference decoder emits it, so a Kotlin decode and a
     * `seq_decode.py` decode (support tree, path above) of the same file can be
     * compared as data rather than as two parse rates. Field names are the extract's
     * `unknown_XX` spelling deliberately — they are also `rs3.sqlite`'s
     * `sequences` column names, so this stays drop-in against the database.
     *
     * Absent fields are OMITTED, never emitted as null: "opcode not present" and
     * "opcode present carrying null" are different records, and opcode 13's
     * empty entries are the one place a real null appears.
     */
    fun toJson(includeId: Boolean = true): JsonObject {
        val o = JsonObject()
        frames?.let { fs ->
            val a = JsonArray()
            for (f in fs) {
                val e = JsonObject()
                e.addProperty("framelength", f.framelength)
                e.addProperty("frameindex", f.frameindex)
                e.addProperty("framefile", f.framefile)
                a.add(e)
            }
            o.add("frames", a)
        }
        unknown02?.let { o.addProperty("unknown_02", it) }
        unknown03?.let { o.add("unknown_03", intArr(it)) }
        unknown05?.let { o.addProperty("unknown_05", it) }
        unknown06?.let { o.addProperty("unknown_06", it) }
        unknown07?.let { o.addProperty("unknown_07", it) }
        unknown08?.let { o.addProperty("unknown_08", it) }
        unknown09?.let { o.addProperty("unknown_09", it) }
        unknown0A?.let { o.addProperty("unknown_0A", it) }
        unknown0B?.let { o.addProperty("unknown_0B", it) }
        unknown0C?.let { o.add("unknown_0C", pairArr(it)) }
        unknown0D?.let { ss ->
            val a = JsonArray()
            for (s in ss) {
                val e = JsonObject()
                if (s.value0 == null) {
                    e.add("value0", JsonNull.INSTANCE)
                    e.add("extras", JsonNull.INSTANCE)
                } else {
                    e.addProperty("value0", s.value0)
                    e.add("extras", intArr(s.extras ?: IntArray(0)))
                }
                a.add(e)
            }
            o.add("unknown_0D", a)
        }
        if (unknown0E) o.addProperty("unknown_0E", true)
        if (unknown0F) o.addProperty("unknown_0F", true)
        if (unknown10) o.addProperty("unknown_10", true)
        if (unknown11) o.addProperty("unknown_11", true)
        if (unknown12) o.addProperty("unknown_12", true)
        unknown13?.let { o.add("unknown_13", intArr(it)) }
        unknown14?.let { o.add("unknown_14", intArr(it)) }
        unknown16?.let { o.addProperty("unknown_16", it) }
        unknown17?.let { o.add("unknown_17", intArr(it)) }
        unknown18?.let { o.addProperty("unknown_18", it) }
        skeletalAnimation?.let { o.addProperty("skeletal_animation", it) }
        skeletalRange?.let { o.add("skeletal_range", intArr(it)) }
        unknown1B?.let { o.addProperty("unknown_1B", it) }
        unknown70?.let { o.add("unknown_70", pairArr(it)) }
        unknown77?.let { o.add("unknown_77", intArr(it)) }
        unknown78?.let { o.add("unknown_78", intArr(it)) }
        extra?.let { ps ->
            val a = JsonArray()
            for (p in ps) {
                val e = JsonObject()
                e.addProperty("prop", p.prop)
                if (p.intvalue == null) e.add("intvalue", JsonNull.INSTANCE)
                else e.addProperty("intvalue", p.intvalue)
                if (p.stringvalue == null) e.add("stringvalue", JsonNull.INSTANCE)
                else e.addProperty("stringvalue", p.stringvalue)
                a.add(e)
            }
            o.add("extra", a)
        }
        if (includeId) o.addProperty("id", id)
        return o
    }

    private fun intArr(v: IntArray): JsonArray {
        val a = JsonArray()
        for (x in v) a.add(x)
        return a
    }

    private fun pairArr(v: List<SeqIntPair>): JsonArray {
        val a = JsonArray()
        for (p in v) {
            val e = JsonObject()
            e.addProperty("intlow", p.intlow)
            e.addProperty("maybe_file", p.maybeFile)
            a.add(e)
        }
        return a
    }
}

/** One entry of opcode 1's three parallel arrays. */
data class SeqFrame(val framelength: Int, val frameindex: Int, val framefile: Int)

/** One entry of opcodes 12 / 112, which the client packs as `low or (high shl 16)`. */
data class SeqIntPair(val intlow: Int, val maybeFile: Int)

/** One opcode-13 entry — one per frame. `value0 == null` is the client's empty entry. */
class SeqSound(val value0: Int?, val extras: IntArray?)

/** One opcode-249 param. Exactly one of [intvalue] / [stringvalue] is non-null. */
class SeqParam(val prop: Int, val intvalue: Int?, val stringvalue: String?)

/**
 * The index-20 opcode walk. See [SeqType]'s KDoc for where the layout came from
 * and for the three defects that a parse rate cannot see.
 */
object SeqCodec {

    /** Every opcode the client's if-chain has a case for. 4 and 21 are NOT in it. */
    val KNOWN_OPCODES = intArrayOf(
        1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        19, 20, 22, 23, 24, 25, 26, 27, 112, 119, 120, 249
    )

    /** Implemented from the binary but present in NEITHER cache, so unexercised. */
    val UNEXERCISED_OPCODES = intArrayOf(3, 12, 17, 19, 20, 23)

    enum class Defect {
        NONE,

        /** Opcode 1 read as `n` interleaved 6-byte tuples. Same byte count. */
        OP1_INTERLEAVED_TUPLES,

        /** Opcode 249 read as `u8 isString, value, u24 key`. Same byte count. */
        OP249_VALUE_BEFORE_KEY,

        /** Opcode 13 read as a bare flag. This one DOES desync — 13,102 files. */
        OP13_NO_PAYLOAD
    }

    class Underflow(message: String) : Exception(message)

    class Reader(val d: ByteArray) {
        var p = 0

        fun need(n: Int) {
            if (p + n > d.size) throw Underflow("want $n at $p of ${d.size}")
        }

        fun u8(): Int {
            need(1); return d[p++].toInt() and 0xff
        }

        fun u16(): Int {
            need(2)
            val v = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)
            p += 2
            return v
        }

        fun u24(): Int {
            need(3)
            val v = ((d[p].toInt() and 0xff) shl 16) or
                    ((d[p + 1].toInt() and 0xff) shl 8) or (d[p + 2].toInt() and 0xff)
            p += 3
            return v
        }

        fun i32(): Int {
            need(4)
            val v = ((d[p].toInt() and 0xff) shl 24) or ((d[p + 1].toInt() and 0xff) shl 16) or
                    ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
            p += 4
            return v
        }

        /** The client's `< 0x80 ? u8 : u16 + 0x8000` reader — opcode 3 only. */
        fun smart(): Int {
            need(1)
            if ((d[p].toInt() and 0xff) < 0x80) return u8()
            return (u16() + 0x8000) and 0xffff
        }

        fun cstr(): String {
            var i = p
            while (i < d.size && d[i].toInt() != 0) i++
            if (i >= d.size) throw Underflow("unterminated string at $p")
            val s = String(d, p, i - p, Charsets.ISO_8859_1)
            p = i + 1
            return s
        }

        fun remaining() = d.size - p
    }

    /** Result of one decode: [type] is always non-null; [error] is null on a clean parse. */
    class Result(val type: SeqType, val error: String?)

    /**
     * Decode one index-20 file.
     *
     * A clean parse means the walk hit opcode 0 with the file exactly consumed.
     * Unknown opcode, underflow, trailing bytes and a missing terminator are all
     * reported rather than swallowed — the client would silently desync on each
     * of them, which is precisely how a wrong table survives unnoticed.
     */
    fun decode(
        id: Int,
        data: ByteArray,
        census: MutableList<Int>? = null,
        defect: Defect = Defect.NONE,
        /** Byte offset of each opcode read, parallel to [census]. */
        offsets: MutableList<Int>? = null
    ): Result {
        val r = Reader(data)

        var frames: List<SeqFrame>? = null
        var u02: Int? = null; var u03: IntArray? = null; var u05: Int? = null
        var u06: Int? = null; var u07: Int? = null; var u08: Int? = null
        var u09: Int? = null; var u0A: Int? = null; var u0B: Int? = null
        var u0C: List<SeqIntPair>? = null; var u0D: List<SeqSound>? = null
        var u0E = false; var u0F = false; var u10 = false; var u11 = false; var u12 = false
        var u13: IntArray? = null; var u14: IntArray? = null; var u16v: Int? = null
        var u17: IntArray? = null; var u18: Int? = null; var skelAnim: Int? = null
        var skelRange: IntArray? = null; var u1B: Int? = null
        var u70: List<SeqIntPair>? = null; var u77: IntArray? = null; var u78: IntArray? = null
        var extra: List<SeqParam>? = null
        val frameFlags = LinkedHashMap<Int, Int>()
        val framePairs = LinkedHashMap<Int, IntArray>()

        fun build(err: String?) = Result(
            SeqType(
                id, frames, u02, u03, u05, u06, u07, u08, u09, u0A, u0B, u0C, u0D,
                u0E, u0F, u10, u11, u12, u13, u14, u16v, u17, u18, skelAnim,
                skelRange, u1B, u70, u77, u78, extra, frameFlags, framePairs
            ), err
        )

        try {
            while (true) {
                if (r.remaining() == 0) return build("no terminator")
                val opAt = r.p
                val op = r.u8()
                if (op == 0) {
                    if (r.remaining() > 0) return build("trailing ${r.remaining()} byte(s)")
                    return build(null)
                }
                census?.add(op)
                offsets?.add(opAt)
                when (op) {
                    // THREE PARALLEL ARRAYS. Not n interleaved 6-byte tuples: the
                    // client fills A[i] in one loop, B[i]'s low half in a second,
                    // and ORs framefile<<16 into B[i] in a third. Same byte count
                    // either way, which is why a tuple reader parses 100% and is
                    // wrong on every record with more than one frame.
                    1 -> {
                        val n = r.u16()
                        frames = if (defect == Defect.OP1_INTERLEAVED_TUPLES) {
                            (0 until n).map { SeqFrame(r.u16(), r.u16(), r.u16()) }
                        } else {
                            val lens = IntArray(n) { r.u16() }
                            val idxs = IntArray(n) { r.u16() }
                            val files = IntArray(n) { r.u16() }
                            (0 until n).map { SeqFrame(lens[it], idxs[it], files[it]) }
                        }
                    }
                    2 -> u02 = r.u16()
                    3 -> {
                        val n = r.smart()
                        u03 = IntArray(n) { r.smart() }
                    }
                    5 -> u05 = r.u8()
                    6 -> u06 = r.u16()
                    7 -> u07 = r.u16()
                    8 -> u08 = r.u8()
                    9 -> u09 = r.u8()
                    10 -> u0A = r.u8()
                    11 -> u0B = r.u8()
                    12 -> u0C = twoArrays(r, r.u8())
                    // NOT no-payload. Reading it as a bare flag desyncs 13,102 of
                    // the old cache's 37,853 files — the single largest defect the
                    // parse rate DOES see.
                    13 -> if (defect != Defect.OP13_NO_PAYLOAD) {
                        val n = r.u16()
                        val items = ArrayList<SeqSound>(n)
                        for (i in 0 until n) {
                            val k = r.u8()
                            if (k == 0) items.add(SeqSound(null, null))
                            else {
                                val v = r.u24()
                                items.add(SeqSound(v, IntArray(k - 1) { r.u16() }))
                            }
                        }
                        u0D = items
                    }
                    14 -> u0E = true
                    15 -> u0F = true
                    16 -> u10 = true
                    17 -> u11 = true
                    18 -> u12 = true
                    // Per-FRAME writes indexed by the first operand, repeatable.
                    // The scalar keeps the last occurrence because that is what
                    // the extract and rs3.sqlite recorded; frameFlags/framePairs
                    // keep every one, which is what the client actually stores.
                    19 -> {
                        val f = r.u8(); val v = r.u8()
                        u13 = intArrayOf(f, v); frameFlags[f] = v
                    }
                    20 -> {
                        val f = r.u8(); val a = r.u16(); val b = r.u16()
                        u14 = intArrayOf(f, a, b); framePairs[f] = intArrayOf(a, b)
                    }
                    22 -> u16v = r.u8()
                    23 -> u17 = intArrayOf(r.u8(), r.u8())
                    24 -> u18 = r.u16()
                    25 -> skelAnim = r.u16()
                    26 -> skelRange = intArrayOf(r.u16(), r.u16())
                    27 -> u1B = r.u8()
                    112 -> u70 = twoArrays(r, r.u16())
                    119 -> {
                        val f = r.u16(); val v = r.u8()
                        u77 = intArrayOf(f, v); frameFlags[f] = v
                    }
                    120 -> {
                        val f = r.u16(); val a = r.u16(); val b = r.u16()
                        u78 = intArrayOf(f, a, b); framePairs[f] = intArrayOf(a, b)
                    }
                    // KEY BEFORE VALUE. u8 isString, u24 key, THEN the value.
                    // Reading the value first advances by the same count and
                    // pairs every prop with the wrong intvalue.
                    249 -> {
                        val n = r.u8()
                        val ps = ArrayList<SeqParam>(n)
                        for (i in 0 until n) {
                            val isString = r.u8()
                            if (defect == Defect.OP249_VALUE_BEFORE_KEY) {
                                // The reading this replaced. Same byte count,
                                // key and value transposed: on sequence 1348's
                                // first param (bytes 00 | 000b68 | 00000057) it
                                // yields prop=87 intvalue=747520 where the bytes
                                // say prop=2920 intvalue=87.
                                if (isString != 0) {
                                    val s = r.cstr(); ps.add(SeqParam(r.u24(), null, s))
                                } else {
                                    val v = r.i32(); ps.add(SeqParam(r.u24(), v, null))
                                }
                            } else {
                                val key = r.u24()
                                if (isString != 0) ps.add(SeqParam(key, null, r.cstr()))
                                else ps.add(SeqParam(key, r.i32(), null))
                            }
                        }
                        extra = ps
                    }
                    else -> return build("unknown opcode $op at ${r.p - 1}")
                }
            }
        } catch (e: Underflow) {
            return build("underflow: ${e.message}")
        }
    }

    /** Opcodes 12 and 112: count, then two parallel u16 arrays. */
    private fun twoArrays(r: Reader, n: Int): List<SeqIntPair> {
        val lo = IntArray(n) { r.u16() }
        val hi = IntArray(n) { r.u16() }
        return (0 until n).map { SeqIntPair(lo[it], hi[it]) }
    }
}
