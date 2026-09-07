package com.opennxt.tools.impl

import com.opennxt.Constants
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.resources.config.items.ItemFilesystemCodec
import com.opennxt.resources.config.items.ItemRecord

object ItemDecodeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val fs = SqliteFilesystem(Constants.CACHE_PATH)
        val max = ItemFilesystemCodec.getMaxId(fs)
        var ok = 0
        var absent = 0
        val failures = LinkedHashMap<String, MutableList<Int>>()
        for (id in 0..max) {
            val data = ItemFilesystemCodec.raw(fs, id)
            if (data == null) { absent++; continue }
            val rec = ItemRecord(id)
            try {
                ItemFilesystemCodec.decodeInto(rec, java.nio.ByteBuffer.wrap(data))
                ok++
            } catch (e: Exception) {
                failures.getOrPut("after opcode ${rec.trail.lastOrNull()}") { ArrayList() }.add(id)
            }
        }
        println("max=$max decoded=$ok absent=$absent failed=${failures.values.sumOf { it.size }}")
        failures.entries.sortedByDescending { it.value.size }.take(25).forEach { (k, v) ->
            println("  ${v.size} x $k   e.g. ids ${v.take(5)}")
        }
    }
}
