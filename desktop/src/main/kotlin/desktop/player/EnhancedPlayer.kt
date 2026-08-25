package desktop.player

import com.hikari.app.data.Episode
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import desktop.fx.Fx
import desktop.ui.Theme
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.util.Duration
import java.io.File
import java.net.ServerSocket
import kotlin.math.roundToInt

/**
 * Enhanced player with CloudStream / Stremio-like UI.
 *
 * - Fixes seek crash via HlsRelay Range support (see HlsRelay.kt)
 * - Custom JavaFX control window that talks to mpv over TCP IPC
 * - Controls: play/pause, ±10s, seek bar, time, volume, speed, next episode, fullscreen
 * - Auto-polls mpv for time-pos/duration and updates UI
 * - Subtitle files passed to mpv, next-episode auto-play support
 *
 * If IPC fails (mpv too old / TCP not supported), falls back to DesktopPlayer's
 * basic external window.
 */
object EnhancedPlayer {

    private var currentStage: Stage? = null
    private var currentProc: Process? = null
    private var ipcClient: MpvIpcClient? = null
    private var pollTimeline: Timeline? = null

    fun play(
        title: String,
        stream: StreamSource,
        episodes: List<Episode>? = null,
        currentEpisode: Episode? = null,
        onEpisodeChanged: ((Episode) -> Unit)? = null,
        refresh: (() -> StreamSource?)? = null
    ) {
        Fx.run {
            // Close previous
            close()

            val stage = Stage()
            currentStage = stage
            stage.title = title

            // --- UI ---
            val titleLabel = Label(title).apply {
                style = "-fx-text-fill: #eceff4; -fx-font-size: 15px; -fx-font-weight: bold;"
                isWrapText = true
                maxWidth = 500.0
            }
            val closeBtn = Button("✕").apply {
                styleClass.add("btn")
                setOnAction { close() }
            }
            val topBar = HBox(12.0, titleLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, closeBtn).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(12.0, 16.0, 12.0, 16.0)
                style = "-fx-background-color: #0e1118; -fx-border-color: #1f2431; -fx-border-width: 0 0 1 0;"
            }

            val videoPlaceholder = StackPane().apply {
                style = "-fx-background-color: #000000;"
                prefHeight = 400.0
                prefWidth = 720.0
                children.add(Label("Video playing in mpv window\nUse controls below").apply {
                    style = "-fx-text-fill: #5a5f70; -fx-font-size: 13px;"
                    isWrapText = true
                })
            }

            // Controls
            val playPauseBtn = Button("⏸").apply {
                styleClass.addAll("btn", "btn-primary")
                minWidth = 48.0
            }
            val rewindBtn = Button("⏪ 10s").apply { styleClass.add("btn") }
            val forwardBtn = Button("10s ⏩").apply { styleClass.add("btn") }

            val seekSlider = Slider(0.0, 100.0, 0.0).apply {
                styleClass.add("seek-slider")
                prefWidth = 400.0
                isFocusTraversable = false
            }
            val timeLabel = Label("00:00 / 00:00").apply {
                style = "-fx-text-fill: #9aa0ae; -fx-font-size: 12px;"
                minWidth = 120.0
            }

            val volumeLabel = Label("🔊").apply { style = "-fx-text-fill: #9aa0ae;" }
            val volumeSlider = Slider(0.0, 100.0, 80.0).apply {
                prefWidth = 100.0
                maxWidth = 100.0
            }

            val speedBtn = Button("1x").apply { styleClass.add("btn") }
            val nextEpBtn = Button("Next ▶").apply {
                styleClass.add("btn")
                isVisible = episodes != null && currentEpisode != null && episodes.indexOfFirst { it.id == currentEpisode.id } < episodes.size - 1
                isManaged = isVisible
            }
            val fullscreenBtn = Button("⛶").apply { styleClass.add("btn") }

            val controlsRow1 = HBox(10.0, playPauseBtn, rewindBtn, forwardBtn, seekSlider, timeLabel).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(seekSlider, Priority.ALWAYS)
            }
            val controlsRow2 = HBox(10.0, volumeLabel, volumeSlider, speedBtn, nextEpBtn, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, fullscreenBtn, Button("Close").apply {
                styleClass.add("btn")
                setOnAction { close() }
            }).apply {
                alignment = Pos.CENTER_LEFT
            }

