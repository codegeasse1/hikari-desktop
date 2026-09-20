package desktop.ui

import javafx.animation.FadeTransition
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.ScaleTransition
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration

/**
 * Shared UI building blocks.
 *
 * Screens should be assembled from these instead of hand-styling controls, so
 * spacing, radii, and colours stay consistent and a theme/accent change lands
 * everywhere at once. Every method returns a plain JavaFX node — nothing here
 * touches app state.
 */
object Ui {

    // ---- buttons ------------------------------------------------------------

    fun button(
        text: String,
        icon: String? = null,
        primary: Boolean = false,
        ghost: Boolean = false,
        danger: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): Button = Button(text).apply {
        styleClass.add("btn")
        when {
            primary -> styleClass.add("btn-primary")
            danger -> styleClass.add("btn-danger")
            ghost -> styleClass.add("btn-ghost")
        }
        if (icon != null) graphic = Icons.of(icon, 16.0)
        if (onClick != null) setOnAction { onClick() }
    }

    fun iconButton(icon: String, tooltip: String? = null, size: Double = 17.0, onClick: (() -> Unit)? = null): Button =
        Button().apply {
            styleClass.add("btn-icon")
            graphic = Icons.of(icon, size)
            isFocusTraversable = false
            if (tooltip != null) Tooltip.install(this, tooltip(tooltip))
            if (onClick != null) setOnAction { onClick() }
        }

    fun playButton(text: String = "Play", onClick: (() -> Unit)? = null): Button = Button(text).apply {
        styleClass.add("btn-play")
        graphic = Icons.of(Icons.PLAY, 17.0)
        if (onClick != null) setOnAction { onClick() }
    }

    fun tooltip(text: String): Tooltip = Tooltip(text).apply { showDelay = Duration.millis(420.0) }

    // ---- inputs -------------------------------------------------------------

    fun field(prompt: String = "", width: Double? = null): TextField = TextField().apply {
        styleClass.add("field")
        promptText = prompt
        if (width != null) {
            prefWidth = width
            minWidth = width
        }
    }

    /** A search field with the magnifier drawn inside its left padding. */
    fun searchField(prompt: String = "Search…", width: Double = 260.0, onSubmit: (String) -> Unit = {}): StackPane {
        val input = TextField().apply {
            styleClass.addAll("field", "field-search")
            this.promptText = prompt
            prefWidth = width
            minWidth = width
            setOnAction { onSubmit(text.trim()) }
        }
        val glass = Icons.of(Icons.SEARCH, 15.0).apply { styleClass.add("search-glass") }
        return StackPane(input, glass).apply {
            StackPane.setAlignment(glass, Pos.CENTER_LEFT)
            StackPane.setMargin(glass, Insets(0.0, 0.0, 0.0, 12.0))
            isMouseTransparent = false
            styleClass.add("search-wrap")
        }
    }

