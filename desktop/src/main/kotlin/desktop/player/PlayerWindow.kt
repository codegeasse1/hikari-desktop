package desktop.player

import desktop.fx.Fx
import desktop.ui.Theme
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Slider
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own player window.
 *
 * Video is still rendered by mpv — that is what actually plays these streams —
 * but the app now owns the PLAYBACK: this window is a real transport surface
 * driven over mpv's JSON IPC, so the things a player needs and mpv's generic
 * overlay cannot know about are here:
 *
 *   - a timeline with a scrubbable seek bar and real time labels,
 *   - play/pause, ±10s, volume and speed,
 *   - the file's actual audio and subtitle tracks, by name and language,
 *   - what the stream really is (container/format/codec) and why it failed,
 *   - "Next episode", fired the moment mpv reports the file ended,
 *   - the position, handed back for resume/history.
 *
 * It is opened next to the mpv window and closes with it. When IPC does not
 * connect, [attach] returns false and the caller keeps the plain player it had —
 * a player that cannot be driven must never be worse than no player window.
 */
object PlayerWindow {

    private var stage: Stage? = null
    private var ipc: MpvIpc? = null

    private var seekBar: Slider? = null
    private var timeLabel: Label? = null
    private var playButton: Button? = null
    private var statusLabel: Label? = null
    private var titleLabel: Label? = null
    private var volumeSlider: Slider? = null
    private var audioMenu: MenuButton? = null
    private var subMenu: MenuButton? = null
    private var nextButton: Button? = null

    private var duration = 0.0
    private var scrubbing = false
    private var lastPosition = 0L

    /** Callbacks owned by the caller. */
    private var onNext: (() -> Unit)? = null
    private var onPosition: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null

    /**
     * Opens the player surface for a running mpv [handle]. Returns false when
     * the surface could not be built (IPC never came up), in which case mpv is
     * left exactly as it was.
     */
    fun attach(
        title: String,
        handle: MpvIpc,
        hasNext: Boolean,
        next: (() -> Unit)?,
        position: ((Long, Long) -> Unit)?,
        closed: (() -> Unit)?,
    ): Boolean {
        ipc = handle
        onNext = next
        onPosition = position
        onClosed = closed

        var ok = false
        Fx.run {
            closeInternal()
            val root = buildUi(title, hasNext)
            val s = Stage()
            s.title = title.take(120)
            s.scene = Theme.style(Scene(root, 560.0, 190.0))
            s.minWidth = 460.0
            s.setOnCloseRequest {
                closeInternal()
                onClosed?.invoke()
            }
            stage = s
            s.show()
            ok = true
        }
        if (!ok) return false

        // Push current state in, then follow it.
        observe()
        return true
    }

    fun isOpen(): Boolean = stage != null

    fun closeAll() {
        Fx.run { closeInternal() }
    }

    private fun closeInternal() {
        runCatching { stage?.close() }
        stage = null
        ipc = null
        seekBar = null
        timeLabel = null
        playButton = null
        statusLabel = null
        titleLabel = null
        volumeSlider = null
        audioMenu = null
        subMenu = null
        nextButton = null
        duration = 0.0
    }

    private fun buildUi(title: String, hasNext: Boolean): Region {
        titleLabel = Label(title).apply {
            style = "-fx-font-size: 14px; -fx-font-weight: bold;"
            maxWidth = Double.MAX_VALUE
        }
        statusLabel = Label("Connecting to the player…").apply {
            style = "-fx-font-size: 11px; -fx-opacity: 0.75;"
            maxWidth = Double.MAX_VALUE
        }

        timeLabel = Label("00:00 / 00:00").apply { style = "-fx-font-size: 11px;" }

        seekBar = Slider(0.0, 1.0, 0.0).apply {
            isDisable = true
            // A scrub must not fight the position updates mpv is streaming in.
            setOnMousePressed { scrubbing = true }
            setOnMouseReleased {
                scrubbing = false
                val d = duration
                if (d > 0) ipc?.post("seek", value * d, "absolute")
            }
        }

        playButton = Button("Pause").apply {
            setOnAction { ipc?.post("cycle", "pause") }
        }

        val back = Button("-10s").apply { setOnAction { ipc?.post("seek", -10, "relative") } }
        val fwd = Button("+10s").apply { setOnAction { ipc?.post("seek", 10, "relative") } }

        volumeSlider = Slider(0.0, 130.0, 100.0).apply {
            prefWidth = 110.0
            setOnMouseReleased { ipc?.setProperty("volume", value) }
        }

        val speed = MenuButton("Speed").apply {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                items.add(MenuItem("${rate}x").apply { setOnAction { ipc?.setProperty("speed", rate) } })
            }
        }

        audioMenu = MenuButton("Audio")
        subMenu = MenuButton("Subtitles")

        nextButton = Button("Next episode").apply {
            isVisible = hasNext
            isManaged = hasNext
            setOnAction { onNext?.invoke() }
        }

        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val controls = HBox(
            8.0, playButton, back, fwd, volumeSlider!!, speed, audioMenu!!, subMenu!!, spacer, nextButton!!,
        ).apply { alignment = Pos.CENTER_LEFT }

        return VBox(10.0, titleLabel!!, seekBar!!, HBox(10.0, timeLabel!!, statusLabel!!), controls).apply {
            padding = Insets(14.0)
        }
    }

    /** Registers the property observations this window renders. */
    private fun observe() {
        val handle = ipc ?: return
        handle.observe("time-pos", 1)
        handle.observe("duration", 2)
        handle.observe("pause", 3)
        handle.observe("volume", 4)
        handle.observe("eof-reached", 5)
        handle.observe("track-list", 6)
        handle.observe("video-format", 7)
        handle.observe("media-title", 8)
    }

    /**
     * Feed one mpv property change (or event) in. Called from the IPC reader
     * thread — everything it touches is hopped to the FX thread.
     */
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
                val paused = value == true
                Fx.run { playButton?.text = if (paused) "Play" else "Pause" }
            }
            "volume" -> {
                val v = (value as? Number)?.toDouble() ?: 100.0
                Fx.run { volumeSlider?.value = v }
            }
            "track-list" -> renderTracks(value)
            "video-format" -> {
                val fmt = value?.toString().orEmpty()
                Fx.run { statusLabel?.text = if (fmt.isBlank()) "Audio only" else "Video: $fmt" }
            }
            "media-title" -> {
                val t = value?.toString().orEmpty()
                if (t.isNotBlank()) Fx.run { titleLabel?.text = t }
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
                Fx.run { statusLabel?.text = "Playback stopped: $reason" }
            }
        }
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
        val selectedAudio = ipc?.getPropertyString("aid")
        Fx.run {
            audioMenu?.items?.setAll(*audio.map { (id, name) ->
                MenuItem(name).apply {
                    setOnAction { ipc?.setProperty("aid", id.toInt()) }
                }
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

    private fun renderTime() {
        val pos = lastPosition / 1000
        val dur = (duration).toLong()
        timeLabel?.text = "${clock(pos)} / ${clock(dur)}"
    }

    private fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
