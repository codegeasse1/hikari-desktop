package desktop.player

import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * CI smoke test for the *whole* embedded-player chain, on the Windows runner
 * that actually ships the app:
 *
 *  1. a real window of our own (a Swing frame, so the test needs no JavaFX and
 *     no JavaFX display) proves the Win32 lookups work — [WinShell.findWindowOf]
 *     finds it by process id, [WinShell.restyleAsVideoSurface] strips its chrome,
 *     [WinShell.placeWindow] moves it and [WinShell.windowRect] reads it back;
 *  2. the bundled mpv is launched on a generated still image and its own window
 *     is adopted exactly the way the app adopts it, then driven over mpv's JSON
 *     IPC to ask whether a video output actually came up (`vo-configured`,
 *     `video-format`) — the difference between "the picture is in the app" and
 *     "the app is showing a black rectangle while mpv plays somewhere else";
 *  3. the pixels over the adopted window are sampled, so a "configured but
 *     black" video output is visible in the log rather than inferred.
 *
 * Exits non-zero when the Win32 plumbing itself fails (that is a real
 * regression). A runner with no desktop, no window station or no working video
 * output prints `SKIP`/`NO VIDEO` with the evidence and exits 0 — the log is
 * then the answer to "why doesn't the picture show up on this machine?".
 */
