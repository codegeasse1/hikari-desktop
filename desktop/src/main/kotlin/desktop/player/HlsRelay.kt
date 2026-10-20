package desktop.player

import com.hikari.app.net.Http
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny local HTTP relay that fronts a remote stream (HLS manifest or direct
 * file) and serves it to the player. The player plays
 * `http://127.0.0.1:PORT/x/<key>` and this relay fetches the real URL with the
 * required headers (Referer/Cookie/UA) through the app's own OkHttp stack,
 * rewriting HLS playlist lines to point back at the relay.
 *
 * Why this exists:
 *  - Many CDNs (chaturbate's mmcdn, myspacecat, ...) 403 the player's direct
 *    connections: signed tokens are single-use (the manifest can only be
 *    fetched once) and the player's TLS/HTTP fingerprint gets blocked. The
 *    app's own HTTP stack fetches the same URLs fine.
 *  - chaturbate's LL-HLS manifests reference their media playlists and
 *    segments as root-relative BACKSLASH paths (`\v1\edge\streams\…`), which
 *    the player misreads as a protocol ("No protocol handler"). The relay
 *    normalizes and resolves them.
 *  - single-use manifests are cached, so the player re-fetching the master
 *    playlist gets the cached copy instead of a 403.
 */
object HlsRelay {

    private data class Route(val url: String, val headers: Map<String, String>)

    private var server: HttpServer? = null
    private var port = 0
    private val routes = ConcurrentHashMap<String, Route>()
    private val keys = ConcurrentHashMap<String, String>()
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val masterCache = ConcurrentHashMap<String, ByteArray>()

    @Synchronized
    private fun ensureServer() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> handle(ex) }
        s.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "hikari-relay").apply { isDaemon = true }
        }
        s.start()
        server = s
        port = s.address.port
    }

    /** Returns the local relay URL to hand to the player. */
    fun urlFor(url: String, headers: Map<String, String>): String {
        ensureServer()
        val key = "k${counter.incrementAndGet()}"
        keys[key] = url
        routes[key] = Route(url, headers)
        return "http://127.0.0.1:$port/x/$key"
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val parts = path.removePrefix("/x/").split("/")
            val key = parts[0]
            val route = routes[key]
            if (route == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return
            }
            val method = ex.requestMethod ?: "GET"
            val isHead = method.equals("HEAD", ignoreCase = true)
            val incomingRange = ex.requestHeaders.getFirst("Range")
            // Single-use playlists are cached: the player re-opens the master
            // (and often the variant) after the first read, and the token URL
            // 403s on a second fetch — serve the cached (rewritten) copy.
            // A ranged re-request is a SEEK, never a playlist re-read, so the
            // cache only answers full GETs.
            if (!isHead && incomingRange.isNullOrBlank()) {
                masterCache[key]?.let { cached ->
                    ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                    ex.responseHeaders.set("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, cached.size.toLong())
                    ex.responseBody.use { it.write(cached) }
                    return
                }
            }
            // Segment-level keys are flat (/x/<segKey>); the legacy nested form
            // (/x/<key>/<seg>) is still accepted for old playlist copies.
            var targetUrl = route.url
            if (parts.size > 1) {
                val segKey = parts[1]
                val segUrl = keys[segKey]
                if (segUrl != null) {
                    targetUrl = segUrl
                } else {
                    val root = URI(route.url)
                    targetUrl = root.resolve(segKey).toString()
                }
            }
            val resp = try {
                // Edges hiccup: one failed segment kills the whole stream in
                // mpv, while a retry usually sails through (403/404 stay final
                // — a spent token or a dead link — see relayFetchRetrying).
                // Signed 51CG/MRDS-family URLs REQUIRE their Referer: when the
                // provider recorded no headers, fall back to the target's own
                // origin instead of fetching bare (a bare fetch 403s and the
                // title reads as "no playable source").
                Http.relayFetchRetrying(targetUrl, effectiveHeaders(route.headers, targetUrl), incomingRange, isHead)
            } catch (e: Exception) {
                ex.sendResponseHeaders(502, -1)
                ex.close()
                return
            }
            if (!resp.isSuccessful && resp.code != 206) {
                val code = resp.code
                resp.close()
                ex.sendResponseHeaders(code, -1)
                ex.close()
                return
            }
            if (isHead) {
                resp.header("Content-Type")?.let { ex.responseHeaders.set("Content-Type", it) }
                resp.header("Content-Length")?.let { ex.responseHeaders.set("Content-Length", it) }
                resp.header("Accept-Ranges")?.let { ex.responseHeaders.set("Accept-Ranges", it) }
                if (ex.responseHeaders.getFirst("Accept-Ranges") == null) {
                    ex.responseHeaders.set("Accept-Ranges", "bytes")
                }
                resp.close()
                ex.sendResponseHeaders(resp.code, -1)
                ex.close()
                return
            }
            val body = resp.body
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            // A ranged answer is ALWAYS bytes: playlists are never fetched with
            // Range, and rewriting a partial playlist as a full one corrupts it.
            // Otherwise only treat the answer as a playlist when it really is
            // one — the old `text/plain OR url contains .m3u8` rule rewrote raw
            // .ts bytes served as text/plain into garbage, which is a silent
            // stuck-loading with no error.
            val ranged = !incomingRange.isNullOrBlank() || resp.code == 206
            if (!ranged && isPlaylistAnswer(ct, targetUrl, resp.header("Content-Length"))) {
                val text = body?.string() ?: ""
                resp.close()
                if (!text.trimStart().startsWith("#EXTM3U")) {
                    // Looked like a playlist URL but isn't one (an extensionless
                    // MP4/CDN answer): serve the bytes we already read instead of
                    // handing mpv an empty playlist.
                    val bytes = text.toByteArray(StandardCharsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", ct)
                    ex.responseHeaders.set("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                    return
                }
                val rewritten = rewritePlaylist(text, targetUrl, route.headers)
                val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                // Cache VOD playlists only, not just the master: variant URLs
                // can be single-use signed too, and mpv re-opens them. A LIVE
                // playlist (no ENDLIST) keeps growing — caching it freezes the
                // stream at whatever segments existed on the first read, so it
                // is always re-fetched. The map is bounded: routes accumulate
                // over a session and must not grow without limit.
                if (text.contains("#EXT-X-ENDLIST")) {
                    if (masterCache.size > 150) masterCache.clear()
                    masterCache[key] = bytes
                }
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.responseHeaders.set("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                return
            }
            // Bytes (segments, MP4, keys): pass the upstream status and range
            // headers through so seeks work, then stream without buffering.
            val code = if (resp.code == 206) 206 else 200
            ex.responseHeaders.set("Content-Type", ct)
            resp.header("Content-Range")?.let { ex.responseHeaders.set("Content-Range", it) }
            resp.header("Accept-Ranges")?.let { ex.responseHeaders.set("Accept-Ranges", it) }
            if (ex.responseHeaders.getFirst("Accept-Ranges") == null) {
                ex.responseHeaders.set("Accept-Ranges", "bytes")
            }
            val len = body?.contentLength() ?: -1L
            // Fixed length only when we know it AND this is a full 200; a 206
            // without a trustworthy length, or an unknown length, streams
            // chunked (0) — the only form com.sun.net.httpserver streams.
            if (code == 200 && len >= 0) ex.sendResponseHeaders(200, len)
            else if (code == 206 && len >= 0) ex.sendResponseHeaders(206, len)
            else ex.sendResponseHeaders(code, 0)
            try {
                body?.byteStream()?.use { input -> input.copyTo(ex.responseBody) }
            } finally {
                runCatching { ex.responseBody.close() }
                resp.close()
            }
        } catch (t: Throwable) {
            runCatching { ex.sendResponseHeaders(502, -1) }
            runCatching { ex.close() }
        }
    }

    /** True when the upstream answer should be parsed as an HLS playlist. */

    /**
     * The headers a relay fetch goes out with: the provider's own headers,
     * plus a browser UA when it recorded none, plus a Referer derived from
     * the target's origin when it recorded none (hotlink-protected signed
     * CDNs 403 a bare fetch).
     */
    private fun effectiveHeaders(recorded: Map<String, String>, targetUrl: String): Map<String, String> {
        if (recorded.keys.any { it.equals("Referer", ignoreCase = true) }) return recorded
        val origin = runCatching {
            val u = URI(targetUrl)
            "${u.scheme}://${u.host}/"
        }.getOrNull() ?: return recorded
        return HashMap(recorded).apply { put("Referer", origin) }
    }

    private fun isPlaylistAnswer(ct: String, targetUrl: String, contentLength: String?): Boolean {
        val c = ct.lowercase()
        if (c.contains("mpegurl") || c.contains("x-mpegurl") || c.contains("hls")) return true
        if (c.startsWith("video/") || c.startsWith("audio/")) return false
        if (c.contains("mp2t") || c.contains("mp4") || c.contains("webm") || c.contains("matroska")) return false
        val path = targetUrl.substringBefore('?').substringBefore('#').lowercase()
        val looksPlaylist = path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".txt")
        if (looksPlaylist) {
            // A playlist URL with a huge body is a mislabelled segment — don't
            // buffer gigabytes as text. Playlists are kilobytes.
            val len = contentLength?.toLongOrNull() ?: -1L
            if (len > 2_000_000L) return false
            return true
        }
        return false
    }

    /** Routes one absolute media URL back through the relay. */
    private fun relayUrlFor(resolved: String, headers: Map<String, String>): String {
        val segKey = "s${counter.incrementAndGet()}"
        keys[segKey] = resolved
        routes[segKey] = Route(resolved, headers)
        return "http://127.0.0.1:$port/x/$segKey"
    }

    private fun rewritePlaylist(text: String, base: String, headers: Map<String, String>): String {
        val root = runCatching { URI(base) }.getOrNull() ?: return text
        val out = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append(line).append('\n')
                continue
            }
            if (trimmed.startsWith("#")) {
                // Every URI= attribute inside a tag (KEY, MAP, MEDIA,
                // I-FRAME-STREAM-INF, SESSION-DATA) names a file mpv must fetch
                // with the provider's headers. The old code only rewrote
                // relative KEY URIs to absolute CDN URLs — mpv then fetched the
                // key directly, without Referer/Cookie, got 403, and a working
                // stream sat on Loading forever. Route them all via the relay.
                var rewritten = line
                for (m in Regex("""URI="([^"]+)"""").findAll(line)) {
                    val raw = m.groupValues[1]
                    if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) continue
                    val normalized = raw.replace('\\', '/')
                    val resolved = runCatching { root.resolve(normalized).toString() }.getOrNull()
                        ?: continue
                    rewritten = rewritten.replaceRange(m.range, "URI=\"" + relayUrlFor(resolved, headers) + "\"")
                }
                out.append(rewritten).append('\n')
                continue
            }
            // chaturbate LL-HLS manifests reference their media playlists and
            // segments as root-relative BACKSLASH paths (e.g. \v1\edge\streams\…)
            // and occasionally as absolute https URLs. Both are served through
            // the relay so every request goes through the app's own HTTP stack
            // (which the CDNs accept) instead of the player's direct connection
            // (which they 403).
            val normalized = trimmed.replace('\\', '/')
            val resolved = runCatching { root.resolve(normalized).toString() }.getOrNull()
            if (resolved == null) {
                out.append(line).append('\n')
                continue
            }
            out.append(relayUrlFor(resolved, headers)).append('\n')
        }
        return out.toString()
    }
}
