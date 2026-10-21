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

    /** The title's episode list for the player's centered Episodes picker
     *  (series only) — stored as fields so failover relaunches keep it without
     *  re-threading every launchMpv call site. */
    @Volatile
    private var episodeList: List<com.hikari.app.data.Episode> = emptyList()

    @Volatile
    private var currentEpisodeId: String = ""

    @Volatile
    private var onPickEpisode: ((com.hikari.app.data.Episode) -> Unit)? = null

    /** True once the current launch has shown its failure dialog (mpv error or
     *  stream-probe fallback), so the two can't double-popup. Reset per launch. */
    @Volatile private var dialogShown = false

    /**
     * Every launch takes a number. The failover, watchdog, drain and probe
     * threads capture it at birth and check it before touching the UI: a
     * thread from an older launch must never pop an error over (or silently
     * relaunch away) a newer launch — that race is the reported "Retry shows,
     * then it plays by itself a second later".
     */
    @Volatile private var launchGen = 0

    /**
     * Death classification (the core of the player rework).
     *
     * A launch that dies inside [DOA_MS] of start without ever showing a frame
     * is DEAD ON ARRIVAL: the bundled player failed to START, so the stream is
     * innocent — fetching a fresh link or silently relaunching the same command
     * can never help, and doing it anyway is the "Retry flashes, then it plays
     * by itself / pipe stopped" loop. DOA gets mpv's own startup reason and a
     * browser path, never a relaunch. A launch that dies AFTER playing (or
     * after living past the window) died on the STREAM: that one gets the
     * fresh-link relaunch, exactly like before.
     */
    private const val DOA_MS = 5_000L

    /**
     * Video-output profiles, tried in order when mpv dies on arrival.
     *
     * GROUND TRUTH, not guesses: the bundled mpv's own binary (shinchiro
     * winbuild 20261020, the build CI ships) was strings-scanned. `vo_sdl`
     * and `SDL_CreateWindow` are ABSENT — there is no SDL driver, so a
     * `--vo=sdl` step dies with "Video output sdl not found" (observed in
     * a user report). Present and confirmed: `gpu-next`, `gpu-context`,
     * `angle-d3d11` with `angle-d3d11-warp` (choices no/yes), and a
     * `direct3d` (D3D9) renderer driven by the inbox d3d9.dll. So:
     * "auto" is today's command line; "nohwdec" keeps the GPU output but
     * decodes on the CPU; "angle" forces the ANGLE backend instead of
     * native d3d11; "warp" runs ANGLE on Microsoft's WARP software device
     * (no functioning GPU needed at all); "direct3d" falls back to the
     * ancient, rock-stable D3D9 renderer. A profile whose flags this mpv
     * build does not know exits instantly with an error, which the
     * dead-on-arrival path reads as "try the next one" — an unknown flag
     * can never hang or mislead, it only fails fast.
     */
    private data class VoProfile(val name: String, val args: List<String>)

    private val VO_PROFILES = listOf(
        VoProfile("auto", emptyList()),
        VoProfile("nohwdec", listOf("--hwdec=no")),
        VoProfile("angle", listOf("--hwdec=no", "--gpu-context=angle")),
        VoProfile("warp", listOf("--hwdec=no", "--gpu-context=angle", "--angle-d3d11-warp=yes")),
        VoProfile("direct3d", listOf("--hwdec=no", "--vo=direct3d")),
    )

    /** The profile this launch should start on: the last one that started a
     *  working player on this machine (window adopted), or "auto" when none
     *  has yet. */
    private fun startVoIdx(): Int {
        val want = runCatching { desktop.ui.AppShell.app.store.playerVo() }.getOrDefault("auto")
        return VO_PROFILES.indexOfFirst { it.name == want }.takeIf { it >= 0 } ?: 0
    }

    /** Remembers a profile whose player started fine (its window was
     *  adopted), so the next Play starts there instead of rediscovering the
     *  crash. A profile is remembered on ADOPTION, not on first picture: on
     *  a machine where nothing has ever produced a frame yet, the profile
     *  that at least starts is still the best next guess — otherwise every
     *  Play press re-burns the whole cascade of crashes first. */
    private fun persistVo(idx: Int) {
        val name = VO_PROFILES.getOrNull(idx)?.name ?: return
        runCatching { desktop.ui.AppShell.app.store.setPlayerVo(name) }
    }

    /** When the current launch's process started (for [DOA_MS]). */
    @Volatile private var launchStartedAt = 0L

    /**
     * mpv startup probe, refreshed at most once a minute: `mpv --version
     * --no-config` must print and exit 0. When the bundled binary cannot even
     * do that, every launch would be dead-on-arrival — fail fast with the
     * reason instead of entering the retry loop. Null = never probed.
     */
    @Volatile private var mpvProbe: Pair<Long, String?>? = null

    /**
     * mpv's log file for the CURRENT launch only. Every launch gets its own
     * file (mpv-<n>.log, latest 8 kept) because one shared mpv.log let the
     * cascade overwrite the reason the earlier step died — the report could
     * only ever show the last attempt's last lines.
     */
    @Volatile private var lastMpvLog: File? = null
    private val mpvLogSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nextMpvLog(dir: File): File {
        val f = File(dir, "mpv-" + mpvLogSeq.incrementAndGet() + ".log")
        lastMpvLog = f
        runCatching {
            dir.listFiles { _, name -> name.startsWith("mpv-") && name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(8)
                ?.forEach { runCatching { it.delete() } }
        }
        return f
    }

    /**
     * `mpv --vo=help` output for THIS mpv binary, probed in the background
     * (never the FX thread — it spawns a process) and cached an hour. The
     * exact video outputs the binary ships, printed into every player
     * report, so the cascade never guesses a driver again.
     */
    @Volatile private var voHelp: Pair<Long, String?>? = null

    private fun refreshVoHelp(mpv: File) {
        val now = System.currentTimeMillis()
        val cached = voHelp
        if (cached != null && now - cached.first < 3_600_000L) return
        val text = runCatching {
            val p = ProcessBuilder(listOf(mpv.absolutePath, "--vo=help", "--no-config"))
                .redirectErrorStream(true).start()
            val sb = StringBuilder()
            val drain = Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        if (sb.length < 1500) sb.append(it).append('\n')
                    }
                }
            }, "hikari-vo-help").apply { isDaemon = true; start() }
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { drain.join(2_000) }
            sb.toString().trim().take(1500)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        voHelp = now to text
    }

    /** The exact command line the current player was launched with, and what it
     *  was asked to play. Kept for the player report — "which flags did it
     *  actually get" is the first question about a player that misbehaves. */
    @Volatile private var lastArgs: List<String> = emptyList()

    @Volatile private var lastTitle: String = ""

    @Volatile private var lastStreamUrl: String = ""

    /** The key file written by [mpvInputConf]: one binding per line, mpv's own
     *  `input.conf` syntax. Transport commands only (seek/volume/pause/mute):
     *  anything the app must decide itself (next episode, close) stays
     *  app-side, where the decision lives. */
    private const val MPV_INPUT_CONF: String =
        "# Written by Hikari on every launch: keys for mpv's own window,\n" +
            "# mirroring the app player layer's shortcuts (see PlayerWindow.handleKey).\n" +
            "RIGHT seek 10\n" +
            "LEFT seek -10\n" +
            "UP add volume 5\n" +
            "DOWN add volume -5\n" +
            "SPACE cycle pause\n" +
            "ENTER cycle pause\n" +
            "K cycle pause\n" +
            "M cycle mute\n" +
            "J seek -10\n" +
            "L seek 10\n" +
            "F cycle fullscreen\n" +
            // mpv's own window (machines where the video cannot be glued in):
            // quitting it ends the process with code 0, which the app reads as
            // "the stream finished" and takes its own layer down too — so ESC
            // and Q always get the user out, even with no app controls around.
            "ESC quit\n" +
            "Q quit\n"

    /** Signed, short-lived stream URLs (chaturbate's `mmcdn.com`/`edge-hls`
     *  LL-HLS links) — their token is single-use and expires in seconds, so:
     *  1) the pre-flight probe must NOT request them (it would burn the token
     *     and the follow-up mpv request would 403), and
     *  2) a 403 means the link expired → relaunch with a freshly-fetched URL.
     *  The rule itself lives on [Http] so the "play straight away" speed race
     *  respects it too. */
    private fun isSignedStreamUrl(url: String): Boolean = com.hikari.app.net.Http.isSignedStreamUrl(url)

    /**
     * mpv's key bindings for ITS OWN window, written on every launch.
     *
     * The app runs mpv with `--no-input-default-bindings` (its clicks and keys
     * must not fight the app's own player layer), but on machines where the
     * video cannot be glued into the app window mpv stays a window of its own
     * — and a click into it used to swallow every shortcut dead. These mirror
     * the app-side keys in [PlayerWindow.handleKey] with mpv-native commands,
     * and the answers flow back through the observed properties (`time-pos`,
     * `pause`, `volume`, `mute`), so both windows stay on the same state
     * whichever one takes the key.
     *
     * `--no-config` only skips AUTO-LOADING config files; an explicit
     * `--input-conf=` on the command line is still honoured.
     */
    private fun mpvInputConf(): String? = runCatching {
        val dir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
        val f = File(dir, "mpv-input.conf")
        f.writeText(MPV_INPUT_CONF)
        f.absolutePath
    }.getOrNull()

    fun play(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)? = null,
        next: (() -> Unit)? = null,
        position: ((Long, Long) -> Unit)? = null,
        sources: List<StreamSource> = emptyList(),
        onPickSource: ((StreamSource) -> Unit)? = null,
        episodes: List<com.hikari.app.data.Episode> = emptyList(),
        currentEpisodeId: String = "",
        onPickEpisode: ((com.hikari.app.data.Episode) -> Unit)? = null,
    ) {
        onEnded = next
        onPosition = position
        sourceList = sources.ifEmpty { listOf(stream) }
        sourceName = stream.name
        this.onPickSource = onPickSource
        episodeList = episodes
        this.currentEpisodeId = currentEpisodeId
        this.onPickEpisode = onPickEpisode
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
        // New title, not a relaunch: last title's banked skips die here —
        // same-stream relaunches (below) keep them so a retry never eats a seek.
        PlayerWindow.clearBankedSeeks()
        launchMpv(title, stream, refresh, attemptsLeft = 2)
    }

    // NOTE for future edits: this is the ONLY new-title stream entry. Every
    // other launchMpv caller is a same-stream relaunch (failover, silent
    // retry, video-output cascade) and must NOT clear banked seeks.

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

    fun playFile(title: String, path: String, voIdx: Int = -1) {
        // New title, not a relaunch: last title's banked skips die here (see
        // PlayerWindow.clearBankedSeeks) — same-stream relaunches keep them.
        PlayerWindow.clearBankedSeeks()
        onEnded = null
        onPosition = null
        sourceList = emptyList()
        sourceName = ""
        onPickSource = null
        episodeList = emptyList()
        currentEpisodeId = ""
        onPickEpisode = null
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
            // Cached probe only (never block the UI thread here): when a
            // stream launch already proved mpv broken this minute, say so now
            // instead of dying on the file too. Fresh failures are caught by
            // the dead-on-arrival message in the drain thread below.
            val now = System.currentTimeMillis()
            val cachedErr = mpvProbe?.takeIf { now - it.first < 60_000L }?.second
            if (cachedErr != null) {
                showBrowserFallback(title, path, brokenPlayerMessage(cachedErr))
                return@run
            }
            // Same ownership rule as the stream path below: the old channel
            // dies with the old player, never into this launch.
            runCatching { ipc?.close() }
            ipc = null
            ipcTarget = openIpcEndpoint()
            val vo = if (voIdx in VO_PROFILES.indices) voIdx else startVoIdx()
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
                add("--keepaspect-window=no")
                add("--no-input-default-bindings")
                // Keys for mpv's own window (unglued mode): with the defaults
                // above it would swallow every shortcut dead. See [mpvInputConf].
                mpvInputConf()?.let { add("--input-conf=$it") }
                add("--title=" + title.take(200).replace('\n', ' '))
                ipcArg()?.let { add(it) }
                val logDir = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }
                add("--log-file=${nextMpvLog(logDir).absolutePath}")
                // Same cascade as the stream path (see [VO_PROFILES]).
                addAll(VO_PROFILES[vo].args)
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
                    // An adopted window means this video output WORKS on this
                    // machine: remember the profile now, not on first picture
                    // (see [persistVo]) — a cascade step that starts but never
                    // plays must still win over re-burning the crashes above it.
                    onAdopted = { persistVo(vo); onAdopted() },
                )
            }
            attachPlayerWindow(title)
            // If the player dies under a live window, recover instead of showing
            // a black rectangle (see PlayerWindow.onPlayerDied).
            PlayerWindow.onPlayerDied = {
                recoverFromPlayerDeath(title, StreamSource(name = title, url = path), null, 0, path, vo)
            }
            PlayerWindow.onVideoUp = { persistVo(vo) }
            val localStartedAt = System.currentTimeMillis()
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
                        // Dead-on-arrival even for a local file: the player
                        // failed to START, so the file is innocent — first the
                        // next safer video output (see [VO_PROFILES]), and
                        // only the exhausted cascade says that on screen.
                        val doa = isDeadOnArrival(localStartedAt)
                        Fx.run {
                            if (dialogShown) return@run
                            if (doa && vo + 1 < VO_PROFILES.size) {
                                PlayerWindow.setStatus("Trying a safer video output…", busy = true)
                                playFile(title, path, vo + 1)
                                return@run
                            }
                            dialogShown = true
                            showBrowserFallback(
                                title, path,
                                if (doa) doaMessage()
                                else "The player closed with an error while opening the downloaded file.",
                            )
                        }
                    }
                },
                "hikari-mpv-local",
            ).apply { isDaemon = true; start() }
        }
    }

    /**
     * True when a stream must go through the local relay ([HlsRelay]) instead
     * of straight to mpv.
     *
     * The relay exists for HLS: single-use signed playlists, TLS-fingerprinted
     * CDNs, and backslash playlist paths. A progressive MP4/MKV sent through
     * it used to stall on "Loading …" forever: the relay drops mpv's `Range`
     * (its moov-atom probe), so mpv waits for gigabytes from byte 0 before its
     * first frame. Direct files play instantly in mpv with their headers, so
     * only playlists (and the hosts that need the app's HTTP stack) take the
     * relay now.
     */
    private fun needsRelay(url: String, isM3u8: Boolean): Boolean {
        if (isM3u8) return true
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.endsWith(".txt")) return true
        if (lower.contains(".m3u8")) return true
        // Backslash playlist paths and single-use signed links need the relay's
        // normalization + caching even when the suffix says otherwise.
        if (url.contains('\\')) return true
        if (isSignedStreamUrl(url)) return true
        return false
    }

    private fun launchMpv(
        title: String,
        stream: StreamSource,
        refresh: (() -> StreamSource?)?,
        attemptsLeft: Int,
        failedOver: Boolean = false,
        voIdx: Int = -1,
    ) {
        // The startup probe blocks (spawns a process and waits): never run it
        // on the FX thread — callers inside Fx.run land here too, so they are
        // offloaded to a launcher thread and the rest runs as before.
        if (javafx.application.Platform.isFxApplicationThread()) {
            Thread(
                { launchMpv(title, stream, refresh, attemptsLeft, failedOver, voIdx) },
                "hikari-play-launch",
            ).apply { isDaemon = true; start() }
            return
        }
        val mpv = findMpv()
        if (mpv == null) {
            val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
            Fx.run {
                showBrowserFallback(title, url,
                    "The video player (mpv) wasn't found next to the app — re-download the latest release.")
            }
            return
        }
        // Startup probe: a bundled mpv that cannot even print its version
        // would die on every launch (dead-on-arrival, always) — say so now
        // with its reason instead of entering the retry loop.
        val probeErr = probeMpv(mpv, force = false)
        // Off the FX thread here (see the offload above), so this is the
        // place to refresh the capability probe the report carries.
        if (probeErr == null) refreshVoHelp(mpv)
        if (probeErr != null) {
            val url = com.hikari.app.net.Http.sanitizeStreamUrl(stream.url)
            Fx.run {
                showBrowserFallback(title, url, brokenPlayerMessage(probeErr), retry = {
                    // User-initiated: re-probe (an antivirus lock can clear)
                    // and try the same stream once more.
                    val err2 = probeMpv(mpv, force = true)
                    if (err2 != null) showBrowserFallback(title, url, brokenPlayerMessage(err2))
                    else launchMpv(title, stream, refresh, 1)
                })
            }
            return
        }
        Fx.run {
            // A previous launch's control channel must not survive into this
            // one: its death arrives as `ipc-closed` on the shared layer and
            // would "recover" (kill + relaunch) the player that just started.
            runCatching { ipc?.close() }
            ipc = null
            ipcTarget = openIpcEndpoint()
            val gen = ++launchGen
            // The video-output profile for this attempt: an explicit cascade
            // step, or the last profile that started a working player on this machine.
            val vo = if (voIdx in VO_PROFILES.indices) voIdx else startVoIdx()
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
                episodes = episodeList,
                currentEpisodeId = currentEpisodeId,
                onPickEpisode = onPickEpisode,
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
            // HLS goes through the app's own HTTP stack (HlsRelay): the CDNs
            // that serve these streams (chaturbate's mmcdn, myspacecat, …) 403
            // the player's direct connections — single-use signed tokens and
            // TLS fingerprint blocking — while the app's OkHttp fetches the
            // same URLs fine. The relay also rewrites chaturbate's backslash
            // root-relative playlist lines into proper URLs.
            //
            // Progressive files (MP4/MKV/WEBM) play DIRECTLY in mpv with their
            // headers: routing them through the relay dropped mpv's Range
            // (its moov probe) and left a 1080p file on "Loading …" forever.
            // [failedOver] means the first path produced no picture in 12s, so
            // the opposite path is tried once, automatically.
            val wantRelay = needsRelay(url, stream.isM3u8)
            val useRelay = if (!failedOver) wantRelay else !wantRelay
            val playUrl = if (useRelay) HlsRelay.urlFor(url, stream.headers) else url
            File(logDir, "hikari-player.log").appendText(
                "[" + java.time.Instant.now() + "] raw=" + debugEscaped(stream.url) +
                    "\n  san=" + debugEscaped(url) +
                    "\n  ply=" + debugEscaped(playUrl) +
                    "\n  glue=" + glued + " relay=" + useRelay + " failover=" + failedOver + "\n"
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
                add("--keepaspect-window=no")
                add("--no-input-default-bindings")
                // Keys for mpv's own window (unglued mode): with the defaults
                // above it would swallow every shortcut dead. See [mpvInputConf].
                mpvInputConf()?.let { add("--input-conf=$it") }
                // No youtube-dl hook: for direct HLS/MP4 URLs it fires a SECOND,
                // header-less probe (no Referer/Cookie) that 403s on protected
                // CDNs and only adds confusing [ytdl_hook] errors to the dialog.
                add("--no-ytdl")
                // The app draws the one control bar, so mpv's on-screen
                // controller would only fight it for the bottom of the video.
                add("--no-osc")
                // Start NOW, not after a long buffer: cap the network wait and
                // the demuxer readahead so the first frame is asked for at
                // once. A slow CDN then errors fast (and fails over below)
                // instead of sitting on "Loading …" while mpv fills a deep
                // buffer it will never need for the first frame.
                add("--network-timeout=20")
                add("--demuxer-readahead-secs=2")
                add("--title=" + title.take(200).replace('\n', ' '))
                val logDir2 = logDir
                add("--log-file=${nextMpvLog(logDir2).absolutePath}")
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
                // The cascade step's output flags (none for "auto"). See
                // [VO_PROFILES]: a dead GPU path is retried safer, not shown.
                addAll(VO_PROFILES[vo].args)
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
            launchStartedAt = System.currentTimeMillis()
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
                    // An adopted window means this video output WORKS on this
                    // machine: remember the profile now, not on first picture
                    // (see [persistVo]) — a cascade step that starts but never
                    // plays must still win over re-burning the crashes above it.
                    onAdopted = { persistVo(vo); onAdopted() },
                )
            }
            attachPlayerWindow(title)
            // If the player dies under a live window, recover instead of showing
            // a black rectangle (see PlayerWindow.onPlayerDied).
            PlayerWindow.onPlayerDied = { recoverFromPlayerDeath(title, stream, refresh, attemptsLeft, url, vo) }
            PlayerWindow.onVideoUp = { persistVo(vo) }
            // Only the FIRST explanation per launch shows: the mpv error path and
            // the stream probe both try to explain a dead player, never both.
            dialogShown = false
            // Fast failover: the wrong path (relay vs direct) looks like
            // "Loading …" forever — no video, no file, no IPC error. After 12s
            // with NOTHING (no picture, file not open), try the OTHER path once
            // automatically instead of leaving the spinner up. A slow GPU
            // shader compile answers no IPC either, so this only fires while
            // the file isn't open AND the channel never answered; once mpv has
            // the file, the 45s watchdog below owns the wait.
            if (!failedOver && glued) {
                val myProc = p
                val myUseRelay = useRelay
                val myGen = gen
                Thread(
                    {
                        Thread.sleep(12_000)
                        if (proc !== myProc || !myProc.isAlive) return@Thread
                        if (PlayerWindow.hasVideo() || PlayerWindow.isLoaded() || PlayerWindow.ipcWorking()) return@Thread
                        if (!PlayerWindow.isEmbedded()) return@Thread
                        System.err.println(
                            "player: no picture/file/ipc after 12s via " +
                                (if (myUseRelay) "relay" else "direct") + " — failing over once",
                        )
                        Fx.run {
                            // Superseded (a newer launch), or the user is
                            // already reading a failure message — never yank
                            // either away with a silent relaunch.
                            if (myGen != launchGen || proc !== myProc) return@run
                            if (PlayerWindow.messageVisible()) return@run
                            launchMpv(title, stream, refresh, attemptsLeft, failedOver = true, voIdx = vo)
                        }
                    },
                    "hikari-play-failover",
                ).apply { isDaemon = true; start() }
            }
            if (glued) {
                // A stream can load and still never produce a picture (a dead
                // CDN edge, an unsupported codec, a geo-block that only bites
                // the media segments). Say so over the video area — never leave
                // a black rectangle sitting there, and never spawn a second
                // player window behind the user's back.
                val myGen = gen
                Thread(
                    {
                        val startedAt = System.currentTimeMillis()
                        var saidStillTrying = false
                        var deadline = startedAt + 45_000
                        // A stream that is still arriving is slow, not stuck:
                        // while mpv keeps showing signs of life the deadline
                        // keeps moving (capped), so a slow CDN can never earn
                        // a "no picture came up" dialog. The reported "it
                        // showed Retry, then played by itself" was this watch
                        // giving up seconds before the picture arrived.
                        val hardCap = startedAt + 150_000
                        while (System.currentTimeMillis() < deadline) {
                            Thread.sleep(500)
                            if (proc !== p || !p.isAlive) return@Thread
                            if (PlayerWindow.hasVideo()) return@Thread
                            // The picture may be in a window of its own: the
                            // layer has already explained that, and "no picture
                            // came up" would be a second, wrong reason for the
                            // same thing.
                            if (!PlayerWindow.isEmbedded()) return@Thread
                            // Still arriving: move the deadline out instead of
                            // failing. The status line says so, honestly.
                            if (System.currentTimeMillis() - PlayerWindow.lastProgressAt() < 10_000 &&
                                deadline < hardCap
                            ) {
                                deadline = minOf(System.currentTimeMillis() + 30_000, hardCap)
                                Fx.run {
                                    if (proc === p && !PlayerWindow.hasVideo()) {
                                        PlayerWindow.setStatus("Still opening the stream…", busy = true)
                                    }
                                }
                            }
                            // The file being open is NOT failure: HLS opens its
                            // playlist in ~2s and fetches media segments after
                            // that (Dailymotion: playlists first, media from
                            // ~2.8s), so breaking here is the "Retry flashes,
                            // then it plays by itself in 3-5s" bug. Keep waiting
                            // while bytes arrive (the deadline extends above);
                            // the dialog below only runs after a true stall.
                            if (PlayerWindow.isLoaded() && !PlayerWindow.ipcWorking()) {
                                Fx.run {
                                    if (proc === p && !PlayerWindow.hasVideo()) {
                                        PlayerWindow.setStatus("Still opening the stream…", busy = true)
                                    }
                                }
                            }
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
                            // A newer launch superseded this watch: its UI is
                            // none of this thread's business any more.
                            if (myGen != launchGen) return@run
                            if (dialogShown || proc !== p || PlayerWindow.hasVideo()) return@run
                            if (!PlayerWindow.isEmbedded()) return@run
                            // Never blame the stream for a player we could not
                            // talk to — that is the one thing we cannot see.
                            if (!PlayerWindow.ipcWorking() && !PlayerWindow.isLoaded()) {
                                PlayerWindow.setStatus("Still opening the stream…", busy = true)
                                return@run
                            }
                            // SILENT retry first: the link this mpv was given may
                            // have died between load and play (signed links live
                            // seconds), and fetching a fresh one + relaunching
                            // costs the user nothing — while an error dialog for
                            // a link that was fine costs them the stream. Only
                            // when there is nothing fresh to try (or no attempts
                            // left) does the browser fallback come up. The
                            // relaunched launch consumes one attempt, so this
                            // cannot loop: every retry decrements the budget.
                            // A visible failure message always wins over this:
                            // relaunching under it is the "Retry flashes, then
                            // it plays by itself" dance.
                            // Silent fresh-link retry only after 10s of true
                            // silence: relaunching while the first mpv is about
                            // to show its first frame murders a healthy stream
                            // and restarts the 3-5s wait (the other half of the
                            // Retry-then-plays-by-itself dance).
                            if (refresh != null && attemptsLeft > 0 && !PlayerWindow.messageVisible() &&
                                System.currentTimeMillis() - PlayerWindow.lastProgressAt() >= 10_000
                            ) {
                                val fresh = runCatching { refresh() }.getOrNull()
                                if (fresh != null && fresh.url.isNotBlank()) {
                                    PlayerWindow.setStatus("Still opening the stream…", busy = true)
                                    launchMpv(title, fresh, refresh, attemptsLeft - 1, failedOver, voIdx = vo)
                                    return@run
                                }
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
            val drainGen = gen
            Thread(
                {
                    val tail = StringBuilder()
                    runCatching { p.inputStream.bufferedReader().forEachLine { if (tail.length < 4000) tail.append(it).append('\n') } }
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    // A newer launch (or a teardown) replaced this process while
                    // it was draining — its exit is not this player's business.
                    if (drainGen != launchGen || proc !== p) return@Thread
                    if (code == 0 && !p.isAlive) {
                        // The stream finished with no error and no "next": mpv
                        // is gone, so the app's player layer goes too.
                        Fx.run { if (proc === p) stopPlayback() }
                    }
                    if (code != 0 && !p.isAlive) {
                        val tailText = tail.toString()
                        val forbidden = tailText.contains("403")
                        // Dead-on-arrival: died within moments of start without
                        // ever showing a frame — the PLAYER failed to start, not
                        // the stream. Relaunching the same command (or fetching
                        // a fresh link for it) can never help, so there is no
                        // silent relaunch here: the reason goes on screen.
                        if (isDeadOnArrival(startedAt)) {
                            // The player failed to START — but "start" depends
                            // on the video output: a machine whose GPU path
                            // crashes gets the next safer profile silently, not
                            // an error. Only the exhausted cascade explains
                            // itself on screen. Same URL, same budget: a new
                            // player configuration is not a stream retry.
                            if (vo + 1 < VO_PROFILES.size) {
                                Fx.run {
                                    if (dialogShown || drainGen != launchGen || proc !== p) return@run
                                    PlayerWindow.setStatus("Trying a safer video output…", busy = true)
                                    launchMpv(title, stream, refresh, attemptsLeft, failedOver, vo + 1)
                                }
                                return@Thread
                            }
                            Fx.run {
                                if (dialogShown || drainGen != launchGen || proc !== p) return@run
                                dialogShown = true
                                showBrowserFallback(
                                    title, url, doaMessage(),
                                    retry = refresh?.let { r ->
                                        {
                                            val f = runCatching { r() }.getOrNull()
                                            if (f != null && f.url.isNotBlank()) launchMpv(title, f, r, 1)
                                        }
                                    },
                                )
                            }
                            return@Thread
                        }
                        // A dead link is fetched FRESH and relaunched silently —
                        // for ANY failure with a refresh available, not just
                        // signed/403 ones: an error dialog for a link that was
                        // fine costs the user the stream, while the relaunch
                        // costs nothing. Never yank away an error the user is
                        // already reading (see [dialogShown]): that flash is the
                        // reported "Retry shows, then it plays by itself".
                        if (attemptsLeft > 0 && refresh != null && !dialogShown) {
                            val fresh = runCatching { refresh() }.getOrNull()
                            if (fresh != null && fresh.url.isNotBlank()) {
                                Fx.run {
                                    if (drainGen != launchGen || proc !== p) return@run
                                    launchMpv(title, fresh, refresh, attemptsLeft - 1)
                                }
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
                                if (dialogShown || drainGen != launchGen || proc !== p) return@run
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
            val probeProc = p
            val probeGen = gen
            if (!signed) {
                Thread(
                    {
                        val verdict = com.hikari.app.net.Http.probeStreamUrl(url, stream.headers)
                        if (verdict != com.hikari.app.net.Http.StreamProbe.HLS &&
                            verdict != com.hikari.app.net.Http.StreamProbe.VIDEO &&
                            verdict != com.hikari.app.net.Http.StreamProbe.UNKNOWN
                        ) {
                            Fx.run {
                                if (dialogShown || probeGen != launchGen || proc !== probeProc || !probeProc.isAlive) return@run
                                dialogShown = true
                                runCatching { probeProc.destroy() }
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

    /**
     * Runs `mpv --version --no-config` and returns null when the bundled
     * player starts fine, or the reason it doesn't. Cached for a minute (a
     * healthy `--version` answers in milliseconds; a wedged one must not
     * stall every Play press). Never call on the FX thread — it blocks.
     */
    private fun probeMpv(mpv: File, force: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            val cached = mpvProbe
            if (cached != null && now - cached.first < 60_000L) return cached.second
        }
        val err: String? = runCatching {
            val p = ProcessBuilder(listOf(mpv.absolutePath, "--version", "--no-config"))
                .redirectErrorStream(true).start()
            val out = StringBuilder()
            val drain = Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        if (out.length < 2000) out.append(it).append('\n')
                    }
                }
            }, "hikari-mpv-probe-drain").apply { isDaemon = true; start() }
            val finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { drain.join(2_000) }
            if (!finished) {
                runCatching { p.destroyForcibly() }
                return@runCatching "mpv did not answer within 15 seconds (wedged or blocked on this machine)"
            }
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            val text = out.toString()
            if (code != 0 || !text.contains("mpv", ignoreCase = true)) {
                val tail = text.trim().lineSequence().filter { it.isNotBlank() }
                    .toList().takeLast(3).joinToString(" | ").take(400)
                return@runCatching "mpv exited with code $code" +
                    (if (tail.isNotBlank()) ": $tail" else " and printed nothing")
            }
            null
        }.getOrElse { t ->
            "couldn't start mpv at all: ${t.message ?: t.javaClass.simpleName}"
        }
        mpvProbe = now to err
        if (err != null) System.err.println("player: mpv probe failed — $err")
        return err
    }

    /** True when the launch that started at [startedAt] died before it could
     *  ever show a frame: the player failed to START, not the stream. */
    private fun isDeadOnArrival(startedAt: Long): Boolean {
        if (startedAt <= 0L) return false
        if (System.currentTimeMillis() - startedAt >= DOA_MS) return false
        return !sawVideoNow()
    }

    private fun sawVideoNow(): Boolean =
        runCatching { Fx.runBlock { PlayerWindow.hasVideo() } ?: false }.getOrDefault(false)

    /** The DOA explanation: names the player (not the stream) and carries
     *  mpv's own last error lines — from THIS launch's log file, so the
     *  cascade cannot overwrite the reason before it is read. */
    private fun doaMessage(): String {
        val logFile = lastMpvLog
            ?: File(System.getProperty("user.home"), ".hikari/mpv.log")
        val tail = runCatching {
            usefulLogLines(logFile.readLines(), 12).joinToString("\n").trim()
        }.getOrDefault("").take(1200)
        return buildString {
            append("The video player (mpv) closed immediately without playing anything — ")
            append("it failed to START, so this is the player, not the stream: ")
            append("another source would fail the exact same way.")
            if (tail.isNotBlank()) append("\n\nmpv's own error:\n").append(tail)
            append("\n\nOpen it in your browser instead, or copy the player report?")
        }.toString()
    }

    private fun brokenPlayerMessage(probeErr: String): String =
        "The bundled video player (mpv) can't start on this machine: $probeErr. " +
            "No source can play until it can — re-download the latest release, " +
            "or open this one in your browser instead?"

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
        // Liveness, not just presence: a kept handle to a dead pipe used to
        // read as "connected" while naming a pipe from two launches ago. The
        // endpoint line says which pipe THIS launch was given, so a mismatch
        // between the two is visible instead of mysterious.
        val h = ipc
        append("ipc: ").append(
            if (h == null) "none"
            else "connected (" + h.target + ", alive=" + h.isAlive() + ")",
        ).append('\n')
        append("ipc endpoint for this launch: ").append(ipcTarget ?: "none").append('\n')
        // The exact video outputs this mpv binary ships — probed on the
        // machine itself, so a missing driver is a fact, not a guess.
        append("mpv video outputs (--vo=help):\n")
        append("  ").append(voHelp?.second?.take(800)?.replace("\n", "\n  ") ?: "(not probed yet — play something once)").append('\n')
        if (lastArgs.isNotEmpty()) {
            append("commands (one per line, as mpv received them):\n")
            lastArgs.forEach { append("  ").append(it).append('\n') }
        }
        append('\n').append(PlayerWindow.windowReport())
        val dir = File(System.getProperty("user.home"), ".hikari")
        append('\n').append(tailOf(lastMpvLog ?: File(dir, "mpv.log"), 60))
        append('\n').append(tailOf(File(dir, "hikari-player.log"), 20))
    }

    /** The last [lines] of a log file, as a titled block — with mpv's
     *  thousands of `watch_later` state-path lines filtered out, so the
     *  block shows what actually happened instead of 60 lines of spam. */
    private fun tailOf(file: File, lines: Int): String {
        val all = runCatching { file.readLines() }.getOrNull()
            ?: return "--- " + file.name + ": not written (" + file.absolutePath + ") ---"
        val useful = usefulLogLines(all, lines)
        return "--- " + file.name + " (" + useful.size + " most useful of " + all.size + " lines) ---\n" +
            useful.joinToString("\n")
    }

    /** mpv prints thousands of `watch_later` state-path lines per launch, so
     *  the last N raw lines of its log are always that spam: every pasted
     *  report — and the DOA dialog — showed nothing but noise while the real
     *  error scrolled above it. Reports and dialogs instead show the last
     *  non-noise lines, plus any error-looking lines wherever they sit. */
    private fun isNoiseLogLine(l: String): Boolean {
        val t = l.lowercase()
        return t.contains("watch_later") || t.contains("state path:")
    }

    private fun usefulLogLines(all: List<String>, keep: Int): List<String> {
        if (all.isEmpty()) return all
        val tail = all.filter { !isNoiseLogLine(it) }.takeLast(keep)
        val errs = all.filter { l ->
            if (isNoiseLogLine(l)) return@filter false
            val t = l.lowercase()
            t.contains("[e]") || t.contains("[f]") || t.contains("error") ||
                t.contains("fail") || t.contains("403") || t.contains("404") ||
                t.contains("not found") || t.contains("no protocol") ||
                t.contains("unsupported") || t.contains("exiting")
        }.takeLast(keep)
        return (errs + tail).distinct().takeLast(keep)
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
        voIdx: Int = -1,
    ) {
        System.err.println("player: the player process died — attemptsLeft=" + attemptsLeft)
        killPrevious(proc)
        // The dead launch's channel dies with it (see [killMpv]): the next
        // launch opens its own, and this one's `ipc-closed` must not chase it.
        runCatching { ipc?.close() }
        ipc = null
        proc = null
        val vo = if (voIdx in VO_PROFILES.indices) voIdx else startVoIdx()
        // Dead-on-arrival through the pipe path (mpv died before ever showing
        // a frame): same rule as the drain thread — first the next safer
        // video output (see [VO_PROFILES]), and only the exhausted cascade
        // gets mpv's startup reason on screen with a browser path.
        if (!dialogShown && isDeadOnArrival(launchStartedAt)) {
            if (vo + 1 < VO_PROFILES.size) {
                PlayerWindow.setStatus("Trying a safer video output…", busy = true)
                launchMpv(title, stream, refresh, attemptsLeft, voIdx = vo + 1)
                return
            }
            dialogShown = true
            showBrowserFallback(
                title, url, doaMessage(),
                retry = refresh?.let { r ->
                    {
                        val f = runCatching { r() }.getOrNull()
                        if (f != null && f.url.isNotBlank()) launchMpv(title, f, r, 1)
                    }
                },
            )
            return
        }
        if (attemptsLeft > 0 && !dialogShown) {
            // Auto-recovery only while no failure is on screen: relaunching
            // under a shown dialog is the "Retry flashes, then it plays by
            // itself" dance. The relaunch clears the layer's message (see
            // resetForNewStream), and the next launch's threads ignore this
            // one (see launchGen).
            val fresh = if (refresh != null) runCatching { refresh() }.getOrNull() else null
            launchMpv(title, fresh ?: stream, refresh, attemptsLeft - 1, voIdx = vo)
            return
        }
        if (dialogShown) return
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
                            // No channel for this launch: say so, and forget
                            // any older one — the report must never call a
                            // dead launch's pipe "connected".
                            ipc = null
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
                        // Only our own handle: a newer launch may already have
                        // attached its channel while this block was queued.
                        if (ipc === client) ipc = null
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
        // Forgetting the process means forgetting its channel too: a handle
        // to a dead pipe reads as "connected" in the report and its death
        // notice would "recover" the next launch.
        runCatching { ipc?.close() }
        ipc = null
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
