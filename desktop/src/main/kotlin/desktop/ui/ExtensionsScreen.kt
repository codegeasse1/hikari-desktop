package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.net.Http
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

class ExtensionsScreenView {

    val root: VBox = VBox(14.0).apply {
        padding = Insets(18.0, 22.0, 18.0, 22.0)
    }

    private val installedBox = VBox(8.0)
    private val reposBox = VBox(8.0)
    private val pluginListBox = VBox(8.0)
    private val statusLabel = Theme.label("", size = 12.5, dim = true)
    private val busy = ProgressBar(-1.0)

    private val extDir: File = File(HikariApp.instance.filesDir, "extensions").apply { mkdirs() }

    init {
        val title = Theme.label("Extensions", size = 26.0, bold = true)
        root.children.addAll(
            HBox(12.0, title, busy).apply { alignment = Pos.CENTER_LEFT },
            addStremioRow(),
            addScraperRow(),
            repoRow(),
            Section(title = "Repositories", body = reposBox),
            Section(title = "Repository plugins", body = pluginListBox),
            Section(title = "Installed providers", body = installedBox),
            installFileRow(),
            statusLabel,
        )
        busy.isVisible = false
        refresh()
    }

    private fun Section(title: String, body: VBox): VBox =
        VBox(8.0, Theme.label(title, size = 16.0, bold = true), body)

