package desktop.player

import com.hikari.app.data.StreamSource
import desktop.fx.DesktopUi
import desktop.fx.Fx
import desktop.ui.AppShell
import desktop.ui.Icons
import desktop.ui.Ui
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.TextAlignment
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's own player — an in-app layer, not a window of its own.
 *
 * mpv is still the engine (it is what actually decodes these CDN/HLS streams),
 * but the app owns the UI: the player is mounted into [AppShell]'s player layer,
 * which covers the whole window (sidebar, top bar and all), so starting
 * playback reads as the app switching to a player view — no second window, no
 * dialog. The only native window involved is the borderless video surface mpv
 * renders into (`--wid`, see [WinShell]): it is owned by the app window, has no
 * chrome, takes no taskbar slot, and is glued exactly over the video area.
 *
 * The layout mirrors the Android app's player: a header with the title and the
 * "what am I watching" badges (elapsed time, quality, server) plus round icon
 * actions, and a control panel under the video with the transport and the
 * feature buttons — Speed, Source, Quality, Audio, Subtitles, Rotate, Skip
 * Intro, Enhance, Fullscreen.
 *
 * A note on the video surface: it is a real window layered ABOVE everything the
 * app draws, so it has to get out of the way while a spinner or an explanation
 * is on screen. It is *shrunk* rather than hidden — hiding the window mpv is
 * rendering into made mpv's video output fail to come up at all (the "sound but
 * no picture" bug), and a 2x2 window is invisible while keeping the output
 * alive. It also must never be created hidden: the child window mpv makes
 * inside it is only findable while every ancestor is visible.
 */
object PlayerWindow {

    /** How often the video surface is glued to the video area. The surface is a
     *  real window, so a move of the app window (a drag, a maximise, a DPI
     *  change) has to be followed — events cover the common cases, this covers
     *  the rest. */
    private const val SYNC_MS = 150.0

    /** The size the surface drops to while an overlay covers the video area. */
    private const val OVERLAY_SIZE = 2

    private var root: BorderPane? = null
    private var mounted = false
    private var videoStage: Stage? = null

