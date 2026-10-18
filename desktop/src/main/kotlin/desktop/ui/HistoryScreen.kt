package desktop.ui

import com.hikari.app.data.HistoryEntry
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * History: the full watch-history list, newest first.
 *
 * CS3-parity screen for the reference client's Watch History view. Library keeps
 * its own Continue-watching tab; this screen is the complete log with clear.
 */
class HistoryScreenView {

    private val body = StackPane()
    val root: ScrollPane = Ui.vScroll(body)

    fun onShown() {
        render()
    }

    private fun render() {
        val store = AppShell.app.store
        val history = runCatching { store.history() }.getOrDefault(emptyList())
        val column = VBox(Theme.S4)
        val header = HBox(8.0,
            Ui.sectionHeader("Watch history", if (history.isEmpty()) null else history.size.toString() + " entries"),
        )
        header.alignment = Pos.CENTER_LEFT
        column.children.add(header)
        if (history.isEmpty()) {
            column.children.add(Ui.emptyState(desktop.ui.Icons.HISTORY, "Nothing watched yet", "Plays you start will land here, newest first."))
        } else {
            for (e in history) {
                column.children.add(historyRow(e))
            }
        }
        body.children.setAll(column)
    }

    private fun historyRow(e: HistoryEntry): VBox {
        val box = VBox(Theme.S1)
        box.children.add(Theme.label(e.title.ifBlank { "Untitled" }, size = 13.0, bold = true))
        box.children.add(Theme.faint(e.providerId + " - " + e.positionMs / 1000 + "s / " + e.durationMs / 1000 + "s"))
        box.styleClass.add("card")
        return box
    }
}
