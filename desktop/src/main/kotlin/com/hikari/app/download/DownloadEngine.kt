package com.hikari.app.download

import com.hikari.app.net.Http
import desktop.player.Mpv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when the user pauses/cancels — the partially downloaded work dir is
 *  left intact so a later resume can continue it. */
class DownloadCancelledException : IOException("Download cancelled")

data class DlProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val durationMs: Long,
    val doneDurationMs: Long,
)

data class DlResult(
    /** On-disk path used for in-app offline playback (the local playlist). */
    val localPath: String?,
    /** Where an exported copy landed (a path in the user's Downloads folder). */
    val savedUri: String?,
)

/**
 * Fetches a stream to local storage.
 *
 * HLS is rebuilt as a *local* bundle rather than fetched piecemeal at playback
 * time: every segment is downloaded (AES-128 segments decrypted with their own
 * key, `EXT-X-BYTERANGE` segments fetched with a Range header, fMP4 init
 * segments via `EXT-X-MAP`), then a VOD media playlist and a master playlist
 * referencing them by local file name are written next to the files. mpv then
 * plays `index.m3u8` off local storage — no network, no CDN, no expiry.
 *
 * Single-file sources are streamed straight to disk with a Range-resumed
 * append, so interrupting a big file and resuming never re-downloads the part
 * already on disk.
 *
 * EXPORT additionally produces ONE playable file in the user's
 * `Downloads/Hikari` folder: a single self-contained stream (TS, or fMP4 with
 * muxed audio) is concatenated as-is; a stream whose audio arrives as a
 * SEPARATE rendition is remuxed through mpv (see [Mpv.remux]). The local bundle
 * is kept either way, so an exported download is still instantly playable
 * inside the app.
 */
object DownloadEngine {

    private const val BUFFER = 1 shl 16
    private const val MAX_MEM_SEGMENT = 96L * 1024 * 1024

    /** How many HLS segments a single rendition fetches at once. One TCP
     *  connection to a streaming origin is usually capped at a few MB/s (CDNs
     *  throttle per connection), so this spreads a download over several
     *  connections. Kept modest so the video + audio renditions together stay
     *  within the per-host budget (16) in [DownloadHttp]. */
    private const val SEGMENT_CONCURRENCY = 6

    /** Segment fetches are retried this many times (short backoff) so a
     *  transient 5xx/429 on one of the parallel connections never kills the
     *  whole download. */
    private const val SEGMENT_RETRIES = 3

    suspend fun run(
        task: DownloadTask,
        workDir: File,
        exportDir: File,
        onProgress: suspend (DlProgress) -> Unit,
        onConverting: suspend () -> Unit = {},
        isCancelled: () -> Boolean,
    ): DlResult = withContext(Dispatchers.IO) {
        if (!task.resumePartial) runCatching { workDir.deleteRecursively() }
        workDir.mkdirs()
        val ua = task.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
        val headers = task.headers
        val isHls = task.isM3u8 || task.url.substringBefore('?').lowercase().contains(".m3u8")

        if (isHls) {
            val res = downloadHls(
                masterUrl = task.url,
                headers = headers,
                ua = ua,
                workDir = workDir,
                reporter = Reporter(onProgress),
                preferredHeight = task.preferredHeight,
                preferredBandwidth = task.preferredBandwidth,
                isCancelled = isCancelled,
            )
            if (task.kind == DownloadKind.EXPORT) {
                onConverting()
                val saved = runCatching { exportHls(task, workDir, exportDir, res) }
                    .onFailure { System.err.println("export failed: $it") }
                    .getOrNull()
                DlResult(res.entry.absolutePath, saved)
            } else {
                DlResult(res.entry.absolutePath, null)
            }
        } else {
            val file = downloadDirect(task, headers, ua, workDir, Reporter(onProgress), isCancelled)
            if (task.kind == DownloadKind.EXPORT) {
                onConverting()
                val name = task.fileBaseName() + "." + file.extension.ifBlank { "mp4" }
                val saved = runCatching { writeToExportDir(exportDir, file, name, "") }.getOrNull()
                DlResult(file.absolutePath, saved)
            } else {
                DlResult(file.absolutePath, null)
            }
        }
    }

