package desktop.ui

import com.hikari.app.data.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.Region

/**
 * A virtualised poster grid.
 *
 * Built on [ListView] with one cell per *row* of N posters: only the rows in view
 * are ever materialised, which is what keeps a library of a few thousand items
 * scrolling smoothly (a FlowPane holding thousands of ImageViews does not). The
 * column count is recomputed from the viewport width, so the grid reflows like a
 * desktop app should.
 */
class PosterGrid(
    private val cardWidth: Double = Theme.POSTER_W,
    private val onOpen: (MediaItem) -> Unit,
    private val onMore: ((MediaItem) -> Unit)? = null,
    private val progressFor: ((MediaItem) -> Double?)? = null,
) {

    private val gap = Theme.S4
    private val rowPad = Theme.S6
    private val cellHeight = cardWidth * Theme.POSTER_RATIO + 92.0

    private val list = ListView<List<MediaItem>>().apply {
        styleClass.add("poster-grid")
        isFocusTraversable = false
        setCellFactory { GridRowCell() }
    }

    private var all: List<MediaItem> = emptyList()
    private var columns = 5

    val root: Region = list

    init {
        list.widthProperty().addListener { _, _, width -> recomputeColumns(width.toDouble()) }
        list.setPlaceholder(null)
    }

    fun setItems(items: List<MediaItem>) {
        all = items
        rebuild()
    }

    fun clear() {
        all = emptyList()
        list.items.clear()
    }

    fun scrollToTop() {
        list.scrollTo(0)
    }

    fun itemCount(): Int = all.size

    private fun recomputeColumns(width: Double) {
        val usable = width - rowPad - 10.0
        val per = cardWidth + gap
        val next = if (usable <= 0) columns else (usable / per).toInt().coerceAtLeast(1)
        if (next != columns) {
            columns = next
            rebuild()
        }
    }

    private fun rebuild() {
        if (all.isEmpty()) {
            list.items.clear()
            return
        }
        list.items.setAll(all.chunked(columns))
    }

    private inner class GridRowCell : ListCell<List<MediaItem>>() {

        private val row = HBox(gap).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(6.0, rowPad, 0.0, rowPad)
        }

        override fun updateItem(items: List<MediaItem>?, empty: Boolean) {
            super.updateItem(items, empty)
            if (empty || items.isNullOrEmpty()) {
                graphic = null
                return
            }
            prefHeight = cellHeight
            minHeight = cellHeight
            row.children.setAll(
                items.map { item ->
                    PosterCard.make(
                        item = item,
                        onOpen = onOpen,
                        width = cardWidth,
                        progress = progressFor?.invoke(item),
                        onMore = onMore,
                    )
                }
            )
            graphic = row
        }
    }
}
