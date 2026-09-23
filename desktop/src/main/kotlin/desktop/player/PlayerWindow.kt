package desktop.player

import com.hikari.app.data.StreamSource
import desktop.fx.DesktopUi
import desktop.fx.Fx
import desktop.ui.AppShell
import desktop.ui.Icons
import desktop.ui.Ui
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import javafx.stage.Screen
import javafx.util.Duration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The app's own player — the video plays INSIDE the app window, never in a
 * second window of its own.
 *
 * mpv is still the engine (JavaFX cannot decode these CDN/HLS streams), but its
 * window is adopted: [WinShell] finds the window mpv opened, strips its caption
 * and taskbar presence, and keeps it glued exactly over the video area below —
 * so what the user sees is one window, the app's, with the picture inside it and
 * ONE control bar under it. mpv's own on-screen controller is switched OFF for
 * the same reason: there is exactly one set of controls, and it is this one.
 *
 * The layout is a player's, not a dashboard's:
 *
 *   top strip     Back · title · status · window controls
 *   video area    mpv's window, glued over it (spinners/explanations park it)
 *   control bar   play/pause · time · seek · total · Source · Audio · Subs ·
 *                 next episode · fullscreen
 *
 * The video surface is a real window sitting ABOVE everything the app draws, so
 * it has to get out of the way while a spinner or an explanation is on screen.
 * It is *parked* (moved far off-screen at its proper size) rather than hidden or
 * shrunk: hiding it stops mpv rendering into it, and shrinking it makes the
 * video output come up at the wrong size — both of which produced the classic
 * "sound but no picture" failure.
 */
object PlayerWindow {

    /** How often the video surface is re-glued to the video area. The surface is
     *  a real window, so a move of the app window (a drag, a maximise, a DPI
     *  change) has to be followed — events cover the common cases, this covers
     *  the rest. */
    private const val SYNC_MS = 120.0

    /** How long the bars stay up after the last pointer/key activity. Two
     *  seconds is what players on every platform do, and it is the difference
     *  between "the picture, with controls when you reach for them" and "a
     *  control bar with a picture behind it". */
    private const val CHROME_IDLE_MS = 2000.0

    /**
     * How long the player's window is waited for before the user is told the
     * picture is playing in a window of its own.
     *
     * mpv is launched with `--force-window=immediate`, so its window exists from
     * startup and this is a formality — the wait only has to cover mpv's own
     * process start. It is deliberately no longer a DEADLINE, though: the wait
     * keeps running past it (see `adoptLoop`), because a window that turns up
     * late is still a window that gets glued over the video area.
     */
    private const val FIRST_WAIT_MS = 10_000L

    private var root: BorderPane? = null
    private var mounted = false

    /** The adopted mpv window, and the app window that owns it. */
    private var surfaceHwnd: Long? = null
    private var ownerHwnd: Long? = null

    /** Set while the layer is being torn down, so a close request cannot
     *  re-enter (the close notification calls back into the owner, which calls
     *  [closeAll] again). */
    private var teardown = false

    private var ipc: MpvIpc? = null
    private var syncTimer: Timeline? = null
    private var keyFilter: EventHandler<KeyEvent>? = null

    private val videoArea = StackPane().apply {
        styleClass.add("player-video")
        minWidth = 0.0
        minHeight = 80.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }

    private var topStrip: HBox? = null
    private var bottomBar: HBox? = null
    private var titleLabel: Label? = null
    private var statusLabel: Label? = null
    private var statusChip: HBox? = null
    private var statusSpinner: ProgressIndicator? = null
    private var timeLabel: Label? = null
    private var totalLabel: Label? = null
    private var seekBar: Slider? = null
    private var playButton: Button? = null
    private var nextButton: Button? = null
    private var sourceMenu: MenuButton? = null
    private var audioMenu: MenuButton? = null
    private var subMenu: MenuButton? = null
    private var fullscreenButton: Button? = null
    /** The two thin separators in the control bar, hidden on a tiny window so
     *  the transport controls keep their space (see [applyResponsive]). */
    private var barSeparators: List<Region> = emptyList()
    private var messageLabel: Label? = null
    private var messageActions: HBox? = null
    private var messageBox: VBox? = null
    private var loadingBox: VBox? = null

    private var duration = 0.0
    private var scrubbing = false
    private var lastPosition = 0L
    private var volume = 100.0
    private var paused = false

    /** Set while the loading overlay or an explanation covers the video area, so
     *  the video surface can get out of the way. */
    private var overlayUp = true

    /** True while the two bars (top strip + control bar) are on screen. They are
     *  not permanent furniture: a mouse move brings them back and they leave
     *  again two seconds later, because a bar that is always up is a bar that is
     *  always covering the picture. */
    private var chromeVisible = true

    /** The countdown that takes the bars away; restarted by [pokeChrome]. */
    private var hideChrome: PauseTransition? = null

    /** The pointer's last seen screen position (physical pixels), so [pollPointer]
     *  can tell a move from a rest. */
    private var lastCursorX = Int.MIN_VALUE
    private var lastCursorY = Int.MIN_VALUE

    /** Whether the left mouse button was down at the last poll — a click on the
     *  picture brings the bars back. */
    private var leftWasDown = false

    /** True while the status chip is showing a failure (so starting playback
     *  does not wipe an error the caller just reported). */
    private var statusIsError = false

    /** The codec mpv reported for the current stream, kept in the title's
     *  tooltip rather than in the bar. */
    private var videoFormat = ""

    /** True while the video is being shown inside the app (the window could be
     *  adopted and has been glued over the video area). */
    private var embedded = false

    /** True once mpv reported a real video format — the signal that the video
     *  output actually came up (a null `video-format` just means "no video yet",
     *  which is NOT the same as "this stream is audio only"). */
    private var sawVideo = false

    /** True once mpv has answered over IPC. A player that never answers has not
     *  told us anything is wrong — see [ipcWorking]. */
    private var ipcAnswered = false

    /** True once mpv has the file open (its `file-loaded` event). */
    private var loaded = false

    private var onNext: (() -> Unit)? = null
    private var onPosition: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    private var onPickSource: ((StreamSource) -> Unit)? = null

    /**
     * Called (once, on the FX thread) when the PLAYER died under a live window:
     * mpv stopped reading its command pipe, or the file ended with an error
     * before a single frame was shown. Without this the layer sat on a black
     * rectangle with "Playback stopped: …" and the user had nothing to click —
     * the reported "player is blank, and it opened another window" state.
     */
    @Volatile
    var onPlayerDied: (() -> Unit)? = null

    /**
     * Installed by the player so that every explanation this layer shows can
     * offer "Copy player report" — a failure that only happens on the user's
     * machine is worth more as text they can send than as a description they
     * have to write.
     */
    @Volatile
    var onCopyReport: (() -> Unit)? = null

    /** The title mpv was launched with (`--title=…`). Its window carries that
     *  title verbatim, which is a second way to find the window when the
     *  process-id search comes up empty. */
    @Volatile
    private var mpvTitle = ""

    /** What the adoption attempt saw, one timestamped line per event. This is
     *  the only honest answer to "why is the picture not inside the app?", so it
     *  is kept for [windowReport] instead of being written to a log nobody
     *  reads. */
    private val adoptTrace = java.util.Collections.synchronizedList(ArrayList<String>())
    private var adoptStartedAt = 0L

