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

    init {
        val back = Ui.button("Back", icon = Icons.CHEVRON_LEFT, ghost = true) { AppShell.back() }
        val heading = Theme.label(title, size = 21.0, bold = true)
        val count = Theme.label(
            "${items.size} title${if (items.size == 1) "" else "s"} · $provider",
            size = 12.5,
            dim = true,
        )
        val grid = PosterGrid(Theme.POSTER_W, onOpen = { AppShell.openDetail(it) })
        grid.setItems(items)
        root.children.addAll(
            HBox(12.0, back, heading, count).apply { alignment = Pos.CENTER_LEFT },
            grid.root,
        )
        VBox.setVgrow(grid.root, Priority.ALWAYS)
    }
}
