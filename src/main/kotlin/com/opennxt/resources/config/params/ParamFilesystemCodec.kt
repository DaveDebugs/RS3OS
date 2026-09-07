package com.opennxt.resources.config.params

import com.opennxt.ext.*
import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.FilesystemResourceCodec
import com.opennxt.resources.config.vars.ScriptVarType
import com.opennxt.util.TextUtils
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

object ParamFilesystemCodec : FilesystemResourceCodec<ParamDefinition> {
    override fun getMaxId(fs: Filesystem): Int {
        val table = fs.getReferenceTable(2) ?: return 0
        return table.archives[11]!!.files.lastKey()
    }

    override fun list(fs: Filesystem): Map<Int, ParamDefinition> {
        val result = Int2ObjectAVLTreeMap<ParamDefinition>()
        for (i in 0 until getMaxId(fs) + 1) {
            val def = load(fs, i) ?: continue
            result[i] = def
        }
        return result
    }

    override fun load(fs: Filesystem, id: Int): ParamDefinition? {
        val table = fs.getReferenceTable(2) ?: return null
        val archive = table.loadArchive(11) ?: return null
        val file = archive.files[id] ?: return null

        return decode(ByteBuffer.wrap(file.data))
    }

    /**
     * The opcode loop, split out of [load] on so it can be driven
     * from a buffer.
     */
    fun decode(buffer: ByteBuffer): ParamDefinition {
        val definition = ParamDefinition()
        while (buffer.hasRemaining()) {
            val opcode = buffer.get().toInt() and 0xff
            if (opcode == 0) return definition

            when (opcode) {
                1 -> definition.type = ScriptVarType.getByChar(TextUtils.cp1252ToChar(buffer.get()))
                2 -> definition.defaultInt = buffer.int
 // THE OTHER HALF OF THE DEFECT, left standing.
                // `membersOnly` ALREADY defaults to true, so this assignment is
                // a no-op and opcode 4 carries exactly no information through
                // this loop: 592 of the served cache's 9,472 param files carry
                // it, and all 9,472 decode to the same value. Whichever
                // polarity is right, this line cannot express it. See
                // [ParamDefinition.membersOnly] for the two candidate readings
                // and why neither is applied here.
                4 -> definition.membersOnly = true
                5 -> definition.defaultString = buffer.getString()
                101 -> {
                    val type = buffer.getSmallSmartInt()
                    val resolved = ScriptVarType.getById(type)
                    if (resolved == null) definition.unknownTypeId = type
                    else definition.type = resolved
                }
                else -> throw IllegalArgumentException("invalid ParamDefinition opcode $opcode")
            }
        }
        return definition
    }

    /**
     * The store-side opcode loop, split out of [store] on for the
     * same reason [decode] was split out of [load] on: a write path
     * nothing can drive is a write path nobody can test.
     */
    fun encode(data: ParamDefinition): ByteArray {
        var size = 4096
        while (true) {
            try {
                val buffer = ByteBuffer.allocate(size)

                buffer.put(101)
                // The raw id wins when the read path could not resolve it, so a
                // load/store round trip cannot quietly retype an unlisted param
                // as the INT placeholder ParamDefinition.type carries.
                buffer.putSmallSmartInt(data.unknownTypeId ?: data.type.id)

                if (data.defaultInt != 0) {
                    buffer.put(2)
                    buffer.putInt(data.defaultInt)
                }

                // THIS LINE IS HALF OF THE DEFECT. See ParamDefinition.membersOnly.
                // [decode] never writes `false` here, so `membersOnly` is `true`
                // for every one of the 9,472 params the served cache holds, so
                // this emits opcode 4 for all 9,472 - against the 592 that
                // actually carry it. Deliberately NOT repaired: the repair is a
                // polarity choice and the polarity is an open question.
                if (data.membersOnly) {
                    buffer.put(4)
                }

                if (data.defaultString != "null") {
                    buffer.put(5)
                    buffer.putString(data.defaultString)
                }

                buffer.put(0)

                buffer.flip()
                return buffer.toByteArray()
            } catch (e: BufferOverflowException) {
                size *= 4
            }
        }
    }

    override fun store(fs: Filesystem, id: Int, data: ParamDefinition) {
        val table = fs.getReferenceTable(2) ?: throw NullPointerException("index 2 table")
        val archive = table.loadOrCreateArchive(11) ?:
        throw NullPointerException("failed to create or get archive 11")

        archive.putFile(id, encode(data))
    }
}