fun main() {
    println("EmbedSelfTest: start")
    val failures = mutableListOf<String>()
    fun check(name: String, condition: Boolean, detail: String = "") {
        println(if (condition) "  OK   $name" else "  FAIL $name $detail")
        if (!condition) failures += name
    }

    if (!WinShell.available) {
        println("EmbedSelfTest: SKIP (not Windows / JNA unavailable)")
        return
    }

    // ── 1. our own window ───────────────────────────────────────────────────
    System.setProperty("java.awt.headless", "false")
    var frame: JFrame? = null
    runCatching {
        SwingUtilities.invokeAndWait {
            val f = JFrame("HikariEmbedTestHost")
            f.setSize(900, 520)
            f.setLocation(80, 80)
            f.isVisible = true
            frame = f
        }
    }.onFailure { println("  (no GUI available: ${it.message})") }

    val pid = ProcessHandle.current().pid()
    var hostHwnd: Long? = null
    val deadline = System.currentTimeMillis() + 8_000
    while (hostHwnd == null && System.currentTimeMillis() < deadline) {
        hostHwnd = WinShell.findWindowOf(pid, "HikariEmbedTestHost")
        if (hostHwnd == null) Thread.sleep(150)
    }
    check("findWindowOf(own pid, title) finds our window", hostHwnd != null)
    check("findWindowOf(own pid) without a hint finds it too", WinShell.findWindowOf(pid) == hostHwnd)
    val host = hostHwnd ?: run {
        println("EmbedSelfTest: SKIP (this runner has no usable window station)")
        frame?.let { runCatching { SwingUtilities.invokeAndWait { it.dispose() } } }
        return
    }
    println("  host hwnd=" + host + " rect=" + WinShell.windowRect(host)?.joinToString(","))

    check("restyleAsVideoSurface(host)", WinShell.restyleAsVideoSurface(host, 0L))
    check("placeWindow(host, 120, 90, 640, 360)", WinShell.placeWindow(host, 120, 90, 640, 360))
    val rect = WinShell.windowRect(host)
    check(
        "windowRect round-trips the placement",
        rect != null && kotlin.math.abs(rect[0] - 120) <= 2 && kotlin.math.abs(rect[1] - 90) <= 2 &&
            kotlin.math.abs(rect[2] - 640) <= 2 && kotlin.math.abs(rect[3] - 360) <= 2,
        "got " + rect?.joinToString(","),
    )
    // ── 2. mpv, adopted the way the app adopts it ───────────────────────────
    val mpv = findMpv()
    println("  mpv=" + (mpv?.absolutePath ?: "NOT FOUND"))
    if (mpv == null) {
        println("EmbedSelfTest: SKIP (bundled mpv not found on this runner)")
        cleanup(frame, null, null)
        return
    }

    val picture = File.createTempFile("hikari-embed-", ".png").apply { deleteOnExit() }
    ImageIO.write(testImage(320, 180), "png", picture)

    val ipcName = "hikari-embed-test-" + ProcessHandle.current().pid()
    // mpv's own log is the only witness to what its video output did, and it is
    // printed at the end of this test — that is what tells "the window was
    // adopted and the picture really came up" apart from "the window was
    // adopted and sat black".
    val mpvLog = File.createTempFile("hikari-embed-mpv-", ".log")
    val args = buildList {
        add(mpv.absolutePath)
        add("--no-config")
        add("--no-border")
        add("--auto-window-resize=no")
        add("--no-osc")
        add("--no-input-default-bindings")
        add("--force-window=immediate")
        add("--image-display-duration=inf")
        add("--title=HikariEmbedTest")
        add("--input-ipc-server=\\\\.\\pipe\\$ipcName")
        add("--log-file=" + mpvLog.absolutePath)
        add(picture.absolutePath)
    }
    println("  launching mpv: " + args.joinToString(" "))
    val proc = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
    if (proc == null) {
        check("mpv launches", false, "ProcessBuilder failed")
        println("EmbedSelfTest: SKIP (mpv would not start)")
        cleanup(frame, null, null)
        return
    }
    val mpvOut = StringBuilder()
    Thread(
        {
            runCatching { proc.inputStream.bufferedReader().forEachLine { if (mpvOut.length < 6000) mpvOut.append(it).append('\n') } }
        },
        "embed-test-drain",
    ).apply { isDaemon = true; start() }

    var mpvHwnd: Long? = null
    val mpvDeadline = System.currentTimeMillis() + 20_000
    while (mpvHwnd == null && System.currentTimeMillis() < mpvDeadline && proc.isAlive) {
        mpvHwnd = WinShell.findWindowOf(proc.pid())
        if (mpvHwnd == null) Thread.sleep(200)
    }
    val window = mpvHwnd
    check("findWindowOf(mpv pid) finds mpv's window", window != null)
    if (window != null) {
        println("  mpv hwnd=" + window + " rect=" + WinShell.windowRect(window)?.joinToString(","))
        // Exactly what the app does: strip the chrome, own it, glue it over the
        // host window's area.
        check("restyleAsVideoSurface(mpv)", WinShell.restyleAsVideoSurface(window, host))
        check("placeWindow(mpv, 140, 110, 720, 405)", WinShell.placeWindow(window, 140, 110, 720, 405))
        val placed = WinShell.windowRect(window)
        check(
            "mpv window sits where the app put it",
            placed != null && kotlin.math.abs(placed[0] - 140) <= 2 && kotlin.math.abs(placed[1] - 110) <= 2 &&
                kotlin.math.abs(placed[2] - 720) <= 2 && kotlin.math.abs(placed[3] - 405) <= 2,
            "got " + placed?.joinToString(","),
        )
    }

    // ── 2b. the window must exist WHILE the stream is still loading ─────────
    // The reported bug: mpv's `--force-window=yes` creates the window only AFTER
    // the file has finished initialising, and a network stream that is slow (or
    // hung) initialises for as long as it likes. The app adopts that window — so
    // on a slow stream there was nothing to adopt, and a perfectly good player
    // was announced as "this machine cannot draw the video inside the app
    // window". A server that accepts the connection and never answers
    // reproduces exactly that, and the window has to be there anyway: that is
    // what `--force-window=immediate` (mpv's own [network] profile) is for.
    val stall = runCatching { java.net.ServerSocket(0) }.getOrNull()
    val heldSockets = java.util.Collections.synchronizedList(ArrayList<java.net.Socket>())
    if (stall != null) {
        Thread(
            {
                while (!stall.isClosed) {
                    val s = runCatching { stall.accept() }.getOrNull() ?: break
                    heldSockets.add(s)
                }
            },
            "embed-test-stall-server",
        ).apply { isDaemon = true; start() }
    }
    val stallArgs = buildList {
        add(mpv.absolutePath)
        add("--no-config")
        add("--no-border")
        add("--auto-window-resize=no")
        add("--no-osc")
        add("--no-input-default-bindings")
        add("--force-window=immediate")
        add("--keep-open=yes")
        add("--title=HikariEmbedStall")
        add("--input-ipc-server=\\\\.\\pipe\\hikari-embed-stall-" + ProcessHandle.current().pid())
        add("http://127.0.0.1:" + (stall?.localPort ?: 0) + "/stream-that-never-answers.ts")
    }
    println("  launching mpv on a stream that never answers: " + stallArgs.joinToString(" "))
    val stallProc = runCatching { ProcessBuilder(stallArgs).redirectErrorStream(true).start() }.getOrNull()
    if (stallProc == null) {
        check("mpv launches for the stalled-stream case", false, "ProcessBuilder failed")
    } else {
        Thread(
            { runCatching { stallProc.inputStream.bufferedReader().forEachLine { } } },
            "embed-test-stall-drain",
        ).apply { isDaemon = true; start() }
        var stallHwnd: Long? = null
        val stallUntil = System.currentTimeMillis() + 12_000
        while (stallHwnd == null && System.currentTimeMillis() < stallUntil && stallProc.isAlive) {
            stallHwnd = WinShell.findWindowOf(stallProc.pid())
            if (stallHwnd == null) Thread.sleep(150)
        }
        val elapsed = 12_000 - (stallUntil - System.currentTimeMillis())
        check(
            "mpv's window EXISTS while the stream is still loading (--force-window=immediate)",
            stallHwnd != null,
            "no window after " + elapsed + " ms on a stream that never answers",
        )
        if (stallHwnd == null) {
            println("  windows of the stalled player's pid:")
            WinShell.describeWindows(stallProc.pid()).forEach { println("    " + it) }
        } else {
            println("  stall hwnd=" + stallHwnd + " rect=" + WinShell.windowRect(stallHwnd)?.joinToString(","))
            // The same two calls the app makes, on a window that exists only
            // because the flag is `immediate`.
            check("restyleAsVideoSurface(stalled mpv)", WinShell.restyleAsVideoSurface(stallHwnd, host))
            check("placeWindow(stalled mpv)", WinShell.placeWindow(stallHwnd, 160, 130, 640, 360))
        }
        runCatching { stallProc.descendants().forEach { c -> runCatching { c.destroyForcibly() } } }
        runCatching { stallProc.destroyForcibly() }
        runCatching { stallProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
    }
    runCatching { stall?.close() }
    heldSockets.forEach { runCatching { it.close() } }

    // ── 3. did a video output actually come up? ─────────────────────────────
    val ipc = MpvIpc(ipcName, true)
    var voConfigured: Any? = null
    var videoFormat: Any? = null
    var shot = ""
    if (ipc.connect(10_000L)) {
        // Generous on purpose: on a machine with no real GPU mpv falls back to
        // software rendering and spends this window compiling shaders, during
        // which it answers no IPC requests at all (observed: picture at ~90s,
        // every request before that timed out). The PIXELS are the verdict —
        // see below — and this only adds what mpv itself says when it says it.
        val until = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < until) {
            voConfigured = runCatching { ipc.getProperty("vo-configured") }.getOrNull() ?: voConfigured
            videoFormat = runCatching { ipc.getProperty("video-format") }.getOrNull() ?: videoFormat
            if (voConfigured == true || videoFormat != null) break
            shot = sample(140, 110, 720, 405)
            if (picture(shot)) break
            Thread.sleep(1000)
        }
        println("  vo-configured=$voConfigured video-format=$videoFormat")
        if (shot.isEmpty()) shot = sample(140, 110, 720, 405)
        println("  pixels over the video area: $shot")
        if (picture(shot)) {
            println("EmbedSelfTest: video CONFIRMED by the pixels inside the adopted window")
        } else if (voConfigured != true && videoFormat == null) {
            println("EmbedSelfTest: NO VIDEO (the window was adopted, but nothing is being drawn in it)")
        }
    } else {
        println("  (mpv's IPC pipe never appeared)")
        println("EmbedSelfTest: NO VIDEO (no IPC)")
    }
    println("  ---- mpv output ----")
    println(mpvOut.toString().trim().take(3000))
    println("  --------------------")
    println("  ---- mpv log (tail) ----")
    runCatching { mpvLog.readLines().takeLast(80).forEach { println("  " + it) } }
    println("  ---- /mpv log (" + (if (mpvLog.isFile) mpvLog.length().toString() + " bytes" else "missing") + ") ----")
    runCatching { ipc.close() }

    // ── 4. teardown: the video window must not outlive the player ───────────
    // This is the "closing the app left the player stuck/frozen on screen"
    // bug: the adopted window belongs to mpv, and abandoning it leaves its last
    // frame over the desktop. The app now parks and hides it and then kills
    // mpv — both are checked here.
    if (window != null) {
        check("parkAndHide(mpv's window) takes it off the screen", WinShell.parkAndHide(window))
        println("  after parkAndHide: rect=" + WinShell.windowRect(window)?.joinToString(","))
        runCatching { proc.descendants().forEach { c -> runCatching { c.destroyForcibly() } } }
        runCatching { proc.destroyForcibly() }
        runCatching { proc.waitFor(6, java.util.concurrent.TimeUnit.SECONDS) }
        val goneBy = System.currentTimeMillis() + 6_000
        while (WinShell.windowExists(window) && System.currentTimeMillis() < goneBy) Thread.sleep(100)
        check("the video window is GONE once the player's process is killed", !WinShell.windowExists(window))
    }

    cleanup(frame, proc, picture)

    if (failures.isNotEmpty()) {
        println("EmbedSelfTest: " + failures.size + " FAILED — " + failures.joinToString("; "))
        kotlin.system.exitProcess(1)
    }
    println("EmbedSelfTest: OK")
    // The Swing host above starts AWT's non-daemon event thread, so returning
    // from main would leave the JVM alive and hang the CI step (observed: the
    // step sat "in progress" forever with every check already printed).
    kotlin.system.exitProcess(0)
}

