package desktop.ui

import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox

/**
 * Explore: every enabled provider's catalogs, browsable in one place.
 *
 * One card per provider — initial badge, name, engine chip — and opening a
 * card jumps Home straight to that provider's catalog (see
 * [HomeScreenView.showProvider]). It reuses the same ContentRepository the
 * Home rails read from, grouped by provider, so no new data path is
 * introduced.
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
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
        val column = VBox(Theme.S4)
        if (providers.isEmpty()) {
            column.children.add(Ui.emptyState(Icons.EXTENSIONS, "No providers enabled", "Enable a provider in Extensions to browse catalogs here."))
        } else {
            column.children.add(
                Ui.sectionHeader(
                    "Explore",
                    "Browse catalogs across ${providers.size} provider${if (providers.size == 1) "" else "s"} — open one to see its catalog on Home.",
                )
            )
            val grid = TilePane(Theme.S4, Theme.S4).apply {
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                prefColumns = 3
            }
            providers.forEach { grid.children.add(providerCard(it)) }
            column.children.add(grid)
        }
        body.children.setAll(column)
    }

    private fun providerCard(p: ProviderConfig): VBox {
        val initial = Label(p.name.trim().firstOrNull()?.uppercase() ?: "?").apply {
            styleClass.add("x-prov-initial")
        }
        val badge = StackPane(initial).apply {
            styleClass.add("x-prov-badge")
            alignment = Pos.CENTER
            minWidth = 46.0; prefWidth = 46.0; maxWidth = 46.0
            minHeight = 46.0; prefHeight = 46.0; maxHeight = 46.0
        }
        val name = Theme.label(p.name, size = 14.5, bold = true).apply {
            styleClass.add("x-prov-name")
            isWrapText = true
            minWidth = 0.0
        }
        val engine = Ui.badge(Ui.providerEngineLabel(p.type), "badge-accent")
        val texts = VBox(4.0, name, HBox(engine).apply { alignment = Pos.CENTER_LEFT }).apply { minWidth = 0.0 }
        HBox.setHgrow(texts, Priority.ALWAYS)
        val go = Icons.of(Icons.CHEVRON_RIGHT, 18.0)
        val row = HBox(12.0, badge, texts, go).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            padding = Insets(14.0, 16.0, 14.0, 14.0)
        }
        return VBox(row).apply {
            styleClass.add("x-prov-card")
            cursor = javafx.scene.Cursor.HAND
            minWidth = 0.0
            prefWidth = 330.0
            maxWidth = 420.0
            setOnMouseClicked { AppShell.homeView.showProvider(p.name) }
        }
    }
}
