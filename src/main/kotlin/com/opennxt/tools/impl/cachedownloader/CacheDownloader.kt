package com.opennxt.tools.impl.cachedownloader

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.opennxt.Constants
import com.opennxt.ext.getCrc32
import com.opennxt.filesystem.ChecksumTable
import com.opennxt.filesystem.Container
import com.opennxt.filesystem.Filesystem
import com.opennxt.filesystem.ReferenceTable
import com.opennxt.filesystem.sqlite.SqliteFilesystem
import com.opennxt.tools.Tool
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.system.exitProcess

class CacheDownloader : Tool("cache-downloader", "Updates / downloads the cache from Jagex' JS5 servers") {
    private val ip by option(help = "Live js5 server ip").default("content.runescape.com")
    private val port by option(help = "Live js5 server port").int().default(43594)
    private val numJs5Clients by option(help = "The amount of concurrent js5 connections").int().default(1)
    private val numHttpClients by option(help = "The max amount of concurrent HTTP connections").int().default(3)
    private val ioThreads by option(help = "The number of I/O threads for cache-related operations").int().default(8)
    private val checkThreads by option(help = "The number of I/O threads for checking which files require updating").int()
        .default(4)

    private lateinit var cache: Filesystem

    private lateinit var checkerExecutor: ExecutorService

    private lateinit var clientPool: Js5ClientPool
    private lateinit var checksumTable: ChecksumTable

    private lateinit var requestHandler: Js5RequestHandler

    fun request(priority: Boolean, index: Int, archive: Int): Js5RequestHandler.ArchiveRequest {
        if (index == 255) {
            val request = Js5RequestHandler.ArchiveRequest(index, archive, priority)

            return request
        } else {
            TODO("Non-255 requests")
        }
    }

    private fun downloadChecksumTable() {
        val request = clientPool.addRequest(true, 255, 255)
            ?: throw IllegalStateException("failed to request [255,255]")
        if (!request.awaitCompletion(30, TimeUnit.SECONDS)) {
            logger.error { "Took more than 30 seconds to download the checksum table. Exiting." }
            exitProcess(1)
        }

        logger.info { "Finished downloading checksum table!" }
        checksumTable = ChecksumTable.decode(ByteBuffer.wrap(Container.decode(request.buffer!!).data))
        checksumTable.entries.forEachIndexed { index, entry ->
            logger.info { "checksum for index $index = $entry" }
        }
    }

    /**
     * Makes sure every index the live checksum table declares actually exists
     * locally, whether it sits past the end of the cache or inside a hole.
     *
     * The original only grew the tail:
     *
     *     for (i in cache.numIndices() until checksumTable.entries.size) cache.createIndex(i)
     *
     * which silently does nothing on a sparse cache. A measured example: a live
     * NXT cache held 40 indices with a highest id of 66, so numIndices() reports
     * 67 and the loop body never runs - while indices 0, 4, 6, 7, 9, 11, 15, 25,
     * 30..39, 43..46, 50, 51, 53, 63 and 64 are all still missing. Every write
     * aimed at one of those is then dropped on the floor, because
     * SqliteFilesystem's writers are null-safe by design (`indices[i]?.put...`).
     * The download reports success and the cache does not change.
     *
     * Iterating the checksum table by INDEX rather than by count fixes both the
     * tail and the holes. Entries with crc == 0 and version == 0 are skipped:
     * that is how the live server marks a slot carrying no content, and creating
     * a file for one leaves behind an empty database that later runs then have
     * to keep explaining.
     */
    private fun createNewIndices() {
        var created = 0
        var skipped = 0

        checksumTable.entries.forEachIndexed { index, entry ->
            if (entry.crc == 0 && entry.version == 0) {
                skipped++
                return@forEachIndexed
            }

            val absent = cache.readReferenceTable(index) == null && !cache.exists(index, 0)
            cache.createIndex(index)
            if (absent) {
                created++
                logger.info { "Index $index is declared by the live checksum table but was absent locally - created." }
            }
        }

        logger.info {
            "Index check done: ${checksumTable.entries.size} declared by the server, " +
                "$skipped empty (crc=0 version=0), $created created locally, " +
                "cache now spans ${cache.numIndices()} slots."
        }
    }

