package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.net.Updater
import com.hikari.app.ui.theme.HikariThemeMode
import desktop.fx.Fx
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Circle
import kotlinx.coroutines.launch

/**
 * Settings: appearance, browser identity, updates, and data.
 *
 * The appearance section drives [Theme] directly, so switching mode or accent
 * re-skins the live window with no restart.
 */
class SettingsScreenView {

    private val body = VBox(Theme.S5)
    val root: ScrollPane = Ui.vScroll(body)

    private val app = HikariApp.instance

    fun onShown() {
        val children = mutableListOf<Region>(
            playbackSection(),
            appearanceSection(),
            browserSection(),
            updatesSection(),
            dataSection(),
            aboutSection(),
        )
        crashSection()?.let { children.add(1, it) }
        body.children.setAll(children)
    }

    /**
     * Playback behaviour. The one setting here is the answer to "why do I have
     * to choose a server before anything plays?": with it on (the default),
     * Play picks the fastest server that answers and starts it immediately —
     * every server the title offered is still in the player's Source menu, so
     * switching to another one is a single click from inside playback.
     */
    private fun playbackSection(): Region {
        val fastest = CheckBox("Play straight away — pick the fastest working server for me").apply {
            isSelected = app.store.playFastest()
        }
        fastest.setOnAction {
            app.store.setPlayFastest(fastest.isSelected)
            AppShell.toast(
                if (fastest.isSelected) "Play now races the servers and starts the fastest"
                else "Play uses the provider's own order",
                "ok",
            )
        }
        val note = Theme.label(
            "With this on, Play tests the first few sources in parallel and starts whichever answers " +
                "fastest — no picking a server first. Servers that are blocked or slow for your network " +
                "lose the race, not your patience. Everything found is still listed under Sources (and in " +
                "the player's Source menu) if you want to change it.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }
        return Ui.panel(
            Ui.sectionHeader("Playback", "What the Play button does"),
            fastest,
            note,
        )
    }

    private fun crashSection(): Region? {
        val crash = HikariApp.lastCrash ?: return null
        return Ui.panel(
            Ui.sectionHeader("Last crash", "Recorded from the previous session"),
            Theme.label(crash, size = 11.5).apply {
                styleClass.add("h-mono")
                isWrapText = true
            },
        )
    }

    private fun appearanceSection(): Region {
        val modeRow = HBox(Theme.S2).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                modeButton("Dark", HikariThemeMode.DARK),
                modeButton("Light", HikariThemeMode.LIGHT),
            )
        }

        val swatches = HBox(Theme.S2).apply { alignment = Pos.CENTER_LEFT }
        ACCENT_SWATCHES.forEach { (key, hex) ->
            val dot = Circle(8.0).apply { style = "-fx-fill: $hex;" }
            val ring = StackPane(dot).apply {
                styleClass.add("accent-swatch")
                if (Theme.accent == key) styleClass.add("accent-swatch-selected")
                prefWidth = 34.0
                prefHeight = 34.0
                minWidth = 34.0
                minHeight = 34.0
                maxWidth = 34.0
                maxHeight = 34.0
                cursor = javafx.scene.Cursor.HAND
                setOnMouseClicked {
                    Theme.setAccent(key)
                    onShown()
                }
                javafx.scene.control.Tooltip.install(this, Ui.tooltip(key.replaceFirstChar { it.uppercase() }))
            }
            swatches.children.add(ring)
        }

