package com.hikari.app.providers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serialises work PER PROVIDER, with separate lanes.
 *
 * A source lookup fans out across every installed extension at once, and the
 * expensive engines (a cold Aniyomi APK, a CS3 plugin's first class load, a
 * SkyStream/nuvio JS engine boot) must not be entered twice concurrently for the
 * same provider — the second caller would either pay for a second boot or collide
 * with the first inside a runtime that is not reentrant. Different providers run
 * in parallel; the same provider runs one call at a time PER LANE.
 *
 * Lanes exist so playback never queues behind browsing: an interactive Play
 * lookup (streams for the title on screen) runs on the "fg" lane while
 * catalog/meta/search work runs on "bg". A 51CG-style provider whose translate
 * + remote-config storm holds the lock during a catalog sweep used to make the
 * Play lookup wait out the whole storm and burn the first-source budget — the
 * "No playable source" with a healthy catalog.
 */
object ProviderGate {

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(providerId: String, lane: String): Mutex =
        locks.computeIfAbsent("$providerId|$lane") { Mutex() }

    suspend fun <T> withProvider(providerId: String, lane: String = "fg", block: suspend () -> T): T =
        lockFor(providerId, lane).withLock { block() }

    /** True while some call for [providerId] is in flight, on any lane. */
    fun isBusy(providerId: String): Boolean =
        locks["$providerId|fg"]?.isLocked == true || locks["$providerId|bg"]?.isLocked == true
}
