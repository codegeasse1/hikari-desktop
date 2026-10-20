package desktop.ui

import com.hikari.app.data.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * The full grid behind a home rail's "See all" — one catalog, laid out as a
 * virtualised poster grid so a few hundred titles still scroll smoothly.
 */
class CatalogScreenView(
    private val title: String,
    private val provider: String,
    private val items: List<MediaItem>,
) {

    val root: VBox = VBox(Theme.S3).apply {
        padding = Insets(Theme.S4, Theme.S5, Theme.S4, Theme.S5)
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }

    private lateinit var grid: PosterGrid
    private lateinit var count: javafx.scene.control.Label
    private lateinit var loadingRow: HBox
    private lateinit var emptyNote: javafx.scene.control.Label

    init {
        val back = Ui.button("Back", icon = Icons.CHEVRON_LEFT, ghost = true) { AppShell.back() }
        val heading = Theme.label(title, size = 21.0, bold = true)
        count = Theme.label(
            if (items.isEmpty()) "Loading…" else "${items.size} title${if (items.size == 1) "" else "s"} · $provider",
            size = 12.5,
            dim = true,
        )
        grid = PosterGrid(Theme.POSTER_W, onOpen = { AppShell.openDetail(it) })
        grid.setItems(items)
        loadingRow = Ui.loadingRow("Loading $provider…").apply { isVisible = items.isEmpty(); isManaged = items.isEmpty() }
        emptyNote = Theme.label("", size = 13.0, dim = true).apply {
            isWrapText = true
            isVisible = false
            isManaged = false
        }
        root.children.addAll(
            HBox(12.0, back, heading, count).apply { alignment = Pos.CENTER_LEFT },
            loadingRow,
            emptyNote,
            grid.root,
        )
        VBox.setVgrow(grid.root, Priority.ALWAYS)
    }

    /**
     * Fills the grid after the fact: the Browse page navigates here FIRST
     * (instantly, with the loading row up) and the catalog lands when it
     * arrives — tapping a provider never stares at a toast for 10 seconds.
     */
    fun setItems(items: List<MediaItem>) {
        desktop.fx.Fx.run {
            grid.setItems(items)
            loadingRow.isVisible = false
            loadingRow.isManaged = false
            if (items.isEmpty()) {
                emptyNote.text = "“$provider” returned no catalog rows — open the logs for the reason."
                emptyNote.isVisible = true
                emptyNote.isManaged = true
                count.text = "No titles · $provider"
            } else {
                emptyNote.isVisible = false
                emptyNote.isManaged = false
                count.text = "${items.size} title${if (items.size == 1) "" else "s"} · $provider"
            }
        }
    }
}
