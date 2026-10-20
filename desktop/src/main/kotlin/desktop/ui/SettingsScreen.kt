package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderType
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

    /** Set by [AppShell.openSettingsLogs] to jump straight into the log folder. */
    var revealLogsOnShown: Boolean = false

    /** The open folder, or null for the folder list. */
    private var openFolder: String? = null

    private var pendingScrollBottom: Boolean = false

    fun onShown() {
        if (revealLogsOnShown) {
            revealLogsOnShown = false
            openFolder = F_LOGS
            pendingScrollBottom = true
        }
        val children = mutableListOf<Region>()
        val folder = openFolder
        if (folder == null) {
            crashSection()?.let { children.add(it) }
            children.add(folderList())
        } else {
            children.add(backRow(folderTitle(folder)))
            children.add(folderBody(folder))
        }
        body.children.setAll(children)
        if (pendingScrollBottom) {
            pendingScrollBottom = false
            javafx.application.Platform.runLater {
                runCatching { root.vvalue = 1.0 }
                AppShell.toast("Logs and diagnostics — save and share the .txt when reporting an issue", "ok")
            }
        }
    }

    /** Settings is folders: one row per area, tapping one goes inside it. */
    private fun folderList(): Region {
        val rows = FOLDERS.map { (id, title, sub) ->
            val go = Ui.button(title, ghost = true) { openFolder = id; onShown() }.apply {
                maxWidth = Double.MAX_VALUE
            }
            VBox(2.0,
                go,
                Theme.label(sub, size = 11.5, dim = true).apply { isWrapText = true },
            ).apply { minWidth = 0.0 }
        }
        return Ui.panel(
            Ui.sectionHeader("Settings", "Pick an area"),
            *rows.toTypedArray(),
        )
    }

    private fun backRow(title: String): Region {
        val back = Ui.button("Back", icon = Icons.CHEVRON_LEFT, ghost = true) {
            openFolder = null
            onShown()
        }
        val head = HBox(10.0, back, Theme.label(title, size = 16.0, bold = true).apply {
            isWrapText = true
        }).apply { alignment = Pos.CENTER_LEFT }
        return head
    }

    private fun folderTitle(id: String): String =
        FOLDERS.firstOrNull { it.first == id }?.second ?: "Settings"

    private fun folderBody(id: String): Region = when (id) {
        F_PLAYBACK -> playbackSection()
        F_HOME -> homeSection()
        F_APPEARANCE -> appearanceSection()
        F_BROWSER -> browserSection()
        F_UPDATES -> updatesSection()
        F_LOGS -> logsSection()
        F_DATA -> dataSection()
        else -> aboutSection()
    }

    /**
     * Playback behaviour + server scope.
     *
     * "Play straight away" (default on) picks the fastest server that answers
     * and starts it — every server found is still in the player's Source menu.
     * "Show all servers first" instead opens the server list so the user picks
     * every time; late servers keep arriving in the background either way.
     *
     * The scope toggles decide WHICH extensions a title's server sweep asks:
     * the origin's own engine is always included, the per-engine switches add
     * more families, and the master switch asks every installed extension.
     * Providers marked solo stay solo — their titles never fan out.
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
        val showAll = CheckBox("Show all servers first — let me pick one every time").apply {
            isSelected = runCatching { app.store.sweepShowAllFirst() }.getOrDefault(false)
        }
        showAll.setOnAction {
            runCatching { app.store.setSweepShowAllFirst(showAll.isSelected) }
            AppShell.toast(
                if (showAll.isSelected) "Play opens the server list first"
                else "Play starts directly and lists the rest in Sources",
                "ok",
            )
        }
        val note = Theme.label(
            "With direct play, Play tests the first few sources in parallel and starts whichever answers " +
                "fastest — no picking a server first. With the list first, you choose and late servers keep " +
                "arriving in the background. Either way every server found stays under Sources (and in the " +
                "player's Source menu) if you want to change it.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }
        val scopeNote = Theme.label(
            "Which extensions a title's server search asks. The provider you opened the title from is " +
                "always asked first and its servers stay on top; the switches below only ADD more places " +
                "to look. A stuck provider never blocks the rest — whatever answers shows immediately.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }
        val rows = mutableListOf<Region>(fastest, showAll, note, scopeNote)
        val allBox = CheckBox("Search ALL sources — every installed extension, any engine").apply {
            isSelected = runCatching { app.store.sweepAllSources() }.getOrDefault(false)
        }
        allBox.setOnAction {
            runCatching { app.store.setSweepAllSources(allBox.isSelected) }
            AppShell.toast(
                if (allBox.isSelected) "Server search covers every installed extension"
                else "Server search follows the per-engine switches",
                "ok",
            )
        }
        rows.add(allBox)
        val on = runCatching { app.store.sweepEngines() }.getOrDefault(emptySet())
        SWEEP_ENGINE_ORDER.forEach { type ->
            val box = CheckBox("Search every ${type.groupLabel} extension").apply {
                isSelected = type.name in on
            }
            box.setOnAction {
                runCatching { app.store.setSweepEngine(type.name, box.isSelected) }
                AppShell.toast(
                    (if (box.isSelected) "Also searching " else "No longer searching ") + type.groupLabel + " extensions",
                    "ok",
                )
            }
            rows.add(box)
        }
        val installed = runCatching { app.store.providers() }.getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
        if (installed.isNotEmpty()) {
            rows.add(
                Theme.label(
                    "Exceptions — stay solo, never search other extensions:",
                    size = 12.0,
                    dim = true,
                ).apply { isWrapText = true },
            )
            val solo = runCatching { app.store.sweepSoloProviders() }.getOrDefault(emptySet())
            installed.forEach { cfg ->
                val box = CheckBox("${cfg.name} (${cfg.type.groupLabel}) — only its own servers").apply {
                    isSelected = cfg.id in solo
                }
                box.setOnAction {
                    runCatching { app.store.setSweepSoloProvider(cfg.id, box.isSelected) }
                    AppShell.toast(
                        if (box.isSelected) "${cfg.name} stays solo from now on"
                        else "${cfg.name} can be rescued by other extensions again",
                        "ok",
                    )
                }
                rows.add(box)
            }
        }
        return Ui.panel(
            Ui.sectionHeader("Playback and servers", "What the Play button does and where it looks"),
            *rows.toTypedArray(),
        )
    }

    /**
     * Home catalog layout: poster (classic vertical 2:3 posters, the default)
     * or cinema (16:9 landscape cards, fed by high-quality TMDB backdrop art).
     * Applies on the next Home load.
     */
    private fun homeSection(): Region {
        val current = app.store.homeLayout()
        val seg = Ui.segmented(listOf("Cinema", "Poster"), if (current == "poster") 1 else 0) { picked ->
            app.store.setHomeLayout(if (picked == 1) "poster" else "cinema")
            AppShell.toast(
                if (picked == 1) "Home catalogs show as posters"
                else "Home catalogs show as cinema cards",
                "ok",
            )
        }
        val note = Theme.label(
            "Poster keeps the classic vertical cards. Cinema cards are 16:9 and use landscape " +
                "artwork (TMDB backdrops where available), so rows look right on a wide PC window.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }
        return Ui.panel(
            Ui.sectionHeader("Home", "How the catalog rows look"),
            labelled("Catalog layout", seg),
            note,
        )
    }

    private fun crashSection(): Region? {        val crash = HikariApp.lastCrash ?: return null
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
        // The build identity is the first thing a bug report needs, and it is the
        // only way to tell whether the exe the user is running is the one that was
        // just published — so it is one click to copy, not a treasure hunt.
        val identity = "Hikari Desktop v" + desktop.Build.VERSION +
            " (" + desktop.Build.DATE + ", " + desktop.Build.COMMIT + ")"
        var downloadUrl: String? = null
        val download = Ui.button("Download update", primary = true) {
            val u = downloadUrl ?: return@button
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u)) }
                .onFailure { AppShell.toast("Open this link: " + u, "error") }
        }
        download.isVisible = false
        download.isManaged = false
        val check = Ui.button("Check for updates", icon = Icons.REFRESH, ghost = true) {
            status.text = "Checking…"
            download.isVisible = false
            download.isManaged = false
            AppShell.uiScope.launch {
                val info = runCatching { Updater.checkForUpdate() }.getOrNull()
                Fx.run {
                    status.text = when {
                        info == null -> "Could not reach the update server."
                        !info.available -> "You are on the latest build (" + info.current + ")."
                        else -> "Update available: " + info.latest + "."
                    }
                    if (info != null && info.available) {
                        downloadUrl = info.url
                        download.text = "Download " + info.latest
                        download.isVisible = true
                        download.isManaged = true
                    }
                }
            }
        }
        val copyBuild = Ui.button("Copy build info", ghost = true) {
            runCatching {
                val content = javafx.scene.input.ClipboardContent()
                content.putString(identity)
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content)
                AppShell.toast("Build info copied", "ok")
            }
        }
        return Ui.panel(
            Ui.sectionHeader("Updates", identity),
            HBox(Theme.S3, check, copyBuild, download, status).apply { alignment = Pos.CENTER_LEFT },
        )
    }

    /**
     * Logs and diagnostics: the in-memory log buffer ([LiveLogs]) the whole
     * app writes to — catalog loads, stream lookups, installs — saved as a
     * plain .txt the user can keep or share when reporting an issue. Home's
     * Logs button jumps straight here (see [AppShell.openSettingsLogs]).
     */
    private fun logsSection(): Region {
        val count = com.hikari.app.util.LiveLogs.recentText().count { it == '\n' } + 1
        val status = Theme.label(
            "Holding the latest app activity in memory" +
                (if (count > 1) " ($count lines)" else "") +
                ". Save it right after something breaks so the reason is still in the buffer.",
            size = 11.5,
            dim = true,
        ).apply { isWrapText = true }
        val save = Ui.button("Save logs as .txt", primary = true) { saveLogs() }
        val copy = Ui.button("Copy logs", ghost = true) { copyLogs() }
        return Ui.panel(
            Ui.sectionHeader("Logs and diagnostics", "What to send when something breaks"),
            HBox(Theme.S2, save, copy).apply { alignment = Pos.CENTER_LEFT },
            status,
        )
    }

    private fun logFileText(): String {
        val identity = "Hikari Desktop v" + desktop.Build.VERSION +
            " (" + desktop.Build.DATE + ", " + desktop.Build.COMMIT + ")"
        return buildString {
            appendLine(identity)
            appendLine("Saved: " + java.time.LocalDateTime.now())
            appendLine()
            append(com.hikari.app.util.LiveLogs.recentText(10_000))
            if (isBlank()) append("(no logs captured yet)")
            appendLine()
        }.toString()
    }

    private fun saveLogs() {
        val dlg = javafx.stage.FileChooser().apply {
            title = "Save Hikari logs"
            val stamp = java.time.LocalDateTime.now().toString().replace(':', '-').take(19)
            initialFileName = "hikari-logs-$stamp.txt"
            extensionFilters.add(javafx.stage.FileChooser.ExtensionFilter("Text files", "*.txt"))
        }
        val file = runCatching { dlg.showSaveDialog(AppShell.stage) }.getOrNull() ?: return
        val ok = runCatching { file.writeText(logFileText()) }.isSuccess
        AppShell.toast(if (ok) "Logs saved: ${file.name}" else "Could not write ${file.absolutePath}", if (ok) "ok" else "error")
    }

    private fun copyLogs() {
        runCatching {
            val content = javafx.scene.input.ClipboardContent()
            content.putString(logFileText())
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content)
            AppShell.toast("Logs copied — paste them wherever you report the issue", "ok")
        }.onFailure {
            AppShell.toast("Could not copy the logs", "error")
        }
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
        const val F_PLAYBACK = "playback"
        const val F_HOME = "home"
        const val F_APPEARANCE = "appearance"
        const val F_BROWSER = "browser"
        const val F_UPDATES = "updates"
        const val F_LOGS = "logs"
        const val F_DATA = "data"
        const val F_ABOUT = "about"

        /** Folders on the Settings front page, in display order. */
        val FOLDERS = listOf(
            Triple(F_PLAYBACK, "Playback and servers", "What the Play button does and where it looks"),
            Triple(F_HOME, "Home", "How the catalog rows look"),
            Triple(F_APPEARANCE, "Appearance", "Applies instantly across the whole app"),
            Triple(F_BROWSER, "Browser", "Some sources only return playable links for a desktop browser"),
            Triple(F_UPDATES, "Updates", "Check for a newer build"),
            Triple(F_LOGS, "Logs and diagnostics", "What to send when something breaks"),
            Triple(F_DATA, "Data", "Everything is stored locally in the app data folder"),
            Triple(F_ABOUT, "About", "Version and install details"),
        )
        /** Engine families the server scope can fan out to, in display order. */
        val SWEEP_ENGINE_ORDER = listOf(
            ProviderType.CS3,
            ProviderType.HIKARI,
            ProviderType.NUVIO,
            ProviderType.SKYSTREAM,
            ProviderType.ANIYOMI,
            ProviderType.STREMIO,
            ProviderType.IPTV,
        )
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