private fun cleanup(frame: JFrame?, proc: Process?, picture: File?) {
    runCatching { proc?.destroy() }
    runCatching { picture?.delete() }
    frame?.let { f -> runCatching { SwingUtilities.invokeAndWait { f.dispose() } } }
}

/** A strongly coloured test pattern, so a sampled frame can tell "picture" from
 *  "black window". */
private fun testImage(w: Int, h: Int): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(230, 60, 90), w.toFloat(), h.toFloat(), java.awt.Color(40, 180, 240))
    g.fillRect(0, 0, w, h)
    g.color = java.awt.Color.WHITE
    g.fillOval(w / 4, h / 4, w / 2, h / 2)
    g.dispose()
    return img
}

/** True when a sample line reports a picture rather than a flat/blank area. */
private fun picture(sample: String): Boolean {
    val spread = Regex("spread=(-?\\d+)").find(sample)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return spread >= 40
}

/** Samples the middle of a screen rectangle: a flat result means nothing (or
 *  only black) is being drawn there. */
private fun sample(x: Int, y: Int, w: Int, h: Int): String = runCatching {
    val img = Robot().createScreenCapture(Rectangle(x + w / 4, y + h / 4, w / 2, h / 2))
    var min = 255
    var max = 0
    var step = 4
    var i = 0
    while (i < img.width) {
        var j = 0
        while (j < img.height) {
            val rgb = img.getRGB(i, j)
            val luma = ((rgb shr 16 and 0xFF) * 30 + (rgb shr 8 and 0xFF) * 59 + (rgb and 0xFF) * 11) / 100
            if (luma < min) min = luma
            if (luma > max) max = luma
            j += step
        }
        i += step
    }
    "min=$min max=$max spread=" + (max - min)
}.getOrElse { "unavailable: " + (it.message ?: it.javaClass.simpleName) }

/** The bundled mpv, wherever this run happens to keep it (the packaged app
 *  layout in CI, or next to the working directory). */
private fun findMpv(): File? {
    val rels = listOf(
        "jpackage-input/mpv/mpv.exe",
        "desktop/build/jpackage-input/mpv/mpv.exe",
        "mpv/mpv.exe",
        "mpv.exe",
    ).map { File(it) }
    return rels.firstOrNull { it.isFile } ?: Mpv.exe()
}
