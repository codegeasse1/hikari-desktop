package com.hikari.app.skystream

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * CI test: does a REAL SkyStream plugin work on this build, end to end?
 *
 * The user's report named three engines — "nuvio provider and stremio provider
 * and skystream provider, not showing any playable source". Two of the three had
 * a test ([com.hikari.app.nuvio.NuvioStreamSelfTest],
 * [com.hikari.app.providers.StremioSelfTest]) and SkyStream had none, which is
 * the one thing a bug report about it cannot survive: SkyStream plugins are
 * whole sites wrapped in a `.sky`, their runtime is a different JS environment
 * from nuvio's, and "the extension registered" says nothing about whether
 * `getHome` / `load` / `loadStreams` answer.
 *
 * So this walks the app's own path, on the runner, against the live repo:
 *
 *   read the repo's plugin list → download a real `.sky`
 *     → SkyStreamPluginManager.install (unzip → plugin.json/plugin.js →
 *       SkyStreamRuntime.validate, i.e. a real engine boot that checks the four
 *       exported callbacks)
 *     → SkyStreamProvider.catalogs()   (getHome)
 *     → getCatalog(row)                (the row's items)
 *     → getMeta / getEpisodes / getStreams
 *     → print exactly what came back, and how long each step took.
 *
 * How to READ the result:
 *  - a RUNTIME failure — the `.sky` will not install, the engine cannot load the
 *    plugin, a call comes back as a non-JSON payload — is OUR bug and fails the
 *    build;
 *  - a SITE failure — the row came back empty, the site is 403/down, a stream
 *    lookup found nothing for that title — is the third-party extension's own
 *    outcome, reported as a WARN with the reason it recorded.
 *
 * Needs the network (that is the point), so a runner without internet fails
 * loudly instead of passing quietly.
 */
fun main() {
    println("SkyStreamSelfTest: start")
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

    // ── the repo's plugin list ──────────────────────────────────────────────
    // The same file the Extensions screen resolves a SkyStream repo to: repo.json
    // names `pluginLists[]`, and each of those is a bare JSON array of plugin
    // entries (`{packageName, name, url, …}`).
    val listUrl = "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/dist/plugins.json"
    val body = Http.fetchRepoJson(listUrl).getOrNull()?.second
        ?: Http.fetchStringRobust(listUrl).getOrNull()
    if (body == null) {
        check("the SkyStream repo's plugin list is reachable", false, listUrl)
        println("SkyStreamSelfTest: $failures FAILED (no network to the repo)")
        kotlin.system.exitProcess(1)
        return
    }
    val listed = runCatching { JSONArray(body) }.getOrNull()
    println("— the SkyStream repo lists ${listed?.length() ?: 0} plugins")
    check("the plugin list parses as the SkyStream format", (listed?.length() ?: 0) > 0, "" + listed?.length())
    if (listed == null || listed.length() == 0) {
        println("SkyStreamSelfTest: $failures FAILED")
        kotlin.system.exitProcess(1)
        return
    }

    // Two plugins, preferring well-established ones (they are the ones a user
    // actually installs); anything that declares a `.sky` URL will do.
    val preferred = listOf("dev.akash.stars.4khd", "dev.akash.stars.hdhub4u", "dev.akash.stars.moviesdrive")
    val picks = ArrayList<Pair<String, String>>()
    for (name in preferred) {
        for (i in 0 until listed.length()) {
            val o = listed.optJSONObject(i) ?: continue
            if (o.optString("packageName") != name) continue
            val url = o.optString("url")
            if (url.isNotBlank()) picks.add(name to url)
            break
        }
        if (picks.size >= 2) break
    }
    if (picks.isEmpty()) {
        for (i in 0 until listed.length()) {
            if (picks.size >= 2) break
            val o = listed.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.endsWith(".sky")) picks.add(o.optString("packageName") to url)
        }
    }
    println("— plugins to try: " + picks.joinToString(", ") { it.first })

    var installedOk = 0
    var calledOk = 0
    var streamOk = 0

    for ((packageName, url) in picks) {
        val bytes = Http.fetchBytesRobust(url)
        if (bytes == null || bytes.isEmpty()) {
            warn("$packageName downloads", "no bytes from $url")
            continue
        }
        println("— $packageName (${bytes.size} bytes)")

        // ── install: unzip, read the manifest, boot a real engine, and require
        //    the four callbacks. A failure here is our side of the report.
        val installed: Result<Int> = try {
            runBlocking { SkyStreamPluginManager.install(HikariApp.instance, bytes, url, null) }
        } catch (t: Throwable) {
            Result.failure(t)
        }
        if (installed.isFailure) {
            check(
                "$packageName installs and its plugin loads in the JS runtime",
                false,
                installed.exceptionOrNull()?.message?.take(300).orEmpty(),
            )
            continue
        }
        installedOk++
        println("      installed and validated (the plugin exports getHome/search/load/loadStreams)")

        val config = HikariApp.instance.store.providers()
            .firstOrNull { it.type == ProviderType.SKYSTREAM && it.id == "sky|$packageName" }
        if (config == null) {
            check("$packageName is registered as a provider", false, "no sky|$packageName in the store")
            continue
        }
        val provider = SkyStreamProvider(config)

        // ── getHome ─────────────────────────────────────────────────────────
        val t0 = System.currentTimeMillis()
        val catalogs = runCatching { runBlocking { provider.catalogs() } }.getOrElse { t ->
            check("$packageName answers getHome", false, "${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        val homeMs = System.currentTimeMillis() - t0
        val homeWhy = SkyStreamProvider.catalogErrors[config.id]
        println(
            "      getHome: ${catalogs.size} row(s) in ${homeMs}ms — " +
                catalogs.take(4).joinToString { it.name } +
                (if (homeWhy.isNullOrBlank()) "" else "  reason=\"$homeWhy\"")
        )
        // What separates OUR bug from the site's day: a plugin whose JS never
        // answered hands back no JSON at all ("did not answer"), which is a
        // broken engine/plugin; a site that has nothing to show still answers,
        // and that is a WARN.
        val unreachable = homeWhy?.contains("did not answer") == true
        check("$packageName's getHome call reaches its plugin", !unreachable, homeWhy.orEmpty().take(300))
        if (unreachable) {
            // A plugin that cannot answer is not a plugin that has nothing.
            continue
        }
        calledOk++
        if (catalogs.isEmpty()) {
            warn("$packageName's getHome returned rows", (homeWhy ?: "the site answered no rows this run").take(220))
            continue
        }

        // ── the first row's items ───────────────────────────────────────────
        val t1 = System.currentTimeMillis()
        val items = runCatching { runBlocking { provider.getCatalog(catalogs.first(), 1) } }.getOrElse { t ->
            check("$packageName answers getCatalog", false, "${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        println("      row \"${catalogs.first().name}\": ${items.size} item(s) in ${System.currentTimeMillis() - t1}ms")
        if (items.isEmpty()) {
            warn("$packageName row \"${catalogs.first().name}\" has items", "the site answered nothing this run")
            continue
        }

        // ── details, episodes, streams ──────────────────────────────────────
        val item = items.first()
        val meta = runCatching { runBlocking { provider.getMeta(item) } }.getOrDefault(item)
        println("      meta: \"${meta.title}\" type=${meta.type} backdrop=${meta.backdropUrl != null} year=${meta.year}")
        val episodes = runCatching { runBlocking { provider.getEpisodes(meta) } }.getOrNull().orEmpty()
        val episode: Episode? = episodes.firstOrNull()
        println("      episodes: ${episodes.size}" + (episode?.let { " (first: ${it.name ?: it.id})" } ?: ""))

        val t2 = System.currentTimeMillis()
        val streams = runCatching { runBlocking { provider.getStreams(meta, episode) } }.getOrElse { t ->
            check("$packageName answers loadStreams", false, "${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        val err = SkyStreamProvider.streamErrors[config.id]
        println(
            "      loadStreams: ${streams.size} source(s) in ${System.currentTimeMillis() - t2}ms" +
                (if (err.isNullOrBlank()) "" else "  reason=\"$err\"")
        )
        streams.take(3).forEach { println("        · ${it.name.take(70)}  ${it.url.take(90)}") }
        if (streams.isEmpty()) {
            warn(
                "$packageName produced a source for its first item",
                (err ?: "no reason recorded").take(220),
            )
        } else {
            check(
                "$packageName's stream rows parse into StreamSource",
                streams.all { it.url.isNotBlank() || it.infoHash != null },
                "a row with neither a url nor an infoHash",
            )
            streamOk++
        }

        // A runtime-level failure is our bug, not the site's: the engine could not
        // run the plugin, or the payload it handed back was not JSON at all.
        val ours = err?.let {
            it.contains("extension did not answer") || it.contains("extension file missing")
        } == true
        if (ours) check("$packageName fails for a reason inside Hikari, not the site", false, err.orEmpty().take(300))
    }

    check("at least one real SkyStream plugin installs and loads", installedOk > 0, "installed=$installedOk")
    check("at least one real SkyStream plugin answers a plugin call", calledOk > 0, "answered=$calledOk")
    if (streamOk == 0) {
        println(
            "  NOTE no SkyStream plugin returned a source this run — that can be the sites (they are " +
                "third-party and change daily), but a build where NONE of ${picks.size} works is the " +
                "report this test exists for."
        )
    }

    println(
        "SkyStreamSelfTest: " +
            (if (failures == 0) "OK" else "$failures FAILED") +
            " (installed=$installedOk, answered getHome=$calledOk, produced sources=$streamOk, warnings=$warns)"
    )
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
