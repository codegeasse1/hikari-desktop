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
 * What the layer carries:
 *
 *   - a loading overlay (spinner + what is being opened) that stays up until
 *     mpv reports the file actually loaded — so the wait for a stream is never
 *     an unexplained black rectangle,
 *   - a timeline with a scrubbable seek bar and real time labels,
 *   - play/pause, ±10s, volume and speed,
 *   - a **Source** picker, so another server/quality can be chosen without
 *     leaving the player,
 *   - the file's actual audio and subtitle tracks, by name and language,
 *   - what the stream really is (format/codec) and why it failed,
 *   - "Next episode", fired the moment mpv reports the file ended,
 *   - the position, handed back for resume/history,
 *   - keyboard control (space, ←/→, ↑/↓, F, Esc).
 *
 * When the surface cannot be created (not Windows, no JNA, no window handle)
 * [open] returns null and mpv keeps its own window; the layer then says so, and
 * the controls still drive mpv over its IPC channel, so nothing is lost.
 */
object PlayerWindow {

    /** How often the video surface is glued to the video area. The surface is a
     *  real window, so a move of the app window (a drag, a maximise, a DPI
     *  change) has to be followed — events cover the common cases, this covers
     *  the rest. */
    private const val SYNC_MS = 150.0

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
    private var surfaceHidden = false

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
    private var subLabel: Label? = null
    private var timeLabel: Label? = null
    private var statusLabel: Label? = null
    private var statusSpinner: ProgressIndicator? = null
    private var seekBar: Slider? = null
    private var playButton: Button? = null
    private var volumeSlider: Slider? = null
    private var audioMenu: MenuButton? = null
    private var subMenu: MenuButton? = null
    private var speedMenu: MenuButton? = null
    private var sourceMenu: MenuButton? = null
    private var nextButton: Button? = null
    private var fullscreenButton: Button? = null
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

    /** Set while the loading overlay is up. The video surface is a window ABOVE
     *  the app layer, so it is kept hidden until playback actually starts —
     *  otherwise it would cover the very spinner that explains the wait. */
    private var loading = false
    private var messageUp = false
    private var lastStatus = ""
    private var lastStatusIsError = false

    /** True when the video is rendered INTO this layer (the embed worked); false
     *  when mpv fell back to its own window, in which case the explanation over
     *  the video area must stay up instead of being cleared at playback start. */
    private var embedded = false

