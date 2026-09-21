package desktop.player

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef

/**
 * The three raw Win32 calls that make mpv's video part of the app's own window.
 *
 * Why this exists: mpv is the only thing that can play these streams, and on its
 * own it opens a SECOND top-level window — which is exactly the "old player" the
 * app is supposed to replace. Telling mpv to render into a window the app owns
 * (`--wid=<hwnd>`) is what turns two windows into one: ours. JavaFX has no API
 * for "the native handle of this Stage", so the handle is found by title (the
 * player gives its video surface a unique one for exactly this), and the child
 * window mpv creates inside it is re-sized to fill it whenever the user resizes
 * or moves the player.
 *
 * Every call is best-effort. If JNA is missing, if we are not on Windows, or if
 * a window is not found, the caller falls back to mpv's own window and nothing
 * breaks — a player that cannot be embedded must never be worse than no player.
 */
object WinShell {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** True when JNA is on the classpath and this is Windows. */
    val available: Boolean by lazy {
        isWindows && runCatching { User32.INSTANCE != null }.getOrDefault(false)
    }

    /** The HWND of the top-level window whose title is exactly [title]. */
    fun windowByTitle(title: String): Long? = call(null) {
        toLong(User32.INSTANCE.FindWindow(null, title))
    }

    /** The first visible child window of [parent] — mpv's video surface, once it
     *  has attached to the handle it was given. */
    fun firstVisibleChild(parent: Long): Long? = call(null) {
        val user32 = User32.INSTANCE
        var child = user32.FindWindowEx(toHwnd(parent), null, null, null)
        while (child != null) {
            if (user32.IsWindowVisible(child)) return@call toLong(child)
            child = user32.FindWindowEx(toHwnd(parent), child, null, null)
        }
        null
    }

    /** Moves and sizes a child window to [w]x[h] at its parent's client origin. */
    fun fillWindow(hwnd: Long, w: Int, h: Int): Boolean = call(false) {
        User32.INSTANCE.MoveWindow(toHwnd(hwnd), 0, 0, w, h, true)
    }

    /** True when the handle still names a live window — a cached child handle
     *  must be discarded once mpv has exited, or the next episode's video would
     *  never be re-sized. */
    fun windowExists(hwnd: Long): Boolean = call(false) {
        User32.INSTANCE.IsWindow(toHwnd(hwnd))
    }

    private inline fun <T> call(fallback: T, block: () -> T): T =
        if (!available) fallback else runCatching(block).getOrDefault(fallback)

    private fun toHwnd(value: Long): WinDef.HWND = WinDef.HWND(Pointer.createConstant(value))

    private fun toLong(hwnd: WinDef.HWND?): Long? = hwnd?.pointer?.let { Pointer.nativeValue(it) }
}
