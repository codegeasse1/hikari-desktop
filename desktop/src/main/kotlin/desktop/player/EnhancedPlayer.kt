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
import javafx.stage.StageStyle
import javafx.util.Duration
import java.io.File
import java.net.ServerSocket
import kotlin.math.roundToInt

/**
 * Enhanced player with CloudStream / Stremio-like controls.
 * 
 * Fixes from previous version:
 * - No longer shows large black placeholder window that looked like a second player.
 * - Now shows ONLY a compact control bar (like YouTube / Stremio overlay) that controls mpv via IPC.
 * - mpv window is the ONLY video window (single player window).
 * - Control bar is always-on-top, auto-positions near bottom, with modern Hikari styling.
 */
object EnhancedPlayer {

    private var controlStage: Stage? = null
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
            close()

            // --- Find mpv ---
            val mpv = findMpv()
            if (mpv == null) {
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
                add("--osc=no") // we provide custom controls
                add("--osd-bar=no")
                add("--border=yes")
                add("--autofit-larger=85%x85%")
                add("--geometry=50%:50%") // center
                add("--title=${title.take(200).replace('\n', ' ')}")
                add("--log-file=${File(logDir, "mpv.log").absolutePath}")
                add("--input-ipc-server=$ipcPath")
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
                DesktopPlayer.play(title, stream, refresh)
                return@run
            }
            currentProc = proc

            // --- Build compact control bar (single window, not a second player) ---
            val stage = Stage()
            controlStage = stage
            stage.title = "Hikari Controls"
            stage.initStyle(StageStyle.UNDECORATED)
            stage.isAlwaysOnTop = true

            val titleLabel = Label(title.take(80)).apply {
                style = "-fx-text-fill: #eceff4; -fx-font-size: 13px; -fx-font-weight: bold;"
                maxWidth = 320.0
            }

            val playPauseBtn = Button("⏸").apply {
                styleClass.addAll("btn", "btn-primary")
                minWidth = 44.0
                style = "-fx-font-size: 14px;"
            }
            val rewindBtn = Button("⏪ 10s").apply { styleClass.add("btn") }
            val forwardBtn = Button("10s ⏩").apply { styleClass.add("btn") }

            val seekSlider = Slider(0.0, 100.0, 0.0).apply {
                prefWidth = 380.0
                isFocusTraversable = false
            }
            val timeLabel = Label("00:00 / 00:00").apply {
                style = "-fx-text-fill: #9aa0ae; -fx-font-size: 11px;"
                minWidth = 110.0
            }

            val volumeSlider = Slider(0.0, 100.0, 80.0).apply {
                prefWidth = 90.0
                maxWidth = 90.0
            }
            val speedBtn = Button("1x").apply { styleClass.add("btn") }
            val nextEpBtn = Button("Next ▶").apply {
                styleClass.add("btn")
                val hasNext = episodes != null && currentEpisode != null && episodes.indexOfFirst { it.id == currentEpisode.id } < episodes.size - 1
                isVisible = hasNext
                isManaged = hasNext
            }
            val closeBtn = Button("✕ Close").apply { styleClass.add("btn-danger") }

            val row1 = HBox(8.0, playPauseBtn, rewindBtn, forwardBtn, seekSlider, timeLabel).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(seekSlider, Priority.ALWAYS)
            }
            val row2 = HBox(8.0, titleLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Label("🔊").apply { style = "-fx-text-fill: #9aa0ae;" }, volumeSlider, speedBtn, nextEpBtn, closeBtn
            ).apply { alignment = Pos.CENTER_LEFT }

            val root = VBox(8.0, row1, row2).apply {
                padding = Insets(12.0, 14.0, 12.0, 14.0)
                style = "-fx-background-color: #141722; -fx-background-radius: 12; -fx-border-color: #2a2f42; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 12, 0, 0, 3);"
            }

            val scene = Scene(root)
            scene.fill = javafx.scene.paint.Color.TRANSPARENT
            // Apply theme
            Theme.style(scene)
            stage.scene = scene
            stage.width = 780.0
            stage.height = 100.0
            // Position at bottom center of screen
            stage.x = 100.0
            stage.y = 600.0
            stage.show()

            // Make draggable
            var dragX = 0.0
            var dragY = 0.0
            root.setOnMousePressed { e ->
                dragX = e.sceneX
                dragY = e.sceneY
            }
            root.setOnMouseDragged { e ->
                stage.x = e.screenX - dragX
                stage.y = e.screenY - dragY
            }

            // IPC connect
            val client = MpvIpcClient()
            ipcClient = client
            Thread({
                for (i in 0..40) {
                    Thread.sleep(300)
                    if (client.connectTcp("127.0.0.1", ipcPort)) break
                    if (!proc.isAlive) break
                }
            }, "mpv-ipc-connect").apply { isDaemon = true; start() }

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
                    seekSlider.value = (pos / dur * 100.0).coerceIn(0.0, 100.0)
                    timeLabel.text = "${formatTime(pos)} / ${formatTime(dur)}"
                } else if (dur == 0.0) {
                    timeLabel.text = "${formatTime(pos)} / --:--"
                }
                playPauseBtn.text = if (paused) "▶" else "⏸"

                if (episodes != null && currentEpisode != null && dur > 0 && pos > 0 && dur - pos < 2.0) {
                    val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                    if (idx >= 0 && idx < episodes.size - 1) {
                        val next = episodes[idx + 1]
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

            playPauseBtn.setOnAction {
                val c = ipcClient
                if (c?.connected == true) {
                    val paused = (c.getProperty("pause") as? Boolean) ?: false
                    c.setProperty("pause", !paused)
                }
            }
            rewindBtn.setOnAction { ipcClient?.seek(-10.0) }
            forwardBtn.setOnAction { ipcClient?.seek(10.0) }
            seekSlider.setOnMousePressed { isSeeking = true }
            seekSlider.setOnMouseReleased {
                val pct = seekSlider.value
                if (lastDuration > 0) {
                    ipcClient?.seek(pct / 100.0 * lastDuration, absolute = true)
                }
                isSeeking = false
            }
            volumeSlider.valueProperty().addListener { _, _, nv ->
                ipcClient?.setProperty("volume", nv.toDouble().roundToInt())
            }
            var speedIdx = 2
            val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
            speedBtn.setOnAction {
                speedIdx = (speedIdx + 1) % speeds.size
                val s = speeds[speedIdx]
                ipcClient?.setProperty("speed", s)
                speedBtn.text = "${s}x"
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
            closeBtn.setOnAction { close() }
            stage.setOnCloseRequest { close() }

            Thread({
                runCatching { proc.inputStream.bufferedReader().forEachLine { } }
                Platform.runLater { if (controlStage == stage) close() }
            }, "mpv-drain-enhanced").apply { isDaemon = true; start() }
        }
    }

    fun close() {
        try { pollTimeline?.stop() } catch (_: Exception) {}
        pollTimeline = null
        try { ipcClient?.quit() } catch (_: Exception) {}
        Thread.sleep(150)
        try { ipcClient?.close() } catch (_: Exception) {}
        ipcClient = null
        try { currentProc?.destroy() } catch (_: Exception) {}
        currentProc = null
        try { controlStage?.close() } catch (_: Exception) {}
        controlStage = null
    }

    private fun findFreePort(): Int {
        return try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { 12345 + (0..10000).random() }
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
                val ext = if (sub.url.endsWith(".vtt")) ".vtt" else ".srt"
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
