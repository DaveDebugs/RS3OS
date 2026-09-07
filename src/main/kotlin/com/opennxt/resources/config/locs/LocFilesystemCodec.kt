package com.opennxt.resources.config.locs

import com.opennxt.ext.getMedium
import com.opennxt.ext.getSmallSmartInt
import com.opennxt.ext.getSmartInt
import com.opennxt.ext.getString
import com.opennxt.ext.skip
import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.ResourceType
import com.opennxt.resources.config.shared.ModelSwapBlock
import java.nio.ByteBuffer

/**
 * Decodes loc (scenery) definitions out of cache index 16.
 *
 * Same approach as the npc codec: name the handful of fields the server reads, and get the payload
 * size right for everything else so the stream stays in sync. LocDecodeProbe and LocDecodeVerify
 * are the two tools that keep it honest.
 */
object LocFilesystemCodec {
    private const val INDEX = 16
    private const val SHIFT = 8

    fun getMaxId(fs: Filesystem): Int {
        val table = fs.getReferenceTable(INDEX) ?: return 0
        val maxArchive = table.highestEntry() - 1
        val files = table.archives[maxArchive]!!.files.lastKey()
        return maxArchive * (1 shl SHIFT) + files
    }

    fun raw(fs: Filesystem, id: Int): ByteArray? {
        val table = fs.getReferenceTable(INDEX) ?: return null
        val archive = table.loadArchive(ResourceType.getArchive(id, SHIFT)) ?: return null
        return archive.files[ResourceType.getFile(id, SHIFT)]?.data
    }

    fun load(fs: Filesystem, id: Int): LocRecord? {
        val data = raw(fs, id) ?: return null
        return decode(id, ByteBuffer.wrap(data))
    }

    fun decode(id: Int, buf: ByteBuffer): LocRecord = decodeInto(LocRecord(id), buf)

    fun decodeInto(r: LocRecord, buf: ByteBuffer): LocRecord {
        while (buf.hasRemaining()) {
            val op = buf.get().toInt() and 0xff
            if (op == 0) return r
            read(r, op, buf)
            r.trail.add(op)
        }
        return r
    }

    private fun ByteBuffer.bigSmart(): Int = getSmartInt()

    /** One or two bytes, signed and biased. Used for the bounding offsets behind opcode 201. */
    private fun ByteBuffer.signedSmart(): Int =
        if ((get(position()).toInt() and 0x80) != 0) (short.toInt() and 0x7fff) - 0x4000
        else (get().toInt() and 0xff) - 0x40

