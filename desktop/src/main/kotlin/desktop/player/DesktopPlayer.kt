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

    /** True once the current launch has shown its failure dialog (mpv error or
     *  stream-probe fallback), so the two can't double-popup. Reset per launch. */
    @Volatile private var dialogShown = false

    /** Signed, short-lived stream URLs (chaturbate's `mmcdn.com`/`edge-hls`
     *  LL-HLS links) — their token is single-use and expires in seconds, so:
     *  1) the pre-flight probe must NOT request them (it would burn the token
     *     and the follow-up mpv request would 403), and
     *  2) a 403 means the link expired → relaunch with a freshly-fetched URL. */
    private fun isSignedStreamUrl(url: String): Boolean =
        url.contains("/v1/edge/streams/") || url.contains("mmcdn.com") ||
            url.contains("edge-hls.chaturbate.com")

    fun play(title: String, stream: StreamSource, refresh: (() -> StreamSource?)? = null) {
        // Sanitize here too so a malformed URL from ANY provider (chaturbate's
        // root-relative escaped HLS path, stray quotes, JSON escapes) can't
        // reach mpv or the browser as garbage.
        val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
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
        // mpv has no handler for browser-only blob:/data: URLs.
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
            val mpv = findMpv()
            if (mpv == null) {
                val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
                showBrowserFallback(title, url,
                    "The video player (mpv) wasn't found next to the app — re-download the latest release.")
                return@run
            }
            val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
            val signed = isSignedStreamUrl(url)
            val args = buildList {
                add(mpv.absolutePath)
                add("--force-window=yes")
                // No youtube-dl hook: for direct HLS/MP4 URLs it fires a SECOND,
                // header-less probe (no Referer/Cookie) that 403s on protected
                // CDNs and only adds confusing [ytdl_hook] errors to the dialog.
                add("--no-ytdl")
                add("--title=" + title.take(200).replace('\n', ' '))
                // Route mpv's own HTTP(S) through the app's loopback proxy so
                // HLS/CDN domains blocked by the OS resolver still resolve (the
                // proxy uses the same DoH-first DNS as the rest of the app).
                val proxyPort = LocalProxy.start()
                add("--http-proxy=127.0.0.1:$proxyPort")
                val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
                add("--log-file=${File(logDir, "mpv.log").absolutePath}")
                // Providers may ship stream headers (Referer/Cookie/UA) their
                // CDN validates — hand them straight to mpv. If no User-Agent is
                // among them, force a desktop-browser one: mpv's default
                // "mpv/x.y.z" UA gets 403'd by token-protected CDNs.
                var hasUA = false
                for ((k, v) in stream.headers) {
                    if (k.isNotBlank() && v.isNotBlank()) {
                        if (k.equals("User-Agent", ignoreCase = true)) hasUA = true
                        add("--http-header-fields=$k: $v")
                    }
                }
                if (!hasUA) add("--user-agent=" + com.hikari.app.net.Http.UA)
                add(url)
            }
            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, url, "Couldn't launch the video player. Open it in your browser instead?")
                return@run
            }
            proc?.let { runCatching { it.destroy() } }
            proc = p
            // Only the FIRST explanation per launch shows: the mpv error path and
            // the stream probe both try to explain a dead player, never both.
            dialogShown = false
            val startedAt = System.currentTimeMillis()
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 4000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (code != 0 && !p.isAlive) {
                        val tailText = tail.toString()
                        val forbidden = tailText.contains("403")
                        // Signed links (chaturbate) 403 the moment their token is
                        // spent — grab a FRESH url from the provider and relaunch
                        // instead of showing an error for a link that was fine.
                        if (attemptsLeft > 0 && refresh != null && (signed || forbidden)) {
                            val fresh = runCatching { refresh() }.getOrNull()
                            if (fresh != null && fresh.url.isNotBlank()) {
                                Fx.run { launchMpv(title, fresh, refresh, attemptsLeft - 1) }
                                return@Thread
                            }
                        }
                        val early = System.currentTimeMillis() - startedAt < 4_000
                        if (early || forbidden) {
                            val err = tailText.trim().lineSequence()
                                .filter { it.isNotBlank() }
                                .toList()
                                .takeLast(10)
                                .joinToString("\n")
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
                                        if (err.isNotBlank()) append(":\n").append(err.take(1600))
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
            // Pre-flight probe: while mpv starts, fetch the first bytes of the
            // stream. If the server answers with a web page or JSON instead of
            // media (the real cause of mpv's "file format not supported"), kill
            // mpv before it errors and offer a clear fallback instead.
            // Signed chaturbate links are skipped — probing them consumes the
            // single-use token and turns the actual playback into a 403.
            val myProc = p
            if (!signed) {
                Thread(
                    {
                        val verdict = com.hikari.app.net.Http.probeStreamUrl(url, stream.headers)
                        if (verdict != com.hikari.app.net.Http.StreamProbe.HLS &&
                            verdict != com.hikari.app.net.Http.StreamProbe.VIDEO &&
                            verdict != com.hikari.app.net.Http.StreamProbe.UNKNOWN
                        ) {
                            Fx.run {
                                if (dialogShown || proc !== myProc || !myProc.isAlive) return@run
                                dialogShown = true
                                runCatching { myProc.destroy() }
                                when (verdict) {
                                    com.hikari.app.net.Http.StreamProbe.DASH -> showBrowserFallback(
                                        title, url,
                                        "This stream uses DASH, which the bundled player can't play yet.",
                                    )
                                    com.hikari.app.net.Http.StreamProbe.HTML -> showBrowserFallback(
                                        title, url,
                                        "That source's server returned a web page instead of a video (login or anti-bot page). Try another source, or open it in your browser.",
                                        tryAnyway = { launchMpv(title, stream, refresh, attemptsLeft) },
                                    )
                                    com.hikari.app.net.Http.StreamProbe.JSON -> showBrowserFallback(
                                        title, url,
                                        "That source's server returned JSON data instead of a video. Try another source.",
                                        tryAnyway = { launchMpv(title, stream, refresh, attemptsLeft) },
                                    )
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

    private fun showBrowserFallback(title: String, url: String, reason: String? = null, tryAnyway: (() -> Unit)? = null, retry: (() -> Unit)? = null) {
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
            proc?.let { runCatching { it.destroy() } }
            proc = null
        }
    }
}
