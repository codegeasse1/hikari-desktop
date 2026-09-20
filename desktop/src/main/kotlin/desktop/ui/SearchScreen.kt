package desktop.ui

import desktop.fx.Fx
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Search across every installed provider.
 *
 * The provider choice lives in a slide-in drawer instead of a wall of chips:
 * the screen only ever shows the *current* scope ("All 43 extensions" or the
 * handful you picked) and the drawer holds the full, scrollable, searchable,
 * multi-select list. Results stream in as each extension answers.
 */
class SearchScreenView {

    private val queryInput: TextField = Ui.field("Search movies, shows, anime…").apply {
        prefHeight = 46.0
        style = "-fx-font-size: 14px;"
        HBox.setHgrow(this, Priority.ALWAYS)
    }
    private val searchButton = Ui.button("Search", primary = true).apply {
        prefHeight = 46.0
        minWidth = 108.0
        style = "-fx-font-size: 14px;"
    }
    private val loading = javafx.scene.control.ProgressIndicator().apply {
        styleClass.add("spinner")
        prefWidth = 16.0
        prefHeight = 16.0
        maxWidth = 16.0
        maxHeight = 16.0
        isVisible = false
    }
    private val status = themed("", "tiny")

    // ── scope bar ───────────────────────────────────────────────────────────

    private val scopeTitle = themed("", "scope-title")
    private val scopeSub = themed("", "scope-sub")
    private val scopeChips = HBox(7.0).apply {
        alignment = Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }
    private val scopeButton = HBox(
        11.0,
        Icons.of(Icons.EXTENSIONS, 16.0),
        VBox(1.0, scopeTitle, scopeSub),
        Icons.of(Icons.CHEVRON_DOWN, 13.0),
    ).apply {
        styleClass.add("scope-btn")
        alignment = Pos.CENTER_LEFT
        setOnMouseClicked { openDrawer() }
    }
    private val scopeBar = HBox(10.0, scopeButton, scopeChips).apply { alignment = Pos.CENTER_LEFT }

    // ── results ─────────────────────────────────────────────────────────────

    private val grid = PosterGrid(Theme.POSTER_W, onOpen = { AppShell.openDetail(it) })

    // ── drawer ──────────────────────────────────────────────────────────────

    private data class Entry(val id: String, val name: String, val type: String)

    private var entries: List<Entry> = emptyList()
    private val selected = LinkedHashSet<String>()

    private val drawerList = VBox(2.0)
    private val drawerFilter = Ui.field("Find extension…").apply {
        textProperty().addListener { _, _, _ -> renderDrawerRows() }
    }
    private val drawerCount = themed("", "tiny")
    private val drawerScrim = Region().apply {
        styleClass.add("drawer-scrim")
        isVisible = false
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }
    private val drawer = VBox().apply {
        styleClass.add("drawer")
        prefWidth = PANEL_W
        minWidth = PANEL_W
        maxWidth = PANEL_W
        maxHeight = Double.MAX_VALUE
        isVisible = false
        translateX = PANEL_W
    }

    val root: StackPane = StackPane()

    private var searchJob: Job? = null

    init {
        val content = VBox(0.0).apply {
            padding = Insets(Theme.S5, Theme.S5, Theme.S4, Theme.S5)
            children.addAll(
                HBox(10.0, queryInput, searchButton).apply { alignment = Pos.CENTER_LEFT },
                VBox(12.0, scopeBar, HBox(10.0, loading, status).apply { alignment = Pos.CENTER_LEFT }).apply {
                    padding = Insets(14.0, 0.0, 12.0, 0.0)
                },
                grid.root,
            )
            VBox.setVgrow(grid.root, Priority.ALWAYS)
        }
        buildDrawer()
        root.children.addAll(content, drawerScrim, drawer)
        StackPane.setAlignment(drawer, Pos.TOP_RIGHT)
        drawerScrim.setOnMouseClicked { closeDrawer() }
        searchButton.setOnAction { search() }
        queryInput.setOnAction { search() }
        refreshScope()
        status.text = "Type a query above. Results stream in as each extension answers."
    }

