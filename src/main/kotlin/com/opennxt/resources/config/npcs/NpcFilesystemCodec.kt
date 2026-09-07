package com.opennxt.resources.config.npcs

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
 * Decodes npc definitions out of cache index 18.
 *
 * Only the fields the server reads are given names. The rest are skipped by size, and the size is
 * the part that has to be right: get one wrong and the stream desyncs, which surfaces straight away
 * as an unknown opcode rather than as a quietly wrong value somewhere further down the record.
 *
 * NpcDecodeProbe runs this over every record in a cache and reports anything that will not parse.
 * NpcDecodeVerify compares the result field by field against a known-good rs3.sqlite.
 */
object NpcFilesystemCodec {
    private const val INDEX = 18
    private const val SHIFT = 7

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

    fun load(fs: Filesystem, id: Int): NpcRecord? {
        val data = raw(fs, id) ?: return null
        return decode(id, ByteBuffer.wrap(data))
    }

    fun decode(id: Int, buf: ByteBuffer): NpcRecord = decodeInto(NpcRecord(id), buf)

    fun decodeInto(r: NpcRecord, buf: ByteBuffer): NpcRecord {
        while (buf.hasRemaining()) {
            val op = buf.get().toInt() and 0xff
            if (op == 0) return r
            read(r, op, buf)
            r.trail.add(op)
        }
        return r
    }

    private fun ByteBuffer.bigSmart(): Int = getSmartInt()

    /** One or two bytes, signed and biased: the cache uses it for offsets that can go negative. */
    private fun ByteBuffer.signedSmart(): Int =
        if ((get(position()).toInt() and 0x80) != 0) (short.toInt() and 0x7fff) - 0x4000
        else (get().toInt() and 0xff) - 0x40

