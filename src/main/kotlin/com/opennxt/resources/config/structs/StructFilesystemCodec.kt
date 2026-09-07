package com.opennxt.resources.config.structs

import com.opennxt.ext.*
import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.FilesystemResourceCodec
import com.opennxt.resources.ResourceType
import com.opennxt.resources.config.vars.BaseVarType
import com.opennxt.resources.config.vars.ScriptVarType
import com.opennxt.util.TextUtils
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

object StructFilesystemCodec : FilesystemResourceCodec<StructDefinition> {
    override fun getMaxId(fs: Filesystem): Int {
        val table = fs.getReferenceTable(22) ?: return 0
        val maxArchive = table.highestEntry() - 1
        val files = table.archives[maxArchive]!!.files.lastKey()

        return maxArchive * 32 + files
    }

    override fun list(fs: Filesystem): Map<Int, StructDefinition> {
        val result = Int2ObjectAVLTreeMap<StructDefinition>()
        for (i in 0 until getMaxId(fs) + 1) {
            val def = load(fs, i) ?: continue
            result[i] = def
        }
        return result
    }

    override fun load(fs: Filesystem, id: Int): StructDefinition? {
        val table = fs.getReferenceTable(22) ?: return null
        val archive = table.loadArchive(ResourceType.getArchive(id, 5)) ?: return null
        val file = archive.files[ResourceType.getFile(id, 5)] ?: return null

        val definition = StructDefinition()
        val buffer = ByteBuffer.wrap(file.data)
        while (buffer.hasRemaining()) {
            val opcode = buffer.get().toInt() and 0xff
            if (opcode == 0) return definition

            when (opcode) {
                249 -> {
 // `and 0xff`, matching the opcode read on
                    // line 40. Read signed, a count of 137 (0x89) becomes -119,
                    // `for (i in 0 until -119)` runs ZERO times, and the struct
                    // decodes EMPTY rather than throwing - a silent wrong answer.
                    //
                    // exactly four structs carry 137 params and hit
                    // this - 2406, 2410, 2414 and 2418 - and they do so in BOTH
                    // caches, so it is not a vintage artefact. Found because it
 // was the single survivor after the config-table
                    // other 18 were the old-vintage tables, this one was a real
                    // decoder defect that the vintage noise had been hiding.
                    val size = buffer.get().toInt() and 0xff
                    for(i in 0 until size) {
                        val isString = buffer.get() == 1.toByte()
                        definition.values[buffer.getMedium()] = if (isString) buffer.getString() else buffer.int
                    }
                }
                else -> throw IllegalArgumentException("invalid StructDefinition opcode $opcode")
            }
        }
        return definition
    }

    override fun store(fs: Filesystem, id: Int, data: StructDefinition) {
        val table = fs.getReferenceTable(22) ?: throw NullPointerException("index 22 table")
        val archive = table.loadOrCreateArchive(ResourceType.getArchive(id, 5)) ?:
        throw NullPointerException("failed to create or get archive ${ResourceType.getArchive(id, 5)}")

        var size = 4096
        while (true) {
            try {
                val buffer = ByteBuffer.allocate(size)

                buffer.put(249.toByte())
                buffer.put(data.values.size.toByte())
                data.values.forEach { (k, v) ->
                    if (v is String) {
                        buffer.put(1)
                        buffer.putMedium(k)
                        buffer.putString(v)
                    } else {
                        buffer.put(0)
                        buffer.putMedium(k)
                        buffer.putInt(v as Int)
                    }
                }

                buffer.put(0)

                buffer.flip()
                val encoded = buffer.toByteArray()
                // Index 22 splits ids 5 bits, not 8. `load` above reads
                // getFile(id, 5) out of getArchive(id, 5); this line used to
                // write getFile(id, 8), so for any id with a bit set in 5..7
                // store wrote a file number `load` never looks at (and one
                // outside the 32-file range of a struct group at all).
                // Nothing in the tree calls this codec's store today, so the
                // defect was latent - but it wrote cache bytes, so it is
                // named rather than quietly corrected.
                archive.putFile(ResourceType.getFile(id, 5), encoded)

                return
            } catch (e: BufferOverflowException) {
                size *= 4
            }
        }
    }
}