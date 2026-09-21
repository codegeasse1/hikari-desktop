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

    /** mpv's IPC endpoint for the current launch. On Windows this is a named
     *  pipe name, elsewhere a Unix socket path — [ipcArg] turns it into the
     *  `--input-ipc-server` value mpv expects. */
    @Volatile
    private var ipcTarget: String? = null

    /** True when [ipcTarget] names a Windows named pipe rather than a socket. */
    @Volatile
    private var ipcIsPipe = false

    private val ipcSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** The IPC channel to the running mpv, when one was established. */
    @Volatile
    private var ipc: MpvIpc? = null

    /** Invoked when the file ends, so the caller can start the next episode. */
    @Volatile
    private var onEnded: (() -> Unit)? = null

    /** Invoked with (positionMs, durationMs) as playback advances. */
    @Volatile
    private var onPosition: ((Long, Long) -> Unit)? = null

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

    fun play(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)? = null,
        next: (() -> Unit)? = null,
        position: ((Long, Long) -> Unit)? = null,
    ) {
        onEnded = next
        onPosition = position
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

    /**
     * Plays a file already on this machine — a finished download. Neither the
     * HLS relay nor the stream probe applies here: there is no network request
     * to make, the path is ours, and routing a local playlist through the relay
     * would hand OkHttp a `file:` URL it cannot fetch.
     */
    fun playFile(title: String, path: String) {
        onEnded = null
        onPosition = null
        Fx.run {
            val file = File(path)
            if (!file.exists()) {
                showBrowserFallback(title, path, "That downloaded file is no longer on disk — it may have been deleted or moved.")
                return@run
            }
            val mpv = findMpv()
            if (mpv == null) {
                showBrowserFallback(title, path, "The video player (mpv) wasn't found next to the app — re-download the latest release.")
                return@run
            }
            ipcTarget = openIpcEndpoint()
            // The app's own player window comes up first: it is what the user
            // sees, and its video surface is the window mpv is told to render
            // into (--wid below). Null — not Windows, no JNA, no handle — keeps
            // mpv's own window, which is still driven by the same controls.
            val surface = PlayerWindow.open(
                title = title,
                hasNext = false,
                next = null,
                position = null,
                closed = { stopPlayback() },
            )
            PlayerWindow.setStatus("Starting the player…", busy = true)
            val args = buildList {
                add(mpv.absolutePath)
                add("--force-window=yes")
                add("--no-ytdl")
                add("--no-osc")
                add("--no-config")
                add("--title=" + title.take(200).replace('\n', ' '))
                surface?.let { add("--wid=$it") }
                ipcArg()?.let { add(it) }
                add(file.absolutePath)
            }
            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, path, "Couldn't launch the video player.")
                return@run
            }
            proc?.let { runCatching { it.destroy() } }
            proc = p
            dialogShown = false
            attachPlayerWindow(title)
            Thread(
                {
                    runCatching { p.inputStream.bufferedReader().forEachLine { } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (code == 0 && !p.isAlive && proc === p) {
                        // The file ended (or the user quit mpv): the app's own
                        // player window goes with the player, rather than being
                        // left behind as a window controlling nothing.
                        Fx.run { if (proc === p) stopPlayback() }
                    } else if (code != 0 && !p.isAlive && proc === p) {
                        Fx.run {
                            if (dialogShown) return@run
                            dialogShown = true
                            showBrowserFallback(
                                title, path,
                                "The player closed with an error while opening the downloaded file.",
                            )
                        }
                    }
                },
                "hikari-mpv-local",
            ).apply { isDaemon = true; start() }
        }
    }

    private fun launchMpv(title: String, stream: StreamSource, refresh: (() -> StreamSource?)?, attemptsLeft: Int) {
        Fx.run {
            ipcTarget = openIpcEndpoint()
            val mpv = findMpv()
            if (mpv == null) {
                val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
                showBrowserFallback(title, url,
                    "The video player (mpv) wasn't found next to the app — re-download the latest release.")
                return@run
            }
            val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
            val signed = isSignedStreamUrl(url)
            // The app's own player window comes up FIRST: it is the window the
            // user sees, and the window mpv is told to render its video into
            // (`--wid`). It replaces the old behaviour of spawning mpv's plain
            // window next to a separate control strip. A null surface means the
            // embed isn't possible here and mpv opens its own window as before.
            val surface = PlayerWindow.open(
                title = title,
                hasNext = onEnded != null,
                next = onEnded,
                position = onPosition,
                closed = { stopPlayback() },
            )
            PlayerWindow.setStatus("Starting the player…", busy = true)
            if (surface == null) {
                PlayerWindow.note(
                    "Embedded video isn't available on this machine, so the video plays in the player's own window.",
                )
            }
            // Debug: record the EXACT characters of the provider URL and the
            // playable URL, so a failed stream always leaves a real reason in
            // .hikari/hikari-player.log (e.g. a lookalike separator that the
            // URL fixer missed).
            val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
            // Play through the app's own HTTP stack (HlsRelay): the CDNs that
            // serve these streams (chaturbate's mmcdn, myspacecat, …) 403 the
            // player's direct connections — single-use signed tokens and TLS
            // fingerprint blocking — while the app's OkHttp fetches the same
            // URLs fine. The relay also rewrites chaturbate's backslash
            // root-relative playlist lines into proper URLs.
            val playUrl = HlsRelay.urlFor(url, stream.headers)
            File(logDir, "hikari-player.log").appendText(
                "[" + java.time.Instant.now() + "] raw=" + debugEscaped(stream.url) +
                    "\n  san=" + debugEscaped(url) +
                    "\n  ply=" + debugEscaped(playUrl) +
                    "\n  wid=" + (surface?.toString() ?: "none") + "\n"
            )
            val args = buildList {
                add(mpv.absolutePath)
                add("--force-window=yes")
                // No youtube-dl hook: for direct HLS/MP4 URLs it fires a SECOND,
                // header-less probe (no Referer/Cookie) that 403s on protected
                // CDNs and only adds confusing [ytdl_hook] errors to the dialog.
                add("--no-ytdl")
                // The app draws its own transport, so mpv's on-screen controller
                // would only fight it for the bottom of the video.
                add("--no-osc")
                add("--title=" + title.take(200).replace('\n', ' '))
                val logDir2 = logDir
                add("--log-file=${File(logDir2, "mpv.log").absolutePath}")
                // Render INTO the app's player window (see WinShell): this is
                // what makes the player in-app instead of a second window.
                surface?.let { add("--wid=$it") }
                ipcArg()?.let { add(it) }
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
                add(playUrl)
            }
            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, url, "Couldn't launch the video player. Open it in your browser instead?")
                return@run
            }
            proc?.let { runCatching { it.destroy() } }
            proc = p
            attachPlayerWindow(title)
            // Only the FIRST explanation per launch shows: the mpv error path and
            // the stream probe both try to explain a dead player, never both.
            dialogShown = false
            val startedAt = System.currentTimeMillis()
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 4000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (code == 0 && !p.isAlive) {
                        // The episode finished with no error and no "next": mpv
                        // is gone, so the app's player window goes too.
                        Fx.run { if (proc === p) stopPlayback() }
                    }
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
                                    com.hikari.app.net.Http.StreamProbe.HTML ->
                                        // A web page usually means the CDN/embed host served an
                                        // anti-bot or JS-built player page — which plays fine in a
                                        // real browser. Render it in the embedded WebEngine, capture
                                        // the ACTUAL media URL the page produces, and hand that to
                                        // mpv. Only give up (with the browser path) if the page
                                        // yields nothing playable.
                                        resolveAndPlay(title, url, stream, refresh, attemptsLeft)
                                    com.hikari.app.net.Http.StreamProbe.JSON ->
                                        resolveAndPlay(title, url, stream, refresh, attemptsLeft)
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

    /**
     * The source URL served a web page (or JSON) — an embed/anti-bot host
     * that plays fine in a browser but dies in the direct player. This runs off
     * the FX thread: render the page in the embedded WebEngine so its JS runs,
     * capture the real media URL it produces, and relaunch mpv with THAT. If
     * the page yields nothing playable, fall back to the browser dialog.
     */
    private fun resolveAndPlay(title: String, url: String, stream: StreamSource, refresh: (() -> StreamSource?)?, attemptsLeft: Int) {
        Thread(
            {
                val resolved = runCatching { desktop.web.FxWebView.resolveStreamUrl(url, 30_000) }.getOrNull()
                Fx.run {
                    if (dialogShown) return@run
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

    /** Merges any cookies the player page set into the stream headers, so the
     *  player re-fetching the resolved manifest sends the session cookie the
     *  CDN issued to the browser. */
    private fun mergeHeaders(base: Map<String, String>, cookie: String): Map<String, String> {
        val merged = HashMap(base)
        if (cookie.isNotBlank()) {
            val existing = merged["Cookie"].orEmpty()
            merged["Cookie"] = if (existing.isBlank()) cookie else "$existing; $cookie"
        }
        return merged
    }

    /** The bundled player, located by [Mpv] (shared with the download engine's
     *  remux step, which needs the same binary). */
    private fun findMpv(): File? = Mpv.exe()

    /** For the debug log: show a string with every non-ASCII character as a
     *  \\uXXXX escape, so a lookalike separator (e.g. a yen sign instead of a
     *  backslash) is actually visible in the log. */
    private fun debugEscaped(s: String): String = buildString {
        for (c in s) {
            val cp = c.code
            if (cp < 32 || cp > 126) append("\\u").append(cp.toString(16).padStart(4, '0'))
            else append(c)
        }
    }

    private fun showBrowserFallback(title: String, url: String, reason: String? = null, tryAnyway: (() -> Unit)? = null, retry: (() -> Unit)? = null) {
        // When the app's own player window is up, the explanation belongs IN it
        // (over the video area, which is what the user is looking at) rather
        // than in a second dialog on top of it.
        val text = reason?.takeIf { it.isNotBlank() } ?: "Open it in your browser instead?"
        if (PlayerWindow.showFailure(text, url, retry = retry, tryAnyway = tryAnyway)) return
        val stage = Stage()
        stage.title = title
        val label = Label(text).apply {
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

    /** True on Windows, where mpv's IPC transport is a named pipe. */
    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Reserves an endpoint for the next mpv launch. A fresh name per launch, so
     * a stale pipe/socket left behind by a crashed player can never be mistaken
     * for the new one's.
     */
    private fun openIpcEndpoint(): String {
        val n = ipcSeq.incrementAndGet()
        return if (isWindows()) {
            ipcIsPipe = true
            "hikari-mpv-" + ProcessHandle.current().pid() + "-" + n
        } else {
            ipcIsPipe = false
            val dir = File(System.getProperty("java.io.tmpdir"), "hikari-player").apply { mkdirs() }
            val sock = File(dir, "mpv-$n.sock")
            runCatching { sock.delete() }
            sock.absolutePath
        }
    }

    /** The `--input-ipc-server=…` argument for the current endpoint, or null
     *  when this launch has no IPC (in which case mpv just plays normally). */
    private fun ipcArg(): String? {
        val target = ipcTarget ?: return null
        return if (ipcIsPipe) "--input-ipc-server=\\\\.\\pipe\\$target"
        else "--input-ipc-server=$target"
    }

    /**
     * Connects the app's player window to a freshly launched mpv. Off the FX
     * thread (mpv creates its pipe a moment after launch). When the endpoint
     * never appears the window stays up and says so — the video keeps playing,
     * only the controls are unavailable, which is a far better outcome than a
     * window that silently shows "Connecting…" forever.
     */
    private fun attachPlayerWindow(title: String) {
        val target = ipcTarget ?: return
        val isPipe = ipcIsPipe
        val mine = proc ?: return
        Thread(
            {
                val client = MpvIpc(target, isPipe)
                if (!client.connect(6_000L)) {
                    runCatching { client.close() }
                    Fx.run {
                        if (proc === mine) {
                            PlayerWindow.setStatus(
                                "Couldn't reach the player's control channel — video plays, controls are unavailable.",
                                isError = true,
                            )
                        }
                    }
                    return@Thread
                }
                // A newer launch (or a stop) happened while we were connecting.
                if (proc !== mine) {
                    runCatching { client.close() }
                    return@Thread
                }
                ipc = client
                Fx.run {
                    if (proc !== mine) {
                        runCatching { client.close() }
                        ipc = null
                        return@run
                    }
                    PlayerWindow.attachIpc(client)
                }
            },
            "hikari-mpv-attach",
        ).apply { isDaemon = true; start() }
    }

    /** Tears the running player down: closes the IPC channel (which closes the
     *  app's player window) and kills mpv. Runs on the FX thread. */
    private fun stopPlayback() {
        Fx.run {
            runCatching { ipc?.close() }
            ipc = null
            runCatching { PlayerWindow.closeAll() }
            runCatching { proc?.destroy() }
            proc = null
            ipcTarget = null
        }
    }

    fun closeAll() {
        stopPlayback()
    }
}
