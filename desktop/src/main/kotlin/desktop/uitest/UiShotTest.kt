package desktop.uitest

import com.hikari.app.HikariApp
import com.hikari.app.data.StreamSource
import desktop.fx.Fx
import desktop.player.PlayerWindow
import desktop.ui.AppShell
import desktop.ui.Screen
import desktop.ui.Theme
import java.io.File
import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.util.Duration
import javax.imageio.ImageIO

/**
 * Renders the real app's screens — and the real player layer — to PNG files, so
 * the layout can be LOOKED at instead of argued about.
 *
 * This exists because a compile and a smoke test cannot see a control bar whose
 * buttons are mis-sized, a title that is squeezed to nothing on a narrow window,
 * or a message box that overlaps the seek bar. The harness boots the same object
 * graph [desktop.MainKt] boots (AppShell + Theme + the player layer), walks a
 * fixed sequence of screens and states, and writes one PNG per state.
 *
 * Usage (CI, or by hand on Windows):
 *   java -cp "jpackage-input/<jars>" desktop.uitest.UiShotTestKt [output-dir]
 *
 * It never talks to a provider and never launches mpv: the player states are
 * driven through the layer's own public API (`open`, `showFailure`), so what is
 * captured is exactly the UI the user gets.
 */
fun main(args: Array<String>) {
    HikariApp().init()
    Application.launch(UiShotApp::class.java, *(args.ifEmpty { arrayOf("ui-shots") }))
}

class UiShotApp : Application() {