    private fun read(r: NpcRecord, op: Int, buf: ByteBuffer) {
        when (op) {
            1 -> r.models = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
            2 -> r.name = buf.getString()
            11 -> r.unknown[op] = buf.get().toInt() and 0xff
            12 -> r.boundSize = buf.get().toInt() and 0xff
            in 30..34 -> r.actions[op - 30] = buf.getString()
            in 35..39 -> r.membersActions[op - 35] = buf.getString()
            40 -> repeat(buf.get().toInt() and 0xff) {
                r.colourReplacements.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
            }
            41 -> repeat(buf.get().toInt() and 0xff) {
                r.materialReplacements.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
            }
            42 -> { val n = buf.get().toInt() and 0xff; buf.skip(n) }
            44, 45 -> buf.skip(2)
            60 -> r.headModels = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
            93 -> r.drawMapDot = false
            95 -> r.combat = buf.short.toInt() and 0xffff
            97 -> r.scaleXZ = buf.short.toInt() and 0xffff
            98 -> r.scaleY = buf.short.toInt() and 0xffff
            99 -> r.unknown[op] = 1
            100 -> r.ambience = buf.get().toInt()
            101 -> r.contrast = buf.get().toInt()
            102 -> r.headIconData = buf.short.toInt() and 0xffff
            103 -> r.unknown[op] = buf.short.toInt() and 0xffff
            106, 118 -> {
                val m = Morph()
                m.varbit = buf.short.toInt() and 0xffff
                m.varp = buf.short.toInt() and 0xffff
                if (op == 118) m.extra = buf.short.toInt() and 0xffff
                val n = buf.getSmallSmartInt()                // long option lists spill into two bytes
                m.options = IntArray(n) { buf.short.toInt() and 0xffff }
                m.default = buf.short.toInt() and 0xffff
                m.trailing = buf.short.toInt() and 0xffff
                if (op == 106) r.morph1 = m else r.morph2 = m
            }
            107 -> r.unknown[op] = 1
            109 -> r.slowWalk = false
            111 -> r.unknown[op] = 1
            113 -> buf.skip(4)
            114 -> buf.skip(2)
            119 -> r.movementCapabilities = buf.get().toInt()
            121 -> { val n = buf.get().toInt() and 0xff; buf.skip(n * 4) }
            122 -> buf.bigSmart()
            123 -> buf.skip(2)
            125 -> buf.skip(1)
            127 -> r.animationGroup = buf.short.toInt() and 0xffff
            128 -> r.movementType = buf.get().toInt() and 0xff
            134 -> r.ambientSound = IntArray(5) {
                if (it == 4) buf.get().toInt() and 0xff else buf.short.toInt() and 0xffff
            }
            135, 136 -> { buf.skip(1); buf.skip(2) }
            137 -> r.attackCursor = buf.short.toInt() and 0xffff
            140 -> buf.skip(1)
            141 -> r.unknown[op] = 1
            142 -> buf.skip(2)
            143 -> r.unknown[op] = 1
            in 150..154 -> r.membersActions[op - 150] = buf.getString()
            155 -> buf.skip(4)
            158, 159 -> r.unknown[op] = 1
            160 -> { val n = buf.get().toInt() and 0xff; buf.skip(n * 2) }
            162 -> r.unknown[op] = 1
            163 -> buf.skip(1)
            164 -> buf.skip(4)
            165 -> buf.skip(1)
            168 -> buf.skip(1)
            169 -> r.unknown[op] = 1
            in 170..174 -> r.actionCursors[op - 170] = buf.short.toInt() and 0xffff
            175 -> buf.skip(2)
            178 -> r.unknown[op] = 1
            183 -> r.unknown[op] = buf.get().toInt() and 0xff
            179 -> repeat(6) { buf.signedSmart() }
            180 -> buf.skip(2)
            182 -> r.unknown[op] = 1
            184 -> r.unknown[op] = buf.get().toInt() and 0xff
            185 -> r.unknown[op] = 1
            186 -> with(ModelSwapBlock) { buf.readModelSwapBlock() }
            219 -> r.unknown[op] = buf.get().toInt() and 0xff
            249 -> {
                val n = buf.get().toInt() and 0xff
                repeat(n) {
                    val isString = buf.get().toInt() == 1
                    val prop = buf.getMedium()
                    // The cache does hand out the same prop twice on a few npcs, so this is a list.
                    r.params.add(prop to (if (isString) buf.getString() else buf.int))
                }
            }
            253 -> buf.skip(1)
            else -> throw IllegalStateException("npc $${r.id}: unknown opcode $op at ${buf.position() - 1}")
        }
    }
}

/** One decoded npc. Fields the server never asks for are left out rather than guessed at. */
class NpcRecord(val id: Int) {
    var name: String? = null
    var boundSize: Int? = null
    var combat: Int? = null
    var movementType: Int? = null
    var movementCapabilities: Int? = null
    var animationGroup: Int? = null
    var attackCursor: Int? = null
    var scaleXZ: Int? = null
    var scaleY: Int? = null
    var ambience: Int? = null
    var contrast: Int? = null
    var drawMapDot: Boolean? = null
    var slowWalk: Boolean? = null
    var headIconData: Int? = null
    var ambientSound: IntArray? = null
    var morph1: Morph? = null
    var morph2: Morph? = null
    val colourReplacements = ArrayList<Pair<Int, Int>>()
    val materialReplacements = ArrayList<Pair<Int, Int>>()
    var models: IntArray? = null
    var headModels: IntArray? = null
    val actions = arrayOfNulls<String>(5)
    val membersActions = arrayOfNulls<String>(5)
    val actionCursors = arrayOfNulls<Int>(5)
    val params = ArrayList<Pair<Int, Any>>()
    /** Opcodes that decoded cleanly but have no name here yet, keyed by opcode. */
    val unknown = LinkedHashMap<Int, Int>()

    /** Every opcode read, in order. Only the probe uses it, to say where a bad record went wrong. */
    val trail = ArrayList<Int>()
}

/** A varbit or varp that swaps one record for another - opcode 106, or 118 with a second selector. */
class Morph {
    var varbit: Int = 0
    var varp: Int = 0
    var extra: Int? = null
    var options: IntArray = IntArray(0)
    var default: Int = 0
    var trailing: Int = 0
}