    private fun read(r: LocRecord, op: Int, buf: ByteBuffer) {
        when (op) {
            1, 5 -> repeat(buf.get().toInt() and 0xff) {
                val type = if (op == 1) buf.get().toInt() and 0xff else -1
                val values = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
                r.models.add(type to values)
            }
            2 -> r.name = buf.getString()
            14 -> r.width = buf.get().toInt() and 0xff
            15 -> r.length = buf.get().toInt() and 0xff
            17 -> r.walkable = true
            18 -> r.allowsLineOfSight = true
            19 -> r.unknown[op] = buf.get().toInt() and 0xff
            21, 22, 23 -> r.unknown[op] = 1
            24 -> r.animation = buf.bigSmart()
            27 -> r.blocksMovement = true
            28 -> buf.skip(1)
            29 -> buf.skip(1)
            in 30..34 -> r.actions[op - 30] = buf.getString()
            39 -> buf.skip(1)
            40, 41 -> {
                val target = if (op == 40) r.colourReplacements else r.materialReplacements
                repeat(buf.get().toInt() and 0xff) {
                    target.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
                }
            }
            42 -> { val n = buf.get().toInt() and 0xff; buf.skip(n) }
            44, 45 -> buf.skip(2)
            62 -> r.unknown[op] = 1
            64 -> r.unknown[op] = 1
            65, 66, 67 -> buf.skip(2)
            69 -> buf.skip(1)
            70, 71, 72 -> buf.skip(2)
            73 -> r.obstructsGround = true
            74 -> r.unknown[op] = 1
            75 -> buf.skip(1)
            77, 92 -> {
                val m = LocMorph()
                m.varbit = buf.short.toInt() and 0xffff
                m.varp = buf.short.toInt() and 0xffff
                if (op == 92) m.extra = buf.bigSmart()
                val n = buf.getSmallSmartInt()
                m.options = IntArray(n) { buf.bigSmart() }
                m.default = buf.bigSmart()                     // used when none match
                if (op == 77) r.morph1 = m else r.morph2 = m
            }
            78 -> r.sound = (buf.short.toInt() and 0xffff) to (buf.get().toInt() and 0xff)
            79 -> {
                buf.skip(2); buf.skip(2); buf.skip(1)
                val n = buf.get().toInt() and 0xff
                buf.skip(n * 2)
            }
            81 -> buf.skip(1)
            82 -> r.unknown[op] = 1
            88 -> r.isMembers = true
            89 -> r.unknown[op] = 1
            91 -> r.unknown[op] = 1
            93 -> buf.skip(2)
            94 -> r.unknown[op] = 1
            95 -> buf.skip(2)
            97 -> r.unknown[op] = 1
            98 -> r.unknown[op] = 1
            99, 100 -> { buf.skip(1); buf.skip(2) }
            101 -> buf.skip(1)
            102 -> buf.skip(2)
            103 -> r.unknown[op] = 1
            104 -> buf.skip(1)
            105 -> r.unknown[op] = 1
            106 -> {
                val n = buf.get().toInt() and 0xff
                repeat(n) { buf.bigSmart(); buf.skip(1) }
            }
            107 -> buf.skip(2)
            108, 109, 110 -> r.unknown[op] = 1
            in 150..154 -> r.membersActions[op - 150] = buf.getString()
            160 -> r.quests = IntArray(buf.get().toInt() and 0xff) { buf.short.toInt() and 0xffff }
            162 -> buf.skip(4)
            163 -> buf.skip(4)
            164, 165, 166 -> buf.skip(2)
            167 -> buf.skip(2)
            168, 169 -> r.unknown[op] = 1
            170, 171 -> buf.getSmallSmartInt()
            173 -> buf.skip(4)
            177 -> r.unknown[op] = 1
            178 -> buf.skip(1)
            186 -> buf.skip(1)
            188, 189 -> r.unknown[op] = 1
            in 190..194 -> r.actionCursors[op - 190] = buf.short.toInt() and 0xffff
            195 -> buf.skip(2)
            196, 197 -> buf.skip(1)
            198, 199 -> r.unknown[op] = 1
            201 -> repeat(6) { buf.signedSmart() }
            202 -> buf.bigSmart()
            203 -> r.unknown[op] = 1
            205 -> with(ModelSwapBlock) { buf.readModelSwapBlock() }
            204 -> repeat(buf.get().toInt() and 0xff) { buf.skip(27) }
            206 -> buf.skip(buf.short.toInt() and 0xffff)
            249 -> {
                val n = buf.get().toInt() and 0xff
                repeat(n) {
                    val isString = buf.get().toInt() == 1
                    val prop = buf.getMedium()
                    r.params.add(prop to (if (isString) buf.getString() else buf.int))
                }
            }
            else -> throw IllegalStateException("loc ${r.id}: unknown opcode $op at ${buf.position() - 1}")
        }
    }
}

/** One decoded loc. */
class LocRecord(val id: Int) {
    var name: String? = null
    var width: Int? = null
    var length: Int? = null
    var blocksMovement: Boolean? = null
    var walkable: Boolean? = null
    var animation: Int? = null
    var isMembers: Boolean? = null
    var allowsLineOfSight: Boolean? = null
    var obstructsGround: Boolean? = null
    val actions = arrayOfNulls<String>(5)
    val membersActions = arrayOfNulls<String>(5)
    val actionCursors = arrayOfNulls<Int>(5)
    val models = ArrayList<Pair<Int, IntArray>>()
    val colourReplacements = ArrayList<Pair<Int, Int>>()
    val materialReplacements = ArrayList<Pair<Int, Int>>()
    var sound: Pair<Int, Int>? = null
    var quests: IntArray? = null
    var morph1: LocMorph? = null
    var morph2: LocMorph? = null
    val params = ArrayList<Pair<Int, Any>>()
    /** Opcodes that decoded cleanly but have no name here yet, keyed by opcode. */
    val unknown = LinkedHashMap<Int, Int>()

    /** Every opcode read, in order. Only the probe uses it. */
    val trail = ArrayList<Int>()
}

/** A varbit or varp that swaps one loc for another - opcode 77, or 92 with a second selector. */
class LocMorph {
    var varbit: Int = 0
    var varp: Int = 0
    var extra: Int? = null
    var options: IntArray = IntArray(0)
    var default: Int = 0
}
