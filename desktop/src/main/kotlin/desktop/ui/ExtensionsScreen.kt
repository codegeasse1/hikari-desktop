package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.net.Http
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

class ExtensionsScreenView {

    private val content = VBox(14.0).apply {
        padding = Insets(18.0, 22.0, 18.0, 22.0)
    }
    val root: ScrollPane = ScrollPane(content).apply {
        isFitToWidth = true
        styleClass.add("scroll-pane")
    }

    private val reposBox = VBox(8.0)
    private val installedBox = VBox(8.0)
    private val statusLabel = Theme.label("", size = 12.5, dim = true)
    private val busy = ProgressBar(-1.0)

    private val extDir: File = File(HikariApp.instance.filesDir, "extensions").apply { mkdirs() }

    /** Fetched repo contents, cached per stored repo URL so expanding a folder
     *  doesn't hit the network again. */
    private data class RepoData(
        val url: String,
        val name: String,
        val description: String,
        val plugins: List<Pair<String, String>>,
    )

    private val repoData = HashMap<String, RepoData>()
    private val expanded = HashSet<String>()

    private fun setStatus(text: String, isError: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = "-fx-text-fill: ${if (isError) "#ff8f8f" else Theme.FG_DIM};"
    }

    init {
        val title = Theme.label("Extensions", size = 26.0, bold = true)
        content.children.addAll(
            HBox(12.0, title, busy).apply { alignment = Pos.CENTER_LEFT },
            addOptionsGrid(),
            Section("Extension repos", reposBox),
            Section("Installed extensions", installedBox),
            statusLabel,
        )
        busy.isVisible = false
        refresh()
    }

    private fun Section(title: String, body: Node): VBox =
        VBox(8.0, Theme.label(title, size = 16.0, bold = true), body)

    // ── Add-sources grid (Android-style option list) ────────────────────────

    private fun addOptionsGrid(): VBox {
        fun option(text: String, primary: Boolean, input: HBox): VBox {
            val inputRow = input.apply { isVisible = false; isManaged = false }
            val btn = Button(text).apply {
                styleClass.addAll("btn", if (primary) "btn-primary" else "")
                maxWidth = Double.MAX_VALUE
                setOnAction {
                    inputRow.isVisible = !inputRow.isVisible
                    inputRow.isManaged = inputRow.isVisible
                    if (inputRow.isVisible) {
                        inputRow.lookupAll(".field").firstOrNull()?.let { it.requestFocus() }
                    }
                }
            }
            return VBox(8.0, btn, inputRow).apply { alignment = Pos.CENTER_LEFT }
        }
        fun col(vararg opts: VBox) = VBox(8.0, *opts).apply { prefWidth = 430.0 }
        val grid = HBox(16.0, col(
            option("🧩 Add Hikari repo", true, hikariRepoInput()),
            option("Add CloudStream repo", true, csRepoInput()),
            option("Add Stremio addon", true, stremioInput()),
            option("Add scraper", true, scraperInput()),
            option("Install .hiki from URL", false, hikiUrlInput()),
        ), col(
            option("Pick .hiki file", false, fileRow("Pick .hiki file…") { installFromDisk() }),
            option("Install .cs3 from URL", false, cs3UrlInput()),
            option("Pick .cs3 file", false, fileRow("Pick .cs3 file…") { installFromDisk() }),
            option("Install .jar from URL", false, jarUrlInput()),
            option("Pick .jar file", false, fileRow("Pick .jar file…") { installFromDisk() }),
        ))
        val hint = Theme.label(
            "Desktop extensions are JVM .jar files — the same code the Android app dexes into .hiki. " +
                "Dex .hiki/.cs3 archives can't run on a PC, so the app auto-matches .hiki/.cs3 names to their .jar build. " +
                "The “Install .jar” options are just the direct form of that.",
            size = 11.5, dim = true,
        ).apply { isWrapText = true }
        return VBox(8.0, grid, hint)
    }

