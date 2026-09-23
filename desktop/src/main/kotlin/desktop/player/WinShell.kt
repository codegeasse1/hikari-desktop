package desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * The raw Win32 calls that turn mpv's player window into part of the app's own
 * player view.
 *
 * Why this exists: mpv is the only thing that can play these streams, and on its
 * own it opens a SECOND window with its own title bar, taskbar entry and
 * controller — exactly the "old player" the app is supposed to replace.
 *
 * The approach here is deliberately the boring one: mpv KEEPS its own window
 * (so its video output is always created by mpv, for mpv, and therefore always
 * works — making mpv render into a window JavaFX owned is what produced the
 * "sound but no picture" failures), and this object *dresses* that window up as
 * the app's video area:
 *
 *  1. [findWindowOf] locates mpv's window by process id (no title guessing),
 *  2. [restyleAsVideoSurface] strips the caption/frame and makes it an owned,
 *     tool-window-style popup: no title bar, no taskbar button, never activated
 *     (so the app keeps keyboard focus),
 *  3. [placeWindow] moves it, in physical pixels, exactly over the player's
 *     video area — or far off-screen while the app has something of its own to
 *     show there (a spinner or an explanation).
 *
 * Every call is best-effort: if JNA is missing, if we are not on Windows, or if
 * a window is not found, the caller leaves mpv's window alone and nothing
 * breaks — a player that cannot be dressed up must never be worse than no
 * player.
 */
object WinShell {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * The user32 entry points JNA's [User32] wrapper does not expose with the
     * types this app needs. Loaded with JNA's UNICODE mapping, so each name
     * resolves to its `…W` export.
     *
     * `LONG_PTR` is declared as a Kotlin `Long`: on 64-bit Windows it is one,
     * and the app only ships (and is only tested) as a 64-bit build.
     */
    private interface Extra : StdCallLibrary {
        fun SetWindowLongPtr(hWnd: WinDef.HWND?, nIndex: Int, dwNewLong: Long): Long

        fun GetWindowLongPtr(hWnd: WinDef.HWND?, nIndex: Int): Long

        /** The pointer's screen position. Asked for by the player on a timer: the
         *  video surface is another process's window, so a move over the picture
         *  never reaches the app as an event. */
        fun GetCursorPos(p: Point): Boolean

        /** Whether a virtual key is currently held down (the high bit of the
         *  result). Used to notice a click on the video surface. */
        fun GetAsyncKeyState(vKey: Int): Short

        /** A window's class name. Declared here rather than used from JNA's
         *  [User32]: this object only needs it for the report, and a name
         *  resolved by the loader cannot go missing from a JNA build. */
        fun GetClassName(hWnd: WinDef.HWND?, lpClassName: CharArray, nMaxCount: Int): Int
    }

    /** A Win32 POINT, declared here so nothing about the out-parameter depends
     *  on the JNA platform package's structure layout. */
    @Structure.FieldOrder("x", "y")
    class Point : Structure() {
        @JvmField
        var x: Int = 0

        @JvmField
        var y: Int = 0
    }

    private val extra: Extra? by lazy {
        if (!isWindows) null
        else runCatching {
            Native.load("user32", Extra::class.java, W32APIOptions.UNICODE_OPTIONS)
        }.getOrNull()
    }

    /** True when JNA is on the classpath and this is Windows. */
    val available: Boolean by lazy {
        isWindows && runCatching { User32.INSTANCE != null && extra != null }.getOrDefault(false)
    }

    // ── Win32 constants ─────────────────────────────────────────────────────

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val GWLP_HWNDPARENT = -8

    private const val WS_CAPTION = 0x00C00000L
    private const val WS_BORDER = 0x00800000L
    private const val WS_DLGFRAME = 0x00400000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_MINIMIZEBOX = 0x00020000L
    private const val WS_MAXIMIZEBOX = 0x00010000L
    private const val WS_SYSMENU = 0x00080000L
    private const val WS_POPUP = 0x80000000L
    private const val WS_VISIBLE = 0x10000000L
    private const val WS_CLIPSIBLINGS = 0x04000000L
    private const val WS_CLIPCHILDREN = 0x02000000L

    private const val WS_EX_TOOLWINDOW = 0x00000080L
    private const val WS_EX_APPWINDOW = 0x00040000L
    private const val WS_EX_NOACTIVATE = 0x08000000L

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    /** `ShowWindow` command: hide the window (see [parkAndHide]). */
    private const val SW_HIDE = 0

    /** Virtual-key code for the left mouse button (see [leftButtonDown]). */
    private const val VK_LBUTTON = 0x01