    /**
     * The video variants an HLS master playlist offers, best first, as
     * (height, bandwidth) pairs. With height 0 the playlist only published
     * bandwidths.
     *
     * The download flow needs this when nothing has PLAYED yet: the quality
     * picker otherwise reads the player's parsed tracks. Any failure is an
     * empty list — the caller then downloads at the engine's own default
     * (highest) quality — so a probe like this can never be the reason a
     * download doesn't start.
     */
    suspend fun hlsQualities(url: String, headers: Map<String, String>): List<Pair<Int, Long>> {
        if (url.isBlank()) return emptyList()
        val ua = headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
        return runCatching {
            val (text, finalUrl) = fetchText(url, headers, ua)
            val top = parsePlaylist(text, finalUrl)
            top.variants
                .map { v -> (v.height.coerceAtLeast(0)) to v.bandwidth }
                .distinct()
                .sortedByDescending { (h, bw) -> if (h > 0) h.toLong() else bw }
        }.getOrDefault(emptyList())
    }

    /** Aggregates the video + (optional) audio rendition into one task-wide
     *  progress reading. Segments (and both renditions) are fetched in
     *  parallel, so every mutator is synchronized and the tallies only ever
     *  move forward — a late/smaller reading from one worker can't make the
     *  bar regress. */
    private class Reporter(private val onProgress: suspend (DlProgress) -> Unit) {
        private val lock = Any()
        private var vBytes = 0L
        private var vTotal = -1L
        private var vDurMs = 0L
        private var vTotalDurMs = 0L
        private var aBytes = 0L
        private var aTotal = -1L
        private var aDurMs = 0L
        private var aTotalDurMs = 0L
        private var hasAudio = false

        /** Byte callbacks fire every 64 KiB and from several workers at once;
         *  without this the UI (and the persisted task list) would be rewritten
         *  hundreds of times per segment. 4 updates/second is plenty for a
         *  progress bar. */
        private var lastEmit = 0L

        /** Pre-seeds the video/audio rendition's total duration so a task-wide
         *  progress reading is monotonic. Without it the video-only denominator
         *  fills to 100%, then the audio rendition (whose duration roughly
         *  equals the video's) doubles the denominator and progress appears to
         *  restart at 50% — even though nothing was lost. */
        fun expectVideoDuration(ms: Long) {
            if (ms <= 0L) return
            synchronized(lock) { if (ms > vTotalDurMs) vTotalDurMs = ms }
        }

        fun expectAudioDuration(ms: Long) {
            if (ms <= 0L) return
            synchronized(lock) {
                hasAudio = true
                aTotalDurMs = ms
            }
        }

        suspend fun video(bytes: Long, total: Long, durMs: Long, totalDurMs: Long) {
            val snap = synchronized(lock) {
                if (bytes > vBytes) vBytes = bytes
                if (total > 0L) vTotal = total
                if (durMs > vDurMs) vDurMs = durMs
                if (totalDurMs > 0L) vTotalDurMs = totalDurMs
                snapshot()
            }
            if (snap != null) onProgress(snap)
        }

        suspend fun audio(bytes: Long, total: Long, durMs: Long, totalDurMs: Long) {
            val snap = synchronized(lock) {
                hasAudio = true
                if (bytes > aBytes) aBytes = bytes
                if (total > 0L) aTotal = total
                if (durMs > aDurMs) aDurMs = durMs
                if (totalDurMs > 0L) aTotalDurMs = totalDurMs
                snapshot()
            }
            if (snap != null) onProgress(snap)
        }

        /** The progress reading, or null when this call falls inside the emit
         *  throttle window. Must be called while holding [lock]. */
        private fun snapshot(): DlProgress? {
            val now = System.currentTimeMillis()
            if (now - lastEmit < 250L) return null
            lastEmit = now
            val done = vBytes + aBytes
            val total = when {
                hasAudio && aTotal > 0 -> vTotal + aTotal
                hasAudio -> -1L
                else -> vTotal
            }
            return DlProgress(done, total, vTotalDurMs + aTotalDurMs, vDurMs + aDurMs)
        }
    }

