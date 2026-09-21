package desktop.player

import desktop.fx.DesktopUi
import desktop.fx.Fx
import desktop.ui.Icons
import desktop.ui.Theme
import desktop.ui.Ui
import desktop.ui.WindowChrome
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
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
 * The app's own player.
 *
 * mpv is still the engine — it is what actually decodes these CDN/HLS streams —
 * but the app owns the window: mpv is told to render INTO a surface this window
 * owns (`--wid`, see [WinShell]), so the plain mpv window the app used to hand
 * playback to no longer exists. What is left is one window with the video in it
 * and, under it, the transport a generic player cannot know about:
 *
 *   - a timeline with a scrubbable seek bar and real time labels,
 *   - play/pause, ±10s, volume and speed,
 *   - the file's actual audio and subtitle tracks, by name and language,
 *   - what the stream really is (container/format/codec) and why it failed,
 *   - "Next episode", fired the moment mpv reports the file ended,
 *   - the position, handed back for resume/history,
 *   - keyboard control (space, ←/→, ↑/↓, F, Esc).
 *
 * When the surface cannot be created (not Windows, no JNA, no window handle)
 * [open] returns null and mpv keeps its own window; the controls below then
 * still drive it, so nothing is lost.
 */
object PlayerWindow {

    /** How often the video surface is glued to the video area. The surface is a
     *  real window, so a move of the player (a drag, a maximise, a DPI change)
     *  has to be followed — events cover the common cases, this covers the rest. */
    private const val SYNC_MS = 180.0

    private var stage: Stage? = null
    private var videoStage: Stage? = null

    /** The native handle of the surface mpv renders into, and mpv's own child
     *  window inside it (found once mpv attaches, then kept filled). */
    private var videoHwnd: Long? = null
    private var mpvChild: Long? = null
    private var childW = 0
    private var childH = 0
    private var lastChildProbe = 0L
    private var surfaceHidden = false

    /** Set while the window is being torn down, so a close request cannot
     *  re-enter (the close notification calls back into the owner, which calls
     *  [closeAll] again). */
    private var teardown = false

    private var ipc: MpvIpc? = null
    private var syncTimer: Timeline? = null

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
    private var nextButton: Button? = null
    private var fullscreenButton: Button? = null
    private var messageLabel: Label? = null
    private var messageActions: HBox? = null
    private var messageBox: VBox? = null

    private var duration = 0.0
    private var scrubbing = false
    private var lastPosition = 0L
    private var volume = 100.0
    private var paused = false

    private var onNext: (() -> Unit)? = null
    private var onPosition: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Opens the player for a new stream. Returns the handle mpv should render
     * into, or null when there is no surface (mpv then opens its own window).
     */
    fun open(
        title: String,
        hasNext: Boolean,
        next: (() -> Unit)?,
        position: ((Long, Long) -> Unit)?,
        closed: (() -> Unit)?,
    ): Long? {
        onNext = next
        onPosition = position
        onClosed = closed
        return Fx.runBlock {
            val existing = stage
            if (existing != null && existing.isShowing) {
                // Advancing to another episode reuses the window: rebuilding it
                // would make the player blink and lose the size/position the
                // user just set between episodes.
                teardown = false
                titleLabel?.text = title
                nextButton?.isVisible = hasNext
                nextButton?.isManaged = hasNext
                resetForNewStream()
                videoHwnd
            } else {
                closeInternal()
                // The teardown flag is set by closeInternal so a re-entrant close
                // request can be ignored; a NEW window is live again.
                teardown = false
                buildWindow(title, hasNext)
                startVideoSurface()
            }
        }
    }

    /** Clears everything that belongs to the previous stream, so the reused
     *  window starts from a clean transport. */
    private fun resetForNewStream() {
        duration = 0.0
        lastPosition = 0L
        scrubbing = false
        paused = false
        // mpv starts as a new process, so the child window we were filling is
        // gone; the probe in syncSurface picks the new one up.
        mpvChild = null
        childW = 0
        childH = 0
        lastChildProbe = 0L
        seekBar?.value = 0.0
        seekBar?.isDisable = true
        timeLabel?.text = "00:00 / 00:00"
        playButton?.graphic = Icons.of(Icons.PAUSE, 16.0)
        statusLabel?.text = ""
        statusLabel?.styleClass?.remove("h-danger")
        subLabel?.text = "Hikari player"
        audioMenu?.items?.setAll()
        subMenu?.items?.setAll()
        clearMessage()
    }

    fun isOpen(): Boolean = stage != null

