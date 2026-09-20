package desktop.ui

import com.hikari.app.data.CatalogRow
import desktop.fx.Fx
import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: every enabled provider's catalogs as poster rails.
 *
 * Rows render the moment each catalog lands, so the page fills in progressively
 * instead of waiting on the slowest addon, and a failing catalog is skipped
 * rather than becoming fatal.
 */
class HomeScreenView {

    private val rowsBox = VBox(Theme.S5)
    private val providerBox = ComboBox<String>()
    private val statusLabel = Theme.label("", size = 12.5, dim = true)
    private val errorLabel = Theme.label("", size = 12.5).apply {
        styleClass.add("h-danger")
        isWrapText = true
    }
    private val logsLabel = Theme.label("", size = 11.5, dim = true).apply {
        styleClass.add("h-mono")
        isWrapText = true
    }
    private val logsScroll = ScrollPane(logsLabel).apply {
        prefHeight = 260.0
        isFitToWidth = true
        styleClass.add("scroll-pane")
    }
    private val logsBtn = Ui.button("Logs", icon = Icons.LIST, ghost = true) { toggleLogs() }
    private val refreshBtn = Ui.button("Refresh", icon = Icons.REFRESH, primary = true) { load(force = true) }
    private val content = VBox(Theme.S4)
    val root: ScrollPane = Ui.vScroll(content)

    private var loadJob: Job? = null
    private var gen = 0
    private var logsOpen = false

    init {
        providerBox.run {
            styleClass.add("combo-box")
            minWidth = 220.0
            setOnAction { load(force = true) }
        }
        logsScroll.isVisible = false
        logsScroll.isManaged = false
        content.children.addAll(
            Ui.sectionHeader(
                "Browse",
                "Catalogs from every enabled source",
                HBox(8.0, providerBox, refreshBtn, logsBtn).apply { alignment = Pos.CENTER_RIGHT },
            ),
            statusLabel,
            errorLabel,
            rowsBox,
            logsScroll,
        )
    }

    fun onShown() {
        load()
    }

    private fun toggleLogs() {
        logsOpen = !logsOpen
        logsScroll.isVisible = logsOpen
        logsScroll.isManaged = logsOpen
        if (logsOpen) refreshLogs()
    }

    private fun refreshLogsButton() {
        val text = com.hikari.app.util.LiveLogs.recentText()
        val lines = text.count { it == '\n' } + 1
        logsBtn.text = if (text.isBlank()) "Logs" else "Logs ($lines)"
    }

    private fun refreshLogs() {
        refreshLogsButton()
        logsLabel.text = com.hikari.app.util.LiveLogs.recentText(400)
        if (logsLabel.text.isBlank()) logsLabel.text = "(no logs captured yet — load a catalog first)"
        logsScroll.vvalue = 1.0
    }

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        val selected = providerBox.value ?: ""
        val myGen = ++gen
        loadJob = AppShell.uiScope.launch {
            try {
                Fx.run {
                    errorLabel.text = ""
                    statusLabel.text = "Loading…"
                    rowsBox.children.setAll(Ui.skeletonRail(), Ui.skeletonRail())
                }
                // The startup provider refresh runs in the background — wait for
                // it to finish once, so the first screen reflects real state.
                var attempts = 0
                while (attempts < 12 && !AppShell.app.providers.initialized.value) {
                    delay(500)
                    attempts++
                }
                val enabled = AppShell.app.store.providers().filter { it.enabled }.distinctBy { it.name }
                val providerNames = enabled.map { it.name }
                // Resolve the ACTIVE choice on the FX thread from the ComboBox's
                // LIVE value. JavaFX can deliver a popup's action event before
                // the chosen item is committed to `value`, so a value captured at
                // load() start can be stale ("All providers") and would clobber
                // the user's pick back to "All providers". Reading it here, a
                // beat later, is reliable — and a valid in-progress selection is
                // never overwritten.
                val (chosen, filterId) = Fx.runBlock {
                    val items = providerBox.items
                    val all = listOf("All providers") + providerNames
                    for (n in all) if (!items.contains(n)) items.add(n)
                    val current = providerBox.value
                    val c = when {
                        current != null && current in items -> current
                        selected in items -> selected
                        else -> "All providers"
                    }
                    providerBox.value = c
                    val cfg = enabled.firstOrNull { it.name == c }
                    c to cfg?.id?.takeIf { c != "All providers" }
                }
                val firstRow = booleanArrayOf(false)
                val rows = AppShell.app.repository.homeRows(filterId, force = force) { row ->
                    Fx.run {
                        if (myGen == gen) {
                            if (!firstRow[0]) {
                                firstRow[0] = true
                                rowsBox.children.clear()
                            }
                            runCatching { rowsBox.children.add(buildRow(row)) }
                        }
                    }
                }
                Fx.run { render(rows, filterId, chosen) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Fx.run {
                    statusLabel.text = ""
                    errorLabel.text = "Failed to load home rows: ${t.message}"
                }
            }
        }
    }

    private fun render(rows: List<CatalogRow>, filterId: String?, chosen: String? = null) {
        statusLabel.text = ""
        val statuses = AppShell.app.providers.statuses.value
        val failed = statuses.filter { !it.loaded }
        val enabledCount = statuses.size
        when {
            enabledCount == 0 -> {
                errorLabel.text = ""
                rowsBox.children.setAll(
                    Ui.emptyState(
                        Icons.EXTENSIONS,
                        "No sources yet",
                        "Add a Stremio addon, paste a universal scraper, or install a CloudStream repo from the Extensions screen.",
                        Ui.button("Open Extensions", icon = Icons.EXTENSIONS, primary = true) {
                            AppShell.show(Screen.Extensions)
                        },
                    )
                )
            }

            failed.isNotEmpty() -> {
                errorLabel.text = ""
                statusLabel.text = buildString {
                    append("${enabledCount} provider(s) enabled, ${statuses.count { it.loaded }} loaded, ${failed.size} failed to start: ")
                    append(failed.joinToString("  ·  ") { "${it.name}: ${it.error ?: "unknown error"}" })
                }
                if (rows.isEmpty()) fallback("Nothing loaded from the enabled sources.")
            }

            rows.isEmpty() -> {
                errorLabel.text = ""
                val who = chosen?.takeIf { it != "All providers" } ?: statuses.firstOrNull { it.id == filterId }?.name
                statusLabel.text = (if (who != null) "'$who' returned no catalog rows" else "No catalog rows loaded") +
                    " — open the logs for the real reason."
                fallback("No catalog rows loaded.")
            }

            else -> {
                errorLabel.text = ""
                statusLabel.text = "${rows.size} row(s) from ${statuses.count { it.loaded }} loaded source(s)"
            }
        }
        refreshLogsButton()
    }

    private fun fallback(message: String) {
        if (rowsBox.children.isNotEmpty()) return
        rowsBox.children.setAll(
            Ui.emptyState(Icons.INFO, message, "Check the logs for the underlying error, then try Refresh.")
        )
        if (!logsOpen) toggleLogs()
    }

    private fun buildRow(row: CatalogRow): Region = PosterRail.of(
        title = row.title,
        subtitle = row.providerName,
        items = row.items,
        onOpen = { AppShell.openDetail(it) },
    )
}