    // ------------------------------------------------------------------ HLS --

    private class Rendition(
        val playlistName: String,
        val durationMs: Long,
        val fmp4: Boolean,
        val parts: List<File>,
    )

    private class HlsResult(
        val entry: File,
        val videoParts: List<File>,
        val audioParts: List<File>,
        val initPart: File?,
        val audioInitPart: File?,
        val fmp4: Boolean,
        val durationMs: Long,
    )

    private suspend fun downloadHls(
        masterUrl: String,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        preferredHeight: Int,
        preferredBandwidth: Long,
        isCancelled: () -> Boolean,
    ): HlsResult {
        val (text, finalUrl) = fetchText(masterUrl, headers, ua)
        val top = parsePlaylist(text, finalUrl)

        val videoUrl: String
        var audioUrl: String? = null
        if (top.isMaster) {
            val variants = top.variants.ifEmpty { throw IOException("HLS master has no variants") }
            val best = pickVariant(variants, preferredHeight, preferredBandwidth)
            videoUrl = best.url
            best.audioGroup
                ?.let { g -> top.audios.firstOrNull { it.group == g } }
                ?.let { audioUrl = it.url }
        } else {
            videoUrl = finalUrl
        }

        // Learn both renditions' lengths up front (two tiny playlist fetches)
        // so the task-wide progress reading is monotonic from the first byte.
        peekDurationMs(videoUrl, headers, ua)
            .takeIf { it > 0L }
            ?.let { reporter.expectVideoDuration(it) }
        val expectedAudioDurMs = if (audioUrl != null) peekDurationMs(audioUrl, headers, ua) else 0L
        if (expectedAudioDurMs > 0L) reporter.expectAudioDuration(expectedAudioDurMs)

        // Fetch the video and audio renditions CONCURRENTLY (each itself pulling
        // its own segments in parallel), so a stream whose audio arrives as a
        // separate rendition no longer pays video-time + audio-time in full.
        if (isCancelled()) throw DownloadCancelledException()
        val (video, audio) = coroutineScope {
            val vDeferred = async {
                downloadRendition("v", videoUrl, headers, ua, workDir, reporter, true, isCancelled)
            }
            val aDeferred = audioUrl?.let { url ->
                async {
                    downloadRendition("a", url, headers, ua, workDir, reporter, false, isCancelled)
                }
            }
            vDeferred.await() to aDeferred?.await()
        }

        // Master playlist referencing the local media playlists. Written even
        // for a single rendition: the local index.m3u8 is the stable entry point
        // that both the offline copy and any bundle export can point at.
        val bandwidth = top.variants.maxByOrNull { it.bandwidth }?.bandwidth ?: 0L
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        if (audio != null) {
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",")
                .append("DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(audio.playlistName).append("\"\n")
        }
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(if (bandwidth > 0) bandwidth else 2_000_000L)
        if (audio != null) sb.append(",AUDIO=\"aud\"")
        sb.append('\n').append(video.playlistName).append('\n')
        val entry = File(workDir, "index.m3u8")
        entry.writeText(sb.toString())

