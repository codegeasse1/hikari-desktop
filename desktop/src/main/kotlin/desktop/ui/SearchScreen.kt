package desktop.ui

import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SearchScreenView {

    val root: VBox = VBox(12.0).apply {
        padding = Insets(18.0, 22.0, 18.0, 22.0)
    }

    private val queryInput = TextField().apply {
        styleClass.add("field")
        promptText = "Search all extensions…"
        prefWidth = 420.0
    }
    private val searchBtn = Button("Search").apply { styleClass.add("btn") }
    private val loading = ProgressBar(-1.0)
    private val status = Theme.label("", dim = true)
    private val grid = FlowPane().apply {
        hgap = 16.0
        vgap = 18.0
        padding = Insets(6.0, 2.0, 20.0, 2.0)
    }

    // ── Extension picker (which installed extensions answer the search) ───

    /** Quick-find box above the chips: narrows them by name, so with 50
     *  extensions installed you type two letters and click instead of
     *  scrolling through every chip. */
    private val providerFilterInput = TextField().apply {
        styleClass.add("field")
        promptText = "Find extension…"
        prefWidth = 240.0
        textProperty().addListener { _, _, _ -> renderProviderChips() }
    }

    /** "n of N match" hint next to the quick-find box. */
    private val chipsHelp = Theme.label("", size = 11.5, dim = true)

    /** Wrapping chip row: "All extensions" + one chip per installed (enabled)
     *  extension. Wraps, never scrolls horizontally. */
    private val chipsRow = FlowPane().apply {
        hgap = 8.0
        vgap = 8.0
        padding = Insets(2.0, 0.0, 2.0, 0.0)
    }

    /** Empty = search every extension; otherwise ONLY these provider ids
     *  are searched. Multi-select is intentional (e.g. two anime extensions). */
    private val selectedProviders = LinkedHashSet<String>()

    /** All enabled provider configs (id → display name), refreshed each time
     *  the screen is shown so new installs/uninstalls appear immediately. */
    private var providerNames: Map<String, String> = emptyMap()

    private var searchJob: Job? = null

    init {
        val header = HBox(12.0, queryInput, searchBtn).apply { alignment = Pos.CENTER_LEFT }
        root.children.addAll(
            header,
            HBox(10.0, Theme.label("Search in:", size = 12.5, dim = true), providerFilterInput, chipsHelp).apply {
                alignment = Pos.CENTER_LEFT
            },
            // Bounded so 50 chips can't eat the results area; the quick-find
            // box keeps the relevant chip on screen without any scrolling.
            ScrollPane(chipsRow).apply {
                isFitToWidth = true
                maxHeight = 148.0
                styleClass.add("scroll-pane")
            },
            HBox(12.0, loading, status).apply {
                alignment = Pos.CENTER_LEFT
            },
            ScrollPane(grid).apply {
                VBox.setVgrow(this, Priority.ALWAYS)
                isFitToWidth = true
                styleClass.add("scroll-pane")
            },
        )
        loading.isVisible = false
        searchBtn.setOnAction { search() }
        queryInput.setOnAction { search() }
        renderProviderChips()
    }

    fun onShown() {
        // Installs/uninstalls/toggles change the provider list — refresh the
        // chips every time the tab is opened.
        providerNames = AppShell.app.store.providers()
            .filter { it.enabled }
            .associate { it.id to it.name.ifBlank { it.type.name } }
        // Drop selections that no longer exist (uninstalled extension).
        selectedProviders.retainAll(providerNames.keys)
        renderProviderChips()
        if (grid.children.isEmpty()) {
            status.text = "Type a query above. Results stream in as each extension answers."
        }
    }

    // ── Provider chips ─────────────────────────────────────────────────────

    /** Rebuilds the "All extensions" + extension chips from the quick-find
     *  filter, keeping the current selection visible via a ✓. */
    private fun renderProviderChips() {
        chipsRow.children.clear()
        val needle = providerFilterInput.text.trim().lowercase()
        val entries = providerNames.entries
            .filter { needle.isEmpty() || it.value.lowercase().contains(needle) }
            .sortedBy { it.value.lowercase() }

        chipsRow.children.add(
            chip("All extensions (${providerNames.size})", selectedProviders.isEmpty()) {
                selectedProviders.clear()
                renderProviderChips()
            }
        )
        entries.forEach { (id, name) ->
            chipsRow.children.add(
                chip(name, id in selectedProviders) {
                    if (id in selectedProviders) selectedProviders.remove(id)
                    else selectedProviders.add(id)
                    renderProviderChips()
                }
            )
        }

        val total = providerNames.size
        chipsHelp.text = when {
            total == 0 -> "No extensions installed yet — add some in the Extensions tab."
            needle.isEmpty() ->
                if (selectedProviders.isEmpty()) "searching all of them"
                else "${selectedProviders.size} selected"
            else -> "${entries.size} of $total match “$needle”"
        }
    }

    private fun chip(text: String, selected: Boolean, onAction: () -> Unit): Button =
        Button(if (selected) "✓ $text" else text).apply {
            styleClass.addAll("chip", if (selected) "chip-selected" else "chip-plain")
            setOnAction { onAction() }
        }

    // ── Search ─────────────────────────────────────────────────────────────

    fun search() {
        val q = queryInput.text.trim()
        if (q.isEmpty()) return
        val filter = selectedProviders.toSet().takeIf { it.isNotEmpty() }
        val scope = when {
            filter == null -> "all extensions"
            filter.size == 1 -> providerNames[filter.first()] ?: "1 extension"
            else -> "${filter.size} extensions"
        }
        searchJob?.cancel()
        grid.children.clear()
        loading.isVisible = true
        status.text = "Searching $scope…"
        searchJob = AppShell.uiScope.launch {
            AppShell.app.repository.searchStreaming(q, 1, filter).collectLatest { items ->
                Fx.run {
                    grid.children.clear()
                    items.forEach { item ->
                        grid.children.add(posterCard(item) { AppShell.openDetail(item) })
                    }
                    status.text = "${items.size} result${if (items.size == 1) "" else "s"} · $scope"
                }
            }
            Fx.run { loading.isVisible = false }
        }
    }
}
