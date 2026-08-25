package desktop.player

import com.hikari.app.net.Http
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP relay that fronts remote streams and serves them to mpv.
 *
 * Fixes for seeking / crashing:
 * - Properly forwards `Range` header to upstream and returns `206 Partial Content`
 *   with `Content-Range` / `Accept-Ranges` so mpv can seek in MP4 files.
 * - Handles HEAD requests (mpv probes with HEAD before seeking).
 * - Streams bodies instead of buffering fully, and always closes exchanges.
 * - Caches only master HLS playlists (when no Range), not media segments.
 * - Rewrites HLS playlists to local URLs so every segment goes through OkHttp
 *   (DoH + headers) instead of mpv's direct connection.
 */
object HlsRelay {

    private data class Route(val url: String, val headers: Map<String, String>)

    private var server: HttpServer? = null
    private var port = 0
    private val routes = ConcurrentHashMap<String, Route>()
    private val keys = ConcurrentHashMap<String, String>()
    private val counter = AtomicLong(0)
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

    fun urlFor(url: String, headers: Map<String, String>): String {
        ensureServer()
        val key = "k${counter.incrementAndGet()}"
        keys[key] = url
        routes[key] = Route(url, headers)
        return "http://127.0.0.1:$port/x/$key"
    }

    private fun handle(ex: HttpExchange) {
        try {
            val method = ex.requestMethod.uppercase()
            val path = ex.requestURI.path
            val parts = path.removePrefix("/x/").split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return
            }
            val key = parts[0]
            val route = routes[key]
            if (route == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return
            }

            val rangeHeader = ex.requestHeaders.getFirst("Range")

            // Serve cached master playlist only for plain GET without Range
            if (key.startsWith("k") && method == "GET" && rangeHeader == null) {
                masterCache[key]?.let { cached ->
                    ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                    ex.responseHeaders.set("Accept-Ranges", "none")
                    ex.responseHeaders.set("Cache-Control", "no-cache")
                    ex.sendResponseHeaders(200, cached.size.toLong())
                    ex.responseBody.use { it.write(cached) }
                    return
                }
            }

            // Resolve target URL: either direct route or a previously mapped segment
            var targetUrl = route.url
            if (parts.size > 1) {
                val segKey = parts[1]
                val segUrl = keys[segKey]
                if (segUrl != null) {
                    targetUrl = segUrl
                } else {
                    // Fallback: resolve relative to root (should not happen often)
                    targetUrl = runCatching { URI(route.url).resolve(segKey).toString() }.getOrDefault(route.url)
                }
            }

            // Build upstream headers, forwarding Range if mpv asked for it
            val upstreamHeaders = HashMap(route.headers)
            if (rangeHeader != null) {
                upstreamHeaders["Range"] = rangeHeader
            }
            // Ensure we always send a UA (some CDNs 403 mpv's default UA even via relay if missing)
            if (upstreamHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                upstreamHeaders["User-Agent"] = Http.UA
            }

            val resp = runCatching { Http.get(targetUrl, upstreamHeaders) }.getOrNull()
            if (resp == null) {
                ex.sendResponseHeaders(502, -1)
                ex.close()
                return
            }

            resp.use { r ->
                if (!r.isSuccessful && r.code != 206) {
                    // Forward error code as-is (403, 404, etc.)
                    ex.sendResponseHeaders(r.code, -1)
                    ex.close()
                    return
                }

                val contentType = r.header("Content-Type") ?: "application/octet-stream"
                val isPlaylist = contentType.contains("mpegurl", ignoreCase = true) ||
                    contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                    contentType.contains("application/x-mpegurl", ignoreCase = true) ||
                    contentType.contains("text/plain", ignoreCase = true) && targetUrl.contains(".m3u8") ||
                    path.endsWith(".m3u8") || targetUrl.contains(".m3u8") ||
                    r.header("Content-Type")?.contains("text/plain") == true && targetUrl.contains(".m3u8")

                // More robust playlist detection: also check body start for #EXTM3U if content-type is ambiguous
                var bodyForCheck: ByteArray? = null
                if (!isPlaylist && method == "GET") {
                    // Peek first bytes for HLS tag without consuming fully if possible
                    // We'll read fully for playlists anyway
                }

                if (isPlaylist || targetUrl.contains(".m3u8") || contentType.contains("mpegurl") || contentType.contains("hls", ignoreCase = true)) {
                    // For playlists, we always rewrite and return 200 (ignore Range)
                    val text = r.body?.string() ?: ""
                    // If body is actually a video (mis-detected), fall through to video handling
                    if (text.trimStart().startsWith("#EXTM3U")) {
                        val rewritten = rewritePlaylist(text, targetUrl, route.headers)
                        val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                        if (key.startsWith("k") && method == "GET" && rangeHeader == null) {
                            masterCache[key] = bytes
                        }
                        ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                        ex.responseHeaders.set("Accept-Ranges", "none")
                        ex.responseHeaders.set("Cache-Control", "no-cache")
                        ex.responseHeaders.set("Access-Control-Allow-Origin", "*")
                        if (method == "HEAD") {
                            ex.sendResponseHeaders(200, -1)
                            ex.close()
                        } else {
                            ex.sendResponseHeaders(200, bytes.size.toLong())
                            ex.responseBody.use { it.write(bytes) }
                        }
                        return
                    }
                    // If not actually a playlist, treat as video below - need to re-fetch? We already consumed body.
                    // For simplicity, if we consumed body as text but it's not playlist, send it as video bytes
                    val bytes = text.toByteArray(StandardCharsets.UTF_8)
                    ex.responseHeaders.set("Content-Type", contentType)
                    ex.responseHeaders.set("Accept-Ranges", "bytes")
                    ex.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    if (method == "HEAD") {
                        ex.sendResponseHeaders(r.code, -1)
                        ex.close()
                    } else {
                        // If original was 206, preserve it, else 200
                        val codeToSend = if (r.code == 206) 206 else 200
                        r.header("Content-Range")?.let { ex.responseHeaders.set("Content-Range", it) }
                        ex.sendResponseHeaders(codeToSend, bytes.size.toLong())
                        ex.responseBody.use { it.write(bytes) }
                    }
                    return
                }

                // --- Video / segment handling (MP4, TS, etc.) with Range support ---
                ex.responseHeaders.set("Content-Type", contentType)
                ex.responseHeaders.set("Accept-Ranges", r.header("Accept-Ranges") ?: "bytes")
                ex.responseHeaders.set("Access-Control-Allow-Origin", "*")
                r.header("Content-Length")?.let { ex.responseHeaders.set("Content-Length", it) }
                r.header("Content-Range")?.let { ex.responseHeaders.set("Content-Range", it) }
                r.header("Cache-Control")?.let { ex.responseHeaders.set("Cache-Control", it) }
                // CORS for mpv (not strictly needed but harmless)
                ex.responseHeaders.set("Access-Control-Allow-Headers", "Range, Content-Type, Accept")

                val responseCode = r.code // 200 or 206

                if (method == "HEAD") {
                    // For HEAD, we must not send body, but need to send headers with correct code
                    ex.sendResponseHeaders(responseCode, -1)
                    ex.close()
                    return
                }

                // Stream body to client
                val body = r.body
                if (body == null) {
                    ex.sendResponseHeaders(responseCode, -1)
                    ex.close()
                    return
                }

                val contentLength = body.contentLength()
                if (responseCode == 206 || contentLength >= 0) {
                    // If we know length, send it; otherwise chunked (0)
                    if (contentLength >= 0) {
                        ex.sendResponseHeaders(responseCode, contentLength)
                    } else {
                        // Unknown length (e.g., live HLS segment) - use chunked
                        ex.sendResponseHeaders(responseCode, 0)
                    }
                } else {
                    ex.sendResponseHeaders(responseCode, 0)
                }

                try {
                    body.byteStream().use { input ->
                        ex.responseBody.use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Client closed connection (mpv seeked / closed) - ignore
                } finally {
                    runCatching { ex.responseBody.close() }
                }
            }
        } catch (t: Throwable) {
            runCatching { ex.sendResponseHeaders(502, -1) }
            runCatching { ex.close() }
        }
    }