        val initPart = workDir.listFiles()?.firstOrNull { it.name == "init_v" + extFor(video.fmp4) }
        val audioInitPart = audio?.let {
            workDir.listFiles()?.firstOrNull { f -> f.name == "init_a" + extFor(it.fmp4) }
        }
        return HlsResult(
            entry = entry,
            videoParts = video.parts,
            audioParts = audio?.parts.orEmpty(),
            initPart = initPart,
            audioInitPart = audioInitPart,
            fmp4 = video.fmp4,
            durationMs = maxOf(video.durationMs, audio?.durationMs ?: 0L),
        )
    }

    /** Chooses the variant to download from a master playlist. No preference =
     *  the highest bandwidth (best quality). A preferred height narrows to that
     *  resolution; the preferred bandwidth breaks ties and covers playlists
     *  that omit RESOLUTION entirely. */
    private fun pickVariant(
        variants: List<Variant>,
        preferredHeight: Int,
        preferredBandwidth: Long,
    ): Variant {
        if (preferredHeight <= 0 && preferredBandwidth <= 0L) {
            return variants.maxByOrNull { it.bandwidth } ?: variants.first()
        }
        if (preferredHeight > 0) {
            val withHeight = variants.filter { it.height > 0 }
            withHeight.firstOrNull { it.height == preferredHeight }?.let { return it }
            if (withHeight.isNotEmpty()) {
                withHeight.minByOrNull { kotlin.math.abs(it.height - preferredHeight) }?.let { return it }
            }
        }
        if (preferredBandwidth > 0L) {
            variants.firstOrNull { it.bandwidth == preferredBandwidth }?.let { return it }
            variants.minByOrNull { kotlin.math.abs(it.bandwidth - preferredBandwidth) }?.let { return it }
        }
        return variants.maxByOrNull { it.bandwidth } ?: variants.first()
    }

    /** Fetches a (possibly nested) media playlist and returns its total duration
     *  in ms, or 0 if it can't be determined. Cheap: no segments are fetched. */
    private suspend fun peekDurationMs(
        playlistUrl: String,
        headers: Map<String, String>,
        ua: String,
    ): Long = try {
        val (text, finalUrl) = fetchText(playlistUrl, headers, ua)
        var pl = parsePlaylist(text, finalUrl)
        if (pl.isMaster) {
            val best = pl.variants.maxByOrNull { it.bandwidth }
            if (best != null) {
                val (t2, u2) = fetchText(best.url, headers, ua)
                pl = parsePlaylist(t2, u2)
            }
        }
        (pl.segments.sumOf { it.duration } * 1000.0).toLong()
    } catch (t: Throwable) {
        0L
    }

    private suspend fun downloadRendition(
        prefix: String,
        playlistUrl: String,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        isVideo: Boolean,
        isCancelled: () -> Boolean,
    ): Rendition {
        val (text, finalUrl) = fetchText(playlistUrl, headers, ua)
        var pl = parsePlaylist(text, finalUrl)
        if (pl.isMaster) {
            val best = pl.variants.maxByOrNull { it.bandwidth } ?: pl.variants.firstOrNull()
                ?: throw IOException("HLS master has no variants")
            val (t2, u2) = fetchText(best.url, headers, ua)
            pl = parsePlaylist(t2, u2)
        }
        val segs = pl.segments
        if (segs.isEmpty()) throw IOException("HLS playlist has no segments")

        val fmp4 = segs.any { it.mapUrl != null }
        val ext = extFor(fmp4)
        val totalDurMs = (segs.sumOf { it.duration } * 1000.0).toLong()

        // fMP4 init segment (#EXT-X-MAP) — required before any fragment.
        var initFile: File? = null
        segs.firstOrNull { it.mapUrl != null }?.mapUrl?.let { mapUrl ->
            val f = File(workDir, "init_$prefix$ext")
            if (!(f.exists() && f.length() > 0)) {
                downloadToFile(mapUrl, headers, ua, f, null, false, { _, _ -> }, isCancelled)
            }
            initFile = f
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val names = ArrayList<String>(segs.size)
        val parts = ArrayList<File>(segs.size)
        segs.forEachIndexed { i, _ ->
            names.add(prefix + "_" + i.toString().padStart(5, '0') + ext)
            parts.add(File(workDir, names[i]))
        }

        // Pull the segments in PARALLEL (see SEGMENT_CONCURRENCY).
        val bytesAcc = AtomicLong(0L)
        val doneDurMsAcc = AtomicLong(0L)
        val nextSeg = AtomicInteger(0)
        val workers = minOf(SEGMENT_CONCURRENCY, segs.size)
        coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val i = nextSeg.getAndIncrement()
                        if (i >= segs.size) break
                        val seg = segs[i]
                        val f = parts[i]
                        if (!(f.exists() && f.length() > 0)) {
                            // Bytes credited to this segment so far, so a retry
                            // can roll its partial credit back and never count a
                            // fragment twice.
                            val credited = longArrayOf(0L)
                            var attempt = 0
                            while (true) {
                                try {
                                    val key = seg.key
                                    val method = key?.method.orEmpty()
                                    if (method.isBlank() || method.equals("NONE", true)) {
                                        downloadToFile(
                                            seg.url, headers, ua, f, seg.byteRange, false,
                                            onBytes = { done, _ ->
                                                val delta = done - credited[0]
                                                if (delta != 0L) {
                                                    credited[0] = done
                                                    reportRendition(
                                                        reporter, isVideo, bytesAcc.addAndGet(delta),
                                                        doneDurMsAcc.get(), totalDurMs,
                                                    )
                                                }
                                            },
                                            isCancelled = isCancelled,
                                        )
                                    } else if (method.equals("AES-128", true)) {
                                        val k = key ?: throw IOException("Encrypted segment without a key")
                                        val keyBytes = keyCache[k.uri]
                                            ?: fetchBytes(k.uri, headers, ua)
                                                .also { keyCache[k.uri] = it }
                                        val enc = fetchBytes(seg.url, headers, ua, seg.byteRange)
                                        val iv = k.iv ?: ivFor(pl.mediaSequence + i)
                                        f.writeBytes(decryptAes128(enc, keyBytes, iv))
                                        val len = f.length()
                                        val delta = len - credited[0]
                                        credited[0] = len
                                        if (delta != 0L) {
                                            reportRendition(
                                                reporter, isVideo, bytesAcc.addAndGet(delta),
                                                doneDurMsAcc.get(), totalDurMs,
                                            )
                                        }
                                    } else {
                                        throw IOException("Unsupported HLS encryption: $method")
                                    }
                                    break
                                } catch (c: DownloadCancelledException) {
                                    throw c
                                } catch (t: Throwable) {
                                    if (isCancelled()) throw DownloadCancelledException()
                                    if (credited[0] != 0L) {
                                        bytesAcc.addAndGet(-credited[0])
                                        credited[0] = 0L
                                    }
                                    if (++attempt >= SEGMENT_RETRIES) throw t
                                    delay(350L * attempt)
                                }
                            }
                        }
                        // Progress is driven by the playlist's DURATIONS (byte
                        // totals are unknown for HLS) — see DownloadTask.progress.
                        val acc = doneDurMsAcc.addAndGet((seg.duration * 1000.0).toLong())
                        reportRendition(reporter, isVideo, bytesAcc.get(), acc, totalDurMs)
                    }
                }
            }
        }

        val playlistName = prefix + "_0.m3u8"
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(segs, pl.targetDuration)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        initFile?.let { sb.append("#EXT-X-MAP:URI=\"").append(it.name).append("\"\n") }
        segs.forEachIndexed { i, seg ->
            sb.append("#EXTINF:").append(fmtDuration(seg.duration)).append(",\n")
            sb.append(names[i]).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        File(workDir, playlistName).writeText(sb.toString())

        return Rendition(playlistName, totalDurMs, fmp4, parts)
    }

    private suspend fun reportRendition(
        reporter: Reporter,
        isVideo: Boolean,
        bytes: Long,
        doneDurMs: Long,
        totalDurMs: Long,
    ) {
        if (isVideo) reporter.video(bytes, -1L, doneDurMs, totalDurMs)
        else reporter.audio(bytes, -1L, doneDurMs, totalDurMs)
    }

    // --------------------------------------------------------------- Direct --

    private suspend fun downloadDirect(
        task: DownloadTask,
        headers: Map<String, String>,
        ua: String,
        workDir: File,
        reporter: Reporter,
        isCancelled: () -> Boolean,
    ): File {
        val rawPath = task.url.substringBefore('?').substringBefore('#')
        val ext = rawPath.substringAfterLast('.', "")
            .takeIf { it.length in 1..4 && it.all { c -> c.isLetterOrDigit() } }
            ?: "mp4"
        val file = File(workDir, "video.$ext")
        downloadToFile(
            task.url, headers, ua, file, null, resume = true,
            onBytes = { done, total -> reporter.video(done, total, 0L, 0L) },
            isCancelled = isCancelled,
        )
        return file
    }

    private suspend fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        ua: String,
        file: File,
        byteRange: Pair<Long, Long>?,
        resume: Boolean,
        onBytes: suspend (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val startAt = if (resume && byteRange == null && file.exists()) file.length() else 0L
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        if (byteRange != null) {
            req.header("Range", "bytes=${byteRange.first}-${byteRange.second}")
        } else if (startAt > 0L) {
            req.header("Range", "bytes=$startAt-")
        }
        DownloadHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            val append = byteRange == null && startAt > 0L && resp.code == 206
            if (!append && byteRange == null && file.exists()) file.delete()
            val avail = body.contentLength()
            val total = when {
                byteRange != null -> byteRange.second - byteRange.first + 1
                avail > 0 && append -> startAt + avail
                avail > 0 -> avail
                else -> -1L
            }
            val cap = if (byteRange != null) byteRange.second - byteRange.first + 1 else Long.MAX_VALUE
            var written = if (append) startAt else 0L
            FileOutputStream(file, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val room = (cap - written).coerceAtMost(buf.size.toLong()).toInt()
                        if (room <= 0) break
                        val n = input.read(buf, 0, room)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBytes(written, total)
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- Export --

    /**
     * Produces ONE playable file in [exportDir] from the downloaded bundle.
     *
     * A single self-contained stream is already complete once its parts are
     * concatenated (TS into one `.ts`, fMP4 — init + fragments — into one
     * `.mp4`). When the audio arrived as a separate rendition, the video-only
     * and audio-only files are assembled first and then remuxed into one `.mp4`
     * by mpv's stream-copy mode.
     *
     * Any failure falls back to copying the whole local bundle into a named
     * folder inside [exportDir], so nothing the user waited for is ever thrown
     * away.
     */
    private fun exportHls(task: DownloadTask, workDir: File, exportDir: File, res: HlsResult): String {
        val base = task.fileBaseName()
        val hasSeparateAudio = res.audioParts.isNotEmpty()
        val temps = ArrayList<File>()

        try {
            if (!hasSeparateAudio) {
                // Single self-contained stream: it is already a complete
                // playable file once its parts are concatenated — TS into one
                // .ts, fMP4 (init + fragments) into one .mp4.
                return if (!res.fmp4) {
                    val out = File(workDir, "export.ts").also { temps.add(it) }
                    concat(res.videoParts, out)
                    writeToExportDir(exportDir, out, "$base.ts", "")
                } else {
                    val out = File(workDir, "export.mp4").also { temps.add(it) }
                    concat(listOfNotNull(res.initPart) + res.videoParts, out)
                    writeToExportDir(exportDir, out, "$base.mp4", "")
                }
            }

            // Audio arrives as a SEPARATE rendition. Gluing video-then-audio
            // would produce a file that plays one track only, so assemble a
            // video-only file and an audio-only file, then remux both tracks.
            val vFile = File(workDir, "v_mux" + if (res.fmp4) ".mp4" else ".ts").also { temps.add(it) }
            concat(listOfNotNull(res.initPart) + res.videoParts, vFile)
            val aFile = File(workDir, "a_mux" + if (res.audioInitPart != null) ".mp4" else ".ts")
                .also { temps.add(it) }
            concat(listOfNotNull(res.audioInitPart) + res.audioParts, aFile)
            val out = File(workDir, "export.mp4").also { temps.add(it) }
            val err = Mpv.remux(vFile, aFile, out)
            if (err != null) throw IOException(err)
            return writeToExportDir(exportDir, out, "$base.mp4", "")
        } catch (t: Throwable) {
            // Last resort: copy every byte of the bundle into a named folder in
            // the export directory. Not one tidy file, but nothing the user
            // waited for is lost.
            System.err.println("exportHls failed, falling back to a bundle copy: $t")
            return exportBundle(exportDir, base, workDir)
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /** Fallback export: copy the whole local bundle (playlists + parts) into a
     *  named folder inside the export directory. */
    private fun exportBundle(exportDir: File, base: String, workDir: File): String {
        val folder = sanitizeFile(base).ifBlank { "hikari-video" }
        var entry = ""
        workDir.listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
            if (f.isFile) {
                val path = writeToExportDir(exportDir, f, f.name, folder)
                if (f.name == "index.m3u8") entry = path
            }
        }
        return entry.ifBlank { File(File(exportDir, folder), "index.m3u8").absolutePath }
    }

    private fun concat(parts: List<File>, out: File) {
        FileOutputStream(out).use { os ->
            parts.forEach { p -> p.inputStream().use { it.copyTo(os, BUFFER) } }
        }
    }

    // -------------------------------------------------------------- Filesystem --

    /** Copies [src] into `Downloads/Hikari[\/subPath]`, never overwriting an
     *  existing file (a second download of the same title gets "(1)"). Returns
     *  the absolute path of the copy. */
    private fun writeToExportDir(exportDir: File, src: File, displayName: String, subPath: String): String {
        val dir = if (subPath.isBlank()) exportDir else File(exportDir, subPath)
        dir.mkdirs()
        val dest = uniqueFile(dir, displayName)
        src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, BUFFER) } }
        return dest.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 500) {
            f = File(dir, "$stem ($i)$ext")
            i++
        }
        return f
    }

    // -------------------------------------------------------------- Playlist --

    private class Variant(val url: String, val bandwidth: Long, val audioGroup: String?, val height: Int)
    private class AudioRendition(val group: String, val name: String, val url: String)
    private class KeyInfo(val method: String, val uri: String, val iv: ByteArray?)
    private class Segment(
        val url: String,
        val duration: Double,
        val byteRange: Pair<Long, Long>?,
        val key: KeyInfo?,
        val mapUrl: String?,
    )
    private class Playlist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        val audios: List<AudioRendition>,
        val segments: List<Segment>,
        val targetDuration: Int,
        val mediaSequence: Int,
    )

    /**
     * Minimal HLS parser — deliberately our own rather than a library's, so the
     * download path never depends on internal parser APIs. Handles the tags
     * that matter for delivering a byte-exact offline copy: variants, audio
     * renditions, byte ranges, AES-128 keys and fMP4 init maps.
     */
    private fun parsePlaylist(text: String, baseUrl: String): Playlist {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val variants = ArrayList<Variant>()
        val audios = ArrayList<AudioRendition>()
        val segments = ArrayList<Segment>()
        var sawMasterTag = false
        var targetDur = 0
        var mediaSeq = 0
        var pendingStreamInf: Map<String, String>? = null
        var pendingKey: KeyInfo? = null
        var pendingMap: String? = null
        var pendingByteRange: Pair<Long, Long>? = null
        var pendingDur = 0.0
        var byteRangeCursor = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    sawMasterTag = true
                    pendingStreamInf = parseAttrs(line.substringAfter(':', ""))
                }
                // Exact tag name — a bare startsWith("#EXT-X-MEDIA") would also
                // swallow #EXT-X-MEDIA-SEQUENCE (which must be parsed as its own
                // tag, or the AES-128 IV default would be wrong).
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    val uri = a["URI"]
                    if (a["TYPE"].equals("AUDIO", true) && !uri.isNullOrBlank()) {
                        audios.add(AudioRendition(a["GROUP-ID"].orEmpty(), a["NAME"].orEmpty(), resolve(baseUrl, uri)))
                    }
                }
                line.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDur = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSeq = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.startsWith("#EXTINF") ->
                    pendingDur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE") -> {
                    val v = line.substringAfter(':').trim()
                    val len = v.substringBefore('@').toLongOrNull() ?: 0L
                    // No explicit offset = continue right after the previous
                    // range (RFC 8216).
                    val off = if (v.contains('@')) {
                        v.substringAfter('@').toLongOrNull() ?: byteRangeCursor
                    } else {
                        byteRangeCursor
                    }
                    if (len > 0) {
                        pendingByteRange = off to (off + len - 1)
                        byteRangeCursor = off + len
                    }
                }
                line.startsWith("#EXT-X-KEY") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    val method = a["METHOD"].orEmpty()
                    pendingKey = if (method.isBlank() || method.equals("NONE", true)) {
                        null
                    } else {
                        KeyInfo(method, resolve(baseUrl, a["URI"].orEmpty()), parseIv(a["IV"]))
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val a = parseAttrs(line.substringAfter(':', ""))
                    pendingMap = a["URI"]?.let { resolve(baseUrl, it) }
                }
                line.startsWith("#") -> {}
                else -> {
                    val si = pendingStreamInf
                    if (si != null) {
                        val bw = si["BANDWIDTH"]?.toLongOrNull()
                            ?: si["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                        val height = si["RESOLUTION"]?.substringAfterLast('x')?.toIntOrNull() ?: 0
                        variants.add(Variant(resolve(baseUrl, line), bw, si["AUDIO"], height))
                        pendingStreamInf = null
                    } else {
                        segments.add(Segment(resolve(baseUrl, line), pendingDur, pendingByteRange, pendingKey, pendingMap))
                        pendingDur = 0.0
                        pendingByteRange = null
                    }
                }
            }
        }
        return Playlist(
            isMaster = sawMasterTag && segments.isEmpty(),
            variants = variants,
            audios = audios,
            segments = segments,
            targetDuration = targetDur,
            mediaSequence = mediaSeq,
        )
    }

    /** `KEY=VAL,KEY2="quoted, with comma"` → map. */
    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < n && s[j] == '"') {
                val end = s.indexOf('"', j + 1)
                if (end < 0) {
                    value = s.substring(j + 1)
                    i = n
                } else {
                    value = s.substring(j + 1, end)
                    i = end + 1
                }
            } else {
                var end = s.indexOf(',', j)
                if (end < 0) end = n
                value = s.substring(j, end).trim()
                i = end
            }
            if (key.isNotEmpty()) out[key] = value
            if (i < n && s[i] == ',') i++
        }
        return out
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref
    }

    private fun parseIv(s: String?): ByteArray? {
        if (s.isNullOrBlank()) return null
        val hex = s.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) {
                ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }

    /** Default AES-128 IV per RFC 8216: the segment's media sequence number as
     *  a 16-byte big-endian integer. */
    private fun ivFor(seq: Int): ByteArray {
        val iv = ByteArray(16)
        var v = seq.toLong()
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return iv
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ----------------------------------------------------------------- HTTP --

    private fun applyHeaders(req: Request.Builder, headers: Map<String, String>, ua: String) {
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("User-Agent", true)) req.header(k, v)
        }
        req.header("User-Agent", ua)
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        ua: String,
    ): Pair<String, String> {
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        DownloadHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response body")
            return body to resp.request.url.toString()
        }
    }

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        ua: String,
        byteRange: Pair<Long, Long>? = null,
    ): ByteArray {
        val req = Request.Builder().url(url)
        applyHeaders(req, headers, ua)
        if (byteRange != null) req.header("Range", "bytes=${byteRange.first}-${byteRange.second}")
        DownloadHttp.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            val len = body.contentLength()
            if (len > MAX_MEM_SEGMENT) throw IOException("Segment too large to buffer")
            val bytes = body.bytes()
            return if (byteRange != null && resp.code == 206) {
                val want = (byteRange.second - byteRange.first + 1).toInt()
                if (bytes.size > want) bytes.copyOf(want) else bytes
            } else {
                bytes
            }
        }
    }

    // --------------------------------------------------------------- Helpers --

    private fun extFor(fmp4: Boolean): String = if (fmp4) ".m4s" else ".ts"

    private fun targetDuration(segs: List<Segment>, declared: Int): Int {
        val max = segs.maxOfOrNull { it.duration } ?: 0.0
        return maxOf(declared, kotlin.math.ceil(max).toInt(), 1)
    }

    private fun fmtDuration(d: Double): String {
        val rounded = kotlin.math.round(d * 1000.0).toLong()
        return if (rounded % 1000 == 0L) (rounded / 1000).toString()
        else String.format(java.util.Locale.US, "%.3f", d)
    }
}
