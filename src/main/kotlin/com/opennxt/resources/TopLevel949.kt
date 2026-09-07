package com.opennxt.resources

import com.opennxt.Constants
import mu.KotlinLogging

/**
 * THE BUILD-949 TOPLEVEL WINDOW TABLE, READ FROM THE CACHE INSTEAD OF A RECORDING.
 */
object TopLevel949 {
    private val logger = KotlinLogging.logger { }

    /** The 949 toplevel interface. Every mount below is a component of it. */
    const val TOPLEVEL = 1477

    /**
     * One window as the cache declares it.
     *
     * @param mount   the primary mount component (struct param 3503)
     * @param struct  the struct id it was read from, so any row can be traced back
     * @param name    the cache's own label for the window (param 3493)
     * @param layers  every 1477 component this window owns, mount first
     * @param content interface ids the cache names as this window's content,
     *                empty when the cache does not say - which is not a gap in
     *                this table, it is the cache declining to answer
     */
    data class Window(
        val mount: Int,
        val struct: Int,
        val name: String,
        val layers: List<Int>,
        val content: List<Int>,
    )

    private val windows: List<Window> by lazy { load() }

    private fun load(): List<Window> {
        val file = Constants.DATA_PATH.resolve("prot949").resolve("toplevel_949.tsv").toFile()
        if (!file.isFile) {
            logger.info {
                "TopLevel949: no ${file.path}. The cache-derived window table is absent, so nothing " +
                    "here can be consulted; regenerate it with tools/949/derive_toplevel.py. " +
                    "This is not fatal - the hand tables still stand - but it is a lost check."
            }
            return emptyList()
        }
        val out = ArrayList<Window>()
        var malformed = 0
        try {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("mount\t")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 3) { malformed++; return@forEachLine }
                val mount = p[0].toIntOrNull()
                val struct = p[1].toIntOrNull()
                if (mount == null || struct == null) { malformed++; return@forEachLine }
                fun ints(s: String?) =
                    s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                out.add(Window(mount, struct, p[2], ints(p.getOrNull(3)), ints(p.getOrNull(4))))
            }
        } catch (e: Exception) {
            logger.warn(e) { "TopLevel949: could not read ${file.path}; falling back to the hand tables" }
            return emptyList()
        }
        if (malformed > 0) logger.warn { "TopLevel949: $malformed malformed line(s) skipped in ${file.name}" }
        logger.info {
            "TopLevel949: ${out.size} windows read from the cache-derived table " +
                "(${out.count { it.content.isNotEmpty() }} carry a content interface; " +
                "${out.sumOf { it.layers.size }} layer components in total)"
        }
        return out
    }

    /** Every window the cache declares, ordered by mount component. */
    fun all(): List<Window> = windows

    /** The window mounted at `1477:component`, or null. */
    fun byMount(component: Int): Window? = windows.firstOrNull { it.mount == component }

    /** Windows whose cache name matches, case-insensitively. Several may share a name. */
    fun byName(name: String): List<Window> = windows.filter { it.name.equals(name, ignoreCase = true) }

    /** The window that owns this 1477 component, whether it is the mount or a later layer. */
    fun owning(component: Int): Window? = windows.firstOrNull { component in it.layers }

    /** Windows the cache says host this interface. Empty is normal - see [Window.content]. */
    fun hosting(interfaceId: Int): List<Window> = windows.filter { interfaceId in it.content }

    /** `1477:101 'Backpack'`, or the bare hash when the table is absent. */
    fun describe(component: Int): String {
        val w = owning(component) ?: return "$TOPLEVEL:$component"
        val which = if (w.mount == component) "" else " layer of"
        return "$TOPLEVEL:$component$which '${w.name}'"
    }

    fun size(): Int = windows.size
}
