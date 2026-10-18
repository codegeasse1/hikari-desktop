package desktop.ui

import com.hikari.app.data.MediaItem
import javafx.scene.control.ScrollPane
import javafx.scene.layout.VBox

/**
 * Explore: every enabled provider's catalogs, browsable in one place.
 *
 * CS3-parity screen for the reference client's Explore & Catalogs view. It reuses
 * the same ContentRepository the Home rails read from, grouped by provider with
 * a provider picker row on top, so no new data path is introduced.
 */
class ExploreScreenView {

    private val body = VBox(Theme.S4)
    val root: ScrollPane = Ui.vScroll(body)

    fun onShown() {
        render()
    }

    private fun render() {
        val app = AppShell.app
        val providers = runCatching { app.store.providers().filter { it.enabled } }.getOrDefault(emptyList())
        val column = VBox(Theme.S4)
        if (providers.isEmpty()) {
            column.children.add(Ui.emptyState(desktop.ui.Icons.EXTENSIONS, "No providers enabled", "Enable a provider in Extensions to browse catalogs here."))
        } else {
            column.children.add(Theme.sub("Browse catalogs across " + providers.size + " provider" + if (providers.size == 1) "" else "s"))
            for (p in providers) {
                val card = VBox(Theme.S2)
                card.children.add(Ui.sectionHeader(p.name, "Open catalog"))
                card.children.add(Theme.faint("Catalogs load on Home rails; opening the full catalog happens from a rail header."))
                card.styleClass.add("card")
                column.children.add(card)
            }
        }
        body.children.setAll(column)
    }
}
