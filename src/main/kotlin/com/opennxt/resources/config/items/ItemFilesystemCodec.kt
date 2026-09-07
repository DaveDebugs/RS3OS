package com.opennxt.resources.config.items

import com.opennxt.ext.getMedium
import com.opennxt.ext.getSmartInt
import com.opennxt.ext.getString
import com.opennxt.ext.skip
import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.ResourceType
import com.opennxt.resources.config.shared.ModelSwapBlock
import java.nio.ByteBuffer

/**
 * Decodes item definitions out of cache index 19. Same approach as the npc and loc codecs, and
 * ItemDecodeProbe and ItemDecodeVerify do the same job for it.
 */
object ItemFilesystemCodec {
    private const val INDEX = 19
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

    fun load(fs: Filesystem, id: Int): ItemRecord? {
        val data = raw(fs, id) ?: return null
        return decode(id, ByteBuffer.wrap(data))
    }

    fun decode(id: Int, buf: ByteBuffer): ItemRecord = decodeInto(ItemRecord(id), buf)

    fun decodeInto(r: ItemRecord, buf: ByteBuffer): ItemRecord {
        while (buf.hasRemaining()) {
            val op = buf.get().toInt() and 0xff
            if (op == 0) return r
            read(r, op, buf)
            r.trail.add(op)
        }
        return r
    }

    private fun ByteBuffer.bigSmart(): Int = getSmartInt()

    private fun read(r: ItemRecord, op: Int, buf: ByteBuffer) {
        when (op) {
            1 -> buf.bigSmart()                                  // base model
            2 -> r.name = buf.getString()
            3 -> buf.getString()
            4, 5, 6, 7, 8 -> buf.skip(2)                         // zoom, rotation, translation
            9 -> r.baseModels = IntArray(buf.get().toInt() and 0xff) { buf.bigSmart() }
            11 -> r.stackable = true
            12 -> buf.skip(4)
            13 -> r.equipSlotId = buf.get().toInt() and 0xff
            14 -> r.equipId = buf.get().toInt() and 0xff
            15 -> r.untradeable = true
            16 -> r.members = true
            18 -> buf.skip(2)
            23, 24 -> r.maleModels[op - 23] = buf.bigSmart()
            25, 26 -> r.femaleModels[op - 25] = buf.bigSmart()
            27 -> buf.skip(1)
            in 30..34 -> r.groundActions[op - 30] = buf.getString()
            in 35..39 -> r.widgetActions[op - 35] = buf.getString()
            40, 41 -> {
                val target = if (op == 40) r.colourReplacements else r.materialReplacements
                repeat(buf.get().toInt() and 0xff) {
                    target.add((buf.short.toInt() and 0xffff) to (buf.short.toInt() and 0xffff))
                }
            }
            42, 43 -> { val n = buf.get().toInt() and 0xff; buf.skip(n) }
            44, 45 -> buf.skip(2)
            65 -> r.tradeable = true
            69 -> r.buyLimit = buf.int
            78, 79 -> buf.bigSmart()
            // Heads interleave male/female rather than running in blocks the way the models do.
            90, 92 -> r.maleHeads[(op - 90) / 2] = buf.bigSmart()
            91, 93 -> r.femaleHeads[(op - 91) / 2] = buf.bigSmart()
            94 -> r.category = buf.short.toInt() and 0xffff
            95 -> buf.skip(2)
            96 -> r.dummyItem = buf.get().toInt() and 0xff
            97, 98 -> buf.bigSmart()
            in 100..109 -> r.stackInfo[op - 100] =
                buf.bigSmart() to (buf.short.toInt() and 0xffff)
            in 110..112 -> buf.skip(2)
            113, 114 -> buf.skip(1)
            115 -> buf.skip(1)
            121, 122 -> buf.bigSmart()
            125, 126 -> buf.skip(3)
            132 -> { val n = buf.get().toInt() and 0xff; buf.skip(n * 2) }
            134 -> buf.skip(1)
            139, 140 -> buf.bigSmart()
            in 142..146 -> r.groundActionCursors[op - 142] = buf.short.toInt() and 0xffff
            in 150..154 -> r.widgetActionCursors[op - 150] = buf.short.toInt() and 0xffff
            157 -> r.unknown[op] = 1
            161, 162, 163 -> buf.skip(2)
            164 -> buf.getString()
            165 -> r.neverStackable = true
            166 -> buf.skip(2)
            167, 168 -> r.unknown[op] = 1
            169 -> buf.skip(2)
            175 -> buf.skip(2)
            177 -> r.unknown[op] = 1
            178 -> r.unknown[op] = 1
            182 -> buf.getMedium()
            179 -> buf.skip(4)
            // The item's value, as a pair of ints. ShopData reads this to price a shop.
            181 -> r.bigValue = buf.int to buf.int
            in 201..208 -> buf.getMedium()
            in 190..198 -> { buf.getMedium(); buf.skip(2) }
            249 -> {
                val n = buf.get().toInt() and 0xff
                repeat(n) {
                    val isString = buf.get().toInt() == 1
                    val prop = buf.getMedium()
                    r.params.add(prop to (if (isString) buf.getString() else buf.int))
                }
            }
            else -> throw IllegalStateException("item ${r.id}: unknown opcode $op at ${buf.position() - 1}")
        }
    }
}

/** One decoded item. */
class ItemRecord(val id: Int) {
    var name: String? = null
    var members: Boolean? = null
    var tradeable: Boolean? = null
    var stackable: Boolean? = null
    var equipSlotId: Int? = null
    var equipId: Int? = null
    var buyLimit: Int? = null
    var category: Int? = null
    var dummyItem: Int? = null
    var untradeable: Boolean? = null
    var neverStackable: Boolean? = null
    val groundActions = arrayOfNulls<String>(5)
    val widgetActions = arrayOfNulls<String>(5)
    var baseModels: IntArray? = null
    var bigValue: Pair<Int, Int>? = null
    val maleModels = arrayOfNulls<Int>(2)
    val femaleModels = arrayOfNulls<Int>(2)
    val maleHeads = arrayOfNulls<Int>(2)
    val femaleHeads = arrayOfNulls<Int>(2)
    val stackInfo = arrayOfNulls<Pair<Int, Int>>(10)
    val colourReplacements = ArrayList<Pair<Int, Int>>()
    val materialReplacements = ArrayList<Pair<Int, Int>>()
    val groundActionCursors = arrayOfNulls<Int>(5)
    val widgetActionCursors = arrayOfNulls<Int>(5)
    val params = ArrayList<Pair<Int, Any>>()
    /** Opcodes that decoded cleanly but have no name here yet, keyed by opcode. */
    val unknown = LinkedHashMap<Int, Int>()

    /** Every opcode read, in order. Only the probe uses it. */
    val trail = ArrayList<Int>()
}