    private var onNext: (() -> Unit)? = null
    private var onPosition: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    private var onPickSource: ((StreamSource) -> Unit)? = null
    private var sources: List<StreamSource> = emptyList()
    private var currentSourceName = ""

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Opens the player for a new stream — instantly. The layer is mounted (first
     * time) or re-shown with its loading overlay up, and the native handle mpv
     * should render into is returned; null means there is no surface (mpv then
     * opens its own window).
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
    ): Long? {
        onNext = next
        onPosition = position
        onClosed = closed
        this.sources = sources
        this.onPickSource = onPickSource
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
            nextButton?.isVisible = hasNext
            nextButton?.isManaged = hasNext
            renderSources(sourceName)
            resetForNewStream()
            val surface = startVideoSurface()
            embedded = surface != null
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
        surfaceHidden = false
        messageUp = false
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        seekBar?.value = 0.0
        seekBar?.isDisable = true
        timeLabel?.text = "00:00 / 00:00"
        playButton?.graphic = Icons.of(Icons.PAUSE, 17.0)
        audioMenu?.items?.setAll()
        subMenu?.items?.setAll()
        audioMenu?.isDisable = true
        subMenu?.isDisable = true
        lastStatus = "Starting the player…"
        lastStatusIsError = false
        applyStatus()
        showLoading(if (currentSourceName.isBlank()) "Loading video…" else "Loading $currentSourceName…")
    }

    fun isOpen(): Boolean = mounted

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
     * window ABOVE the app layer, so it is hidden while a message is up —
     * otherwise the message would be behind a black rectangle. Returns false
     * when there is no player open (the caller then shows a dialog).
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

    /**
     * A short note over the video area (no buttons) — used when the video is
     * playing somewhere this layer cannot show, e.g. a machine where the embed
     * is unavailable and mpv keeps its own window.
     */
    fun note(text: String) {
        if (!isOpen()) return
        message(text, emptyList())
    }

    /** Closes the player layer without notifying the owner (used while tearing
     *  playback down from the outside). */
    fun closeAll() {
        Fx.run { closeInternal() }
    }

    // ── window ──────────────────────────────────────────────────────────────

    private fun buildUi() {
        val headTitle = Label("").apply {
            styleClass.add("player-title")
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
        }
        val headSub = Label("Hikari player").apply { styleClass.add("player-sub") }
        val titleBox = VBox(1.0, headTitle, headSub).apply { minWidth = 0.0 }
        runCatching { AppShell.makeDraggable(titleBox) }

        // Leaving the player is a first-class action, not a window close: it puts
        // the app back exactly where the user left it.
        val back = Ui.button("Back", icon = Icons.CHEVRON_LEFT, ghost = true) { requestClose() }
        back.styleClass.add("player-back")

        val header = HBox(12.0, back, titleBox, Ui.spacer()).apply {
            styleClass.add("player-head")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        // The window controls stay on the right, so the player window can still
        // be minimised / maximised / closed like the app's own top bar.
        runCatching { header.children.add(AppShell.windowControls()) }

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
        }

        videoArea.children.setAll(loadBox, msgBox)

        val seek = Slider(0.0, 1.0, 0.0).apply {
            styleClass.add("player-seek")
            isDisable = true
            // A scrub must not fight the position updates mpv is streaming in.
            setOnMousePressed { scrubbing = true }
            setOnMouseReleased {
                scrubbing = false
                val d = duration
                if (d > 0) ipc?.post("seek", value * d, "absolute")
            }
        }

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

        val play = playerButton(Icons.PAUSE, "Play / pause (space)") { ipc?.post("cycle", "pause") }
        play.styleClass.add("player-btn-play")
        val back10 = playerButton(null, "Back 10 seconds (←)") { ipc?.post("seek", -10, "relative") }
            .apply { text = "−10s" }
        val fwd10 = playerButton(null, "Forward 10 seconds (→)") { ipc?.post("seek", 10, "relative") }
            .apply { text = "+10s" }

        val volume = Slider(0.0, 130.0, 100.0).apply {
            styleClass.add("player-vol")
            prefWidth = 100.0
            minWidth = 60.0
            setOnMouseReleased {
                this@PlayerWindow.volume = value
                ipc?.setProperty("volume", value)
            }
        }
        val volumeIcon = Label().apply {
            styleClass.add("player-vol-icon")
            graphic = Icons.of(Icons.VOLUME, 15.0)
            isFocusTraversable = false
        }

        val speed = MenuButton("Speed").apply {
            styleClass.add("player-menu")
            graphic = Icons.of(Icons.SPEED, 14.0)
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                items.add(MenuItem("${rate}x").apply { setOnAction { ipc?.setProperty("speed", rate) } })
            }
        }

        val audio = MenuButton("Audio").apply {
            styleClass.add("player-menu")
            graphic = Icons.of(Icons.VOLUME, 14.0)
            isDisable = true
        }
        val subs = MenuButton("Subtitles").apply {
            styleClass.add("player-menu")
            graphic = Icons.of(Icons.SUBTITLES, 14.0)
            isDisable = true
        }

        // The stream picker. Hidden until there is more than one source to pick
        // from, so a single-source episode doesn't get a dead button.
        val source = MenuButton("Source").apply {
            styleClass.addAll("player-menu", "player-source")
            graphic = Icons.of(Icons.TV, 14.0)
            isVisible = false
            isManaged = false
        }

        val next = Button("Next episode").apply {
            styleClass.addAll("btn", "btn-primary")
            isVisible = false
            isManaged = false
            setOnAction { onNext?.invoke() }
        }

        val full = playerButton(Icons.FULLSCREEN, "Fullscreen (F)") { toggleFullscreen() }

        val controlsRow = HBox(
            8.0, play, back10, fwd10, volumeIcon, volume, time, Ui.spacer(),
            source, speed, audio, subs, full,
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val infoRow = HBox(10.0, spinner, status, Ui.spacer(), next).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val controls = VBox(9.0, seek, controlsRow, infoRow).apply {
            styleClass.add("player-bar")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }

        val body = BorderPane().apply {
            styleClass.add("player-root")
            top = header
            center = videoArea
            bottom = controls
        }

        titleLabel = headTitle
        subLabel = headSub
        timeLabel = time
        statusLabel = status
        statusSpinner = spinner
        seekBar = seek
        playButton = play
        volumeSlider = volume
        audioMenu = audio
        subMenu = subs
        speedMenu = speed
        sourceMenu = source
        nextButton = next
        fullscreenButton = full
        messageLabel = msgLabel
        messageActions = msgActions
        messageBox = msgBox
        loadingBox = loadBox
        loadingLabel = loadTitle
        loadingSub = loadSub

        // Anything that moves or resizes the video area has to move the native
        // surface glued to it.
        videoArea.layoutBoundsProperty().addListener { _, _, _ -> syncSurface() }
        val stage = runCatching { AppShell.stage }.getOrNull()
        if (stage != null) {
            stage.xProperty().addListener { _, _, _ -> syncSurface() }
            stage.yProperty().addListener { _, _, _ -> syncSurface() }
            stage.widthProperty().addListener { _, _, _ -> syncSurface() }
            stage.heightProperty().addListener { _, _, _ -> syncSurface() }
            stage.fullScreenProperty().addListener { _, _, _ -> syncSurface() }
        }
        syncTimer = Timeline(
            KeyFrame(Duration.millis(SYNC_MS), EventHandler<ActionEvent> { syncSurface() }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
        root = body
    }

    private fun playerButton(icon: String?, tooltip: String, onClick: () -> Unit): Button =
        Button().apply {
            styleClass.add("player-btn")
            if (icon != null) graphic = Icons.of(icon, 17.0)
            isFocusTraversable = false
            javafx.scene.control.Tooltip.install(this, Ui.tooltip(tooltip))
            setOnAction { onClick() }
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
        uninstallKeys()
        runCatching { ipc?.close() }
        ipc = null
        runCatching { videoStage?.close() }
        videoStage = null
        videoHwnd = null
        mpvChild = null
        childW = 0
        childH = 0
        surfaceHidden = false
        loading = false
        messageUp = false
        runCatching {
            AppShell.playerHost.isVisible = false
            AppShell.playerHost.isManaged = false
        }
        duration = 0.0
        scrubbing = false
        paused = false
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
        // Deliberately NOT `Theme.style(scene)`: that would make the surface the
        // theme's "current scene" and a theme toggle would re-style a window
        // that is nothing but black. A plain black fill is all it needs.
        val surfaceRoot = Region().apply { style = "-fx-background-color: #000000;" }
        vs.scene = javafx.scene.Scene(surfaceRoot, 32.0, 18.0).apply { fill = Color.BLACK }
        runCatching { vs.show() }.onFailure { return null }
        val hwnd = WinShell.windowByTitle(probe)
        if (hwnd == null) {
            runCatching { vs.close() }
            return null
        }
        vs.title = "Hikari Player"
        videoStage = vs
        videoHwnd = hwnd
        refreshOverlay()
        syncSurface()
        return hwnd
    }

    /** Keeps the video surface exactly over the video area, and mpv's child
     *  window exactly filling the surface. */
    private fun syncSurface() {
        val vs = videoStage ?: return
        if (!vs.isShowing) return
        val area = videoArea
        if (area.scene == null || area.width < 8.0 || area.height < 8.0) return
        val rect = runCatching { area.localToScreen(area.boundsInLocal) }.getOrNull() ?: return
        if (rect.width < 8.0 || rect.height < 8.0) return
        if (abs(vs.x - rect.minX) > 0.5) vs.x = rect.minX
        if (abs(vs.y - rect.minY) > 0.5) vs.y = rect.minY
        if (abs(vs.width - rect.width) > 0.5) vs.width = rect.width
        if (abs(vs.height - rect.height) > 0.5) vs.height = rect.height
        val child = ensureChild() ?: return
        // Win32 geometry is in physical pixels; JavaFX's is in logical ones, so
        // on a scaled display the child has to be told the real size or it
        // covers only part of the surface.
        val scale = screenScale()
        val w = (vs.width * scale).roundToInt()
        val h = (vs.height * scale).roundToInt()
        if (w > 0 && h > 0 && (w != childW || h != childH)) {
            childW = w
            childH = h
            WinShell.fillWindow(child, w, h)
        }
    }

    /** mpv's child window inside the surface, probed until it exists. */
    private fun ensureChild(): Long? {
        // A cached handle from a previous stream must be dropped once mpv has
        // exited, or the new video would never be re-sized to the window.
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
        // Force a re-fill: the child appears at whatever size mpv chose.
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
        loading = true
        loadingLabel?.text = text
        loadingBox?.isVisible = true
        loadingBox?.isManaged = true
        refreshOverlay()
    }

    private fun setLoading(value: Boolean) {
        loading = value
        if (value) {
            loadingBox?.isVisible = true
            loadingBox?.isManaged = true
        } else {
            loadingBox?.isVisible = false
            loadingBox?.isManaged = false
        }
        refreshOverlay()
    }

    /**
     * The single place that decides whether the native video surface is shown.
     * It is a window on top of everything the app draws, so while a loading
     * spinner or an explanation is up it has to be hidden for that text to be
     * readable at all.
     */
    private fun refreshOverlay() {
        val hide = loading || messageUp
        val vs = videoStage ?: return
        if (hide) {
            if (vs.isShowing) {
                vs.hide()
                surfaceHidden = true
            }
        } else if (surfaceHidden) {
            surfaceHidden = false
            runCatching {
                vs.show()
                syncSurface()
            }
        }
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
            messageUp = true
            refreshOverlay()
        }
    }

    private fun clearMessage() {
        messageUp = false
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        refreshOverlay()
    }

    // ── the source picker ───────────────────────────────────────────────────

    /** Fills the Source menu with every stream the title/episode offered. */
    private fun renderSources(current: String) {
        currentSourceName = current
        val menu = sourceMenu ?: return
        val list = sources
        if (list.size <= 1) {
            menu.isVisible = false
            menu.isManaged = false
            if (list.isNotEmpty() && current.isBlank()) currentSourceName = list[0].name
            return
        }
        menu.isVisible = true
        menu.isManaged = true
        menu.text = current.ifBlank { "${list.size} sources" }.let { if (it.length > 26) it.take(25) + "…" else it }
        javafx.scene.control.Tooltip.install(menu, Ui.tooltip(current.ifBlank { "${list.size} sources" }))
        menu.items.setAll(
            *list.map { s ->
                MenuItem(s.name.ifBlank { s.url.take(60) }).apply {
                    if (s.name == current) graphic = Icons.of(Icons.CHECK, 13.0)
                    setOnAction { pickSource(s) }
                }
            }.toTypedArray()
        )
    }

    private fun pickSource(source: StreamSource) {
        currentSourceName = source.name
        renderSources(source.name)
        onPickSource?.invoke(source)
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    /** While the player is up it owns the keyboard: the app's own shortcuts
     *  (Ctrl+1…4, Ctrl+K, Escape-to-go-back) must not fire behind it. */
    private fun installKeys() {
        if (keyFilter != null) return
        val scene = runCatching { AppShell.playerHost.scene }.getOrNull() ?: return
        val filter = EventHandler<KeyEvent> { e -> handleKey(e) }
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
            KeyCode.SPACE -> ipc?.post("cycle", "pause")
            KeyCode.LEFT -> ipc?.post("seek", -10, "relative")
            KeyCode.RIGHT -> ipc?.post("seek", 10, "relative")
            KeyCode.UP -> setVolume(volume + 5)
            KeyCode.DOWN -> setVolume(volume - 5)
            KeyCode.F, KeyCode.F11 -> toggleFullscreen()
            KeyCode.ESCAPE -> requestClose()
            else -> Unit
        }
        e.consume()
    }

    private fun setVolume(next: Double) {
        val clamped = next.coerceIn(0.0, 130.0)
        volume = clamped
        volumeSlider?.value = clamped
        ipc?.setProperty("volume", clamped)
    }

    private fun toggleFullscreen() {
        val s = runCatching { AppShell.stage }.getOrNull() ?: return
        s.isFullScreen = !s.isFullScreen
        fullscreenButton?.graphic =
            Icons.of(if (s.isFullScreen) Icons.FULLSCREEN_EXIT else Icons.FULLSCREEN, 17.0)
        syncSurface()
    }

    // ── transport, driven by mpv's JSON IPC ─────────────────────────────────

    /** Hands the IPC channel over once mpv's pipe is up. From here the controls
     *  and the timeline are live. */
    fun attachIpc(handle: MpvIpc) {
        ipc = handle
        handle.onProperty = { name, value -> onProperty(name, value) }
        handle.onEvent = { event, data -> onEvent(event, data) }
        val observations = listOf(
            "time-pos" to 1L,
            "duration" to 2L,
            "pause" to 3L,
            "volume" to 4L,
            "eof-reached" to 5L,
            "track-list" to 6L,
            "video-format" to 7L,
            "media-title" to 8L,
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
                Fx.run { playButton?.graphic = Icons.of(if (paused) Icons.PLAY else Icons.PAUSE, 17.0) }
            }
            "volume" -> {
                volume = (value as? Number)?.toDouble() ?: 100.0
                Fx.run { volumeSlider?.value = volume }
            }
            "track-list" -> renderTracks(value)
            "video-format" -> {
                val fmt = value?.toString().orEmpty()
                Fx.run {
                    playbackStarted()
                    statusSpinner?.isVisible = false
                    statusSpinner?.isManaged = false
                }
                setStatus(if (fmt.isBlank()) "Audio only" else "Video: $fmt")
            }
            "media-title" -> {
                val t = value?.toString().orEmpty()
                if (t.isNotBlank()) Fx.run { subLabel?.text = t }
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
                        statusLabel?.text = "Playback stopped: $reason"
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
        if (loading) setLoading(false)
        // With no embed the video lives in mpv's own window, so the note saying
        // so has to survive — otherwise the video area is just blank.
        if (embedded) clearMessage()
        statusSpinner?.isVisible = false
        statusSpinner?.isManaged = false
    }

    private fun renderTime() {
        val pos = lastPosition / 1000
        timeLabel?.text = "${clock(pos)} / ${clock(duration.toLong())}"
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
                MenuItem(name).apply { setOnAction { ipc?.setProperty("aid", id.toInt()) } }
            }.toTypedArray())
            subMenu?.items?.setAll(*buildList {
                add(MenuItem("Off").apply { setOnAction { ipc?.setProperty("sid", false) } })
                subs.forEach { (id, name) ->
                    add(MenuItem(name).apply { setOnAction { ipc?.setProperty("sid", id.toInt()) } })
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