    /** Where a parked (off-screen) video window is kept: nowhere near any
     *  plausible monitor origin, but still a *shown*, correctly sized window so
     *  the player's video output stays alive. */
    const val PARKED_X = -32000
    const val PARKED_Y = -32000

    // ── lookups ─────────────────────────────────────────────────────────────

    /** The HWND of the top-level window whose title is exactly [title]. */
    fun windowByTitle(title: String): Long? = call(null) {
        toLong(User32.INSTANCE.FindWindow(null, title))
    }

    /**
     * mpv's VIDEO window class on Windows. mpv owns more than one window in the
     * same process — this one, and helpers such as the
     * System-Media-Transport-Controls window (`mpv-smtc`) it creates alongside
     * it. A helper is visible and can even be larger than the video window, so
     * "the largest visible window of the process" is not good enough on its own:
     * this is the class that makes the player's window unmistakable.
     */
    const val MPV_VIDEO_CLASS = "mpv"

    /** Window classes that are never the player's video surface: mpv's SMTC
     *  helper, and the IME windows Windows attaches to any window. They belong
     *  to the same process, they are visible, and they would otherwise be
     *  candidates — the observed failure was the app dressing up `mpv-smtc` as
     *  the video surface while mpv drew the picture in its real window. */
    private val HELPER_CLASSES = setOf("mpv-smtc", "IME", "MSCTFIME UI")

    /** A window's class name, or null when it cannot be read. */
    private fun classNameOf(h: WinDef.HWND, buffer: CharArray): String? {
        val fn = extra ?: return null
        val len = runCatching { fn.GetClassName(h, buffer, buffer.size) }.getOrNull() ?: return null
        return if (len > 0) String(buffer, 0, len) else null
    }

