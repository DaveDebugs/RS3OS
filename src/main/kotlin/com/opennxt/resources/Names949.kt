package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

/**
 * BUILD-949 INTERFACE NAMES, for the log.
 *
 * WHY. Every interface line this server writes is a bare number, and reading them back is the slowest
 * part of every debugging session in this server: `IF_BUTTON1 interface=1466 component=7` says
 * nothing, `IF_BUTTON1 interface=1466 (toplevel_v2_stats) component=7` says the operator clicked a
 * skill. The names are already on disk in `data/prot949/interface_names_949.sym` - 1,881 of them,
 * one `<id>\t<name>` per line - and nothing was reading the file.
 *
 * This is a LOG AID and nothing else. No behaviour may branch on a name: the file is a convenience
 * export, not protocol evidence, and a missing entry has to be harmless. Every accessor degrades to
 * the number.
 */
object Names949 {
    private val logger = KotlinLogging.logger { }

    private val byId: Map<Int, String> by lazy { load() }

    private fun load(): Map<Int, String> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("interface_names_949.sym").toFile()
        if (!file.isFile) {
            logger.info { "Names949: no ${file.path} - interface ids will print as bare numbers" }
            return emptyMap()
        }
        val out = HashMap<Int, String>()
        try {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val id = line.substring(0, tab).trim().toIntOrNull() ?: return@forEachLine
                val name = line.substring(tab + 1).trim()
                if (name.isNotEmpty()) out[id] = name
            }
        } catch (e: Exception) {
            logger.warn(e) { "Names949: could not read ${file.path}; ids will print as bare numbers" }
            return emptyMap()
        }
        logger.info { "Names949: ${out.size} build-949 interface names loaded for logging" }
        return out
    }

    /** The interface's name, or null when this cache export does not carry one. */
    fun interfaceName(id: Int): String? = byId[id]

    /** `1466 (toplevel_v2_stats)`, or just `1466`. Safe on any id, including -1. */
    fun iface(id: Int): String {
        val name = byId[id] ?: return id.toString()
        return "$id ($name)"
    }

    /** `1466:7 (toplevel_v2_stats)`, or just `1466:7`. */
    fun component(id: Int, component: Int): String {
        val name = byId[id] ?: return "$id:$component"
        return "$id:$component ($name)"
    }

    /** The same, from a packed `(interface << 16) | component` hash. */
    fun hash(hash: Int): String = component((hash shr 16) and 0xffff, hash and 0xffff)

    /** How many names are loaded. For the checks. */
    fun size(): Int = byId.size
}
