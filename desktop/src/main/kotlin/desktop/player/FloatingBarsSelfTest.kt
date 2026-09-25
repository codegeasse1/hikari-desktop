package desktop.player

import com.hikari.app.HikariApp
import com.hikari.app.data.StreamSource
import desktop.fx.Fx
import desktop.ui.AppShell
import desktop.ui.Theme
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.StageStyle
import org.json.JSONObject
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * CI self-test for the player's FLOATING bars and for the two player controls
 * that were reported broken — the reported "when the buttons are showing the
 * video is not full screen / it is cropped, and when they hide it becomes full
 * screen", plus "pause works, but pressing it again does not resume" and
 * "the player's X closes the whole app".
 *
 * The player's video is mpv's OWN window, glued over the app window, and Windows
 * draws an owned window above everything its owner paints. So the bars can never
 * be drawn over the picture by the app's own JavaFX scene: they have to be
 * windows of their own, placed above the video window. This test proves, with a
 * real mpv, a real player layer and real screen pixels, that:
 *
 *  1. the video area is the WHOLE window — the bars take no layout space, so the
 *     picture is never squeezed by them;
 *  2. each bar is a BAR: a short window at an edge of the window, not a
 *     translucent panel covering the picture (measuring the bar's own window is
 *     the only thing that catches that — the scene-level numbers all look
 *     right when the bar is the height of the whole window);
 *  3. the bars really are on top of the picture (their screen rectangles are
 *     darker than the video behind them, which is a checkerboard of known
 *     brightness — a bar that was behind the video would sample as pure video);
 *  4. hiding the bars uncovers the video WITHOUT changing its size — the video
 *     window's rectangle is identical with the bars up and with them away;
 *  5. the control bar's play/pause button really reaches the picture's control
 *     channel, in BOTH directions — the channel reports "playing", the button is
 *     pressed, it reports "paused", the button is pressed again, it reports
 *     "playing" — and a toggle command that is DROPPED (answered, but with no
 *     effect, which is what a lost command looks like from the player's side) is
 *     noticed and recovered from, because that is the reported "pause works and
 *     then pressing it again does nothing". The channel used here is a stand-in
 *     (see [FakeMpvChannel]) — mpv's own pipe is not usable on this runner, and
 *     [probePipe] prints the measurement that says why;
 *  6. the player's own close button closes the PLAYER — the app window is still
 *     there afterwards, the video is not glued over it any more, and the bars
 *     are gone.
 *
 * It exits non-zero when any of those is false, so a build that breaks them
 * fails instead of shipping. Nothing here waits longer than its budget: the
 * JavaFX toolkit keeps a non-daemon thread alive, so a hang would eat the whole
 * step timeout and leave the log ending on the hang (which is exactly what the
 * first version of this test did).
 */
fun main() {
    println("FloatingBarsSelfTest: start")
    if (!WinShell.available) {
        println("FloatingBarsSelfTest: SKIP (not Windows / JNA unavailable)")
        return
    }
    val mpv = findMpvForBars()
    println("  mpv=" + (mpv?.absolutePath ?: "NOT FOUND"))
    if (mpv == null) {
        println("FloatingBarsSelfTest: SKIP (bundled mpv not found on this runner)")
        return
    }
    System.setProperty("java.awt.headless", "false")
    // A hard stop, so a wedged pipe or a window that never answers cannot keep
    // this process (and the CI step) alive.
    Thread(
        {
            runCatching { Thread.sleep(WATCHDOG_MS) }
            println("FloatingBarsSelfTest: FAIL the test did not finish within " + (WATCHDOG_MS / 1000) + "s")
            runCatching { println(PlayerWindow.windowReport()) }
            halt(1)
        },
        "bars-test-watchdog",
    ).apply { isDaemon = true; start() }
    HikariApp().init()
    Application.launch(BarsTestApp::class.java, mpv.absolutePath)
}

/** Exits immediately: `Platform.exit()` still has the toolkit's threads to join,
 *  and a test process that lingers is a CI step that times out. */
private fun halt(code: Int) {
    runCatching { System.out.flush() }
    runCatching { System.err.flush() }
    Runtime.getRuntime().halt(code)
}

class BarsTestApp : Application() {

    override fun start(stage: Stage) {
        Fx.onStart()
        stage.title = HOST_TITLE
        stage.initStyle(StageStyle.UNDECORATED)
        val root = AppShell.create(stage)
        val scene = Theme.style(Scene(root, WIDTH, HEIGHT))
        stage.scene = scene
        stage.minWidth = 320.0
        stage.minHeight = 260.0
        stage.show()
        val mpvPath = parameters.raw.firstOrNull()
        if (mpvPath == null) {
            println("FloatingBarsSelfTest: SKIP (no mpv path was passed)")
            halt(0)
        }
        // The checks block on mpv, on screen pixels and on IPC replies, so they
        // cannot run ON the FX thread (every hop back to it would deadlock).
        // start() returns, and the body runs on its own thread.
        Thread(
            {
                val code = runCatching { test(stage, mpvPath!!) }.getOrElse { t ->
                    println("FloatingBarsSelfTest: FAIL the test threw " + t)
                    t.printStackTrace(System.out)
                    1
                }
                halt(code)
            },
            "bars-test-body",
        ).apply { isDaemon = true; start() }
    }
}

private fun test(host: Stage, mpvPath: String): Int {
    val failures = mutableListOf<String>()
    fun check(name: String, condition: Boolean, detail: String = "") {
        println(if (condition) "  OK   $name" else "  FAIL $name  $detail")
        if (!condition) failures += name
    }
    fun report(): Int {
        println(PlayerWindow.windowReport())
        if (failures.isEmpty()) {
            println("FloatingBarsSelfTest: OK")
            return 0
        }
        println("FloatingBarsSelfTest: " + failures.size + " FAILED — " + failures.joinToString("; "))
        return 1
    }

    val pid = ProcessHandle.current().pid()
    var hostHwnd: Long? = null
    val hostBy = System.currentTimeMillis() + 10_000
    while (hostHwnd == null && System.currentTimeMillis() < hostBy) {
        hostHwnd = WinShell.findWindowOf(pid, HOST_TITLE)
        if (hostHwnd == null) Thread.sleep(150)
    }
    check("the app window is found by title", hostHwnd != null)
    val appHwnd = hostHwnd ?: return report()
    println("  app hwnd=" + appHwnd + " rect=" + WinShell.windowRect(appHwnd)?.joinToString(","))

    // ── a real video, playing into a window the player adopts ───────────────
    val picture = File.createTempFile("hikari-bars-", ".png").apply { deleteOnExit() }
    ImageIO.write(checkerboard(WIDTH.toInt(), HEIGHT.toInt()), "png", picture)
    val ipcName = "hikari-bars-test-" + pid
    val mpvLog = File.createTempFile("hikari-bars-mpv-", ".log")
    val args = listOf(
        mpvPath,
        "--no-config",
        "--no-border",
        "--auto-window-resize=no",
        "--no-osc",
        "--no-input-default-bindings",
        "--force-window=immediate",
        "--image-display-duration=inf",
        // The picture must FILL the window for this test: a letterboxed image
        // would put black under the floating bar, and "no picture here" would
        // mean nothing. Aspect handling inside the video area is mpv's business,
        // not the player's.
        "--keepaspect=no",
        "--title=$VIDEO_TITLE",
        // The player's own control channel, so the play/pause and close checks
        // below drive the REAL path (button -> PlayerWindow -> mpv). Written as a
        // concatenation rather than an escape soup: the value has to be exactly
        // `\\.\pipe\<name>`, which is what [MpvIpc] opens.
        "--input-ipc-server=" + "\\\\.\\pipe\\" + ipcName,
        "--log-file=" + mpvLog.absolutePath,
        picture.absolutePath,
    )
    println("  launching mpv: " + args.joinToString(" "))
    val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
    if (proc == null) {
        check("mpv launches", false, "ProcessBuilder failed")
        return report()
    }
    Thread(
        { runCatching { proc.inputStream.bufferedReader().forEachLine { } } },
        "bars-test-drain",
    ).apply { isDaemon = true; start() }

    var mpvHwnd: Long? = null
    val mpvBy = System.currentTimeMillis() + 20_000
    while (mpvHwnd == null && System.currentTimeMillis() < mpvBy && proc.isAlive) {
        mpvHwnd = WinShell.findWindowOf(proc.pid(), VIDEO_TITLE, WinShell.MPV_VIDEO_CLASS)
        if (mpvHwnd == null) Thread.sleep(200)
    }
    check("the player's own window exists", mpvHwnd != null)
    if (mpvHwnd == null) return report()

    // ── the app's player layer opens, adopts it, and floats its bars ────────
    var closedByButton = false
    val sources = listOf(StreamSource("Test source", "https://example.com/v.m3u8", isM3u8 = true))
    val opened = onFx {
        PlayerWindow.open(
            title = "Floating bars self-test",
            hasNext = true,
            next = { },
            position = { _, _ -> },
            closed = { closedByButton = true },
            sources = sources,
            sourceName = "Test source",
            onPickSource = { },
        )
    }
    check("the player layer opens and can embed the video", opened == true)
    onFx { PlayerWindow.attachProcess(proc.pid()) }

    var embedded = false
    val embedBy = System.currentTimeMillis() + 30_000
    while (!embedded && System.currentTimeMillis() < embedBy) {
        embedded = onFx { PlayerWindow.isEmbedded() } == true
        if (!embedded) Thread.sleep(250)
    }
    check("mpv's window is adopted by the player layer", embedded)
    if (!embedded) return report()

    // The picture is "up" (there is no stream here, only a still, so the layer
    // is told): that is what takes the loading overlay away and lets the video
    // surface be glued over the video area.
    onFx { PlayerWindow.previewPlaying() }
    check("the bars float over the picture", onFx { PlayerWindow.barsFloating() } == true)
    Thread.sleep(800)

    val barHwnd = onFx { PlayerWindow.floatingBarHwnd() }
    val stripHwnd = onFx { PlayerWindow.floatingStripHwnd() }
    val videoHwnd = onFx { PlayerWindow.videoSurfaceHwnd() }
    check("the control bar has a window of its own", barHwnd != null && barHwnd != 0L)
    check("the top strip has a window of its own", stripHwnd != null && stripHwnd != 0L)

    val videoRect = videoHwnd?.let { WinShell.windowRect(it) }
    val appRect = WinShell.windowRect(appHwnd)
    check(
        "the video window covers the WHOLE app window (the bars take no layout space)",
        videoRect != null && appRect != null &&
            kotlin.math.abs(videoRect[0] - appRect[0]) <= 4 &&
            kotlin.math.abs(videoRect[1] - appRect[1]) <= 4 &&
            kotlin.math.abs(videoRect[2] - appRect[2]) <= 6 &&
            kotlin.math.abs(videoRect[3] - appRect[3]) <= 6,
        "video=" + videoRect?.joinToString(",") + " app=" + appRect?.joinToString(","),
    )
    val area = onFx { PlayerWindow.videoAreaSize() }
    val layer = onFx { PlayerWindow.layerSize() }
    check(
        "the video area is the whole player layer in JavaFX too",
        area != null && layer != null && area[0] >= layer[0] - 1.0 && area[1] >= layer[1] - 1.0,
        "area=" + area?.joinToString(",") + " layer=" + layer?.joinToString(","),
    )

    val barRect = barHwnd?.let { WinShell.windowRect(it) }
    val stripRect = stripHwnd?.let { WinShell.windowRect(it) }
    println("  bar rect=" + barRect?.joinToString(",") + "  strip rect=" + stripRect?.joinToString(","))
    // A floating bar is a BAR. The first version placed a bar the height of the
    // whole window over the picture (a stage that kept the size it was shown
    // with, because `sizeToScene()` does nothing to a window already on screen):
    // every scene-level check passed, and only measuring the bar's own window
    // showed that the "transparent controls" were a translucent sheet over the
    // video. So the height is checked against the window, not just against zero.
    check(
        "the control bar is a short bar, not a panel over the picture",
        barRect != null && appRect != null && barRect[3] > 0 && barRect[3] <= appRect[3] / 4,
        "bar=" + barRect?.joinToString(",") + " app=" + appRect?.joinToString(","),
    )
    check(
        "the top strip is a short bar too",
        stripRect != null && appRect != null && stripRect[3] > 0 && stripRect[3] <= appRect[3] / 4,
        "strip=" + stripRect?.joinToString(",") + " app=" + appRect?.joinToString(","),
    )
    check(
        "the control bar sits at the bottom edge of the window",
        barRect != null && appRect != null &&
            kotlin.math.abs((barRect[1] + barRect[3]) - (appRect[1] + appRect[3])) <= 6 &&
            kotlin.math.abs(barRect[0] - appRect[0]) <= 6 &&
            barRect[2] >= appRect[2] - 8,
        "bar=" + barRect?.joinToString(",") + " app=" + appRect?.joinToString(","),
    )
    check(
        "the top strip sits at the top edge of the window",
        stripRect != null && appRect != null &&
            kotlin.math.abs(stripRect[1] - appRect[1]) <= 6 &&
            kotlin.math.abs(stripRect[0] - appRect[0]) <= 6 &&
            stripRect[2] >= appRect[2] - 8,
        "strip=" + stripRect?.joinToString(",") + " app=" + appRect?.joinToString(","),
    )

    // Windows enumerates top-level windows in z-order, topmost first: the bar
    // must come BEFORE the video window, or it is behind the picture.
    if (barHwnd != null && videoHwnd != null) {
        val above = WinShell.isAbove(barHwnd, videoHwnd)
        check(
            "the control bar is ABOVE the video window in the z-order",
            above == true,
            "isAbove=" + above + " bar=" + WinShell.windowRect(barHwnd)?.joinToString(",") +
                " video=" + WinShell.windowRect(videoHwnd)?.joinToString(","),
        )
    }
    if (stripHwnd != null && videoHwnd != null) {
        val above = WinShell.isAbove(stripHwnd, videoHwnd)
        check("the top strip is ABOVE the video window in the z-order", above == true, "isAbove=$above")
    }

    // ── the pixels: the bar is over the picture, and hiding it uncovers it ──
    //
    // The two numbers below are the mean luma of the SAME screen rectangle, once
    // with the chrome up and once with it away. Comparing that rectangle against
    // "the video" is what this check used to do, and it cannot work: the picture
    // here is a bright/dark checkerboard with white bands, so whether the bar's
    // rectangle reads darker than some other strip of video says as much about
    // which rows were sampled as about the bar. What "the bar is drawn over the
    // picture" actually means is that the pixels THERE change when the bar comes
    // and go back when it leaves — which is what is measured.
    //
    // The chrome has to be forced up first for the same reason: it auto-hides
    // after a few idle seconds, and this test's pointer never moves, so the old
    // version photographed a bar that had already taken itself away and compared
    // two pictures of bare video.
    onFx { PlayerWindow.previewChrome(true) }
    Thread.sleep(600)
    val barWithChrome = barRect?.let { meanLuma(it[0] + 8, it[1] + 8, it[2] - 16, it[3] - 24) }
    println("  mean luma over the bar rect, chrome UP   = " + barWithChrome)

    onFx { PlayerWindow.previewChrome(false) }
    Thread.sleep(700)
    val barWithoutChrome = barRect?.let { meanLuma(it[0] + 8, it[1] + 8, it[2] - 16, it[3] - 24) }
    val videoHidden = videoHwnd?.let { WinShell.windowRect(it) }
    println("  mean luma over the bar rect, chrome AWAY = " + barWithoutChrome)
    check(
        "the bar rect is REPAINTED while the chrome is up (the bar really is over the picture)",
        barWithChrome != null && barWithoutChrome != null &&
            kotlin.math.abs(barWithChrome - barWithoutChrome) > 6.0,
        "up=" + barWithChrome + " away=" + barWithoutChrome,
    )
    check(
        "hiding the bars gives the picture back (the rect reads as plain video again)",
        barWithChrome != null && barWithoutChrome != null && barWithoutChrome > 0.0,
        "uncovered=" + barWithoutChrome,
    )

    // ── the bars RENDER their own controls ──────────────────────────────────
    // A bar is a translucent panel with white text and icons on it. Every check
    // above is geometry (a window of the right size, in the right place, in the
    // right z-order) and a floating bar that draws NOTHING passes all of them —
    // which is exactly what a user reported: a top strip that was there, empty,
    // with no Back, no title and no close button. These two checks look at the
    // bar's own pixels.
    val stripBright = onFx { PlayerWindow.barBrightPixelsForTest(true) }
    val barBright = onFx { PlayerWindow.barBrightPixelsForTest(false) }
    println("  bar text/icon pixels: strip=$stripBright controlBar=$barBright")
    check(
        "the floating TOP STRIP draws its own contents (Back, title, buttons)",
        stripBright != null && stripBright > 40,
        "bright pixels=" + stripBright,
    )
    check(
        "the floating CONTROL BAR draws its own contents",
        barBright != null && barBright > 40,
        "bright pixels=" + barBright,
    )
    println("  chrome: " + PlayerWindow.chromeReport())

    // ── a bar the pointer is ON does not slide out from under it ────────────
    // This is the reported complaint, in full: the strip appears, the cursor
    // moves down to it, and it goes away again before it can be used (and with
    // it, any hope of finding a close button). The bars auto-hide on an idle
    // timer, and a pointer RESTING on a bar is not idle — so the test parks the
    // real cursor on the bar, waits longer than that timer, and asks whether the
    // bar is still there.
    val robot = runCatching { java.awt.Robot() }.getOrNull()
    if (robot == null || barRect == null || appRect == null) {
        println("  (skipping the pointer-rests-on-the-bar check: no usable Robot / no bar rect)")
    } else {
        onFx { PlayerWindow.previewChrome(true) }
        robot.mouseMove(barRect[0] + barRect[2] / 2, barRect[1] + barRect[3] / 2)
        Thread.sleep(800)
        check("the bars are up with the pointer on the control bar", onFx { PlayerWindow.chromeUpForTest() } == true)
        Thread.sleep(5_000)
        val stillUp = onFx { PlayerWindow.chromeUpForTest() }
        println("  after 5s with the pointer resting on the bar, chrome up = " + stillUp)
        check(
            "the bars do NOT hide while the pointer is on them (the reported disappearing strip)",
            stillUp == true,
            "chrome vanished under the pointer",
        )
        // ...and off the bars they go again, so the picture is never stuck with
        // controls over it: park the pointer back in the middle of the picture.
        robot.mouseMove(appRect[0] + appRect[2] / 2, appRect[1] + appRect[3] / 2)
        Thread.sleep(6_000)
        val afterLeaving = onFx { PlayerWindow.chromeUpForTest() }
        println("  after 6s with the pointer off the bars, chrome up = " + afterLeaving)
        check(
            "the bars hide again once the pointer leaves them",
            afterLeaving == false,
            "chrome stayed up with the pointer on the picture",
        )
        onFx { PlayerWindow.previewChrome(true) }
    }

    // The close button is the button the user could not find: it has to be on
    // screen, inside the bar's own window, and reachable while the chrome is up.
    val closeRect = onFx { PlayerWindow.closeButtonScreenRect() }
    check(
        "the control bar's close button is on screen inside the bar",
        closeRect != null && barRect != null &&
            closeRect[2] > 0 && closeRect[3] > 0 &&
            closeRect[0] >= barRect[0] - 4 &&
            closeRect[0] + closeRect[2] <= barRect[0] + barRect[2] + 4 &&
            closeRect[1] >= barRect[1] - 4 &&
            closeRect[1] + closeRect[3] <= barRect[1] + barRect[3] + 4,
        "close=" + closeRect?.joinToString(",") + " bar=" + barRect?.joinToString(","),
    )
    check(
        "the video does NOT resize when the bars come and go",
        videoRect != null && videoHidden != null &&
            kotlin.math.abs(videoRect[2] - videoHidden[2]) <= 4 &&
            kotlin.math.abs(videoRect[3] - videoHidden[3]) <= 4 &&
            kotlin.math.abs(videoRect[0] - videoHidden[0]) <= 4 &&
            kotlin.math.abs(videoRect[1] - videoHidden[1]) <= 4,
        "with bars=" + videoRect?.joinToString(",") + " without=" + videoHidden?.joinToString(","),
    )
    onFx { PlayerWindow.previewChrome(true) }

    // ── the control bar's play/pause button, against a controlled channel ───
    // The channel is a stand-in (see [FakeMpvChannel]): mpv's own pipe is
    // unusable on this runner (see the probe below), and the case that matters
    // most here — a toggle command that never lands — is not something a healthy
    // mpv will do on demand.
    val fake = FakeMpvChannel()
    val client = MpvIpc(fake.path.absolutePath, false)
    check(
        "the player's control channel connects (a controlled endpoint)",
        client.connect(5_000L),
        "socket=" + fake.path.name,
    )
    if (client.isAlive()) {
        onFx { PlayerWindow.attachIpc(client) }
        Thread.sleep(500)
        check("the channel reads back the picture's pause state", onFx { PlayerWindow.mpvPaused() } == false)

        // ── the Quality picker, through the same channel ────────────────────
        // The picture publishes the tracks it has (two video renditions, one
        // audio, one subtitle); the Quality pill must offer Auto plus exactly the
        // two renditions, and picking one must reach the picture as a `vid`.
        fake.publishTrackList()
        Thread.sleep(700)
        val qualityRows = onFx { PlayerWindow.qualityItems() }
        println("  quality menu rows: " + qualityRows)
        check(
            "the Quality menu offers Auto (adaptive) first",
            qualityRows?.firstOrNull() == "Auto (adaptive)",
            "" + qualityRows,
        )
        check(
            "the Quality menu lists every video track and nothing else",
            qualityRows?.size == 3,
            "" + qualityRows,
        )
        check(
            "a quality row carries the resolution being chosen between",
            qualityRows?.getOrNull(1)?.contains("1080p") == true &&
                qualityRows?.getOrNull(2)?.contains("720p") == true,
            "" + qualityRows,
        )
        check("nothing is pinned before a choice is made", onFx { PlayerWindow.qualityChoice() } == "auto")
        check("picking a quality row is accepted", onFx { PlayerWindow.pickQuality(2) } == true)
        Thread.sleep(600)
        check(
            "the picked row becomes the current quality",
            onFx { PlayerWindow.qualityChoice() } == "2",
            "" + onFx { PlayerWindow.qualityChoice() },
        )
        check(
            "the choice reaches the picture as a vid command",
            fake.commands.any { it.startsWith("set_property vid 2") },
            fake.commands.joinToString(" | ").take(300),
        )
        check("the picture's own vid is the one that was picked", fake.vid.toString() == "2", "" + fake.vid)
        onFx { PlayerWindow.pickQuality(0) }
        Thread.sleep(500)
        check("Auto hands the choice back to the stream", onFx { PlayerWindow.qualityChoice() } == "auto")
        check("Auto reaches the picture too", fake.vid.toString() == "auto", "" + fake.vid)

        onFx { PlayerWindow.clickPlayPause() }
        val paused = waitForFake(fake, true, 5_000L)
        check("pressing play/pause PAUSES the picture", paused == true, "channel=" + paused)
        check(
            "the button puts a pause command on the channel",
            fake.commands.any { it.startsWith("cycle pause") || it.startsWith("set_property pause") },
            fake.commands.joinToString(" | ").take(300),
        )

        onFx { PlayerWindow.clickPlayPause() }
        val resumed = waitForFake(fake, false, 5_000L)
        check("pressing it again RESUMES the picture (the reported bug)", resumed == false, "channel=" + resumed)

        // …and the case the user hit: the toggle command never lands. The player
        // is expected to notice (it re-reads the state afterwards) and to state
        // the intent outright, so the picture still ends up paused.
        fake.dropNextCycle = true
        onFx { PlayerWindow.clickPlayPause() }
        val recovered = waitForFake(fake, true, 8_000L)
        check("a DROPPED pause command is noticed and recovered from", recovered == true, "channel=" + recovered)

        println("  channel commands the player sent: " + fake.commands.joinToString(" | "))
        runCatching { client.close() }
    }
    fake.close()
    probePipe(ipcName)

    println("  ---- mpv log (ipc lines, and the tail) ----")
    runCatching {
        val all = mpvLog.readLines()
        all.filter { it.contains("ipc") }.takeLast(25).forEach { println("    " + it) }
        println("    ...")
        all.takeLast(25).forEach { println("    " + it) }
    }.onFailure { println("    (mpv wrote no log: " + it.message + ")") }

    // ── the player's close button closes the PLAYER ─────────────────────────
    val minimized = WinShell.isIconified(appHwnd)
    onFx { PlayerWindow.previewChrome(true) }
    Thread.sleep(200)
    onFx { PlayerWindow.clickClose() }
    Thread.sleep(700)
    check("the close button reports the player as closed", closedByButton)
    check("the app window is STILL OPEN after closing the player", WinShell.windowExists(appHwnd))
    check("closing the player did not minimise the app", minimized == WinShell.isIconified(appHwnd))
    check(
        "the video is no longer glued over the app",
        onFx { PlayerWindow.isEmbedded() } != true,
    )
    check(
        "the player layer is gone from the app window",
        onFx { !AppShell.playerHost.isVisible } == true,
    )
    check(
        "the floating bars are gone with it",
        onFx { PlayerWindow.barsFloating() } != true ||
            onFx { PlayerWindow.floatingBarHwnd() } == null,
    )

    return report()
}

/** Waits for the stand-in channel's own pause state to reach [want]. Its state —
 *  not the player's — is what the button is supposed to move. */
private fun waitForFake(fake: FakeMpvChannel, want: Boolean, timeoutMs: Long): Boolean? {
    val by = System.currentTimeMillis() + timeoutMs
    var last: Boolean? = null
    while (System.currentTimeMillis() < by) {
        last = fake.paused
        if (last == want) return last
        Thread.sleep(120)
    }
    return last
}

/**
 * A stand-in for mpv's control channel: a real [MpvIpc] client, over a real
 * socket, with the replies a test chooses to give.
 *
 * It exists for two reasons. mpv's own pipe is unusable on the CI runner — a
 * write into it never comes back (see [probePipe], which measures exactly that
 * and prints the answer) — so the pause/resume path cannot be driven through mpv
 * there. And the case that matters most, a command that never lands, is not
 * something a healthy mpv will do on demand: [dropNextCycle] swallows one toggle
 * exactly the way a dropped command does, and the player is expected to notice
 * and recover.
 */
private class FakeMpvChannel {

    /** A unique path that does not exist yet (binding a unix socket needs that). */
    val path: File = File.createTempFile("hikari-fake-mpv-", ".sock").apply { delete() }

    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

    /** The picture's pause state, as this channel sees it. */
    @Volatile
    var paused = false

    /** When set, the next `cycle pause` is answered but does nothing — a dropped
     *  command, seen from the player's side. Cleared by that command. */
    @Volatile
    var dropNextCycle = false

    /** Every command the player sent, in order, for the log. */
    val commands = java.util.Collections.synchronizedList(ArrayList<String>())

    /** The picture's video quality state (mpv's `vid`). */
    @Volatile var vid: Any = "auto"

    /** What the picture says its video tracks are — two renditions, so the
     *  Quality picker has something to choose between. */
    private val tracks = org.json.JSONArray().apply {
        put(JSONObject().put("type", "video").put("id", 1)
            .put("demux-w", 1920).put("demux-h", 1080).put("demux-bitrate", 4_200_000))
        put(JSONObject().put("type", "video").put("id", 2)
            .put("demux-w", 1280).put("demux-h", 720).put("demux-bitrate", 1_800_000))
        put(JSONObject().put("type", "audio").put("id", 1).put("lang", "eng"))
        put(JSONObject().put("type", "sub").put("id", 2).put("lang", "eng"))
    }

    private val writeLock = Any()

    @Volatile private var channel: SocketChannel? = null

    /** Pushes a `track-list` property change, exactly as mpv does when the file's
     *  tracks are known — the path the Quality/Audio/Subs menus are built from. */
    fun publishTrackList() {
        val ch = channel ?: return
        val message = JSONObject()
            .put("event", "property-change")
            .put("name", "track-list")
            .put("data", tracks)
        send(ch, message)
    }

    private fun send(ch: SocketChannel, json: JSONObject) {
        synchronized(writeLock) {
            runCatching { ch.write(ByteBuffer.wrap((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))) }
        }
    }

    init {
        server.bind(UnixDomainSocketAddress.of(path.toPath()))
        Thread({ serve() }, "fake-mpv-channel").apply { isDaemon = true; start() }
    }

    private fun serve() {
        runCatching {
            val ch = server.accept()
            channel = ch
            val buffer = ByteBuffer.allocate(8192)
            val line = StringBuilder()
            while (true) {
                buffer.clear()
                val n = ch.read(buffer)
                if (n < 0) break
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val c = buffer.get().toInt().toChar()
                    if (c == '\n') {
                        val text = line.toString()
                        line.setLength(0)
                        if (text.isNotBlank()) reply(ch, text)
                    } else if (c != '\r') {
                        line.append(c)
                    }
                }
            }
        }
    }

    private fun reply(ch: SocketChannel, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val args = json.optJSONArray("command") ?: return
        val verb = args.optString(0)
        val name = if (args.length() > 1) args.optString(1) else ""
        commands.add((0 until args.length()).joinToString(" ") { args.optString(it) })
        var data: Any? = null
        when (verb) {
            "cycle" -> {
                if (dropNextCycle) {
                    dropNextCycle = false
                } else if (name == "pause") {
                    paused = !paused
                }
                data = paused
            }
            "set_property" -> {
                if (name == "pause") paused = args.optBoolean(2)
                if (name == "vid") vid = args.opt(2) ?: "auto"
                data = paused
            }
            "get_property" -> data = when (name) {
                "pause" -> paused
                "vid" -> vid
                "track-list" -> tracks
                else -> 0
            }
            else -> data = 0
        }
        val out = JSONObject()
        out.put("request_id", json.optLong("request_id"))
        out.put("error", "success")
        out.put("data", data)
        send(ch, out)
    }

    fun close() {
        runCatching { server.close() }
        runCatching { path.delete() }
    }
}

