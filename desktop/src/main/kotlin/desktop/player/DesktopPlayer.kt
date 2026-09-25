package desktop.player

import com.hikari.app.data.StreamSource
import desktop.Build
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
 * mpv's own window is ADOPTED by the app's player layer (see [PlayerWindow] and
 * [WinShell]): its caption and taskbar button are stripped and it is glued
 * exactly over the video area, so playback happens inside the app window. mpv's
 * own on-screen controller is switched off because the app draws the one control
 * bar. mpv keeps rendering into its own window (never into a window JavaFX
 * owns), which is what makes the picture reliable: the video output is always
 * created by mpv, for mpv.
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

    /** Every source the caller offered for the title being played, plus the one
     *  that was chosen — handed to the player so its Source menu can switch
     *  servers without going back to the detail screen. */
    @Volatile
    private var sourceList: List<StreamSource> = emptyList()

    @Volatile
    private var sourceName: String = ""

    @Volatile
    private var onPickSource: ((StreamSource) -> Unit)? = null

    /** True once the current launch has shown its failure dialog (mpv error or
     *  stream-probe fallback), so the two can't double-popup. Reset per launch. */
    @Volatile private var dialogShown = false

    /** The exact command line the current player was launched with, and what it
     *  was asked to play. Kept for the player report — "which flags did it
     *  actually get" is the first question about a player that misbehaves. */
    @Volatile private var lastArgs: List<String> = emptyList()

    @Volatile private var lastTitle: String = ""

    @Volatile private var lastStreamUrl: String = ""

    /** Signed, short-lived stream URLs (chaturbate's `mmcdn.com`/`edge-hls`
     *  LL-HLS links) — their token is single-use and expires in seconds, so:
     *  1) the pre-flight probe must NOT request them (it would burn the token
     *     and the follow-up mpv request would 403), and
     *  2) a 403 means the link expired → relaunch with a freshly-fetched URL.
     *  The rule itself lives on [Http] so the "play straight away" speed race
     *  respects it too. */
    private fun isSignedStreamUrl(url: String): Boolean = com.hikari.app.net.Http.isSignedStreamUrl(url)

    fun play(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)? = null,
        next: (() -> Unit)? = null,
        position: ((Long, Long) -> Unit)? = null,
        sources: List<StreamSource> = emptyList(),
        onPickSource: ((StreamSource) -> Unit)? = null,
    ) {
        onEnded = next
        onPosition = position
        sourceList = sources.ifEmpty { listOf(stream) }
        sourceName = stream.name
        this.onPickSource = onPickSource
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
    /**
     * Hands the player's Source menu a longer server list for the playback that
     * is already running.
     *
     * The sweep that finds a title's servers returns as soon as the first one is
     * playable (playback starts in seconds) and keeps asking the remaining
     * extensions in the background, so the extra servers arrive while the user is
     * already watching. Without this, "another server" would mean going back to
     * the title and reloading it.
     */
    fun updateSources(sources: List<StreamSource>) {
        if (sources.isEmpty()) return
        sourceList = sources
        runCatching { PlayerWindow.setSources(sources) }
    }

    fun playFile(title: String, path: String) {
        onEnded = null
        onPosition = null
        sourceList = emptyList()
        sourceName = ""
        onPickSource = null
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
            runCatching { PlayerWindow.parkSurface() }
            // Park the previous stream's window FIRST (it belongs to the old mpv,
            // which is killed below): a killed process can take a moment to let go
            // of its window, and in that moment the previous picture sat behind the
            // new player — "it opened another player".
            runCatching { PlayerWindow.parkSurface() }
            // The app's own player layer comes up first: it is what the user
            // sees, and it adopts mpv's window once it exists. False — not
            // Windows, no JNA — leaves mpv's window as a window of its own.
            val glued = PlayerWindow.open(
                title = title,
                hasNext = false,
                next = null,
                position = null,
                closed = { stopPlayback() },
            )
            if (!glued) {
                PlayerWindow.note("This machine can't draw the video inside the app window, so it plays in the player's own window.")
            }
            val args = buildList {
                add(mpv.absolutePath)
                // Create the window AT PROGRAM START, not once the file has
                // finished initialising. `--force-window=yes` only creates it
                // after initialisation — which on a slow (or hung) network
                // stream never happens, so the window the app has to adopt does
                // not exist and a working player looks broken. mpv's own manual
                // warns about exactly this and its built-in [network] profile
                // uses `immediate` for the same reason.
                add("--force-window=immediate")
                // Never let mpv EXIT on its own: without this the process dies at
                // the end of a segment or on a decode error, its window vanishes,
                // and the app is left talking to a closed pipe ("the player stopped
                // reading its command pipe") over a black video area. With it, mpv
                // stays up, keeps the last frame, and the app keeps its controls —
                // the end of the file is an `eof-reached` event we handle, not a
                // process disappearing.
                add("--keep-open=yes")
                add("--no-ytdl")
                add("--no-osc")
                add("--no-config")
                // The app is the frame, and the app's keys are the shortcuts:
                // mpv must not draw a border of its own, and must not swallow
                // space/arrows/q once its window has been clicked.
                add("--no-border")
                add("--auto-window-resize=no")
                add("--no-input-default-bindings")
                add("--title=" + title.take(200).replace('\n', ' '))
                ipcArg()?.let { add(it) }
                add(file.absolutePath)
            }
            val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            if (p == null) {
                showBrowserFallback(title, path, "Couldn't launch the video player.")
                return@run
            }
            lastArgs = args
            lastTitle = title
            lastStreamUrl = path
            killPrevious(proc)
            proc = p
            dialogShown = false
            PlayerWindow.onCopyReport = { copyPlayerReport() }
            if (glued) {
                PlayerWindow.attachProcess(
                    p.pid(),
                    onFailed = { onAdoptionFailed() },
                    onAdopted = { onAdopted() },
                )
            }
            attachPlayerWindow(title)
            // If the player dies under a live window, recover instead of showing
            // a black rectangle (see PlayerWindow.onPlayerDied).
            PlayerWindow.onPlayerDied = {
                recoverFromPlayerDeath(title, StreamSource(name = title, url = path), null, 0, path)
            }
            Thread(
                {
                    runCatching { p.inputStream.bufferedReader().forEachLine { } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    // A newer launch (or a teardown) replaced this process while
                    // it was draining — its exit is not this player's business.
                    if (proc !== p) return@Thread
                    if (code == 0 && !p.isAlive) {
                        Fx.run { if (proc === p) stopPlayback() }
                    } else if (code != 0 && !p.isAlive) {
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

    private fun launchMpv(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)?,
        attemptsLeft: Int,
    ) {
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
            // The app's own player layer comes up FIRST: it is the window the
            // user sees, and it adopts mpv's window (gluing it over the video
            // area) as soon as mpv has created it. False means this machine
            // can't do that and the video stays in a window of its own.
            val glued = PlayerWindow.open(
                title = title,
                hasNext = onEnded != null,
                next = onEnded,
                position = onPosition,
                closed = { stopPlayback() },
                sources = sourceList,
                sourceName = sourceName,
                onPickSource = onPickSource,
            )
            if (!glued) {
                PlayerWindow.note(
                    "This machine can't draw the video inside the app window, so it plays in the player's own window.",
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
                    "\n  glue=" + glued + "\n"
            )
            val args = buildList {
                add(mpv.absolutePath)
                // Create the window AT PROGRAM START, not once the stream has
                // finished initialising. `--force-window=yes` creates the window
                // only AFTER initialisation — and a slow (or hung) HLS/CDN
                // stream initialises for as long as it likes, so the window the
                // app has to adopt does not exist for minutes, or ever. That is
                // the "the app says it cannot draw the video inside the app
                // window" report: a working mpv, adopted by nobody, because its
                // window was not there yet. mpv's manual warns about this and
                // its own built-in [network] profile uses `immediate`.
                add("--force-window=immediate")
                // Never let mpv EXIT on its own: without this the process dies at
                // the end of a segment or on a decode error, its window vanishes,
                // and the app is left talking to a closed pipe ("the player stopped
                // reading its command pipe") over a black video area. With it, mpv
                // stays up, keeps the last frame, and the app keeps its controls —
                // the end of the file is an `eof-reached` event we handle, not a
                // process disappearing.
                add("--keep-open=yes")
                // The user's own mpv.conf must never decide how the app's player
                // looks or renders: this is one embedded surface, not their mpv.
                add("--no-config")
                // The app supplies the frame and the shortcuts.
                add("--no-border")
                // mpv must never resize its own window when a file's dimensions
                // become known: the app owns the window's geometry ([WinShell]).
                add("--auto-window-resize=no")
                add("--no-input-default-bindings")
                // No youtube-dl hook: for direct HLS/MP4 URLs it fires a SECOND,
                // header-less probe (no Referer/Cookie) that 403s on protected
                // CDNs and only adds confusing [ytdl_hook] errors to the dialog.
                add("--no-ytdl")
                // The app draws the one control bar, so mpv's on-screen
                // controller would only fight it for the bottom of the video.
                add("--no-osc")
                add("--title=" + title.take(200).replace('\n', ' '))
                val logDir2 = logDir
                add("--log-file=${File(logDir2, "mpv.log").absolutePath}")
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
            lastArgs = args
            lastTitle = title
            lastStreamUrl = url
            killPrevious(proc)
            proc = p
            // Adopt mpv's window and glue it over the video area (see
            // PlayerWindow/WinShell). Off the FX thread: mpv creates its window
            // a moment after launch. A window that turns up late is still
            // adopted — onAdopted takes the "it is playing in a window of its
            // own" explanation back down when that happens.
            PlayerWindow.onCopyReport = { copyPlayerReport() }
            if (glued) {
                PlayerWindow.attachProcess(
                    p.pid(),
                    onFailed = { onAdoptionFailed() },
                    onAdopted = { onAdopted() },
                )
            }
            attachPlayerWindow(title)
            // If the player dies under a live window, recover instead of showing
            // a black rectangle (see PlayerWindow.onPlayerDied).
            PlayerWindow.onPlayerDied = { recoverFromPlayerDeath(title, stream, refresh, attemptsLeft, url) }
            // Only the FIRST explanation per launch shows: the mpv error path and
            // the stream probe both try to explain a dead player, never both.
            dialogShown = false
            if (glued) {
                // A stream can load and still never produce a picture (a dead
                // CDN edge, an unsupported codec, a geo-block that only bites
                // the media segments). Say so over the video area — never leave
                // a black rectangle sitting there, and never spawn a second
                // player window behind the user's back.
                Thread(
                    {
                        val startedAt = System.currentTimeMillis()
                        var saidStillTrying = false
                        val deadline = startedAt + 45_000
                        while (System.currentTimeMillis() < deadline) {
                            Thread.sleep(500)
                            if (proc !== p || !p.isAlive) return@Thread
                            if (PlayerWindow.hasVideo()) return@Thread
                            // The picture may be in a window of its own: the
                            // layer has already explained that, and "no picture
                            // came up" would be a second, wrong reason for the
                            // same thing.
                            if (!PlayerWindow.isEmbedded()) return@Thread
                            // The file is open and there is still nothing to
                            // show: the wait is over, and it failed.
                            if (PlayerWindow.isLoaded()) break
                            // A player that has not answered a single IPC message
                            // is not evidence of a broken stream: on a machine
                            // whose GPU falls back to software, mpv spends this
                            // time compiling shaders (observed on CI: the picture
                            // arrived long after every request had timed out). Say
                            // so instead of sitting on a silent spinner.
                            if (!saidStillTrying && System.currentTimeMillis() - startedAt > 12_000 &&
                                !PlayerWindow.ipcWorking()
                            ) {
                                saidStillTrying = true
                                Fx.run { if (proc === p) PlayerWindow.setStatus("Still opening the stream…", busy = true) }
                            }
                        }
                        Fx.run {
                            if (dialogShown || proc !== p || PlayerWindow.hasVideo()) return@run
                            if (!PlayerWindow.isEmbedded()) return@run
                            // Never blame the stream for a player we could not
                            // talk to — that is the one thing we cannot see.
                            if (!PlayerWindow.ipcWorking() && !PlayerWindow.isLoaded()) {
                                PlayerWindow.setStatus("Still opening the stream…", busy = true)
                                return@run
                            }
                            dialogShown = true
                            showBrowserFallback(
                                title, url,
                                "The stream loaded but no picture came up. Try another source, or open it in your browser.",
                                retry = refresh?.let { r ->
                                    {
                                        val fresh = runCatching { r() }.getOrNull()
                                        if (fresh != null && fresh.url.isNotBlank()) launchMpv(title, fresh, r, 1)
                                    }
                                },
                            )
                        }
                    },
                    "hikari-video-watchdog",
                ).apply { isDaemon = true; start() }
            }
            val startedAt = System.currentTimeMillis()
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 4000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    // A newer launch (or a teardown) replaced this process while
                    // it was draining — its exit is not this player's business.
                    if (proc !== p) return@Thread
                    if (code == 0 && !p.isAlive) {
                        // The stream finished with no error and no "next": mpv
                        // is gone, so the app's player layer goes too.
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
        // When the app's own player layer is up, the explanation belongs IN it
        // (over the video area, which is what the user is looking at) rather
        // than in a second dialog on top of it.
        val text = reason?.takeIf { it.isNotBlank() } ?: "Open it in your browser instead?"
        if (PlayerWindow.showFailure(text, url, retry = retry, tryAnyway = tryAnyway)) return
        val stage = Stage()
        stage.title = title
        val label = Label(text).apply { isWrapText = true }
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

    /**
     * mpv's window was not adopted within the first wait: the picture is playing
     * in a window of its own — for now. The wait keeps running in the player
     * layer, and [onAdopted] takes this explanation back down if the window
     * turns up after all.
     *
     * mpv's own on-screen controller was switched off at launch because the app
     * draws the control bar, and its window was stripped of its border — so both
     * are switched back on here, or the user is left with a borderless,
     * control-less video window and no way to pause, seek or close it.
     */
    private fun onAdoptionFailed() {
        System.err.println("player: mpv's window was not adopted in time — leaving it as a window of its own")
        val h = ipc
        if (h != null) {
            // `set_property osc` LOADS the controller script at runtime. Passing
            // --no-osc at launch means the process has no on-screen controller
            // in it at all, so a script-message to one would silently do
            // nothing — the reason "its controls are back on" was not true
            // before this.
            runCatching { h.setProperty("osc", true) }
            runCatching { h.setProperty("border", true) }
            // The app's shortcuts are bound to the APP's window, so the bindings
            // mpv came with are what the user has left in this one.
            runCatching { h.setProperty("input-default-bindings", true) }
            runCatching { h.command(2_000L, "script-message", "osc-visibility", "auto") }
        }
        Fx.run {
            PlayerWindow.showOwnWindowNote(
                "This machine cannot draw the video inside the app window, so it is playing in " +
                    "the player's own window — its border and controls are back on. Use this bar " +
                    "to control it, and close that window when you are done.",
                actions = listOf("Copy player report" to { copyPlayerReport() }),
            )
        }
    }

    /**
     * mpv's window WAS adopted after all — it turned up late. mpv's own chrome
     * goes away again and the explanation comes down, so what the user sees is
     * the picture inside the app with nothing drawn over it claiming otherwise.
     */
    private fun onAdopted() {
        System.err.println("player: mpv's window was adopted late — the picture is inside the app")
        val h = ipc
        if (h != null) {
            runCatching { h.setProperty("osc", false) }
            runCatching { h.setProperty("border", false) }
        }
        Fx.run { PlayerWindow.clearNote() }
    }

    /**
     * The player report: everything the app knows about the current playback,
     * as one paste.
     *
     * It exists because the failures that matter here only happen on the user's
     * own machine ("the picture is in a window of its own") and cannot be
     * reproduced or guessed at from a screenshot. It carries the facts that
     * decide the question — the build, the machine, the exact mpv command line,
     * what the window handling saw, and the tail of mpv's own log.
     */
    private fun playerReport(): String = buildString {
        append("Hikari player report\n")
        append("build: ").append(Build.VERSION).append("  (").append(Build.DATE).append(", ")
            .append(Build.COMMIT).append(")\n")
        append("os: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append("  arch=")
            .append(System.getProperty("os.arch")).append("  java=")
            .append(System.getProperty("java.version")).append('\n')
        append("title: ").append(lastTitle).append('\n')
        append("stream: ").append(lastStreamUrl.take(300)).append('\n')
        append("source: \"").append(sourceName).append("\"  (")
            .append(sourceList.size).append(" offered)\n")
        val p = proc
        append("mpv process: ").append(if (p == null) "none" else "pid=" + p.pid() + " alive=" + p.isAlive)
            .append('\n')
        append("ipc: ").append(if (ipc == null) "not connected" else "connected").append('\n')
        if (lastArgs.isNotEmpty()) {
            append("commands (one per line, as mpv received them):\n")
            lastArgs.forEach { append("  ").append(it).append('\n') }
        }
        append('\n').append(PlayerWindow.windowReport())
        val dir = File(System.getProperty("user.home"), ".hikari")
        append('\n').append(tailOf(File(dir, "mpv.log"), 60))
        append('\n').append(tailOf(File(dir, "hikari-player.log"), 20))
    }

    /** The last [lines] of a log file, as a titled block. */
    private fun tailOf(file: File, lines: Int): String {
        val all = runCatching { file.readLines() }.getOrNull()
            ?: return "--- " + file.name + ": not written (" + file.absolutePath + ") ---"
        return "--- " + file.name + " (last " + minOf(lines, all.size) + " of " + all.size + " lines) ---\n" +
            all.takeLast(lines).joinToString("\n")
    }

    /** Writes the report next to the app's other logs, then copies it. */
    private fun copyPlayerReport() {
        val text = runCatching { playerReport() }.getOrElse { "player report failed: " + it }
        val saved = runCatching {
            val f = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
            File(f, "player-report.txt").also { it.writeText(text) }
        }.getOrNull()
        val copied = runCatching {
            val content = javafx.scene.input.ClipboardContent()
            content.putString(text)
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content)
        }.isSuccess
        System.err.println("player: report written to " + (saved?.absolutePath ?: "?"))
        Fx.run {
            when {
                copied -> desktop.ui.AppShell.toast("Player report copied", "ok")
                saved != null -> desktop.ui.AppShell.toast("Report saved to " + saved.absolutePath, "ok")
                else -> desktop.ui.AppShell.toast("Could not write the player report", "error")
            }
        }
    }

    /** Takes any previous mpv off the screen and out of the process table.
     *
     *  Its window belongs to it, so it is parked first (instant, whatever the kill
     *  costs) and only then killed — and the kill is WAITED on, because starting
     *  the next player while the old one is still alive is how two video windows
     *  end up on screen.
     */
    private fun killPrevious(old: Process?) {
        val victim = old ?: return
        runCatching { PlayerWindow.parkSurface() }
        runCatching { victim.descendants().forEach { c -> runCatching { c.destroyForcibly() } } }
        runCatching { victim.destroyForcibly() }
        runCatching { victim.waitFor(1200L, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * The player process died (or its command pipe closed) while the app's layer
     * was up.
     *
     * A fresh link from the provider is the best answer: signed/tokenised URLs
     * expire and a dead CDN edge usually has a sibling server. Failing that, the
     * layer gets the explanation and a Retry button — never a black window.
     */
    private fun recoverFromPlayerDeath(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)?,
        attemptsLeft: Int,
        url: String,
    ) {
        if (dialogShown) return
        System.err.println("player: the player process died — attemptsLeft=" + attemptsLeft)
        killPrevious(proc)
        proc = null
        if (attemptsLeft > 0) {
            val fresh = if (refresh != null) runCatching { refresh() }.getOrNull() else null
            launchMpv(title, fresh ?: stream, refresh, attemptsLeft - 1)
            return
        }
        dialogShown = true
        showBrowserFallback(
            title, url,
            "The video player stopped unexpectedly. Try another source, or open this one in your browser.",
            retry = refresh?.let { r ->
                {
                    val f = runCatching { r() }.getOrNull()
                    if (f != null && f.url.isNotBlank()) launchMpv(title, f, r, 1)
                }
            },
        )
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
     * Connects the app's player layer to a freshly launched mpv. Off the FX
     * thread (mpv creates its pipe a moment after launch). When the endpoint
     * never appears the layer stays up and says so — the video keeps playing,
     * only the controls are unavailable, which is a far better outcome than a
     * layer that silently shows "Connecting…" forever.
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
     *  app's player layer) and kills mpv. Runs on the FX thread. */
    private fun stopPlayback() {
        Fx.run {
            runCatching { ipc?.close() }
            ipc = null
            // Takes the video window out of the picture (see parkSurface) and
            // hides the layer, before the process below is killed.
            runCatching { PlayerWindow.closeAll() }
        }
        killMpv()
    }

    fun closeAll() {
        stopPlayback()
    }

    /**
     * Kills the running mpv and forgets it — from ANY thread, at any point of
     * shutdown.
     *
     * Why this is not just [stopPlayback]: the player is a SEPARATE process
     * whose window the app adopts, and adopting a window does not make it die
     * with the app. Closing the app therefore used to leave mpv running with
     * its last frame frozen on screen — the reported "when closing player the
     * app closes but the player stays stuck". This is called from the window's
     * close request, from `Application.stop()` and from a JVM shutdown hook;
     * by the time the last two run the JavaFX toolkit is already gone, so
     * nothing here may go through [Fx] (a `Platform.runLater` at that point
     * throws, and the player would survive).
     */
    fun shutdown() {
        runCatching { PlayerWindow.parkSurface() }
        runCatching { ipc?.close() }
        ipc = null
        runCatching { PlayerWindow.closeAll() }
        killMpv()
    }

    /** Destroys mpv (its own window dies with it) and waits, briefly, for the
     *  process to actually go. `destroy()` on Windows terminates the process
     *  outright; the wait is what turns "I asked it to stop" into "it is gone",
     *  and the forcible fallback covers a wedged one. */
    private fun killMpv() {
        val p = proc
        proc = null
        ipcTarget = null
        if (p == null) return
        val started = System.currentTimeMillis()
        runCatching {
            p.descendants().forEach { child -> runCatching { child.destroy() } }
            p.destroy()
            if (!p.waitFor(1_500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.descendants().forEach { child -> runCatching { child.destroyForcibly() } }
                p.destroyForcibly()
                p.waitFor(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            System.err.println(
                "player: mpv pid=" + p.pid() + " alive=" + p.isAlive +
                    " (stopped in " + (System.currentTimeMillis() - started) + "ms)",
            )
        }.onFailure { System.err.println("player: killing mpv failed: " + it) }
    }
}
