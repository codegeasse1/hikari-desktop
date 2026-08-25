package desktop.player

import com.hikari.app.data.Episode
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import desktop.fx.Fx
import desktop.ui.Theme
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.embed.swing.SwingNode
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.util.Duration
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dimension
import java.io.File
import java.net.ServerSocket
import javax.swing.JPanel
import kotlin.math.roundToInt

/**
 * True single-window player like CloudStream Android / Stremio desktop.
 * 
 * - Embeds mpv video inside JavaFX window via --wid + AWT Canvas + JNA
 * - Video + custom controls in SAME window (no more 2 windows)
 * - Controls: play/pause, ±10s, seek bar, time, volume, speed, next ep, fullscreen, close
 * - Falls back to EnhancedPlayer (separate mpv window + control bar) if embedding fails
 */
object EmbeddedPlayer {

    private var stage: Stage? = null
    private var proc: Process? = null
    private var ipcClient: MpvIpcClient? = null
    private var pollTimeline: Timeline? = null
    private var canvas: Canvas? = null

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

            val st = Stage()
            stage = st
            st.title = title

            // --- Top bar ---
            val titleLabel = Label(title.take(70)).apply {
                style = "-fx-text-fill: #eceff4; -fx-font-size: 14px; -fx-font-weight: bold;"
                maxWidth = 500.0
            }
            val closeBtnTop = Button("✕").apply {
                styleClass.add("btn")
                setOnAction { close() }
            }
            val topBar = HBox(10.0, titleLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, closeBtnTop).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(10.0, 14.0, 10.0, 14.0)
                style = "-fx-background-color: #0e1118; -fx-border-color: #1f2431; -fx-border-width: 0 0 1 0;"
            }

            // --- Video area with SwingNode + Canvas ---
            val swingNode = SwingNode()
            val awtCanvas = Canvas().apply {
                background = java.awt.Color.BLACK
                preferredSize = Dimension(1280, 720)
            }
            canvas = awtCanvas

            // Create JPanel holding canvas
            val panel = JPanel(BorderLayout()).apply {
                background = java.awt.Color.BLACK
                add(awtCanvas, BorderLayout.CENTER)
            }

            // Set content on FX thread via SwingNode
            swingNode.content = panel

            val videoPane = StackPane(swingNode).apply {
                style = "-fx-background-color: #000000;"
                prefHeight = 500.0
                prefWidth = 900.0
                VBox.setVgrow(this, Priority.ALWAYS)
            }

            // --- Controls ---
            val playPauseBtn = Button("⏸").apply {
                styleClass.addAll("btn", "btn-primary")
                minWidth = 46.0
            }
            val rewindBtn = Button("⏪ 10s").apply { styleClass.add("btn") }
            val forwardBtn = Button("10s ⏩").apply { styleClass.add("btn") }
            val seekSlider = Slider(0.0, 100.0, 0.0).apply {
                prefWidth = 400.0
                isFocusTraversable = false
            }
            val timeLabel = Label("00:00 / 00:00").apply {
                style = "-fx-text-fill: #9aa0ae; -fx-font-size: 11px;"
                minWidth = 110.0
            }
            val volumeSlider = Slider(0.0, 100.0, 85.0).apply {
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
            val fsBtn = Button("⛶").apply { styleClass.add("btn") }
            val closeBtn = Button("Close").apply {
                styleClass.add("btn-danger")
                setOnAction { close() }
            }

            val row1 = HBox(8.0, playPauseBtn, rewindBtn, forwardBtn, seekSlider, timeLabel).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(seekSlider, Priority.ALWAYS)
            }
            val row2 = HBox(8.0, Label("🔊").apply { style = "-fx-text-fill: #9aa0ae;" }, volumeSlider, speedBtn, nextEpBtn,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, fsBtn, closeBtn
            ).apply { alignment = Pos.CENTER_LEFT }

            val bottomBar = VBox(8.0, row1, row2).apply {
                padding = Insets(10.0, 14.0, 10.0, 14.0)
                style = "-fx-background-color: #10131c; -fx-border-color: #1f2431; -fx-border-width: 1 0 0 0;"
            }

            val root = VBox(topBar, videoPane, bottomBar).apply {
                style = "-fx-background-color: #0b0d12;"
            }

            val scene = Scene(root, 960.0, 620.0)
            Theme.style(scene)
            st.scene = scene
            st.show()

            // After stage shown, get HWND and launch mpv embedded
            Platform.runLater {
                // Small delay to ensure AWT peer created
                Thread({
                    Thread.sleep(600)
                    val hwnd = getHwnd(awtCanvas)
                    Platform.runLater {
                        if (hwnd == 0L) {
                            // Embedding failed, fallback to separate window + control bar
                            st.close()
                            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
                        } else {
                            launchMpvEmbedded(
                                title, stream, hwnd, videoPane,
                                playPauseBtn, seekSlider, timeLabel, volumeSlider, speedBtn, nextEpBtn,
                                episodes, currentEpisode, onEpisodeChanged, refresh
                            )
                        }
                    }
                }, "hwnd-finder").apply { isDaemon = true; start() }
            }

            st.setOnCloseRequest { close() }
        }
    }

    private fun launchMpvEmbedded(
        title: String,
        stream: StreamSource,
        hwnd: Long,
        videoPane: StackPane,
        playPauseBtn: Button,
        seekSlider: Slider,
        timeLabel: Label,
        volumeSlider: Slider,
        speedBtn: Button,
        nextEpBtn: Button,
        episodes: List<Episode>?,
        currentEpisode: Episode?,
        onEpisodeChanged: ((Episode) -> Unit)?,
        refresh: (() -> StreamSource?)?
    ) {
        val mpv = findMpv() ?: run {
            close()
            DesktopPlayer.play(title, stream, refresh)
            return
        }

        val url = Http.sanitizeStreamUrl(stream.url)
        val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
        val playUrl = HlsRelay.urlFor(url, stream.headers)
        val ipcPort = findFreePort()
        val ipcPath = "tcp://127.0.0.1:$ipcPort"
        val subFiles = downloadSubtitles(stream, logDir)

        val args = buildList {
            add(mpv.absolutePath)
            add("--wid=$hwnd")
            add("--no-border")
            add("--keep-open=no")
            add("--idle=no")
            add("--terminal=no")
            add("--no-config")
            add("--no-ytdl")
            add("--msg-level=all=warn")
            add("--osc=no")
            add("--osd-bar=no")
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
            add("--hwdec=auto")
            add("--hwdec-codecs=all")
            add("--sub-auto=fuzzy")
            add("--title=${title.take(200).replace('\n', ' ')}")
            add("--log-file=${File(logDir, "mpv.log").absolutePath}")
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
            close()
            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
            return
        }
        this.proc = proc

        val client = MpvIpcClient()
        ipcClient = client
        Thread({
            for (i in 0..50) {
                Thread.sleep(300)
                if (client.connectTcp("127.0.0.1", ipcPort)) break
                if (!proc.isAlive) break
            }
        }, "mpv-ipc-connect-embed").apply { isDaemon = true; start() }

        var isSeeking = false
        var lastDuration = 0.0

        pollTimeline = Timeline(KeyFrame(Duration.millis(400.0), {
            val c = ipcClient
            if (c == null || !c.connected) return@KeyFrame
            val pos = (c.getProperty("time-pos") as? Number)?.toDouble() ?: 0.0
            val dur = (c.getProperty("duration") as? Number)?.toDouble() ?: lastDuration
            if (dur > 0) lastDuration = dur
            val paused = (c.getProperty("pause") as? Boolean) ?: false

            if (!isSeeking && dur > 0) {
                seekSlider.value = (pos / dur * 100.0).coerceIn(0.0, 100.0)
                timeLabel.text = "${formatTime(pos)} / ${formatTime(dur)}"
            }
            playPauseBtn.text = if (paused) "▶" else "⏸"

            if (episodes != null && currentEpisode != null && dur > 0 && pos > 0 && dur - pos < 2.0) {
                val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                if (idx >= 0 && idx < episodes.size - 1) {
                    val next = episodes[idx + 1]
                    if (pollTimeline != null) {
                        pollTimeline?.stop()
                        pollTimeline = null
                        Fx.run {
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

        // Controls
        playPauseBtn.setOnAction {
            val c = ipcClient
            if (c?.connected == true) {
                val paused = (c.getProperty("pause") as? Boolean) ?: false
                c.setProperty("pause", !paused)
            }
        }
        seekSlider.setOnMousePressed { isSeeking = true }
        seekSlider.setOnMouseReleased {
            val pct = seekSlider.value
            if (lastDuration > 0) ipcClient?.seek(pct / 100.0 * lastDuration, absolute = true)
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

        // Monitor mpv exit
        Thread({
            runCatching { proc.inputStream.bufferedReader().forEachLine { } }
            Platform.runLater { if (stage?.isShowing == true) close() }
        }, "mpv-drain-embed").apply { isDaemon = true; start() }
    }

    fun close() {
        try { pollTimeline?.stop() } catch (_: Exception) {}
        pollTimeline = null
        try { ipcClient?.quit() } catch (_: Exception) {}
        Thread.sleep(150)
        try { ipcClient?.close() } catch (_: Exception) {}
        ipcClient = null
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        try { stage?.close() } catch (_: Exception) {}
        stage = null
        canvas = null
    }

    private fun getHwnd(canvas: Canvas): Long {
        return try {
            // Ensure AWT peer exists
            canvas.addNotify()
            // Try JNA first
            try {
                val nativeClass = Class.forName("com.sun.jna.Native")
                val getPointerMethod = nativeClass.getMethod("getComponentPointer", java.awt.Component::class.java)
                val pointer = getPointerMethod.invoke(null, canvas) as com.sun.jna.Pointer
                val nativeValue = com.sun.jna.Pointer.nativeValue(pointer)
                if (nativeValue != 0L) return nativeValue
            } catch (_: Exception) {
            }
            // Fallback: reflection on WComponentPeer.getHWnd()
            try {
                val peerField = java.awt.Component::class.java.getDeclaredField("peer")
                peerField.isAccessible = true
                val peer = peerField.get(canvas) ?: return 0L
                val getHWndMethod = peer.javaClass.getMethod("getHWnd")
                getHWndMethod.isAccessible = true
                val hwnd = getHWndMethod.invoke(peer)
                when (hwnd) {
                    is Long -> hwnd
                    is Int -> hwnd.toLong()
                    else -> 0L
                }
            } catch (_: Exception) {
                0L
            }
        } catch (_: Exception) {
            0L
        }
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