/**
 * Measures mpv's OWN command pipe, and prints what it finds. This never fails the
 * test: it is the evidence behind "the pause check runs against a stand-in".
 *
 * The candidate causes for a command that is sent and never answered are all
 * distinguishable here — a write on a handle with nothing pending on it, a reply
 * on the same handle, a write while a read is pending on that handle (Windows
 * queues the I/O of a synchronous handle, so a pending read can hold a write
 * behind it), and whether mpv accepts a second client at all.
 */
private fun probePipe(pipeName: String) {
    val path = "\\\\.\\pipe\\" + pipeName
    val handle = runCatching { java.io.RandomAccessFile(path, "rw") }.getOrNull()
    if (handle == null) {
        println("  probe: mpv's command pipe could not be opened at all")
        return
    }
    var firstDone = false
    val t0 = System.currentTimeMillis()
    val w1 = Thread({ runCatching { handle.write(PROBE_CMD_1.toByteArray()); firstDone = true } }, "probe-w1")
    w1.isDaemon = true
    w1.start()
    var waited = 0L
    while (!firstDone && waited < 5_000) {
        Thread.sleep(100)
        waited += 100
    }
    println(
        "  probe: write with no read pending -> " +
            (if (firstDone) "completed in " + (System.currentTimeMillis() - t0) + " ms" else "STILL BLOCKED after 5s"),
    )

    val got = StringBuilder()
    val r1 = Thread(
        {
            runCatching {
                val b = ByteArray(4096)
                val n = handle.read(b)
                if (n > 0) got.append(String(b, 0, n, StandardCharsets.UTF_8))
            }
        },
        "probe-r1",
    )
    r1.isDaemon = true
    r1.start()
    waited = 0L
    while (got.isEmpty() && waited < 8_000) {
        Thread.sleep(200)
        waited += 200
    }
    println("  probe: reply on that handle after " + waited + " ms: \"" + got.toString().trim().take(160) + "\"")

    var secondDone = false
    val w2 = Thread({ runCatching { handle.write(PROBE_CMD_2.toByteArray()); secondDone = true } }, "probe-w2")
    w2.isDaemon = true
    w2.start()
    Thread.sleep(3_000)
    println(
        "  probe: write while a read is pending -> " +
            (if (secondDone) "completed" else "STILL BLOCKED after 3s"),
    )

    val two = runCatching { java.io.RandomAccessFile(path, "rw") }.getOrNull()
    println("  probe: a second client connection -> " + (two != null))
    runCatching { two?.close() }
}

