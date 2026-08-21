package desktop.player

import com.hikari.app.data.StreamSource
import desktop.fx.DesktopUi
import desktop.fx.Fx
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

private fun Duration.isUnknownDur(): Boolean = isUnknown || isIndefinite

/**
 * Desktop player window. JavaFX Media plays HLS (m3u8) and MP4; every stream
 * goes through [HlsRelay] so Referer/Cookie/UA headers are honored.
 * DASH (mpd), torrents and YouTube fall back to the system browser with an
 * explanatory dialog.
 */
object DesktopPlayer {

    private var stage: Stage? = null
    private var mediaPlayer: MediaPlayer? = null

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
                showBrowserFallback(title, stream.url)
                return@run
            }
            if (stream.isTorrent || stream.infoHash != null) {
                showBrowserFallback(title, stream.url.ifBlank { "magnet stream (infoHash ${stream.infoHash})" })
                return@run
            }
            val relay = HlsRelay.urlFor(stream.url, stream.headers)
            showStage(title, relay, stream.url)
        }
    }

    private fun showBrowserFallback(title: String, url: String) {
        val stage = Stage()
        stage.title = title
        val label = Label(
            "This stream type isn't playable in the desktop player yet " +
                "(DASH/torrent/YouTube). Open it in your browser instead?"
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
        stage.scene = Scene(box, 480.0, 160.0)
        stage.show()
    }

    private fun showStage(title: String, relayUrl: String, originalUrl: String) {
        val s = stage ?: Stage().also { stage = it }
        s.title = title
        val media = runCatching { Media(relayUrl) }.getOrNull()
        if (media == null) {
            showBrowserFallback(title, originalUrl)
            return
        }
        val player = MediaPlayer(media)
        mediaPlayer = player
        player.isAutoPlay = true
        val mediaView = MediaView(player)
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
            bottom = VBox(0.0, loadingBar, controls)
        }

        player.statusProperty().addListener { _, _, status ->
            when (status) {
                MediaPlayer.Status.PLAYING -> {
                    playBtn.text = "Pause"
                    loadingBar.isVisible = false
                    spinner.isVisible = false
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
        player.setOnError {
            loadingBar.isVisible = false
            spinner.isVisible = false
        }
        player.setOnEndOfMedia {
            playBtn.text = "Replay"
        }

        playBtn.setOnAction {
            when (playBtn.text) {
                "Pause" -> player.pause()
                "Play" -> player.play()
                else -> {
                    player.seek(Duration.ZERO)
                    player.play()
                    playBtn.text = "Pause"
                }
            }
        }

        val fmt: (Duration) -> String = { d ->
            val secs = d.toSeconds().toLong().coerceAtLeast(0)
            "%d:%02d".format(secs / 60, secs % 60)
        }
        player.currentTimeProperty().addListener { _, _, cur ->
            val dur = player.totalDuration
            if (dur.isUnknownDur()) {
                posLabel.text = fmt(cur)
                slider.isDisable = true
            } else {
                posLabel.text = "${fmt(cur)} / ${fmt(dur)}"
                slider.isDisable = false
                slider.value = if (dur.toMillis() > 0) cur.toMillis() / dur.toMillis() else 0.0
            }
        }
        slider.valueProperty().addListener { _, _, v ->
            val dur = player.totalDuration
            if (!slider.isValueChanging && !dur.isUnknownDur() && dur.toMillis() > 0) {
                player.seek(Duration(dur.toMillis() * v.toDouble()))
            }
        }
        slider.valueChangingProperty().addListener { _, _, changing ->
            if (!changing) {
                val dur = player.totalDuration
                if (!dur.isUnknownDur() && dur.toMillis() > 0) {
                    player.seek(Duration(dur.toMillis() * slider.value))
                }
            }
        }

        s.scene = Scene(root, 960.0, 540.0)
        s.setOnCloseRequest {
            player.stop()
            player.dispose()
            mediaPlayer = null
            if (stage === s) stage = null
        }
        s.show()
    }

    fun closeAll() {
        Fx.run {
            mediaPlayer?.let { runCatching { it.stop() }; runCatching { it.dispose() } }
            mediaPlayer = null
            stage?.close()
            stage = null
        }
    }
}
