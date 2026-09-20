package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.ui.theme.HikariThemeMode
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import java.util.prefs.Preferences

/**
 * Design tokens + theming for the desktop app.
 *
 * Colours live in `theme.css` as JavaFX looked-up colours defined on `.root`, so
 * every component re-themes from one place. This object owns the two things that
 * *select* a theme — light/dark and the accent preset — and exposes the spacing,
 * radius and type scales that layout code should use instead of magic numbers.
 *
 * Persistence: light/dark goes through [AppStore.theme] (the same key the Android
 * app writes), the accent preset through Java's [Preferences] so no on-disk data
 * format changes.
 */
object Theme {

    // ---- legacy colour constants -------------------------------------------
    // Kept because screens still reference them in inline styles; prefer the
    // style classes in theme.css for anything new.
    val BG = "#0a0b10"
    val BG_ELEV = "#13161e"
    val BG_CARD = "#191d27"
    val FG = "#eef0f7"
    val FG_DIM = "#a6adc2"
    val ACCENT = "#a970ff"
    val ACCENT_2 = "#5aa2ff"
    val BORDER = "#232838"
    val OK = "#3ddc97"
    val WARN = "#ffc857"
    val DANGER = "#ff6b6b"

    // ---- spacing scale ------------------------------------------------------
    const val S1 = 4.0
    const val S2 = 8.0
    const val S3 = 12.0
    const val S4 = 16.0
    const val S5 = 22.0
    const val S6 = 28.0

    /** Poster width used by every grid and rail, so screens line up. */
    const val POSTER_W = 168.0
    /** 2:3 poster aspect, matching the Android app's artwork. */
    const val POSTER_RATIO = 1.5
    const val POSTER_H = POSTER_W * POSTER_RATIO

    // ---- accents ------------------------------------------------------------

    val ACCENTS = listOf("violet", "blue", "cyan", "emerald", "amber", "rose")

    private val prefs: Preferences = Preferences.userRoot().node("hikari-desktop")

    @Volatile
    private var currentScene: Scene? = null

    @Volatile
    var mode: HikariThemeMode = HikariThemeMode.DARK
        private set

    @Volatile
    var accent: String = ACCENTS.first()
        private set

    // ---- typography ---------------------------------------------------------

    /**
     * A themed label. Colour comes from CSS classes (so it follows the active
     * theme); only the size is set inline, which keeps every call site that
     * already passes a size working unchanged.
     */
    fun label(text: String, size: Double = 14.0, bold: Boolean = false, dim: Boolean = false): Label =
        Label(text).apply {
            styleClass.add("label")
            if (dim) styleClass.add("h-dim")
            if (bold) styleClass.add("h-bold")
            style = "-fx-font-size: ${size}px;"
        }

    fun display(text: String): Label = Label(text).apply { styleClass.add("h-display") }

    fun title(text: String): Label = Label(text).apply { styleClass.add("h-title") }

    fun sub(text: String): Label = Label(text).apply { styleClass.add("h-sub") }

    fun faint(text: String, size: Double = 12.0): Label = Label(text).apply {
        styleClass.add("h-faint")
        style = "-fx-font-size: ${size}px;"
    }

    fun mono(text: String): Label = Label(text).apply { styleClass.add("h-mono") }

    /** Small all-caps group heading, e.g. the sidebar sections. */
    fun overline(text: String): Label = Label(text.uppercase()).apply { styleClass.add("h-upper") }

    // ---- theming -----------------------------------------------------------

    /** Applies the theme to a scene: canvas fill, stylesheet, mode + accent classes. */
    fun style(scene: Scene): Scene = scene.apply {
        currentScene = this
        val css = Theme::class.java.getResource("/theme.css")
        if (css != null && !stylesheets.contains(css.toExternalForm())) {
            stylesheets.add(css.toExternalForm())
        }
        mode = runCatching { HikariApp.instance.store.theme() }
            .getOrNull()
            ?.let { HikariThemeMode.fromKey(it) }
            ?: HikariThemeMode.DARK
        accent = prefs.get("accent", ACCENTS.first()).takeIf { it in ACCENTS } ?: ACCENTS.first()
        applyClasses()
        fill = javafx.scene.paint.Color.web(if (mode == HikariThemeMode.LIGHT) "#eff1f7" else BG)
    }

    fun scene(root: javafx.scene.Parent): Scene = style(Scene(root))

    /** Re-reads the persisted theme + accent and applies them to the live scene. */
    fun refresh() {
        mode = runCatching { HikariApp.instance.store.theme() }
            .getOrNull()
            ?.let { HikariThemeMode.fromKey(it) }
            ?: mode
        applyClasses()
        currentScene?.fill = javafx.scene.paint.Color.web(if (mode == HikariThemeMode.LIGHT) "#eff1f7" else BG)
    }

    fun setMode(next: HikariThemeMode) {
        mode = next
        runCatching { HikariApp.instance.store.setTheme(next.key) }
        applyClasses()
        currentScene?.fill = javafx.scene.paint.Color.web(if (next == HikariThemeMode.LIGHT) "#eff1f7" else BG)
    }

    fun setAccent(key: String) {
        if (key !in ACCENTS) return
        accent = key
        runCatching { prefs.put("accent", key) }
        applyClasses()
    }

    /** Raw looked-up colour name for inline styles: `Theme.varOf("accent")`. */
    fun varOf(token: String): String = "-h-$token"

    private fun applyClasses() {
        val root = currentScene?.root ?: return
        root.styleClass.removeAll("light")
        if (mode == HikariThemeMode.LIGHT) root.styleClass.add("light")
        ACCENTS.forEach { root.styleClass.remove("accent-$it") }
        root.styleClass.add("accent-$accent")
    }

    // ---- layout helpers -----------------------------------------------------

    fun insets(all: Double): Insets = Insets(all)

    fun insets(x: Double, y: Double): Insets = Insets(y, x, y, x)

    fun centerBox(vararg children: javafx.scene.Node): VBox = VBox(12.0, *children).apply {
        alignment = Pos.CENTER
    }
}
