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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Explore: every enabled provider's catalogs, browsable in one place.
 *
 * One card per provider — initial badge, name, engine chip — and opening a
 * card loads THAT provider's catalog page (its catalogs, flattened into one
 * grid), so a tap always lands on the provider's own catalogue instead of a
 * filtered Home that looks unchanged. It reuses the same ContentRepository
 * the Home rails read from, so no new data path is introduced.
 */
class ExploreScreenView {

    private val body = VBox(Theme.S4)
    val root: ScrollPane = Ui.vScroll(body)
    private var catalogJob: Job? = null

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
            setOnMouseClicked { openCatalog(p) }
        }
    }

    /**
     * Loads this provider's catalogs and opens them as their own page — the
     * provider's catalogue, not a Home that merely has it selected.
     */
    private fun openCatalog(p: ProviderConfig) {
        catalogJob?.cancel()
        AppShell.toast("Loading ${p.name}…")
        catalogJob = AppShell.uiScope.launch {
            // A provider whose catalog fetch hangs used to leave this toast up
            // forever ("opening X…" that never opens): the load has a hard
            // budget, and a timeout names the provider instead of silence.
            val items = try {
                kotlinx.coroutines.withTimeout(20_000L) {
                    val rows = AppShell.app.repository.homeRows(providerId = p.id)
                    rows.flatMap { it.items }.distinctBy { it.uniqueId }.take(150)
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                desktop.fx.Fx.run {
                    AppShell.toast("“${p.name}” took too long to answer — try again.", "error")
                }
                return@launch
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                desktop.fx.Fx.run {
                    AppShell.toast("Couldn't open “${p.name}” (${t.message ?: t.javaClass.simpleName}).", "error")
                }
                return@launch
            }
            desktop.fx.Fx.run {
                if (items.isEmpty()) {
                    AppShell.toast("“${p.name}” returned no catalog rows — open the logs for the reason.", "error")
                } else {
                    AppShell.show(Screen.Catalog(p.name, p.name, items))
                }
            }
        }
    }
}
