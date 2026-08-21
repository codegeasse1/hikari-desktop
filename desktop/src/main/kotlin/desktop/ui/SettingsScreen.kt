package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.net.Updater
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.launch

class SettingsScreenView {

    val root: VBox = VBox(16.0).apply {
        padding = Insets(18.0, 22.0, 18.0, 22.0)
    }

    private val app = HikariApp.instance

    init {
        val title = Theme.label("Settings", size = 26.0, bold = true)
        root.children.addAll(
            title,
            crashBanner(),
            themeRow(),
            uaRow(),
            updateRow(),
            historyRow(),
            aboutRow(),
        )
    }

    private fun crashBanner(): Label? {
        val crash = app.lastCrash ?: return null
        val l = Theme.label("A previous crash was recorded:\n$crash", size = 12.0, dim = true)
        l.isWrapText = true
        l.style = l.style + "; -fx-text-fill: #ff9a9a;"
        return l
    }

    private fun themeRow(): HBox {
        val dark = CheckBox("Dark theme")
        dark.isSelected = app.store.theme() != "light"
        dark.setOnAction {
            app.store.setTheme(if (dark.isSelected) "dark" else "light")
        }
        return HBox(12.0, dark).apply { alignment = Pos.CENTER_LEFT; styleClass.add("list-row") }
    }

    private fun uaRow(): VBox {
        val useDefault = CheckBox("Use default desktop UA in the embedded browser")
        useDefault.isSelected = app.store.webviewUseDefaultUa()
        val custom = TextField().apply {
            styleClass.add("field")
            promptText = "Custom user agent (optional)"
            prefWidth = 420.0
            text = app.store.webviewCustomUa()
        }
        useDefault.setOnAction {
            app.store.setWebViewUa(useDefault.isSelected, custom.text.trim())
        }
        custom.setOnAction {
            app.store.setWebViewUa(useDefault.isSelected, custom.text.trim())
        }
        val save = Button("Save").apply {
            styleClass.add("btn")
            setOnAction {
                app.store.setWebViewUa(useDefault.isSelected, custom.text.trim())
                app.webViewUseDefaultUa = useDefault.isSelected
                app.webViewCustomUa = custom.text.trim().ifBlank { null }
            }
        }
        val box = VBox(8.0, useDefault, HBox(10.0, custom, save))
        box.styleClass.add("list-row")
        return box
    }

    private fun updateRow(): HBox {
        val status = Theme.label("", size = 12.5, dim = true)
        val check = Button("Check for updates").apply {
            styleClass.add("btn")
            setOnAction {
                status.text = "Checking…"
                AppShell.uiScope.launch {
                    val info = runCatching { Updater.checkForUpdate() }.getOrNull()
                    Fx.run {
                        if (info == null) status.text = "Could not reach the update server."
                        else if (!info.available) status.text = "You're on the latest build (${info.current})."
                        else status.text = "Update available: ${info.latest} → open the GitHub release page."
                    }
                }
            }
        }
        return HBox(12.0, check, status).apply { alignment = Pos.CENTER_LEFT; styleClass.add("list-row") }
    }

    private fun historyRow(): HBox {
        val clear = Button("Clear watch history").apply {
            styleClass.add("btn", "btn-danger")
            setOnAction {
                app.store.clearHistory()
            }
        }
        return HBox(12.0, clear).apply { alignment = Pos.CENTER_LEFT; styleClass.add("list-row") }
    }

    private fun aboutRow(): Label =
        Theme.label(
            "Hikari Desktop · built from the Hikari streaming stack (Stremio addons, universal scrapers, CloudStream .cs3 plugins, Hikari extensions). " +
                "Data lives in ${app.filesDir.absolutePath}.",
            size = 12.0,
            dim = true,
        ).apply { isWrapText = true }
}
