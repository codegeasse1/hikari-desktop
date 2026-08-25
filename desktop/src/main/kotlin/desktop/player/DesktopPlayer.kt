package desktop.player

import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import desktop.fx.DesktopUi
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.io.File
import java.net.Socket
import java.nio.file.Files
import desktop.ui.Theme

/**
 * Desktop player — mpv with robust seeking and CloudStream/Stremio-like UX.
 *
 * Why mpv: JavaFX's MediaPlayer refuses most HLS/CDN streams (MediaException,
 * black window). mpv plays HLS/MP4/MKV natively and is the same engine
 * Stremio desktop uses.
 *
 * Previous issue — "skip forward stuck/crash":
 * - HlsRelay ignored HTTP Range, so MP4 seeks returned full file from byte 0.
 *   mpv got wrong bytes and either stalled or exited non-zero.
 * - mpv args lacked cache / demuxer limits, so a seek could exhaust the
 *   demuxer queue and freeze.
 *
 * Fixes:
 * - HlsRelay now forwards Range and returns 206 Partial Content (see HlsRelay.kt)
 * - mpv launched with cache, demuxer, hr-seek, force-seekable, hwdec=auto,
 *   keep-open=no, and proper OSC (Stremio-like bar)
 * - IPC server for graceful quit and future custom UI (play/pause/seek)
 * - Subtitles from StreamSource are downloaded and passed as --sub-file
 */
object DesktopPlayer {

    private var proc: Process? = null
    private var ipcPath: String? = null
    private var loadingStage: javafx.stage.Stage? = null
    @Volatile private var dialogShown = false

    private fun closeLoading() {
        Fx.run {
            runCatching { loadingStage?.close() }
            loadingStage = null
        }
    }

    private fun showLoading(title: String) {
        Fx.run {
            runCatching { loadingStage?.close() }
            val s = javafx.stage.Stage()
            val spin = javafx.scene.control.ProgressBar(-1.0).apply { prefWidth = 220.0 }
            val lbl = Label("Player is loading…").apply {
                style = "-fx-text-fill: white; -fx-font-size: 15px;"
                isWrapText = true
            }
            val box = VBox(14.0, spin, lbl).apply {
                alignment = Pos.CENTER
                padding = Insets(26.0)
                style = "-fx-background-color: ${Theme.BG_ELEV};"
            }
            s.scene = Theme.scene(box)
            s.width = 300.0
            s.height = 150.0
            loadingStage = s
            s.show()
        }
    }

    private fun isSignedStreamUrl(url: String): Boolean =
        url.contains("/v1/edge/streams/") || url.contains("mmcdn.com") ||
            url.contains("edge-hls.chaturbate.com")

    fun play(title: String, stream: StreamSource, refresh: (() -> StreamSource?)? = null) {
        val url = Http.sanitizeStreamUrl(stream.url)
        if (stream.externalUrl) {
            Fx.run { DesktopUi.open(url) }
            return
        }
        if (stream.ytId != null) {
            Fx.run { DesktopUi.open("https://www.youtube.com/watch?v=${stream.ytId}") }
            return
        }
        if (stream.isMpd || url.endsWith(".mpd")) {
            Fx.run { showBrowserFallback(title, url, "This stream uses DASH, which the bundled player can't play yet.") }
            return
        }
        if (stream.isTorrent || stream.infoHash != null) {
            Fx.run {
                showBrowserFallback(
                    title, url.ifBlank { "magnet stream (infoHash ${stream.infoHash})" },
                    "This is a torrent stream, which the bundled player can't play yet.",
                )
            }
            return
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Fx.run {
                showBrowserFallback(
                    title, url,
                    "This source uses a web-only video URL (blob:), which only a browser can play. Try another source, or open it in your browser.",
                )
            }
            return
        }
        launchMpv(title, stream, refresh, attemptsLeft = 2)
    }

