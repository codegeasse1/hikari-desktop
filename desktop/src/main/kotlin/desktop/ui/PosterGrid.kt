package desktop.ui

import com.hikari.app.data.MediaItem
import javafx.geometry.Insets
import javafx.scene.control.ScrollPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region

/**
 * A poster grid.
 *
 * A plain wrapping [FlowPane] in a [ScrollPane] — deliberately NOT a
 * virtualised ListView. Virtualised cells are recycled between mouse press
 * and release (column reflows, late fills), so a press and its release can
 * land on different node generations and the click never completes: the
 * classic "first click does nothing, second click opens". Grids here are
 * capped in the low hundreds (catalogs take 150), where a flat grid scrolls
 * smoothly and every card is the node it always was — one click opens.
 * Images still stream in asynchronously, so a slow CDN never blocks the UI.
 */
class PosterGrid(
    private val cardWidth: Double = Theme.POSTER_W,
    private val onOpen: (MediaItem) -> Unit,
    private val onMore: ((MediaItem) -> Unit)? = null,
    private val progressFor: ((MediaItem) -> Double?)? = null,
) {

    private val gap = Theme.S4
    private val rowPad = Theme.S6

    private val flow = FlowPane(gap, gap).apply {
        padding = Insets(6.0, rowPad, 16.0, rowPad)
    }

    private val scroll = ScrollPane(flow).apply {
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        styleClass.addAll("scroll-pane", "poster-flow")
        isFocusTraversable = false
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }

    private var all: List<MediaItem> = emptyList()

    val root: Region = scroll

    /**
     * Paints the whole grid in ONE atomic swap.
     *
     * This used to fill in 24-card batches across pulses (smoother first
     * paint), but every `addAll` reflows the FlowPane: a card under the pointer
     * shifts between mouse-press and mouse-release and JavaFX eats the click —
     * the "first click does nothing, second click opens" report. Images still
     * stream in asynchronously in place (fixed-size frames, no reflow), so one
     * atomic swap is both instant-feeling and click-safe, and it stays that way
     * no matter what else changes.
     */
    fun setItems(items: List<MediaItem>) {
        all = items
        flow.children.setAll(items.map { item ->
            PosterCard.make(
                item = item,
                onOpen = onOpen,
                width = cardWidth,
                progress = progressFor?.invoke(item),
                onMore = onMore,
            )
        })
    }

    fun clear() {
        all = emptyList()
        flow.children.clear()
    }

    fun scrollToTop() {
        scroll.vvalue = 0.0
    }

    fun itemCount(): Int = all.size
}
