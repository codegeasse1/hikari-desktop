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
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
                val branch = m.groupValues[3]
                val path = m.groupValues[4]
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
                        val merged = resolvePluginLists(root)
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

    /** Fetches a CloudStream v2 manifest's `pluginLists` files and returns the
     *  manifest text with the first usable plugins array merged under a
     *  "plugins" key (null when none of the list URLs serves plugins). */
    private fun resolvePluginLists(root: org.json.JSONObject): String? {
        val lists = root.optJSONArray("pluginLists") ?: return null
        for (i in 0 until lists.length()) {
            val pu = lists.optString(i).ifBlank { continue }
            val pr = fetchStringRobust(normalizeUrl(pu))
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
            if (plugins != null) {
                return runCatching { root.put("plugins", plugins).toString() }.getOrNull()
            }
        }
        return null
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
        // \uXXXX patterns still match).
        u = u
            .replace("\\u002f", "/").replace("\\u0026", "&")
            .replace("\\u003d", "=").replace("\\u003f", "?")
            .replace("\\u0025", "%").replace("\\u0023", "#")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
        if (u.startsWith("http://") || u.startsWith("https://")) {
            // Backslashes are never legal in a URL; a CDN that escaped `/` as
            // `\/` in a JS blob leaks them here. Normalize everything after the
            // scheme://host so mpv never sees them.
            val hostEnd = u.indexOf('/', u.indexOf("://") + 3)
            return if (hostEnd > 0) {
                u.substring(0, hostEnd + 1).replace('\\', '/') +
                    u.substring(hostEnd + 1).replace('\\', '/')
            } else {
                u.replace('\\', '/')
            }
        }
        // Scheme-less. chaturbate serves the signed LL-HLS playlist as a
        // root-relative path on its edge host (backslashes or escaped slashes),
        // sometimes prefixed by the bare host with no scheme.
        val norm = u.replace('\\', '/').trimStart('/')
        if (norm.startsWith("v1/edge/streams/")) {
            return "https://edge-hls.chaturbate.com/edge-hls/" + norm
        }
        if (norm.contains("/v1/edge/streams/")) {
            val hostPart = norm.substringBefore("/v1/edge/streams/")
            val path = norm.substringAfter("/v1/edge/streams/")
            val host = hostPart.takeIf {
                it.isNotBlank() && it.contains('.') &&
                    it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
            } ?: "edge-hls.chaturbate.com"
            return "https://$host/edge-hls/v1/edge/streams/$path"
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
                if (!resp.isSuccessful) return StreamProbe.UNKNOWN
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
    ): Boolean = try {
        get(url, headers).use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
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
            true
        }
    } catch (e: Exception) {
        dest.delete()
        false
    }

    fun getStringStrict(url: String, headers: Map<String, String> = emptyMap()): Result<String> =        try {
            get(url, headers).use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    private val GITHUB_RAW =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

    /**
     * URL + a jsDelivr CDN mirror (global CDN, reachable where GitHub raw often isn't),
     * each retried once. Returns the first success or the last failure.
     */
    private fun urlVariants(url: String): List<String> {
        val variants = mutableListOf(url)
        GITHUB_RAW.matchEntire(url)?.let { m ->
            val user = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3]
            val path = m.groupValues[4]
            variants.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }
        return variants
    }

    fun fetchStringRobust(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var last: Throwable = Exception("Failed to fetch $url")
        for (u in urlVariants(url)) {
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
        return null
    }

    /**
     * Streams a download to [dest], trying the jsDelivr mirror for
     * raw.githubusercontent.com URLs when the primary URL fails. Mirrors
     * [downloadTo]'s signature and semantics.
     */
    fun downloadToRobust(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean {
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                if (downloadTo(u, dest, headers, onProgress)) return true
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    return false
                }
            }
        }
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

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

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
                val req = HttpRequest.newBuilder(URI.create(url))
                    .header("accept", "application/dns-json")
                    .header("User-Agent", Http.UA)
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    val obj = org.json.JSONObject(resp.body())
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