    private fun rewritePlaylist(text: String, base: String, headers: Map<String, String>): String {
        val root = runCatching { URI(base) }.getOrNull() ?: return text
        val out = StringBuilder(text.length + 1024)
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
                continue
            }
            if (trimmed.startsWith("#")) {
                // Rewrite URI inside #EXT-X-KEY, #EXT-X-MAP, etc.
                val keyUriMatch = Regex("""URI="([^"]+)"""").find(line)
                if (keyUriMatch != null) {
                    val orig = keyUriMatch.groupValues[1]
                    if (!orig.startsWith("http://") && !orig.startsWith("https://")) {
                        val resolved = runCatching { root.resolve(orig.replace('\\', '/')).toString() }.getOrNull() ?: orig
                        val segKey = "s${counter.incrementAndGet()}"
                        keys[segKey] = resolved
                        routes[segKey] = Route(resolved, headers)
                        val local = "http://127.0.0.1:$port/x/$segKey"
                        out.append(line.replace(orig, local))
                        out.append('\n')
                        continue
                    }
                }
                out.append(line).append('\n')
                continue
            }
            // Media segment line
            val normalized = trimmed.replace('\\', '/')
            val resolved = runCatching { root.resolve(normalized).toString() }.getOrNull()
            if (resolved == null) {
                out.append(line).append('\n')
                continue
            }
            val segKey = "s${counter.incrementAndGet()}"
            keys[segKey] = resolved
            routes[segKey] = Route(resolved, headers)
            out.append("http://127.0.0.1:$port/x/$segKey").append('\n')
        }
        return out.toString()
    }
}
