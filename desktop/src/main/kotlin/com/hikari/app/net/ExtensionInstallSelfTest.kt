package com.hikari.app.net

import org.json.JSONObject
import java.io.File

/**
 * CI test for the failures the bug reports are about: "all extensions failing to
 * install", and the two reasons the Extensions screen showed for them —
 * "Download failed for <name> — no mirror served the file" and "the TLS
 * handshake is being blocked by this network".
 *
 * The root cause it pins down: the official Hikari repo publishes EVERY one of
 * its extensions as a GitHub RELEASE ASSET
 * (`github.com/<owner>/<repo>/releases/download/<tag>/<file>`), and the mirror
 * list only ever knew how to mirror RAW file paths — so a release asset had
 * exactly one candidate URL and no mirror at all. On a network where github.com
 * is blocked or TLS-filtered, installing anything from that repo could only
 * fail, however long the ladder walked.
 *
 * What is checked, against the real hosts:
 *
 *  1. every URL shape those repos publish gets mirrors — including the release
 *     assets (which must be offered the GitHub proxy frontdoors), so no install
 *     can ever again have a single candidate;
 *  2. a real extension (a release asset, from the live repo manifest) downloads
 *     through `Http.downloadToRobust` and really is a zip;
 *  3. a real Nuvio scraper (a raw file) downloads through `Http.fetchBytesRobust`;
 *  4. the OS's own HTTP client — curl.exe/PowerShell, i.e. Schannel and the
 *     Windows certificate store, the stack the user's browser uses — exists on
 *     this machine and can fetch a release asset that the JVM's stack could not
 *     reach on the user's network.
 *
 * Needs the network (that is the point), so a runner without internet fails
 * loudly instead of passing quietly.
 */
