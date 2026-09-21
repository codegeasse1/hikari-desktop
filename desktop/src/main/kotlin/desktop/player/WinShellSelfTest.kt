package desktop.player

/**
 * CI smoke test for the raw Win32 layer the embedded player needs.
 *
 * `WinShell` is the only place in the app that reaches outside JavaFX, and it is
 * reached lazily from the player — which a headless build cannot start. So this
 * exercises the plumbing directly, on the packaged classpath, where the failure
 * modes actually live: a missing JNA artifact, a native library that cannot be
 * extracted, or a Win32 call that blows up on a null handle. Every one of those
 * would otherwise only show up as a black video area on a user's machine.
 *
 * Exits non-zero on failure.
 */
fun main() {
    println("WinShellSelfTest: start")
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println(if (ok) "  OK   $name" else "  FAIL $name ${detail}")
        if (!ok) failures++
    }

    check("JNA + Win32 mappings load", WinShell.available)

    if (WinShell.available) {
        // A window that certainly does not exist must come back as null, not as
        // a crash or a bogus handle.
        check("windowByTitle(unknown) == null", WinShell.windowByTitle("__hikari_no_such_window__") == null)
        // An invalid parent handle must come back as "found nothing", not as a
        // crash and not as a bogus handle (this probe runs from a timer while
        // mpv starts, so it fires repeatedly before mpv's window exists).
        check("firstVisibleChild(invalid) == null", WinShell.firstVisibleChild(0x7FFF0000L) == null)
        // A dead cached handle must be detectable, or the next episode's video
        // would never be re-sized.
        check("windowExists(0) is false", !WinShell.windowExists(0L))
        // Filling a window that does not exist must fail quietly.
        check("fillWindow(0) is false", !WinShell.fillWindow(0L, 16, 16))
    }

    if (failures > 0) {
        println("WinShellSelfTest: $failures FAILED")
        kotlin.system.exitProcess(1)
    }
    println("WinShellSelfTest: OK")
}
