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
        val headerRow = HBox(8.0,
            Ui.sectionHeader("Watch history", if (history.isEmpty()) null else history.size.toString() + " entries"),
        )
        headerRow.alignment = Pos.CENTER_LEFT
        // Clear-all sits in the header so it is always one tap away; two taps
        // to confirm (re-arms after 3s) so a stray click never wipes 102 plays.
        if (history.isNotEmpty()) {
            val spacer = HBox().apply { HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }
            val clearBtn = javafx.scene.control.Button("Clear all").apply {
                styleClass.add("btn-ghost")
                isFocusTraversable = false
            }
            var armed = false
            val disarm = javafx.animation.PauseTransition(javafx.util.Duration.seconds(3.0))
            disarm.setOnFinished { armed = false; clearBtn.text = "Clear all" }
            clearBtn.setOnAction {
                if (!armed) {
                    armed = true
                    clearBtn.text = "Tap again to confirm"
                    disarm.playFromStart()
                } else {
                    disarm.stop()
                    runCatching { store.clearHistory() }
                    render()
                }
            }
            headerRow.children.addAll(spacer, clearBtn)
        }
        column.children.add(headerRow)
        if (history.isEmpty()) {
            column.children.add(Ui.emptyState(desktop.ui.Icons.HISTORY, "Nothing watched yet", "Plays you start will land here, newest first."))
        } else {
            for (e in history) {
                column.children.add(historyRow(e))
            }
        }
        body.children.setAll(column)
    }

    private fun historyRow(e: HistoryEntry): HBox {
        val store = AppShell.app.store
        val text = VBox(Theme.S1,
            Theme.label(e.title.ifBlank { "Untitled" }, size = 13.0, bold = true),
            Theme.faint(e.providerId + " - " + e.positionMs / 1000 + "s / " + e.durationMs / 1000 + "s"),
        ).apply { HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }
        val remove = Ui.iconButton(desktop.ui.Icons.CLOSE, "Remove", size = 16.0) {
            runCatching { store.removeHistory(e.uniqueKey) }
            render()
        }
        return HBox(Theme.S2, text, remove).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("card")
        }
    }
}
