package com.opennxt.model.tick

import com.google.common.util.concurrent.ThreadFactoryBuilder
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TickEngine {
    private val logger = KotlinLogging.logger { }

    /**
     * One thread, stated as a constant.
     *
     * This was `min(1, availableProcessors() - 2)`, which evaluates to 1 on any
     * box with three or more cores - so a pool of one is what has always
     * actually run, and pinning it changes nothing there. On smaller boxes the
     * expression was a boot bug rather than a tuning knob: two cores gives
     * `min(1, 0) = 0`, a pool that never runs a tick at all, and one core gives
     * `-1`, which ScheduledThreadPoolExecutor rejects outright. (This container
     * has 2.) `max` was presumably meant - but a tick engine wants serial
     * execution, since the world and lobby ticks touch the same players, so 1 is
     * also the right answer.
     */
    private val executor = Executors.newScheduledThreadPool(
        1,
        ThreadFactoryBuilder()
            .setNameFormat("tick-engine")
            .setUncaughtExceptionHandler { t, e -> logger.error("Error with thread $t", e) }
            .build())

    /**
     * Tickables submitted here will indefinitely be invoked once every 600 milliseconds.
     *
     * The same tickable won't be called concurrently. If ticking takes, for example, 1000 milliseconds, the next
     * execution will start 400 millis late.
     *
     * THE TRY/CATCH IS THE POINT OF THIS METHOD.
     *
     * `scheduleAtFixedRate` documents that "if any execution of the task
     * encounters an exception, subsequent executions are suppressed" - and it
     * suppresses them SILENTLY. The throwable is recorded into the
     * ScheduledFuture, which nothing here ever inspects, so the
     * uncaughtExceptionHandler configured on the factory above never fires
     * either. Measured with a scratch program (/tmp/probe/tick/TickProbe.java),
     * throwing once on the third of ~30 scheduled executions:
     *
     * ticks executed in 1500ms at 50ms period (expect ~30): 3
     * uncaughtExceptionHandler fired: 0 time(s)
     * executor is shut down: false
     *
     * So one throw anywhere under World.tick stopped the ENTIRE WORLD -
     * every player's packets, movement, npcs, autosave - permanently, with no
     * log line of any kind, on a thread pool that still reports itself healthy.
     */
    /**
     * How long a tick may take before it is reported, in ms. The schedule is 600 ms, so anything
     * at or over that has already pushed the next tick late - `scheduleAtFixedRate` does not run
     * two at once, it runs the next one immediately and the world falls behind wall-clock.
     *
     * `-Dopennxt.tick.warnMs=<n>` moves it.
     */
    private val warnMs: Long = System.getProperty("opennxt.tick.warnMs")?.toLongOrNull() ?: 600L

    /** Overruns already reported per tickable, so a permanently slow world cannot own the log. */
    private val overrunCount = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun submitTickable(tickable: Tickable) {
        executor.scheduleAtFixedRate({
            // ------------------------------------------------------------------------------
 // TICK DURATION, instrumented. There was NO timing here at all, which
            // meant an overrunning tick was invisible: the world simply ran slow and the only
            // symptom was in the game.
            //
            // It matters because several things bound their work against this budget and nobody
            // could see the budget. Measured the same day: one autosave UPSERT of a real save
            // blob costs ~1.5 ms committed, and `World.autosave` writes ONE PER PLAYER inside a
            // single tick - so the autosave tick alone runs out of budget somewhere around 400
            // concurrent players. That is a design ceiling rather than a defect, but it is not
            // one anybody should discover by feel.
            //
            // Logged on a doubling schedule (1st, 2nd, 4th, 8th...) so a world that is
            // permanently over budget says so without filling the log.
            // ------------------------------------------------------------------------------
            val started = System.nanoTime()
            try {
                tickable.tick()
            } catch (t: Throwable) {
                logger.error(t) {
                    "Uncaught throwable in ${tickable.javaClass.name}.tick() - continuing. Without this catch " +
                        "scheduleAtFixedRate would have stopped ticking it forever, silently."
                }
            } finally {
                val tookMs = (System.nanoTime() - started) / 1_000_000
                if (tookMs >= warnMs) {
                    val name = tickable.javaClass.simpleName
                    val n = overrunCount.computeIfAbsent(name) { java.util.concurrent.atomic.AtomicLong() }
                        .incrementAndGet()
                    if (java.lang.Long.bitCount(n) == 1) {   // 1st, 2nd, 4th, 8th, ...
                        logger.warn {
                            "TICK OVERRUN: $name.tick() took ${tookMs}ms against a ${warnMs}ms budget " +
                                "(occurrence $n). The next tick starts late and the world falls behind " +
                                "wall-clock. -Dopennxt.tick.warnMs=<n> moves the threshold."
                        }
                    }
                }
            }
        }, 0, 600, TimeUnit.MILLISECONDS)
    }

    /**
     * Executes a task asynchronously
     */
    fun executeAsync(delay: Long = 0L, runnable: () -> Unit) {
        if (delay <= 0L) executor.execute(runnable)
        else executor.schedule(runnable, delay, TimeUnit.MILLISECONDS)
    }

}