    /** The last thing the status chip said — read off the FX thread by
     *  [windowReport]. */
    @Volatile
    private var lastStatus = ""

    /** The window [findWindowOf] settled on for the player, for the report. */
    @Volatile
    private var candidateHwnd = 0L

    /** Why the last adoption attempt was refused — "refused" alone is not
     *  something anyone can act on. */
    @Volatile
    private var adoptRefusal = ""

    /** True while the "it is playing in the player's own window" explanation is
     *  on screen, so a LATE adoption can take it back down again. */
    @Volatile
    private var ownWindowNoteUp = false

    private var sources: List<StreamSource> = emptyList()
    private var currentSource: StreamSource? = null
    private var currentSourceName = ""

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Opens the player for a new stream — instantly. The layer is mounted (first
     * time) or re-shown with its loading overlay up. Returns true when this
     * machine can show the video inside the app (the caller then lets mpv open
     * its own window, which is adopted by [attachProcess]); false means the
     * video will stay in a window of its own.
     */
    fun open(
        title: String,
        hasNext: Boolean,
        next: (() -> Unit)?,
        position: ((Long, Long) -> Unit)?,
        closed: (() -> Unit)?,
        sources: List<StreamSource> = emptyList(),
        sourceName: String = "",
        onPickSource: ((StreamSource) -> Unit)? = null,
    ): Boolean {
        onNext = next
        onPosition = position
        onClosed = closed
        this.sources = sources
        this.onPickSource = onPickSource
        // Exactly what mpv is given as `--title=…` (see DesktopPlayer), so the
        // window can be found by title if the process-id search fails.
        this.mpvTitle = title.take(200).replace('\n', ' ')
        return Fx.runBlock {
            val host = runCatching { AppShell.playerHost }.getOrNull() ?: return@runBlock false
            teardown = false
            if (root == null) buildUi()
            val ui = root ?: return@runBlock false
            if (!mounted) {
                host.children.setAll(ui)
                mounted = true
            }
            host.isVisible = true
            host.isManaged = true
            installKeys()
            titleLabel?.text = title
            titleLabel?.tooltip = javafx.scene.control.Tooltip(title)
            nextButton?.isVisible = hasNext
            nextButton?.isManaged = hasNext
            renderSources(sourceName)
            releaseSurface()
            refreshFullscreenButton()
            resetForNewStream()
            refreshOverlay()
            // Embedding needs Win32 (to adopt mpv's window) and the app's own
            // window to own it.
            WinShell.available && ownedByApp()
        }
    }

    /** Clears everything that belongs to the previous stream and puts the
     *  loading overlay back up, so the reused layer starts from a clean state. */
    private fun resetForNewStream() {
        duration = 0.0
        lastPosition = 0L
        scrubbing = false
        paused = false
        // A new stream starts with its chrome up: the loading overlay is the only
        // thing on screen and the bars are what say so.
        chromeVisible = true
        runCatching { hideChrome?.stop() }
        sawVideo = false
        loaded = false
        ipcAnswered = false
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        seekBar?.value = 0.0
        seekBar?.isDisable = true
        playButton?.isDisable = true
        nextButton?.isDisable = true
        playButton?.graphic = Icons.of(Icons.PAUSE, 18.0)
        audioMenu?.items?.setAll()
        subMenu?.items?.setAll()
        audioMenu?.isDisable = true
        subMenu?.isDisable = true
        videoFormat = ""
        renderTime()
        setStatus("Starting the player…", busy = true)
        showLoading(if (currentSourceName.isBlank()) "Loading video…" else "Loading $currentSourceName…")
    }

    fun isOpen(): Boolean = mounted

    /** True once mpv reported a real video format — used by the caller's
     *  watchdog to detect a stream whose picture never came up. */
    fun hasVideo(): Boolean = sawVideo

    /**
     * True once the player has answered anything at all over its control pipe.
     *
     * This is the difference between "the stream is broken" and "the player is
     * still busy": on a machine whose GPU has to fall back to software, mpv
     * spends the first seconds of a stream compiling shaders and answers no IPC
     * requests while it does — observed on the CI runner, where the picture
     * came up long after every `get_property` had timed out. The watchdog must
     * not turn that into a "the stream failed" dialog.
     */
    fun ipcWorking(): Boolean = ipcAnswered

    /** True once mpv reported the file as loaded. */
    fun isLoaded(): Boolean = loaded

    /** Live status text — "Starting the player…", why a stream died. It shows as
     *  a chip in the top strip that is absent while there is nothing to say, so
     *  the strip never carries an empty label. */
    fun setStatus(text: String, isError: Boolean = false, busy: Boolean = false) {
        lastStatus = text
        // Whatever the layer has to say ("Volume 40%", "Playback stopped: …") is
        // said in the top strip, so saying it is a reason to have the strip up.
        if (text.isNotBlank()) pokeChrome()
        Fx.run {
            statusIsError = isError && text.isNotBlank()
            statusLabel?.text = text
            statusLabel?.styleClass?.remove("h-danger")
            if (isError) statusLabel?.styleClass?.add("h-danger")
            val show = text.isNotBlank()
            statusChip?.isVisible = show
            statusChip?.isManaged = show
            statusSpinner?.isVisible = busy && show
            statusSpinner?.isManaged = busy && show
        }
    }

    /**
     * Shows a message over the video area, with buttons. The video window is
     * parked while it is up — otherwise the message would be behind the picture.
     * Returns false when there is no player open (the caller then shows a
     * dialog).
     */
    fun showFailure(
        reason: String,
        url: String?,
        retry: (() -> Unit)? = null,
        tryAnyway: (() -> Unit)? = null,
    ): Boolean {
        if (!isOpen()) return false
        val actions = ArrayList<Pair<String, () -> Unit>>()
        retry?.let { r -> actions.add("Retry (fresh link)" to { clearMessage(); r() }) }
        url?.takeIf { it.isNotBlank() }?.let { target ->
            actions.add("Open in browser" to {
                DesktopUi.open(target)
                closeAll()
            })
        }
        tryAnyway?.let { it0 -> actions.add("Play anyway" to { clearMessage(); it0() }) }
        actions.add("Close player" to { closeAll() })
        onCopyReport?.let { copy -> actions.add("Copy player report" to { copy() }) }
        setLoading(false)
        // The strip stops claiming the player is still starting once it has
        // failed — the message says what happened, and a stale "Starting the
        // player…" with a spinner next to it contradicts it.
        setStatus("")
        // Nothing is playing, so the transport stops offering to drive it: a
        // pause button over "the stream didn't load" reads as a lie.
        Fx.run {
            playButton?.isDisable = true
            seekBar?.isDisable = true
            nextButton?.isDisable = true
        }
        message(reason, actions)
        return true
    }

    /** A short note over the video area — used when the video is playing
     *  somewhere this layer cannot show. [actions] gives the user something to
     *  do about it (in practice: copy a report of why it happened). */
    fun note(text: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        if (!isOpen()) return
        message(text, actions)
    }

