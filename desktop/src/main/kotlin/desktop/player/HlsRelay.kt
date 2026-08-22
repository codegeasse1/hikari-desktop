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
            // Single-use master playlists are cached: the player re-opens the
            // master after the first read, and the token URL 403s on a second
            // fetch — serve the cached (rewritten) copy instead.
            if (key.startsWith("k")) {
                masterCache[key]?.let { cached ->
                    ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                    ex.sendResponseHeaders(200, cached.size.toLong())
                    ex.responseBody.use { it.write(cached) }
                    return
                }
            }
            // Segment-level keys look like <base64>, resolved against the root URL.
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
            val resp = Http.get(targetUrl, route.headers)
            if (!resp.isSuccessful) {
                ex.sendResponseHeaders(resp.code, -1)
                resp.close()
                ex.close()
                return
            }
            val body = resp.body
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            if (ct.contains("mpegurl") || ct.contains("application/vnd.apple.mpegurl") ||
                ct.contains("text/plain") || ct.contains("application/x-mpegurl") ||
                path.endsWith(".m3u8") || targetUrl.contains(".m3u8")
            ) {
                val text = body?.string() ?: ""
                val rewritten = rewritePlaylist(text, targetUrl, route.headers)
                val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                if (key.startsWith("k")) masterCache[key] = bytes
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.responseHeaders.set("Content-Type", ct)
                val len = body?.contentLength() ?: -1L
                if (len >= 0) ex.sendResponseHeaders(200, len) else ex.sendResponseHeaders(200, 0)
                body?.byteStream()?.use { input -> input.copyTo(ex.responseBody) }
                runCatching { ex.responseBody.close() }
            }
            resp.close()
        } catch (t: Throwable) {
            runCatching { ex.sendResponseHeaders(502, -1) }
            runCatching { ex.close() }
        }
    }

    private fun rewritePlaylist(text: String, base: String, headers: Map<String, String>): String {
        val root = runCatching { URI(base) }.getOrNull() ?: return text
        val out = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // rewrite key URIs inside #EXT-X-KEY
                val keyUri = Regex("""URI="([^"]+)"""").find(line)
                if (keyUri != null && !keyUri.groupValues[1].startsWith("http")) {
                    val u = root.resolve(keyUri.groupValues[1].replace('\\', '/')).toString()
                    out.append(line.replaceRange(keyUri.range, "URI=\"$u\""))
                } else {
                    out.append(line)
                }
                out.append('\n')
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
            val segKey = "s${counter.incrementAndGet()}"
            keys[segKey] = resolved
            routes["$segKey"] = Route(resolved, headers)
            out.append("http://127.0.0.1:$port/x/$segKey").append('\n')
        }
        return out.toString()
    }
}