    fun onShown() {
        entries = runCatching {
            AppShell.app.store.providers()
                .filter { it.enabled }
                .map { Entry(it.id, it.name.ifBlank { it.type.name }, it.type.name) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
        selected.retainAll(entries.map { it.id }.toSet())
        renderDrawerRows()
        refreshScope()
    }

    /** Entry point for the global search box in the top bar. */
    fun searchWith(query: String) {
        queryInput.text = query
        search()
    }

    // ── drawer ──────────────────────────────────────────────────────────────

    private fun buildDrawer() {
        val head = VBox(3.0,
            Label("Search in").apply { styleClass.add("watch-title") },
            themed("Pick any number of extensions. Nothing selected means all of them.", "wrap-hint"),
        ).apply { styleClass.add("drawer-head") }

        val filterRow = HBox(drawerFilter).apply {
            padding = Insets(12.0, 16.0, 10.0, 16.0)
            drawerFilter.maxWidth = Double.MAX_VALUE
            HBox.setHgrow(drawerFilter, Priority.ALWAYS)
        }
        val listScroll = ScrollPane(drawerList).apply {
            isFitToWidth = true
            styleClass.add("scroll-pane")
            VBox.setVgrow(this, Priority.ALWAYS)
            padding = Insets(0.0, 10.0, 0.0, 10.0)
        }
        val foot = HBox(8.0,
            drawerCount,
            Ui.spacer(),
            Ui.button("Clear", ghost = true) { clearSelection() },
            Ui.button("Apply", primary = true) { applySelection() },
        ).apply {
            styleClass.add("drawer-foot")
            alignment = Pos.CENTER_LEFT
        }
        drawer.children.addAll(head, filterRow, listScroll, foot)
        VBox.setVgrow(listScroll, Priority.ALWAYS)
    }

    private fun openDrawer() {
        renderDrawerRows()
        drawer.isVisible = true
        drawerScrim.isVisible = true
        drawerScrim.opacity = 0.0
        DrawerFx.slide(drawer, PANEL_W, open = true)
        Ui.fade(drawerScrim, 1.0, 180.0)
    }

    private fun closeDrawer() {
        DrawerFx.slide(drawer, PANEL_W, open = false) { drawer.isVisible = false }
        Timeline(
            KeyFrame(Duration.millis(160.0), KeyValue(drawerScrim.opacityProperty(), 0.0, Interpolator.EASE_BOTH)),
        ).apply {
            onFinished = { drawerScrim.isVisible = false }
            play()
        }
    }

    private fun applySelection() {
        closeDrawer()
        if (queryInput.text.isNotBlank()) search()
    }

    private fun clearSelection() {
        selected.clear()
        renderDrawerRows()
        refreshScope()
    }

    private fun renderDrawerRows() {
        val needle = drawerFilter.text.trim().lowercase()
        val all = Entry("", "All extensions", "")
        val filtered = entries.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
        val rows = mutableListOf<Node>()
        if (needle.isEmpty()) rows.add(providerRow(all, selected.isEmpty()))
        filtered.forEach { rows.add(providerRow(it, it.id in selected)) }
        if (rows.isEmpty()) rows.add(themed("No extension matches “$needle”.", "tiny").apply { padding = Insets(10.0, 10.0, 10.0, 10.0) })
        drawerList.children.setAll(rows)
        drawerCount.text = if (selected.isEmpty()) "All ${entries.size} selected" else "${selected.size} selected"
    }

    private fun providerRow(entry: Entry, isSelected: Boolean): HBox {
        val tick = Region().apply {
            styleClass.add("prow-tick")
            isVisible = isSelected
        }
        val cb = StackPane(tick).apply {
            styleClass.add("prow-cb")
            if (isSelected) styleClass.add("prow-cb-sel")
        }
        val name = themed(entry.name, if (isSelected) "prow-name-sel" else "prow-name")
        val row = HBox(11.0, cb, name).apply {
            styleClass.add("prow")
            if (isSelected) styleClass.add("prow-sel")
            alignment = Pos.CENTER_LEFT
            if (entry.type.isNotBlank()) {
                children.add(Ui.badge(entry.type, "badge"))
            }
            HBox.setHgrow(name, Priority.ALWAYS)
            setOnMouseClicked {
                if (entry.id.isEmpty()) {
                    selected.clear()
                } else if (!selected.remove(entry.id)) {
                    selected.add(entry.id)
                }
                renderDrawerRows()
                refreshScope()
            }
        }
        return row
    }

    // ── scope ───────────────────────────────────────────────────────────────

    private fun refreshScope() {
        val total = entries.size
        scopeTitle.text = when {
            total == 0 -> "No extensions installed"
            selected.isEmpty() -> "All extensions · $total"
            else -> "${selected.size} of $total extensions"
        }
        val names = entries.filter { it.id in selected }.map { it.name }
        scopeSub.text = when {
            total == 0 -> "Add one from the Extensions screen"
            selected.isEmpty() -> "Every installed provider answers"
            names.isEmpty() -> "Every installed provider answers"
            else -> names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3} more" else ""
        }
        val chips = mutableListOf<Node>()
        entries.filter { it.id in selected }.take(4).forEach { entry ->
            chips.add(chipButton("${entry.name}  ✕", "selchip") {
                selected.remove(entry.id)
                renderDrawerRows()
                refreshScope()
            })
        }
        if (names.size > 4) {
            chips.add(chipButton("+${names.size - 4} more", "pill") { openDrawer() })
        }
        if (names.isNotEmpty()) {
            chips.add(chipButton("Clear", "pill") { clearSelection() })
        }
        scopeChips.children.setAll(chips)
        scopeChips.isVisible = chips.isNotEmpty()
        scopeChips.isManaged = chips.isNotEmpty()
    }

    private fun chipButton(text: String, cls: String, onClick: () -> Unit): javafx.scene.control.Button =
        javafx.scene.control.Button(text).apply {
            styleClass.add(cls)
            isFocusTraversable = false
            setOnAction { onClick() }
        }

    // ── search ──────────────────────────────────────────────────────────────

    fun search() {
        val query = queryInput.text.trim()
        if (query.isEmpty()) return
        val filter = selected.toSet().takeIf { it.isNotEmpty() }
        val scope = when {
            filter == null -> "all ${entries.size} extensions"
            filter.size == 1 -> entries.firstOrNull { it.id == filter.first() }?.name ?: "1 extension"
            else -> "${filter.size} extensions"
        }
        searchJob?.cancel()
        grid.clear()
        loading.isVisible = true
        status.text = "Searching $scope…"
        searchJob = AppShell.uiScope.launch {
            AppShell.app.repository.searchStreaming(query, 1, filter).collectLatest { items ->
                Fx.run {
                    grid.setItems(items)
                    status.text = "${items.size} result${if (items.size == 1) "" else "s"} · $scope"
                }
            }
            Fx.run { loading.isVisible = false }
        }
    }

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    private companion object {
        const val PANEL_W = 388.0
    }
}

/** Slide in/out helper shared by the filter drawer. */
private object DrawerFx {
    fun slide(node: Node, width: Double, open: Boolean, onDone: (() -> Unit)? = null) {
        val timeline = Timeline(
            KeyFrame(
                Duration.millis(210.0),
                KeyValue(node.translateXProperty(), if (open) 0.0 else width, Interpolator.EASE_BOTH),
            )
        )
        if (onDone != null) timeline.onFinished = { onDone() }
        timeline.play()
    }
}
