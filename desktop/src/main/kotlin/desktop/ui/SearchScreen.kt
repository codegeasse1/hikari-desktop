package desktop.ui

import com.hikari.app.data.MediaItem
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

    private var searchJob: Job? = null

    init {
        val header = HBox(12.0, queryInput, searchBtn).apply { alignment = Pos.CENTER_LEFT }
        root.children.addAll(header, HBox(12.0, loading, status).apply {
            alignment = Pos.CENTER_LEFT
        }, ScrollPane(grid).apply {
            VBox.setVgrow(this, Priority.ALWAYS)
            isFitToWidth = true
            styleClass.add("scroll-pane")
        })
        loading.isVisible = false
        searchBtn.setOnAction { search() }
        queryInput.setOnAction { search() }
    }

    fun onShown() {
        if (grid.children.isEmpty()) {
            status.text = "Type a query above. Results stream in as each extension answers."
        }
    }

    fun search() {
        val q = queryInput.text.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        grid.children.clear()
        loading.isVisible = true
        status.text = "Searching…"
        searchJob = AppShell.uiScope.launch {
            AppShell.app.repository.searchStreaming(q, 1).collectLatest { items ->
                Fx.run {
                    grid.children.clear()
                    items.forEach { item ->
                        grid.children.add(posterCard(item) { AppShell.openDetail(item) })
                    }
                    status.text = "${items.size} result${if (items.size == 1) "" else "s"}"
                }
            }
            Fx.run { loading.isVisible = false }
        }
    }
}
