package com.opennxt.model.definitions

import com.opennxt.filesystem.Filesystem
import mu.KotlinLogging

/**
 * Cache index 20 (sequences / animations), loaded at boot.
 */
object SeqDefinitions {
    private val logger = KotlinLogging.logger { }
    private const val INDEX = 20

    private val definitions = mutableMapOf<Int, SeqType>()

    fun get(id: Int): SeqType? = definitions[id]

    fun size(): Int = definitions.size

    fun load(fs: Filesystem) {
        val table = fs.getReferenceTable(INDEX)
        if (table == null) {
            logger.warn { "No reference table for index $INDEX (Sequences)" }
            return
        }

        definitions.clear()
        var loaded = 0
        var failed = 0
        val firstFailures = ArrayList<String>()
        for (a in table.archives.keys.sorted()) {
            val arc = try { table.loadArchive(a) } catch (e: Exception) { null } ?: continue
            for ((fid, f) in arc.files) {
                val d = f.data ?: continue
                val id = a * 128 + fid

                val res = SeqCodec.decode(id, d)
                if (res.error != null) {
                    failed++
                    if (firstFailures.size < 5) firstFailures.add("$id: ${res.error}")
                    // Kept anyway: a partially decoded record is still better
                    // evidence than an absent one, and the count above is what
                    // says the table is wrong.
                }
                definitions[id] = res.type
                loaded++
            }
        }
        logger.info { "Loaded $loaded sequence definitions from cache ($failed did not decode cleanly)." }
        if (failed > 0) {
            logger.warn { "Sequence decode failures, first ${firstFailures.size}: $firstFailures" }
        }
    }
}
