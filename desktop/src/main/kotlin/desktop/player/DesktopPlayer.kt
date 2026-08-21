package desktop.player

import com.hikari.app.data.StreamSource
import desktop.fx.DesktopUi
import desktop.fx.Fx
import javafx.animation.PauseTransition
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.Slider
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.stage.Stage
import javafx.util.Duration
import desktop.ui.Theme

private fun Duration.isUnknownDur(): Boolean = isUnknown || isIndefinite

/**
 * Desktop player window. JavaFX Media plays HLS (m3u8) and MP4; streams that
 * need Referer/Cookie/UA headers go through [HlsRelay] (JavaFX can't send
 * custom headers). DASH (mpd), torrents and YouTube fall back to the system
 * browser with an explanatory dialog.
 *
 * Never a silent black window: if the media errors (codec/CDN refusal) the
 * player retries the alternate URL, and if that fails too it closes and asks
 * to open the stream in the browser, showing the real error. If playback
 * stalls without an error for [STALL_TIMEOUT_MS], a "Open in browser" button
 * appears instead of spinning forever.
 */
object DesktopPlayer {

    private const val STALL_TIMEOUT_MS = 25_000L

    private var stage: Stage? = null

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
                showBrowserFallback(title, stream.url, "This stream uses DASH, which the desktop player can't play yet.")
                return@run
            }
            if (stream.isTorrent || stream.infoHash != null) {
                showBrowserFallback(title, stream.url.ifBlank { "magnet stream (infoHash ${stream.infoHash})" },
                    "This is a torrent stream, which the desktop player can't play yet.")
                return@run
            }
            val relay = HlsRelay.urlFor(stream.url, stream.headers)
            // No custom headers → try the real URL first (JavaFX's own HLS
            // handling is often more tolerant); on error fall back to the relay.
            val candidates = if (stream.headers.isEmpty()) listOf(stream.url, relay) else listOf(relay)
            showStage(title, candidates, stream.url)
        }
    }

    private fun showBrowserFallback(title: String, url: String, reason: String? = null) {
        val stage = Stage()
        stage.title = title
        val label = Label(
            listOfNotNull(
                reason,
                "Open it in your browser instead?",
            ).joinToString(" ").ifBlank { "Open it in your browser instead?" }
        )
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
        stage.scene = Theme.style(Scene(box, 520.0, 180.0))
        stage.show()
    }

    private fun showStage(title: String, candidateUrls: List<String>, originalUrl: String) {
        val s = stage ?: Stage().also { stage = it }
        s.title = title

        val mediaView = MediaView()
        mediaView.fitWidthProperty().bind(s.widthProperty())
        mediaView.fitHeightProperty().bind(s.heightProperty())
        mediaView.isPreserveRatio = true

        val playBtn = Button("Pause")
        val posLabel = Label("0:00 / 0:00")
        val slider = Slider(0.0, 1.0, 0.0)
        val spinner = ProgressBar(-1.0).apply { isVisible = false; maxWidth = 90.0 }
        val controls = HBox(10.0, playBtn, slider, posLabel, spinner).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 14.0, 8.0, 14.0)
            styleClass.add("player-controls")
        }

        // Shown when playback stalls or fails, so a black window is never
        // silent: the user always gets a path to actually watching.
        val hint = HBox(10.0).apply {
            isVisible = false
            isManaged = false
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 14.0, 8.0, 14.0)
            children.addAll(
                Theme.label("Not playing? The stream may need a browser/codec the app doesn't have.", size = 12.0, dim = true),
                Button("Open in browser").apply {
                    styleClass.add("btn")
                    setOnAction { DesktopUi.open(originalUrl) }
                },
            )
        }

        val loadingBar = ProgressBar(-1.0).apply {
            prefWidthProperty().bind(s.widthProperty())
            isVisible = false
            maxHeight = 4.0
            minHeight = 4.0
            prefHeight = 4.0
            isMouseTransparent = true
        }
        val root = BorderPane().apply {
            center = StackPane(mediaView)
            bottom = VBox(0.0, loadingBar, controls, hint)
        }

        var attempt = 0
        var lastError: String? = null
        val stall = PauseTransition(Duration(STALL_TIMEOUT_MS.toDouble()))
        val fmt: (Duration) -> String = { d ->
            val secs = d.toSeconds().toLong().coerceAtLeast(0)
            "%d:%02d".format(secs / 60, secs % 60)
        }
        stall.setOnFinished {
            val p = mediaView.mediaPlayer
            if (p != null && p.status != MediaPlayer.Status.PLAYING) {
                hint.isVisible = true
                hint.isManaged = true
            }
        }

        fun attachPlayer(p: MediaPlayer) {
            mediaView.mediaPlayer = p
            p.isAutoPlay = true
            stall.playFromStart()
            p.statusProperty().addListener { _, _, status ->
                stall.playFromStart()
                when (status) {
                    MediaPlayer.Status.PLAYING -> {
                        playBtn.text = "Pause"
                        loadingBar.isVisible = false
                        spinner.isVisible = false
                        hint.isVisible = false
                        hint.isManaged = false
                        stall.stop()
                    }
                    MediaPlayer.Status.PAUSED -> {
                        playBtn.text = "Play"
                        loadingBar.isVisible = false
                        spinner.isVisible = false
                    }
                    MediaPlayer.Status.STALLED, MediaPlayer.Status.UNKNOWN -> {
                        loadingBar.isVisible = true
                        spinner.isVisible = true
                    }
                    else -> loadingBar.isVisible = false
                }
            }
            p.setOnError {
                lastError = p.error?.message?.take(300) ?: "JavaFX media error"
                loadingBar.isVisible = false
                spinner.isVisible = false
                runCatching { p.stop() }
                runCatching { p.dispose() }
                mediaView.mediaPlayer = null
                stall.stop()
                next()
            }
            p.setOnEndOfMedia {
                playBtn.text = "Replay"
            }
            p.currentTimeProperty().addListener { _, _, cur ->
                val dur = p.totalDuration
                if (dur.isUnknownDur()) {
                    posLabel.text = fmt(cur)
                    slider.isDisable = true
                } else {
                    posLabel.text = "${fmt(cur)} / ${fmt(dur)}"
                    slider.isDisable = false
                    slider.value = if (dur.toMillis() > 0) cur.toMillis() / dur.toMillis() else 0.0
                }
            }
        }

        playBtn.setOnAction {
            val p = mediaView.mediaPlayer ?: return@setOnAction
            when (playBtn.text) {
                "Pause" -> p.pause()
                "Play" -> p.play()
                else -> {
                    p.seek(Duration.ZERO)
                    p.play()
                    playBtn.text = "Pause"
                }
            }
        }

        slider.valueProperty().addListener { _, _, v ->
            val p = mediaView.mediaPlayer ?: return@addListener
            val dur = p.totalDuration
            if (!slider.isValueChanging && !dur.isUnknownDur() && dur.toMillis() > 0) {
                p.seek(Duration(dur.toMillis() * v.toDouble()))
            }
        }
        slider.valueChangingProperty().addListener { _, _, changing ->
            val p = mediaView.mediaPlayer ?: return@addListener
            if (!changing) {
                val dur = p.totalDuration
                if (!dur.isUnknownDur() && dur.toMillis() > 0) {
                    p.seek(Duration(dur.toMillis() * slider.value))
                }
            }
        }

        fun next() {
            if (attempt >= candidateUrls.size) {
                s.close()
                if (stage === s) stage = null
                showBrowserFallback(title, originalUrl,
                    "The stream failed to play${if (lastError != null) " ($lastError)" else ""}. Open it in your browser instead?")
                return
            }
            val url = candidateUrls[attempt]
            attempt++
            val media = runCatching { Media(url) }.getOrNull()
            if (media == null) {
                lastError = "could not read media from $url"
                next()
                return
            }
            val player = MediaPlayer(media)
            attachPlayer(player)
        }

        s.scene = Theme.style(Scene(root, 960.0, 540.0))
        s.setOnCloseRequest {
            mediaView.mediaPlayer?.let { p ->
                runCatching { p.stop() }
                runCatching { p.dispose() }
            }
            mediaView.mediaPlayer = null
            stall.stop()
            if (stage === s) stage = null
        }
        s.show()
        next()
    }

    fun closeAll() {
        Fx.run {
            stage?.close()
            stage = null
        }
    }
}
