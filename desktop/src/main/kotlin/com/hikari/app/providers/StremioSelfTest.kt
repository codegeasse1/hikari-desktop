package com.hikari.app.providers

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import kotlinx.coroutines.runBlocking

/**
 * CI test: the Stremio half of "no provider shows a playable source".
 *
 * Stremio is a protocol, not a scraper: the app talks to a third-party addon
 * over HTTP (manifest → catalog → meta → stream) and parses the result. That
 * whole path is testable on the runner, and it is the path that has to work for
 * the sources the user never sees to appear.
 *
 * Two addons, chosen for what they prove:
 *  - Cinemeta, the official catalog/metadata addon: catalogs + meta + episodes
 *    must parse, and a stream-less addon must say so instead of pretending.
 *  - Torrentio, the playback addon nearly every Stremio user has: it must be
 *    asked, and its torrent streams must parse into StreamSource rows.
 *
 * A dead/blocked addon is a WARN (it is somebody else's server); a parse failure
 * or an exception out of our own code is a FAIL.
 */
fun main() {
    println("StremioSelfTest: start")
    var failures = 0
    var warns = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println(if (ok) "  OK   $name" else "  FAIL $name  $detail")
        if (!ok) failures++
    }
    fun warn(name: String, detail: String = "") {
        println("  WARN $name  $detail")
        warns++
    }

    runCatching { HikariApp().init() }
    Http.init()

    val cinemeta = ProviderConfig(
        id = "stremio|cinemeta|0",
        name = "Cinemeta",
        type = ProviderType.STREMIO,
        url = "https://v3-cinemeta.strem.io/manifest.json",
    )
    val torrentio = ProviderConfig(
        id = "stremio|torrentio|0",
        name = "Torrentio",
        type = ProviderType.STREMIO,
        url = "https://torrentio.strem.fun/manifest.json",
    )

    // ── Cinemeta: catalogs, meta, episodes ─────────────────────────────────
    run {
        val addon = StremioAddon(cinemeta)
        val catalogs = runCatching { runBlocking { addon.catalogs() } }.getOrElse { t ->
            warn("Cinemeta catalogs", "${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        println("— Cinemeta catalogs: ${catalogs.size} (first: " + catalogs.firstOrNull()?.name + ")")
        if (catalogs.isNotEmpty()) {
            val items = runCatching { runBlocking { addon.getCatalog(catalogs.first(), 1) } }.getOrElse { t ->
                check("Cinemeta's first catalog loads", false, "${t.javaClass.simpleName}: ${t.message}")
                emptyList()
            }
            println("— Cinemeta first catalog: ${items.size} item(s) (first: " + items.firstOrNull()?.title + ")")
            check("a Stremio catalog parses into items", items.isNotEmpty(), "0 items")
            if (items.isNotEmpty()) {
                val detailed = runCatching { runBlocking { addon.getMeta(items.first()) } }.getOrDefault(items.first())
                println("— Cinemeta meta for \"" + detailed.title + "\": backdrop=" +
                    (detailed.backdropUrl != null) + " overview=" + (detailed.overview != null))
            }
        } else {
            warn("Cinemeta catalog list", "the addon did not answer this run")
        }
        // A catalog-only addon must NOT silently return nothing: it has to say
        // that it has no streams (that message is what the detail screen shows).
        val streams = runCatching { runBlocking { addon.getStreams(fightClub(), null) } }.getOrDefault(emptyList())
        println("— Cinemeta streams: ${streams.size} (reason=\"" + (StremioAddon.streamErrors[cinemeta.id] ?: "-") + "\")")
        if (streams.isEmpty() && StremioAddon.streamErrors[cinemeta.id].isNullOrBlank()) {
            check("a catalog-only addon explains itself instead of a blank list", false, "no reason recorded")
        }
    }

    // ── Torrentio: the playback addon ──────────────────────────────────────
    run {
        val addon = StremioAddon(torrentio)
        val item = fightClub()
        val t0 = System.currentTimeMillis()
        val streams = runCatching { runBlocking { addon.getStreams(item, null) } }.getOrElse { t ->
            check("asking Torrentio for streams does not throw", false, "${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        val ms = System.currentTimeMillis() - t0
        println("— Torrentio streams for tt0137523: ${streams.size} in ${ms}ms (reason=\"" +
            (StremioAddon.streamErrors[torrentio.id] ?: "-") + "\")")
        streams.take(3).forEach { println("      · ${it.name.take(60)}  torrent=${it.isTorrent}  ${it.url.take(70)}") }
        if (streams.isEmpty()) {
            warn("Torrentio produced streams", (StremioAddon.streamErrors[torrentio.id] ?: "no reason").take(220))
        } else {
            check("a Stremio playback addon's streams parse", streams.all { it.url.isNotBlank() || it.infoHash != null }, "blank url")
        }
    }

    println(
        "StremioSelfTest: " + (if (failures == 0) "OK" else "$failures FAILED") +
            " (warnings=$warns)"
    )
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}

/** Fight Club on IMDb — the id every Stremio addon knows. */
private fun fightClub(): MediaItem = MediaItem(
    providerId = "stremio|selftest|0",
    id = "tt0137523",
    title = "Fight Club",
    type = MediaType.MOVIE,
    year = 1999,
    rawType = "movie",
)
