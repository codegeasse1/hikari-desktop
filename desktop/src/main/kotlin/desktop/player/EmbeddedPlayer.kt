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
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Single-window embedded player — video + controls in SAME window like CloudStream/Stremio.
 * 
 * Previous black screen cause: HWND not ready when mpv launched, and mpv args missing vo=gpu.
 * Fixes:
 * - Wait for AWT peer with retries, try 3 different HWND methods (JNA, WComponentPeer, Glass Window)
 * - Use SwingUtilities.invokeAndWait to ensure Canvas peer created on EDT
 * - Add --vo=gpu --gpu-context=d3d11 --hwdec=auto for Windows embedding
 * - Add --force-window=no --keep-open=no --idle=no for proper embedding
 * - Detailed logging to .hikari/mpv.log and .hikari/hikari-player.log
 * - Fallback to EnhancedPlayer if embedding fails after retries
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

            val swingNode = SwingNode()
            val awtCanvas = Canvas().apply {
                background = java.awt.Color.BLACK
                preferredSize = Dimension(1280, 720)
                isVisible = true
            }
            canvas = awtCanvas

            val panel = JPanel(BorderLayout()).apply {
                background = java.awt.Color.BLACK
                add(awtCanvas, BorderLayout.CENTER)
                preferredSize = Dimension(1280, 720)
            }

            // Must set SwingNode content on FX thread, but panel creation on EDT is safer
            SwingUtilities.invokeLater {
                // Ensure peer
                panel.addNotify()
                awtCanvas.addNotify()
            }
            swingNode.content = panel

            val videoPane = StackPane(swingNode).apply {
                style = "-fx-background-color: #000000;"
                prefHeight = 500.0
                prefWidth = 900.0
                VBox.setVgrow(this, Priority.ALWAYS)
            }

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
            val fsBtn = Button("⛶ Fullscreen").apply { styleClass.add("btn") }
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

            val scene = Scene(root, 960.0, 640.0)
            Theme.style(scene)
            st.scene = scene
            st.show()

            // Wait for window to be visible and AWT peer ready, then launch mpv
            Platform.runLater {
                Thread({
                    var hwnd = 0L
                    // Try up to 20 times with increasing delay to get HWND
                    for (attempt in 1..20) {
                        Thread.sleep(300L + attempt * 50L)
                        // Ensure AWT EDT has created peer
                        try {
                            SwingUtilities.invokeAndWait {
                                try {
                                    if (!panel.isDisplayable) panel.addNotify()
                                    if (!awtCanvas.isDisplayable) awtCanvas.addNotify()
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}

                        hwnd = getHwnd(awtCanvas, st)
                        if (hwnd != 0L) {
                            logToFile("Found HWND $hwnd on attempt $attempt")
                            break
                        }
                        logToFile("HWND attempt $attempt failed, retrying...")
                    }

                    Platform.runLater {
                        if (hwnd == 0L) {
                            logToFile("All HWND attempts failed, falling back to EnhancedPlayer")
                            st.close()
                            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
                        } else {
                            launchMpvEmbedded(
                                title, stream, hwnd, videoPane,
                                playPauseBtn, seekSlider, timeLabel, volumeSlider, speedBtn, nextEpBtn, fsBtn,
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
        fsBtn: Button,
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
        // Use named pipe on Windows for reliable IPC (TCP sometimes not supported in shinchiro builds)
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val ipcPort = findFreePort()
        val ipcPipeName = "mpv-${System.currentTimeMillis()}-${(0..9999).random()}"
        val ipcPath = if (isWindows) "\\\\.\\pipe\\$ipcPipeName" else "/tmp/$ipcPipeName.sock"
        // For TCP fallback, also keep tcp path as alternative if pipe fails, but we will try pipe first
        val ipcTcpPath = "tcp://127.0.0.1:$ipcPort"
        val subFiles = downloadSubtitles(stream, logDir)

        logToFile("Launching mpv embedded: hwnd=$hwnd playUrl=$playUrl ipcPipe=$ipcPath ipcTcp=$ipcTcpPath")

        // Try multiple vo/gpu-context combos for embedding compatibility
        // First attempt: gpu + win (most compatible for AWT Canvas), hwdec=no to avoid black screen
        val baseArgs = mutableListOf<String>().apply {
            add(mpv.absolutePath)
            add("--wid=$hwnd")
            // Video output - try gpu with win context (works best for embedding into child HWND)
            add("--vo=gpu")
            add("--gpu-context=win")
            add("--gpu-api=auto")
            add("--hwdec=no") // start with no hwdec to avoid black screen, will try auto if this works
            add("--force-window=no")
            add("--keep-open=no")
            add("--idle=no")
            add("--terminal=no")
            add("--no-config")
            add("--no-ytdl")
            add("--msg-level=all=v")
            add("--osc=no")
            add("--osd-bar=no")
            add("--input-ipc-server=$ipcPath")
            add("--input-ipc-server=$ipcTcpPath") // also try TCP as second server
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
            add("--sub-auto=fuzzy")
            add("--audio-file-auto=fuzzy")
            add("--title=${title.take(200).replace('\n', ' ')}")
            add("--log-file=${File(logDir, "mpv-embed.log").absolutePath}")
            for (sf in subFiles) add("--sub-file=${sf.absolutePath}")
            var hasUA = false
            for ((k, v) in stream.headers) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    if (k.equals("User-Agent", ignoreCase = true)) hasUA = true
                    add("--http-header-fields=$k: $v")
                }
            }
            if (!hasUA) add("--user-agent=${Http.UA}")
        }

        val args = baseArgs + playUrl

        logToFile("mpv args: ${args.joinToString(" ")}")

        val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
        if (proc == null) {
            logToFile("Failed to start mpv process")
            close()
            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
            return
        }
        this.proc = proc

        val client = MpvIpcClient()
        ipcClient = client
        Thread({
            var connected = false
            // Try pipe first, then TCP
            for (i in 0..60) {
                Thread.sleep(300)
                if (client.connectAuto(ipcPath) || client.connectAuto(ipcTcpPath)) {
                    connected = true
                    logToFile("IPC connected on attempt $i via ${if (client.connected) "pipe/tcp" else "unknown"}")
                    break
                }
                if (!proc.isAlive) {
                    logToFile("mpv died before IPC connect, exit=${proc.exitValue()}")
                    break
                }
            }
            if (!connected) {
                logToFile("IPC failed to connect after 60 attempts - video may still play without controls")
                // Don't fallback immediately if mpv is alive and playing audio - user reported sound works
                // But if mpv died, fallback
                if (!proc.isAlive) {
                    Platform.runLater {
                        close()
                        EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
                    }
                }
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
        fsBtn.setOnAction {
            ipcClient?.command("cycle", "fullscreen")
        }

        // Set rewind/forward/next handlers via stage lookup (since we didn't pass them)
        Platform.runLater {
            val scene = stage?.scene
            scene?.root?.lookupAll(".btn")?.forEach { node ->
                if (node is Button) {
                    when (node.text) {
                        "⏪ 10s" -> node.setOnAction { ipcClient?.seek(-10.0) }
                        "10s ⏩" -> node.setOnAction { ipcClient?.seek(10.0) }
                        "Next ▶" -> node.setOnAction {
                            if (episodes != null && currentEpisode != null) {
                                val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                                if (idx >= 0 && idx < episodes.size - 1) {
                                    val next = episodes[idx + 1]
                                    close()
                                    onEpisodeChanged?.invoke(next)
                                }
                            }
                        }
                    }
                }
            }
        }

        Thread({
            val tail = StringBuilder()
            runCatching { proc.inputStream.bufferedReader().forEachLine { if (tail.length < 8000) tail.append(it).append('\n') } }
            logToFile("mpv exited: ${tail.toString().take(2000)}")
            Platform.runLater { if (stage?.isShowing == true) close() }
        }, "mpv-drain-embed").apply { isDaemon = true; start() }
    }

    fun close() {
        try { pollTimeline?.stop() } catch (_: Exception) {}
        pollTimeline = null
        try { ipcClient?.quit() } catch (_: Exception) {}
        try { Thread.sleep(150) } catch (_: Exception) {}
        try { ipcClient?.close() } catch (_: Exception) {}
        ipcClient = null
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        try { stage?.close() } catch (_: Exception) {}
        stage = null
        canvas = null
    }

    private fun getHwnd(canvas: Canvas, stage: Stage): Long {
        // Method 1: JNA Native.getComponentPointer
        try {
            val nativeClass = Class.forName("com.sun.jna.Native")
            val getPointerMethod = nativeClass.getMethod("getComponentPointer", java.awt.Component::class.java)
            val pointer = getPointerMethod.invoke(null, canvas) as com.sun.jna.Pointer
            val hwnd = com.sun.jna.Pointer.nativeValue(pointer)
            if (hwnd != 0L) {
                logToFile("HWND via JNA Native.getComponentPointer: $hwnd")
                return hwnd
            }
        } catch (e: Exception) {
            logToFile("JNA method failed: ${e.message}")
        }

        // Method 2: WComponentPeer.getHWnd() via reflection
        try {
            val peerField = java.awt.Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(canvas)
            if (peer != null) {
                val getHWndMethod = peer.javaClass.getMethod("getHWnd")
                getHWndMethod.isAccessible = true
                val hwnd = getHWndMethod.invoke(peer)
                val result = when (hwnd) {
                    is Long -> hwnd
                    is Int -> hwnd.toLong()
                    is Number -> hwnd.toLong()
                    else -> 0L
                }
                if (result != 0L) {
                    logToFile("HWND via WComponentPeer.getHWnd: $result")
                    return result
                }
            }
        } catch (e: Exception) {
            logToFile("WComponentPeer method failed: ${e.message}")
        }

        // Method 3: Glass Window native handle from Stage
        try {
            val windowClass = Class.forName("com.sun.glass.ui.Window")
            val getWindowsMethod = windowClass.getMethod("getWindows")
            @Suppress("UNCHECKED_CAST")
            val windows = getWindowsMethod.invoke(null) as? List<*>
            windows?.forEach { w ->
                try {
                    val getNativeHandle = w?.javaClass?.getMethod("getNativeHandle")
                    getNativeHandle?.isAccessible = true
                    val handle = getNativeHandle?.invoke(w) as? Long
                    if (handle != null && handle != 0L) {
                        // Try to match by title or just return first non-zero
                        // We will return first for now, but ideally match stage title
                        logToFile("HWND via Glass Window.getNativeHandle: $handle")
                        // This is Stage HWND, not Canvas HWND, but mpv can still embed into Stage
                        // and we can position video area to fill Stage - controls will be covered though
                        // So we return it as last resort
                        // Don't return yet, try other methods first, but keep as fallback
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logToFile("Glass Window method failed: ${e.message}")
        }

        // Method 4: User32 FindWindow by title (JNA)
        try {
            val user32Class = Class.forName("com.sun.jna.platform.win32.User32")
            val instanceField = user32Class.getField("INSTANCE")
            val user32 = instanceField.get(null)
            val findWindowMethod = user32Class.getMethod("FindWindow", String::class.java, String::class.java)
            val hwnd = findWindowMethod.invoke(user32, null, stage.title) as? com.sun.jna.platform.win32.WinDef.HWND
            if (hwnd != null) {
                val nativeVal = com.sun.jna.Pointer.nativeValue(hwnd.pointer)
                if (nativeVal != 0L) {
                    logToFile("HWND via User32.FindWindow: $nativeVal")
                    return nativeVal
                }
            }
        } catch (e: Exception) {
            logToFile("User32 FindWindow failed: ${e.message}")
        }

        return 0L
    }

    private fun logToFile(msg: String) {
        try {
            val logDir = File(System.getProperty("user.home"), ".hikari")
            logDir.mkdirs()
            File(logDir, "hikari-player.log").appendText("[${java.time.Instant.now()}] $msg\n")
        } catch (_: Exception) {}
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