    /** Live status text on the transport bar — "Starting the player…", the
     *  reason a stream died, the codec that is playing. */
    fun setStatus(text: String, isError: Boolean = false, busy: Boolean = false) {
        Fx.run {
            statusLabel?.text = text
            statusLabel?.styleClass?.remove("h-danger")
            if (isError) statusLabel?.styleClass?.add("h-danger")
            statusSpinner?.isVisible = busy
            statusSpinner?.isManaged = busy
        }
    }

    /**
     * Shows a message over the video area, with buttons. The video surface is a
     * window ABOVE this one, so it is hidden while a message is up — otherwise
     * the message would be behind a black rectangle. Returns false when there is
     * no player window to put a message in (the caller then shows a dialog).
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
        message(reason, actions)
        return true
    }

    /**
     * A short note over the video area (no buttons) — used when the video is
     * playing somewhere this window cannot show, e.g. a machine where the embed
     * is unavailable and mpv keeps its own window.
     */
    fun note(text: String) {
        if (!isOpen()) return
        message(text, emptyList())
    }

    /** Closes the window without notifying the owner (used while tearing
     *  playback down from the outside). */
    fun closeAll() {
        Fx.run { closeInternal() }
    }

    // ── window ──────────────────────────────────────────────────────────────

    private fun buildWindow(title: String, hasNext: Boolean) {
        val s = Stage()
        s.title = title.take(120)
        s.minWidth = 660.0
        s.minHeight = 420.0
        val chrome = WindowChrome(s)

        val headTitle = Label(title).apply {
            styleClass.add("player-title")
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
        }
        val headSub = Label("Hikari player").apply { styleClass.add("player-sub") }
        val titleBox = VBox(0.0, headTitle, headSub).apply { minWidth = 0.0 }
        chrome.makeDraggable(titleBox)
        val header = HBox(10.0, titleBox, Ui.spacer(), chrome.controls()).apply {
            styleClass.add("player-head")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }

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
        videoArea.children.setAll(msgBox)

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
        val back10 = playerButton(null, "Back 10 seconds (←)") { ipc?.post("seek", -10, "relative") }
            .apply { text = "-10s" }
        val fwd10 = playerButton(null, "Forward 10 seconds (→)") { ipc?.post("seek", 10, "relative") }
            .apply { text = "+10s" }

        val volume = Slider(0.0, 130.0, 100.0).apply {
            styleClass.add("player-vol")
            prefWidth = 108.0
            minWidth = 70.0
            setOnMouseReleased {
                this@PlayerWindow.volume = value
                ipc?.setProperty("volume", value)
            }
        }

        val speed = MenuButton("Speed").apply {
            styleClass.add("player-menu")
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                items.add(MenuItem("${rate}x").apply { setOnAction { ipc?.setProperty("speed", rate) } })
            }
        }

        val audio = MenuButton("Audio").apply { styleClass.add("player-menu") }
        val subs = MenuButton("Subtitles").apply { styleClass.add("player-menu") }

        val next = Button("Next episode").apply {
            styleClass.addAll("btn", "btn-primary")
            isVisible = hasNext
            isManaged = hasNext
            setOnAction { onNext?.invoke() }
        }

        val full = playerButton(Icons.FULLSCREEN, "Fullscreen (F)") { toggleFullscreen() }