    fun chip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null): Button = Button(text).apply {
        styleClass.add("pill")
        if (selected) styleClass.add("pill-selected")
        if (onClick != null) setOnAction { onClick() }
    }

    // ---- badges & headers ---------------------------------------------------

    fun badge(text: String, kind: String = "badge"): Label = Label(text).apply {
        styleClass.add("badge")
        styleClass.addAll(kind.split(" "))
    }

    fun sectionHeader(title: String, subtitle: String? = null, trailing: Node? = null): HBox {
        val text = VBox(1.0).apply {
            children.add(Theme.label(title, size = 17.0, bold = true).apply { styleClass.add("section-title") })
            if (subtitle != null) {
                children.add(Theme.label(subtitle, size = 12.5, dim = true).apply { styleClass.add("section-sub") })
            }
        }
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        return HBox(10.0, text, spacer).apply {
            alignment = Pos.CENTER_LEFT
            if (trailing != null) children.add(trailing)
        }
    }

    fun divider(): Region = Region().apply {
        styleClass.add("divider")
        minHeight = 1.0
        maxHeight = 1.0
    }

    fun spacer(): Region = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }

    fun card(vararg children: Node): VBox = VBox(10.0, *children).apply { styleClass.add("card") }

    fun panel(vararg children: Node): VBox = VBox(12.0, *children).apply { styleClass.add("panel") }

    fun statTile(value: String, label: String): VBox = VBox(0.0,
        Theme.label(value, size = 22.0, bold = true).apply { styleClass.add("stat-value") },
        Theme.label(label, size = 11.5).apply { styleClass.add("stat-label") },
    ).apply { styleClass.add("stat-tile") }

    // ---- states -------------------------------------------------------------

    fun emptyState(icon: String, title: String, subtitle: String? = null, action: Node? = null): VBox {
        val iconBox = StackPane(Icons.of(icon, 26.0)).apply { styleClass.add("empty-icon") }
        return VBox(12.0, iconBox).apply {
            styleClass.add("empty-state")
            alignment = Pos.CENTER
            children.add(Theme.label(title, size = 16.0, bold = true).apply { styleClass.add("empty-title") })
            if (subtitle != null) {
                children.add(Theme.label(subtitle, size = 12.5, dim = true).apply {
                    styleClass.add("empty-sub")
                    isWrapText = true
                    maxWidth = 460.0
                    textAlignment = javafx.scene.text.TextAlignment.CENTER
                })
            }
            if (action != null) children.add(action)
        }
    }

    fun loadingRow(text: String = "Loading…"): HBox = HBox(10.0,
        ProgressIndicator().apply { styleClass.add("spinner"); prefWidth = 18.0; prefHeight = 18.0; maxWidth = 18.0; maxHeight = 18.0 },
        Theme.label(text, size = 12.5, dim = true),
    ).apply { alignment = Pos.CENTER_LEFT }

    /** A shimmering placeholder block; [w]/[h] are fixed, so grids don't jump when content lands. */
    fun skeleton(w: Double, h: Double, radius: Double = 10.0): Region = Region().apply {
        prefWidth = w
        prefHeight = h
        minWidth = w
        minHeight = h
        maxWidth = w
        maxHeight = h
        styleClass.add("skeleton")
        style = "-fx-background-radius: ${radius}px;"
        pulse(this)
    }

    fun posterSkeleton(): VBox = VBox(Theme.S2,
        skeleton(Theme.POSTER_W, Theme.POSTER_H, 12.0),
        skeleton(Theme.POSTER_W * 0.72, 12.0, 4.0),
        skeleton(Theme.POSTER_W * 0.42, 10.0, 4.0),
    )

    fun skeletonRail(count: Int = 6): HBox = HBox(Theme.S4).apply {
        repeat(count) { children.add(posterSkeleton()) }
    }

    private fun pulse(node: Node) {
        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(node.opacityProperty(), 0.85, Interpolator.EASE_BOTH)),
            KeyFrame(Duration.seconds(1.0), KeyValue(node.opacityProperty(), 0.42, Interpolator.EASE_BOTH)),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            autoReverse = true
            play()
        }
        node.properties["hikari-pulse"] = timeline
    }

    // ---- transitions --------------------------------------------------------

    fun fadeIn(node: Node, millis: Double = 180.0) {
        node.opacity = 0.0
        FadeTransition(Duration.millis(millis), node).apply {
            toValue = 1.0
            play()
        }
    }

    fun popIn(node: Node) {
        node.scaleX = 0.97
        node.scaleY = 0.97
        ScaleTransition(Duration.millis(140.0), node).apply {
            toX = 1.0
            toY = 1.0
            interpolator = Interpolator.EASE_OUT
            play()
        }
    }

    // ---- scroll helpers -----------------------------------------------------

    fun vScroll(content: Node, padding: Insets = Insets(Theme.S5, Theme.S5, Theme.S6, Theme.S5)): ScrollPane =
        ScrollPane(content).apply {
            isFitToWidth = true
            styleClass.add("scroll-pane")
            if (content is Region) content.padding = padding
        }

    /** A horizontally scrolling rail of posters, used by the home screen rows. */
    fun rail(content: Node): ScrollPane = ScrollPane(content).apply {
        isFitToHeight = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        styleClass.addAll("scroll-pane", "poster-rail")
    }

    // ---- toasts -------------------------------------------------------------

    /** Transient status messages anchored to the bottom-right of a host pane. */
    class Toasts(private val host: StackPane) {

        private val column = VBox(Theme.S2).apply {
            styleClass.add("toast-host")
            alignment = Pos.BOTTOM_RIGHT
            isMouseTransparent = true
        }

        init {
            host.children.add(column)
            StackPane.setAlignment(column, Pos.BOTTOM_RIGHT)
            StackPane.setMargin(column, Insets(0.0, Theme.S5, 0.0, 0.0))
        }

        fun show(message: String, kind: String = "") {
            val toast = Label(message).apply {
                styleClass.add("toast")
                when (kind) {
                    "error" -> styleClass.add("toast-error")
                    "ok" -> styleClass.add("toast-ok")
                }
                isWrapText = true
                maxWidth = 380.0
            }
            column.children.add(toast)
            FadeTransition(Duration.millis(140.0), toast).apply { toValue = 1.0; play() }
            Timeline(
                KeyFrame(Duration.seconds(4.5)),
                KeyFrame(Duration.seconds(5.0), KeyValue(toast.opacityProperty(), 0.0)),
            ).apply {
                onFinished = { column.children.remove(toast) }
                play()
            }
        }
    }
}
