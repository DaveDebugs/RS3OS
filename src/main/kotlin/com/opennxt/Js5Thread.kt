package com.opennxt

import com.opennxt.net.DiagnosticLog
import com.opennxt.net.js5.Js5Session
import mu.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// TODO This should probably be re-done entirely.
object Js5Thread: Thread("js5-thread") {

    private val running = AtomicBoolean(true)
    private val logger = KotlinLogging.logger {  }
    private val sessions = CopyOnWriteArrayList<Js5Session>()

    /**
     * Serves queued JS5 requests for every live session.
     *
     * ## Why each session is wrapped
     *
     * This loop used to call `process` unguarded. `process` can throw - a bad
     * container, an encoder fault, a closed channel - and because this is ONE
     * thread shared by every session, a single throw ended `run()` and took JS5
     * serving down for the whole server, permanently and silently. Nothing
     * logged it; the symptom was simply that files stopped arriving.
     *
     * That is not hypothetical. A live 947 client was served exactly one file
     * (the checksum table) and then nothing; it gave up after 30 seconds and
     * reconnected twice more, and those later connections were served ZERO
     * files from their very first request - the giveaway, because a per-session
     * fault cannot affect a later session. The thread was already dead.
     *
     * So: one session's failure is now that session's problem. It is logged
     * with the request that caused it, recorded to the diagnostic file, and the
     * loop carries on.
     */
    override fun run() {
        while (running.get()) {
            // Sleep when NOTHING was served this pass, not merely when the
            // session list is empty.
            //
            // The old condition was `if (sessions.isEmpty()) sleep(100)`, and
            // the comment beside process() claimed it blocks. It does not: it
            // drains whatever is queued and returns, so a single connected but
            // idle JS5 session spun this thread flat out - measured at 97.8% of
            // one core, against 0.0% with no session attached. A client sits in
            // the lobby for minutes with an open, idle JS5 connection, so this
            // is the normal case rather than an edge one.
            //
            // process() returns bytes sent, which is exactly the signal needed:
            // work done means come straight back, nothing done means yield.
            var served = 0L

            for (session in sessions) {
                try {
                    served += session.process(10_000_000)
                } catch (t: Throwable) {
                    failures++
                    logger.error(t) { "js5 session ${session.channel.remoteAddress()} threw while serving; dropping it and continuing" }
                    DiagnosticLog.reason(
                        session.channel,
                        "js5 serve threw ${t.javaClass.name}: ${t.message} - session dropped, js5 thread SURVIVES"
                    )
                    try { session.close() } catch (ignored: Throwable) { }
                    sessions.remove(session)
                }
            }

            if (served == 0L) sleep(5)
        }
        logger.error { "js5 thread has exited its loop - JS5 serving is now dead for every session" }
    }

    /** How many sessions have thrown while being served. Non-zero is worth looking at. */
    @Volatile
    var failures: Int = 0
        private set

    fun addSession(session: Js5Session) {
        sessions.add(session)
    }

    fun removeSession(session: Js5Session) {
        sessions.remove(session)
    }

}