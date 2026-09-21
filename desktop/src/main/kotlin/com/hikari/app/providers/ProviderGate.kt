package com.hikari.app.providers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serialises work PER PROVIDER.
 *
 * A source lookup fans out across every installed extension at once, and the
 * expensive engines (a cold Aniyomi APK, a CS3 plugin's first class load, a
 * SkyStream/nuvio JS engine boot) must not be entered twice concurrently for the
 * same provider — the second caller would either pay for a second boot or collide
 * with the first inside a runtime that is not reentrant. Different providers run
 * in parallel; the same provider runs one call at a time.
 */
object ProviderGate {

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(providerId: String): Mutex = locks.computeIfAbsent(providerId) { Mutex() }

    suspend fun <T> withProvider(providerId: String, block: suspend () -> T): T =
        lockFor(providerId).withLock { block() }

    /** True while some call for [providerId] is in flight. */
    fun isBusy(providerId: String): Boolean = locks[providerId]?.isLocked == true
}