fun main() {
    println("ExtensionInstallSelfTest: start")
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

    Http.init()

    // ── 1. every URL shape gets mirrors ─────────────────────────────────────
    val releaseAsset = "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/anime.jar"
    val rawFile = "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/providers/4khdhub.js"
    val repoFile = "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo-desktop.json"

    for ((label, url) in listOf(
        "a release asset" to releaseAsset,
        "a raw file" to rawFile,
        "a repo manifest" to repoFile,
    )) {
        val origins = Http.originVariants(url)
        val mirrors = Http.mirrorVariants(url)
        println("— $label: $url")
        println("    origins (${origins.size}): " + origins.joinToString(" "))
        println("    mirrors (${mirrors.size}): " + mirrors.joinToString(" "))
        check("$label is its own first candidate", origins.firstOrNull() == url, origins.firstOrNull() ?: "none")
        check("$label has at least 3 mirrors", mirrors.size >= 3, "got ${mirrors.size}")
        // A frontdoor is handed the FULL GitHub URL — that is the only kind of
        // mirror a release asset can have, and what this fix is.
        check(
            "$label can go through a GitHub frontdoor",
            mirrors.any { it.contains("ghfast.top/") || it.contains("ghproxy.net/") || it.contains("gh-proxy.com/") },
            mirrors.joinToString(" "),
        )
        if (label == "a release asset") {
            check(
                "a release asset is NOT offered a CDN mirror that cannot serve it",
                mirrors.none { it.contains("jsdelivr") || it.contains("githack") },
                mirrors.joinToString(" "),
            )
            check(
                "a release asset keeps its own path in every mirror",
                mirrors.all { it.contains("/releases/download/continuous/anime.jar") },
                mirrors.joinToString(" "),
            )
        }
    }

    // ── 2. every live extension the official repo lists has a mirror ─────────
    val liveRepo = "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo-desktop.json"
    val manifest = runCatching { JSONObject(Http.fetchStringRobust(liveRepo).getOrThrow()) }.getOrNull()
    val listed = manifest?.optJSONArray("plugins") ?: org.json.JSONArray()
    println("— the live Hikari repo lists ${listed.length()} extensions")
    var unmirrored = 0
    for (i in 0 until listed.length()) {
        val url = listed.optJSONObject(i)?.optString("url").orEmpty()
        if (url.isBlank()) continue
        if (!Http.isGithubUrl(url)) continue
        if (Http.mirrorVariants(url).isEmpty()) {
            unmirrored++
            if (unmirrored <= 5) println("    no mirror for $url")
        }
    }
    check(
        "no extension in the live repo is left with a single candidate URL",
        listed.length() > 0 && unmirrored == 0,
        "${listed.length()} listed, $unmirrored without mirrors",
    )

    // ── 3. a real extension downloads (and is a real archive) ───────────────
    val dest = File.createTempFile("hikari-install-test-", ".jar").apply { deleteOnExit() }
    val attempts = ArrayList<String>()
    val t0 = System.currentTimeMillis()
    val ok = Http.downloadToRobust(releaseAsset, dest) { tried, success, reason ->
        if (!success) attempts.add(tried.substringAfter("//").substringBefore('/') + " — " + reason)
    }
    val ms = System.currentTimeMillis() - t0
    println("— downloading the Anime extension: ok=$ok in ${ms}ms, ${dest.length()} bytes" +
        if (attempts.isEmpty()) "" else "  (" + attempts.size + " host(s) failed)")
    attempts.take(8).forEach { println("    $it") }
    check("the release asset downloads", ok, "after ${ms}ms")
    val head = runCatching { dest.inputStream().use { s -> s.readNBytes(4) } }.getOrDefault(ByteArray(0))
    check(
        "what landed is a real archive (PK header), not an error page",
        head.size == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte(),
        head.joinToString(" ") { (it.toInt() and 0xFF).toString(16) },
    )
    check("a real extension arrives in seconds, not minutes", ms < 30_000L, "${ms}ms")

    // ── 4. a real Nuvio scraper (raw file) downloads ────────────────────────
    val t1 = System.currentTimeMillis()
    val scraper = Http.fetchBytesRobust(rawFile)
    val ms2 = System.currentTimeMillis() - t1
    println("— downloading a Nuvio scraper: ${scraper?.size ?: 0} bytes in ${ms2}ms")
    check("the Nuvio scraper downloads", scraper != null && scraper.isNotEmpty(), "after ${ms2}ms")
    check(
        "the scraper is script text, not a web page",
        scraper != null && !Http.isWebPage(scraper) && scraper.size > 32,
        "" + scraper?.size,
    )

    // ── 5. the OS's own HTTP client (curl/PowerShell, i.e. the user's stack) ─
    val tool = Http.osHttpClient()
    println("— the OS HTTP client on this machine: " + (tool ?: "none"))
    if (tool == null) {
        warn("no OS HTTP client on this machine — the Schannel/curl rescue pass is untested here")
    } else {
        val osDest = File.createTempFile("hikari-osdl-test-", ".jar").apply { deleteOnExit() }
        val t2 = System.currentTimeMillis()
        val why = Http.osFetchToFile(releaseAsset, osDest)
        val ms3 = System.currentTimeMillis() - t2
        println("— OS client fetch of the release asset: ${if (why == null) "ok" else why} in ${ms3}ms, ${osDest.length()} bytes")
        check("the OS client can fetch a GitHub release asset (the pass that rescues a filtered network)", why == null, why ?: "")
        val osHead = runCatching { osDest.inputStream().use { s -> s.readNBytes(4) } }.getOrDefault(ByteArray(0))
        check(
            "the OS client's file is a real archive too",
            osHead.size == 4 && osHead[0] == 'P'.code.toByte() && osHead[1] == 'K'.code.toByte(),
            osHead.joinToString(" ") { (it.toInt() and 0xFF).toString(16) },
        )
        val text = Http.osFetchString("https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo-desktop.json")
        check(
            "the OS client can fetch a repo manifest as text",
            text != null && text.trimStart().startsWith("{"),
            "" + text?.length,
        )
    }

    println(
        "ExtensionInstallSelfTest: " + if (failures == 0) "OK" + (if (warns > 0) " ($warns warning(s))" else "") else "$failures FAILED",
    )
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
