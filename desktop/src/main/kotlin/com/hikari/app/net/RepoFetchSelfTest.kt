package com.hikari.app.net

/**
 * CI test for the repos out of the bug reports, against the REAL hosts.
 *
 * The failure it guards against was not a network one: the file came back fine
 * and the app threw it away. `fetchRepoJson` accepted only CloudStream shapes
 * (`plugins`, `pluginLists`) and returned null for anything else, in silence —
 * so a Nuvio repo (`manifest.json` with a `scrapers` array) could never load,
 * and the reason the user was shown was whichever OTHER candidate failed loudly
 * (a third-party mirror's expired certificate, typically). A bare-ARRAY index
 * (Aniyomi/Mihon `index.json`) failed one step earlier, on `JSONObject(text)`.
 *
 * Every shape a repo can have is fetched here, and the ladder's own candidate
 * list is printed, so a mirrored URL that cannot work is visible instead of
 * guessed at. Needs the network (that is the point), so a runner without
 * internet fails loudly rather than passing quietly.
 */
fun main() {
    println("RepoFetchSelfTest: start")
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println(if (ok) "  OK   $name" else "  FAIL $name $detail")
        if (!ok) failures++
    }

    Http.init()

    // The exact URLs from the screenshots: a Nuvio manifest, and a Mihon/Aniyomi
    // index. Both answer HTTP 200 in a browser.
    val nuvio = "https://raw.githubusercontent.com/D3adlyRocket/All-In-One-Nuvio/refs/heads/main/manifest.json"
    val mihon = "https://raw.githubusercontent.com/codegeasse1/codegeasse-mihon-extension/repo/index.json"

    for (url in listOf(nuvio, mihon)) {
        val name = Http.repoDisplayName(url)
        println("— " + name)
        println("  url: " + url)
        val origins = runCatching { Http.originVariants(url) }.getOrDefault(emptyList())
        val mirrors = runCatching { Http.mirrorVariants(url) }.getOrDefault(emptyList())
        println("  origins (" + origins.size + "):")
        origins.forEach { println("    " + it) }
        println("  mirrors (" + mirrors.size + "):")
        mirrors.forEach { println("    " + it) }
        check("the URL it was given is tried first for " + name, origins.contains(url))
        // The CDN mirrors expect a bare branch: a "…@refs/heads/main/…" URL 404s,
        // which is how a repo add ends up reporting mirrors that cannot work.
        val branchStyle = mirrors.filter { it.contains("jsdelivr") || it.contains("githack") || it.contains("b-cdn") }
        check(
            "a branch-style mirror is given the branch, not the ref path (" + name + ")",
            branchStyle.isNotEmpty() && branchStyle.none { it.contains("refs/heads") },
            branchStyle.joinToString(" "),
        )
        val t0 = System.currentTimeMillis()
        val r = Http.fetchRepoJson(url)
        val ms = System.currentTimeMillis() - t0
        val got = r.getOrNull()
        if (got == null) {
            check(
                "the repo file is fetched for " + name,
                false,
                "after " + ms + "ms: " + (r.exceptionOrNull()?.message ?: "?"),
            )
        } else {
            // fetchRepoJson answers with (url that served it, the file text).
            println("  fetched in " + ms + "ms via " + got.first + " (" + got.second.length + " chars)")
            val text = got.second.trim()
            check("the repo file is fetched for " + name, true)
            check("and it is JSON of a repo shape for " + name, text.startsWith("{") || text.startsWith("["), text.take(60))
            if (text.startsWith("{")) {
                val keys = runCatching {
                    val o = org.json.JSONObject(text)
                    listOf("plugins", "pluginLists", "scrapers").filter { o.has(it) }
                }.getOrDefault(emptyList())
                check("it carries one of the repo keys the screen reads (" + name + ")", keys.isNotEmpty(), keys.toString())
            } else {
                val n = runCatching { org.json.JSONArray(text).length() }.getOrDefault(0)
                check("it is an index array the screen can read (" + name + ")", n > 0, "length=" + n)
            }
        }
    }

    // A file that cannot be a repo has to SAY so instead of borrowing the reason
    // from another host's failure — that is what made a repo that HAD loaded look
    // like a certificate problem.
    val nonsense = Http.fetchRepoJson("https://raw.githubusercontent.com/codegeasse1/hikari-desktop/main/gradle.properties")
    val why = nonsense.exceptionOrNull()?.message ?: ""
    println("  a file that is not a repo reports: " + why.take(200))
    check(
        "a non-manifest file is named as such",
        why.contains("not JSON") || why.contains("web page") || why.contains("not a"),
        why.take(140),
    )

    if (failures > 0) {
        println("RepoFetchSelfTest: $failures FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RepoFetchSelfTest: OK")
    kotlin.system.exitProcess(0)
}
