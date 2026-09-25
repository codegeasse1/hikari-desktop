package com.hikari.app.nuvio

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * CI test: does a REAL Nuvio scraper actually produce servers on this build?
 *
 * The bug report is "not showing any playable source in any nuvio extension",
 * which is a claim no unit test can confirm or deny: the whole pipeline is a
 * third-party script running in the JS engine, over the network, against a
 * site that can be down, geo-blocked or changed. So this walks the real path,
 * end to end, on the runner:
 *
 *   download a real scraper (the same files the Extensions screen installs)
 *     → write it to an installed-scraper file
 *     → NuvioScraper.getStreams (TMDB resolve → nuvio runtime → StreamSource)
 *     → print exactly what came back, and how long it took.
 *
 * How to READ the result:
 *  - a RUNTIME failure (the engine could not load the script, the payload was
 *    unreadable, `provider has no getStreams export`) is OUR bug: it fails the
 *    test.
 *  - a PROVIDER failure ("no sources for this title", a site 403, a timeout on
 *    a dead host) is the third-party scraper's own outcome: it is reported as a
 *    WARN with the reason, because a black-box scraper breaking is not a reason
 *    to fail a build.
 *
 * Needs the network — that is the point — so a runner without internet fails
 * loudly instead of passing quietly.
 */
fun main() {
    println("NuvioStreamSelfTest: start")
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

    // ── the scrapers: two from the public nuvio-providers repo, plus whatever
    //    the All-in-One repo lists first. Fetched, never committed. ──────────
    val sources = linkedMapOf(
        "4khdhub" to "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/providers/4khdhub.js",
        "streamflix" to "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/providers/streamflix.js",
    )
    runCatching {
        val manifest = JSONObject(
            Http.fetchStringRobust(
                "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json"
            ).getOrThrow()
        )
        val arr = manifest.optJSONArray("scrapers")
        val base = "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/main"
        var taken = 0
        for (i in 0 until (arr?.length() ?: 0)) {
            if (taken >= 2) break
            val s = arr?.optJSONObject(i) ?: continue
            val path = s.optString("path").ifBlank { s.optString("file") }
            if (path.isBlank()) continue
            val name = path.substringAfterLast('/').removeSuffix(".js")
            if (sources.containsKey(name)) continue
            sources[name] = base + "/" + path.trimStart('/')
            taken++
        }
    }.onFailure { println("  (the All-in-One manifest could not be read: ${it.message})") }
    println("— scrapers to try: " + sources.keys.joinToString(", "))

    // ── the title: Fight Club (TMDB 550) — old, well-seeded, and named the same
    //    on every site, so a scraper that works at all finds it. ─────────────
    val tmdbId = "550"
    val mediaType = "movie"

    var runtimeOk = 0
    var providerOk = 0
    for ((name, url) in sources) {
        val bytes = runCatching { Http.fetchBytesRobust(url) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            warn("$name downloads", "no bytes from $url")
            continue
        }
        val file = File.createTempFile("hikari-nuvio-$name-", ".js").apply { deleteOnExit() }
        file.writeBytes(bytes)
        // The scraper must load in the JS engine at all: this is the check that
        // separates "the extension is broken on this build" from "the site has
        // nothing for this title".
        val verdict = runCatching {
            runBlocking { NuvioRuntime.validate(HikariApp.instance, String(bytes, Charsets.UTF_8)) }
        }.getOrElse { "ERR: threw ${it.javaClass.simpleName}: ${it.message}" }
        println("— $name (${bytes.size} bytes): validate → " + verdict.take(160))
        if (!verdict.startsWith("OK")) {
            check("$name loads in the nuvio runtime", false, verdict.take(300))
            continue
        }
        runtimeOk++

        val config = ProviderConfig(
            id = "nuvio|selftest-$name|0",
            name = name,
            type = ProviderType.NUVIO,
            url = file.absolutePath,
            extra = "$url|0",
        )
        val item = MediaItem(
            providerId = config.id,
            id = tmdbId,
            title = "Fight Club",
            type = MediaType.MOVIE,
            year = 1999,
            originalTitle = "Fight Club",
            rawType = "movie",
        )
        val t0 = System.currentTimeMillis()
        val streams = runCatching {
            runBlocking { NuvioScraper(config).getStreams(item, null as Episode?) }
        }.getOrElse {
            check("$name lookup does not throw", false, "${it.javaClass.simpleName}: ${it.message}")
            continue
        }
        val ms = System.currentTimeMillis() - t0
        val err = NuvioScraper.streamErrors[config.id]
        println(
            "— $name lookup: ${streams.size} source(s) in ${ms}ms" +
                (if (err.isNullOrBlank()) "" else "  reason=\"$err\"")
        )
        streams.take(3).forEach { println("      · ${it.name.take(70)}  ${it.url.take(90)}") }
        if (streams.isNotEmpty()) {
            providerOk++
        } else {
            warn("$name produced sources", (err ?: "no reason reported").take(200))
        }
        // A runtime-level failure is our bug, not the site's: the payload was
        // unreadable, or the engine could not run the provider.
        val ours = err?.let {
            it.contains("runtime returned an unreadable result") ||
                it.contains("timed out after") ||
                it.contains("no getStreams export") ||
                it.contains("Couldn't resolve a TMDB id")
        } == true
        if (ours) check("$name fails for a reason inside Hikari, not the site", false, err.orEmpty().take(300))
    }

    check("at least one real nuvio scraper loads in the JS runtime", runtimeOk > 0, "loaded=$runtimeOk")
    if (providerOk == 0) {
        println(
            "  NOTE no scraper returned a source this run — that can be the sites (they are " +
                "third-party and change daily), but a build where NONE of ${sources.size} works " +
                "is the report this test exists for."
        )
    }

    println(
        "NuvioStreamSelfTest: " +
            (if (failures == 0) "OK" else "$failures FAILED") +
            " (scrapers loaded=$runtimeOk, produced sources=$providerOk, warnings=$warns)"
    )
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