        return Ui.panel(
            Ui.sectionHeader("Appearance", "Applies instantly across the whole app"),
            labelled("Theme", modeRow),
            labelled("Accent", swatches),
        )
    }

    private fun modeButton(text: String, mode: HikariThemeMode): Region =
        Ui.chip(text, Theme.mode == mode) {
            Theme.setMode(mode)
            onShown()
        }

    private fun labelled(text: String, control: Region): Region = HBox(Theme.S4,
        Theme.label(text, size = 13.0).apply { minWidth = 90.0; styleClass.add("h-dim") },
        control,
    ).apply { alignment = Pos.CENTER_LEFT }

    private fun browserSection(): Region {
        val useDefault = CheckBox("Use the default desktop user agent in the embedded browser").apply {
            isSelected = app.store.webviewUseDefaultUa()
        }
        val custom = Ui.field("Custom user agent (optional)", width = 460.0).apply {
            text = app.store.webviewCustomUa()
        }
        fun save() {
            app.store.setWebViewUa(useDefault.isSelected, custom.text.trim())
            app.webViewUseDefaultUa = useDefault.isSelected
            app.webViewCustomUa = custom.text.trim().ifBlank { null }
            AppShell.toast("Browser settings saved", "ok")
        }
        useDefault.setOnAction { save() }
        custom.setOnAction { save() }

        return Ui.panel(
            Ui.sectionHeader("Browser", "Some sources only return playable links for a desktop browser"),
            useDefault,
            HBox(Theme.S2, custom, Ui.button("Save", primary = true) { save() }).apply { alignment = Pos.CENTER_LEFT },
        )
    }

    private fun updatesSection(): Region {
        val status = Theme.label("", size = 12.5, dim = true).apply { isWrapText = true }
        val check = Ui.button("Check for updates", icon = Icons.REFRESH, ghost = true) {
            status.text = "Checking…"
            AppShell.uiScope.launch {
                val info = runCatching { Updater.checkForUpdate() }.getOrNull()
                Fx.run {
                    status.text = when {
                        info == null -> "Could not reach the update server."
                        !info.available -> "You're on the latest build (${info.current})."
                        else -> "Update available: ${info.latest} — grab it from the GitHub release page."
                    }
                }
            }
        }
        return Ui.panel(
            Ui.sectionHeader("Updates", "Hikari Desktop v${desktop.Build.VERSION} (${desktop.Build.DATE})"),
            HBox(Theme.S3, check, status).apply { alignment = Pos.CENTER_LEFT },
        )
    }

    private fun dataSection(): Region {
        val clearHistory = Ui.button("Clear watch history", icon = Icons.TRASH, ghost = true) {
            app.store.clearHistory()
            AppShell.toast("Watch history cleared", "ok")
        }
        val openFolder = Ui.button("Open data folder", icon = Icons.FOLDER, ghost = true) {
            val dir = app.filesDir
            val ok = runCatching { java.awt.Desktop.getDesktop().open(dir) }.isSuccess
            if (!ok) AppShell.toast("Data folder: ${dir.absolutePath}", "error")
        }
        val reset = Ui.button("Reset all data", icon = Icons.WARNING, danger = true) {
            app.store.reset()
            AppShell.uiScope.launch { app.providers.refresh() }
            AppShell.toast("Cleared. Default providers are re-added on next launch.")
            onShown()
        }
        val note = Theme.label(
            "Resetting removes providers, repositories, installed extensions and history from this machine.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }

        return Ui.panel(
            Ui.sectionHeader("Data", "Everything is stored locally in the app data folder"),
            HBox(Theme.S2, clearHistory, openFolder, reset).apply { alignment = Pos.CENTER_LEFT },
            note,
        )
    }

    private fun aboutSection(): Region = Ui.panel(
        Ui.sectionHeader("About"),
        Theme.label(
            "Hikari Desktop — one player for every ecosystem: Stremio addons, universal scrapers, " +
                "CloudStream .cs3 plugins and Hikari extensions.",
            size = 12.5,
            dim = true,
        ).apply { isWrapText = true },
        Theme.label(app.filesDir.absolutePath, size = 11.0, dim = true).apply {
            styleClass.add("h-mono")
            isWrapText = true
        },
    )

    private companion object {
        val ACCENT_SWATCHES = listOf(
            "violet" to "#a970ff",
            "blue" to "#4d8dff",
            "cyan" to "#2fd4e0",
            "emerald" to "#34d399",
            "amber" to "#f5a524",
            "rose" to "#ff5d8f",
        )
    }
}
