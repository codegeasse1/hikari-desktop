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
 * file) and forwards it to the JavaFX media stack. JavaFX's Media can't send
 * custom headers (Referer/Cookie/UA), which many CDNs require, so the player
 * plays `http://127.0.0.1:PORT/x/<key>` instead and this relay fetches the
 * real URL with the required headers, rewriting HLS playlist lines to point
 * back at the relay. Also sidesteps mixed-content/CORS-type issues.
 */
object HlsRelay {

    private data class Route(val url: String, val headers: Map<String, String>)

    private var server: HttpServer? = null
    private var port = 0
    private val routes = ConcurrentHashMap<String, Route>()
    private val keys = ConcurrentHashMap<String, String>()
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

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
                path.endsWith(".m3u8")
            ) {
                val text = body?.string() ?: ""
                val rewritten = rewritePlaylist(text, route.url, route.headers)
                val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                val bytes = body?.bytes() ?: ByteArray(0)
                ex.responseHeaders.set("Content-Type", ct)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
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
        var segIdx = 0
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // rewrite key URIs inside #EXT-X-KEY
                val keyUri = Regex("""URI="([^"]+)"""").find(line)
                if (keyUri != null && !keyUri.groupValues[1].startsWith("http")) {
                    val u = root.resolve(keyUri.groupValues[1]).toString()
                    out.append(line.replaceRange(keyUri.range, "URI=\"$u\""))
                } else {
                    out.append(line)
                }
                out.append('\n')
                continue
            }
            if (trimmed.startsWith("http")) {
                out.append(trimmed).append('\n')
                continue
            }
            // relative segment/media URI → relay-local path
            val resolved = runCatching { root.resolve(trimmed).toString() }.getOrNull()
            if (resolved == null) {
                out.append(line).append('\n')
                continue
            }
            val segKey = "s${counter.incrementAndGet()}"
            keys[segKey] = resolved
            routes["$segKey"] = Route(resolved, headers)
            val baseRelay = "http://127.0.0.1:$port/x/$segKey"
            // keep media segments grouped under the root for M3U8 ordering
            out.append(line.replace(trimmed, baseRelay)).append('\n')
        }
        return out.toString()
    }
}
