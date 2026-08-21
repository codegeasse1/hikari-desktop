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

    fun loadAsync(url: String?, onReady: (Image?) -> Unit) {
        Fx.requireFx()
        if (url.isNullOrBlank()) {
            onReady(null)
            return
        }
        mem[url]?.let {
            onReady(it)
            return
        }
        val pending = inFlight.getOrPut(url) { mutableListOf() }
        pending.add(onReady)
        if (pending.size > 1) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val img = runCatching {
                gate.acquire()
                try {
                    load(url)
                } finally {
                    gate.release()
                }
            }.getOrNull()
            Fx.run {
                if (img != null) mem[url] = img
                val list = inFlight.remove(url) ?: emptyList()
                list.forEach { it(img) }
            }
        }
    }

    private suspend fun load(url: String): Image? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = fetchBytes(url) ?: return@withContext null
            Image(ByteArrayInputStream(bytes))
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
