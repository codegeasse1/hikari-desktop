package desktop.player

import com.hikari.app.data.StreamSource
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
import desktop.ui.Theme

/**
 * Desktop player. Video playback is done by mpv — the same engine media
 * players use — because JavaFX's built-in media stack refuses most HLS/CDN
 * streams (jfxmedia MediaException, black window). mpv ships INSIDE the
 * release (`app/mpv/mpv.exe`, bundled by CI) and plays HLS/MP4/MKV natively,
 * sending the stream's Referer/Cookie/UA headers itself.
 *
 * If mpv is missing, DASH/torrent/YouTube streams are not playable, the user
 * gets a clear dialog with an "Open in browser" path — never a silent black
 * window.
 */
object DesktopPlayer {

    private var proc: Process? = null

    fun play(title: String, stream: StreamSource) {
        Fx.run {
            if (stream.externalUrl) {
                DesktopUi.open(stream.url)
                return@run
            }
            if (stream.ytId != null) {
                DesktopUi.open("https://www.youtube.com/watch?v=${stream.ytId}")
                return@run
            }
            if (stream.isMpd || stream.url.endsWith(".mpd")) {
                showBrowserFallback(title, stream.url, "This stream uses DASH, which the bundled player can't play yet.")
                return@run
            }
            if (stream.isTorrent || stream.infoHash != null) {
                showBrowserFallback(title, stream.url.ifBlank { "magnet stream (infoHash ${stream.infoHash})" },
                    "This is a torrent stream, which the bundled player can't play yet.")
                return@run
            }
            val mpv = findMpv()
            if (mpv == null) {
                showBrowserFallback(title, stream.url,
                    "The video player (mpv) wasn't found next to the app — re-download the latest release.")
                return@run
            }
            val args = buildList {
                add(mpv.absolutePath)
                add("--force-window=yes")
                add("--title=" + title.take(200).replace('\n', ' '))
                // Route mpv's own HTTP(S) through the app's loopback proxy so
                // HLS/CDN domains blocked by the OS resolver still resolve (the
                // proxy uses the same DoH-first DNS as the rest of the app).
                // --http-proxy (NOT --https-proxy — the bundled build has no
                // such option and exits with an error if passed) makes ffmpeg
                // send https:// streams through the same CONNECT proxy.
                val proxyPort = LocalProxy.start()
                add("--http-proxy=127.0.0.1:$proxyPort")
                // No --no-terminal: stderr is drained for the error dialog, and
                // mirrored to a log file so a failed stream shows a real reason.
                val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
                add("--log-file=${File(logDir, "mpv.log").absolutePath}")
                for ((k, v) in stream.headers) {
                    if (k.isNotBlank() && v.isNotBlank()) add("--http-header-fields=$k: $v")
                }
                add(stream.url)
            }
            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, stream.url, "Couldn't launch the video player. Open it in your browser instead?")
                return@run
            }
            proc?.let { runCatching { it.destroy() } }
            proc = p
            val startedAt = System.currentTimeMillis()
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 4000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (code != 0 && !p.isAlive) {
                        val early = System.currentTimeMillis() - startedAt < 4_000
                        if (early) {
                            // Show the REAL last mpv lines (the log-file mirrors
                            // stderr, so the most useful lines are at the end).
                            val err = tail.toString().trim().lineSequence()
                                .filter { it.isNotBlank() }
                                .takeLast(10)
                                .joinToString("\n")
                            Fx.run {
                                showBrowserFallback(
                                    title, stream.url,
                                    buildString {
                                        append("The player closed with an error")
                                        if (err.isNotBlank()) append(":\n").append(err.take(1600))
                                        append("\n\nOpen it in your browser instead?")
                                    },
                                )
                            }
                        }
                    }
                },
                "hikari-mpv-drain",
            ).apply { isDaemon = true; start() }
        }
    }

    /** `app/mpv/mpv.exe` inside the installed app (jpackage layout), with a
     *  couple of fallbacks for running from an IDE / loose jar. */
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

    private fun showBrowserFallback(title: String, url: String, reason: String? = null) {
        val stage = Stage()
        stage.title = title
        val label = Label(reason?.takeIf { it.isNotBlank() } ?: "Open it in your browser instead?").apply {
            isWrapText = true
        }
        val openBtn = Button("Open in browser").apply {
            setOnAction {
                DesktopUi.open(url)
                stage.close()
            }
        }
        val closeBtn = Button("Close").apply { setOnAction { stage.close() } }
        val box = VBox(14.0, label, HBox(10.0, openBtn, closeBtn)).apply {
            alignment = Pos.CENTER
            padding = Insets(24.0)
        }
        stage.scene = Theme.style(Scene(box, 640.0, 220.0))
        stage.show()
    }

    fun closeAll() {
        Fx.run {
            proc?.let { runCatching { it.destroy() } }
            proc = null
        }
    }
}