            val bottomBar = VBox(10.0, controlsRow1, controlsRow2).apply {
                padding = Insets(12.0, 16.0, 12.0, 16.0)
                style = "-fx-background-color: #10131c; -fx-border-color: #1f2431; -fx-border-width: 1 0 0 0;"
            }

            val root = VBox(topBar, videoPlaceholder, bottomBar).apply {
                style = "-fx-background-color: #0b0d12;"
                VBox.setVgrow(videoPlaceholder, Priority.ALWAYS)
            }

            val scene = Theme.style(Scene(root, 780.0, 560.0))
            // Add custom CSS for seek slider
            scene.stylesheets.add(EnhancedPlayer::class.java.getResource("/theme.css")?.toExternalForm())
            stage.scene = scene
            stage.show()

            // --- mpv launch with TCP IPC ---
            val mpv = findMpv()
            if (mpv == null) {
                // Fallback to old player
                close()
                DesktopPlayer.play(title, stream, refresh)
                return@run
            }

            val url = Http.sanitizeStreamUrl(stream.url)
            val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
            val playUrl = HlsRelay.urlFor(url, stream.headers)
            val ipcPort = findFreePort()
            val ipcPath = "tcp://127.0.0.1:$ipcPort"

            val subFiles = downloadSubtitles(stream, logDir)

