package com.opennxt.model.entity.updating

/**
 * What the extended-info section of a PLAYER_INFO or NPC_INFO send ACTUALLY
 * carried, per send, with running totals per block type.
 *
 * ============================================================================
 * WHY THIS EXISTS: THE BLINDNESS IT REMOVES
 * ============================================================================
 * The instrumentation this replaces was
 *
 *     if (sends < 3) { sends++; logger.info { ... } }
 *
 * in [NpcInfoEncoder.writeTo]. A live 122-packet session therefore produced
 * THREE log lines, all from the first three ticks - i.e. from before anything
 * interesting had happened - and the question "did a hit splat ever reach the
 * wire?" could not be answered from the log at all. A cap on instrumentation is
 * indistinguishable from a cap on the thing being instrumented.
 *
 * The replacement is the other way round: SILENT when a send carries no
 * extended-info block (which is the overwhelming majority - a stationary npc
 * costs one bit and no bytes), and one line for every send that carries at
 * least one, naming the block types, the entity index each rode on, and the
 * byte length of the section. Combat queues one block per engage and one per
 * landed hit, so the volume is bounded by the events themselves.
 *
 * ============================================================================
 * WHAT A NUMBER FROM HERE PROVES, AND WHAT IT DOES NOT
 * ============================================================================
 * [record] is called from inside the encoders, at the point where the bytes
 * have ALREADY been written into the outgoing buffer - after
 * `NpcUpdates.encode` returned true, after `packUpdateBlock` wrote its mask.
 * So a non-zero [count] for a block type means those bytes are in the packet
 * that is handed to `client.write`.
 *
 * It does NOT prove the client parsed them, and nothing here should be read as
 * saying so: the socket, the encoder pipeline and the client's own reader are
 * all downstream. What it does settle, which was previously unanswerable, is
 * WHICH SIDE of the encoder a missing block went missing on.
 *
 * Counters are cumulative for the life of the process and keyed
 * `"<kind>.<BLOCK>"` (e.g. `npc.HITS`, `player.HIT`), so a check can assert an
 * exact number and a deliberate mutation upstream changes it.
 */
object ExtendedInfoTrace {

    /** One entity's extended-info record inside one send. */
    data class Entry(
        /** "npc" or "player" - which encoder wrote it. */
        val kind: String,
        /** NPC_INFO slot index, or PLAYER_INFO entity index. */
        val index: Int,
        /** Block type names, in the order they were written. */
        val blocks: List<String>,
        /** Bytes this record occupies, filler + mask + bodies. */
        val bytes: Int
    ) {
        override fun toString() = "$kind[$index]=${blocks.joinToString(",")}/${bytes}B"
    }

    /**
     * One send's whole extended-info section. [bytes] is the section total,
     * which is the sum of the entries' bytes plus nothing - there is no
     * envelope, which is exactly why a block written out of order corrupts the
     * remainder of the packet rather than one block.
     */
    data class Report(val kind: String, val viewer: String, val entries: List<Entry>, val bytes: Int) {
        fun isEmpty(): Boolean = entries.isEmpty()

        /** Every block type in this send, deduplicated, for a one-line log. */
        fun blockTypes(): List<String> = entries.flatMap { it.blocks }.distinct()

        override fun toString() =
            "${entries.size} ${if (kind == "npc") "npc" else "player"}(s), $bytes byte(s): " +
                entries.joinToString(" ")
    }

    private val counts = LinkedHashMap<String, Int>()
    private val sendsWithExtended = LinkedHashMap<String, Int>()
    private val bytesTotal = LinkedHashMap<String, Int>()
    private val lastReports = LinkedHashMap<String, Report>()

    /**
     * Files one send. An EMPTY report is still counted as a send with no
     * extended info (it moves nothing), so "no line in the log" and "the
     * encoder was never called" stay distinguishable through [totalSends].
     */
    @Synchronized
    fun record(report: Report) {
        totalSends[report.kind] = (totalSends[report.kind] ?: 0) + 1
        if (report.isEmpty()) return
        sendsWithExtended[report.kind] = (sendsWithExtended[report.kind] ?: 0) + 1
        bytesTotal[report.kind] = (bytesTotal[report.kind] ?: 0) + report.bytes
        for (entry in report.entries) {
            for (block in entry.blocks) {
                val key = "${report.kind}.$block"
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        lastReports[report.kind] = report
    }

    private val totalSends = LinkedHashMap<String, Int>()

    /** How many of `kind`'s block type [block] have been written, ever. */
    @Synchronized
    fun count(kind: String, block: String): Int = counts["$kind.$block"] ?: 0

    /** Every counter, for a report line or a check that wants the whole picture. */
    @Synchronized
    fun counts(): Map<String, Int> = LinkedHashMap(counts)

    @Synchronized
    fun sendsWithExtended(kind: String): Int = sendsWithExtended[kind] ?: 0

    @Synchronized
    fun totalSends(kind: String): Int = totalSends[kind] ?: 0

    @Synchronized
    fun extendedBytes(kind: String): Int = bytesTotal[kind] ?: 0

    @Synchronized
    fun lastReport(kind: String): Report? = lastReports[kind]

    /** Drops everything. For checks that drive several scenarios in one process. */
    @Synchronized
    fun reset() {
        counts.clear()
        sendsWithExtended.clear()
        bytesTotal.clear()
        lastReports.clear()
        totalSends.clear()
    }

    const val NPC = "npc"
    const val PLAYER = "player"
}