private const val PROBE_CMD_1 = "{\"command\":[\"get_property\",\"pause\"],\"request_id\":9001}\n"
private const val PROBE_CMD_2 = "{\"command\":[\"get_property\",\"volume\"],\"request_id\":9002}\n"

private const val HOST_TITLE = "HikariFloatingBarsTestHost"
private const val VIDEO_TITLE = "HikariFloatingBarsTestVideo"
private const val WIDTH = 1160.0
private const val HEIGHT = 650.0
private const val WATCHDOG_MS = 240_000L

/** Runs [block] on the JavaFX thread and returns its result. */
private fun <T> onFx(block: () -> T): T? {
    val latch = CountDownLatch(1)
    var value: T? = null
    Platform.runLater {
        value = runCatching { block() }.getOrNull()
        latch.countDown()
    }
    if (!latch.await(20, TimeUnit.SECONDS)) return null
    return value
}

/** The mean luma of a screen rectangle — see the checks above for why a MEAN
 *  (rather than the spread EmbedSelfTest uses): the floating bars are
 *  translucent on purpose, so what they do to the picture is darken it, and a
 *  darkened picture has to be told from a bright one. */
private fun meanLuma(x: Int, y: Int, w: Int, h: Int): Double? = runCatching {
    val img: BufferedImage = Robot().createScreenCapture(Rectangle(x, y, w, h))
    var sum = 0.0
    var count = 0
    var i = 0
    while (i < img.width) {
        var j = 0
        while (j < img.height) {
            val rgb = img.getRGB(i, j)
            sum += (((rgb shr 16 and 0xFF) * 30 + (rgb shr 8 and 0xFF) * 59 + (rgb and 0xFF) * 11) / 100).toDouble()
            count++
            j += 4
        }
        i += 4
    }
    if (count == 0) null else sum / count
}.getOrElse {
    println("  (screen capture failed: " + (it.message ?: it.javaClass.simpleName) + ")")
    null
}

/** A bright checkerboard with white bands, so "the picture" and "a flat area"
 *  cannot be confused by the brightness checks above. */
private fun checkerboard(w: Int, h: Int): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    val cell = 20
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val light = ((x / cell) + (y / cell)) % 2 == 0
            g.color = if (light) java.awt.Color(230, 60, 90) else java.awt.Color(40, 180, 240)
            g.fillRect(x, y, cell, cell)
            x += cell
        }
        y += cell
    }
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, w, 8)
    g.fillRect(0, h - 8, w, 8)
    g.dispose()
    return img
}

private fun findMpvForBars(): File? {
    val rels = listOf(
        "jpackage-input/mpv/mpv.exe",
        "desktop/build/jpackage-input/mpv/mpv.exe",
        "mpv/mpv.exe",
        "mpv.exe",
    ).map { File(it) }
    return rels.firstOrNull { it.isFile } ?: Mpv.exe()
}