    override fun start(stage: Stage) {
        Fx.onStart()
        val out = File(parameters.raw.firstOrNull() ?: "ui-shots").apply { mkdirs() }
        println("UiShotTest: writing to " + out.absolutePath)

        stage.title = "Hikari"
        stage.initStyle(StageStyle.UNDECORATED)
        val root = AppShell.create(stage)
        val scene = Theme.style(Scene(root, WIDE_W, WIDE_H))
        stage.scene = scene
        stage.minWidth = 320.0
        stage.minHeight = 420.0
        stage.show()
        AppShell.show(Screen.Home)

        val sources = listOf(
            StreamSource("VidCloud", "https://example.com/v.m3u8", isM3u8 = true),
            StreamSource("StreamTape", "https://example.com/s.m3u8", isM3u8 = true),
            StreamSource("Filemoon", "https://example.com/f.m3u8", isM3u8 = true),
        )

        // Layout checks that are not just "look at the PNG": counted here and used
        // as the process exit code, so a bar that overflows its own control fails
        // the build instead of shipping quietly.
        val failures = intArrayOf(0)

        val steps = ArrayList<Pair<Long, () -> Unit>>()
        fun shot(name: String, note: String = "") {
            steps += 900L to {
                val file = File(out, "$name.png")
                save(scene.snapshot(WritableImage(scene.width.toInt(), scene.height.toInt())), file)
                println("UiShotTest: $name  " + scene.width.toInt() + "x" + scene.height.toInt() +
                    "  " + file.length() + " bytes" + if (note.isBlank()) "" else "  ($note)")
            }
        }

        // ── the app's own screens ───────────────────────────────────────────
        steps += 2600L to { }
        shot("a-home", "sidebar + top bar + home rails")
        steps += 400L to { AppShell.show(Screen.Extensions) }
        steps += 2200L to { }
        shot("b-extensions", "the repo/extension list")
        steps += 400L to { AppShell.show(Screen.Settings) }
        steps += 1400L to { }
        shot("c-settings", "settings list")

        // ── the player layer, wide ──────────────────────────────────────────
        steps += 400L to {
            AppShell.show(Screen.Home)
            PlayerWindow.open(
                title = "The Grand Budapest Hotel",
                hasNext = true,
                next = { },
                position = { _, _ -> },
                closed = { },
                sources = sources,
                sourceName = "VidCloud",
                onPickSource = { },
            )
        }
        steps += 1200L to { }
        shot("d-player-loading", "title/status chips + control bar + loading pane")

        steps += 200L to {
            PlayerWindow.showFailure(
                "The stream loaded, but no picture came up. It may be geo-blocked, or the source " +
                    "may have gone down. Try another source, or open it in your browser.",
                "https://example.com/v.m3u8",
                retry = { },
                tryAnyway = { },
            )
        }
        steps += 800L to { }
        shot("e-player-error", "message box + its buttons")

        // ── the player with the picture up: the chrome comes and goes ───────
        steps += 200L to {
            // There is no mpv in this harness, so the layer is simply told the
            // picture is up — otherwise the loading overlay (which holds the
            // bars in place) would be the only state capturable here.
            PlayerWindow.previewPlaying()
        }
        steps += 1400L to { PlayerWindow.previewChrome(false) }
        steps += 400L to { }
        shot("h-player-playing-hidden", "bars away: the picture fills the window")
        steps += 200L to { PlayerWindow.previewChrome(true) }
        steps += 400L to { }
        shot("i-player-playing-chrome", "bars back: transport left, pickers right")

        // ── the seek bar's geometry ─────────────────────────────────────────
        // A Slider's skin draws its own `.track` node, and JavaFX widens that
        // node by the CSS `-fx-background-radius` on EACH side (see the note in
        // theme.css): with a pill radius, a 620px scrubber painted a hairline
        // the whole width of the window, under the Source/Audio/Subs buttons —
        // which is exactly what a seek bar that "runs over the buttons" is. This
        // measures the node instead of trusting the stylesheet.
        steps += 200L to {
            val seek = scene.lookup(".player-seek")
            val track = scene.lookup(".player-seek .track")
            val seekW = seek?.layoutBounds?.width ?: 0.0
            val trackW = track?.layoutBounds?.width ?: 0.0
            println("UiShotTest: seek layout width=" + seekW + "  track layout width=" + trackW +
                "  track h=" + (track?.layoutBounds?.height ?: 0.0))
            println("UiShotTest: sliders in scene=" + scene.root.lookupAll(".slider").size)
            if (seekW <= 0.0 || trackW <= 0.0) {
                println("UiShotTest: FAIL the seek bar is not in the scene as expected")
                failures[0]++
            } else if (trackW > seekW + 12.0) {
                println("UiShotTest: FAIL the seek track is wider than its slider (" + trackW + " vs " + seekW + ")")
                failures[0]++
            } else {
                println("UiShotTest: OK   seek track stays inside its slider")
            }
        }

        // ── the player layer on a phone-width window ────────────────────────
        steps += 200L to {
            PlayerWindow.closeAll()
            stage.width = NARROW_W
            stage.height = NARROW_H
        }
        steps += 700L to {
            PlayerWindow.open(
                title = "The Grand Budapest Hotel",
                hasNext = true,
                next = { },
                position = { _, _ -> },
                closed = { },
                sources = sources,
                sourceName = "VidCloud",
                onPickSource = { },
            )
        }
        steps += 1000L to { }
        shot("f-player-narrow", "the bar on a 460px-wide window")

        steps += 200L to {
            PlayerWindow.showFailure(
                "The stream loaded, but no picture came up. Try another source.",
                null,
                retry = { },
            )
        }
        steps += 800L to { }
        shot("g-player-narrow-error", "message box on a 460px-wide window")

        steps += 200L to {
            PlayerWindow.closeAll()
            stage.width = WIDE_W
            stage.height = WIDE_H
        }

        // ── run it ──────────────────────────────────────────────────────────
        var index = 0
        fun pump() {
            if (index >= steps.size) {
                if (failures[0] > 0) {
                    println("UiShotTest: " + failures[0] + " LAYOUT CHECK(S) FAILED")
                    kotlin.system.exitProcess(1)
                    return
                }
                println("UiShotTest: OK (" + steps.size + " steps)")
                // The app's own work (Home's fetches, OkHttp's pools) keeps
                // non-daemon threads alive; a test harness must not sit and wait
                // for them.
                kotlin.system.exitProcess(0)
                return
            }
            val step = steps[index++]
            val pause = PauseTransition(Duration.millis(step.first.toDouble()))
            pause.setOnFinished {
                runCatching { step.second() }.onFailure { println("UiShotTest: step failed: " + it) }
                pump()
            }
            pause.play()
        }
        pump()
    }

    private fun save(image: javafx.scene.image.Image, file: File) {
        val w = image.width.toInt()
        val h = image.height.toInt()
        val reader = image.pixelReader ?: return
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(w)
        for (y in 0 until h) {
            reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w)
            out.setRGB(0, y, w, 1, row, 0, w)
        }
        ImageIO.write(out, "png", file)
    }

    private companion object {
        const val WIDE_W = 1280.0
        const val WIDE_H = 820.0
        const val NARROW_W = 460.0
        const val NARROW_H = 860.0
    }
}