    /**
     * The window of [pid] to use as a surface — mpv's window, once mpv has
     * created it, or the app's own window when called with our own pid.
     *
     * Chosen in this order:
     *
     *  1. a window whose title is exactly [titleHint] — how the app's own window
     *     is identified when the process also owns a bigger one (a WebView
     *     surface, a popup), and how mpv's window is identified by the title it
     *     was launched with;
     *  2. otherwise a window whose class is [preferClass] (`mpv` for the player:
     *     the class is the only thing that tells mpv's video window apart from
     *     the helpers it creates beside it);
     *  3. otherwise the largest visible window;
     *  4. and only then any window at all, which covers the case where Windows
     *     does not call the video window "visible" yet.
     *
     * Helper windows ([HELPER_CLASSES]) are never candidates, at any step.
     */
    fun findWindowOf(pid: Long, titleHint: String? = null, preferClass: String? = null): Long? = call(null) {
        if (pid <= 0L) return@call null
        var hinted = 0L
        var bestVisible = 0L
        var bestVisibleArea = -1L
        var bestAny = 0L
        var bestAnyArea = -1L
        var classVisible = 0L
        var classVisibleArea = -1L
        var classAny = 0L
        var classAnyArea = -1L
        val buffer = CharArray(512)
        val classBuffer = CharArray(256)
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hwnd: WinDef.HWND?, data: Pointer?): Boolean {
                val h = hwnd ?: return true
                val owner = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(h, owner)
                if (owner.value.toLong() != pid) return true
                val cls = classNameOf(h, classBuffer)
                if (cls != null && HELPER_CLASSES.contains(cls)) return true
                val address = Pointer.nativeValue(h.pointer)
                // The hint is checked before the visibility test on purpose: a
                // window that Windows does not call visible yet is still the
                // window we are looking for.
                if (!titleHint.isNullOrBlank() && hinted == 0L) {
                    val len = User32.INSTANCE.GetWindowText(h, buffer, buffer.size)
                    if (len > 0 && String(buffer, 0, len) == titleHint) hinted = address
                }
                val rect = WinDef.RECT()
                if (!User32.INSTANCE.GetWindowRect(h, rect)) return true
                val w = rect.right - rect.left
                val hh = rect.bottom - rect.top
                if (w <= 0 || hh <= 0) return true
                val area = w.toLong() * hh.toLong()
                val visible = User32.INSTANCE.IsWindowVisible(h)
                // A window the player has not shown yet is the LAST resort, not a
                // candidate the moment one is visible.
                if (visible && area > bestVisibleArea) {
                    bestVisibleArea = area
                    bestVisible = address
                }
                if (area > bestAnyArea) {
                    bestAnyArea = area
                    bestAny = address
                }
                if (preferClass != null && preferClass == cls) {
                    if (visible && area > classVisibleArea) {
                        classVisibleArea = area
                        classVisible = address
                    }
                    if (area > classAnyArea) {
                        classAnyArea = area
                        classAny = address
                    }
                }
                return true
            }
        }
        User32.INSTANCE.EnumWindows(callback, null)
        return@call hinted.takeIf { it != 0L }
            ?: classVisible.takeIf { it != 0L }
            ?: bestVisible.takeIf { it != 0L }
            ?: classAny.takeIf { it != 0L }
            ?: bestAny.takeIf { it != 0L }
    }

    /**
     * One top-level window of a process, as far as Windows will describe it.
     *
     * This exists for the report the player can copy: when a window cannot be
     * adopted, the first question is what Windows thought the player's windows
     * were, and this is the only honest answer to it.
     */
    data class WinInfo(
        val hwnd: Long,
        val visible: Boolean,
        val title: String,
        val className: String,
        val width: Int,
        val height: Int,
    ) {
        fun describe(): String = "hwnd=0x" + java.lang.Long.toHexString(hwnd) +
            (if (visible) " visible" else " hidden") +
            " " + width + "x" + height +
            " class=" + className.ifBlank { "?" } +
            " title=\"" + title.take(80) + "\""
    }

    /**
     * Every top-level window of [pid], biggest first — visible or not, which is
     * the point: a report has to show the windows that were NOT good enough to
     * adopt as well as the one that was.
     */
    fun windowsOf(pid: Long): List<WinInfo> = call(emptyList<WinInfo>()) {
        if (pid <= 0L) return@call emptyList()
        val found = ArrayList<WinInfo>()
        val buffer = CharArray(512)
        val classBuffer = CharArray(256)
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hwnd: WinDef.HWND?, data: Pointer?): Boolean {
                val h = hwnd ?: return true
                val owner = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(h, owner)
                if (owner.value.toLong() != pid) return true
                val titleLen = User32.INSTANCE.GetWindowText(h, buffer, buffer.size)
                val cls = classNameOf(h, classBuffer)
                val rect = WinDef.RECT()
                User32.INSTANCE.GetWindowRect(h, rect)
                found += WinInfo(
                    hwnd = Pointer.nativeValue(h.pointer),
                    visible = User32.INSTANCE.IsWindowVisible(h),
                    title = if (titleLen > 0) String(buffer, 0, titleLen) else "",
                    className = cls ?: "",
                    width = rect.right - rect.left,
                    height = rect.bottom - rect.top,
                )
                return true
            }
        }
        User32.INSTANCE.EnumWindows(callback, null)
        found.sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    /** [windowsOf] as report lines — or one line saying there are none. */
    fun describeWindows(pid: Long): List<String> {
        if (!available) return listOf("(Win32 window lookups unavailable)")
        val windows = windowsOf(pid)
        if (windows.isEmpty()) return listOf("no top-level windows at all")
        return windows.mapIndexed { i, w -> (i + 1).toString() + ". " + w.describe() }
    }

    /** The first visible child window of [parent] — a window nested inside it. */
    fun firstVisibleChild(parent: Long): Long? = call(null) {
        val user32 = User32.INSTANCE
        var child = user32.FindWindowEx(toHwnd(parent), null, null, null)
        while (child != null) {
            if (user32.IsWindowVisible(child)) return@call toLong(child)
            child = user32.FindWindowEx(toHwnd(parent), child, null, null)
        }
        null
    }

    /** True when the handle still names a live window — a cached handle must be
     *  discarded once the process behind it has exited. */
    fun windowExists(hwnd: Long): Boolean = call(false) {
        hwnd != 0L && User32.INSTANCE.IsWindow(toHwnd(hwnd))
    }

    /** A window's screen rectangle as [x, y, width, height], or null.
     *
     *  For an undecorated window this is also its CLIENT rectangle, which is
     *  what lets the player place the video without a screen-coordinate
     *  conversion: the video area's offset inside the app's client area plus
     *  this origin is the video's place on screen. (The app's window is never
     *  Win32-maximised — its maximise is a JavaFX resize — so the
     *  maximised-window inset Windows adds to `GetWindowRect` never applies.) */
    fun windowRect(hwnd: Long): IntArray? = call(null) {
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(toHwnd(hwnd), rect)) return@call null
        intArrayOf(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }

    // ── window dressing ─────────────────────────────────────────────────────

    /**
     * Removes a window's caption/frame, makes it a tool window that is never
     * activated, and gives it [owner] as its owning window — so it stays above
     * the app, minimises with it, and takes no taskbar slot of its own. The
     * app's own keyboard shortcuts keep working because an owned, non-activating
     * window never takes focus.
     */
    fun restyleAsVideoSurface(hwnd: Long, owner: Long): Boolean = call(false) {
        val fn = extra ?: return@call false
        if (!windowExists(hwnd)) return@call false
        val h = toHwnd(hwnd)
        val style = fn.GetWindowLongPtr(h, GWL_STYLE)
        val exStyle = runCatching { fn.GetWindowLongPtr(h, GWL_EXSTYLE) }.getOrDefault(0L)
        if (style == 0L) return@call false
        val frame = WS_CAPTION or WS_BORDER or WS_DLGFRAME or WS_THICKFRAME or
            WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU
        val newStyle = (style and frame.inv()) or WS_POPUP or WS_VISIBLE or
            WS_CLIPSIBLINGS or WS_CLIPCHILDREN
        val newExStyle = (exStyle and WS_EX_APPWINDOW.inv()) or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE
        fn.SetWindowLongPtr(h, GWL_STYLE, newStyle)
        fn.SetWindowLongPtr(h, GWL_EXSTYLE, newExStyle)
        if (owner != 0L) fn.SetWindowLongPtr(h, GWLP_HWNDPARENT, owner)
        User32.INSTANCE.SetWindowPos(
            h, WinDef.HWND(Pointer.createConstant(0)), 0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_FRAMECHANGED,
        )
        true
    }

    /** Moves and sizes a window in physical screen pixels, without activating
     *  it. A window parked off-screen is moved, never hidden or minimised: a
     *  hidden window makes mpv stop rendering into it. */
    fun placeWindow(hwnd: Long, x: Int, y: Int, w: Int, h: Int): Boolean = call(false) {
        if (w <= 0 || h <= 0) return@call false
        User32.INSTANCE.SetWindowPos(
            toHwnd(hwnd), WinDef.HWND(Pointer.createConstant(0)), x, y, w, h,
            SWP_NOACTIVATE,
        )
    }

    /** Kept for the CI smoke test: resizes a child window to fill its parent. */
    fun fillWindow(hwnd: Long, w: Int, h: Int): Boolean = call(false) {
        User32.INSTANCE.MoveWindow(toHwnd(hwnd), 0, 0, w, h, true)
    }

    /**
     * Moves a window off-screen and hides it (SW_HIDE).
     *
     * Used for exactly one thing: while a player is being torn down. The video
     * window belongs to mpv, so the app cannot destroy it — and if it is simply
     * abandoned, its last painted frame stays on screen until mpv's process
     * dies, which is the "the player stays stuck/frozen after closing" users
     * saw. Hiding it is immediate and costs mpv nothing it needs (it is about
     * to be killed).
     */
    fun parkAndHide(hwnd: Long): Boolean = call(false) {
        if (!windowExists(hwnd)) return@call false
        User32.INSTANCE.ShowWindow(toHwnd(hwnd), SW_HIDE)
        User32.INSTANCE.SetWindowPos(
            toHwnd(hwnd), WinDef.HWND(Pointer.createConstant(0)), PARKED_X, PARKED_Y, 320, 180,
            SWP_NOACTIVATE,
        )
        true
    }

    // ── the pointer ─────────────────────────────────────────────────────────

    /** The pointer's screen position as [x, y] in physical pixels, or null.
     *
     *  Polled rather than hooked, because the window that covers the picture
     *  belongs to another process and takes every mouse message for its whole
     *  rectangle — the app is never told the pointer moved at all. */
    fun cursorPos(): IntArray? = call(null) {
        val fn = extra ?: return@call null
        val p = Point()
        if (!fn.GetCursorPos(p)) return@call null
        intArrayOf(p.x, p.y)
    }

    /** True while the left mouse button is down anywhere on the desktop; null
     *  when the question cannot be asked. Used to notice a click on the video
     *  surface, whose window never forwards the click to the app. */
    fun leftButtonDown(): Boolean? = call(null) {
        val fn = extra ?: return@call null
        (fn.GetAsyncKeyState(VK_LBUTTON).toInt() and 0x8000) != 0
    }

    private inline fun <T> call(fallback: T, block: () -> T): T =
        if (!available) fallback else runCatching(block).getOrDefault(fallback)

    private fun toHwnd(value: Long): WinDef.HWND = WinDef.HWND(Pointer.createConstant(value))

    private fun toLong(hwnd: WinDef.HWND?): Long? = hwnd?.pointer?.let { Pointer.nativeValue(it) }
}
