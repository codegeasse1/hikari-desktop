package desktop.ui

import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
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

    /** Newest tap wins: an older load must never fill a newer page. */
    private var catalogGen = 0

    /** The provider whose catalog is (or was last) on screen: Back lands on
     *  a snapshot that predates its load, and this is what reloads it. */
    private var lastProvider: ProviderConfig? = null

    /** Re-entrancy guard for [openCatalog]: navigating to the Catalog screen
     *  synchronously calls back into [onCatalogShown] with the empty snapshot
     *  we navigated with — without this it re-opens the same catalog, which
     *  re-navigates, until the stack overflows and the page stays black. */
    private var inOpen = false

    /**
     * A Catalog screen just showed with whatever snapshot it carries. Fresh
     * navigation brings its own rows; a back-stack return brings an empty
     * one — refill it from the last load, or reload the provider when even
     * that is missing, so Back never sticks on Loading.
     */
    fun onCatalogShown(s: Screen.Catalog) {
        if (s.items.isNotEmpty()) {
            AppShell.lastCatalog[s.provider] = s.items
            return
        }
        // Our own navigation (see [openCatalog]): the async load already
        // started will fill this page — reloading here recurses until the
        // stack overflows and leaves a black page.
        if (inOpen) return
        val cached = AppShell.lastCatalog[s.provider]
        if (!cached.isNullOrEmpty()) {
            AppShell.catalogView?.setItems(cached)
            return
        }
        val p = lastProvider
        if (p != null && p.name == s.provider) openCatalog(p)
        else AppShell.catalogView?.setItems(emptyList())
    }

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
                    "Browse catalogs across ${providers.size} provider${if (providers.size == 1) "" else "s"} — open one to browse its catalog.",
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
        // The extension's own icon when it names one (see
        // [ExtensionsScreenView.providerIconFor]), the initial tile while it
        // loads or when none exists — never a bare letter when art is there.
        val badge = Ui.provBadge(p.name, runCatching { AppShell.extensionsView.providerIconFor(p) }.getOrNull(), 46.0)
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
     * Opens this provider's catalog page INSTANTLY — the page (with its
     * loading row) is the first thing the tap does, and the catalog lands
     * into it when it arrives. Waiting for the network BEFORE navigating is
     * what left users staring at a "Loading X…" toast for 10 seconds.
     */
    private fun openCatalog(p: ProviderConfig) {
        catalogJob?.cancel()
        lastProvider = p
        val gen = ++catalogGen
        // Navigate first, load second: the screen is up before a single
        // packet is awaited. The navigation itself calls back into
        // [onCatalogShown] with this empty snapshot, so [inOpen] is held
        // across it — that callback must NOT reload (see its note).
        inOpen = true
        try {
            AppShell.show(Screen.Catalog(p.name, p.name, emptyList()))
        } finally {
            inOpen = false
        }
        catalogJob = AppShell.uiScope.launch {
            // A provider whose catalog fetch hangs used to leave the wait up
            // forever ("opening X…" that never opens): the load has a hard
            // budget, and a timeout names the provider instead of silence.
            // A newer tap cancels this job, and a navigation away clears the
            // view — either way a late answer fills nothing.
            val items = try {
                kotlinx.coroutines.withTimeout(20_000L) {
                    val rows = AppShell.app.repository.homeRows(providerId = p.id)
                    rows.flatMap { it.items }.distinctBy { it.uniqueId }.take(150)
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                desktop.fx.Fx.run {
                    if (gen == catalogGen) {
                        // A stale load beats an empty screen: show last rows
                        // with the warning instead of a dead "no rows" page.
                        val stale = AppShell.lastCatalog[p.name]
                        if (!stale.isNullOrEmpty()) {
                            AppShell.catalogView?.setItems(stale)
                            AppShell.toast("“${p.name}” took too long to answer — showing last loaded rows.", "error")
                        } else {
                            AppShell.catalogView?.setItems(emptyList())
                            AppShell.toast("“${p.name}” took too long to answer — try again.", "error")
                        }
                    }
                }
                return@launch
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                desktop.fx.Fx.run {
                    if (gen == catalogGen) {
                        val stale = AppShell.lastCatalog[p.name]
                        if (!stale.isNullOrEmpty()) {
                            AppShell.catalogView?.setItems(stale)
                            AppShell.toast("Couldn't refresh “${p.name}” — showing last loaded rows.", "error")
                        } else {
                            AppShell.catalogView?.setItems(emptyList())
                            AppShell.toast("Couldn't open “${p.name}” (${t.message ?: t.javaClass.simpleName}).", "error")
                        }
                    }
                }
                return@launch
            }
            desktop.fx.Fx.run {
                if (gen == catalogGen) {
                    if (items.isNotEmpty()) AppShell.lastCatalog[p.name] = items
                    AppShell.catalogView?.setItems(items)
                }
            }
        }
    }
}
