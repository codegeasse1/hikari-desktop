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

/**
 * Poster image loader for the desktop UI. Browser UA + same-origin Referer
 * (hotlink protection), memory + disk cache, data: URIs decoded via the
 * android.util.Base64 shim.
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

    fun loadAsync(url: String?, onReady: (Image?) -> Unit, w: Int = 0, h: Int = 0) {
        Fx.requireFx()
        if (url.isNullOrBlank()) {
            onReady(null)
            return
        }
        // Requested-size-aware cache key: the same URL may be shown as a small
        // poster and a wide banner.
        val key = if (w > 0 && h > 0) "$url|${w}x$h" else url
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
                    load(url, key, w, h)
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

    private suspend fun fetchBytes(url: String): ByteArray? {
        if (url.startsWith("data:image/")) {
            val comma = url.indexOf(',')
            if (comma <= 0) return null
            val raw = url.substring(comma + 1)
            return runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }.getOrNull()
        }
        val disk = diskFile(url)
        disk.takeIf { it.exists() && it.length() > 0 }?.let {
            return runCatching { it.readBytes() }.getOrNull()
        }
        val referer = runCatching { java.net.URI(url).let { u -> "${u.scheme}://${u.host}/" } }.getOrNull()
        val headers = buildMap {
            if (referer != null) put("Referer", referer)
        }
        val bytes = Http.getBytes(url, headers) ?: return null
        runCatching { disk.parentFile?.mkdirs(); disk.writeBytes(bytes) }
        return bytes
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