    /**
     * The note that says the picture is playing in a window of its own — the
     * one note that a LATE adoption has to be able to take back down
     * ([clearNote]), so it is remembered as such.
     */
    fun showOwnWindowNote(text: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        if (!isOpen()) return
        ownWindowNoteUp = true
        adoptTrace += stamp("note shown: the video is playing in a window of its own")
        message(text, actions)
    }

    /** Takes [showOwnWindowNote]'s explanation back down, because the window WAS
     *  adopted after all — the picture is inside the app now, so an explanation
     *  of why it is not would be a lie sitting over it. */
    fun clearNote() {
        if (!ownWindowNoteUp) return
        ownWindowNoteUp = false
        adoptTrace += stamp("late adoption: taking the note back down")
        Fx.run {
            clearMessage()
            if (!sawVideo) setStatus("Opening the stream…", busy = true)
        }
    }

    /** Closes the player layer without notifying the owner (used while tearing
     *  playback down from the outside). */
    fun closeAll() {
        Fx.run { closeInternal() }
    }

    // ── the video window ────────────────────────────────────────────────────

    /** The app's own window handle, which owns the video surface. */
    private fun ownedByApp(): Boolean {
        ownerHwnd?.let { if (WinShell.windowExists(it)) return true }
        val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)
        // Prefer the stage by title: the process can own other windows (a
        // WebView surface, a popup) and only the stage must be the owner.
        val title = runCatching { AppShell.stage.title }.getOrNull().orEmpty()
        val found = (if (title.isBlank()) null else WinShell.findWindowOf(pid, title))
            ?: WinShell.findWindowOf(pid) ?: return false
        ownerHwnd = found
        return true
    }

    /**
     * Adopts the window of a freshly launched mpv: finds it, strips its chrome
     * and glues it over the video area. Runs off the FX thread (mpv creates its
     * window a moment after launch).
     *
     * [onFailed] fires once, if the window has not been adopted by
     * [FIRST_WAIT_MS] — the caller then explains where the picture went.
     * [onAdopted] fires once if the window shows up LATER and is adopted then,
     * so a slow window is not the end of the story.
     */
    fun attachProcess(pid: Long, onFailed: (() -> Unit)? = null, onAdopted: (() -> Unit)? = null) {
        adoptTrace.clear()
        adoptStartedAt = System.currentTimeMillis()
        candidateHwnd = 0L
        if (pid <= 0L || !WinShell.available) {
            adoptTrace += stamp(
                "cannot embed on this machine: " +
                    (if (pid <= 0L) "no player process id" else "Win32 window lookups unavailable"),
            )
            onFailed?.let { cb -> Fx.run { cb() } }
            return
        }
        Thread({ adoptLoop(pid, onFailed, onAdopted) }, "hikari-video-adopt").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits for the player's window and glues it over the video area.
     *
     * mpv is launched with `--force-window=immediate`, so its window exists from
     * startup and this succeeds almost immediately. It does NOT give up after
     * the first wait, though, because giving up is the bug this replaces: the
     * layer used to stop looking after ten seconds, announce that it could not
     * draw the video inside the app, and leave a working player in a window of
     * its own for the rest of the stream — even if the window turned up a second
     * later. The loop now runs for as long as the player process lives.
     */
    private fun adoptLoop(pid: Long, onFailed: (() -> Unit)?, onAdopted: (() -> Unit)?) {
        var announced = false
        var lastLoggedNoWindow = 0L
        var lastCandidate = 0L
        var lastOutcome: Boolean? = null
        while (true) {
            if (embedded) return
            if (!playerAlive(pid)) {
                adoptTrace += stamp("the player process is gone — stopping the wait")
                return
            }
            val handle = findPlayerWindow(pid)
            if (handle != null && handle != lastCandidate) {
                lastCandidate = handle
                candidateHwnd = handle
                lastOutcome = null
                adoptTrace += stamp(
                    "found a window for the player: 0x" + java.lang.Long.toHexString(handle) +
                        " rect=" + (WinShell.windowRect(handle)?.joinToString(",") ?: "?"),
                )
            }
            if (handle != null) {
                val ok = runCatching { Fx.runBlock { adopt(handle) } }.getOrDefault(false)
                // Once per CHANGE of outcome, never once per attempt: this loop
                // runs many times a second and the report has to stay readable.
                if (ok != lastOutcome) {
                    lastOutcome = ok
                    adoptTrace += stamp(
                        "dress and glue 0x" + java.lang.Long.toHexString(handle) + " -> " +
                            (if (ok) "adopted" else "refused: " + adoptRefusal),
                    )
                }
                if (ok) {
                    if (announced) onAdopted?.let { cb -> Fx.run { cb() } }
                    return
                }
            } else {
                val now = System.currentTimeMillis()
                if (now - lastLoggedNoWindow > 2_000L) {
                    lastLoggedNoWindow = now
                    adoptTrace += stamp("no window of the player's own yet (" + ageMs() + " ms in)")
                }
            }
            val waited = ageMs()
            if (!announced && waited >= FIRST_WAIT_MS) {
                announced = true
                adoptTrace += stamp("no window to adopt after " + waited + " ms — saying so")
                adoptTrace += WinShell.describeWindows(pid).map { "  window: " + it }
                onFailed?.let { cb -> Fx.run { cb() } }
            }
            // Slow down once the fast window is behind us: a window that did not
            // appear in the first seconds is worth a look every half second, and
            // after a minute every two — this thread can outlive a long stream.
            val nap = when {
                waited < FIRST_WAIT_MS -> 120L
                waited < 60_000L -> 500L
                else -> 2_000L
            }
            runCatching { Thread.sleep(nap) }
        }
    }

    /** True while the process behind [pid] is still running. Unknown is treated
     *  as alive: losing the ability to ask must not end the wait. */
    private fun playerAlive(pid: Long): Boolean =
        runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(true) }.getOrDefault(true)

    /** The player's own window: mpv's VIDEO window (its class is the one thing
     *  that tells it apart from the helper windows mpv creates beside it — the
     *  observed failure was the app adopting `mpv-smtc` while mpv drew the
     *  picture in its real window), with mpv's own title (`--title=…`) winning
     *  outright when one of its windows carries it.
     *
     *  The process id is never dropped from the search: a window that merely
     *  happens to share the episode's title is somebody else's window, and
     *  dressing THAT up as the video surface would wreck it. */
    private fun findPlayerWindow(pid: Long): Long? {
        val title = mpvTitle
        return WinShell.findWindowOf(
            pid,
            titleHint = title.takeIf { it.isNotBlank() },
            preferClass = WinShell.MPV_VIDEO_CLASS,
        )
    }

    /**
     * Dresses mpv's window up and glues it over the video area. Runs on the FX
     * thread. False means it could not be adopted yet (retry) — and
     * [adoptRefusal] says which step refused, because "refused" on its own is
     * not something anyone can act on.
     */
    private fun adopt(handle: Long): Boolean {
        adoptRefusal = ""
        if (!WinShell.windowExists(handle)) {
            adoptRefusal = "that window no longer exists"
            return false
        }
        // The app's own window has to be known before anything is glued: without
        // it the video window has no owner and nowhere to be placed, so
        // "adopting" would only strip mpv's chrome and leave the picture sitting
        // wherever mpv put it.
        if (!ownedByApp()) {
            adoptRefusal = "the app's own window could not be identified"
            return false
        }
        val owner = ownerHwnd
        adoptTrace += stamp("the app's own window is " + hwndText(owner))
        if (!WinShell.restyleAsVideoSurface(handle, owner ?: 0L)) {
            adoptRefusal = "Win32 refused to restyle mpv's window"
            return false
        }
        surfaceHwnd = handle
        embedded = true
        safeSync()
        // The window is up, so the wait for a picture is a wait on mpv, not on
        // the window manager.
        if (loadingBox?.isVisible != true && !sawVideo) setStatus("Opening the stream…", busy = true)
        return true
    }

    /** Millis since [attachProcess] started looking. */
    private fun ageMs(): Long =
        if (adoptStartedAt == 0L) 0L else System.currentTimeMillis() - adoptStartedAt

    private fun stamp(message: String): String = "+" + ageMs() + "ms " + message

    private fun hwndText(hwnd: Long?): String =
        if (hwnd == null || hwnd == 0L) "none" else "0x" + java.lang.Long.toHexString(hwnd)

    /**
     * Everything this layer knows about putting the picture inside the app
     * window, as text — what the player's "Copy player report" button hands
     * over.
     *
     * It exists because this failure cannot be seen from outside: "the video is
     * in a window of its own" has a dozen possible causes (no Win32, no window
     * yet, a window Windows will not let us restyle, no owner handle) that look
     * identical on screen and are told apart only by asking the machine that
     * failed.
     */
    fun windowReport(): String = buildString {
        append("player layer\n")
        append("  embedded inside the app: ").append(embedded).append('\n')
        append("  video surface hwnd: ").append(hwndText(surfaceHwnd)).append('\n')
        append("  app-window hwnd: ").append(hwndText(ownerHwnd)).append('\n')
        append("  candidate hwnd: ").append(hwndText(candidateHwnd)).append('\n')
        append("  mpv window title: \"").append(mpvTitle).append("\"\n")
        append("  Win32 window lookups: ").append(WinShell.available).append('\n')
        append("  video seen: ").append(sawVideo)
            .append("   file loaded: ").append(loaded)
            .append("   ipc answered: ").append(ipcAnswered).append('\n')
        append("  overlay up: ").append(overlayUp)
            .append("   own-window note up: ").append(ownWindowNoteUp).append('\n')
        append("  status chip: \"").append(lastStatus).append("\"\n")
        append("  adoption watched for: ").append(ageMs()).append(" ms\n")
        if (adoptRefusal.isNotBlank()) append("  last refusal: ").append(adoptRefusal).append('\n')
        append("  adoption trace (").append(adoptTrace.size).append(" lines):\n")
        synchronized(adoptTrace) { adoptTrace.forEach { append("    ").append(it).append('\n') } }
    }

    /** True while the video is glued inside the app. */
    fun isEmbedded(): Boolean = embedded

    /** Drops the adopted window (never closes it — mpv owns it) so the next
     *  stream starts from a clean surface. */
    private fun releaseSurface() {
        surfaceHwnd = null
        embedded = false
    }

    /**
     * Moves the adopted video window far off-screen and hides it, from ANY
     * thread. Used while the player — or the whole app — is being torn down:
     * the video is another process's window, so if the app simply stops
     * touching it, whatever it last painted (a frozen frame) sits over the
     * desktop until that process dies. Hiding it makes the teardown instant and
     * visible, whatever the kill costs.
     */
    fun parkSurface() {
        val h = surfaceHwnd ?: return
        runCatching { WinShell.parkAndHide(h) }
        surfaceHwnd = null
        embedded = false
    }

    private fun safeSync() {
        runCatching { syncSurface() }
    }

    /**
     * Keeps the video window glued to the video area — or parked far off-screen
     * at its proper size while an overlay covers the area.
     */
    private fun syncSurface() {
        val hwnd = surfaceHwnd ?: return
        if (!WinShell.windowExists(hwnd)) {
            releaseSurface()
            return
        }
        val area = videoArea
        if (area.scene == null || area.width < 16.0 || area.height < 16.0) return
        val scale = screenScale()
        val w = (area.width * scale).roundToInt()
        val h = (area.height * scale).roundToInt()
        if (w < 16 || h < 16) return
        val target = if (overlayUp) {
            intArrayOf(WinShell.PARKED_X, WinShell.PARKED_Y, w, h)
        } else {
            val owner = ownerHwnd ?: return
            // The app's window is undecorated, so its window rectangle IS its
            // client rectangle: the video area's offset inside the client area
            // plus that origin is where the video belongs on screen. JavaFX
            // reports the offset in logical pixels, Win32 wants physical ones,
            // which is what [scale] is for.
            val appRect = WinShell.windowRect(owner) ?: return
            val local = area.localToScene(0.0, 0.0)
            intArrayOf(
                appRect[0] + (local.x * scale).roundToInt(),
                appRect[1] + (local.y * scale).roundToInt(),
                w,
                h,
            )
        }
        // Windows itself, and mpv, move windows behind the app's back (mpv
        // resizes its own window when a file's dimensions become known), so the
        // LIVE rectangle is what this compares against: a window that drifted
        // gets put back, and a window that is already right costs one cheap
        // GetWindowRect per tick. Caching the last placement would leave the
        // video at whatever size mpv decided on.
        val live = WinShell.windowRect(hwnd)
        if (live != null && live.contentEquals(target)) return
        WinShell.placeWindow(hwnd, target[0], target[1], target[2], target[3])
    }

    private fun screenScale(): Double = runCatching {
        val stage = runCatching { AppShell.stage }.getOrNull() ?: return@runCatching 1.0
        val screen = Screen.getScreensForRectangle(stage.x, stage.y, 1.0, 1.0).firstOrNull()
            ?: Screen.getPrimary()
        screen.outputScaleX.takeIf { it > 0.0 } ?: 1.0
    }.getOrDefault(1.0)

    // ── UI ──────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val msgLabel = Label("").apply {
            styleClass.add("player-msg")
            isWrapText = true
            maxWidth = 560.0
            minWidth = 0.0
            textAlignment = TextAlignment.CENTER
        }
        val msgActions = HBox(8.0).apply { alignment = Pos.CENTER }
        val msgBox = VBox(14.0, msgLabel, msgActions).apply {
            styleClass.add("player-msg-box")
            alignment = Pos.CENTER
            isVisible = false
            isManaged = false
        }

        // The loading overlay: the only thing on screen between "Play" and the
        // first decoded frame.
        val loadSpinner = ProgressIndicator().apply {
            styleClass.add("player-load-spinner")
            prefWidth = 54.0
            prefHeight = 54.0
            minWidth = 54.0
            minHeight = 54.0
            maxWidth = 54.0
            maxHeight = 54.0
        }
        val loadTitle = Label("Loading video…").apply { styleClass.add("player-load-title") }
        val loadBox = VBox(16.0, loadSpinner, loadTitle).apply {
            styleClass.add("player-load")
            alignment = Pos.CENTER
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }

        videoArea.children.setAll(loadBox, msgBox)

        // ── top strip: back · title · status · window controls ──────────────
        val back = roundButton(Icons.CHEVRON_LEFT, "Back to the app (Esc)", 16.0, 34.0) { requestClose() }

        val title = Label("").apply {
            styleClass.add("player-title")
            maxWidth = 380.0
            minWidth = 60.0
        }
        titleLabel = title

        val spinner = ProgressIndicator().apply {
            styleClass.add("spinner")
            prefWidth = 13.0
            prefHeight = 13.0
            minWidth = 13.0
            minHeight = 13.0
            maxWidth = 13.0
            maxHeight = 13.0
            isVisible = false
            isManaged = false
        }
        statusSpinner = spinner

        val status = Label("").apply {
            styleClass.add("player-status")
            minWidth = 0.0
            maxWidth = 320.0
        }
        statusLabel = status
        val statusBox = HBox(6.0, spinner, status).apply {
            alignment = Pos.CENTER_LEFT
            isVisible = false
            isManaged = false
        }
        statusChip = statusBox

        val stripSpacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val strip = HBox(10.0, back, title, statusBox, stripSpacer).apply {
            styleClass.add("player-top")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        // The strip is the window's title area while the player is up (it covers
        // the app's own top bar), so it drags the window like the app's does.
        // Only the empty parts do, never the controls.
        runCatching {
            AppShell.makeDraggable(title)
            AppShell.makeDraggable(stripSpacer)
        }
        runCatching { strip.children.add(AppShell.windowControls()) }
        topStrip = strip

        // ── control bar: transport, then the pickers ────────────────────────
        val play = roundButton(Icons.PAUSE, "Play / pause (space)", 18.0, 38.0) {
            runCatching { ipc?.post("cycle", "pause") }
        }.apply { styleClass.add("player-play") }
        playButton = play

        val time = Label("00:00").apply { styleClass.add("player-time") }
        timeLabel = time
        val total = Label("/ 00:00").apply {
            styleClass.add("player-time")
            styleClass.add("player-time-total")
        }
        totalLabel = total

        val seek = Slider(0.0, 1.0, 0.0).apply {
            styleClass.add("player-seek")
            isDisable = true
            cursor = Cursor.HAND
            // Clicking anywhere on the bar scrubs there, and the readout follows
            // the pointer while it is dragged; the seek itself is posted once the
            // button is released, so a drag costs ONE seek instead of one per
            // pixel.
            setOnMousePressed {
                scrubbing = true
                previewScrub()
            }
            setOnMouseDragged { previewScrub() }
            setOnMouseReleased {
                scrubbing = false
                seekTo(value)
            }
        }
        seekBar = seek

        // The Source picker: EVERY server the title offered, switchable without
        // leaving the player. This is the button the Android player has and the
        // reason sources are handed to this layer at all.
        val source = MenuButton("Source").apply {
            styleClass.addAll("player-pill", "player-pill-accent")
            graphic = Icons.of(Icons.FILM, 14.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip("Pick another source / server"))
        }
        sourceMenu = source

        val audio = MenuButton("Audio").apply {
            styleClass.add("player-pill")
            graphic = Icons.of(Icons.VOLUME, 14.0)
            isFocusTraversable = false
            isDisable = true
            javafx.scene.control.Tooltip.install(this, Ui.tooltip("Audio track"))
        }
        audioMenu = audio

        val subs = MenuButton("Subs").apply {
            styleClass.add("player-pill")
            graphic = Icons.of(Icons.SUBTITLES, 14.0)
            isFocusTraversable = false
            isDisable = true
            javafx.scene.control.Tooltip.install(this, Ui.tooltip("Subtitle track"))
        }
        subMenu = subs

        val next = roundButton(Icons.SKIP_NEXT, "Next episode (N)", 16.0, 34.0) { onNext?.invoke() }.apply {
            isVisible = false
            isManaged = false
        }
        nextButton = next

        val full = roundButton(Icons.FULLSCREEN, "Fullscreen (F)", 16.0, 34.0) { toggleFullscreen() }
        fullscreenButton = full

        val sepA = separator()
        val sepB = separator()
        barSeparators = listOf(sepA, sepB)
        // Transport on the left, pickers on the right, and the flexible gap
        // BETWEEN them. The seek bar used to be the growing child of this row,
        // so it stretched from the time label all the way to the Source button —
        // at every window size the two were in each other's lap, and on a
        // maximised window the bar ran the width of the screen. Its length is
        // now chosen in [applyResponsive] and the gap takes whatever is left.
        val barGap = Region().apply {
            HBox.setHgrow(this, Priority.ALWAYS)
            minWidth = 12.0
        }
        val bar = HBox(10.0).apply {
            styleClass.add("player-bar")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            children.addAll(
                play, time, seek, total,
                barGap,
                sepA,
                source, audio, subs,
                sepB,
                next, full,
            )
        }
        bottomBar = bar

        root = BorderPane().apply {
            styleClass.add("player-root")
            top = strip
            center = videoArea
            bottom = bar
        }

        // ── the bars are shown, not always there ────────────────────────────
        // A move anywhere inside the layer counts as activity, and it is an
        // event FILTER, so it fires for one over a slider, a menu or a button
        // too. Moves over the picture itself never arrive here — the picture is
        // a separate Win32 window — and that half is what [pollPointer] is for.
        root?.addEventFilter(MouseEvent.MOUSE_MOVED) { pokeChrome() }
        root?.addEventFilter(MouseEvent.MOUSE_PRESSED) { pokeChrome() }
        hideChrome = PauseTransition(Duration.millis(CHROME_IDLE_MS)).apply {
            setOnFinished { setChromeVisible(false) }
        }

        messageLabel = msgLabel
        messageActions = msgActions
        messageBox = msgBox
        loadingBox = loadBox

        // The control bar has more controls than a phone-width window can show
        // on one line (the seek bar used to be squeezed to a dot and the
        // fullscreen button pushed off the edge) — see [applyResponsive].
        applyResponsive()
        root?.widthProperty()?.addListener { _, _, _ -> applyResponsive() }

        // Anything that moves or resizes the video area has to move the window
        // glued to it.
        videoArea.layoutBoundsProperty().addListener { _, _, _ -> safeSync() }
        val stage = runCatching { AppShell.stage }.getOrNull()
        if (stage != null) {
            stage.xProperty().addListener { _, _, _ -> safeSync() }
            stage.yProperty().addListener { _, _, _ -> safeSync() }
            stage.widthProperty().addListener { _, _, _ -> safeSync() }
            stage.heightProperty().addListener { _, _, _ -> safeSync() }
            stage.fullScreenProperty().addListener { _, _, _ -> refreshFullscreenButton(); pokeChrome() }
        }
        syncTimer = Timeline(
            KeyFrame(Duration.millis(SYNC_MS), EventHandler<ActionEvent> {
                safeSync()
                pollPointer()
            }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    /**
     * Fits the two bars to the width the window actually has.
     *
     * The player's bar carries thirteen things (transport, a seek bar, three
     * pickers, the window controls) — on a 460px-wide window that cannot fit,
     * and what used to happen was the worst outcome: the seek bar collapsed to
     * a purple dot and the fullscreen button was pushed off the right edge.
     * The labels go first, then the pickers that are only occasionally needed,
     * so the transport controls and a USABLE seek bar always survive.
     */
    private fun applyResponsive() {
        val width = runCatching { root?.width ?: 0.0 }.getOrDefault(0.0)
        if (width <= 0.0) return
        val narrow = width < 780.0
        val tiny = width < 560.0
        sourceMenu?.let { it.text = if (narrow) "" else "Source" }
        audioMenu?.let { it.text = if (narrow) "" else "Audio" }
        subMenu?.let { it.text = if (narrow) "" else "Subs" }
        // Source stays (switching server is the first thing to reach for when a
        // stream dies); audio/subtitle tracks live behind the same kind of
        // control and are one tap away once there is room for them.
        for (menu in listOf(audioMenu, subMenu)) {
            menu ?: continue
            menu.isVisible = !tiny
            menu.isManaged = !tiny
        }
        for (sep in barSeparators) {
            sep.isVisible = !tiny
            sep.isManaged = !tiny
        }
        // The title strip must not eat the window controls: on a phone-width
        // window it gives them up instead of drawing under them.
        titleLabel?.let { it.maxWidth = if (narrow) 200.0 else 380.0 }
        statusLabel?.let { it.maxWidth = if (narrow) 170.0 else 320.0 }
        // The seek bar's length is what is LEFT of the row after the controls
        // that must always be there (transport buttons, three pickers, the window
        // buttons and their gaps ≈ 480px), capped so it never grows into a
        // corner-to-corner line: on a maximised window the two dozen other things
        // on the row leave plenty of room, and on a narrow one the length is the
        // first thing to go. Its floor (90px) is what keeps it scrubbable when
        // the window is at its smallest.
        seekBar?.let { it1 ->
            val length = (width - 480.0).coerceIn(90.0, 620.0)
            it1.minWidth = 90.0
            it1.prefWidth = length
            it1.maxWidth = length
        }
        // Below 620px the row is carryable without the total-time label: the
        // elapsed time and the seek bar still say where playback is, and dropping
        // the label is what stops the remaining controls being squeezed into each
        // other (the seek bar keeps its own length either way).
        totalLabel?.let { it.isVisible = width >= 620.0; it.isManaged = width >= 620.0 }
    }

    private fun roundButton(icon: String, tooltip: String, iconSize: Double, diameter: Double, onClick: () -> Unit): Button =
        Button().apply {
            styleClass.add("player-round")
            setMinSize(diameter, diameter)
            setMaxSize(diameter, diameter)
            setPrefSize(diameter, diameter)
            graphic = Icons.of(icon, iconSize)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip(tooltip))
            setOnAction { runCatching { onClick() } }
        }

    private fun separator(): Region = Region().apply {
        styleClass.add("player-sep")
        prefWidth = 1.0
        minWidth = 1.0
        maxWidth = 1.0
        prefHeight = 20.0
        minHeight = 20.0
        maxHeight = 20.0
    }

    private fun requestClose() {
        if (teardown) return
        closeInternal()
        onClosed?.invoke()
    }

    private fun closeInternal() {
        teardown = true
        runCatching { syncTimer?.stop() }
        syncTimer = null
        runCatching { hideChrome?.stop() }
        uninstallKeys()
        runCatching { ipc?.close() }
        ipc = null
        // FIRST: take the video window out of the picture. It belongs to mpv,
        // not to JavaFX, so a half-finished teardown (or a slow kill) would
        // otherwise leave its last frame frozen over the app.
        parkSurface()
        // Leaving the app fullscreen with no player in it would strand the user
        // on a screen with no controls.
        runCatching {
            if (AppShell.stage.isFullScreen) AppShell.stage.isFullScreen = false
        }
        overlayUp = true
        duration = 0.0
        scrubbing = false
        paused = false
        runCatching {
            AppShell.playerHost.isVisible = false
            AppShell.playerHost.isManaged = false
        }
    }

    /** Only the fullscreen button's icon depends on the stage's own fullscreen
     *  state now. The bars are governed by [applyChrome] in every mode, so
     *  fullscreen is no longer a place with no controls at all: moving the
     *  pointer there brings them back, exactly as it does in a window. */
    private fun refreshFullscreenButton() {
        val fs = runCatching { AppShell.stage.isFullScreen }.getOrDefault(false)
        fullscreenButton?.graphic = Icons.of(if (fs) Icons.FULLSCREEN_EXIT else Icons.FULLSCREEN, 16.0)
    }

    // ── showing and hiding the bars ─────────────────────────────────────────

    /**
     * Brings the bars back and restarts their countdown.
     *
     * Called for a mouse move, a click and a key press. While the picture is a
     * separate Win32 window glued over the video area, the moves that matter
     * most — the ones over the picture — never reach JavaFX at all, which is
     * why [pollPointer] calls this too.
     */
    fun pokeChrome() {
        Fx.run {
            setChromeVisible(true)
            scheduleChromeHide()
        }
    }

    /** Starts (or restarts) the countdown — unless the bars are the only way to
     *  drive playback right now (paused, still loading, or explaining a
     *  failure), in which case they stay. */
    private fun scheduleChromeHide() {
        if (!mounted || paused || overlayUp) return
        hideChrome?.playFromStart()
    }

    private fun setChromeVisible(visible: Boolean) {
        val wanted = visible || paused || overlayUp
        if (wanted == chromeVisible) {
            applyChrome()
            return
        }
        chromeVisible = wanted
        applyChrome()
    }

    /**
     * The one place that puts the bars on screen or takes them away. Hiding them
     * gives their height back to the video area, which is the entire point: the
     * picture grows into the space instead of being permanently wrapped in
     * controls. [safeSync] then moves the video surface to the new rectangle.
     */
    private fun applyChrome() {
        val show = chromeVisible || paused || overlayUp
        if (!show) runCatching { hideChrome?.stop() }
        topStrip?.let { it.isVisible = show; it.isManaged = show }
        bottomBar?.let { it.isVisible = show; it.isManaged = show }
        // The pointer is the only thing left on the picture when the bars are
        // away, and a player that keeps an arrow parked in the middle of the
        // frame is not a player. (mpv hides its own cursor over the video; this
        // covers the strip/bar areas, which the app draws.)
        root?.cursor = if (show) Cursor.DEFAULT else Cursor.NONE
        safeSync()
    }

    /**
     * Counts pointer movement — and a click — inside the app's window as
     * activity, and brings the bars back for it.
     *
     * Polling is the only option: the picture is another process's window, so it
     * consumes the mouse messages for its whole rectangle and the layer never
     * sees them. `GetCursorPos` is a cheap call that needs no hook, and the
     * layer already has a 120 ms timer running for the video surface.
     */
    private fun pollPointer() {
        if (!mounted) return
        val p = WinShell.cursorPos()
        if (p != null) {
            val moved = p[0] != lastCursorX || p[1] != lastCursorY
            lastCursorX = p[0]
            lastCursorY = p[1]
            if (moved && pointerInWindow()) pokeChrome()
        }
        // A click on the picture is swallowed by mpv's window, so it is noticed
        // here: while the bars are away, the first click brings them back rather
        // than doing nothing at all.
        val down = WinShell.leftButtonDown() ?: return
        val pressed = down && !leftWasDown
        leftWasDown = down
        if (pressed && !chromeVisible && pointerInWindow()) pokeChrome()
    }

    /** True while the pointer is inside the app's own window. */
    private fun pointerInWindow(): Boolean {
        val owner = ownerHwnd ?: return false
        val r = WinShell.windowRect(owner) ?: return false
        val p = WinShell.cursorPos() ?: return false
        return p[0] >= r[0] && p[1] >= r[1] && p[0] < r[0] + r[2] && p[1] < r[1] + r[3]
    }

    /**
     * Harness hook (see UiShotTest): makes the layer believe the picture is up,
     * so the playing chrome can be captured without launching mpv.
     */
    fun previewPlaying() {
        loaded = true
        ipcAnswered = true
        sawVideo = true
        Fx.run {
            setLoading(false)
            clearMessage()
            playButton?.isDisable = false
            seekBar?.isDisable = false
            seekBar?.value = 0.34
            lastPosition = 125_000L
            // Seconds, like every other duration the layer holds: 6:07.
            duration = 367.0
            renderTime()
            refreshFullscreenButton()
            pokeChrome()
        }
    }

    /** Harness hook (see UiShotTest): drive the bars directly, because a build
     *  agent's pointer never moves and the real trigger cannot fire there. */
    fun previewChrome(visible: Boolean) {
        Fx.run { setChromeVisible(visible) }
    }

    // ── overlays over the video ─────────────────────────────────────────────

    private fun showLoading(text: String) {
        loadingBox?.isVisible = true
        loadingBox?.isManaged = true
        loadingBox?.children?.filterIsInstance<Label>()?.lastOrNull()?.text = text
        setLoading(true)
    }

    private fun setLoading(value: Boolean) {
        loadingBox?.isVisible = value
        loadingBox?.isManaged = value
        refreshOverlay()
    }

    /** The single place that decides whether the video window should be in the
     *  way of the app's own content. */
    private fun refreshOverlay() {
        overlayUp = (loadingBox?.isVisible == true) || (messageBox?.isVisible == true)
        // Loading or explaining something puts the bars back, and holds them
        // there ([applyChrome] keeps them up while an overlay is); the picture
        // gets its full size back the moment the overlay goes.
        applyChrome()
    }

    private fun message(text: String, actions: List<Pair<String, () -> Unit>>) {
        Fx.run {
            val box = messageBox ?: return@run
            messageLabel?.text = text
            messageActions?.children?.setAll(
                *actions.mapIndexed { index, (label, action) ->
                    Button(label).apply {
                        styleClass.add("btn")
                        // Only the FIRST action is the accent one, so the row has
                        // a hierarchy: "Retry (fresh link)" is what the user
                        // wants, "Close player" is not the same weight as it.
                        styleClass.add(if (index == 0) "btn-primary" else "btn-ghost")
                        isFocusTraversable = false
                        setOnAction { action() }
                    }
                }.toTypedArray()
            )
            box.isVisible = true
            box.isManaged = true
            refreshOverlay()
        }
    }

    private fun clearMessage() {
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        refreshOverlay()
    }

    // ── the source picker ───────────────────────────────────────────────────

    /** Fills the Source menu with every stream the title/episode offered, and
     *  labels the button with the one that is playing. */
    private fun renderSources(current: String) {
        currentSourceName = current
        currentSource = sources.firstOrNull { it.name == current } ?: sources.firstOrNull()
        val menu = sourceMenu ?: return
        val active = currentSource
        menu.text = active?.name?.takeIf { it.isNotBlank() }?.let { shorten(it, 18) } ?: "Source"
        javafx.scene.control.Tooltip.install(
            menu,
            Ui.tooltip(active?.name?.takeIf { it.isNotBlank() } ?: "Pick another source / server"),
        )
        if (sources.size <= 1) {
            // Still shown: a one-source title shows WHAT is playing, which is
            // worth a button (and the menu is simply short).
            menu.items.setAll(
                *sources.map { s -> MenuItem(s.name.ifBlank { s.url.take(60) }).apply { isDisable = true } }.toTypedArray()
            )
            return
        }
        menu.items.setAll(
            *sources.map { s ->
                MenuItem(s.name.ifBlank { s.url.take(60) }).apply {
                    if (s.name == currentSourceName) graphic = Icons.of(Icons.CHECK, 13.0)
                    setOnAction { pickSource(s) }
                }
            }.toTypedArray()
        )
    }

    private fun shorten(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private fun pickSource(source: StreamSource) {
        currentSourceName = source.name
        currentSource = source
        renderSources(source.name)
        // The owner re-launches playback with this source; the layer stays up
        // (it is reused), so switching servers never drops the user out of the
        // player.
        onPickSource?.invoke(source)
    }

    private fun toggleFullscreen() {
        val stage = runCatching { AppShell.stage }.getOrNull() ?: return
        stage.isFullScreen = !stage.isFullScreen
        refreshFullscreenButton()
        pokeChrome()
    }

    // ── seeking ─────────────────────────────────────────────────────────────

    /** Moves the readout to where the pointer is dragging, without telling mpv
     *  yet — the picture jumps once, on release. */
    private fun previewScrub() {
        val d = duration
        if (d <= 0) return
        lastPosition = ((seekBar?.value ?: 0.0) * d * 1000).toLong()
        renderTime()
    }

    /** Seeks to a 0..1 position and moves the readout there immediately. */
    private fun seekTo(fraction: Double) {
        val d = duration
        if (d <= 0) return
        val pos = fraction.coerceIn(0.0, 1.0)
        lastPosition = (pos * d * 1000).toLong()
        renderTime()
        runCatching { ipc?.post("seek", pos * d, "absolute") }
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    /** While the player is up it owns the keyboard: the app's own shortcuts
     *  (Ctrl+1…4, Ctrl+K, Escape-to-go-back) must not fire behind it. */
    private fun installKeys() {
        if (keyFilter != null) return
        val scene = runCatching { AppShell.playerHost.scene }.getOrNull() ?: return
        val filter = EventHandler<KeyEvent> { e -> runCatching { handleKey(e) } }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, filter)
        keyFilter = filter
    }

    private fun uninstallKeys() {
        val filter = keyFilter ?: return
        keyFilter = null
        runCatching { AppShell.playerHost.scene?.removeEventFilter(KeyEvent.KEY_PRESSED, filter) }
    }

    private fun handleKey(e: KeyEvent) {
        // A key press is activity: a space bar aimed at a picture whose controls
        // are hidden must not be aimed at nothing, so any handled key brings the
        // bars back.
        pokeChrome()
        when (e.code) {
            KeyCode.SPACE -> runCatching { ipc?.post("cycle", "pause") }
            KeyCode.LEFT -> runCatching { ipc?.post("seek", -10, "relative") }
            KeyCode.RIGHT -> runCatching { ipc?.post("seek", 10, "relative") }
            KeyCode.UP -> setVolume(volume + 5)
            KeyCode.DOWN -> setVolume(volume - 5)
            KeyCode.F, KeyCode.F11 -> toggleFullscreen()
            KeyCode.N -> onNext?.invoke()
            KeyCode.ESCAPE -> requestClose()
            else -> return
        }
        e.consume()
    }

    private fun setVolume(next: Double) {
        val clamped = next.coerceIn(0.0, 130.0)
        volume = clamped
        runCatching { ipc?.setProperty("volume", clamped) }
        setStatus("Volume ${clamped.roundToInt()}%", busy = false)
    }

    // ── transport, driven by mpv's JSON IPC ─────────────────────────────────

    /** Hands the IPC channel over once mpv's pipe is up. */
    fun attachIpc(handle: MpvIpc) {
        ipc = handle
        handle.onProperty = { name, value -> runCatching { onProperty(name, value) } }
        handle.onEvent = { event, data -> runCatching { onEvent(event, data) } }
        val observations = listOf(
            "time-pos" to 1L,
            "duration" to 2L,
            "pause" to 3L,
            "volume" to 4L,
            "eof-reached" to 5L,
            "track-list" to 6L,
            "video-format" to 7L,
            "vo-configured" to 8L,
        )
        // `observe` waits for a reply, so it must not run on the FX thread.
        Thread(
            { observations.forEach { (name, id) -> runCatching { handle.observe(name, id) } } },
            "hikari-mpv-observe",
        ).apply { isDaemon = true; start() }
    }

    /** Feeds one mpv property change in. Called from the IPC reader thread —
     *  everything it touches is hopped to the FX thread. */
    fun onProperty(name: String, value: Any?) {
        ipcAnswered = true
        when (name) {
            "duration" -> {
                duration = (value as? Number)?.toDouble() ?: 0.0
                Fx.run {
                    seekBar?.isDisable = duration <= 0.0
                    renderTime()
                }
            }
            "time-pos" -> {
                val pos = (value as? Number)?.toDouble() ?: 0.0
                lastPosition = (pos * 1000).toLong()
                Fx.run {
                    if (!scrubbing && duration > 0) seekBar?.value = (pos / duration).coerceIn(0.0, 1.0)
                    renderTime()
                }
                onPosition?.invoke(lastPosition, (duration * 1000).toLong())
            }
            "pause" -> {
                paused = value == true
                Fx.run {
                    playButton?.graphic = Icons.of(if (paused) Icons.PLAY else Icons.PAUSE, 18.0)
                    // Paused means the bars stay (they are how playback gets
                    // started again); playing means they get out of the way.
                    if (paused) runCatching { hideChrome?.stop() } else scheduleChromeHide()
                    applyChrome()
                }
            }
            "volume" -> volume = (value as? Number)?.toDouble() ?: 100.0
            "track-list" -> renderTracks(value)
            "vo-configured" -> if (value == true) markVideoUp()
            "video-format" -> {
                val fmt = value?.toString().orEmpty()
                // Only a REAL format means the video output is up: observing the
                // property delivers its current (often null) value immediately,
                // and treating that as "playback started" revealed a black
                // surface and labelled a perfectly good stream "Audio only".
                if (fmt.isNotBlank()) {
                    videoFormat = fmt
                    markVideoUp()
                }
            }
            "eof-reached" -> {
                if (value == true) onNext?.invoke()
            }
        }
    }

    fun onEvent(event: String, data: JSONObject) {
        if (event != "ipc-closed") ipcAnswered = true
        when (event) {
            // mpv has the file open and is about to render: the wait is over.
            "file-loaded", "playback-restart" -> Fx.run { playbackStarted() }
            "end-file", "ipc-closed" -> {
                val fatalPipe = event == "ipc-closed"
                val reason = data.optString("reason").ifBlank { data.optString("error") }
                val realReason = reason.isNotBlank() && reason != "eof" && reason != "quit" && reason != "stop"
                if (fatalPipe || realReason) {
                    val neverPlayed = !sawVideo
                    Fx.run {
                        setLoading(false)
                        statusSpinner?.isVisible = false
                        statusSpinner?.isManaged = false
                        if (fatalPipe || neverPlayed) {
                            // The player itself is gone (or never managed a frame):
                            // hand it to the owner, which restarts playback on a fresh
                            // link or takes the layer down. Leaving an error line over
                            // a black window is the one thing that must not happen.
                            val cb = onPlayerDied
                            onPlayerDied = null
                            if (!teardown && cb != null) {
                                cb()
                                return@run
                            }
                        }
                        setStatus("Playback stopped: " + reason, isError = true)
                    }
                }
            }
        }
    }

    private fun markVideoUp() {
        playButton?.isDisable = false
        seekBar?.isDisable = false
        nextButton?.isDisable = !(nextButton?.isVisible ?: false)
        if (sawVideo) return
        sawVideo = true
        Fx.run { playbackStarted() }
    }

    /** Called the moment playback is actually up: drops the loading overlay and
     *  reveals the video window. Safe to call more than once. */
    private fun playbackStarted() {
        loaded = true
        if (loadingBox?.isVisible == true) setLoading(false)
        if (embedded) clearMessage()
        // The wait is over: the strip goes back to being just a title. A failure
        // the caller reported is left alone.
        if (!statusIsError) setStatus("")
        titleLabel?.tooltip = javafx.scene.control.Tooltip(
            titleLabel?.text.orEmpty() + if (videoFormat.isBlank()) "" else "\nVideo: $videoFormat"
        )
        // The picture is up: the bars have said what they had to say, and the
        // countdown to getting out of the way starts now.
        pokeChrome()
    }

    private fun renderTime() {
        timeLabel?.text = clock(lastPosition / 1000)
        totalLabel?.text = "/ " + clock(duration.toLong())
    }

    /** One menu row per real track, chosen via `aid`/`sid`. */
    private fun renderTracks(value: Any?) {
        val array = value as? JSONArray ?: run {
            val text = value?.toString() ?: return
            runCatching { JSONArray(text) }.getOrNull() ?: return
        }
        val audio = ArrayList<Pair<String, String>>()
        val subs = ArrayList<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val type = t.optString("type")
            val id = t.optInt("id", -1)
            if (id < 0) continue
            val lang = t.optString("lang").takeIf { it.isNotBlank() && it != "null" }
            val label = t.optString("title").takeIf { it.isNotBlank() }
                ?: lang
                ?: t.optString("codec").takeIf { it.isNotBlank() }
                ?: "Track $id"
            val formatted = if (lang != null && label != lang) "$label ($lang)" else label
            when (type) {
                "audio" -> audio.add(id.toString() to formatted)
                "sub" -> subs.add(id.toString() to formatted)
            }
        }
        Fx.run {
            audioMenu?.items?.setAll(*audio.map { (id, name) ->
                MenuItem(name).apply {
                    setOnAction { runCatching { ipc?.setProperty("aid", id.toInt()) } }
                }
            }.toTypedArray())
            subMenu?.items?.setAll(*buildList {
                add(MenuItem("Off").apply { setOnAction { runCatching { ipc?.setProperty("sid", false) } } })
                subs.forEach { (id, name) ->
                    add(MenuItem(name).apply {
                        setOnAction { runCatching { ipc?.setProperty("sid", id.toInt()) } }
                    })
                }
            }.toTypedArray())
            audioMenu?.isDisable = audio.isEmpty()
            subMenu?.isDisable = subs.isEmpty()
        }
    }

    private fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
