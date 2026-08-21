package desktop.ui

import com.hikari.app.data.CatalogRow
import com.hikari.app.data.MediaItem
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeScreenView {

    val root: VBox = VBox(12.0).apply {
        padding = Insets(18.0, 22.0, 18.0, 22.0)
    }

    private val providerBox = ComboBox<String>()
    private val rowsBox = VBox(22.0)
    private val loading = ProgressBar(-1.0)
    private val errorLabel = Theme.label("", dim = true)
    private val statusLabel = Theme.label("", size = 12.5, dim = true)

    private var loadJob: Job? = null

    init {
        root.children.addAll(header(), loading, statusLabel, errorLabel, ScrollPane(rowsBox).apply {
            VBox.setVgrow(this, Priority.ALWAYS)
            isFitToWidth = true
            styleClass.add("scroll-pane")
        })
    }

    private fun header(): HBox {
        val title = Theme.label("Browse", size = 26.0, bold = true).apply {
            cursor = javafx.scene.Cursor.HAND
            Tooltip.install(this, Tooltip("Click to reload"))
            setOnMouseClicked { load() }
        }
        providerBox.run {
            minWidth = 220.0
            setOnAction { load() }
        }
        val refresh = Button("Refresh").apply { styleClass.add("btn"); setOnAction { load() } }
        return HBox(14.0, title, providerBox, refresh).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    fun onShown() {
        load()
    }

    fun load() {
        loadJob?.cancel()
        val selected = providerBox.value ?: ""
        loadJob = AppShell.uiScope.launch {
            try {
                Fx.run {
                    loading.isVisible = true
                    errorLabel.text = ""
                    statusLabel.text = "Loading…"
                    rowsBox.children.clear()
                }
                // The startup provider refresh runs in the background — wait for
                // it to finish once, so the first screen reflects real state.
                var attempts = 0
                while (attempts < 12 && !AppShell.app.providers.initialized.value) {
                    delay(500)
                    attempts++
                }
                val enabled = AppShell.app.store.providers().filter { it.enabled }
                val providerNames = enabled.map { it.name }
                Fx.run {
                    val all = listOf("All providers") + providerNames
                    providerBox.items.setAll(all)
                    providerBox.value = selected.takeIf { it in all } ?: "All providers"
                }
                // The dropdown shows provider NAMES, but the repository matches
                // providers by ID — resolve the selected name to its id so
                // choosing one actually filters the rows.
                val selectedCfg = enabled.firstOrNull { it.name == selected }
                val filterId = selectedCfg?.id?.takeIf { selected != "All providers" }
                val rows = AppShell.app.repository.homeRows(filterId)
                Fx.run {
                    loading.isVisible = false
                    render(rows, filterId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Fx.run {
                    loading.isVisible = false
                    statusLabel.text = ""
                    errorLabel.text = "Failed to load home rows: ${t.message}"
                }
            }
        }
    }

    private fun render(rows: List<CatalogRow>, filterId: String?) {
        rowsBox.children.clear()
        statusLabel.text = ""
        val statuses = AppShell.app.providers.statuses.value
        val failed = statuses.filter { !it.loaded }
        val enabledCount = statuses.size
        if (enabledCount == 0) {
            errorLabel.text = ""
            statusLabel.text = "No providers installed or enabled yet. Open the Extensions tab to add one — or add a Stremio addon."
        } else if (failed.isNotEmpty()) {
            errorLabel.text = ""
            statusLabel.text = buildString {
                append("${enabledCount} provider(s) enabled, ${statuses.count { it.loaded }} loaded, ${failed.size} failed to start: ")
                append(failed.joinToString(" | ") { "${it.name}: ${it.error ?: "unknown error"}" })
            }
        } else if (rows.isEmpty()) {
            errorLabel.text = ""
            val who = statuses.firstOrNull { it.id == filterId }?.name
            statusLabel.text = (if (who != null) "'$who' returned no catalog rows" else "No catalog rows loaded") +
                " — the addon may be offline, or block this network."
        } else {
            errorLabel.text = ""
        }
        if (rows.isEmpty()) {
            rowsBox.children.add(Theme.label("Nothing loaded yet — add extensions or a Stremio addon.", dim = true))
            return
        }
        rows.forEach { row ->
            val head = HBox(10.0).apply {
                children.addAll(
                    Theme.label(row.title, size = 17.0, bold = true),
                    Theme.label(row.providerName, size = 12.5, dim = true),
                )
                alignment = Pos.BASELINE_LEFT
            }
            val cardRow = HBox(14.0).apply { alignment = Pos.CENTER_LEFT }
            row.items.forEach { item ->
                cardRow.children.add(posterCard(item) {
                    AppShell.openDetail(item)
                })
            }
            val scroller = ScrollPane(cardRow).apply {
                isFitToHeight = true
                hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                styleClass.add("scroll-pane")
            }
            rowsBox.children.add(VBox(10.0, head, scroller))
        }
    }
}

fun posterCard(media: MediaItem, onClick: () -> Unit): VBox {
    val img = javafx.scene.image.ImageView().apply {
        fitWidth = 150.0
        fitHeight = 205.0
        isPreserveRatio = true
        styleClass.add("poster-img")
    }
    desktop.img.ImageLoader.loadAsync(media.posterUrl) { fx -> img.image = fx }
    val title = Label(media.title).apply {
        styleClass.add("poster-title")
        isWrapText = true
        maxWidth = 150.0
        prefWidth = 150.0
        minHeight = 36.0
    }
    val box = VBox(8.0, img, title).apply {
        cursor = javafx.scene.Cursor.HAND
        Tooltip.install(this, Tooltip(media.title))
        onMouseClicked = { onClick() }
    }
    return box
}