    /** The native handle of the surface mpv renders into, and mpv's own child
     *  window inside it (found once mpv attaches, then kept filled). */
    private var videoHwnd: Long? = null
    private var mpvChild: Long? = null
    private var childW = 0
    private var childH = 0
    private var lastChildProbe = 0L

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
        minHeight = 140.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }

    private var titleLabel: Label? = null
    private var timeChip: Label? = null
    private var qualityChip: Label? = null
    private var serverChip: Label? = null
    private var statusLabel: Label? = null
    private var statusSpinner: ProgressIndicator? = null
    private var seekBar: Slider? = null
    private var playButton: Button? = null
    private var prevButton: Button? = null
    private var nextButton: Button? = null
    private var volumeSlider: Slider? = null
    private var volumeIcon: Label? = null
    private var speedMenu: MenuButton? = null
    private var sourceMenu: MenuButton? = null
    private var qualityMenu: MenuButton? = null
    private var audioMenu: MenuButton? = null
    private var subMenu: MenuButton? = null
    private var rotateButton: Button? = null
    private var skipIntroButton: Button? = null
    private var enhanceButton: Button? = null
    private var fullscreenButton: Button? = null
    private var favButton: Button? = null
    private var downloadButton: Button? = null
    private var pinButton: Button? = null
    private var lockButton: Button? = null
    private var featureRow: HBox? = null
    private var header: HBox? = null
    private var messageLabel: Label? = null
    private var messageActions: HBox? = null
    private var messageBox: VBox? = null
    private var loadingBox: VBox? = null
    private var loadingLabel: Label? = null
    private var loadingSub: Label? = null

    private var duration = 0.0
    private var scrubbing = false
    private var lastPosition = 0L
    private var volume = 100.0
    private var paused = false
    private var rotation = 0
    private var enhanced = false
    private var locked = false
    private var pinned = false
    private var muted = false

    /** Set while the loading overlay or an explanation covers the video area, so
     *  the native surface can get out of the way. */
    private var overlayUp = true
    private var lastStatus = ""
    private var lastStatusIsError = false

    /** True when the video is rendered INTO this layer (the embed worked). */
    private var embedded = false

    /** True once mpv has reported a real video format — the signal that the
     *  video output actually came up (a null `video-format` just means "no
     *  video yet", which is NOT the same as "this stream is audio only"). */
    private var sawVideo = false

    /** True once mpv has the file open (its `file-loaded` event). */
    private var loaded = false

    private var onNext: (() -> Unit)? = null
    private var onPrev: (() -> Unit)? = null
    private var onPosition: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    private var onPickSource: ((StreamSource) -> Unit)? = null
    private var onDownload: ((StreamSource) -> Unit)? = null
    private var onToggleFavourite: (() -> Unit)? = null
    private var isFavourite: () -> Boolean = { false }
    private var onOpenInBrowser: (() -> Unit)? = null
    private var sources: List<StreamSource> = emptyList()
    private var currentSource: StreamSource? = null
    private var currentSourceName = ""

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** Opens the player for a new stream — instantly. The layer is mounted (first
     *  time) or re-shown with its loading overlay up, and the native handle mpv
     *  should render into is returned; null means there is no surface (mpv then
     *  opens its own window). */
    fun open(
        title: String,
        hasNext: Boolean,
        hasPrev: Boolean,
        next: (() -> Unit)?,
        prev: (() -> Unit)?,
        position: ((Long, Long) -> Unit)?,
        closed: (() -> Unit)?,
        sources: List<StreamSource> = emptyList(),
        sourceName: String = "",
        onPickSource: ((StreamSource) -> Unit)? = null,
        onDownload: ((StreamSource) -> Unit)? = null,
        isFavourite: (() -> Boolean)? = null,
        onToggleFavourite: (() -> Unit)? = null,
        onOpenInBrowser: (() -> Unit)? = null,
        embed: Boolean = true,
    ): Long? {
        onNext = next
        onPrev = prev
        onPosition = position
        onClosed = closed
        this.sources = sources
        this.onPickSource = onPickSource
        this.onDownload = onDownload
        this.onToggleFavourite = onToggleFavourite
        this.onOpenInBrowser = onOpenInBrowser
        if (isFavourite != null) this.isFavourite = isFavourite
        return Fx.runBlock {
            val host = runCatching { AppShell.playerHost }.getOrNull() ?: return@runBlock null
            teardown = false
            if (root == null) buildUi()
            val ui = root ?: return@runBlock null
            if (!mounted) {
                host.children.setAll(ui)
                mounted = true
            }
            host.isVisible = true
            host.isManaged = true
            installKeys()
            titleLabel?.text = title
            prevButton?.isVisible = hasPrev
            prevButton?.isManaged = hasPrev
            nextButton?.isVisible = hasNext
            nextButton?.isManaged = hasNext
            renderSources(sourceName)
            refreshFavourite()
            resetForNewStream()
            // No embed: mpv keeps its own window (the fallback path), so the
            // layer just explains that instead of covering nothing.
            val surface = if (embed) startVideoSurface() else null
            embedded = surface != null
            if (!embed) {
                note("Video is playing in the player's own window — this machine's window compositor wouldn't let the app draw it inside itself. Everything below still controls it.")
            }
            refreshOverlay()
            surface
        }
    }

    /** Clears everything that belongs to the previous stream and puts the
     *  loading overlay back up, so the reused layer starts from a clean state. */
    private fun resetForNewStream() {
        duration = 0.0
        lastPosition = 0L
        scrubbing = false
        paused = false
        sawVideo = false
        loaded = false
        // mpv starts as a new process, so the surface (and the child window we
        // were filling) is rebuilt for every stream — a stale handle from the
        // previous player can never be mistaken for the new one's.
        runCatching { videoStage?.close() }
        videoStage = null
        videoHwnd = null
        embedded = false
        mpvChild = null
        childW = 0
        childH = 0
        lastChildProbe = 0L
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        seekBar?.value = 0.0
        seekBar?.isDisable = true
        playButton?.graphic = Icons.of(Icons.PAUSE, 18.0)
        audioMenu?.items?.setAll()
        subMenu?.items?.setAll()
        audioMenu?.isDisable = true
        subMenu?.isDisable = true
        renderTime()
        lastStatus = "Starting the player…"
        lastStatusIsError = false
        applyStatus()
        showLoading(if (currentSourceName.isBlank()) "Loading video…" else "Loading $currentSourceName…")
    }

    fun isOpen(): Boolean = mounted

    /** True once mpv reported a real video format — used by the caller's
     *  watchdog to detect a failed video embed. */
    fun hasVideo(): Boolean = sawVideo

    /** True once mpv reported the file as loaded. */
    fun isLoaded(): Boolean = loaded

    /** Live status text on the transport bar — "Starting the player…", the
     *  reason a stream died, the codec that is playing. */
    fun setStatus(text: String, isError: Boolean = false, busy: Boolean = false) {
        lastStatus = text
        lastStatusIsError = isError
        Fx.run {
            applyStatus()
            statusSpinner?.isVisible = busy
            statusSpinner?.isManaged = busy
        }
    }

    private fun applyStatus() {
        statusLabel?.text = lastStatus
        statusLabel?.styleClass?.remove("h-danger")
        if (lastStatusIsError) statusLabel?.styleClass?.add("h-danger")
        loadingSub?.text = lastStatus
    }

    /**
     * Shows a message over the video area, with buttons. The video surface is a
     * window ABOVE the app layer, so it gets out of the way while a message is
     * up — otherwise the message would be behind a black rectangle. Returns
     * false when there is no player open (the caller then shows a dialog).
     */
    fun showFailure(
        reason: String,
        url: String?,
        retry: (() -> Unit)? = null,
        tryAnyway: (() -> Unit)? = null,
    ): Boolean {
        if (!isOpen()) return false
        val actions = ArrayList<Pair<String, () -> Unit>>()
        url?.takeIf { it.isNotBlank() }?.let { target ->
            actions.add("Open in browser" to {
                DesktopUi.open(target)
                closeAll()
            })
        }
        tryAnyway?.let { it0 -> actions.add("Try anyway" to { it0() }) }
        retry?.let { r -> actions.add("Retry (fresh link)" to { r() }) }
        actions.add("Close player" to { closeAll() })
        setLoading(false)
        message(reason, actions)
        return true
    }

    /** A short note over the video area (no buttons) — used when the video is
     *  playing somewhere this layer cannot show. */
    fun note(text: String) {
        if (!isOpen()) return
        message(text, emptyList())
    }

    /** Closes the player layer without notifying the owner (used while tearing
     *  playback down from the outside). */
    fun closeAll() {
        Fx.run { closeInternal() }
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val headTitle = Label("").apply {
            styleClass.add("player-title")
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
        }
        timeChip = chip("00:00", "player-chip-time")
        qualityChip = chip("", "player-chip-quality")
        serverChip = chip("", "player-chip-server")
        val badgeRow = HBox(6.0, timeChip, qualityChip, serverChip).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val titleBox = VBox(3.0, headTitle, badgeRow).apply { minWidth = 0.0 }
        runCatching { AppShell.makeDraggable(titleBox) }

        // Leaving the player is a first-class action, not a window close: it
        // puts the app back exactly where the user left it.
        val back = roundButton(Icons.CHEVRON_LEFT, "Back to the app") { requestClose() }
        back.styleClass.add("player-back")

        favButton = roundButton(Icons.HEART_OUTLINE, "Add to library") {
            onToggleFavourite?.invoke()
            refreshFavourite()
        }
        downloadButton = roundButton(Icons.DOWNLOAD, "Download this source") {
            currentSource?.let { onDownload?.invoke(it) }
        }
        pinButton = roundButton(Icons.PIP, "Keep the window on top") { togglePin() }
        lockButton = roundButton(Icons.LOCK, "Hide the title and the extra buttons") { toggleLock() }
        val gear = MenuButton().apply {
            styleClass.addAll("player-round", "player-round-menu")
            graphic = Icons.of(Icons.SETTINGS, 16.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip("Player options"))
            items.add(MenuItem("Always on top").apply { setOnAction { togglePin() } })
            items.add(MenuItem("Rotate video").apply { setOnAction { rotate() } })
            items.add(MenuItem("Enhance (sharpen)").apply { setOnAction { toggleEnhance() } })
            items.add(MenuItem("Open in browser").apply { setOnAction { onOpenInBrowser?.invoke() } })
            items.add(MenuItem("Open the stream URL").apply {
                setOnAction { currentSource?.url?.let { DesktopUi.open(it) } }
            })
            items.add(MenuItem("Close player").apply { setOnAction { requestClose() } })
        }

        val actions = HBox(
            6.0, favButton, downloadButton, pinButton, gear, lockButton,
        ).apply { alignment = Pos.CENTER_RIGHT }

        header = HBox(12.0, back, titleBox, Ui.spacer(), actions).apply {
            styleClass.add("player-head")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        runCatching { header!!.children.add(AppShell.windowControls()) }

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
        val loadSub = Label("").apply {
            styleClass.add("player-load-sub")
            isWrapText = true
            maxWidth = 520.0
            minWidth = 0.0
            textAlignment = TextAlignment.CENTER
        }
        val loadBox = VBox(16.0, loadSpinner, loadTitle, loadSub).apply {
            styleClass.add("player-load")
            alignment = Pos.CENTER
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }

        videoArea.children.setAll(loadBox, msgBox)

        // ── transport ───────────────────────────────────────────────────────
        val play = roundButton(Icons.PAUSE, "Play / pause (space)") {
            runCatching { ipc?.post("cycle", "pause") }
        }.apply {
            styleClass.add("player-play")
            graphic = Icons.of(Icons.PAUSE, 22.0)
        }
        prevButton = roundButton(Icons.SKIP_PREV, "Previous episode") { onPrev?.invoke() }
        nextButton = roundButton(Icons.SKIP_NEXT, "Next episode") { onNext?.invoke() }
        val back10 = roundButton(null, "Back 10 seconds (←)") { runCatching { ipc?.post("seek", -10, "relative") } }
            .apply { text = "10"; styleClass.add("player-skip") }
        val fwd10 = roundButton(null, "Forward 10 seconds (→)") { runCatching { ipc?.post("seek", 10, "relative") } }
            .apply { text = "10"; styleClass.add("player-skip") }

        volumeSlider = Slider(0.0, 130.0, 100.0).apply {
            styleClass.add("player-vol")
            prefWidth = 96.0
            minWidth = 60.0
            maxWidth = 96.0
            setOnMouseReleased {
                volume = value
                runCatching { ipc?.setProperty("volume", value) }
            }
        }
        volumeIcon = Label().apply {
            styleClass.add("player-vol-icon")
            graphic = Icons.of(Icons.VOLUME, 16.0)
            setOnMouseClicked {
                muted = !muted
                runCatching { ipc?.setProperty("mute", muted) }
                graphic = Icons.of(if (muted) Icons.VOLUME_OFF else Icons.VOLUME, 16.0)
            }
        }

        val seek = Slider(0.0, 1.0, 0.0).apply {
            styleClass.add("player-seek")
            isDisable = true
            setOnMousePressed { scrubbing = true }
            setOnMouseReleased {
                scrubbing = false
                val d = duration
                if (d > 0) runCatching { ipc?.post("seek", value * d, "absolute") }
            }
        }
        seekBar = seek

        val time = Label("00:00 / 00:00").apply { styleClass.add("player-time") }
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
        val status = Label("").apply {
            styleClass.add("player-status")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        statusLabel = status
        statusSpinner = spinner
        timeLabel = time

        val seekRow = HBox(10.0, seek).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val timeRow = HBox(10.0, time).apply {
            alignment = Pos.CENTER_RIGHT
            minWidth = 0.0
        }
        val transportRow = HBox(
            8.0, prevButton, back10, play, fwd10, nextButton, Ui.spacer(), volumeIcon, volumeSlider,
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }

        // ── feature buttons (Android parity) ────────────────────────────────
        speedMenu = pillMenu("1x", Icons.SPEED, "Playback speed").apply {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                items.add(MenuItem("${rate}x").apply {
                    setOnAction {
                        runCatching { ipc?.setProperty("speed", rate) }
                        speedMenu?.text = "${rate}x"
                    }
                })
            }
        }
        sourceMenu = pillMenu("Source", Icons.FILM, "Pick another source / server").apply {
            styleClass.add("player-pill-accent")
        }
        qualityMenu = pillMenu("Quality", Icons.TV, "Switch to a source in this quality")
        audioMenu = pillMenu("Audio", Icons.VOLUME, "Audio track").apply { isDisable = true }
        subMenu = pillMenu("Subtitles", Icons.SUBTITLES, "Subtitle track").apply { isDisable = true }
        rotateButton = pillButton("Rotate", Icons.REFRESH, "Rotate the video 90°") { rotate() }
        skipIntroButton = pillButton("Skip Intro", Icons.ZAP, "Jump forward 85 seconds") {
            runCatching { ipc?.post("seek", 85, "relative") }
        }.apply { styleClass.add("player-pill-accent") }
        enhanceButton = pillButton("Enhance", Icons.SPARKLE, "Sharpen the picture") { toggleEnhance() }
            .apply { styleClass.add("player-pill-accent") }
        fullscreenButton = roundButton(Icons.FULLSCREEN, "Fullscreen (F)") { toggleFullscreen() }

        featureRow = HBox(
            6.0, speedMenu, sourceMenu, qualityMenu, audioMenu, subMenu,
            rotateButton, skipIntroButton, enhanceButton, Ui.spacer(), fullscreenButton,
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }

        val statusRow = HBox(8.0, spinner, status).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }

        val controls = VBox(7.0, seekRow, timeRow, transportRow, featureRow, statusRow).apply {
            styleClass.add("player-bar")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }

        root = BorderPane().apply {
            styleClass.add("player-root")
            top = header
            center = videoArea
            bottom = controls
        }

        messageLabel = msgLabel
        messageActions = msgActions
        messageBox = msgBox
        loadingBox = loadBox
        loadingLabel = loadTitle
        loadingSub = loadSub

        // Anything that moves or resizes the video area has to move the native
        // surface glued to it.
        videoArea.layoutBoundsProperty().addListener { _, _, _ -> safeSync() }
        val stage = runCatching { AppShell.stage }.getOrNull()
        if (stage != null) {
            stage.xProperty().addListener { _, _, _ -> safeSync() }
            stage.yProperty().addListener { _, _, _ -> safeSync() }
            stage.widthProperty().addListener { _, _, _ -> safeSync() }
            stage.heightProperty().addListener { _, _, _ -> safeSync() }
            stage.fullScreenProperty().addListener { _, _, _ -> safeSync() }
        }
        syncTimer = Timeline(
            KeyFrame(Duration.millis(SYNC_MS), EventHandler<ActionEvent> { safeSync() }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun chip(text: String, cls: String): Label = Label(text).apply {
        styleClass.addAll("player-chip", cls)
        isVisible = text.isNotBlank()
        isManaged = isVisible
    }

    private fun setChip(label: Label?, text: String) {
        label?.text = text
        label?.isVisible = text.isNotBlank()
        label?.isManaged = text.isNotBlank()
    }

    private fun roundButton(icon: String?, tooltip: String, onClick: () -> Unit): Button =
        Button().apply {
            styleClass.add("player-round")
            if (icon != null) graphic = Icons.of(icon, 16.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip(tooltip))
            setOnAction { runCatching { onClick() } }
        }

    private fun pillButton(text: String, icon: String, tooltip: String, onClick: () -> Unit): Button =
        Button(text).apply {
            styleClass.add("player-pill")
            graphic = Icons.of(icon, 14.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip(tooltip))
            setOnAction { runCatching { onClick() } }
        }

    private fun pillMenu(text: String, icon: String, tooltip: String): MenuButton =
        MenuButton(text).apply {
            styleClass.addAll("player-pill", "player-pill-menu")
            graphic = Icons.of(icon, 14.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip(tooltip))
        }

    private var timeLabel: Label? = null

    private fun requestClose() {
        if (teardown) return
        closeInternal()
        onClosed?.invoke()
    }

    private fun closeInternal() {
        teardown = true
        runCatching { syncTimer?.stop() }
        syncTimer = null
        uninstallKeys()
        runCatching { ipc?.close() }
        ipc = null
        runCatching { videoStage?.close() }
        videoStage = null
        videoHwnd = null
        mpvChild = null
        childW = 0
        childH = 0
        overlayUp = true
        duration = 0.0
        scrubbing = false
        paused = false
        runCatching {
            AppShell.playerHost.isVisible = false
            AppShell.playerHost.isManaged = false
        }
    }

    // ── the video surface ───────────────────────────────────────────────────

    /**
     * Creates the borderless window mpv renders into: owned by the app window
     * (so it minimises, moves and closes with it, and never takes a taskbar slot
     * of its own), and nothing but video.
     *
     * Its native handle is found by title — a UNIQUE title per stream, so a
     * stale surface from an earlier stream can never be matched.
     */
    private fun startVideoSurface(): Long? {
        val owner = runCatching { AppShell.stage }.getOrNull() ?: return null
        if (!WinShell.available) return null
        val probe = "HikariVideo" + System.nanoTime()
        val vs = Stage()
        vs.initOwner(owner)
        runCatching { vs.initStyle(StageStyle.UNDECORATED) }
        vs.title = probe
        val surfaceRoot = Region().apply { style = "-fx-background-color: #000000;" }
        vs.scene = javafx.scene.Scene(surfaceRoot, 640.0, 360.0).apply { fill = Color.BLACK }
        // Shown at a sane size, NOT hidden: mpv's video output is created when
        // it starts playing, and a hidden parent makes both the output and the
        // child window it renders into unusable (sound-only playback).
        runCatching { vs.show() }.onFailure { return null }
        val hwnd = WinShell.windowByTitle(probe)
        if (hwnd == null) {
            runCatching { vs.close() }
            return null
        }
        vs.title = "Hikari Player"
        videoStage = vs
        videoHwnd = hwnd
        syncSurface()
        return hwnd
    }

    private fun safeSync() {
        runCatching { syncSurface() }
    }

    /**
     * Keeps the video surface glued to the video area (or shrunk to nothing
     * while an overlay is up), and mpv's child window filling it.
     */
    private fun syncSurface() {
        val vs = videoStage ?: return
        if (!vs.isShowing) return
        val area = videoArea
        if (area.scene == null || area.width < 8.0 || area.height < 8.0) return
        val rect = runCatching { area.localToScreen(area.boundsInLocal) }.getOrNull() ?: return
        if (rect.width < 8.0 || rect.height < 8.0) return
        val w = if (overlayUp) OVERLAY_SIZE.toDouble() else rect.width
        val h = if (overlayUp) OVERLAY_SIZE.toDouble() else rect.height
        if (abs(vs.x - rect.minX) > 0.5) vs.x = rect.minX
        if (abs(vs.y - rect.minY) > 0.5) vs.y = rect.minY
        if (abs(vs.width - w) > 0.5) vs.width = w
        if (abs(vs.height - h) > 0.5) vs.height = h
        val child = ensureChild() ?: return
        // Win32 geometry is in physical pixels; JavaFX's is in logical ones, so
        // on a scaled display the child has to be told the real size.
        val scale = screenScale()
        val pw = (w * scale).roundToInt()
        val ph = (h * scale).roundToInt()
        if (pw > 0 && ph > 0 && (pw != childW || ph != childH)) {
            childW = pw
            childH = ph
            WinShell.fillWindow(child, pw, ph)
        }
    }

    /** mpv's child window inside the surface, probed until it exists. */
    private fun ensureChild(): Long? {
        mpvChild?.let { live ->
            if (WinShell.windowExists(live)) return live
            mpvChild = null
            childW = 0
            childH = 0
        }
        val parent = videoHwnd ?: return null
        val now = System.currentTimeMillis()
        if (now - lastChildProbe < 250L) return null
        lastChildProbe = now
        val found = WinShell.firstVisibleChild(parent) ?: return null
        mpvChild = found
        childW = 0
        childH = 0
        return found
    }

    private fun screenScale(): Double = runCatching {
        val s = runCatching { AppShell.stage }.getOrNull() ?: return@runCatching 1.0
        val screen = Screen.getScreensForRectangle(s.x, s.y, 1.0, 1.0).firstOrNull() ?: Screen.getPrimary()
        screen.outputScaleX.takeIf { it > 0.0 } ?: 1.0
    }.getOrDefault(1.0)

    // ── overlays over the video ─────────────────────────────────────────────

    private fun showLoading(text: String) {
        loadingLabel?.text = text
        loadingBox?.isVisible = true
        loadingBox?.isManaged = true
        setLoading(true)
    }

    private fun setLoading(value: Boolean) {
        if (value) {
            loadingBox?.isVisible = true
            loadingBox?.isManaged = true
        } else {
            loadingBox?.isVisible = false
            loadingBox?.isManaged = false
        }
        refreshOverlay()
    }

    /** The single place that decides whether the native video surface should be
     *  in the way of the app's own content. */
    private fun refreshOverlay() {
        overlayUp = (loadingBox?.isVisible == true) || (messageBox?.isVisible == true)
        safeSync()
    }

    private fun message(text: String, actions: List<Pair<String, () -> Unit>>) {
        Fx.run {
            val box = messageBox ?: return@run
            messageLabel?.text = text
            messageActions?.children?.setAll(
                *actions.map { (label, action) ->
                    Button(label).apply {
                        styleClass.add("btn")
                        styleClass.add("btn-primary")
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

    // ── the source / quality pickers ────────────────────────────────────────

    /** Fills the Source menu with every stream the title/episode offered, and
     *  the Quality menu with the qualities those sources cover. */
    private fun renderSources(current: String) {
        currentSourceName = current
        currentSource = sources.firstOrNull { it.name == current } ?: sources.firstOrNull()
        setChip(serverChip, current.ifBlank { sources.firstOrNull()?.name.orEmpty() })

        val menu = sourceMenu
        if (menu != null) {
            if (sources.size <= 1) {
                menu.isVisible = false
                menu.isManaged = false
            } else {
                menu.isVisible = true
                menu.isManaged = true
                menu.text = "Source"
                menu.items.setAll(
                    *sources.map { s ->
                        MenuItem(s.name.ifBlank { s.url.take(60) }).apply {
                            if (s.name == currentSourceName) graphic = Icons.of(Icons.CHECK, 13.0)
                            setOnAction { pickSource(s) }
                        }
                    }.toTypedArray()
                )
            }
        }

        val qualities = sources.map { qualityOf(it) }.filter { it.isNotBlank() }.distinct()
        val qMenu = qualityMenu
        if (qMenu != null) {
            val show = qualities.size > 1
            qMenu.isVisible = show
            qMenu.isManaged = show
            if (show && currentSource != null) setChip(qualityChip, qualityOf(currentSource!!))
            qMenu.items.setAll(
                *qualities.map { q ->
                    MenuItem(q).apply {
                        if (currentSource != null && qualityOf(currentSource!!) == q) {
                            graphic = Icons.of(Icons.CHECK, 13.0)
                        }
                        setOnAction {
                            sources.firstOrNull { qualityOf(it) == q }?.let { pickSource(it) }
                        }
                    }
                }.toTypedArray()
            )
        } else {
            setChip(qualityChip, currentSource?.let { qualityOf(it) } ?: "")
        }
        setChip(qualityChip, currentSource?.let { qualityOf(it) } ?: "")
    }

    private fun pickSource(source: StreamSource) {
        currentSourceName = source.name
        currentSource = source
        renderSources(source.name)
        onPickSource?.invoke(source)
    }

    /** The quality a source advertises in its name, if any. */
    private fun qualityOf(source: StreamSource): String {
        val name = source.name.lowercase()
        Regex("(\\d{3,4}p)").find(name)?.let { return it.groupValues[1].uppercase() }
        if (name.contains("4k") || name.contains("2160")) return "4K"
        if (name.contains("1080")) return "1080P"
        if (name.contains("720")) return "720P"
        if (name.contains("480")) return "480P"
        if (name.contains("hd")) return "HD"
        return ""
    }

    private fun refreshFavourite() {
        runCatching {
            val fav = isFavourite()
            favButton?.graphic = Icons.of(if (fav) Icons.HEART else Icons.HEART_OUTLINE, 16.0)
            favButton?.styleClass?.remove("player-round-on")
            if (fav) favButton?.styleClass?.add("player-round-on")
        }
    }

    // ── toggles ─────────────────────────────────────────────────────────────

    private fun togglePin() {
        val s = runCatching { AppShell.stage }.getOrNull() ?: return
        pinned = !pinned
        s.isAlwaysOnTop = pinned
        pinButton?.styleClass?.remove("player-round-on")
        if (pinned) pinButton?.styleClass?.add("player-round-on")
    }

    private fun toggleLock() {
        locked = !locked
        header?.isVisible = !locked
        header?.isManaged = !locked
        featureRow?.isVisible = !locked
        featureRow?.isManaged = !locked
        lastStatus = if (locked) "Controls hidden — press L to bring them back" else ""
        lastStatusIsError = false
        applyStatus()
    }

    private fun rotate() {
        rotation = (rotation + 90) % 360
        runCatching { ipc?.setProperty("video-rotate", rotation) }
        lastStatus = if (rotation == 0) "Rotation reset" else "Rotated $rotation°"
        lastStatusIsError = false
        applyStatus()
    }

    private fun toggleEnhance() {
        enhanced = !enhanced
        runCatching { ipc?.setProperty("sharpen", if (enhanced) 1.2 else 0.0) }
        enhanceButton?.styleClass?.remove("player-pill-on")
        if (enhanced) enhanceButton?.styleClass?.add("player-pill-on")
        lastStatus = if (enhanced) "Enhance on" else "Enhance off"
        lastStatusIsError = false
        applyStatus()
    }

    private fun setVolume(next: Double) {
        val clamped = next.coerceIn(0.0, 130.0)
        volume = clamped
        volumeSlider?.value = clamped
        runCatching { ipc?.setProperty("volume", clamped) }
    }

    private fun toggleFullscreen() {
        val s = runCatching { AppShell.stage }.getOrNull() ?: return
        s.isFullScreen = !s.isFullScreen
        fullscreenButton?.graphic =
            Icons.of(if (s.isFullScreen) Icons.FULLSCREEN_EXIT else Icons.FULLSCREEN, 16.0)
        safeSync()
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
        when (e.code) {
            KeyCode.SPACE -> runCatching { ipc?.post("cycle", "pause") }
            KeyCode.LEFT -> runCatching { ipc?.post("seek", -10, "relative") }
            KeyCode.RIGHT -> runCatching { ipc?.post("seek", 10, "relative") }
            KeyCode.UP -> setVolume(volume + 5)
            KeyCode.DOWN -> setVolume(volume - 5)
            KeyCode.F, KeyCode.F11 -> toggleFullscreen()
            KeyCode.L -> toggleLock()
            KeyCode.N -> onNext?.invoke()
            KeyCode.P -> onPrev?.invoke()
            KeyCode.ESCAPE -> requestClose()
            else -> Unit
        }
        e.consume()
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
        )
        // `observe` waits for a reply, so it must not run on the FX thread.
        Thread(
            { observations.forEach { (name, id) -> runCatching { handle.observe(name, id) } } },
            "hikari-mpv-observe",
        ).apply { isDaemon = true; start() }
        Fx.run {
            statusSpinner?.isVisible = true
            statusSpinner?.isManaged = true
        }
    }

    /** Feeds one mpv property change in. Called from the IPC reader thread —
     *  everything it touches is hopped to the FX thread. */
    fun onProperty(name: String, value: Any?) {
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
                Fx.run { playButton?.graphic = Icons.of(if (paused) Icons.PLAY else Icons.PAUSE, 22.0) }
            }
            "volume" -> {
                volume = (value as? Number)?.toDouble() ?: 100.0
                Fx.run { volumeSlider?.value = volume }
            }
            "track-list" -> renderTracks(value)
            "video-format" -> {
                val fmt = value?.toString().orEmpty()
                // Only a REAL format means the video output is up: observing the
                // property delivers its current (often null) value immediately,
                // and treating that as "playback started" revealed a black
                // surface and labelled a perfectly good stream "Audio only".
                if (fmt.isNotBlank()) {
                    sawVideo = true
                    Fx.run { playbackStarted() }
                    setStatus("Video: $fmt")
                }
            }
            "eof-reached" -> {
                if (value == true) onNext?.invoke()
            }
        }
    }

    fun onEvent(event: String, data: JSONObject) {
        when (event) {
            // mpv has the file open and is about to render: the wait is over.
            "file-loaded", "playback-restart" -> Fx.run { playbackStarted() }
            "end-file", "ipc-closed" -> {
                val reason = data.optString("reason").ifBlank { data.optString("error") }
                if (reason.isNotBlank() && reason != "eof" && reason != "quit" && reason != "stop") {
                    Fx.run {
                        setLoading(false)
                        lastStatus = "Playback stopped: $reason"
                        lastStatusIsError = true
                        applyStatus()
                        statusSpinner?.isVisible = false
                        statusSpinner?.isManaged = false
                    }
                }
            }
        }
    }

    /** Called the moment playback is actually up: drops the loading overlay and
     *  reveals the video surface. Safe to call more than once. */
    private fun playbackStarted() {
        loaded = true
        if (loadingBox?.isVisible == true) setLoading(false)
        if (embedded) clearMessage()
        statusSpinner?.isVisible = false
        statusSpinner?.isManaged = false
    }

    private fun renderTime() {
        val now = clock(lastPosition / 1000)
        val total = clock(duration.toLong())
        timeLabel?.text = "$now / $total"
        setChip(timeChip, if (duration > 0.0) "$now / $total" else "")
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
