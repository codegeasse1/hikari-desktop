package desktop.img

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import desktop.fx.Fx
import javafx.scene.image.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Poster image loader for the desktop UI.
 *
 * Plain OkHttp first (browser UA + a Referer the image host expects — the
 * per-plugin headers CloudStream providers declare, a per-host fallback
 * recorded for hotlink-protected CDNs, else the image's own origin). When that
 * request fails, returns a non-image (a WAF challenge page is served with 200
 * to bots) or the whole host is refused, the fetch is retried through the
 * embedded WebEngine — the real-browser TLS stack several poster CDNs (fkbae,
 * porn4fans, …) insist on. Results are memory- + disk-cached like before.
 */
object ImageLoader {

    private val mem = object : LinkedHashMap<String, Image>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?): Boolean = size > 160
    }

    private val diskDir: File by lazy {
        File(HikariApp.instance.cacheDir, "hikari_poster_cache").apply { mkdirs() }
    }

    private val inFlight = mutableMapOf<String, MutableList<(Image?) -> Unit>>()

    /** Cap on simultaneous network image fetches — Home renders hundreds of
     *  posters at once and firing them all concurrently starves the IO pool
     *  and makes the UI lag on weak networks. */
    private val gate = java.util.concurrent.Semaphore(6)

    /** Hosts that 403 any image request carrying a Referer (verified in the
     *  Android app: same-origin referer => 403, bare request => 200). */
    private val NO_REFERER_HOSTS = setOf("fourhoi.com", "surrit.com")

    /** Hosts whose plain-HTTP image fetch recently failed even via the WebView
     *  fallback — retry the WebView only after a cooldown instead of burning
     *  the shared engine on every re-render of a doomed poster. */
    private val webViewCooldown = ConcurrentHashMap<String, Long>()

    fun loadAsync(url: String?, onReady: (Image?) -> Unit, w: Int = 0, h: Int = 0) {
        Fx.requireFx()
        // Repaired here rather than at every call site: a scheme-less or
        // protocol-relative poster URL can't be fetched at all, and that is the
        // difference between an extension's grid rendering and a wall of blanks.
        val fixed = normalize(url)
        if (fixed.isNullOrBlank()) {
            onReady(null)
            return
        }
        // Requested-size-aware cache key: the same URL may be shown as a small
        // poster and a wide banner.
        val key = if (w > 0 && h > 0) "$fixed|${w}x$h" else fixed
        mem[key]?.let {
            onReady(it)
            return
        }
        val pending = inFlight.getOrPut(key) { mutableListOf() }
        pending.add(onReady)
        if (pending.size > 1) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val img = runCatching {
                gate.acquire()
                try {
                    load(fixed, key, w, h)
                } finally {
                    gate.release()
                }
            }.getOrNull()
            Fx.run {
                if (img != null) mem[key] = img
                val list = inFlight.remove(key) ?: emptyList()
                list.forEach { it(img) }
            }
        }
    }

    private suspend fun load(url: String, key: String, w: Int, h: Int): Image? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = fetchBytes(url) ?: return@withContext null
                // Decode DOWNSCALED: a full-res CDN poster can be 1.5-6MB of
                // pixels; with hundreds of cards that OOMs the app (the crash
                // on Home). Requesting a small decode bounds total memory.
                if (w > 0 && h > 0) {
                    Image(ByteArrayInputStream(bytes), w.toDouble(), h.toDouble(), true, true)
                } else {
                    Image(ByteArrayInputStream(bytes))
                }
            }.getOrNull()
        }

    /**
     * The artwork a card should show: the poster, or the landscape backdrop when
     * the provider left the poster empty. Several catalogs only fill the
     * landscape image, and an empty model is a blank cell (the Android app has
     * the same fallback).
     */
    fun artFor(poster: String?, backdrop: String?): String? =
        normalize(poster)?.takeIf { it.isNotBlank() } ?: normalize(backdrop)

    /** Host-looking path with no scheme — extensions emit these now and then
     *  ('pic.example.com/x.jpg'), and OkHttp has no URL for a scheme-less
     *  string, so the poster came out blank. */
    private val HOST_LIKE = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(/.*)?$")

    /**
     * Repairs a poster URL the way the Android app does: trims it and gives a
     * missing scheme an https one. Protocol-relative ('//host/x.jpg') and bare
     * host paths are the two shapes that made a whole extension's posters blank
     * on desktop while the same data rendered fine on Android.
     */
    fun normalize(url: String?): String? {
        val u = url?.trim() ?: return null
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("file:")) return u
        if (u.startsWith("//")) return "https:$u"
        if (HOST_LIKE.matches(u)) return "https://$u"
        return u
    }

    /**
     * Decodes the base64 payload of a `data:` URI, stubbornly.
     *
     * A silently-wrong decode is worse than a failure: the standard decoder
     * stops at the first character outside its alphabet, so a URL-safe payload
     * (`-`/`_`) decodes to truncated garbage that no image decoder accepts — a
     * whole provider's rows of blank posters, with no error anywhere. So each
     * alphabet is tried and only a result whose magic bytes really are an image
     * is accepted. (This is exactly what the Android app's PosterLoader does,
     * and why the same extension's encrypted posters work there.)
     */
    private fun decodeImageDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        val payload = url.substring(comma + 1)
            .filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (payload.isEmpty()) return null
        val decoders = listOf(
            java.util.Base64.getDecoder(),
            java.util.Base64.getUrlDecoder(),
            java.util.Base64.getMimeDecoder(),
        )
        for (d in decoders) {
            val bytes = runCatching { d.decode(payload) }.getOrNull() ?: continue
            if (isImageBytes(bytes)) return bytes
        }
        return null
    }

    /** Poster bytes for a `data:` URL — base64 first, then the one case the
     *  embedded browser handles and JavaFX cannot: an inline SVG. */
    private fun loadDataUri(url: String): ByteArray? {
        val bytes = decodeImageDataUri(url)
        if (bytes != null) return makeDecodable(bytes, url)
        val mime = url.substringAfter("data:", "").substringBefore(';').lowercase()
        if (mime.contains("svg")) return desktop.web.FxWebView.imageBytes(url)
        return null
    }

    private suspend fun fetchBytes(url: String): ByteArray? {
        if (url.startsWith("data:")) return loadDataUri(url)
        val disk = diskFile(url)
        disk.takeIf { it.exists() && it.length() > 0 }?.let {
            val cached = runCatching { it.readBytes() }.getOrNull()
            if (cached != null && isImageBytes(cached)) {
                val decodable = makeDecodable(cached, url)
                if (decodable != null) return decodable
            }
            // A stale corrupt cache entry (a WAF challenge page cached by an
            // older build, or a format even the converters can't open) must
            // never be served — drop it and re-fetch.
            runCatching { disk.delete() }
        }
        val plain = plainFetch(url)
        if (plain != null && isImageBytes(plain)) {
            val decodable = makeDecodable(plain, url)
            if (decodable != null) {
                writeDisk(disk, decodable)
                return decodable
            }
        }
        if (shouldTryWebView(url)) {
            val wv = desktop.web.FxWebView.imageBytes(url)
            if (wv != null && isImageBytes(wv)) {
                writeDisk(disk, wv)
                return wv
            }
            markWebViewFailed(url)
        }
        return null
    }

    /** Ensures bytes can be decoded by JavaFX Image (png/jpeg/gif/bmp only).
     *  Everything else (webp, avif, …) is converted in-process to a PNG — the
     *  platform poster CDNs serve webp heavily and JavaFX silently blanks on
     *  it — with the embedded WebEngine canvas re-encode as the fallback. */
    private fun makeDecodable(bytes: ByteArray, url: String): ByteArray? {
        if (isJavaFxDecodable(bytes)) return bytes
        return convertImageIO(bytes) ?: desktop.web.FxWebView.imageBytes(url)
    }

    /** In-process decode + downscale via ImageIO (TwelveMonkeys registers a
     *  WebP reader). Bounds the decode like the WebView path so a full-res
     *  poster can't spike memory. */
    private fun convertImageIO(bytes: ByteArray): ByteArray? = runCatching {
        val src = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
        val maxDim = 640
        val w = src.width
        val h = src.height
        val scale = minOf(1.0, maxDim.toDouble() / maxOf(1, maxOf(w, h)))
        val tw = maxOf(1, (w * scale).toInt())
        val th = maxOf(1, (h * scale).toInt())
        val out = if (scale < 1.0) {
            val bmp = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = bmp.createGraphics()
            try {
                g.drawImage(src, 0, 0, tw, th, null)
            } finally {
                g.dispose()
            }
            bmp
        } else {
            src
        }
        val bos = java.io.ByteArrayOutputStream()
        if (!javax.imageio.ImageIO.write(out, "png", bos)) return null
        bos.toByteArray()
    }.getOrNull()

    /** OkHttp fetch with the headers the image host actually expects: the
     *  exact per-poster headers CloudStream plugins declared, else the per-host
     *  Referer recorded for that CDN, else the image's own origin (hotlink
     *  protection), except NO_REFERER_HOSTS which reject any Referer. */
    private fun plainFetch(url: String): ByteArray? {
        val cs3 = com.hikari.app.cs3.Cs3MainApiProvider
        val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
        val headers = buildMap {
            if (host in NO_REFERER_HOSTS) {
                // no Referer — these hosts 403 the image when one is present
            } else {
                val exact = cs3.imageHeaders[url]
                if (exact != null) {
                    exact.forEach { (k, v) -> put(k, v) }
                } else {
                    val hostRef = cs3.imageHostReferers[host]
                    if (hostRef != null) {
                        put("Referer", hostRef)
                    } else if (host.isNotBlank()) {
                        val scheme = runCatching { java.net.URI(url).scheme }.getOrDefault("https")
                        put("Referer", "$scheme://$host/")
                    }
                }
            }
        }
        return Http.getBytes(url, headers)
    }

    private fun shouldTryWebView(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
        val retryAt = webViewCooldown[host] ?: return true
        return System.currentTimeMillis() > retryAt
    }

    private fun markWebViewFailed(url: String) {
        val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
        if (host.isNotBlank()) webViewCooldown[host] = System.currentTimeMillis() + 5 * 60_000L
    }

    private fun writeDisk(disk: File, bytes: ByteArray) {
        runCatching { disk.parentFile?.mkdirs(); disk.writeBytes(bytes) }
    }

    /** Real image magic bytes — a WAF challenge page served with 200 to a bot
     *  must never be mistaken for a poster (it fails the JavaFX decode and
     *  would poison the disk cache). */
    private fun isImageBytes(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val u = { i: Int -> b[i].toInt() and 0xFF }
        return when {
            u(0) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> true
            u(0) == 0xFF && u(1) == 0xD8 -> true
            u(0) == 'G'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == '8'.code -> true
            u(0) == 'R'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == 'F'.code &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> true
            u(0) == 'B'.code && u(1) == 'M'.code -> true
            u(4) == 'f'.code && u(5) == 't'.code && u(6) == 'y'.code && u(7) == 'p'.code -> true
            else -> false
        }
    }

    /** Formats JavaFX's Image can decode natively — everything else must be
     *  converted first (see [makeDecodable]). */
    private fun isJavaFxDecodable(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val u = { i: Int -> b[i].toInt() and 0xFF }
        return when {
            u(0) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> true
            u(0) == 0xFF && u(1) == 0xD8 -> true
            u(0) == 'G'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == '8'.code -> true
            u(0) == 'B'.code && u(1) == 'M'.code -> true
            else -> false
        }
    }

    private fun diskFile(url: String): File {
        val h = fnv1a(url)
        return File(diskDir, h + "_" + url.length)
    }

    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = (h xor (b.toInt() and 0xFF))
            h *= 0x01000193
        }
        return (h.toUInt()).toString(16)
    }
}