    private fun urlInput(prompt: String, buttonText: String, initial: String = "", onGo: (String) -> Unit): HBox {
        val input = TextField().apply {
            styleClass.add("field")
            promptText = prompt
            prefWidth = 330.0
            text = initial
        }
        val btn = Button(buttonText).apply {
            styleClass.addAll("btn", "btn-primary")
            setOnAction { onGo(input.text) }
        }
        return HBox(8.0, input, btn).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun fileRow(label: String, onPick: () -> Unit): HBox =
        HBox(Button(label).apply { styleClass.add("btn"); setOnAction { onPick() } })
            .apply { alignment = Pos.CENTER_LEFT }

    private fun hikariRepoInput(): HBox = urlInput(
        "Hikari repo URL",
        "Add",
        initial = "https://github.com/codegeasse1/hikari-extensions",
    ) { addRepo(Http.normalizeUrl(it)) }

    private fun csRepoInput(): HBox = urlInput("repo.json URL (CloudStream)", "Add repo") { addRepo(Http.normalizeUrl(it)) }

    private fun stremioInput(): HBox = urlInput("Stremio addon manifest URL (e.g. https://…/manifest.json)", "Add addon") {
        val url = Http.normalizeUrl(it)
        if (url.contains("github.com") || url.contains("raw.githubusercontent.com")) {
            setStatus("That looks like a GitHub repo URL, not a Stremio addon manifest.", isError = true)
            return@urlInput
        }
        if (url.isNotBlank()) {
            val name = url.substringAfter("://").substringBefore("/")
            AppShell.app.store.addProvider(
                ProviderConfig(id = "stremio|$url", name = "Addon · $name", type = ProviderType.STREMIO, url = url)
            )
            refresh()
            setStatus("Stremio addon added: $name")
        }
    }

    private fun scraperInput(): HBox = urlInput("Universal scraper JSON config URL", "Add scraper") {
        val url = Http.normalizeUrl(it)
        if (url.isNotBlank()) {
            val name = url.substringAfter("://").substringBefore("/")
            AppShell.app.store.addProvider(
                ProviderConfig(id = "uni|$url", name = "Scraper · $name", type = ProviderType.UNIVERSAL, url = url)
            )
            refresh()
            setStatus("Scraper added: $name")
        }
    }

    private fun hikiUrlInput(): HBox = urlInput("Direct .hiki URL (installs its .jar)", "Install") { installFromUrl(it) }
    private fun cs3UrlInput(): HBox = urlInput("Direct .cs3 URL (installs its .jar)", "Install") { installFromUrl(it) }
    private fun jarUrlInput(): HBox = urlInput("Direct .jar URL (GitHub raw / jsDelivr / Drive)", "Install") { installFromUrl(it) }

    // ── Repos as expandable folders ─────────────────────────────────────────

    private fun repoDisplayName(repo: Cs3Repo): String {
        val cached = repoData[repo.url]
        if (cached != null && cached.name.isNotBlank()) return cached.name
        return Http.repoDisplayName(repo.url).takeIf { it.isNotBlank() } ?: repo.name
    }

    private fun renderRepos() {
        reposBox.children.clear()
        val repos = AppShell.app.store.repos()
        if (repos.isEmpty()) {
            reposBox.children.add(Theme.label("No repos added yet — use “Add Hikari repo” or “Add CloudStream repo” above.", dim = true))
            return
        }
        repos.forEach { repo -> reposBox.children.add(repoFolder(repo)) }
    }

    private fun repoFolder(repo: Cs3Repo): VBox {
        val open = repo.url in expanded
        val data = repoData[repo.url]

        val folderBtn = Button((if (open) "▼  " else "▶  ") + repoDisplayName(repo)).apply {
            styleClass.add("repo-folder")
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
            setOnAction { toggleRepo(repo.url) }
        }
        val refresh = Button("↻").apply {
            styleClass.add("repo-btn")
            Tooltip.install(this, Tooltip("Reload this repo"))
            setOnAction { refreshRepo(repo.url) }
        }
        val remove = Button("🗑").apply {
            styleClass.addAll("repo-btn", "btn-danger")
            Tooltip.install(this, Tooltip("Remove this repo"))
            setOnAction {
                AppShell.app.store.removeCs3Repo(repo.url)
                repoData.remove(repo.url)
                expanded.remove(repo.url)
                renderRepos()
            }
        }
        val header = HBox(6.0, folderBtn, refresh, remove).apply { alignment = Pos.CENTER_LEFT }

        val body = VBox(6.0)
        if (open) {
            when {
                data == null -> body.children.add(Theme.label("Loading plugins…", dim = true))
                data.plugins.isEmpty() -> body.children.add(Theme.label("No installable plugins found in this repo.", dim = true))
                else -> {
                    if (data.description.isNotBlank()) {
                        body.children.add(Theme.label(data.description, size = 11.5, dim = true))
                    }
                    data.plugins.forEach { (name, url) ->
                        val info = VBox(2.0).apply {
                            children.add(Theme.label(name, size = 14.0, bold = true))
                            children.add(Theme.label(url, size = 10.5, dim = true))
                        }
                        val install = Button("Install").apply {
                            styleClass.addAll("btn", "btn-primary")
                            setOnAction { installPlugin(name, url) }
                        }
                        body.children.add(HBox(10.0, info, install).apply {
                            VBox.setVgrow(info, Priority.ALWAYS)
                            alignment = Pos.CENTER_LEFT
                            styleClass.add("list-row")
                        })
                    }
                }
            }
        }
        val box = VBox(6.0, header)
        if (open) box.children.add(body)
        return box
    }

    private fun toggleRepo(url: String) {
        if (url in expanded) {
            expanded.remove(url)
        } else {
            expanded.add(url)
            if (repoData[url] == null) loadRepoData(url)
        }
        renderRepos()
    }

    private fun refreshRepo(url: String) {
        repoData.remove(url)
        expanded.add(url)
        loadRepoData(url)
    }

    private fun loadRepoData(url: String) {
        busy.isVisible = true
        setStatus("Fetching repo…")
        AppShell.uiScope.launch {
            val result = Http.fetchRepoJson(url)
            Fx.run {
                busy.isVisible = false
                val pair = result.getOrNull()
                if (pair == null) {
                    setStatus("Failed to fetch repo — ${result.exceptionOrNull()?.message ?: "network error"}", isError = true)
                    return@run
                }
                val resolved = pair.first
                val text = pair.second
                val root = runCatching { JSONObject(text) }.getOrNull()
                val name = root?.optString("name").orEmpty().ifBlank { Http.repoDisplayName(resolved) }
                val description = root?.optString("description").orEmpty()
                val plugins = parsePlugins(root)
                repoData[url] = RepoData(resolved, name, description, plugins)
                if (resolved != url) repoData[resolved] = repoData[url]!!
                setStatus("")
                renderRepos()
            }
        }
    }

    /** Reads the repo's `plugins` array, converting .hiki plugin URLs to their
     *  .jar desktop builds. `plugins` is already a JSONArray — org.json's
     *  `new JSONArray(jsonArray)` throws, so never re-wrap an existing array. */
    private fun parsePlugins(root: JSONObject?): List<Pair<String, String>> {
        root ?: return emptyList()
        val plugins = when (val p = root.opt("plugins")) {
            null -> JSONArray()
            is JSONArray -> p
            else -> runCatching { JSONArray(p) }.getOrNull() ?: JSONArray()
        }
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until plugins.length()) {
            val p = plugins.optJSONObject(i) ?: continue
            val name = p.optString("name")
            var url = p.optString("url")
            if (name.isBlank() || url.isBlank()) continue
            if (url.endsWith(".hiki")) url = url.removeSuffix(".hiki") + ".jar"
            out += name to url
        }
        return out
    }

