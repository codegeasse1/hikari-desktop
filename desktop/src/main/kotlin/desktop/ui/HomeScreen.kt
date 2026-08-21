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

    private var loadJob: Job? = null

    init {
        root.children.addAll(header(), loading, errorLabel, ScrollPane(rowsBox).apply {
            VBox.setVgrow(this, Priority.ALWAYS)
            isFitToWidth = true
            styleClass.add("scroll-pane")
        })
    }

    private fun header(): HBox {
        val title = Theme.label("Browse", size = 26.0, bold = true)
        providerBox.run {
            minWidth = 220.0
            styleClass.add("field")
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
            Fx.run {
                loading.isVisible = true
                errorLabel.text = ""
                rowsBox.children.clear()
            }
            // Providers refresh asynchronously at startup — wait for them
            // before the first row load, so the very first screen isn't empty.
            var attempts = 0
            while (attempts < 10 && AppShell.app.providers.providers.value.isEmpty()) {
                delay(1000)
                attempts++
            }
            val providerNames = AppShell.app.store.providers().filter { it.enabled }.map { it.name }
            Fx.run {
                val all = listOf("All providers") + providerNames
                providerBox.items.setAll(all)
                providerBox.value = selected.takeIf { it in all } ?: "All providers"
            }
            val filter = selected.takeIf { it != "All providers" }
            val rows = runCatching {
                AppShell.app.repository.homeRows(filter)
            }.getOrElse { t ->
                Fx.run { errorLabel.text = "Failed to load home rows: ${t.message}" }
                emptyList()
            }
            Fx.run {
                loading.isVisible = false
                render(rows)
            }
        }
    }

    private fun render(rows: List<CatalogRow>) {
        rowsBox.children.clear()
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