    private fun launchMpv(title: String, stream: StreamSource, refresh: (() -> StreamSource?)?, attemptsLeft: Int) {
        Fx.run {
            showLoading(title)
            val mpv = findMpv()
            if (mpv == null) {
                val url = Http.sanitizeStreamUrl(stream.url)
                showBrowserFallback(title, url, "The video player (mpv) wasn't found next to the app — re-download the latest release.")
                return@run
            }
            val url = Http.sanitizeStreamUrl(stream.url)
            val signed = isSignedStreamUrl(url)
            val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }

            // Play through HlsRelay so headers/DoH are handled and Range works
            val playUrl = HlsRelay.urlFor(url, stream.headers)

            File(logDir, "hikari-player.log").appendText(
                "[${java.time.Instant.now()}] raw=${debugEscaped(stream.url)}\n  san=${debugEscaped(url)}\n  ply=${debugEscaped(playUrl)}\n"
            )

            // Prepare IPC path for graceful control (quit, seek, etc.)
            val ipc = createIpcPath()
            ipcPath = ipc

            // Download subtitles to temp files if any
            val subFiles = downloadSubtitles(stream, logDir)

            val args = buildList {
                add(mpv.absolutePath)
                // Window & UX — Stremio/CloudStream-like
                add("--force-window=yes")
                add("--force-window=immediate")
                add("--keep-open=no")
                add("--idle=no")
                add("--terminal=no")
                add("--no-config") // ignore user's mpv.conf that could break seeking
                add("--no-ytdl")
                add("--msg-level=all=warn")
                add("--osc=yes")
                add("--osd-bar=yes")
                add("--osd-bar-align-y=0.9")
                add("--osd-duration=2500")
                add("--osd-font-size=22")
                add("--border=yes")
                add("--autofit-larger=90%x90%")
                add("--title=${title.take(200).replace('\n', ' ')}")
                add("--log-file=${File(logDir, "mpv.log").absolutePath}")

                // IPC for future custom UI and clean quit
                add("--input-ipc-server=$ipc")
                add("--input-default-bindings=yes")
                add("--input-vo-keyboard=yes")

                // Seeking / cache robustness — fixes forward-seek stuck/crash
                add("--cache=yes")
                add("--cache-secs=20")
                add("--demuxer-max-bytes=300M")
                add("--demuxer-max-back-bytes=100M")
                add("--demuxer-readahead-secs=20")
                add("--demuxer-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
                add("--stream-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
                add("--force-seekable=yes")
                add("--hr-seek=yes")
                add("--hr-seek-demuxer-offset=10")
                add("--hr-seek-framedrop=no")
                add("--audio-file-auto=fuzzy")
                add("--sub-auto=fuzzy")

                // Hardware decoding — CloudStream Android uses hwdec, Stremio does too
                add("--hwdec=auto")
                add("--hwdec-codecs=all")

                // Subtitles
                for (sf in subFiles) {
                    add("--sub-file=${sf.absolutePath}")
                }

                // Headers / UA
                var hasUA = false
                for ((k, v) in stream.headers) {
                    if (k.isNotBlank() && v.isNotBlank()) {
                        if (k.equals("User-Agent", ignoreCase = true)) hasUA = true
                        add("--http-header-fields=$k: $v")
                    }
                }
                if (!hasUA) add("--user-agent=${Http.UA}")

                add(playUrl)
            }

            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, url, "Couldn't launch the video player. Open it in your browser instead?")
                return@run
            }
            // Close previous instance gracefully via IPC first, then destroy
            proc?.let { old ->
                tryQuitViaIpc()
                Thread.sleep(300)
                runCatching { old.destroy() }
            }
            proc = p

            Thread({ try { Thread.sleep(2500) } catch (_: InterruptedException) {}; closeLoading() }, "hikari-loading-close")
                .apply { isDaemon = true; start() }

            dialogShown = false
            val startedAt = System.currentTimeMillis()
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 8000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    // Clean up IPC file/socket
                    ipcPath?.let { path ->
                        runCatching { File(path).delete() }
                        // On Unix, socket file may be at path
                        runCatching { java.nio.file.Path.of(path).toFile().delete() }
                    }
                    if (code != 0 && !p.isAlive) {
                        val tailText = tail.toString()
                        val forbidden = tailText.contains("403") || tailText.contains("Forbidden")
                        if (attemptsLeft > 0 && refresh != null && (signed || forbidden)) {
                            val fresh = runCatching { refresh() }.getOrNull()
                            if (fresh != null && fresh.url.isNotBlank()) {
                                Fx.run { launchMpv(title, fresh, refresh, attemptsLeft - 1) }
                                return@Thread
                            }
                        }
                        val early = System.currentTimeMillis() - startedAt < 4000
                        // Only show dialog if player died early or got 403. A normal close (code 0) or
                        // late exit after successful playback should be silent.
                        if (early || forbidden) {
                            val err = tailText.trim().lineSequence().filter { it.isNotBlank() }.toList().takeLast(15).joinToString("\n")
                            Fx.run {
                                if (dialogShown || proc !== p) return@run
                                dialogShown = true
                                val hint = if (forbidden)
                                    "\n\nThis site refused the stream link (HTTP 403). The link may have expired —" +
                                        (if (refresh != null) " click Retry to grab a fresh one." else " reopen the stream to get a new one.")
                                else ""
                                showBrowserFallback(
                                    title, url,
                                    buildString {
                                        append("The player closed with an error")
                                        if (err.isNotBlank()) append(":\n").append(err.take(2000))
                                        append(hint)
                                        append("\n\nOpen it in your browser instead?")
                                    },
                                    retry = refresh?.let { r ->
                                        { val f = runCatching { r() }.getOrNull(); if (f != null && f.url.isNotBlank()) launchMpv(title, f, r, 1) }
                                    },
                                )
                            }
                        }
                    }
                },
                "hikari-mpv-drain",
            ).apply { isDaemon = true; start() }

            // Probe for HTML/JSON pages that need browser resolving
            val myProc = p
            if (!signed) {
                Thread(
                    {
                        val verdict = Http.probeStreamUrl(url, stream.headers)
                        if (verdict != Http.StreamProbe.HLS && verdict != Http.StreamProbe.VIDEO && verdict != Http.StreamProbe.UNKNOWN) {
                            Fx.run {
                                if (dialogShown || proc !== myProc || !myProc.isAlive) return@run
                                dialogShown = true
                                runCatching { myProc.destroy() }
                                when (verdict) {
                                    Http.StreamProbe.DASH -> showBrowserFallback(title, url, "This stream uses DASH, which the bundled player can't play yet.")
                                    Http.StreamProbe.HTML -> resolveAndPlay(title, url, stream, refresh, attemptsLeft)
                                    Http.StreamProbe.JSON -> resolveAndPlay(title, url, stream, refresh, attemptsLeft)
                                    else -> {}
                                }
                            }
                        }
                    },
                    "hikari-stream-probe",
                ).apply { isDaemon = true; start() }
            }
        }
    }

    private fun downloadSubtitles(stream: StreamSource, logDir: File): List<File> {
        if (stream.subtitles.isEmpty()) return emptyList()
        val out = mutableListOf<File>()
        val subDir = File(logDir, "subs").apply { mkdirs() }
        for ((idx, sub) in stream.subtitles.withIndex()) {
            try {
                val safeLang = sub.lang.replace(Regex("[^A-Za-z0-9_-]"), "_").take(20).ifBlank { "sub$idx" }
                val ext = when {
                    sub.url.endsWith(".vtt") -> ".vtt"
                    sub.url.endsWith(".ass") -> ".ass"
                    sub.url.endsWith(".ssa") -> ".ssa"
                    else -> ".srt"
                }
                val file = File(subDir, "${safeLang}_${System.currentTimeMillis()}_$idx$ext")
                val bytes = Http.getBytes(sub.url, stream.headers) ?: continue
                file.writeBytes(bytes)
                out.add(file)
            } catch (_: Exception) {
                // ignore failed subtitle
            }
        }
        return out
    }

    private fun createIpcPath(): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) {
            // Windows named pipe: \\.\pipe\mpv-xxx
            "\\\\.\\pipe\\mpv-${System.currentTimeMillis()}-${(0..9999).random()}"
        } else {
            // Unix socket in temp
            val tmp = System.getProperty("java.io.tmpdir")
            "$tmp/mpv-${System.currentTimeMillis()}.sock"
        }
    }

    private fun tryQuitViaIpc() {
        val path = ipcPath ?: return
        try {
            if (path.startsWith("\\\\.\\pipe\\")) {
                // Windows named pipe - try via file write? mpv's named pipe is not easily writable via Java without JNA
                // Fallback to process destroy, but attempt socket-style if possible
                return
            } else {
                // Unix domain socket - try to send quit command via JSON IPC
                // mpv IPC protocol: {"command": ["quit"]}\n
                val socketFile = File(path)
                if (!socketFile.exists()) return
                // Use simple socket connection via Java 16+ UnixDomainSocketAddress if available, else fallback
                runCatching {
                    val addr = java.net.UnixDomainSocketAddress.of(path)
                    java.net.Socket().use { s ->
                        s.connect(addr, 1000)
                        s.getOutputStream().write("{\"command\": [\"quit\"]}\n".toByteArray())
                        s.getOutputStream().flush()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveAndPlay(title: String, url: String, stream: StreamSource, refresh: (() -> StreamSource?)?, attemptsLeft: Int) {
        Thread(
            {
                val resolved = runCatching { desktop.web.FxWebView.resolveStreamUrl(url, 30_000) }.getOrNull()
                Fx.run {
                    if (dialogShown) return@run
                    closeLoading()
                    if (resolved != null && resolved.url.isNotBlank()) {
                        val merged = mergeHeaders(stream.headers, resolved.cookie)
                        launchMpv(title, stream.copy(url = resolved.url, headers = merged), refresh, attemptsLeft)
                    } else {
                        showBrowserFallback(
                            title, url,
                            "That source's server returned a web page instead of a video, and nothing playable could be extracted from it. Try another source, or open it in your browser.",
                            tryAnyway = { launchMpv(title, stream, refresh, attemptsLeft) },
                        )
                    }
                }
            },
            "hikari-resolve",
        ).apply { isDaemon = true; start() }
    }

    private fun mergeHeaders(base: Map<String, String>, cookie: String): Map<String, String> {
        val merged = HashMap(base)
        if (cookie.isNotBlank()) {
            val existing = merged["Cookie"].orEmpty()
            merged["Cookie"] = if (existing.isBlank()) cookie else "$existing; $cookie"
        }
        return merged
    }

    private fun findMpv(): File? {
        val runtimeHome = runCatching { File(System.getProperty("java.home")) }.getOrNull()
        val appDir = runtimeHome?.parentFile
        val rels = listOfNotNull(
            appDir?.resolve("app/mpv/mpv.exe"),
            appDir?.resolve("mpv/mpv.exe"),
            appDir?.resolve("mpv.exe"),
            File("mpv/mpv.exe"),
            File("C:\\mpv\\mpv.exe"),
        )
        return rels.firstOrNull { it.isFile }
    }

    private fun debugEscaped(s: String): String = buildString {
        for (c in s) {
            val cp = c.code
            if (cp < 32 || cp > 126) append("\\u").append(cp.toString(16).padStart(4, '0'))
            else append(c)
        }
    }

    private fun showBrowserFallback(title: String, url: String, reason: String? = null, tryAnyway: (() -> Unit)? = null, retry: (() -> Unit)? = null) {
        closeLoading()
        val stage = Stage()
        stage.title = title
        val label = Label(reason?.takeIf { it.isNotBlank() } ?: "Open it in your browser instead?").apply { isWrapText = true }
        val openBtn = Button("Open in browser").apply {
            setOnAction {
                DesktopUi.open(url)
                stage.close()
            }
        }
        val closeBtn = Button("Close").apply { setOnAction { stage.close() } }
        val buttons = HBox(10.0, openBtn, closeBtn)
        tryAnyway?.let { t -> buttons.children.add(Button("Try anyway").apply { setOnAction { stage.close(); t() } }) }
        retry?.let { r -> buttons.children.add(Button("Retry (fresh link)").apply { setOnAction { stage.close(); r() } }) }
        val box = VBox(14.0, label, buttons).apply {
            alignment = Pos.CENTER
            padding = Insets(24.0)
        }
        stage.scene = Theme.style(Scene(box, 640.0, 240.0))
        stage.show()
    }

    fun closeAll() {
        Fx.run {
            tryQuitViaIpc()
            Thread.sleep(200)
            proc?.let { runCatching { it.destroy() } }
            proc = null
            ipcPath = null
        }
    }
}