    /** Validates + stores a repo, then expands it so its plugins appear
     *  immediately under the new folder. */
    private fun addRepo(url: String) {
        if (url.isBlank()) return
        busy.isVisible = true
        setStatus("Checking $url…")
        AppShell.uiScope.launch {
            val result = Http.fetchRepoJson(url)
            Fx.run {
                busy.isVisible = false
                val pair = result.getOrNull()
                if (pair == null) {
                    setStatus(
                        "Couldn't load that repo — ${result.exceptionOrNull()?.message ?: "network error"}",
                        isError = true,
                    )
                    return@run
                }
                val resolved = pair.first
                val name = Http.repoDisplayName(resolved)
                val already = AppShell.app.store.repos().any {
                    it.url == resolved || Http.repoDisplayName(it.url) == name
                }
                if (already) {
                    setStatus("That repo is already added.", isError = true)
                    return@run
                }
                AppShell.app.store.addCs3Repo(
                    Cs3Repo(url = resolved, name = name, kind = com.hikari.app.data.RepoKind.HIKARI)
                )
                // Seed the folder with the already-fetched data and expand it.
                val root = runCatching { JSONObject(pair.second) }.getOrNull()
                repoData[resolved] = RepoData(
                    url = resolved,
                    name = root?.optString("name").orEmpty().ifBlank { name },
                    description = root?.optString("description").orEmpty(),
                    plugins = parsePlugins(root),
                )
                expanded.add(resolved)
                setStatus("")
                renderRepos()
            }
        }
    }

    // ── Installing plugins ──────────────────────────────────────────────────