    private fun updateReferenceTables() {
        val pending = HashSet<Js5RequestHandler.ArchiveRequest>()

        checksumTable.entries.forEachIndexed { index, entry ->
            if (entry.crc == 0 && entry.version == 0) return@forEachIndexed

            val existingRaw = cache.readReferenceTable(index)
            if (existingRaw == null) {
                logger.info { "Reference table for index $index missing, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            val crc = existingRaw.getCrc32()
            if (entry.crc != crc) {
                logger.info { "CRC mismatch in reference table for index $index, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            val existing = ReferenceTable(cache, index)
            existing.decode(ByteBuffer.wrap(Container.decode(existingRaw).data))

            if (existing.version != entry.version) {
                logger.info { "Version mismatch in reference table for index $index, adding to downloads..." }
                pending += clientPool.addRequest(true, 255, index)
                    ?: throw IllegalStateException("Failed to add reference table request $index")
                return@forEachIndexed
            }

            logger.info { "Reference table for index $index is up-to-date." }
        }

        // A rejected table is skipped, not fatal.
        //
        // Every failure here used to be exitProcess(1), which is the wrong shape
        // for a REPAIR run. This tool is normally pointed at a cache that is
        // already broken in some way, and the tables are downloaded as one batch
        // - so the twentieth table timing out threw away the nineteen that had
        // already arrived and left the cache exactly as broken as it started.
        // Worse, "Exiting." after a partial write is indistinguishable, from the
        // outside, from the tool having corrupted something.
        //
        // The integrity gates themselves are kept exactly as strict: a table
        // whose CRC or version disagrees with the checksum table is NOT written,
        // because writing it would put content in the cache that the server does
        // not vouch for. It is the blast radius that changes - one bad table
        // costs that one index, and the count is reported at the end so a
        // partial repair is never mistaken for a complete one.
        var saved = 0
        val failures = LinkedHashMap<Int, String>()

        pending.forEach { request ->
            val index = request.archive

            if (!request.awaitCompletion(30, TimeUnit.SECONDS)) {
                failures[index] = "timed out after 30s"
                return@forEach
            }

            val buffer = request.buffer
            if (buffer == null) {
                failures[index] = "completed with a null buffer"
                return@forEach
            }

            val crc = buffer.getCrc32()
            val entry = checksumTable.entries[index]
            if (crc != entry.crc) {
                failures[index] = "CRC mismatch (server checksum table says ${entry.crc}, downloaded bytes are $crc)"
                return@forEach
            }

            val container = try {
                Container.decode(buffer)
            } catch (e: Exception) {
                failures[index] = "container failed to decode: $e"
                return@forEach
            }

            val referenceTable = ReferenceTable(cache, index)
            try {
                referenceTable.decode(ByteBuffer.wrap(container.data))
            } catch (e: Exception) {
                failures[index] = "reference table failed to decode: $e"
                return@forEach
            }

            if (referenceTable.version != entry.version) {
                failures[index] = "version mismatch (expected ${entry.version}, got ${referenceTable.version})"
                return@forEach
            }

            cache.writeReferenceTable(index, buffer.array(), container.version, crc)
            saved++
            logger.info { "Saved reference table for index $index (${referenceTable.archives.size} archives)." }
        }

        logger.info { "Reference tables: ${pending.size} requested, $saved saved, ${failures.size} rejected." }
        if (failures.isNotEmpty()) {
            logger.warn { "-------------------------------------------------------------" }
            logger.warn { " These reference tables were NOT written. The indices below" }
            logger.warn { " remain unserveable and their archives cannot be verified:" }
            failures.forEach { (index, why) -> logger.warn { "   index $index: $why" } }
            logger.warn { "-------------------------------------------------------------" }
        }
    }

    override fun runTool() {
        check(numJs5Clients > 0) { "num-js5-clients must be greater than 0" }
        check(numHttpClients > 0) { "num-http-clients must be greater than 0" }

        logger.info { "Starting download from $ip:$port" }

        logger.info { "Opening filesystem from ${Constants.CACHE_PATH}" }
        cache = SqliteFilesystem(Constants.CACHE_PATH)

        logger.info { "Setting up client pool with $numJs5Clients js5 clients and $numHttpClients http clients" }
        clientPool = Js5ClientPool(numJs5Clients, numHttpClients, ip, port)

        logger.info { "Grabbing checksum and reference tables..." }
        clientPool.openConnections(amount = 1)
        val client = clientPool.getClient()

        try {
            if (!client.awaitConnected(30, TimeUnit.SECONDS)) {
                // Name the STATE, not just the elapsed time. The old message
                // said "Took more than 30 seconds to successfully connect",
                // which reads as a network failure - and sent a real debugging
                // session after firewalls and DNS when the socket had in fact
                // connected on the first try and the decoder was blocked
                // waiting on a prefetch block that the NXT protocol has no such
                // thing as. The state tells the two apart at a glance.
                logger.error { "Gave up waiting for the js5 connection to become usable after 30s." }
                logger.error { "  socket open : ${client.channel?.isOpen}" }
                logger.error { "  state       : ${client.state}" }
                logger.error { "  last read   : ${System.currentTimeMillis() - client.lastRead}ms ago" }
                when (client.state) {
                    Js5ClientState.HANDSHAKE -> logger.error {
                        "  The server accepted the socket and never answered the handshake. " +
                            "Check the build (${client.version}) and token against live jav_config."
                    }
                    Js5ClientState.PREFETCHES -> logger.error {
                        "  The handshake was ACCEPTED and we then stalled waiting for " +
                            "${Js5ClientPipeline.EXPECTED_PREFETCHES} prefetch ints. NXT sends none - " +
                            "this should be 0. Check -Dopennxt.js5.client.prefetches."
                    }
                    else -> logger.error { "  Reached ${client.state} without ever being notified." }
                }
                exitProcess(1)
            }
        } catch (e: InterruptedException) {
            logger.error { "Lock on connection got interrupted. Exiting." }
            exitProcess(1)
        }

        logger.info { "Connected! Requesting checksum table now..." }
        downloadChecksumTable()
        createNewIndices()

        logger.info { "Setting up request handler" }
        requestHandler = Js5RequestHandler(clientPool, cache, ioThreads)

        logger.info { "Checking tables" }
        updateReferenceTables()

        logger.info { "Starting table checks" }
        checkerExecutor = Executors.newFixedThreadPool(checkThreads, ThreadFactoryBuilder()
            .setNameFormat("table-checker-%d")
            .setUncaughtExceptionHandler { t, e ->
                logger.error { "Uncaught exception in thread ${t.name}: $e" }
                e.printStackTrace()
            }
            .build())

        // start music first, big archive over http we can download first for faster overall downloads
        val musicChecker = IndexCompletionChecker(cache, 40, requestHandler)
        checkerExecutor.submit(musicChecker)

        val completionCheckers = HashSet<IndexCompletionChecker>()
        checksumTable.entries.forEachIndexed { index, entry ->
            if (index == 40 || (entry.crc == 0 && entry.version == 0)) return@forEachIndexed

            val checker = IndexCompletionChecker(cache, index, requestHandler)
            completionCheckers.add(checker)
            checkerExecutor.submit(checker)
        }

        // Other clients in the pool will automatically be opened in the request handler
        Thread(requestHandler, "js5-request-handler").start()

        var doneJs5 = false
        var doneHttp = false

        // Stall detection.
        //
        // The shutdown condition below requires pendingHttpCount to reach zero.
        // Any request that neither completes nor is marked crashed therefore
        // hangs this loop forever, printing a status line every second that
        // looks exactly like healthy progress except that one number never
        // changes. A real run ended with every write flushed (Pending IO ops:
        // 0) and "Http pending: 548" frozen, spinning indefinitely with nothing
        // in the log to say anything was wrong.
        //
        // The underlying cause is fixed in Js5HttpRequest and Js5RequestHandler,
        // but "the loop cannot make progress" deserves to be reported by the
        // loop itself rather than diagnosed from a screenshot. If nothing moves
        // for this long, say so and stop.
        val stallLimitTicks = System.getProperty("opennxt.stall.seconds")?.toIntOrNull() ?: 180
        var lastFingerprint = ""
        var stalledTicks = 0

        while (true) {
            val snapshot = requestHandler.createSnapshot()

            val fingerprint = "${snapshot.pendingCount}/${snapshot.processingCount}/" +
                "${snapshot.pendingHttpCount}/${snapshot.pendingIOOPerations}"
            if (fingerprint == lastFingerprint) stalledTicks++ else stalledTicks = 0
            lastFingerprint = fingerprint

            if (stalledTicks >= stallLimitTicks) {
                logger.error { "-------------------------------------------------------------" }
                logger.error { " STALLED. Nothing has changed for ${stalledTicks}s:" }
                logger.error { "   unassigned js5 : ${snapshot.pendingCount}" }
                logger.error { "   assigned js5   : ${snapshot.processingCount}" }
                logger.error { "   pending http   : ${snapshot.pendingHttpCount}" }
                logger.error { "   pending io     : ${snapshot.pendingIOOPerations}" }
                logger.error { "" }
                if (snapshot.pendingHttpCount > 0 && snapshot.pendingIOOPerations == 0) {
                    logger.error { " Every write is flushed and only HTTP requests remain, which" }
                    logger.error { " means ${snapshot.pendingHttpCount} music archives failed to download and were" }
                    logger.error { " never retried. Everything already downloaded IS saved." }
                    logger.error { " Re-run this tool - it will re-request exactly the missing" }
                    logger.error { " files and skip everything already present." }
                } else {
                    logger.error { " Re-running is safe: the tool verifies what is on disk and" }
                    logger.error { " downloads only what is missing." }
                }
                logger.error { "-------------------------------------------------------------" }
                exitProcess(2)
            }

            if (!clientPool.closed && completionCheckers.all { it.completed } && snapshot.pendingCount == 0 && snapshot.processingCount == 0) {
                logger.info { "All js5 operations are done, closing js5 client pool" }
                clientPool.close()
                doneJs5 = true
            }

            if (clientPool.closed && musicChecker.completed && snapshot.pendingHttpCount == 0) {
                logger.info { "All http operations are done, preparing to shutdown" }
                doneHttp = true
            }

            if (doneJs5 && doneHttp && snapshot.pendingIOOPerations == 0) {
                logger.info { "No more pending IO operations, shutting down remaining things" }
                logger.error { "We can't close the cache yet. Should probably support that." }
                exitProcess(0)
            }

            logger.info {
                "Unassigned: ${snapshot.pendingCount} (~${snapshot.pendingSize / 1024L / 1024L}MB). Assigned: ${snapshot.processingCount} (~${snapshot.processingSize / 1024L / 1024L}MB). Http pending: ${snapshot.pendingHttpCount} (~${snapshot.pendingHttpSize / 1024L / 1024L}MB). Js5 Bandwidth: ${
                    "%.2f".format(
                        Js5ClientPipeline.getReadThroughput().toDouble() / 1024.0 / 1024.0
                    )
                }MB/s (excludes http). Pending IO ops: ${snapshot.pendingIOOPerations}. Last worker tick: ${System.currentTimeMillis()-snapshot.lastTick}ms ago"
            }
            sleep(1000)
        }
    }
}