    private fun addStremioRow(): HBox {
        val input = TextField().apply {
            styleClass.add("field")
            promptText = "Stremio addon manifest URL (e.g. https://…/manifest.json)"
            prefWidth = 420.0
        }
        val btn = Button("Add addon").apply {
            styleClass.add("btn")
            setOnAction {
                val url = input.text.trim()
                if (url.isNotBlank()) {
                    val name = url.substringAfter("://").substringBefore("/")
                    AppShell.app.store.addProvider(
                        ProviderConfig(id = "stremio|$url", name = "Addon · $name", type = ProviderType.STREMIO, url = url)
                    )
                    input.clear()
                    refresh()
                }
            }
        }
        return HBox(10.0, input, btn).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun addScraperRow(): HBox {
        val input = TextField().apply {
            styleClass.add("field")
            promptText = "Universal scraper JSON config URL"
            prefWidth = 420.0
        }
        val btn = Button("Add scraper").apply {
            styleClass.add("btn")
            setOnAction {
                val url = input.text.trim()
                if (url.isNotBlank()) {
                    val name = url.substringAfter("://").substringBefore("/")
                    AppShell.app.store.addProvider(
                        ProviderConfig(id = "uni|$url", name = "Scraper · $name", type = ProviderType.UNIVERSAL, url = url)
                    )
                    input.clear()
                    refresh()
                }
            }
        }
        return HBox(10.0, input, btn).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun repoRow(): HBox {
        val input = TextField().apply {
            styleClass.add("field")
            promptText = "repo.json URL"
            prefWidth = 420.0
        }
        val add = Button("Add repo").apply {
            styleClass.add("btn")
            setOnAction {
                val url = input.text.trim()
                if (url.isNotBlank()) {
                    AppShell.app.store.addCs3Repo(
                        Cs3Repo(url = url, name = url.substringAfter("://").substringBefore("/"), kind = com.hikari.app.data.RepoKind.HIKARI)
                    )
                    input.clear()
                    refresh()
                }
            }
        }
        val load = Button("Browse").apply {
            styleClass.add("btn")
            setOnAction {
                val url = input.text.trim()
                if (url.isNotBlank()) browseRepo(url)
            }
        }
        return HBox(10.0, input, add, load).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun installFileRow(): HBox {
        val btn = Button("Install .jar extension…").apply {
            styleClass.add("btn")
            setOnAction { installFromDisk() }
        }
        val hint = Theme.label("Desktop extensions are JVM jars — the same code the Android app dexes into .hiki. Dex .hiki/.cs3 files cannot run on desktop.", size = 11.5, dim = true)
        return HBox(12.0, btn, hint).apply { alignment = Pos.CENTER_LEFT }
    }

    fun onShown() {
        refresh()
    }

    private fun refresh() {
        renderInstalled()
        renderRepos()
    }

    private fun renderInstalled() {
        installedBox.children.clear()
        val list = AppShell.app.store.providers().sortedBy { it.name }
        if (list.isEmpty()) {
            installedBox.children.add(Theme.label("No providers yet. Add an addon, repo, or extension.", dim = true))
            return
        }
        list.forEach { cfg ->
            val name = Label(cfg.name).apply {
                maxWidth = 340.0
                isWrapText = true
            }
            val type = Theme.label(cfg.type.name, size = 11.0, dim = true)
            val toggle = CheckBox("Enabled").apply { isSelected = cfg.enabled }
            toggle.setOnAction { AppShell.app.store.setEnabled(cfg.id, toggle.isSelected); refresh() }
            val remove = Button("Remove").apply {
                styleClass.add("btn", "btn-danger")
                setOnAction {
                    AppShell.app.store.removeProvider(cfg.id)
                    if (cfg.url.isNotBlank() && cfg.type in setOf(ProviderType.HIKARI, ProviderType.CS3)) {
                        runCatching { File(cfg.url).delete() }
                    }
                    AppShell.app.providers.refresh()
                    refresh()
                }
            }
            installedBox.children.add(HBox(10.0, name, type, toggle, remove).apply {
                alignment = Pos.CENTER_LEFT
                styleClass.add("list-row")
            })
        }
    }

    private fun renderRepos() {
        reposBox.children.clear()
        val repos = AppShell.app.store.repos()
        if (repos.isEmpty()) {
            reposBox.children.add(Theme.label("No repositories.", dim = true))
            return
        }
        repos.forEach { repo ->
            val name = Label(repo.name).apply { maxWidth = 300.0; isWrapText = true }
            val browse = Button("Browse").apply {
                styleClass.add("btn")
                setOnAction { browseRepo(repo.url) }
            }
            val remove = Button("Remove").apply {
                styleClass.add("btn", "btn-danger")
                setOnAction { AppShell.app.store.removeCs3Repo(repo.url); refresh() }
            }
            reposBox.children.add(HBox(10.0, name, browse, remove).apply {
                alignment = Pos.CENTER_LEFT
                styleClass.add("list-row")
            })
        }
    }

    private fun browseRepo(repoUrl: String) {
        busy.isVisible = true
        statusLabel.text = "Fetching $repoUrl…"
        pluginListBox.children.clear()
        AppShell.uiScope.launch {
            val text = Http.getString(repoUrl)
            Fx.run {
                busy.isVisible = false
                if (text == null) {
                    statusLabel.text = "Failed to fetch repo.json"
                    return@run
                }
                statusLabel.text = ""
                renderPlugins(repoUrl, text)
            }
        }
    }

    private fun renderPlugins(repoUrl: String, jsonText: String) {
        pluginListBox.children.clear()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: run {
            pluginListBox.children.add(Theme.label("Invalid repo.json", dim = true))
            return
        }
        val plugins = JSONArray(root.opt("plugins"))
        if (plugins.length() == 0) {
            pluginListBox.children.add(Theme.label("No plugins listed.", dim = true))
            return
        }
        for (i in 0 until plugins.length()) {
            val p = plugins.optJSONObject(i) ?: continue
            val name = p.optString("name")
            val url = p.optString("url")
            if (name.isBlank() || url.isBlank()) continue
            val desc = p.optString("description")
            val row = HBox(10.0).apply { alignment = Pos.CENTER_LEFT; styleClass.add("list-row") }
            val info = VBox(2.0).apply {
                children.addAll(
                    Theme.label(name, size = 14.5, bold = true),
                    Theme.label(desc.ifBlank { url }, size = 11.5, dim = true),
                )
            }
            val install = Button("Install").apply {
                styleClass.add("btn")
                setOnAction { installPlugin(name, url) }
            }
            row.children.addAll(info, install)
            pluginListBox.children.add(row)
        }
    }

    private fun installPlugin(name: String, url: String) {
        busy.isVisible = true
        statusLabel.text = "Installing $name…"
        AppShell.uiScope.launch {
            val fileName = url.substringAfterLast('/').ifBlank { "ext.jar" }
            val safeName = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ext" }
            val dest = File(extDir, "$safeName.jar")
            val ok = Http.downloadTo(url, dest)
            val (kind, err) = if (ok) resolveKind(dest) else null to "Download failed for $url"
            Fx.run {
                busy.isVisible = false
                if (kind == null) {
                    statusLabel.text = err ?: "unknown reason"
                    return@run
                }
                val cfg = when (kind) {
                    ProviderType.HIKARI -> ProviderConfig(
                        id = "hiki|$safeName",
                        name = name,
                        type = ProviderType.HIKARI,
                        url = dest.absolutePath,
                        extra = "$url|0",
                    )
                    else -> ProviderConfig(
                        id = "cs3|$safeName|0",
                        name = name,
                        type = ProviderType.CS3,
                        url = dest.absolutePath,
                    )
                }
                AppShell.app.store.addProvider(cfg)
                AppShell.app.providers.refresh()
                statusLabel.text = "Installed $name ($kind). Reloading providers…"
                renderInstalled()
            }
        }
    }

    private fun installFromDisk() {
        val chooser = FileChooser().apply {
            title = "Choose an extension jar"
            extensionFilters.add(FileChooser.ExtensionFilter("JAR files", "*.jar"))
        }
        val file = chooser.showOpenDialog(null) ?: return
        busy.isVisible = true
        statusLabel.text = "Installing ${file.name}…"
        AppShell.uiScope.launch {
            val dest = File(extDir, file.name.lowercase().replace(Regex("[^a-z0-9.]+"), "-"))
            val copied = runCatching { file.copyTo(dest, overwrite = true) }.isSuccess
            val (kind, err) = if (copied) resolveKind(dest) else null to "Could not copy ${file.name}"
            Fx.run {
                busy.isVisible = false
                if (kind == null) {
                    statusLabel.text = err ?: "unknown reason"
                    return@run
                }
                val base = file.nameWithoutExtension
                val cfg = when (kind) {
                    ProviderType.HIKARI -> ProviderConfig(
                        id = "hiki|$base",
                        name = base,
                        type = ProviderType.HIKARI,
                        url = dest.absolutePath,
                        extra = "0",
                    )
                    else -> ProviderConfig(
                        id = "cs3|$base|0",
                        name = base,
                        type = ProviderType.CS3,
                        url = dest.absolutePath,
                    )
                }
                AppShell.app.store.addProvider(cfg)
                AppShell.app.providers.refresh()
                statusLabel.text = "Installed $base ($kind)."
                renderInstalled()
            }
        }
    }

    private fun resolveKind(dest: File): Pair<ProviderType?, String?> {
        val hiki = HikariPluginManager.reload(HikariApp.instance, dest)
        if (hiki.isNotEmpty()) return ProviderType.HIKARI to null
        val hErr = HikariPluginManager.lastError
        val cs3 = Cs3PluginManager.reload(HikariApp.instance, dest)
        if (cs3.isNotEmpty()) return ProviderType.CS3 to null
        return null to (hErr ?: Cs3PluginManager.lastError ?: "not an extension")
    }
}