    private fun installFromUrl(raw: String) {
        val url = Http.normalizeUrl(raw)
        if (url.isBlank()) return
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "ext.jar" }
        val name = fileName
            .removeSuffix(".hiki").removeSuffix(".cs3").removeSuffix(".jar")
            .replace(Regex("[-_.]+"), " ").trim().ifBlank { "Extension" }
        installPlugin(name, url)
    }

    /** Downloads (auto-swapping .hiki → .jar), loads, and registers a provider.
     *  Every path ends with a visible status — nothing ever fails silently. */
    private fun installPlugin(name: String, url: String) {
        var dl = url
        if (dl.endsWith(".hiki")) dl = dl.removeSuffix(".hiki") + ".jar"
        busy.isVisible = true
        setStatus("Installing $name…")
        AppShell.uiScope.launch {
            var statusText = "Install failed: unknown error"
            var isErr = true
            try {
                val safeName = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ext" }
                val dest = File(extDir, "$safeName.jar")
                dest.delete()
                val ok = Http.downloadToRobust(dl, dest)
                if (ok) {
                    // A blocked/misbehaving network can serve an HTML error page
                    // with a 200 — a real extension is always a zip (PK header).
                    val isZip = dest.inputStream().use { s ->
                        val h = s.readNBytes(4)
                        h.size == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
                    }
                    if (!isZip) {
                        statusText = "Downloaded file is not an extension (the network may have served an error page instead of the jar)."
                    } else {
                        val (kind, err) = resolveKind(dest)
                        if (kind != null) {
                            val cfg = when (kind) {
                                ProviderType.HIKARI -> ProviderConfig(
                                    id = "hiki|$safeName",
                                    name = name,
                                    type = ProviderType.HIKARI,
                                    url = dest.absolutePath,
                                    extra = "$dl|0",
                                )
                                else -> ProviderConfig(
                                    id = "cs3|$safeName|0",
                                    name = name,
                                    type = ProviderType.CS3,
                                    url = dest.absolutePath,
                                )
                            }
                            AppShell.app.store.addProvider(cfg)
                            statusText = "Installed $name ($kind)."
                            isErr = false
                        } else {
                            statusText = "Couldn't load $name: ${err ?: "not an extension"}"
                        }
                    }
                } else {
                    statusText = "Download failed for $name — check the URL and that GitHub/repo host is reachable from your network."
                }
            } catch (t: Throwable) {
                statusText = "Install failed: ${t.message?.take(300) ?: t.javaClass.simpleName}"
            }
            AppShell.app.providers.refresh()
            Fx.run {
                busy.isVisible = false
                setStatus(statusText, isErr)
                renderInstalled()
            }
        }
    }

    private fun installFromDisk() {
        val chooser = FileChooser().apply {
            title = "Choose an extension (.jar is the desktop format)"
            extensionFilters.add(FileChooser.ExtensionFilter("Extensions", "*.jar", "*.hiki", "*.cs3"))
            extensionFilters.add(FileChooser.ExtensionFilter("All files", "*.*"))
        }
        val file = chooser.showOpenDialog(null) ?: return
        busy.isVisible = true
        setStatus("Installing ${file.name}…")
        AppShell.uiScope.launch {
            var statusText = "Install failed: unknown error"
            var isErr = true
            try {
                val dest = File(extDir, file.name.lowercase().replace(Regex("[^a-z0-9.]"), "-"))
                file.copyTo(dest, overwrite = true)
                val (kind, err) = resolveKind(dest)
                if (kind != null) {
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
                    statusText = "Installed $base ($kind)."
                    isErr = false
                } else {
                    statusText = "Couldn't load ${file.name}: ${err ?: "not an extension"}"
                }
            } catch (t: Throwable) {
                statusText = "Install failed: ${t.message?.take(300) ?: t.javaClass.simpleName}"
            }
            AppShell.app.providers.refresh()
            Fx.run {
                busy.isVisible = false
                setStatus(statusText, isErr)
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

    // ── Installed providers ─────────────────────────────────────────────────

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
            installedBox.children.add(Theme.label("Nothing installed yet.", dim = true))
            return
        }
        list.forEach { cfg ->
            val name = Label(cfg.name).apply {
                maxWidth = 300.0
                isWrapText = true
            }
            val type = Theme.label(cfg.type.name, size = 11.0, dim = true)
            val status = AppShell.app.providers.statuses.value.firstOrNull { it.id == cfg.id }
            val failedStatus = status?.takeIf { !it.loaded }
            val toggle = CheckBox("Enabled").apply { isSelected = cfg.enabled }
            toggle.setOnAction {
                AppShell.app.store.setEnabled(cfg.id, toggle.isSelected)
                AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                refresh()
            }
            val remove = Button("Remove").apply {
                styleClass.addAll("btn", "btn-danger")
                setOnAction {
                    AppShell.app.store.removeProvider(cfg.id)
                    if (cfg.url.isNotBlank() && cfg.type in setOf(ProviderType.HIKARI, ProviderType.CS3)) {
                        runCatching { File(cfg.url).delete() }
                    }
                    AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                    refresh()
                }
            }
            val row = VBox(6.0).apply {
                children.add(
                    HBox(10.0, name, type, toggle, remove).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("list-row")
                    }
                )
                if (failedStatus != null) {
                    children.add(
                        Theme.label("⚠ failed to load: ${failedStatus.error ?: "unknown error"}", size = 11.0, dim = true)
                            .apply { style = style + "; -fx-text-fill: #ff9a9a;" }
                    )
                }
            }
            installedBox.children.add(row)
        }
    }
}
