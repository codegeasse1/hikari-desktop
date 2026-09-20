package desktop.ui

import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * Library: favourites and continue-watching, both driven by [AppStore] so the
 * data matches the Android app's.
 *
 * Continue-watching entries carry a resume position, which is rendered as the
 * progress bar on the poster card; clicking one opens its detail screen.
 */
class LibraryScreenView {

    private val body = StackPane()
    private val tabs = HBox(8.0)
    val root: ScrollPane = Ui.vScroll(body)

    private var tab = TAB_FAVOURITES

    fun onShown() {
        renderTabs()
        render()
    }

    private fun renderTabs() {
        tabs.children.setAll(
            Ui.chip("Favourites", tab == TAB_FAVOURITES) { tab = TAB_FAVOURITES; onShown() },
            Ui.chip("Continue watching", tab == TAB_CONTINUE) { tab = TAB_CONTINUE; onShown() },
        )
        tabs.alignment = Pos.CENTER_LEFT
    }

    private fun render() {
        val store = AppShell.app.store
        val favorites = runCatching { store.favorites() }.getOrDefault(emptyList())
        val history = runCatching { store.history() }.getOrDefault(emptyList())

        val column = VBox(Theme.S4)
        column.children.add(tabs)

        when (tab) {
            TAB_FAVOURITES -> renderFavorites(column, favorites, history)
            else -> renderContinue(column, history)
        }
        body.children.setAll(column)
    }

    private fun renderFavorites(column: VBox, favorites: List<MediaItem>, history: List<HistoryEntry>) {
        column.children.add(
            Ui.sectionHeader(
                "Favourites",
                if (favorites.isEmpty()) null else "${favorites.size} title${if (favorites.size == 1) "" else "s"}",
            )
        )
        if (favorites.isEmpty()) {
            column.children.add(
                Ui.emptyState(
                    Icons.HEART_OUTLINE,
                    "No favourites yet",
                    "Open a title and tap the heart to keep it here. Favourites are shared with the Android app's library.",
                )
            )
            return
        }
        val progress = progressByMedia(history)
        val grid = PosterGrid(
            onOpen = { AppShell.openDetail(it) },
            progressFor = { progress[it.uniqueId] },
        )
        grid.setItems(favorites)
        column.children.add(grid.root.apply { VBox.setVgrow(this, Priority.ALWAYS) })
    }

    private fun renderContinue(column: VBox, history: List<HistoryEntry>) {
        val items = history.filter { it.positionMs > 0L }
        column.children.add(
            Ui.sectionHeader(
                "Continue watching",
                if (items.isEmpty()) null else "${items.size} in progress",
                trailing = Ui.button("Clear history", ghost = true) {
                    runCatching { AppShell.app.store.clearHistory() }
                    AppShell.toast("Watch history cleared")
                    onShown()
                },
            )
        )
        if (items.isEmpty()) {
            column.children.add(
                Ui.emptyState(
                    Icons.HISTORY,
                    "Nothing in progress",
                    "Titles you start watching appear here with a resume bar, so you can jump straight back in.",
                )
            )
            return
        }
        val grid = PosterGrid(
            onOpen = { AppShell.openDetail(it) },
            progressFor = { media -> fractionFor(media, history) },
        )
        grid.setItems(items.map { it.toMediaItem() })
        column.children.add(grid.root.apply { VBox.setVgrow(this, Priority.ALWAYS) })
    }

    private fun progressByMedia(history: List<HistoryEntry>): Map<String, Double> =
        history.associate { it.toMediaItem().uniqueId to fraction(it) }

    private fun fractionFor(media: MediaItem, history: List<HistoryEntry>): Double? =
        history.firstOrNull {
            it.providerId == media.providerId && it.mediaId == media.id && it.type == media.type
        }?.let { fraction(it) }

    private fun fraction(entry: HistoryEntry): Double =
        if (entry.durationMs > 0L) (entry.positionMs.toDouble() / entry.durationMs.toDouble()) else 0.0

    private fun HistoryEntry.toMediaItem(): MediaItem = MediaItem(
        providerId = providerId,
        id = mediaId,
        title = title,
        type = type,
        posterUrl = posterUrl,
    )

    private companion object {
        const val TAB_FAVOURITES = "favourites"
        const val TAB_CONTINUE = "continue"
    }
}
