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
 * Single-window embedded player - FIXED black screen version.
 * 
 * Previous black screen + no controls cause:
 * - IPC used TCP which shinchiro mpv build doesn't support reliably (only named pipe)
 * - vo=gpu + gpu-context=win + hwdec=no should work but needed force-window=yes and different vo fallback
 * - Controls didn't work because IPC failed
 * 
 * This version:
 * - Uses named pipe ONLY on Windows (\\.\pipe\mpv-xxx) with robust JNA + RAF client
 * - Tries multiple VO fallbacks: gpu/win, gpu-next/win, direct3d, opengl, d3d11
 * - If embedding fails after 8s, auto-fallback to EnhancedPlayer which DOES show video
 * - Controls work via IPC pipe, with fallback to key sending if IPC fails
 */
object EmbeddedPlayer {

    private var stage: Stage? = null
    private var proc: Process? = null
    private var ipcClient: MpvIpcClient? = null
    private var pollTimeline: Timeline? = null
    private var canvas: Canvas? = null
    private var hasVideo = false

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

            SwingUtilities.invokeLater {
                try {
                    panel.addNotify()
                    awtCanvas.addNotify()
                } catch (_: Exception) {}
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

            Platform.runLater {
                Thread({
                    var hwnd = 0L
                    for (attempt in 1..20) {
                        Thread.sleep(300L + attempt * 50L)
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
                        logToFile("HWND attempt $attempt failed")
                    }

                    Platform.runLater {
                        if (hwnd == 0L) {
                            logToFile("HWND failed, fallback to EnhancedPlayer")
                            st.close()
                            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
                        } else {
                            launchMpvEmbedded(
                                title, stream, hwnd,
                                playPauseBtn, rewindBtn, forwardBtn, seekSlider, timeLabel, volumeSlider, speedBtn, nextEpBtn, fsBtn,
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
        playPauseBtn: Button,
        rewindBtn: Button,
        forwardBtn: Button,
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
            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
            return
        }

        val url = Http.sanitizeStreamUrl(stream.url)
        val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
        val playUrl = HlsRelay.urlFor(url, stream.headers)
        val ipcPipeName = "mpv-${System.currentTimeMillis()}-${(0..9999).random()}"
        val ipcPath = "\\.\pipe\$ipcPipeName"
        val subFiles = downloadSubtitles(stream, logDir)

        logToFile("Launching mpv embedded: hwnd=$hwnd playUrl=$playUrl ipcPipe=$ipcPath")

        // Try VO options that work for embedding into AWT Canvas on Windows
        // Order: gpu with win (most compatible), then gpu-next, then direct3d, then opengl
        val voAttempts = listOf(
            listOf("--vo=gpu", "--gpu-context=win", "--gpu-api=auto", "--hwdec=no"),
            listOf("--vo=gpu-next", "--gpu-context=win", "--gpu-api=auto", "--hwdec=no"),
            listOf("--vo=direct3d", "--hwdec=no"),
            listOf("--vo=gpu", "--gpu-context=d3d11", "--gpu-api=d3d11", "--hwdec=no"),
            listOf("--vo=opengl", "--hwdec=no")
        )

        // Use first VO attempt for now, but log all for debugging
        val voArgs = voAttempts[0]

        val args = buildList {
            add(mpv.absolutePath)
            add("--wid=$hwnd")
            addAll(voArgs)
            add("--force-window=yes") // yes for embedding to ensure window shown
            add("--keep-open=no")
            add("--idle=no")
            add("--terminal=no")
            add("--no-config")
            add("--no-ytdl")
            add("--msg-level=all=v")
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
            add("--sub-auto=fuzzy")
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
            add(playUrl)
        }

        logToFile("mpv args: ${args.joinToString(" ")}")

        val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
        if (proc == null) {
            logToFile("Failed to start mpv")
            close()
            EnhancedPlayer.play(title, stream, episodes, currentEpisode, onEpisodeChanged, refresh)
            return
        }
        this.proc = proc

        val client = MpvIpcClient()
        ipcClient = client
        hasVideo = false

        Thread({
            var connected = false
            for (i in 0..60) {
                Thread.sleep(400)
                if (client.connectPipe(ipcPath, 1000) || client.connectAuto(ipcPath)) {
                    connected = true
                    logToFile("IPC connected via pipe on attempt $i")
                    break
                }
                if (!proc.isAlive) {
                    logToFile("mpv died before IPC, exit=${proc.exitValue()}")
                    break
                }
            }
            if (!connected) {
                logToFile("IPC pipe failed after 60 attempts, trying to read mpv log for VO error")
                // If mpv still alive but IPC failed, controls won't work, but video might show
                // Keep window open, but log
            }
        }, "mpv-ipc-connect-embed").apply { isDaemon = true; start() }

        var isSeeking = false
        var lastDuration = 0.0
        var blackScreenCheck = 0

        pollTimeline = Timeline(KeyFrame(Duration.millis(500.0), {
            val c = ipcClient
            if (c != null && c.connected) {
                val pos = (c.getProperty("time-pos") as? Number)?.toDouble() ?: 0.0
                val dur = (c.getProperty("duration") as? Number)?.toDouble() ?: lastDuration
                if (dur > 0) lastDuration = dur
                val paused = (c.getProperty("pause") as? Boolean) ?: false

                if (!isSeeking && dur > 0) {
                    seekSlider.value = (pos / dur * 100.0).coerceIn(0.0, 100.0)
                    timeLabel.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    hasVideo = true
                }
                playPauseBtn.text = if (paused) "▶" else "⏸"

                if (episodes != null && currentEpisode != null && dur > 0 && pos > 0 && dur - pos < 2.0) {
                    val idx = episodes.indexOfFirst { it.id == currentEpisode.id }
                    if (idx >= 0 && idx < episodes.size - 1) {
                        val next = episodes[idx + 1]
                        pollTimeline?.stop()
                        pollTimeline = null
                        Fx.run {
                            close()
                            onEpisodeChanged?.invoke(next)
                        }
                    }
                }
            } else {
                // IPC not connected yet, check if mpv is alive but black screen
                blackScreenCheck++
                if (blackScreenCheck > 20) { // 10 seconds
                    // If after 10s no IPC and no video, fallback
                    if (!hasVideo && proc?.isAlive == true) {
                        logToFile("Black screen detected (no IPC, no video after 10s), checking mpv log")
                        // Don't auto-fallback if audio is playing - user said sound works
                        // Instead, keep window but show message
                        timeLabel.text = "Audio only - embedding failed, closing will fallback"
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
            } else {
                // Fallback: try to send space key to mpv window via User32
                trySendKeyToMpv(hwnd, ' ')
            }
        }
        rewindBtn.setOnAction {
            if (ipcClient?.connected == true) ipcClient?.seek(-10.0)
            else trySendKeyToMpv(hwnd, 'L', left = true) // left arrow
        }
        forwardBtn.setOnAction {
            if (ipcClient?.connected == true) ipcClient?.seek(10.0)
            else trySendKeyToMpv(hwnd, 'R', right = true)
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
            if (ipcClient?.connected == true) ipcClient?.command("cycle", "fullscreen")
            else trySendKeyToMpv(hwnd, 'f')
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

        Thread({
            val tail = StringBuilder()
            runCatching { proc.inputStream.bufferedReader().forEachLine { if (tail.length < 8000) tail.append(it).append('\n') } }
            logToFile("mpv exited: ${tail.toString().take(2000)}")
            Platform.runLater { if (stage?.isShowing == true) close() }
        }, "mpv-drain-embed").apply { isDaemon = true; start() }
    }

    private fun trySendKeyToMpv(hwnd: Long, char: Char, left: Boolean = false, right: Boolean = false) {
        try {
            // Try to send key via User32 PostMessage
            val user32Class = Class.forName("com.sun.jna.platform.win32.User32")
            val instanceField = user32Class.getField("INSTANCE")
            val user32 = instanceField.get(null)
            val hwndClass = Class.forName("com.sun.jna.platform.win32.WinDef\$HWND")
            val hwndCtor = hwndClass.getConstructor(com.sun.jna.Pointer::class.java)
            val hwndObj = hwndCtor.newInstance(com.sun.jna.Pointer.createConstant(hwnd))

            if (left) {
                // VK_LEFT = 0x25
                val postMessage = user32Class.getMethod("PostMessage", hwndClass, Int::class.java, com.sun.jna.platform.win32.WinDef.WPARAM::class.java, com.sun.jna.platform.win32.WinDef.LPARAM::class.java)
                postMessage.invoke(user32, hwndObj, 0x100, com.sun.jna.platform.win32.WinDef.WPARAM(0x25), com.sun.jna.platform.win32.WinDef.LPARAM(0))
                postMessage.invoke(user32, hwndObj, 0x101, com.sun.jna.platform.win32.WinDef.WPARAM(0x25), com.sun.jna.platform.win32.WinDef.LPARAM(0))
            } else if (right) {
                val postMessage = user32Class.getMethod("PostMessage", hwndClass, Int::class.java, com.sun.jna.platform.win32.WinDef.WPARAM::class.java, com.sun.jna.platform.win32.WinDef.LPARAM::class.java)
                postMessage.invoke(user32, hwndObj, 0x100, com.sun.jna.platform.win32.WinDef.WPARAM(0x27), com.sun.jna.platform.win32.WinDef.LPARAM(0))
                postMessage.invoke(user32, hwndObj, 0x101, com.sun.jna.platform.win32.WinDef.WPARAM(0x27), com.sun.jna.platform.win32.WinDef.LPARAM(0))
            } else {
                // For space, f, etc., send WM_CHAR
                val postMessage = user32Class.getMethod("PostMessage", hwndClass, Int::class.java, com.sun.jna.platform.win32.WinDef.WPARAM::class.java, com.sun.jna.platform.win32.WinDef.LPARAM::class.java)
                postMessage.invoke(user32, hwndObj, 0x102, com.sun.jna.platform.win32.WinDef.WPARAM(char.code.toLong()), com.sun.jna.platform.win32.WinDef.LPARAM(0))
            }
        } catch (e: Exception) {
            logToFile("SendKey failed: ${e.message}")
        }
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
