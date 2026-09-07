package com.opennxt.tools.impl.cachedownloader

import mu.KotlinLogging
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one archive over HTTP. Used for index 40 (music), which is served
 * over plain HTTP rather than JS5.
 *
 * The original body was four statements with no error handling at all:
 *
 *     val data = url.readBytes()
 *     request.allocateBuffer(data.size + 2)
 *     request.buffer!!.put(data, 0, data.size); request.buffer!!.flip()
 *     request.notifyCompleted()
 *
 * Every failure mode of that is silent and permanent. `url.readBytes()` throws
 * on a 404, a connection reset, or a rate-limit response; the task dies inside
 * the executor, the Future is never inspected, `notifyCompleted()` is never
 * called, and `crashed` is never set. The request then sits in
 * `Js5RequestHandler.processingHttp` forever - and the tool's shutdown check
 * requires `pendingHttpCount == 0`, so the entire download hangs at
 * "Http pending: N" with an empty I/O queue and nothing on screen to say why,
 * for as long as it is left running.
 *
 * Measured: a run with 32 concurrent HTTP connections completed every write
 * (`Pending IO ops: 0`) and then sat at `Http pending: 548` indefinitely. Higher
 * concurrency makes it worse, because remote 429/reset responses scale with it,
 * but the bug is latent at any concurrency.
 *
 * There were also no timeouts. `URL.readBytes()` inherits the JVM default of
 * infinite connect and read timeouts, so one stalled socket pins a worker
 * thread of the pool permanently.
 *
 * This version: bounded timeouts, bounded retries with backoff, and a request
 * that is ALWAYS resolved one way or the other - completed, or marked `crashed`
 * so the handler re-queues it.
 */
class Js5HttpRequest(val url: URL, val request: Js5RequestHandler.ArchiveRequest) : Runnable {

    private val logger = KotlinLogging.logger { }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Overridable so a slow or flaky link can be given more room without a rebuild. */
        private val attempts: Int =
            System.getProperty("opennxt.http.attempts")?.toIntOrNull()?.coerceAtLeast(1) ?: 4
    }

    private fun fetch(): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP $code ${connection.responseMessage ?: ""}".trim())
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    override fun run() {
        var lastError: Exception? = null

        for (attempt in 1..attempts) {
            try {
                val data = fetch()

                request.allocateBuffer(data.size + 2)
                request.buffer!!.put(data, 0, data.size)
                request.buffer!!.flip()
                request.crashed = false
                request.notifyCompleted()
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts) {
                    // Linear backoff. These failures are dominated by the remote
                    // end shedding load, so the useful response is to wait rather
                    // than to immediately add more of it.
                    try {
                        Thread.sleep(500L * attempt)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        // Out of attempts. Mark it, so the request handler re-queues it instead
        // of the request silently vanishing and stalling shutdown forever.
        logger.warn {
            "HTTP download failed after $attempts attempts for [${request.index}, ${request.archive}]: " +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message} ($url)"
        }
        request.crashed = true
    }
}
