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

    fun tooltip(text: String): Tooltip = Tooltip(text).apply {
        // Deliberately unhurried: a JavaFX tooltip is its own popup window, and
        // a popup that appears while the pointer is resting on a control can
        // take the next click (dismissing itself instead of pressing the
        // button). Only an intentional hover should bring one up.
        showDelay = Duration.millis(900.0)
    }

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
        val wrap = searchInput(prompt, width)
        (wrap.children[0] as TextField).setOnAction { onSubmit((wrap.children[0] as TextField).text.trim()) }
        return wrap
    }

    /** A search field wrapper whose [TextField] is the first child, so callers
     *  can drive it (focus, clear, fire the search) themselves. */
    fun searchInput(prompt: String = "Search…", width: Double = 260.0): StackPane {
        val input = TextField().apply {
            styleClass.addAll("field", "field-search")
            this.promptText = prompt
            prefWidth = width
            // Shrinkable: the top bar must still fit on a narrow window.
            minWidth = 150.0
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

    // ---- layout guarantees --------------------------------------------------

    /**
     * Makes a region fill whatever its parent gives it, and — just as
     * important — makes it able to shrink.
     *
     * JavaFX regions default `maxWidth`/`maxHeight` to their *preferred* size,
     * so a screen dropped into a `StackPane` is laid out at its preferred size;
     * and because a `VBox`'s minimum height is the SUM of its children's minima,
     * a page of rails and panels reports a minimum as tall as its whole content.
     * The pane therefore cannot shrink it, the page ends up taller than the
     * window, and none of it scrolls. Every screen root and every intermediate
     * container between a screen root and a scroll pane goes through this.
     */
    fun <T : Region> fill(region: T): T = region.apply {
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }

    /**
     * Stops a control inside a clickable container from also firing the
     * container's own handler. JavaFX buttons fire on mouse *release*, so
     * consuming `MOUSE_CLICKED` here only stops the event from bubbling to the
     * parent — a poster's "More" button, or the arrows inside the hero banner,
     * would otherwise also open the title.
     */
    fun <T : Node> isolateClicks(node: T): T = node.apply {
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
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
        ensureShimmer()
        opacityProperty().bind(shimmer)
    }

    /**
     * One shimmer value shared by every skeleton.
     *
     * Each skeleton used to own an INDEFINITE `Timeline`, and nothing stopped it
     * when the skeleton left the scene — so every catalog load leaked a dozen
     * full-rate animations that kept running forever. After a few refreshes the
     * FX pulse was saturated, which shows up as dropped input: clicks that need
     * two or three attempts. A single timeline driving a shared property costs
     * one animation no matter how many placeholders are on screen.
     */
    private val shimmer = javafx.beans.property.SimpleDoubleProperty(0.86)
    private var shimmerTimeline: Timeline? = null

    private fun ensureShimmer() {
        if (shimmerTimeline != null) return
        shimmerTimeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(shimmer, 0.86, Interpolator.EASE_BOTH)),
            KeyFrame(Duration.seconds(1.1), KeyValue(shimmer, 0.40, Interpolator.EASE_BOTH)),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            setAutoReverse(true)
            play()
        }
    }

    fun posterSkeleton(): VBox = VBox(Theme.S2,
        skeleton(Theme.POSTER_W, Theme.POSTER_H, 12.0),
        skeleton(Theme.POSTER_W * 0.72, 12.0, 4.0),
        skeleton(Theme.POSTER_W * 0.42, 10.0, 4.0),
    )

    fun skeletonRail(count: Int = 6): HBox = HBox(Theme.S4).apply {
        repeat(count) { children.add(posterSkeleton()) }
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
            // A scroll pane is where a too-big layout is supposed to stop: it
            // must be free to shrink to the viewport, or it reports its
            // content's full size as its minimum and the window grows to match.
            minWidth = 0.0
            minHeight = 0.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            if (content is Region) content.padding = padding
        }

    /** A horizontally scrolling rail of posters, used by the home screen rows.
     *  The vertical mouse wheel is translated into horizontal movement (and only
     *  consumed while the rail can still move that way), so scrolling over a row
     *  behaves the way people expect on a desktop. */
    fun rail(content: Node): ScrollPane = ScrollPane(content).apply {
        isFitToHeight = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        styleClass.addAll("scroll-pane", "poster-rail")
        // Crucial: a rail is a ScrollPane whose content is an HBox of poster
        // cards, and an HBox's minimum width is the SUM of its children's — 20
        // posters would otherwise declare a ~2800px minimum and drag the whole
        // window's minimum size with them.
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        (content as? Region)?.let { it.minWidth = 0.0 }
        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL) { e ->
            if (kotlin.math.abs(e.deltaY) > kotlin.math.abs(e.deltaX)) {
                val next = (hvalue - e.deltaY / 620.0).coerceIn(0.0, 1.0)
                if (next != hvalue) {
                    hvalue = next
                    e.consume()
                }
            }
        }
    }

    /**
     * Wraps a rail in a hover-reactive overlay with circular left/right arrows.
     * The arrows fade in only while the pointer is over the row, so the artwork
     * stays clean but paging is always one click away.
     */
    fun railWithArrows(scroll: ScrollPane): StackPane {
        fun arrow(icon: String, dir: Int, align: Pos): Button = Button().apply {
            styleClass.add("rail-arrow")
            graphic = Icons.of(icon, 16.0)
            isFocusTraversable = false
            opacity = 0.0
            // An invisible arrow must not steal clicks from the poster under it
            // — it only becomes interactive once the hover has faded it in.
            mouseTransparentProperty().bind(opacityProperty().lessThan(0.05))
            setOnAction { nudge(scroll, dir) }
            StackPane.setAlignment(this, align)
        }
        val left = arrow(Icons.CHEVRON_LEFT, -1, Pos.CENTER_LEFT)
        val right = arrow(Icons.CHEVRON_RIGHT, 1, Pos.CENTER_RIGHT)
        StackPane.setMargin(left, Insets(0.0, 0.0, 34.0, -10.0))
        StackPane.setMargin(right, Insets(0.0, -10.0, 34.0, 0.0))
        return StackPane(scroll, left, right).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            setOnMouseEntered { fade(left, 1.0); fade(right, 1.0) }
            setOnMouseExited { fade(left, 0.0); fade(right, 0.0) }
        }
    }

    /** Slides a rail by roughly a third of a screen. */
    fun nudge(scroll: ScrollPane, dir: Int) {
        val target = (scroll.hvalue + dir * 0.32).coerceIn(0.0, 1.0)
        Timeline(
            KeyFrame(Duration.millis(260.0), KeyValue(scroll.hvalueProperty(), target, Interpolator.EASE_BOTH)),
        ).play()
    }

    fun fade(node: Node, to: Double, millis: Double = 140.0) {
        FadeTransition(Duration.millis(millis), node).apply {
            toValue = to
            play()
        }
    }

    // ---- misc chrome --------------------------------------------------------

    /** A labelled icon node matching the type scale of [Theme.label]. */
    fun iconLabel(icon: String, text: String, size: Double = 13.0, cls: String? = null): HBox =
        HBox(8.0, Icons.of(icon, size + 3.0), Theme.label(text, size = size)).apply {
            alignment = Pos.CENTER_LEFT
            if (cls != null) styleClass.addAll(cls.split(" "))
        }

    /** A pill-shaped segmented control; [onSelect] receives the chosen index. */
    fun segmented(options: List<String>, initial: Int = 0, onSelect: (Int) -> Unit): HBox {
        val buttons = mutableListOf<Button>()
        val box = HBox(4.0).apply { styleClass.add("segmented") }
        options.forEachIndexed { index, label ->
            val button = Button(label).apply {
                styleClass.add("seg")
                if (index == initial) styleClass.add("seg-sel")
                isFocusTraversable = false
                setOnAction {
                    buttons.forEach { it.styleClass.remove("seg-sel") }
                    styleClass.add("seg-sel")
                    onSelect(index)
                }
            }
            buttons.add(button)
            box.children.add(button)
        }
        return box
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