        val infoRow = HBox(10.0, time, spinner, status, Ui.spacer(), next).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val buttonRow = HBox(
            8.0, play, back10, fwd10, volume, speed, audio, subs, Ui.spacer(), full,
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        val controls = VBox(7.0, seek, infoRow, buttonRow).apply {
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
        nextButton = next
        fullscreenButton = full
        messageLabel = msgLabel
        messageActions = msgActions
        messageBox = msgBox

        val scene = Theme.style(Scene(chrome.build(body), 1120.0, 700.0))
        scene.setOnKeyPressed { e -> handleKey(e.code) }
        s.scene = scene
        s.setOnCloseRequest { requestClose() }
        // The video surface is a separate window glued to the video area, so
        // anything that moves or resizes this window has to move it too.
        videoArea.layoutBoundsProperty().addListener { _, _, _ -> syncSurface() }
        s.xProperty().addListener { _, _, _ -> syncSurface() }
        s.yProperty().addListener { _, _, _ -> syncSurface() }
        s.widthProperty().addListener { _, _, _ -> syncSurface() }
        s.heightProperty().addListener { _, _, _ -> syncSurface() }
        s.fullScreenProperty().addListener { _, _, _ -> syncSurface() }
        stage = s
        s.show()
        runCatching {
            val b = Screen.getPrimary().visualBounds
            s.x = b.minX + (b.width - s.width) / 2.0
            s.y = b.minY + (b.height - s.height) / 2.0
        }
        syncTimer = Timeline(
            KeyFrame(Duration.millis(SYNC_MS), EventHandler<ActionEvent> { syncSurface() }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun playerButton(icon: String?, tooltip: String, onClick: () -> Unit): Button =
        Button().apply {
            styleClass.add("player-btn")
            if (icon != null) graphic = Icons.of(icon, 16.0)
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
        runCatching { ipc?.close() }
        ipc = null
        runCatching { videoStage?.close() }
        videoStage = null
        videoHwnd = null
        mpvChild = null
        childW = 0
        childH = 0
        surfaceHidden = false
        runCatching { stage?.close() }
        stage = null
        titleLabel = null
        subLabel = null
        timeLabel = null
        statusLabel = null
        statusSpinner = null
        seekBar = null
        playButton = null
        volumeSlider = null
        audioMenu = null
        subMenu = null
        nextButton = null
        fullscreenButton = null
        messageLabel = null
        messageActions = null
        messageBox = null
        duration = 0.0
        scrubbing = false
        paused = false
    }

    // ── the video surface ───────────────────────────────────────────────────

    /**
     * Creates the window mpv renders into: borderless, owned by the player
     * window (so it minimises, moves and closes with it, and never takes a
     * taskbar slot of its own), and nothing but video.
     *
     * Its native handle is found by title — a UNIQUE title per launch, so a
     * stale window from an earlier player can never be matched.
     */
    private fun startVideoSurface(): Long? {
        val owner = stage ?: return null
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
        vs.scene = Scene(surfaceRoot, 32.0, 18.0).apply { fill = Color.BLACK }
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
        // A cached handle from a previous episode must be dropped once mpv has
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
        val s = stage ?: return@runCatching 1.0
        val screen = Screen.getScreensForRectangle(s.x, s.y, 1.0, 1.0).firstOrNull() ?: Screen.getPrimary()
        screen.outputScaleX.takeIf { it > 0.0 } ?: 1.0
    }.getOrDefault(1.0)

    // ── messages over the video ─────────────────────────────────────────────

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
            val vs = videoStage
            if (vs != null && vs.isShowing) {
                vs.hide()
                surfaceHidden = true
            }
        }
    }

    private fun clearMessage() {
        messageBox?.isVisible = false
        messageBox?.isManaged = false
        if (surfaceHidden) {
            surfaceHidden = false
            runCatching {
                videoStage?.show()
                syncSurface()
            }
        }
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    private fun handleKey(code: KeyCode) {
        when (code) {
            KeyCode.SPACE -> ipc?.post("cycle", "pause")
            KeyCode.LEFT -> ipc?.post("seek", -10, "relative")
            KeyCode.RIGHT -> ipc?.post("seek", 10, "relative")
            KeyCode.UP -> setVolume(volume + 5)
            KeyCode.DOWN -> setVolume(volume - 5)
            KeyCode.F, KeyCode.F11 -> toggleFullscreen()
            KeyCode.ESCAPE -> requestClose()
            else -> return
        }
    }

    private fun setVolume(next: Double) {
        val clamped = next.coerceIn(0.0, 130.0)
        volume = clamped
        volumeSlider?.value = clamped
        ipc?.setProperty("volume", clamped)
    }

    private fun toggleFullscreen() {
        val s = stage ?: return
        s.isFullScreen = !s.isFullScreen
        fullscreenButton?.graphic =
            Icons.of(if (s.isFullScreen) Icons.FULLSCREEN_EXIT else Icons.FULLSCREEN, 16.0)
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
            clearMessage()
            statusLabel?.text = ""
            statusSpinner?.isVisible = false
            statusSpinner?.isManaged = false
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
                Fx.run { playButton?.graphic = Icons.of(if (paused) Icons.PLAY else Icons.PAUSE, 16.0) }
            }
            "volume" -> {
                volume = (value as? Number)?.toDouble() ?: 100.0
                Fx.run { volumeSlider?.value = volume }
            }
            "track-list" -> renderTracks(value)
            "video-format" -> {
                val fmt = value?.toString().orEmpty()
                Fx.run {
                    statusLabel?.text = if (fmt.isBlank()) "Audio only" else "Video: $fmt"
                    statusSpinner?.isVisible = false
                    statusSpinner?.isManaged = false
                }
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
        if (event == "end-file" || event == "ipc-closed") {
            val reason = data.optString("reason").ifBlank { data.optString("error") }
            if (reason.isNotBlank() && reason != "eof" && reason != "quit" && reason != "stop") {
                Fx.run {
                    statusLabel?.text = "Playback stopped: $reason"
                    statusSpinner?.isVisible = false
                    statusSpinner?.isManaged = false
                }
            }
        }
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
