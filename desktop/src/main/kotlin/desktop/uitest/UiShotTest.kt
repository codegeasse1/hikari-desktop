package desktop.uitest

import com.hikari.app.HikariApp
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.StreamSource
import desktop.fx.Fx
import desktop.player.PlayerWindow
import desktop.ui.AppShell
import desktop.ui.DetailScreenTestSeam
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
        // The player's bars float over the picture in windows of their own when
        // the video can be embedded (see PlayerWindow.overlaysOn), and a window
        // of its own is not in this scene to be snapshotted. These shots are the
        // app-side look, so the bars are kept in the layout for them;
        // FloatingBarsSelfTest verifies the floating arrangement with real mpv
        // and real screen pixels.
        steps += 400L to {
            PlayerWindow.setFloatBars(false)
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
            // The Quality picker has to BE there (the Android player's quality
            // button). There is no mpv in this harness, so there is no track list
            // yet: the picker must be present and honest about it (disabled,
            // empty) rather than offering qualities it does not know.
            val pills = scene.root.lookupAll(".player-pill")
                .filterIsInstance<javafx.scene.control.MenuButton>()
            println("UiShotTest: player pickers = " + pills.map { it.text }.joinToString(" | "))
            val quality = pills.firstOrNull { it.text == "Quality" }
            when {
                quality == null -> {
                    println("UiShotTest: FAIL the player bar has no Quality picker")
                    failures[0]++
                }
                !quality.isDisable || quality.items.isNotEmpty() -> {
                    println(
                        "UiShotTest: FAIL the Quality picker claims qualities with no track list (items=" +
                            quality.items.size + " disabled=" + quality.isDisable + ")",
                    )
                    failures[0]++
                }
                else -> println("UiShotTest: OK   the Quality picker is in the bar (no tracks without mpv)")
            }
        }

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
            // Every control on the bar must fit its own label. This is the check
            // for "the player's buttons are cut off: Quality shows as 'Qua…' and
            // the time as '0…'", which happened as soon as the bar had one more
            // picker than the old fixed-width budget allowed for.
            val squeezed = squeezedBarControls(scene)
            println("UiShotTest: bar controls narrower than their label: " + squeezed)
            println("UiShotTest: bar geometry: " + barLayout(scene))
            val overflow = barOverflow(scene)
            if (overflow != null) {
                println("UiShotTest: FAIL the player bar overflows its own row: " + overflow)
                failures[0]++
            }
            if (squeezed.isEmpty()) {
                println("UiShotTest: OK   every control on the player bar fits its label")
            } else {
                println("UiShotTest: FAIL the player bar squeezes its own controls: " + squeezed)
                failures[0]++
            }
        }

        // ── the bars float over the picture ─────────────────────────────────
        // The point of the floating arrangement is that the video area is the
        // WHOLE window — the bars take no layout space at all — so this measures
        // the two floating bar windows and the video area against the player
        // layer. (FloatingBarsSelfTest goes further and checks the composed
        // pixels with a real mpv; this is the cheap half, runnable without a
        // video, and it is what catches a bar that silently stopped floating.)
        steps += 400L to {
            PlayerWindow.setFloatBars(true)
        }
        steps += 600L to {
            val floating = PlayerWindow.barsFloating()
            val bar = PlayerWindow.floatingBarHwnd()
            val strip = PlayerWindow.floatingStripHwnd()
            val area = PlayerWindow.videoAreaSize()
            val layer = PlayerWindow.layerSize()
            val barRect = bar?.takeIf { it != 0L }?.let { desktop.player.WinShell.windowRect(it) }
            val stripRect = strip?.takeIf { it != 0L }?.let { desktop.player.WinShell.windowRect(it) }
            val appHwnd = runCatching {
                val own = ProcessHandle.current().pid()
                desktop.player.WinShell.findWindowOf(own, stage.title)
                    ?: desktop.player.WinShell.findWindowOf(own)
            }.getOrNull()
            val appRect = appHwnd?.let { desktop.player.WinShell.windowRect(it) }
            println("UiShotTest: floating bars=$floating barHwnd=$bar stripHwnd=$strip")
            println("UiShotTest: video area=" + area?.joinToString(",") + " layer=" + layer?.joinToString(","))
            println("UiShotTest: bar rect=" + barRect?.joinToString(",") + " strip rect=" +
                stripRect?.joinToString(",") + " app rect=" + appRect?.joinToString(","))
            if (!desktop.player.WinShell.available) {
                println("UiShotTest: OK   floating bars not applicable (no Win32 on this runner)")
            } else if (!floating || bar == null || bar == 0L || strip == null || strip == 0L) {
                println("UiShotTest: FAIL the bars did not get their own windows")
                failures[0]++
            } else if (area == null || layer == null || area[0] < layer[0] - 1.0 || area[1] < layer[1] - 1.0) {
                println("UiShotTest: FAIL the video area does not fill the player layer")
                failures[0]++
            } else if (barRect != null && barRect[3] > 140) {
                // The bars float over a full-bleed video, so a bar that is a
                // PANEL (a stage that kept the window's height) covers the
                // picture with a translucent sheet: the scene checks above all
                // still pass, because the video area really is the whole window.
                // Measuring the bar's own window height is what catches it.
                println("UiShotTest: FAIL the floating control bar is " + barRect[3] +
                    "px tall — a bar, not a panel over the picture")
                failures[0]++
            } else if (barRect != null && appRect != null &&
                kotlin.math.abs((barRect[1] + barRect[3]) - (appRect[1] + appRect[3])) > 8
            ) {
                println("UiShotTest: FAIL the floating control bar is not at the bottom of the window")
                failures[0]++
            } else {
                println("UiShotTest: OK   the bars float and the video area is the whole window")
            }
        }
        steps += 200L to { PlayerWindow.setFloatBars(false) }

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
        // The picture "comes up", exactly as in the wide-window section above:
        // without this the shot below captures the loading state, where the
        // transport controls are not drawn at all — which says nothing about the
        // bar a user actually sees while a stream plays.
        steps += 1000L to { PlayerWindow.previewPlaying() }
        steps += 600L to {
            // On the narrow window the pickers drop their labels and, below
            // 560px, drop out of the row entirely — whichever of those happened,
            // what is still on the bar has to fit its label, and the row as a
            // whole has to fit the window.
            val squeezed = squeezedBarControls(scene)
            println("UiShotTest: narrow bar, controls narrower than their label: " + squeezed)
            println("UiShotTest: narrow bar geometry: " + barLayout(scene))
            val overflow = barOverflow(scene)
            if (overflow != null) {
                println("UiShotTest: FAIL the narrow player bar overflows its own row: " + overflow)
                failures[0]++
            }
            // ...and the bar itself must fit the WINDOW. A bar laid out for the
            // window's previous size is how the Source pill and, before it, the
            // fullscreen button ended up past the right edge — every control
            // perfect, and the right-hand ones off the screen.
            val bar = scene.lookup(".player-bar") as? javafx.scene.layout.HBox
            println(
                "UiShotTest: narrow bar width=" + (bar?.width ?: 0.0) +
                    " window width=" + scene.width,
            )
            if (bar != null && bar.width > scene.width + 4.0) {
                println(
                    "UiShotTest: FAIL the player bar is laid out wider than the window (" +
                        bar.width.toInt() + " > " + scene.width.toInt() + ")",
                )
                failures[0]++
            }
            if (squeezed.isEmpty() && overflow == null) {
                println("UiShotTest: OK   the narrow player bar fits everything it shows")
            } else {
                println("UiShotTest: FAIL the narrow player bar squeezes its own controls: " + squeezed)
                failures[0]++
            }
        }
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

        // ── a long season's episode pager ───────────────────────────────────
        // The state that matters is the one a 379-episode series opens on: page
        // one of the season, the range picker, and the hint that says where in
        // the season the user is looking. The season is handed in through the
        // screen's own seam so this drives the REAL renderEpisodes/
        // renderEpisodeGrid path (no provider, no network, no mock-up) — and the
        // numbers are checked here, not just looked at.
        val detailItem = MediaItem(
            providerId = "demo",
            id = "qi-refining",
            title = "One Hundred Thousand Years of Qi Refining",
            type = MediaType.SERIES,
            overview = "Wang Lin refuses the life he was handed, and pays for it.",
            genres = listOf("Anime", "Fantasy"),
            year = 2023,
        )
        steps += 300L to {
            PlayerWindow.closeAll()
            DetailScreenTestSeam.season = { media ->
                media to (1..379).map { n ->
                    Episode(
                        number = n,
                        id = "ep-" + n,
                        name = "One Hundred Thousand Years of Qi Refining Episode " + n + " English Subtitles",
                    )
                }
            }
            AppShell.openDetail(detailItem)
        }
        steps += 3200L to { }
        steps += 300L to {
            val nums = episodeNumbers(scene)
            val range = selectedRange(scene)
            println("UiShotTest: episode page 1: count=" + nums.size + " nums=" +
                nums.firstOrNull() + ".." + nums.lastOrNull() + " range=" + range)
            if (nums.size == 30 && nums.firstOrNull() == 1 && nums.lastOrNull() == 30 && range == "1 - 30") {
                println("UiShotTest: OK   the season opens on episodes 1..30 (range " + range + ")")
            } else {
                println("UiShotTest: FAIL the episode pager did not open on the first 30 of 379 episodes")
                failures[0]++
            }
        }
        shot("j-detail-episodes-page1", "hero + page one of a 379-episode season")

        // The range picker, driven the way the user drives it: pick "121 - 150".
        steps += 300L to {
            (scene.lookup(".ep-range") as? javafx.scene.control.ComboBox<*>)?.selectionModel?.select(4)
        }
        steps += 900L to { }
        steps += 300L to {
            val nums = episodeNumbers(scene)
            println("UiShotTest: range pick: count=" + nums.size + " nums=" +
                nums.firstOrNull() + ".." + nums.lastOrNull() + " range=" + selectedRange(scene))
            if (nums.size == 30 && nums.firstOrNull() == 121 && nums.lastOrNull() == 150) {
                println("UiShotTest: OK   picking a range loads exactly those episodes")
            } else {
                println("UiShotTest: FAIL picking the range did not load its episodes")
                failures[0]++
            }
        }
        shot("k-detail-episodes-range", "the range picker set to 121 - 150")

        // …and "Next 30", which is the same walk one page at a time.
        steps += 200L to {
            scene.root.lookupAll(".button").filterIsInstance<javafx.scene.control.Button>()
                .firstOrNull { it.text == "Next 30" }?.fire()
        }
        steps += 600L to { }
        steps += 300L to {
            val nums = episodeNumbers(scene)
            println("UiShotTest: next 30: count=" + nums.size + " nums=" +
                nums.firstOrNull() + ".." + nums.lastOrNull() + " range=" + selectedRange(scene))
            if (nums.firstOrNull() == 151 && nums.lastOrNull() == 180) {
                println("UiShotTest: OK   Next 30 walks to the following page")
            } else {
                println("UiShotTest: FAIL Next 30 did not advance the page")
                failures[0]++
            }
        }

        // …and searching, which must reach an episode on any page.
        steps += 200L to {
            (scene.root.lookupAll(".ep-filter").firstOrNull() as? javafx.scene.control.TextField)?.text = "187"
        }
        steps += 900L to { }
        steps += 300L to {
            val nums = episodeNumbers(scene)
            println("UiShotTest: search 187: count=" + nums.size + " nums=" + nums)
            if (nums.size == 1 && nums.firstOrNull() == 187) {
                println("UiShotTest: OK   searching an episode number jumps straight to it")
            } else {
                println("UiShotTest: FAIL searching an episode number did not find it")
                failures[0]++
            }
        }
        shot("l-detail-episodes-search", "searching episode 187 of 379")

        // ── the detail banner's back arrow, clicked with a REAL mouse ───────
        // The arrow in the banner was reported dead. Two things kill it silently:
        // something painted over it taking the click, and the tooltip popup a
        // hover puts on top of it (a JavaFX tooltip is a window of its own and a
        // click while it is up is spent dismissing it). So this does exactly what
        // the user does — point at the arrow, wait longer than the tooltip delay,
        // press, release — through the OS, and it reports what is painted ON TOP of
        // the arrow's own centre either way, so a change in paint order is caught
        // here rather than in the next bug report.
        steps += 300L to {
            PlayerWindow.closeAll()
            AppShell.show(Screen.Home)
            AppShell.openDetail(detailItem)
        }
        steps += 1600L to { }
        steps += 200L to {
            // A window off the display (or not focused) cannot be clicked through
            // the OS, so it is pinned to the top-left corner first.
            stage.x = 0.0
            stage.y = 0.0
            val btn = detailBackButton(scene)
            if (btn == null) {
                println("UiShotTest: FAIL the detail banner has no back arrow")
                failures[0]++
            } else {
                val centre = btn.localToScreen(btn.boundsInLocal.centerX, btn.boundsInLocal.centerY)
                println(
                    "UiShotTest: back arrow: visible=" + btn.isVisible + " disabled=" + btn.isDisabled +
                        " centre on screen=" + centre,
                )
                val top = centre?.let { topmostAt(scene.root, it.x, it.y) }
                println("UiShotTest: the node on top of that point is " + top)
                if (top == null || !isInside(top, btn)) {
                    println("UiShotTest: FAIL something is painted over the back arrow (topmost=" + top + ")")
                    failures[0]++
                }
                if (centre == null || !onScreen(centre)) {
                    println("UiShotTest: WARN the back arrow is off this display — no real click is possible here")
                } else {
                    val robot = runCatching { java.awt.Robot() }.getOrNull()
                    if (robot == null) {
                        println("UiShotTest: WARN java.awt.Robot is unavailable — no real click is possible here")
                    } else {
                        robot.mouseMove(centre.x.toInt(), centre.y.toInt())
                        // Longer than Ui.tooltip's 900ms delay: if a tooltip is
                        // what eats the click, it is up by now.
                        Thread.sleep(1400L)
                        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(80L)
                        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(900L)
                        println("UiShotTest: clicked the banner's back arrow with the real mouse")
                    }
                }
            }
        }
        steps += 200L to {
            val now = AppShell.current
            if (now is Screen.Detail) {
                println("UiShotTest: FAIL the detail screen's back arrow did nothing (still " + now + ")")
                failures[0]++
            } else {
                println("UiShotTest: OK   the banner's back arrow navigates back (now " + now + ")")
            }
        }

        // ── the banner's Play button: on top, and it really is pressed ───────
        // The same test as the back arrow, for the same reason: this control has
        // already been reported dead once (a full-size, backgroundless VBox was
        // stacked over the banner and took the click), and "the button is there
        // but nothing happens" is invisible to a screenshot.
        steps += 300L to {
            AppShell.show(Screen.Home)
            AppShell.openDetail(detailItem)
            DetailScreenTestSeam.playPresses.set(0)
        }
        steps += 1800L to { }
        steps += 200L to {
            stage.x = 0.0
            stage.y = 0.0
            val btn = scene.root.lookup("#heroPlayBtn") as? javafx.scene.control.Button
            if (btn == null) {
                println("UiShotTest: FAIL the detail banner has no Play button")
                failures[0]++
            } else {
                val centre = btn.localToScreen(btn.boundsInLocal.centerX, btn.boundsInLocal.centerY)
                val at = btn.localToScene(btn.boundsInLocal.centerX, btn.boundsInLocal.centerY)
                // Everything lying over the button, topmost first: when a click
                // does nothing, this names the node that ate it.
                println("UiShotTest: over the Play button, topmost first:")
                occludersAt(scene.root, at.x, at.y).take(8).forEach {
                    println("UiShotTest:     " + it)
                }
                val top = topmostAt(scene.root, at.x, at.y)
                println(
                    "UiShotTest: Play button: visible=" + btn.isVisible + " disabled=" + btn.isDisabled +
                        " centre=" + centre + " topmost=" + top,
                )
                if (top == null || !isInside(top, btn)) {
                    println("UiShotTest: FAIL something is painted over the banner's Play button (topmost=" + top + ")")
                    failures[0]++
                }
                if (centre == null || !onScreen(centre)) {
                    println("UiShotTest: WARN the Play button is off this display — no real click is possible here")
                } else {
                    val robot = runCatching { java.awt.Robot() }.getOrNull()
                    if (robot == null) {
                        println("UiShotTest: WARN java.awt.Robot is unavailable — no real click is possible here")
                    } else {
                        robot.mouseMove(centre.x.toInt(), centre.y.toInt())
                        Thread.sleep(300L)
                        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(80L)
                        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(600L)
                        println("UiShotTest: clicked the banner's Play button with the real mouse")
                    }
                }
            }
        }
        steps += 400L to {
            val presses = DetailScreenTestSeam.playPresses.get()
            if (presses >= 1) {
                println("UiShotTest: OK   the banner's Play button runs the play path (" + presses + " press(es))")
            } else {
                println("UiShotTest: FAIL the banner's Play button never ran (presses=" + presses + ")")
                failures[0]++
            }
        }

        // ── the Installed list's engine filter: All | CloudStream | Hikari | … ─
        // The desktop twin of the Android picker's chips. With a hundred providers
        // from several engines installed, "show me only the Hikari ones" has to be
        // one click. Providers of three different engines are registered here (and
        // removed again at the end) so the chips have something real to filter.
        val chipProviders = listOf(
            com.hikari.app.data.ProviderConfig(
                id = "zztest|hiki1", name = "ZZ Test Hikari One",
                type = com.hikari.app.data.ProviderType.HIKARI, url = "",
            ),
            com.hikari.app.data.ProviderConfig(
                id = "zztest|hiki2", name = "ZZ Test Hikari Two",
                type = com.hikari.app.data.ProviderType.HIKARI, url = "",
            ),
            com.hikari.app.data.ProviderConfig(
                id = "zztest|cs3", name = "ZZ Test CloudStream",
                type = com.hikari.app.data.ProviderType.CS3, url = "",
            ),
            com.hikari.app.data.ProviderConfig(
                id = "zztest|nuvio", name = "ZZ Test Nuvio",
                type = com.hikari.app.data.ProviderType.NUVIO, url = "",
            ),
        )
        steps += 300L to {
            chipProviders.forEach { AppShell.app.store.addProvider(it) }
            AppShell.show(Screen.Home)
            AppShell.show(Screen.Extensions)
        }
        steps += 1400L to {
            val chips = engineChips(scene)
            println("UiShotTest: engine chips = " + chips.joinToString(" | "))
            if (chips.none { it == "All" } || chips.none { it == "Hikari" } || chips.none { it == "Nuvio" }) {
                println("UiShotTest: FAIL the Installed list has no engine chips")
                failures[0]++
            } else {
                println("UiShotTest: OK   the Installed list offers one chip per engine")
            }
            // ...and the chips have to be REACHABLE, not merely present. The
            // Installed section sits BELOW the repo box — that is the order the
            // page is meant to have (add a repo, then install from it, then see
            // what is installed) — so the check scrolls the page down to the
            // Installed list and then asks whether the row is really inside the
            // viewport. A filter that exists but can never be brought on screen
            // is not a filter.
            val row = scene.root.lookup(".kind-chips")
            if (row == null) {
                println("UiShotTest: FAIL the Installed list has no engine chip row")
                failures[0]++
            } else {
                scrollIntoViewport(row)
            }
        }
        steps += 400L to {
            val row = scene.root.lookup(".kind-chips")
            if (row != null && inScrollViewport(row)) {
                println("UiShotTest: OK   the engine chips can be scrolled into view")
            } else {
                println("UiShotTest: FAIL the engine chips are not in the visible page area (node=" + row + ")")
                failures[0]++
            }
            // Every chip has to show its WHOLE label: a chip laid out narrower
            // than its own text is drawn as "…", and an unreadable filter is
            // worse than a filter you have to scroll to.
            val kind = scene.root.lookupAll(".kind-chips .seg").filterIsInstance<javafx.scene.control.Button>()
            val squeezed = kind.filter { it.width + 0.5 < it.prefWidth(-1.0) }
            println(
                "UiShotTest: engine chip widths = " +
                    kind.joinToString(" | ") { it.text + "=" + it.width.toInt() + "/" + it.prefWidth(-1.0).toInt() },
            )
            if (squeezed.isNotEmpty()) {
                println("UiShotTest: FAIL these engine chips are squeezed: " + squeezed.map { it.text })
                failures[0]++
            } else {
                println("UiShotTest: OK   every engine chip is wide enough for its label")
            }
            // The composer's own chip row ("Hikari repo | CloudStream repo | …")
            // follows the same rule, and broke it the same way: nine modes never
            // fit one panel width, so the labels came out as "CloudStream re…".
            val modeChips = scene.root.lookupAll(".composer-chips .seg").filterIsInstance<javafx.scene.control.Button>()
            val squeezedModes = modeChips.filter { it.width + 0.5 < it.prefWidth(-1.0) }
            println("UiShotTest: composer chip widths = " + modeChips.joinToString(" | ") { it.text })
            if (modeChips.isEmpty()) {
                println("UiShotTest: FAIL the composer has no mode chips to measure")
                failures[0]++
            } else if (squeezedModes.isNotEmpty()) {
                println("UiShotTest: FAIL these composer chips are squeezed: " + squeezedModes.map { it.text })
                failures[0]++
            } else {
                println("UiShotTest: OK   every composer chip is wide enough for its label")
            }
            // The shot that follows is of the FILTERED Installed list, so the
            // page is left scrolled to it — that is the state a user looking for
            // "show me only the Nuvio ones" is actually in.
        }
        steps += 300L to {
            scene.root.lookupAll(".kind-chips .seg").filterIsInstance<javafx.scene.control.Button>()
                .firstOrNull { it.text == "Hikari" }?.fire()
        }
        steps += 700L to {
            val mine = installedNames(scene).filter { it.startsWith("ZZ Test") }
            println("UiShotTest: with the Hikari chip on, test rows = " + mine)
            if (mine.size == 2 && mine.all { it.contains("Hikari") }) {
                println("UiShotTest: OK   picking Hikari leaves only the Hikari extensions")
            } else {
                println("UiShotTest: FAIL the Hikari chip did not narrow the Installed list")
                failures[0]++
            }
            save(
                scene.snapshot(WritableImage(scene.width.toInt(), scene.height.toInt())),
                File(out, "n-extensions-engine-filter.png"),
            )
        }
        // ── the home catalog's provider picker (the Android provider sheet) ──
        // The control that decides which provider the whole home page is built
        // from. It is a Popup (a window with a scene of its own), so it is driven
        // through the picker's own hooks — the same paths its rows and chips use.
        steps += 300L to {
            AppShell.show(Screen.Home)
            AppShell.homeView.load(force = true)
        }
        steps += 1400L to {
            val picker = AppShell.homeView.pickerForTest
            (scene.root.lookup(".provider-picker") as? javafx.scene.control.Button)?.fire()
            val open = picker.isOpenForTest()
            val chips = picker.chipLabelsForTest()
            val rows = picker.rowNamesForTest()
            println("UiShotTest: provider picker open=" + open + " chips=" + chips)
            println("UiShotTest: provider picker rows (first 4) = " + rows.take(4))
            if (!open) {
                println("UiShotTest: FAIL the home provider picker did not open")
                failures[0]++
            } else if (chips.firstOrNull() != "All" || !chips.contains("Hikari") || !chips.contains("Nuvio")) {
                println("UiShotTest: FAIL the provider picker has no All+engine chips (" + chips + ")")
                failures[0]++
            } else if (rows.firstOrNull() != "All providers") {
                println("UiShotTest: FAIL the provider list does not start with \"All providers\" (" + rows.take(2) + ")")
                failures[0]++
            } else {
                println("UiShotTest: OK   the provider picker opens with All + one chip per engine")
            }
        }
        steps += 400L to {
            // The chips have to SHOW their engine names. A chip that cannot fit
            // its label is drawn as "…" — which is exactly what the picker did
            // with six engines installed — and the chip's own `text` still reads
            // "CloudStream", so only a width comparison catches it.
            val picker = AppShell.homeView.pickerForTest
            val widths = picker.chipWidthsForTest()
            println(
                "UiShotTest: picker chip widths = " +
                    widths.joinToString(" | ") { it.first + "=" + it.second.toInt() + "/" + it.third.toInt() },
            )
            val squeezed = widths.filter { it.second + 0.5 < it.third }
            if (widths.isEmpty()) {
                println("UiShotTest: FAIL the provider picker has no chips to measure")
                failures[0]++
            } else if (squeezed.isNotEmpty()) {
                println("UiShotTest: FAIL these provider chips are squeezed: " + squeezed.map { it.first })
                failures[0]++
            } else {
                println("UiShotTest: OK   every provider chip is wide enough for its name")
            }
            // ...and the row is a scroller, so the chips that do not fit the
            // popup are reachable instead of clipped.
            val scroller = picker.chipRowForTest()
            val content = scroller.content
            val contentW = (content as? javafx.scene.layout.Region)?.width ?: 0.0
            println(
                "UiShotTest: picker chip row content=" + contentW.toInt() +
                    " viewport=" + scroller.viewportBounds.width.toInt(),
            )
            if (contentW > scroller.viewportBounds.width + 1.0) {
                val before = scroller.hvalue
                scroller.hvalue = 1.0
                val moved = scroller.hvalue > before
                scroller.hvalue = before
                if (moved) {
                    println("UiShotTest: OK   the provider chip row scrolls horizontally")
                } else {
                    println("UiShotTest: FAIL the provider chip row cannot scroll")
                    failures[0]++
                }
            } else {
                println("UiShotTest: OK   the provider chip row fits the popup")
            }
        }
        steps += 300L to {
            val picker = AppShell.homeView.pickerForTest
            picker.pressChipForTest("Hikari")
            val rows = picker.rowNamesForTest()
            println("UiShotTest: provider picker, Hikari chip on: " + rows)
            if (rows.any { it.startsWith("ZZ Test Hikari") } &&
                rows.none { it.contains("Nuvio") || it.contains("CloudStream") }
            ) {
                println("UiShotTest: OK   picking an engine chip leaves only that engine's providers")
            } else {
                println("UiShotTest: FAIL the engine chip did not narrow the provider list")
                failures[0]++
            }
        }
        steps += 300L to {
            val picker = AppShell.homeView.pickerForTest
            val picked = picker.selectForTest("ZZ Test Hikari One")
            val label = (scene.root.lookup(".provider-picker") as? javafx.scene.control.Button)?.text
            println("UiShotTest: picked a provider=" + picked + " button now says \"" + label + "\"")
            if (picked && label == "ZZ Test Hikari One") {
                println("UiShotTest: OK   picking a provider sets the catalog source")
            } else {
                println("UiShotTest: FAIL picking a provider did not take")
                failures[0]++
            }
        }
        steps += 900L to {
            // ...and the selection SURVIVES the reload the pick triggers.
            val picker = AppShell.homeView.pickerForTest
            println("UiShotTest: after the reload the picker still says \"" + picker.selectedName() + "\"")
            if (picker.selectedName() == "ZZ Test Hikari One") {
                println("UiShotTest: OK   the picked provider survives its own reload")
            } else {
                println("UiShotTest: FAIL the picked provider was reset by the reload")
                failures[0]++
            }
            picker.selectForTest("All providers")
        }

        // ── the page keeps its place while it repaints ───────────────────────
        // A finished install ends with a repaint of the Installed list and the
        // header, and the page used to jump back to the top for it: the children
        // were cleared, the page was momentarily empty, and a ScrollPane clamps an
        // empty content back to the top (the wheel then had nothing to scroll
        // until the new rows had been laid out).
        steps += 400L to {
            val page = AppShell.extensionsView.pageForTest()
            page.vvalue = 0.65
            println("UiShotTest: extensions page scrolled to " + page.vvalue)
        }
        steps += 300L to { AppShell.extensionsView.repaintAfterInstallForTest() }
        steps += 700L to {
            val page = AppShell.extensionsView.pageForTest()
            val v = page.vvalue
            println("UiShotTest: extensions page vvalue after the repaint = " + v)
            if (kotlin.math.abs(v - 0.65) < 0.05) {
                println("UiShotTest: OK   the page stays where the user left it while it repaints")
            } else {
                println("UiShotTest: FAIL the repaint moved the page (0.65 -> " + v + ")")
                failures[0]++
            }
            // ...and the repos are above the installed list, in that order.
            val order = AppShell.extensionsView.sectionOrderForTest()
            println("UiShotTest: extensions sections top to bottom = " + order)
            val repos = order.indexOf("Repos")
            val installed = order.indexOf("Installed")
            if (repos >= 0 && installed > repos) {
                println("UiShotTest: OK   the repo box is above the installed list")
            } else {
                println("UiShotTest: FAIL the section order is wrong (" + order + ")")
                failures[0]++
            }
            page.vvalue = 0.0
        }
        steps += 300L to {
            scene.root.lookupAll(".kind-chips .seg").filterIsInstance<javafx.scene.control.Button>()
                .firstOrNull { it.text == "All" }?.fire()
            chipProviders.forEach { runCatching { AppShell.app.store.removeProvider(it.id) } }
        }

        // ── a repo that will not load: the card the user acts on ─────────────
        // This is the screen the bug reports are about, so it is worth looking
        // at: the failure message, and the action row under it ("Try again",
        // "Diagnose network", and "Trust this network's certificate…" when the
        // message is a certificate one).
        //
        // Reaching it deterministically: the repo list is emptied and ONE
        // unloadable repo is added, so there is exactly one card and "Open" is
        // unambiguous; then that card is opened and the screen is polled for the
        // action row. A repo that cannot load is the state being fixed here.
        val badRepoUrl = "https://raw.githubusercontent.com/codegeasse1/hikari-desktop/main/no-such-repo.json"
        var errorCardShot = false
        steps += 300L to { AppShell.show(Screen.Extensions) }
        steps += 1200L to {
            val store = AppShell.app.store
            store.repos().forEach { r -> runCatching { store.removeCs3Repo(r.url) } }
            store.addCs3Repo(Cs3Repo(url = badRepoUrl, name = "Broken repo (test)", kind = RepoKind.HIKARI))
            AppShell.show(Screen.Home)
            AppShell.show(Screen.Extensions)
        }
        steps += 1500L to {
            scene.root.lookupAll(".button")
                .filterIsInstance<javafx.scene.control.Button>()
                .firstOrNull { it.text == "Open" }
                ?.fire()
        }
        repeat(16) {
            steps += 6000L to poll@{
                if (errorCardShot) return@poll
                val labels = scene.root.lookupAll(".button")
                    .filterIsInstance<javafx.scene.control.Button>()
                    .mapNotNull { it.text }
                    .toList()
                if (labels.none { it.contains("Diagnose network") }) return@poll
                errorCardShot = true
                println("UiShotTest: repo-error card buttons = " + labels.joinToString(" | "))
                println(
                    "UiShotTest: OK   the repo error card offers Diagnose network (" +
                        labels.count { it.contains("certificate") } + " certificate trust button(s))",
                )
                save(
                    scene.snapshot(WritableImage(scene.width.toInt(), scene.height.toInt())),
                    File(out, "m-extensions-repo-error.png"),
                )
            }
        }
        steps += 300L to {
            if (!errorCardShot) {
                println(
                    "UiShotTest: WARN the repo error card had not appeared after 96s; buttons = " +
                        scene.root.lookupAll(".button")
                            .filterIsInstance<javafx.scene.control.Button>()
                            .mapNotNull { it.text }
                            .joinToString(" | "),
                )
                save(
                    scene.snapshot(WritableImage(scene.width.toInt(), scene.height.toInt())),
                    File(out, "m-extensions-repo-error.png"),
                )
            }
            runCatching { AppShell.app.store.removeCs3Repo(badRepoUrl) }
            AppShell.show(Screen.Home)
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

    /**
     * Every node whose laid-out bounds contain a scene point, topmost first.
     *
     * Deliberately *geometric*: it ignores the background/bounds picking rules
     * the app relies on, so when a click on a button does nothing it names the
     * exact node lying over it (an invisible, stretched container included)
     * instead of leaving the cause to a guess.
     */
    private fun occludersAt(root: javafx.scene.Parent, sceneX: Double, sceneY: Double): List<String> {
        val out = ArrayList<String>()
        fun walk(parent: javafx.scene.Parent) {
            for (child in parent.childrenUnmodifiable.reversed()) {
                if (!child.isVisible) continue
                val local = child.sceneToLocal(sceneX, sceneY)
                if (!runCatching { child.contains(local.x, local.y) }.getOrDefault(false)) continue
                if (child is javafx.scene.Parent) walk(child)
                out.add(describeNode(child))
            }
        }
        walk(root)
        return out
    }

    /** One node in the form a bug report can name: class, id, style classes,
     *  scene bounds, and the flags that decide whether JavaFX picks it. */
    private fun describeNode(n: javafx.scene.Node): String {
        val b = runCatching { n.localToScene(n.boundsInLocal) }.getOrNull()
        val region = n as? javafx.scene.layout.Region
        return n.javaClass.simpleName +
            "[id=" + n.id + " cls=" + n.styleClass.joinToString(".") + "]" +
            " scene=" + (b?.let {
                "(" + it.minX.toInt() + "," + it.minY.toInt() + " " + it.width.toInt() + "x" + it.height.toInt() + ")"
            } ?: "?") +
            " pickOnBounds=" + n.isPickOnBounds + " bg=" + (region?.background != null) +
            " border=" + (region?.border != null) + " mouseTransparent=" + n.isMouseTransparent +
            " opacity=" + n.opacity
    }

    /**
     * The bar's row pushed past the edge of its own container. An HBox whose
     * children cannot shrink below their minimum does not squeeze them — it
     * OVERFLOWS, so the rightmost control is clipped by the window edge. A
     * per-control width check cannot see that (every control is exactly as wide
     * as it asked for, while the last one is half off the screen), which is how
     * the Source pill ended up reading "Vi" on a 460px window.
     *
     * Returns null when the row fits.
     */
    private fun barOverflow(scene: Scene): String? {
        val bar = scene.lookup(".player-bar") as? javafx.scene.layout.HBox ?: return null
        var total = 0.0
        var shown = 0
        for (child in bar.children) {
            if (!child.isVisible || !child.isManaged) continue
            total += child.layoutBounds.width
            shown++
        }
        if (shown > 1) total += bar.spacing * (shown - 1)
        return if (total > bar.width + 1.0) "row wants " + total.toInt() + "px in " + bar.width.toInt() + "px" else null
    }

    /** "text(laidOut/pref)" for every control on the bar, so a layout change is
     *  visible in the log and not only in a screenshot. */
    private fun barLayout(scene: Scene): String {
        val bar = scene.lookup(".player-bar") as? javafx.scene.layout.HBox ?: return "(no bar)"
        return bar.children.filter { it.isVisible && it.isManaged }.joinToString(" | ") { c ->
            val label = (c as? javafx.scene.control.Labeled)?.text?.take(14) ?: c.javaClass.simpleName
            val r = c as? javafx.scene.layout.Region
            label + "(" + c.layoutBounds.width.toInt() + "/" + ((r?.prefWidth(-1.0) ?: 0.0).toInt()) + ")"
        }
    }

    /**
     * The controls on the player's bar that have been squeezed narrower than the
     * width they asked for — i.e. the ones JavaFX will have drawn with an
     * ellipsis ("Qua…", "0…"). A control's `text` is unchanged by truncation, so
     * the only way to see this from a test is to compare the laid-out width with
     * the computed preferred width.
     */
    private fun squeezedBarControls(scene: Scene): List<String> {
        val bar = scene.lookup(".player-bar") as? javafx.scene.layout.HBox ?: return emptyList()
        // The seek bar is the ONE control that is meant to give way: it is what
        // absorbs the shortfall when the row is tight, and it has a floor of its
        // own (see PlayerWindow.applyResponsive). Every other control on the bar
        // has to fit its own label.
        val seek = scene.lookup(".player-seek")
        val out = ArrayList<String>()
        for (child in bar.children) {
            if (child === seek) continue
            if (!child.isVisible || !child.isManaged) continue
            val r = child as? javafx.scene.layout.Region ?: continue
            val want = r.prefWidth(-1.0)
            if (want <= 0.0) continue
            val got = child.layoutBounds.width
            if (got + 1.0 < want) out.add((child as? javafx.scene.control.Labeled)?.text + " (" + got + "<" + want + ")")
        }
        return out
    }

    /** The detail banner's own back arrow (see DetailScreen.buildHero). */
    private fun detailBackButton(scene: Scene): javafx.scene.control.Button? =
        (scene.root.lookup("#heroBackBtn") as? javafx.scene.control.Button)
            ?: scene.root.lookupAll(".hero-back")
                .filterIsInstance<javafx.scene.control.Button>().firstOrNull()

    /** The engine chips of the Installed list ("All | CloudStream | Hikari | …"). */
    private fun engineChips(scene: Scene): List<String> =
        scene.root.lookupAll(".kind-chips .seg").filterIsInstance<javafx.scene.control.Button>()
            .mapNotNull { it.text }.toList()

    /** The names of every rendered installed/repo row. */
    private fun installedNames(scene: Scene): List<String> =
        scene.root.lookupAll(".src-name")
            .filterIsInstance<javafx.scene.control.Label>().mapNotNull { it.text }.toList()

    /**
     * The node that would take a click at a scene point — the same question
     * JavaFX's own picking answers, walked by hand so the test can ask it.
     *
     * A Region with no background and no border is not pickable (JavaFX skips
     * it), and nor is a mouse-transparent node, so both are skipped here too —
     * otherwise every transparent wrapper would look like an obstruction.
     */
    private fun topmostAt(root: javafx.scene.Parent, sceneX: Double, sceneY: Double): javafx.scene.Node? {
        for (child in root.childrenUnmodifiable.reversed()) {
            if (!pickable(child)) continue
            val local = child.sceneToLocal(sceneX, sceneY)
            if (!runCatching { child.contains(local.x, local.y) }.getOrDefault(false)) continue
            val deeper = if (child is javafx.scene.Parent) topmostAt(child, sceneX, sceneY) else null
            return deeper ?: child
        }
        return null
    }

    private fun pickable(node: javafx.scene.Node): Boolean {
        if (!node.isVisible || node.isMouseTransparent || node.opacity <= 0.01) return false
        return when (node) {
            is javafx.scene.layout.Region ->
                node.isPickOnBounds || node.background != null || node.border != null
            else -> true
        }
    }

    /** True when [node] (or a child of it) is [ancestor] itself. */
    private fun isInside(node: javafx.scene.Node, ancestor: javafx.scene.Node): Boolean {
        var n: javafx.scene.Node? = node
        while (n != null) {
            if (n === ancestor) return true
            n = n.parent
        }
        return false
    }

    /**
     * True when [node] is inside the viewport of the [ScrollPane] it lives in —
     * i.e. the user can SEE it without scrolling.
     *
     * This is not pedantry: the Installed list (and its engine chips) sat at the
     * bottom of the Extensions page, below the composer and every repo card, so
     * the control existed, worked when fired programmatically, and could not be
     * found by anyone looking at the screen. A test that only asks "is it in the
     * scene graph?" cannot tell those two apart.
     */
    private fun inScrollViewport(node: javafx.scene.Node): Boolean {
        val pane = outerScrollPane(node) ?: return true
        val vp = pane.viewportBounds
        if (vp.height <= 0.0) return false
        val inPane = pane.sceneToLocal(node.localToScene(node.boundsInLocal))
        return inPane.minY >= -0.5 && inPane.maxY <= vp.height + 0.5
    }

    /**
     * The OUTERMOST [ScrollPane] that holds [node].
     *
     * Outermost, not nearest: a control can sit inside a horizontal chip rail
     * which itself sits inside the page's vertical scroller, and "can the user
     * see it" is a question about the PAGE. The nearest scroller (the rail) is
     * fit-to-height, so it answers "yes" about a row that is two screens down.
     */
    private fun outerScrollPane(node: javafx.scene.Node): javafx.scene.control.ScrollPane? {
        var p: javafx.scene.Node? = node.parent
        var found: javafx.scene.control.ScrollPane? = null
        while (p != null) {
            if (p is javafx.scene.control.ScrollPane) found = p
            p = p.parent
        }
        return found
    }

    /**
     * Scrolls the [ScrollPane] that holds [node] until the node is inside its
     * viewport (its top aligned just below the viewport's top edge).
     *
     * Used by the layout checks: the Installed list sits below the repo box, so
     * a test that wants to look at the Installed section has to scroll the page
     * to it first — exactly as a user does. Nothing here is automatic: the page
     * is a plain ScrollPane, and a control that cannot be scrolled to is a
     * control nobody can use.
     */
    private fun scrollIntoViewport(node: javafx.scene.Node) {
        val pane = outerScrollPane(node) ?: return
        val vp = pane.viewportBounds
        if (vp.height <= 0.0) return
        val contentH = (pane.content as? javafx.scene.layout.Region)?.height ?: 0.0
        val max = (contentH - vp.height).coerceAtLeast(0.0)
        if (max <= 0.0) return
        val inPane = pane.sceneToLocal(node.localToScene(node.boundsInLocal))
        val now = pane.vvalue * max
        pane.vvalue = ((now + inPane.minY - 8.0) / max).coerceIn(0.0, 1.0)
    }

    /** True when a screen point is on this display (a click there can land). */
    private fun onScreen(p: javafx.geometry.Point2D): Boolean {
        val b = javafx.stage.Screen.getPrimary().visualBounds
        return p.x > b.minX + 2 && p.y > b.minY + 2 && p.x < b.maxX - 2 && p.y < b.maxY - 2
    }

    /** The episode numbers currently on screen — read off the real tiles. */
    private fun episodeNumbers(scene: Scene): List<Int> =
        scene.root.lookupAll(".ep-num").toList()
            .filterIsInstance<javafx.scene.control.Button>()
            .mapNotNull { it.text?.trim()?.toIntOrNull() }
            .sorted()

    /** What the episode range picker currently says, e.g. "121 - 150". */
    private fun selectedRange(scene: Scene): String? =
        (scene.lookup(".ep-range") as? javafx.scene.control.ComboBox<*>)?.selectionModel?.selectedItem?.toString()

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