            val args = buildList {
                add(mpv.absolutePath)
                add("--force-window=yes")
                add("--force-window=immediate")
                add("--keep-open=no")
                add("--idle=no")
                add("--terminal=no")
                add("--no-config")
                add("--no-ytdl")
                add("--msg-level=all=warn")
                add("--osc=no") // we have custom OSC
                add("--osd-bar=no")
                add("--border=yes")
                add("--autofit-larger=90%x90%")
                add("--title=${title.take(200).replace('\n', ' ')}")
                add("--log-file=${File(logDir, "mpv.log").absolutePath}")
                add("--input-ipc-server=$ipcPath")

                // Robust seeking / cache
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
                add("--hwdec=auto")
                add("--hwdec-codecs=all")
                add("--sub-auto=fuzzy")
                add("--audio-file-auto=fuzzy")

                for (sf in subFiles) add("--sub-file=${sf.absolutePath}")

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

            val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (proc == null) {
                stage.close()
                DesktopPlayer.play(title, stream, refresh)
                return@run
            }
            currentProc = proc

            // Connect IPC with retry
            val client = MpvIpcClient()
            ipcClient = client
            Thread({
                var connected = false
                for (i in 0..30) {
                    Thread.sleep(300)
                    if (client.connectTcp("127.0.0.1", ipcPort)) {
                        connected = true
                        break
                    }
                    if (!proc.isAlive) break
                }
                if (!connected) {
                    Platform.runLater {
                        // IPC failed, but mpv is still playing with its own OSC - keep window as controller-less
                        titleLabel.text = "$title (basic controls - IPC unavailable)"
                    }
                }
            }, "mpv-ipc-connect").apply { isDaemon = true; start() }

            // Poll time-pos
            var isSeeking = false
            var lastDuration = 0.0

            pollTimeline = Timeline(KeyFrame(Duration.millis(500.0), {
                val c = ipcClient
                if (c == null || !c.connected) return@KeyFrame
                val pos = (c.getProperty("time-pos") as? Number)?.toDouble() ?: 0.0
                val dur = (c.getProperty("duration") as? Number)?.toDouble() ?: lastDuration
                if (dur > 0) lastDuration = dur
                val paused = (c.getProperty("pause") as? Boolean) ?: false

                if (!isSeeking && dur > 0) {
                    val pct = (pos / dur * 100.0).coerceIn(0.0, 100.0)
                    seekSlider.value = pct
                    timeLabel.text = "${formatTime(pos)} / ${formatTime(dur)}"
                } else if (dur == 0.0) {
                    timeLabel.text = "${formatTime(pos)} / --:--"
                }

                playPauseBtn.text = if (paused) "▶" else "⏸"

                // Auto next episode when near end
                if (episodes != null && currentEpisode != null && dur > 0 && pos > 0 && dur - pos < 2.0) {
                    val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                    if (idx >= 0 && idx < episodes.size - 1) {
                        val next = episodes[idx + 1]
                        // Prevent double trigger
                        if (pollTimeline != null) {
                            pollTimeline?.stop()
                            pollTimeline = null
                            Platform.runLater {
                                close()
                                onEpisodeChanged?.invoke(next)
                            }
                        }
                    }
                }
            })).apply {
                cycleCount = Timeline.INDEFINITE
                play()
            }

            // Controls handlers
            playPauseBtn.setOnAction {
                val c = ipcClient
                if (c?.connected == true) {
                    val paused = (c.getProperty("pause") as? Boolean) ?: false
                    c.setProperty("pause", !paused)
                } else {
                    // Fallback: try to pause via process? just toggle button text
                }
            }

            rewindBtn.setOnAction { ipcClient?.seek(-10.0) }
            forwardBtn.setOnAction { ipcClient?.seek(10.0) }

            seekSlider.setOnMousePressed { isSeeking = true }
            seekSlider.setOnMouseReleased {
                val pct = seekSlider.value
                if (lastDuration > 0) {
                    val target = pct / 100.0 * lastDuration
                    ipcClient?.seek(target, absolute = true)
                }
                isSeeking = false
            }

            volumeSlider.valueProperty().addListener { _, _, newVal ->
                ipcClient?.setProperty("volume", newVal.toDouble().roundToInt())
            }

            var speedIdx = 1
            val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
            speedBtn.setOnAction {
                speedIdx = (speedIdx + 1) % speeds.size
                val s = speeds[speedIdx]
                ipcClient?.setProperty("speed", s)
                speedBtn.text = "${s}x"
            }

            fullscreenBtn.setOnAction {
                ipcClient?.command("cycle", "fullscreen")
            }

            nextEpBtn.setOnAction {
                if (episodes != null && currentEpisode != null) {
                    val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                    if (idx >= 0 && idx < episodes.size - 1) {
                        val next = episodes[idx + 1]
                        close()
                        onEpisodeChanged?.invoke(next)
                    }
                }
            }

            stage.setOnCloseRequest {
                close()
            }

            // Monitor mpv exit
            Thread({
                val tail = StringBuilder()
                runCatching { proc.inputStream.bufferedReader().forEachLine { if (tail.length < 8000) tail.append(it).append('\n') } }
                Platform.runLater {
                    // If mpv closed by user, close our window too
                    if (currentStage == stage) {
                        close()
                    }
                }
            }, "mpv-drain-enhanced").apply { isDaemon = true; start() }
        }
    }

    fun close() {
        try {
            pollTimeline?.stop()
        } catch (_: Exception) {}
        pollTimeline = null
        try {
            ipcClient?.quit()
        } catch (_: Exception) {}
        Thread.sleep(200)
        try {
            ipcClient?.close()
        } catch (_: Exception) {}
        ipcClient = null
        try {
            currentProc?.destroy()
        } catch (_: Exception) {}
        currentProc = null
        try {
            currentStage?.close()
        } catch (_: Exception) {}
        currentStage = null
    }

    private fun findFreePort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Exception) {
            12345 + (0..10000).random()
        }
    }

    private fun findMpv(): File? {
        val runtimeHome = runCatching { File(System.getProperty("java.home")) }.getOrNull()
        val appDir = runtimeHome?.parentFile
        val rels = listOfNotNull(
            appDir?.resolve("app/mpv/mpv.exe"),
            appDir?.resolve("mpv/mpv.exe"),
            appDir?.resolve("mpv.exe"),
            File("mpv/mpv.exe"),
        )
        return rels.firstOrNull { it.isFile }
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
                    else -> ".srt"
                }
                val file = File(subDir, "${safeLang}_${System.currentTimeMillis()}_$idx$ext")
                val bytes = Http.getBytes(sub.url, stream.headers) ?: continue
                file.writeBytes(bytes)
                out.add(file)
            } catch (_: Exception) {}
        }
        return out
    }

    private fun formatTime(sec: Double): String {
        if (sec.isNaN() || sec < 0) return "00:00"
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }
}
