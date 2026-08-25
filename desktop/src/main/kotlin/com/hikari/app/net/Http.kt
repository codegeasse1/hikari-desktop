package com.hikari.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Http {

    /** Desktop Chrome UA, matching CloudStream's own USER_AGENT. The WAFs that
     *  guard the TamilBlasters/StreamHG/luluvdo family serve their player pages
     *  and HLS CDNs to desktop browsers (the plugins' own requests even use a
     *  desktop Chrome 149); a mobile "… Mobile Safari" UA stands out to those
     *  WAFs and some answer 403 before ever checking the token. */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Same current-Chrome fingerprint the WebView uses so probes and the site
     *  agree on what browser is "visiting" (Cloudflare checks consistency). */
    const val WEBVIEW_UA = UA

    private lateinit var client: OkHttpClient

    fun init() {
        // Align with the browser: on networks where the OS resolver is filtered
        // (or a local proxy/VPN must be used), the system ProxySelector and the
        // DoH-first resolver below let the app reach what the user's browser can.
        System.setProperty("java.net.useSystemProxies", "true")
        client = applyConscryptTls(OkHttpClient.Builder())
            .proxySelector(java.net.ProxySelector.getDefault())
            .dns(HikariDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS) // was 20, now 10 for superfast
            .readTimeout(15, TimeUnit.SECONDS) // was 30, now 15 for superfast
            .build()
    }

    /** Explicitly pins every client to Conscrypt (BoringSSL). This is critical:
     *  the JVM's lazy `sun.security.ssl.SSLSessionImpl` class-init can fail
     *  fatally on Windows (NoClassDefFoundError) once Conscrypt is installed as
     *  the default provider, so the JDK SSL stack must never be touched at all —
     *  a plain `.sslSocketFactory` from the JDK default context is a landmine. */
    fun applyConscryptTls(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            if (java.security.Security.getProvider("Conscrypt") == null) {
                java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            }
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance("X509")
            tmf.init(null as java.security.KeyStore?)
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, tmf.trustManagers, null)
            return builder.sslSocketFactory(
                ctx.socketFactory,
                tmf.trustManagers[0] as javax.net.ssl.X509TrustManager,
            )
        } catch (t: Throwable) {
            System.err.println("applyConscryptTls failed: $t")
            return builder
        }
    }

    /** Runs the call. No JDK-TLS retry: the Conscrypt stack above is the one and
     *  only TLS path — a broken alternative that touches sun.security.ssl can
     *  poison the JVM (NoClassDefFoundError: SSLSessionImpl). */
    private fun execute(client: OkHttpClient, request: Request): Response =
        client.newCall(request).execute()

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(client, builder.build())
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(client, builder.build())
    }

    fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = try {
        post(url, body, headers, contentType).use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /** Adds https:// when a scheme is missing and trims stray quotes. */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'')
        if (u.isBlank()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    private fun ghRaw(out: MutableSet<String>, user: String, repo: String, branch: String) {
        // Desktop first: the desktop app runs JVM .jar extensions, and the
        // official repos publish repo-desktop.json with jar URLs. Fall back to
        // the dex (.hiki) repo.json only when no desktop variant exists.
        out.add("https://raw.githubusercontent.com/$user/$repo/$branch/repo-desktop.json")
        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/repo-desktop.json")
        out.add("https://raw.githubusercontent.com/$user/$repo/$branch/repo.json")
        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/repo.json")
    }

    /**
     * Candidate URLs for a repo.json. Accepts a direct repo.json URL, a raw
     * GitHub URL (with or without the full path) or a github.com repo page —
     * trying main/master and a jsDelivr mirror — so pasting the repo's GitHub
     * page just works instead of 404ing.
     */
    fun repoJsonCandidates(raw: String): List<String> {
        val url = normalizeUrl(raw)
        val out = linkedSetOf(url)
        Regex("^https?://github\\.com/([^/]+)/([^/]+?)(?:/tree/([^/]+))?(?:/.*)?$")
            .matchEntire(url)?.let { m ->
                val user = m.groupValues[1]
                val repo = m.groupValues[2]
                val branch = m.groupValues[3].ifBlank { "main" }
                ghRaw(out, user, repo, branch)
                if (branch != "main") ghRaw(out, user, repo, "main")
                ghRaw(out, user, repo, "master")
                // CloudStream convention: the repo manifest lives on the builds
                // branch (builds/repo.json), so pasting a CloudStream repo page
                // should find it too.
                ghRaw(out, user, repo, "builds")
            }
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)(?:/([^/]+))?(?:/(.*))?$")
            .matchEntire(url)?.let { m ->
                val user = m.groupValues[1]
                val repo = m.groupValues[2]
                var branch = m.groupValues[3]
                var path = m.groupValues[4]
                // Fully-qualified refs (…/u/r/refs/heads/<branch>/p): normalize
                // so the generated mirror URLs carry a bare branch.
                if (branch == "refs" && (path.startsWith("heads/") || path.startsWith("tags/"))) {
                    val rest = path.substringAfter('/')
                    val cut = rest.indexOf('/')
                    if (cut > 0) {
                        branch = rest.substring(0, cut)
                        path = rest.substring(cut + 1)
                    }
                }
                if (branch.isNotBlank()) {
                    if (path.isBlank()) {
                        ghRaw(out, user, repo, branch)
                    } else {
                        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
                        if (!path.endsWith(".json")) ghRaw(out, user, repo, branch)
                        else if (path.contains("repo.json") && !path.contains("repo-desktop.json")) {
                            // A repo.json path on any branch (e.g. builds/repo.json)
                            // is usually the dex-only Android repo. Also offer the
                            // desktop repo-desktop.json on the same branch and on
                            // main, so the desktop app finds the .jar build
                            // instead of dead .hiki URLs.
                            ghRaw(out, user, repo, branch)
                            ghRaw(out, user, repo, "main")
                            // CloudStream convention: the manifest lives on the
                            // builds branch even when the pasted link says main.
                            if (branch != "builds") ghRaw(out, user, repo, "builds")
                        }
                    }
                } else {
                    ghRaw(out, user, repo, "main")
                    ghRaw(out, user, repo, "master")
                }
            }
        return out.toList()
    }

    /** Mirrors of official repos on a CDN that works even where GitHub is slow
     *  or blocked. Regenerate whenever the source repo.json changes. */
    private const val HIKARI_REPO_MIRROR = "https://user.uploads.dev/file/160d14f91512b838449f155070cb0c58.json"
    private const val CLOUDSTREAM_REPO_MIRROR = "https://user.uploads.dev/file/42f88079718447227d8bf7ccc5a5e286.txt"

    /** Hard cap for a repo fetch so a slow/blocked network fails with a clear
     *  error instead of leaving the UI stuck on "Checking…" for minutes. */
    private const val REPO_FETCH_DEADLINE_MS = 60_000L

    /** Overall budget for one extension download (all mirrors + retries). */
    private const val DOWNLOAD_BUDGET_MS = 90_000L

    /** Fetches a repo.json, trying every candidate URL. Only accepts a response
     *  that is actually a JSON object with a "plugins" key — or a CloudStream v2
     *  manifest whose "pluginLists" files hold the plugins array — a github.com
     *  HTML page or a CDN error body is skipped instead of being passed to the UI.
     *
     *  repo-desktop.json candidates are tried before repo.json ones: the desktop
     *  app runs JVM jars, and the desktop repos publish repo-desktop.json with
     *  .jar URLs — plain repo.json is the Android dex repo. [onStep] reports
     *  progress so the UI can show which candidate is being tried. */
    fun fetchRepoJson(raw: String, onStep: ((String) -> Unit)? = null): Result<Pair<String, String>> {
        val candidates = repoJsonCandidates(raw)
        val ordered = candidates.filter { it.contains("repo-desktop.json") } +
            candidates.filter { !it.contains("repo-desktop.json") }
        val list = if (ordered.any { it.contains("codegeasse1/hikari-extensions") }) {
            // The official repo resolves to the CDN mirror FIRST: the mirror
            // serves every plugin's jar from user.uploads.dev, which works even
            // on networks where GitHub (github.com + raw + jsDelivr) is blocked
            // or refused by the app's HTTP stack. Regenerate the mirror whenever
            // the official repo.json changes.
            listOf(HIKARI_REPO_MIRROR) + ordered
        } else if (ordered.any { it.contains("codegeasse1/codegeasse-cloudstream-repos") }) {
            listOf(CLOUDSTREAM_REPO_MIRROR) + ordered
        } else {
            ordered
        }
        val deadline = System.currentTimeMillis() + REPO_FETCH_DEADLINE_MS
        var last: Throwable = Exception("No candidate URL served a valid repo.json")
        var tried = 0
        for (u in list) {
            if (System.currentTimeMillis() > deadline) {
                return Result.failure(
                    Exception("Timed out after ${REPO_FETCH_DEADLINE_MS / 1000}s — the repo host is unreachable from this network.")
                )
            }
            tried++
            onStep?.invoke("Fetching repo… ($tried/${list.size}) $u")
            val r = fetchStringRobust(u)
            if (r.isSuccess) {
                val text = r.getOrThrow()
                val root = runCatching { org.json.JSONObject(text) }.getOrNull()
                if (root != null) {
                    if (root.has("plugins")) return Result.success(u to text)
                    // CloudStream v2 manifests (manifestVersion + pluginLists):
                    // fetch the pluginLists file and merge its plugins array in,
                    // so callers keep seeing a plain "plugins" key.
                    if (root.has("pluginLists")) {
                        val merged = resolvePluginLists(root, u)
                        if (merged != null) return Result.success(u to merged)
                        last = Exception("repo.json listed no readable plugins list ($u)")
                        continue
                    }
                }
                last = Exception("not a repo.json ($u)")
                continue
            }
            r.exceptionOrNull()?.let { last = it }
        }
        return Result.failure(last)
    }

    /** Resolves [url] against [baseUrl] when it is relative. The CloudStream
     *  repo spec allows plugin lists ("plugins.json") and plugin URLs
     *  ("builds/X.cs3", "X.cs3") relative to the file that referenced them —
     *  the Android client resolves them that way, and a plain
     *  "https://plugins.json" guess is DNS-dead, so resolving correctly is
     *  what makes template-style v2 repos work at all. NB: a relative FILE
     *  name contains dots ("plugins.json"), so a dot alone must NEVER be
     *  taken as "this looks like a hostname" — anything without a scheme is
     *  resolved against [baseUrl]. */
    fun resolveRelativeTo(url: String, baseUrl: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (baseUrl.isBlank()) return normalizeUrl(u)
        return runCatching { java.net.URI(baseUrl.trim()).resolve(u).toString() }
            .getOrDefault(normalizeUrl(u))
    }

    /** Fetches a CloudStream v2 manifest's `pluginLists` files and returns the
     *  manifest text with every usable plugins array merged under a "plugins"
     *  key (null when no list URL serves plugins). List URLs and plugin URLs
     *  may be relative to the repo.json / list file — both are resolved. */
    private fun resolvePluginLists(root: org.json.JSONObject, repoUrl: String): String? {
        val lists = root.optJSONArray("pluginLists") ?: return null
        val merged = org.json.JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until lists.length()) {
            val raw = lists.optString(i)
            if (raw.isBlank()) continue
            val listUrl = resolveRelativeTo(raw, repoUrl)
            val pr = fetchStringRobust(listUrl)
            if (!pr.isSuccess) continue
            val text = pr.getOrThrow()
            val plugins: org.json.JSONArray? = runCatching { org.json.JSONArray(text) }.getOrNull()
                ?: runCatching {
                    val o = org.json.JSONObject(text)
                    when (val p = o.opt("plugins")) {
                        null -> null
                        is org.json.JSONArray -> p
                        else -> org.json.JSONArray(p)
                    }
                }.getOrNull()
            if (plugins == null) continue
            for (j in 0 until plugins.length()) {
                val p = plugins.optJSONObject(j) ?: continue
                val u = p.optString("url")
                if (u.isBlank()) continue
                // Relative plugin URLs resolve against the plugins-list file.
                val abs = resolveRelativeTo(u, listUrl)
                if (!seen.add(abs)) continue
                if (abs != u) p.put("url", abs)
                merged.put(p)
            }
        }
        if (merged.length() == 0) return null
        return runCatching { root.put("plugins", merged).toString() }.getOrNull()
    }

    /** Short human-readable reason for a failed network call: collapses the
     *  multi-line Conscrypt/BoringSSL TLS noise into a single line. */
    fun humanMessage(t: Throwable?): String {
        val raw = t?.message?.trim().orEmpty().ifBlank { t?.javaClass?.simpleName ?: "unknown network error" }
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
        return firstLine
            .replace(Regex("ssl=[0-9A-Fa-f]+: "), "")
            .replace("error:10000089:SSL routines:OPENSSL_internal:DECODE_ERROR", "TLS handshake failed")
            .trim()
            .ifBlank { "unknown network error" }
    }

    /** Fixes stream URLs that some providers hand back half-escaped or relative:
     *  JSON `\/`/`\uXXXX` escapes, stray quotes/whitespace, and chaturbate's
     *  signed LL-HLS path — which arrives scheme-less with backslash separators,
     *  an escaped root-relative path, or a bare host prefix with no scheme.
     *  Returns the clean, playable URL (an unrewriteable value passes through). */
    fun sanitizeStreamUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'')
        if (u.isBlank()) return u
        // JSON escapes a JS-embedded URL commonly carries (before \\ → \\ so the
        // \\uXXXX patterns still match).
        u = u
            // Decode EVERY \uXXXX escape (chaturbate's dossier escapes quotes
            // as \u0022 and may escape any other char too) BEFORE collapsing
            // the JSON backslash escapes.
            .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
        // RFC 3986 URL characters. EVERYTHING else (real backslashes, escaped
        // slashes, or the lookalike Unicode separators chaturbate's dossier can
        // slip in) is treated as a path separator.
        val safe = "[^0-9A-Za-z._~:/?#\\[\\]@!'()*+,;=%&-]"
        // A chaturbate edge stream, found wherever it hides in the string and
        // whatever junk/separator precedes it — match ANY non-URL character
        // between the path segments, then rebuild it on the edge host.
        val edgeMatch = Regex("v1$safe+edge$safe+streams$safe+(.+)$").find(u)
        if (edgeMatch != null) {
            val host = run {
                var h = u.substringBefore(edgeMatch.value).trim().trimStart('/')
                if (h.startsWith("https://")) h = h.removePrefix("https://")
                else if (h.startsWith("http://")) h = h.removePrefix("http://")
                h = h.substringBefore('/').substringBefore('?').substringBefore('#')
                h.takeIf {
                    it.isNotBlank() && it.contains('.') &&
                        it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
                }
            } ?: "edge-hls.chaturbate.com"
            val cleanPath = edgeMatch.value.replace(Regex(safe), "/").replace(Regex("/{2,}"), "/")
            return "https://$host/edge-hls/$cleanPath"
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            // Separators that are never legal in a URL (backslashes, escaped
            // slashes, lookalikes) normalize to `/` so mpv never sees them.
            val hostEnd = u.indexOf('/', u.indexOf("://") + 3)
            return if (hostEnd > 0) {
                u.substring(0, hostEnd + 1) + u.substring(hostEnd + 1).replace(Regex(safe), "/")
            } else {
                u.replace(Regex(safe), "/")
            }
        }
        return u
    }

    /** Verdict on a stream URL before the player commits to it. mpv answers a
     *  source whose server returns a web page, a JSON error body, or a
     *  browser-only blob:/data: URL with a cryptic "file format not supported"
     *  — so probe the first bytes and classify. */
    enum class StreamProbe { HLS, VIDEO, DASH, HTML, JSON, UNKNOWN }

    /** Fast classifier client: short timeouts, system resolver (no DoH) so a
     *  filtered network fails fast as UNKNOWN and the player's own DoH proxy
     *  path takes over instead of stalling playback. */
    private val probeClient: OkHttpClient? by lazy {
        try {
            applyConscryptTls(OkHttpClient.Builder())
                .proxySelector(java.net.ProxySelector.getDefault())
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        } catch (t: Throwable) {
            null
        }
    }

    /** Fetches the first bytes of a stream URL (with the provider's Referer /
     *  Cookie / UA headers) and classifies the response. Only definite answers
     *  are trusted — an HTTP error, a non-2xx, or anything ambiguous returns
     *  UNKNOWN, meaning "let the player try" (the player often succeeds where a
     *  bare probe is refused). */
    fun probeStreamUrl(raw: String, headers: Map<String, String> = emptyMap()): StreamProbe {
        val url = raw.trim().trim('"', '\'')
        if (url.startsWith("blob:") || url.startsWith("data:")) return StreamProbe.HTML
        if (!url.startsWith("http://") && !url.startsWith("https://")) return StreamProbe.UNKNOWN
        val pc = probeClient ?: return StreamProbe.UNKNOWN
        return try {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Range", "bytes=0-2047")
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) b.header(k, v) }
            pc.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // A 403 with an HTML body is a WAF/geo/login page — classify
                    // it so the player shows a clean message instead of raw
                    // [curl]/[ytdl_hook] error noise.
                    if (resp.code == 403) {
                        val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
                        val head = String(body, 0, minOf(body.size, 2048), Charsets.UTF_8)
                            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) {
                            return StreamProbe.HTML
                        }
                    }
                    return StreamProbe.UNKNOWN
                }
                val ct = (resp.header("Content-Type") ?: "").lowercase()
                if (ct.contains("mpegurl") || ct.contains("hls")) return StreamProbe.HLS
                if (ct.contains("dash+xml") || ct.endsWith("/mpd")) return StreamProbe.DASH
                if (ct.startsWith("text/html") || ct.contains("html")) return StreamProbe.HTML
                if (ct.contains("json")) return StreamProbe.JSON
                if (ct.startsWith("video/") || ct.startsWith("audio/")) return StreamProbe.VIDEO
                val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
                val head = String(body, 0, minOf(body.size, 2048), Charsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                if (head.startsWith("#EXTM3U")) return StreamProbe.HLS
                if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) return StreamProbe.HTML
                if (head.startsWith("<?xml") && head.contains("<MPD")) return StreamProbe.DASH
                if (head.startsWith("{") || head.startsWith("[")) return StreamProbe.JSON
                StreamProbe.UNKNOWN
            }
        } catch (e: Exception) {
            StreamProbe.UNKNOWN
        }
    }

    /** Human-friendly repo name from a URL: "codegeasse1/hikari-extensions"
     *  for github/raw URLs, the host otherwise. */
    fun repoDisplayName(url: String): String {
        val clean = normalizeUrl(url).removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val parts = clean.split("/").filter { it.isNotBlank() }
        return if (parts.size >= 3 && (parts[0] == "github.com" || parts[0] == "raw.githubusercontent.com")) {
            parts[1] + "/" + parts[2]
        } else {
            parts.firstOrNull() ?: clean
        }
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }

    /**
     * Streams a download to [dest], reporting (downloadedBytes, totalBytes) through
     * [onProgress] on each chunk. totalBytes is -1 when unknown. Returns true on success.
     */
    fun downloadTo(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean = downloadToReason(url, dest, headers, onProgress) == null

    /** [downloadTo] that reports WHY it failed: null on success, a short
     *  human-readable reason ("HTTP 404", "UnknownHostException: …") on failure. */
    private fun downloadToReason(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        via: OkHttpClient? = null,
    ): String? = try {
        getOn(via ?: client, url, headers).use { resp ->
            if (!resp.isSuccessful) {
                "HTTP ${resp.code}"
            } else {
                val body = resp.body ?: return@use "empty response body"
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress?.invoke(done, total)
                        }
                    }
                }
                null
            }
        }
    } catch (e: Exception) {
        runCatching { dest.delete() }
        humanMessage(e)
    }

    fun getStringStrict(url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        getStringStrictOn(client, url, headers)

    private fun getStringStrictOn(c: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        try {
            getOn(c, url, headers).use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** GET on an explicit client (see [noProxyClient]). */
    private fun getOn(c: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(c, builder.build())
    }

    private val GITHUB_RAW =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
    private val GITHUB_ALT =
        Regex("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)/(.+)$")

    /** A GitHub raw URL split into user/repo/ref/path. */
    private data class GhTarget(val user: String, val repo: String, val ref: String, val path: String)

    /**
     * Parses a GitHub raw URL (`raw.githubusercontent.com/u/r/b/p` or
     * `github.com/u/r/raw|blob/b/p`). Modern CloudStream repos hand out the
     * fully-qualified form `…/u/r/refs/heads/<branch>/p`, which the naive
     * regex splits as ref="refs" + path="heads/<branch>/p" — every mirror URL
     * built from that 404s. The ref is normalized here ("refs/heads/builds"
     * → "builds") so the CDN mirrors, which expect a bare branch, work.
     */
    private fun parseGhTarget(url: String): GhTarget? {
        val m = GITHUB_RAW.matchEntire(url) ?: GITHUB_ALT.matchEntire(url) ?: return null
        val (user, repo, ref0, path0) = m.destructured
        var ref = ref0
        var path = path0
        if (ref == "refs" && (path.startsWith("heads/") || path.startsWith("tags/"))) {
            val rest = path.substringAfter('/')
            val cut = rest.indexOf('/')
            if (cut > 0) {
                ref = rest.substring(0, cut)
                path = rest.substring(cut + 1)
            }
        }
        if (path.isBlank()) return null
        return GhTarget(user, repo, ref, path)
    }

    /**
     * Download sources for a URL. For GitHub-hosted files a chain of public
     * mirrors is generated so a single blocked/unreachable host can't kill an
     * install — networks routinely block raw.githubusercontent.com or throttle
     * it (~60 req/hr per IP) while the CDNs still work, and vice versa:
     *
     *  1. jsDelivr — global CDN mirror of GitHub files (rate-limit-free),
     *  2. the original URL as typed,
     *  3. the canonical raw URL (refs/heads forms normalized, query stripped),
     *  4. statically.io — a second independent CDN,
     *  5. raw.githack.com — a third,
     *  6. github.com's own /raw/ path (serves the bytes, follows redirects),
     *  7-8. GitHub proxy frontdoors (ghfast.top, ghproxy.net) — different
     *     hostnames that stream the same raw files, for networks that
     *     SNI-block/TLS-break every GitHub-family host. Last resort only.
     *
     * Google Drive share links are rewritten to the direct-download form
     * first (otherwise the "file" downloaded is a virus-scan HTML page).
     */
    private fun urlVariants(url: String): List<String> {
        val base = normalizeDriveUrl(url.trim())
        val gh = parseGhTarget(base) ?: return listOf(base)
        // Mirror URLs are built from the bare path — a query string that is
        // fine on raw.githubusercontent ("…?token=x") makes CDN/proxy mirrors
        // answer HTTP 400, so it never leaks into generated variants.
        val p = gh.path.substringBefore('?')
        val raw = "https://raw.githubusercontent.com/${gh.user}/${gh.repo}/${gh.ref}/$p"
        val out = linkedSetOf<String>()
        out.add("https://cdn.jsdelivr.net/gh/${gh.user}/${gh.repo}@${gh.ref}/$p")
        out.add(base)
        out.add(raw)
        out.add("https://cdn.statically.io/gh/${gh.user}/${gh.repo}/${gh.ref}/$p")
        out.add("https://raw.githack.com/${gh.user}/${gh.repo}/${gh.ref}/$p")
        out.add("https://github.com/${gh.user}/${gh.repo}/raw/${gh.ref}/$p")
        // Frontdoors for networks where TLS to every GitHub-family host dies
        // (the classic "SSL protocol error" on raw + jsDelivr while normal
        // sites still load). Different hostnames, same files.
        out.add("https://ghfast.top/$raw")
        out.add("https://ghproxy.net/$raw")
        return out.toList()
    }

    /** True when the OS has an HTTP(S) proxy configured — used to decide
     *  whether a final no-proxy rescue pass is worth trying. */    private fun systemProxyInUse(): Boolean {
        return try {
            java.net.ProxySelector.getDefault()
                ?.select(java.net.URI("https://raw.githubusercontent.com/"))
                ?.any { it.type() != java.net.Proxy.Type.DIRECT } == true
        } catch (t: Throwable) {
            false
        }
    }

    /** Same stack as [client] but with the system proxy DISABLED. A leftover
     *  OS proxy config (typical after uninstalling a VPN/Clash/Psiphon) makes
     *  every JVM connection fail with "connection refused"/"connect timed
     *  out" while the browser and the phone on the same network work fine —
     *  the last-resort pass on this client rescues exactly that case. */
    private val noProxyClient: OkHttpClient by lazy {
        applyConscryptTls(OkHttpClient.Builder())
            .proxy(java.net.Proxy.NO_PROXY)
            .dns(HikariDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Final rescue client: Conscrypt TLS pinned to TLS 1.2, no proxy. Some
     *  Windows machines/networks fail every TLS 1.3 handshake inside
     *  Conscrypt ("Read error: Failure in SSL library, usually a protocol
     *  error") while TLS 1.2 goes through — and a broken system proxy can
     *  compound it. Still Conscrypt (never JDK TLS — see applyConscryptTls:
     *  the JDK SSL stack's lazy class-init can kill the JVM once Conscrypt is
     *  the default provider). */
    private val rescueClient: OkHttpClient by lazy {
        try {
            val tls12 = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
                .build()
            applyConscryptTls(OkHttpClient.Builder())
                .proxy(java.net.Proxy.NO_PROXY)
                .connectionSpecs(listOf(tls12))
                .dns(HikariDns)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (t: Throwable) {
            noProxyClient
        }
    }

    fun fetchStringRobust(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var last: Throwable = Exception("Failed to fetch $url")
        val variants = urlVariants(url)
        for (u in variants) {
            for (attempt in 0 until 2) {
                val r = getStringStrict(u, headers)
                if (r.isSuccess) return r
                r.exceptionOrNull()?.let { last = it }
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        // Rescue pass with the system proxy bypassed (see [noProxyClient]).
        if (systemProxyInUse()) {
            for (u in variants) {
                val r = getStringStrictOn(noProxyClient, u, headers)
                if (r.isSuccess) return r
                r.exceptionOrNull()?.let { last = it }
            }
        }
        return Result.failure(last)
    }

    fun fetchBytesRobust(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val b = getBytes(u, headers)
                if (b != null) return b
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        if (systemProxyInUse()) {
            for (u in urlVariants(url)) {
                try {
                    getOn(noProxyClient, u, headers).use { r ->
                        if (r.isSuccessful) return r.body?.bytes()
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /**
     * Streams a download to [dest], walking the mirror chain from
     * [urlVariants]. [onAttempt] reports every mirror that was tried and why
     * it failed (ok=true on the one that succeeded) so the UI can show the
     * REAL cause instead of a generic "check your connection".
     *
     * A final rescue pass repeats every mirror with the system proxy
     * bypassed ([noProxyClient]) — the classic "browser works, JVM doesn't"
     * desktop misconfiguration.
     */
    fun downloadToRobust(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        onAttempt: ((url: String, ok: Boolean, reason: String?) -> Unit)? = null,
    ): Boolean {
        val variants = urlVariants(url)
        // Overall budget: on a black-hole network every mirror costs a full
        // connect timeout; bound the walk so the UI doesn't sit for minutes
        // (the Android app caps installs at 90s for the same reason). An
        // in-flight download is never interrupted — only new attempts are.
        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
        for (u in variants) {
            if (System.currentTimeMillis() > deadline) {
                onAttempt?.invoke(u, false, "gave up after ${DOWNLOAD_BUDGET_MS / 1000}s total")
                break
            }
            var reason: String? = null
            for (attempt in 0 until 2) {
                reason = downloadToReason(u, dest, headers, onProgress)
                if (reason == null) {
                    onAttempt?.invoke(u, true, null)
                    return true
                }
                try {
                    Thread.sleep(400L)
                } catch (e: InterruptedException) {
                    return false
                }
            }
            onAttempt?.invoke(u, false, reason)
        }
        if (systemProxyInUse()) {
            for (u in variants) {
                if (System.currentTimeMillis() > deadline) break
                val reason = try {
                    downloadToReason(u, dest, headers, onProgress, noProxyClient)
                } catch (t: Throwable) {
                    humanMessage(t)
                }
                if (reason == null) {
                    onAttempt?.invoke(u, true, "(bypassed system proxy)")
                    return true
                }
                onAttempt?.invoke(u, false, "$reason (no proxy)")
            }
        }
        // Final rescue: TLS 1.2 pinned + no proxy, ALWAYS tried — not gated
        // on a proxy being configured, because the failure it fixes (TLS 1.3
        // "SSL protocol error" inside Conscrypt) happens with no proxy at all.
        for (u in variants) {
            if (System.currentTimeMillis() > deadline) break
            val reason = try {
                downloadToReason(u, dest, headers, onProgress, rescueClient)
            } catch (t: Throwable) {
                humanMessage(t)
            }
            if (reason == null) {
                onAttempt?.invoke(u, true, "(TLS 1.2)")
                return true
            }
            onAttempt?.invoke(u, false, "$reason (TLS 1.2)")
        }
        System.err.println("downloadToRobust failed for $url")
        return false
    }

    /**
     * Turns Google Drive share/download URLs into the direct-download form that
     * serves raw file bytes (no virus-scan HTML page). Handles:
     *   drive.google.com/uc?export=download&id=X
     *   drive.google.com/open?id=X
     *   drive.google.com/file/d/<id>/view
     */
    fun normalizeDriveUrl(url: String): String {
        val u = url.trim().trim('"', '\'')
        if (u.isBlank()) return u
        val id = Regex("""drive\.google\.com/(?:uc|open)\?(?:.*&)?id=([^&\s"']+)""")
            .find(u)?.groupValues?.get(1)
            ?: Regex("""drive\.google\.com/file/d/([^/\s"']+)""")
                .find(u)?.groupValues?.get(1)
        return if (id != null) {
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        } else u
    }
}

/**
 * DNS-over-HTTPS resolver used by [HikariDns] and the mpv proxy
 * ([desktop.player.LocalProxy]). Queries Cloudflare then Google over HTTPS —
 * the same "Secure DNS" a desktop browser uses — so hosts that the OS resolver
 * filters (a common ISP/border DNS block) still resolve to their real IPs. The
 * result is cached per-host for 60s.
 */
object DoH {

    // Own OkHttp client on Conscrypt TLS. The JDK's java.net.http client runs
    // on sun.security.ssl, whose class-init fails fatally on Windows
    // (NoClassDefFoundError: SSLSessionImpl) once Conscrypt is installed — so
    // even DNS-over-HTTPS must stay on Conscrypt. Uses the SYSTEM resolver,
    // not [HikariDns] (that would recurse back into DoH).
    private val client: OkHttpClient by lazy {
        Http.applyConscryptTls(OkHttpClient.Builder())
            .proxySelector(java.net.ProxySelector.getDefault())
            .followRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val ENDPOINTS = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
    )

    private data class Entry(val addrs: List<InetAddress>, val expiry: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 60_000L

    fun resolve(host: String): List<InetAddress> {
        cache[host]?.let { if (System.currentTimeMillis() < it.expiry) return it.addrs }
        var addrs = emptyList<InetAddress>()
        for (ep in ENDPOINTS) {
            try {
                val url = "$ep?name=${URLEncoder.encode(host, StandardCharsets.UTF_8)}&type=A"
                val req = Request.Builder().url(url)
                    .header("accept", "application/dns-json")
                    .header("User-Agent", Http.UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = org.json.JSONObject(resp.body?.string() ?: "")
                        val answers = obj.optJSONArray("Answer")
                        if (answers != null) {
                            val ips = mutableListOf<InetAddress>()
                            for (i in 0 until answers.length()) {
                                val a = answers.optJSONObject(i) ?: continue
                                if (a.optInt("type") == 1) {
                                    a.optString("data").takeIf { it.isNotBlank() }?.let { raw ->
                                        runCatching { ips.add(InetAddress.getByName(raw)) }
                                    }
                                }
                            }
                            if (ips.isNotEmpty()) { addrs = ips; break }
                        }
                    }
                }
            } catch (e: Exception) {
                // try the next endpoint
            }
        }
        cache[host] = Entry(addrs, System.currentTimeMillis() + TTL_MS)
        return addrs
    }
}

/**
 * OkHttp [okhttp3.Dns] that prefers DoH ([DoH]) and falls back to the OS
 * resolver. Attached to the app's shared client, so every catalog/stream HTTP
 * request resolves the way the user's browser does.
 */
object HikariDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val doh = runCatching { DoH.resolve(hostname) }.getOrDefault(emptyList())
        if (doh.isNotEmpty()) return doh
        return try {
            okhttp3.Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            throw e
        }
    }
}
