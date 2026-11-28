package com.hikari.app.providers

import com.hikari.app.data.Logs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One non-reentrant lane per provider. Foreground work (opening a title or
 * playing it) gets priority over background catalogue/search work. A wedged
 * third-party extension cannot permanently own the lock: after a bounded grace
 * period a fresh lane is installed and the old call is allowed to finish alone.
 *
 * The String overload preserves the desktop call sites ("fg"/"bg"); the enum
 * overload is the Android contract.
 */
object ProviderGate {
    enum class Lane { INTERACTIVE, BACKGROUND }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val interactiveWaiters = ConcurrentHashMap<String, AtomicInteger>()
    private val interactiveWindows = AtomicInteger(0)

    private const val BACKGROUND_MAX_HOLD_MS = 20_000L
    private const val BACKGROUND_COURTESY_MS = 2_000L
    private const val INTERACTIVE_GATE_MAX_MS = 90_000L
    private const val BACKGROUND_GATE_MAX_MS = 120_000L
    private const val STUCK_GRACE_MS = 5_000L
    private const val WAIT_LOG_MS = 750L

    class ProviderStalledException(
        val providerId: String,
        val waitedMs: Long,
    ) : Exception("stalled behind an earlier call into this extension (${waitedMs / 1000}s)")

    private fun lockFor(id: String) = locks.computeIfAbsent(id) { Mutex() }
    private fun waitersFor(id: String) = interactiveWaiters.computeIfAbsent(id) { AtomicInteger(0) }

    fun interactiveActive(): Boolean = interactiveWindows.get() > 0

    suspend fun <T> interactive(block: suspend () -> T): T {
        interactiveWindows.incrementAndGet()
        try {
            return block()
        } finally {
            interactiveWindows.decrementAndGet()
        }
    }

    suspend fun <T> withProvider(
        providerId: String,
        lane: String = "fg",
        block: suspend () -> T,
    ): T = withProvider(
        providerId,
        if (lane.equals("bg", ignoreCase = true) || lane.equals("background", ignoreCase = true))
            Lane.BACKGROUND else Lane.INTERACTIVE,
        block,
    )

    suspend fun <T> withProvider(
        id: String,
        lane: Lane = Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T {
        if (lane == Lane.INTERACTIVE) {
            val waiters = waitersFor(id)
            waiters.incrementAndGet()
            try {
                var mutex = lockFor(id)
                val started = System.currentTimeMillis()
                var contendedSince = -1L
                while (true) {
                    if (mutex.tryLock()) {
                        try {
                            return block()
                        } finally {
                            mutex.unlock()
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (contendedSince < 0L) contendedSince = now
                    if (now - contendedSince >= STUCK_GRACE_MS) {
                        locks[id] = Mutex()
                        mutex = lockFor(id)
                        contendedSince = now
                        Logs.log("Provider", "$id: interactive waiter bypassed a stuck holder after ${STUCK_GRACE_MS / 1000}s")
                        continue
                    }
                    if (now - started >= INTERACTIVE_GATE_MAX_MS) {
                        throw ProviderStalledException(id, now - started)
                    }
                    delay(25L)
                }
            } finally {
                waiters.decrementAndGet()
            }
        }

        var mutex = lockFor(id)
        val waiters = waitersFor(id)
        val started = System.currentTimeMillis()
        var contendedSince = -1L
        while (true) {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= BACKGROUND_GATE_MAX_MS) {
                throw ProviderStalledException(id, elapsed)
            }
            val pageWantsThis = waiters.get() > 0 && elapsed < BACKGROUND_MAX_HOLD_MS
            val somePageLoading = waiters.get() == 0 && interactiveWindows.get() > 0 &&
                elapsed < BACKGROUND_COURTESY_MS
            if (!pageWantsThis && !somePageLoading) {
                if (mutex.tryLock()) {
                    if (elapsed >= WAIT_LOG_MS) {
                        Logs.log("Provider", "$id: background call started after ${elapsed / 1000.0}s")
                    }
                    try {
                        return block()
                    } finally {
                        mutex.unlock()
                    }
                }
                val now = System.currentTimeMillis()
                if (contendedSince < 0L) contendedSince = now
                if (now - contendedSince >= STUCK_GRACE_MS) {
                    locks[id] = Mutex()
                    mutex = lockFor(id)
                    contendedSince = now
                    Logs.log("Provider", "$id: background waiter bypassed a stuck holder after ${STUCK_GRACE_MS / 1000}s")
                }
            } else {
                contendedSince = -1L
            }
            delay(25L)
        }
    }

    fun isBusy(providerId: String): Boolean = locks[providerId]?.isLocked == true

    fun breaker(id: String) {
        locks[id] = Mutex()
        interactiveWaiters.remove(id)
        Logs.log("Provider", "$id: stall breaker pulled — later calls no longer queue behind the stuck one")
    }
}
