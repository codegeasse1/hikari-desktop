package com.hikari.app.work

import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop BackgroundWork — one-to-one behavior with Android, desktop mechanics.
 *
 * Android keeps a foreground service + wakelock while tokens are registered so
 * the process survives; on the desktop the process is the user's own app
 * window, so there is nothing to keep awake. What stays identical is the
 * contract the catalogue/search/sweep code programs against: begin/end tokens,
 * idempotent end, cancelAll with onCancel hooks, the stale-token sweeper, and
 * the one-line label().
 *
 * The Android Handler/Looper scheduler becomes a single daemon thread so work
 * tracking never keeps the JVM alive and never touches JavaFX threads.
 */
object BackgroundWork {

    /** A token that was never ended is dropped after this long. */
    private const val MAX_TOKEN_MS = 60L * 60 * 1000

    /** How often the safety sweep runs. */
    private const val SWEEP_INTERVAL_MS = 60L * 1000

    /** How long the sweeper lingers after the last token, so back-to-back work
     *  doesn't stop and immediately restart it. */
    private const val STOP_GRACE_MS = 6L * 1000

    /** Handle for one registered piece of work. */
    class Token internal constructor(internal val id: Long)

    private class Entry(
        val label: String,
        val startedAt: Long,
        /** Cancels the work behind this token. Optional: work that is a flow
         *  being collected by somebody else's coroutine cannot be cancelled from
         *  here. */
        val onCancel: (() -> Unit)?,
    )

    private val lock = Any()
    private val active = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private var stopScheduled = false
    private var sweeping = false

    @Volatile
    private var closed = false

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hikari-work").apply { isDaemon = true }
        }

    @Volatile
    private var stopFuture: ScheduledFuture<*>? = null

    @Volatile
    private var sweepFuture: ScheduledFuture<*>? = null

    private val stopRunnable = Runnable { stopIfIdle() }

    private val sweepRunnable = Runnable {
        if (!sweep()) synchronized(lock) {
            sweeping = false
            sweepFuture?.cancel(false)
            sweepFuture = null
        }
    }

    fun begin(label: String, onCancel: (() -> Unit)? = null): Token {
        val id = synchronized(lock) {
            val i = nextId++
            active[i] = Entry(label.trim(), System.currentTimeMillis(), onCancel)
            i
        }
        // The app is closed (see [closed]): the work is still registered — so it
        // reports and ends normally — but it may not revive the sweeper.
        if (closed) return Token(id)
        cancelScheduledStop()
        ensureSweeper()
        return Token(id)
    }

    fun reopen() {
        closed = false
    }

    /** Ends a token. Idempotent — ending the same token twice is harmless. */
    fun end(token: Token) {
        val remaining = synchronized(lock) {
            active.remove(token.id)
            active.size
        }
        if (remaining <= 0) scheduleStop()
    }

    fun isActive(): Boolean = synchronized(lock) { active.isNotEmpty() }

    fun cancelAll(reason: String) {
        closed = true
        val dropped = synchronized(lock) {
            val all = active.values.toList()
            active.clear()
            stopScheduled = false
            all
        }
        stopFuture?.cancel(false)
        stopFuture = null
        if (dropped.isEmpty()) return
        Logs.log(
            "Work",
            "cancelling ${dropped.size} background task(s) (" +
                dropped.joinToString(", ") { "\"" + it.label + "\"" } + ") — " + reason,
        )
        dropped.forEach { entry -> runCatching { entry.onCancel?.invoke() } }
    }

    /** One line describing everything running, e.g. `Searching "scam" (+2)`. */
    fun label(): String? = synchronized(lock) {
        val first = active.values.firstOrNull() ?: return null
        if (active.size == 1) first.label else "${first.label}  (+${active.size - 1})"
    }

    private fun scheduleStop() {
        val post = synchronized(lock) {
            if (stopScheduled) false else {
                stopScheduled = true
                true
            }
        }
        if (post) {
            stopFuture?.cancel(false)
            stopFuture = scheduler.schedule(stopRunnable, STOP_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelScheduledStop() {
        synchronized(lock) { stopScheduled = false }
        stopFuture?.cancel(false)
        stopFuture = null
    }

    private fun stopIfIdle() {
        synchronized(lock) { stopScheduled = false }
        stopFuture = null
        // Desktop: nothing to stop (no service). The sweeper is cancelled by
        // the next sweep() returning false.
    }

    private fun ensureSweeper() {
        val start = synchronized(lock) {
            if (sweeping) false else {
                sweeping = true
                true
            }
        }
        if (start) {
            sweepFuture?.cancel(false)
            sweepFuture = scheduler.scheduleAtFixedRate(
                sweepRunnable, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS
            )
        }
    }

    /** Drops stale tokens. Returns true while work is still registered — i.e.
     *  whether the sweeper should keep running. */
    private fun sweep(): Boolean {
        val now = System.currentTimeMillis()
        val expired = synchronized(lock) {
            val stale = active.filterValues { now - it.startedAt > MAX_TOKEN_MS }
            stale.forEach { (id, entry) ->
                active.remove(id)
                // A token that was never ended is a bug somewhere, but the work
                // behind it is real and must be stopped, not just forgotten.
                runCatching { entry.onCancel?.invoke() }
            }
            stale.size
        }
        if (expired > 0) {
            Logs.log(
                "Work",
                "dropped $expired stale background task(s) that never reported finishing",
            )
        }
        return isActive()
    }

    /** Convenience: the current Application, or null very early in startup. */
    internal fun context(): HikariApp? = runCatching { HikariApp.instance }.getOrNull()
}
