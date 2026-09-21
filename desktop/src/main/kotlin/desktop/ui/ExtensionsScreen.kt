package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.net.Http
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * Extensions: everything that feeds the app content.
 *
 * Three ideas drive the layout:
 *  - **One composer, one input.** Instead of ten always-visible buttons, a
 *    segmented control picks what you are adding and a single URL field submits
 *    it, so the page stops looking like a control panel.
 *  - **Repos are cards, plugins are rows.** A repo card summarises what is
 *    inside (plugin count, kind) and opens a drill-down that lists every plugin
 *    with its own install state and its own error text.
 *  - **Nothing fails silently.** Every install/uninstall ends in a per-row
 *    message *and* a toast, and reload failures are attached to the row that
 *    caused them.
 */
class ExtensionsScreenView {

    private val content = VBox(Theme.S4)
    val root: ScrollPane = Ui.vScroll(content)

    private val reposBox = VBox(Theme.S2)
    private val installedBox = VBox(Theme.S2)
    private val pluginsBox = VBox(Theme.S2)
    private val statusLabel = themed("", "tiny")

    private val busy = ProgressIndicator().apply {
        styleClass.add("spinner")
        prefWidth = 16.0
        prefHeight = 16.0
        maxWidth = 16.0
        maxHeight = 16.0
        isVisible = false
    }

    private val extDir: File = File(HikariApp.instance.filesDir, "extensions").apply { mkdirs() }

    /** Fetched repo contents, cached per stored repo URL so opening a repo
     *  doesn't hit the network again. */
    private data class RepoData(
        val url: String,
        val name: String,
        val description: String,
        val plugins: List<PluginRef>,
    )

    /** One installable entry from a repo's plugin list. The hashes are the
     *  repo's own sha256 signatures ("sha256-<hex>") — fileHash for the dex
     *  archive (.cs3/.hiki), jarHash for the JVM .jar build. When present
     *  they're verified after download (Android-app parity), so a broken
     *  mirror or proxy frontdoor can never install a tampered plugin. */
    private data class PluginRef(
        val name: String,
        val url: String,
        val fileHash: String?,
        val jarHash: String?,
        val iconUrl: String? = null,
    )

    private val repoData = HashMap<String, RepoData>()
    private val repoErrors = HashMap<String, String>()
    private val repoLoading = HashSet<String>()

    /** On-disk copy of every fetched repo manifest + its parsed plugin list, so
     *  the screen paints instantly at launch instead of re-fetching every repo
     *  over the network (with the top-bar "Fetching repo…" chip up) before it
     *  can show anything. */
    private val repoCache = RepoCache()

    /** When each repo's manifest was last fetched (or last attempted): decides
     *  which entries are stale enough to refresh in the background. */
    private val repoFetchedAt = HashMap<String, Long>()

    private var openRepo: Cs3Repo? = null

    /** Per-repo-plugin install outcome, shown right under its row so a failed
     *  install says why where the user is looking. Keyed by plugin URL. */
    private val installErrors = HashMap<String, String>()

    /**
     * Plugin URL → what is happening to it right now ("Installing…").
     *
     * The row's Install button is replaced by a spinner + that text, so the
     * click has an immediate answer *where the user clicked*; the same text is
     * mirrored into the shell's top-bar chip, so it is still visible when the
     * row has scrolled out of view.
     */
    private val busyPlugins = HashMap<String, String>()

    // ── composer state (kept across re-renders) ─────────────────────────────

    private var mode = 0
    private val composerInput = Ui.field(COMPOSER_PROMPTS[0]).apply {
        HBox.setHgrow(this, Priority.ALWAYS)
        maxWidth = Double.MAX_VALUE
        focusedProperty().addListener { _, _, focused -> if (focused) selectAll() }
        setOnAction { submitComposer() }
    }
    private val composerButton = Ui.button("Add repo", primary = true) { submitComposer() }.apply { minWidth = 128.0 }
    private val fileRow = HBox(8.0).apply { alignment = Pos.CENTER_LEFT; isVisible = false; isManaged = false }
    private val installedFilter = Ui.field("Filter installed…").apply {
        prefWidth = 220.0
        minWidth = 160.0
        textProperty().addListener { _, _, _ -> fillInstalled() }
    }
    private val pluginFilter = Ui.field("Find extension…").apply {
        HBox.setHgrow(this, Priority.ALWAYS)
        maxWidth = Double.MAX_VALUE
        textProperty().addListener { _, _, _ -> fillPlugins() }
    }
    private val pluginCount = themed("", "tiny")

    init {
        buildFileRow()
        renderAll()
    }

    fun onShown() {
        renderAll()
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private fun renderAll() {
        content.children.clear()
        content.children.add(header())
        // The live status line sits directly under the header: an install that
        // finishes while the user is looking at the top of the page must not
        // report itself at the bottom of a long list (that is where the old
        // layout put it, and why installs looked like they did nothing).
        content.children.add(
            HBox(10.0, busy, statusLabel).apply { alignment = Pos.CENTER_LEFT },
        )
        val repo = openRepo
        if (repo != null) {
            renderRepoDetail(repo)
        } else {
            renderComposer()
            renderRepos()
            renderInstalled()
        }
    }

    private fun header(): Node {
        val installed = runCatching { AppShell.app.store.providers().size }.getOrDefault(0)
        val repos = runCatching { AppShell.app.store.repos().size }.getOrDefault(0)
        val statuses = AppShell.app.providers.statuses.value
        val failed = statuses.count { !it.loaded }
        val icons = HBox(6.0,
            Ui.button("Reload all", icon = Icons.REFRESH, ghost = true) { reloadAll() },
            Ui.button("Open folder", icon = Icons.FOLDER, ghost = true) { openExtFolder() },
        ).apply { alignment = Pos.CENTER_RIGHT }
        val subtitle = buildString {
            append("$installed installed · $repos repos")
            if (statuses.isNotEmpty()) append(" · ${statuses.size - failed} loaded")
            if (failed > 0) append(" · $failed failed")
        }
        return Ui.sectionHeader("Extensions", subtitle, icons)
    }

    // ── composer ────────────────────────────────────────────────────────────

    private fun renderComposer() {
        val segmented = Ui.segmented(COMPOSER_MODES, mode) { index ->
            mode = index
            composerInput.promptText = COMPOSER_PROMPTS[index]
            composerButton.text = COMPOSER_LABELS[index]
            fileRow.isVisible = index == MODE_FILE
            fileRow.isManaged = index == MODE_FILE
        }
        val row = HBox(10.0, composerInput, composerButton).apply {
            alignment = Pos.CENTER_LEFT
            composerInput.promptText = COMPOSER_PROMPTS[mode]
            composerButton.text = COMPOSER_LABELS[mode]
            fileRow.isVisible = mode == MODE_FILE
            fileRow.isManaged = mode == MODE_FILE
        }
        content.children.add(
            Ui.panel(
                Ui.sectionHeader("Add a source", "Repos, addons, scrapers and single files"),
                HBox(12.0, segmented).apply { alignment = Pos.CENTER_LEFT },
                row,
                fileRow,
                Ui.divider(),
                themed(HINT, "wrap-hint").apply { isWrapText = true },
            )
        )
    }

    private fun buildFileRow() {
        fileRow.children.setAll(
            Ui.button("Pick an extension file…", icon = Icons.UPLOAD, ghost = true) { installFromDisk() },
            Ui.button("Install from a URL", ghost = true) { mode = MODE_FILE; renderAll() },
            themed("accepts .jar, .cs3, .hiki, .sky, .js, .apk, .m3u/.m3u8", "tiny"),
        )
    }

    private fun submitComposer() {
        val raw = composerInput.text.trim()
        if (raw.isBlank()) {
            setStatus("Paste a URL first.", isError = true)
            return
        }
        when (mode) {
            MODE_HIKARI -> addRepo(Http.normalizeUrl(raw), RepoKind.HIKARI)
            MODE_CS3 -> addRepo(Http.normalizeUrl(raw), RepoKind.CS3)
            MODE_SKYSTREAM -> addSkyStreamRepo(raw)
            MODE_NUVIO -> addRepo(Http.normalizeUrl(raw), RepoKind.NUVIO)
            MODE_ANIYOMI -> addAniyomiRepo(raw)
            MODE_IPTV -> addIptv(raw)
            MODE_STREMIO -> {
                val url = Http.normalizeUrl(raw)
                if (url.contains("github.com") || url.contains("raw.githubusercontent.com")) {
                    setStatus("That looks like a GitHub repo URL, not a Stremio addon manifest.", isError = true)
                } else {
                    val name = url.substringAfter("://").substringBefore("/")
                    AppShell.app.store.addProvider(
                        ProviderConfig(id = "stremio|$url", name = "Addon · $name", type = ProviderType.STREMIO, url = url)
                    )
                    AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                    composerInput.clear()
                    renderAll()
                    setStatus("Stremio addon added: $name")
                    AppShell.toast("Stremio addon added: $name", "ok")
                }
            }
            MODE_SCRAPER -> {
                val url = Http.normalizeUrl(raw)
                val name = url.substringAfter("://").substringBefore("/")
                AppShell.app.store.addProvider(
                    ProviderConfig(id = "uni|$url", name = "Scraper · $name", type = ProviderType.UNIVERSAL, url = url)
                )
                AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                composerInput.clear()
                renderAll()
                setStatus("Scraper added: $name")
                AppShell.toast("Scraper added: $name", "ok")
            }
            else -> installFromUrl(raw)
        }
    }

    // ── repos ───────────────────────────────────────────────────────────────

    /** The uppercase badge text for a repo's engine. */
    private fun kindLabel(kind: RepoKind): String = when (kind) {
        RepoKind.CS3 -> "CLOUDSTREAM"
        RepoKind.HIKARI -> "HIKARI"
        RepoKind.NUVIO -> "NUVIO"
        RepoKind.SKYSTREAM -> "SKYSTREAM"
        RepoKind.ANIYOMI -> "ANIYOMI"
    }

    private fun repoDisplayName(repo: Cs3Repo): String {
        val cached = repoData[repo.url]
        if (cached != null && cached.name.isNotBlank()) return cached.name
        return Http.repoDisplayName(repo.url).takeIf { it.isNotBlank() } ?: repo.name
    }

    private fun renderRepos() {
        reposBox.children.clear()
        val repos = runCatching { AppShell.app.store.repos() }.getOrDefault(emptyList())
        content.children.add(Ui.sectionHeader("Repos", "${repos.size}"))
        if (repos.isEmpty()) {
            reposBox.children.add(
                Ui.emptyState(
                    Icons.GLOBE,
                    "No repos yet",
                    "Add the built-in Hikari repo above, or paste any CloudStream repo.json URL.",
                    Ui.button("Add the Hikari repo", primary = true) {
                        addRepo(DEFAULT_HIKARI_REPO, RepoKind.HIKARI)
                    },
                )
            )
        } else {
            // Everything already fetched is painted straight from disk, so the
            // cards (names, plugin counts) are there on the first frame.
            primeRepoCache()
            val now = System.currentTimeMillis()
            repos.forEach { repo ->
                reposBox.children.add(repoCard(repo))
                val known = repoData.containsKey(repo.url) || repoErrors.containsKey(repo.url)
                val stale = !known || now - (repoFetchedAt[repo.url] ?: 0L) > REPO_CACHE_TTL_MS
                // A stale repo is refreshed quietly, in the background: cached
                // contents stay on screen and nothing claims the UI while it
                // happens.
                if (stale && repoLoading.add(repo.url)) loadRepoData(repo.url, silent = true)
            }
        }
        content.children.add(reposBox)
    }

    /** Loads every stored repo's cached manifest into [repoData] (disk only —
     *  never the network), so they render before anything is fetched. */
    private fun primeRepoCache() {
        val repos = runCatching { AppShell.app.store.repos() }.getOrDefault(emptyList())
        for (repo in repos) {
            if (repoData.containsKey(repo.url)) continue
            val entry = repoCache.load(repo.url) ?: continue
            val name = entry.name.ifBlank { Http.repoDisplayName(entry.resolved) }
            val data = RepoData(entry.resolved, name, entry.description, entry.plugins)
            repoData[repo.url] = data
            if (entry.resolved != repo.url) repoData[entry.resolved] = data
            repoFetchedAt[repo.url] = entry.fetchedAt
        }
    }

    private fun repoCard(repo: Cs3Repo): Node {
        val data = repoData[repo.url]
        val error = repoErrors[repo.url]
        // The name gets a row of its own: "Hikari Extensions (desktop)" and
        // "CloudStream Repo" were being ellipsized to "hikari e…"/"cloudstream
        // r…" because the title shared one row with three buttons that refused
        // to shrink.
        val title = themed(repoDisplayName(repo), "src-name").apply {
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        val subtitle = themed(
            when {
                data != null -> data.description.ifBlank { repo.url }
                error != null -> error
                else -> "Loading…"
            },
            "src-meta",
        ).apply { isWrapText = true; minWidth = 0.0; maxWidth = 620.0 }

        val badgeRow = HBox(6.0).apply {
            alignment = Pos.CENTER_RIGHT
            children.add(Ui.badge(
                data?.let { "${it.plugins.size} plugins" } ?: "…",
                "badge-accent",
            ))
            children.add(Ui.badge(kindLabel(repo.kind), if (repo.kind == RepoKind.CS3) "badge" else "badge-accent"))
            if (error != null) children.add(Ui.badge("unreachable", "badge-danger"))
        }

        // Clicking a row action must not also open the repo (the card itself is
        // clickable): isolate each button from the card's own handler.
        val open = Ui.isolateClicks(Ui.button("Open", primary = true) { openRepoData(repo) })
        val reload = Ui.isolateClicks(Ui.iconButton(Icons.REFRESH, "Reload this repo", 15.0) { refreshRepo(repo.url) })
        val remove = Ui.isolateClicks(
            Ui.iconButton(Icons.TRASH, "Remove this repo", 15.0) { removeRepo(repo) }.apply {
                styleClass.add("h-danger")
            }
        )

        val icon = VBox(Icons.of(Icons.GLOBE, 20.0)).apply {
            styleClass.add("repo-ic")
            alignment = Pos.CENTER
        }
        val info = VBox(2.0, title, subtitle).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)
        val head = HBox(14.0, icon, info, badgeRow).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        val actions = HBox(8.0, Ui.spacer(), open, reload, remove).apply {
            alignment = Pos.CENTER_RIGHT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        val card = VBox(Theme.S2, head, actions).apply {
            styleClass.add("repo-card")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        card.setOnMouseClicked { openRepoData(repo) }
        return card
    }

    private fun openRepoData(repo: Cs3Repo) {
        openRepo = repo
        pluginFilter.clear()
        if (!repoData.containsKey(repo.url)) loadRepoData(repo.url)
        renderAll()
    }

    private fun removeRepo(repo: Cs3Repo) {
        runCatching { AppShell.app.store.removeCs3Repo(repo.url) }
        repoData.remove(repo.url)
        repoErrors.remove(repo.url)
        repoFetchedAt.remove(repo.url)
        repoCache.remove(repo.url)
        if (openRepo?.url == repo.url) openRepo = null
        renderAll()
        setStatus("Removed repo ${repoDisplayName(repo)}")
        AppShell.toast("Removed ${repoDisplayName(repo)}", "ok")
    }

    private fun refreshRepo(url: String) {
        repoData.remove(url)
        repoErrors.remove(url)
        loadRepoData(url)
    }

    private fun reloadAll() {
        setStatus("Reloading every provider and repo…", busy = true)
        busy.isVisible = true
        repoData.clear()
        repoErrors.clear()
        repoFetchedAt.clear()
        // Explicit reload means the user wants fresh network answers — drop the
        // on-disk copies too, or the next render would just re-paint them.
        repoCache.clear()
        renderAll()
        AppShell.uiScope.launch {
            runCatching { AppShell.app.providers.refresh() }
            Fx.run {
                busy.isVisible = false
                setStatus("Reloaded every provider.")
                renderAll()
            }
        }
    }

    private fun openExtFolder() {
        runCatching { desktop.fx.DesktopUi.open(extDir.absolutePath) }
    }

    /** Drills into a repo: its plugins, each with its own install state. */
    private fun renderRepoDetail(repo: Cs3Repo) {
        val data = repoData[repo.url]
        val back = Ui.button("All extensions", icon = Icons.CHEVRON_LEFT, ghost = true) {
            openRepo = null
            renderAll()
        }
        val reload = Ui.button("Reload", icon = Icons.REFRESH, ghost = true) { refreshRepo(repo.url) }
        val remove = Ui.button("Remove repo", icon = Icons.TRASH, danger = true) { removeRepo(repo) }
        // The repo's full name on its own line, with the actions underneath: a
        // name sharing a row with three buttons is a name that gets truncated.
        val heading = Theme.label(
            data?.name?.ifBlank { null } ?: repoDisplayName(repo),
            size = 19.0,
            bold = true,
        ).apply {
            styleClass.add("section-title")
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        val sub = Theme.label(
            data?.description?.ifBlank { null } ?: repo.url,
            size = 12.5,
            dim = true,
        ).apply {
            styleClass.add("section-sub")
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        content.children.add(
            VBox(Theme.S2,
                heading,
                sub,
                HBox(8.0, Ui.spacer(), reload, remove, back).apply {
                    alignment = Pos.CENTER_RIGHT
                    minWidth = 0.0
                    maxWidth = Double.MAX_VALUE
                },
            ).apply { minWidth = 0.0 }
        )
        val filterRow = HBox(10.0, pluginFilter, pluginCount).apply { alignment = Pos.CENTER_LEFT }
        filterRow.isVisible = data != null && data.plugins.isNotEmpty()
        filterRow.isManaged = filterRow.isVisible
        content.children.add(filterRow)
        content.children.add(pluginsBox)
        fillPlugins()
    }

    /** Rebuilds only the plugin list (keeps focus in the filter field). */
    private fun fillPlugins() {
        pluginsBox.children.clear()
        val repo = openRepo ?: return
        val data = repoData[repo.url]
        when {
            data == null -> {
                val error = repoErrors[repo.url]
                if (error != null) {
                    pluginsBox.children.add(
                        Ui.emptyState(
                            Icons.WARNING,
                            "Couldn't load this repo",
                            error,
                            Ui.button("Try again", primary = true) { refreshRepo(repo.url) },
                        )
                    )
                } else {
                    pluginsBox.children.add(Ui.loadingRow("Loading plugins…"))
                }
                pluginCount.text = ""
            }
            data.plugins.isEmpty() -> {
                pluginsBox.children.add(
                    Ui.emptyState(Icons.INFO, "No installable plugins", "This repo published an empty plugin list.")
                )
                pluginCount.text = ""
            }
            else -> {
                val needle = pluginFilter.text.trim().lowercase()
                val filtered = data.plugins.filter {
                    needle.isEmpty() || it.name.lowercase().contains(needle) || it.url.lowercase().contains(needle)
                }
                pluginCount.text = if (needle.isEmpty()) {
                    "${data.plugins.size} plugins"
                } else {
                    "${filtered.size} of ${data.plugins.size} match"
                }
                if (filtered.isEmpty()) {
                    pluginsBox.children.add(themed("No plugin matches “$needle”.", "tiny"))
                }
                filtered.forEach { pluginsBox.children.add(pluginRow(it)) }
            }
        }
    }

    private fun pluginRow(plugin: PluginRef): Node {
        val installed = providersFor(plugin.url)
        val name = themed(plugin.name, "src-name").apply {
            isWrapText = true
            minWidth = 0.0
        }
        val url = themed(plugin.url, "h-mono").apply {
            maxWidth = 520.0
            minWidth = 0.0
            isWrapText = true
        }
        val info = VBox(3.0, name, url).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val state = HBox(6.0).apply {
            alignment = Pos.CENTER_RIGHT
            if (installed.isNotEmpty()) {
                children.add(Ui.badge("${installed.size} installed", "badge-ok"))
            }
            if (plugin.jarHash != null || plugin.fileHash != null) {
                children.add(Ui.badge("signed", "badge"))
            }
        }
        // While the click is being acted on, the button becomes the answer.
        val working = busyPlugins[plugin.url]
        val action: Node = if (working != null) {
            HBox(8.0, tinySpinner(), themed(working, "tiny")).apply { alignment = Pos.CENTER_RIGHT }
        } else if (installed.isEmpty()) {
            Ui.button("Install", primary = true) { installFromRepo(plugin) }
        } else {
            Ui.button("Uninstall", danger = true) { uninstallFromRepo(plugin) }
        }
        val row = HBox(12.0, info, state, action).apply {
            styleClass.add("src-row")
            alignment = Pos.CENTER_LEFT
        }
        val error = installErrors[plugin.url]
        return if (error == null) {
            row
        } else {
            VBox(6.0, row, themed("⚠ $error", "tiny").apply {
                styleClass.add("h-danger")
                isWrapText = true
                maxWidth = 620.0
            })
        }
    }

    /** The 14px spinner used inline in a row's action slot. */
    private fun tinySpinner(): ProgressIndicator = ProgressIndicator().apply {
        styleClass.add("spinner")
        prefWidth = 14.0
        prefHeight = 14.0
        minWidth = 14.0
        minHeight = 14.0
        maxWidth = 14.0
        maxHeight = 14.0
    }

    // ── network ─────────────────────────────────────────────────────────────

    private fun loadRepoData(url: String, silent: Boolean = false) {
        if (!silent) {
            busy.isVisible = true
            setStatus("Fetching repo…")
        }
        AppShell.uiScope.launch {
            val kind = repoKindOf(url)
            // A silent load is a background refresh of something the user can
            // already see — it must not hijack the status line or the shell's
            // activity chip with "Fetching repo… (racing 8 mirrors)".
            val onStep: ((String) -> Unit)? =
                if (silent) null else ({ step -> Fx.run { setStatus(step, busy = true) } })
            val result = fetchManifest(url, kind, onStep)
            val manifest = result.getOrNull()
            if (manifest == null) {
                Fx.run {
                    repoLoading.remove(url)
                    repoFetchedAt[url] = System.currentTimeMillis()
                    val message = Http.humanMessage(result.exceptionOrNull())
                    // Cached contents beat an error message for a repo the user
                    // has already added: the failure is only worth showing when
                    // there is nothing to show in its place.
                    if (repoData[url] == null) repoErrors[url] = message
                    if (!silent) {
                        busy.isVisible = false
                        setStatus("Couldn't load that repo — $message", isError = true)
                    }
                    renderAll()
                }
                return@launch
            }
            // Parsing can fetch nested list files (SkyStream repos), so it stays
            // off the FX thread; only the finished rows are published onto it.
            val resolved = manifest.url
            val root = manifest.root
            val name = root?.optString("name").orEmpty().ifBlank { Http.repoDisplayName(resolved) }
            val description = root?.optString("description").orEmpty()
            val plugins = parsePlugins(kind, manifest)
            Fx.run {
                repoLoading.remove(url)
                repoErrors.remove(url)
                val data = RepoData(resolved, name, description, plugins)
                repoData[url] = data
                if (resolved != url) repoData[resolved] = data
                val at = System.currentTimeMillis()
                repoFetchedAt[url] = at
                repoCache.save(url, RepoCache.Entry(resolved, name, description, plugins, at))
                if (!silent) {
                    busy.isVisible = false
                    setStatus("Loaded ${plugins.size} plugin(s) from $name")
                }
                renderAll()
            }
        }
    }

    /** A fetched repo manifest. Every repo kind publishes a JSON OBJECT except
     *  Aniyomi, whose `index.min.json` is a bare array — that lands in [array]. */
    private class Manifest(val url: String, val root: JSONObject?, val array: JSONArray?)

    /** The kind of a stored repo, by its URL. New repos are added with an
     *  explicit kind; this only serves re-renders/refreshes of ones already
     *  in the store. */
    private fun repoKindOf(url: String): RepoKind {
        val stored = runCatching {
            AppShell.app.store.repos().firstOrNull { it.url == url }
        }.getOrNull()
        if (stored != null) return stored.kind
        val u = url.lowercase()
        return when {
            u.endsWith("manifest.json") -> RepoKind.NUVIO
            u.endsWith("index.min.json") -> RepoKind.ANIYOMI
            else -> RepoKind.CS3
        }
    }

    private fun manifestFileName(kind: RepoKind): String = when (kind) {
        RepoKind.NUVIO -> "manifest.json"
        RepoKind.ANIYOMI -> "index.min.json"
        else -> "repo.json"
    }

    /**
     * Candidate URLs for a repo manifest: what the user pasted, the pasted
     * folder + the kind's index file, and — for the `github.com/owner/repo`
     * links people actually paste — the raw.githubusercontent equivalents (a
     * github HTML page can never parse as JSON).
     */
    private fun manifestCandidates(rawUrl: String, fileName: String): List<String> {
        val raw = rawUrl.trim()
        if (raw.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(raw)
        val base = raw.trimEnd('/')
        if (!base.lowercase().endsWith(".json")) out.add("$base/$fileName")
        val m = Regex(
            "^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/(?:tree|blob)/([^/]+)(/.*)?)?$"
        ).find(base)
        if (m != null) {
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3].ifBlank { "main" }
            val rest = m.groupValues[4].trim('/')
            val roots = linkedSetOf(
                "https://raw.githubusercontent.com/$owner/$repo/$branch" + if (rest.isBlank()) "" else "/$rest",
            )
            if (branch == "main") {
                roots.add("https://raw.githubusercontent.com/$owner/$repo/master" + if (rest.isBlank()) "" else "/$rest")
            }
            for (r in roots) {
                out.add(r)
                if (!r.lowercase().endsWith(".json")) out.add("$r/$fileName")
            }
        }
        return out.toList()
    }

    /**
     * Fetches a repo manifest of any kind. CloudStream/Hikari repos keep the
     * existing multi-mirror [Http.fetchRepoJson] path; the other kinds fetch
     * their own index file (Nuvio `manifest.json`, Aniyomi `index.min.json`,
     * SkyStream `repo.json`) from [manifestCandidates].
     */
    private fun fetchManifest(
        url: String,
        kind: RepoKind,
        onStep: ((String) -> Unit)? = null,
    ): Result<Manifest> {
        if (kind == RepoKind.CS3 || kind == RepoKind.HIKARI) {
            val r = Http.fetchRepoJson(url, onStep)
            val pair = r.getOrNull()
                ?: return Result.failure(r.exceptionOrNull() ?: Exception("Could not fetch repo"))
            val root = runCatching { JSONObject(pair.second) }.getOrNull()
                ?: return Result.failure(Exception("Invalid repo.json"))
            return Result.success(Manifest(pair.first, root, null))
        }
        val fileName = manifestFileName(kind)
        val candidates = manifestCandidates(url, fileName)
        if (candidates.isEmpty()) return Result.failure(Exception("Enter a repo URL"))
        var last: Throwable? = null
        for (candidate in candidates) {
            onStep?.invoke("Fetching $fileName…")
            val text = Http.fetchStringRobust(candidate).getOrNull()
            if (text == null) {
                last = Exception("Could not fetch $candidate")
                continue
            }
            if (kind == RepoKind.ANIYOMI) {
                val arr = runCatching { JSONArray(text) }.getOrNull()
                if (arr != null && arr.length() > 0) return Result.success(Manifest(candidate, null, arr))
                last = Exception("Invalid $fileName — expected a JSON array of extensions")
            } else {
                val root = runCatching { JSONObject(text) }.getOrNull()
                if (root != null) return Result.success(Manifest(candidate, root, null))
                last = Exception("Invalid $fileName")
            }
        }
        return Result.failure(last ?: Exception("No candidate URL served $fileName"))
    }

    /** Reads a repo's plugin list for its [kind]: an Aniyomi index array, a
     *  Nuvio manifest's `scrapers`, a SkyStream repo's `plugins`/`pluginLists`/
     *  nested `repos`, or the plain CloudStream/Hikari `plugins` array. */
    private fun parsePlugins(kind: RepoKind, manifest: Manifest): List<PluginRef> {
        when (kind) {
            RepoKind.ANIYOMI -> {
                val arr = manifest.array ?: return emptyList()
                val trimmed = manifest.url.trimEnd('/')
                val baseUrl = if (trimmed.endsWith("/index.min.json", ignoreCase = true)) {
                    trimmed.substring(0, trimmed.length - "/index.min.json".length)
                } else {
                    trimmed
                }
                val out = LinkedHashMap<String, PluginRef>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = com.hikari.app.aniyomi.AniyomiExtensionManager.repoPlugin(o, baseUrl) ?: continue
                    out[p.url] = PluginRef(p.name, p.url, p.fileHash, null, p.iconUrl)
                }
                return out.values.toList()
            }
            RepoKind.NUVIO -> {
                val root = manifest.root ?: return emptyList()
                val baseUrl = manifest.url.substringBeforeLast('/')
                val out = LinkedHashMap<String, PluginRef>()
                val arr = root.optJSONArray("scrapers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = com.hikari.app.nuvio.NuvioPluginManager.repoPlugin(o, baseUrl) ?: continue
                    out[p.url] = PluginRef(p.name, p.url, p.fileHash, null, p.iconUrl)
                }
                return out.values.toList()
            }
            RepoKind.SKYSTREAM -> {
                val root = manifest.root ?: return emptyList()
                val out = LinkedHashMap<String, PluginRef>()
                fun addSky(a: JSONArray?) {
                    if (a == null) return
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        val p = com.hikari.app.skystream.SkyStreamPluginManager.repoPlugin(o, "") ?: continue
                        out[p.url] = PluginRef(p.name, p.url, p.fileHash, null, p.iconUrl)
                    }
                }
                fun addList(listUrl: String) {
                    val text = Http.fetchStringRobust(listUrl).getOrNull() ?: return
                    addSky(runCatching { JSONArray(text) }.getOrNull())
                }
                addSky(root.optJSONArray("plugins"))
                root.optJSONArray("pluginLists")?.let { lists ->
                    for (i in 0 until lists.length()) {
                        addList(lists.optString(i))
                    }
                }
                root.optJSONArray("repos")?.let { nested ->
                    for (i in 0 until nested.length()) {
                        val nestedUrl = nested.optString(i).trim()
                        if (nestedUrl.isBlank()) continue
                        val nestedText = Http.fetchStringRobust(nestedUrl).getOrNull() ?: continue
                        val nestedRoot = runCatching { JSONObject(nestedText) }.getOrNull() ?: continue
                        addSky(nestedRoot.optJSONArray("plugins"))
                        nestedRoot.optJSONArray("pluginLists")?.let { lists ->
                            for (j in 0 until lists.length()) addList(lists.optString(j))
                        }
                    }
                }
                return out.values.toList()
            }
            else -> return parseCloudStreamPlugins(manifest.root, manifest.url)
        }
    }

    /** Reads the repo's `plugins` array, resolving relative plugin URLs
     *  against the repo.json ([baseUrl] — the URL that actually served it),
     *  converting .hiki plugin URLs to their .jar desktop builds, and keeping
     *  the repo's sha256 signatures for post-download verification. */
    private fun parseCloudStreamPlugins(root: JSONObject?, baseUrl: String = ""): List<PluginRef> {
        root ?: return emptyList()
        val plugins = when (val p = root.opt("plugins")) {
            null -> JSONArray()
            is JSONArray -> p
            else -> runCatching { JSONArray(p) }.getOrNull() ?: JSONArray()
        }
        val out = mutableListOf<PluginRef>()
        for (i in 0 until plugins.length()) {
            val p = plugins.optJSONObject(i) ?: continue
            val name = p.optString("name")
            var url = p.optString("url")
            if (name.isBlank() || url.isBlank()) continue
            if (baseUrl.isNotBlank()) url = Http.resolveRelativeTo(url, baseUrl)
            if (url.endsWith(".hiki")) url = url.removeSuffix(".hiki") + ".jar"
            out += PluginRef(
                name = name,
                url = url,
                fileHash = p.optString("fileHash").ifBlank { null },
                jarHash = p.optString("jarHash").ifBlank { null },
                iconUrl = p.optString("iconUrl").ifBlank { p.optString("logo") }.ifBlank { null },
            )
        }
        return out
    }

    /** Validates + stores a repo, then opens its folder so the plugins appear
     *  immediately (Android behaviour). [kind] is the row the user chose; a
     *  CloudStream v2 manifest (`pluginLists`) is always stored as CS3 even if
     *  pasted into the Hikari field, so the kind always reflects what the repo
     *  actually is. */
    private fun addRepo(url: String, kind: RepoKind) {
        if (url.isBlank()) return
        busy.isVisible = true
        setStatus("Checking $url…", busy = true)
        AppShell.uiScope.launch {
            val result = fetchManifest(url, kind) { step -> Fx.run { setStatus(step, busy = true) } }
            val manifest = result.getOrNull()
            if (manifest == null) {
                Fx.run {
                    busy.isVisible = false
                    val message = Http.humanMessage(result.exceptionOrNull())
                    repoErrors[url] = message
                    setStatus("Couldn't add that repo — $message", isError = true)
                    AppShell.toast("Repo could not be added", "error")
                    renderAll()
                }
                return@launch
            }
            val resolved = manifest.url
            val root = manifest.root
            // Only a CloudStream-family manifest can re-declare itself as v2
            // (pluginLists); a SkyStream repo also carries pluginLists but must
            // keep its own kind.
            val effKind = if ((kind == RepoKind.CS3 || kind == RepoKind.HIKARI) &&
                root?.has("pluginLists") == true
            ) {
                RepoKind.CS3
            } else {
                kind
            }
            val name = root?.optString("name").orEmpty().ifBlank { Http.repoDisplayName(resolved) }
            val description = root?.optString("description").orEmpty()
            val plugins = parsePlugins(effKind, manifest)
            Fx.run {
                busy.isVisible = false
                val existing = runCatching {
                    AppShell.app.store.repos().firstOrNull {
                        it.url == resolved || (it.kind == effKind && Http.repoDisplayName(it.url) == name)
                    }
                }.getOrNull()
                if (existing != null && existing.url != resolved) {
                    runCatching { AppShell.app.store.removeCs3Repo(existing.url) }
                    repoData.remove(existing.url)
                }
                if (existing != null && existing.url == resolved) {
                    repoData[existing.url] = RepoData(existing.url, name, description, plugins)
                    repoErrors.remove(existing.url)
                    val at = System.currentTimeMillis()
                    repoFetchedAt[existing.url] = at
                    repoCache.save(existing.url, RepoCache.Entry(resolved, name, description, plugins, at))
                    openRepo = existing
                    composerInput.clear()
                    renderAll()
                    setStatus("${repoDisplayName(existing)} is already added — refreshed it.")
                    return@run
                }
                runCatching { AppShell.app.store.addCs3Repo(Cs3Repo(url = resolved, name = name, kind = effKind)) }
                repoData[resolved] = RepoData(resolved, name, description, plugins)
                repoErrors.remove(resolved)
                val at = System.currentTimeMillis()
                repoFetchedAt[resolved] = at
                repoCache.save(resolved, RepoCache.Entry(resolved, name, description, plugins, at))
                openRepo = Cs3Repo(url = resolved, name = name, kind = effKind)
                composerInput.clear()
                renderAll()
                setStatus("Added $name")
                AppShell.toast("Added repo $name", "ok")
            }
        }
    }

    // ── engines: skystream / nuvio / aniyomi / iptv ─────────────────────────

    /** SkyStream "add repo": a bare shortcode is resolved through the manager's
     *  cutt.ly `sky-` namespace, a URL is used as-is. */
    private fun addSkyStreamRepo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addRepo(Http.normalizeUrl(trimmed), RepoKind.SKYSTREAM)
            return
        }
        busy.isVisible = true
        setStatus("Resolving SkyStream shortcode…", busy = true)
        AppShell.uiScope.launch {
            val resolved = runCatching {
                com.hikari.app.skystream.SkyStreamPluginManager.resolveRepoUrl(trimmed)
            }.getOrNull()
            Fx.run {
                busy.isVisible = false
                if (resolved.isNullOrBlank()) {
                    setStatus("Couldn't resolve “$trimmed” — paste the repo URL instead.", isError = true)
                    AppShell.toast("SkyStream repo not found", "error")
                } else {
                    addRepo(resolved, RepoKind.SKYSTREAM)
                }
            }
        }
    }

    /** Aniyomi repos publish an index.min.json (a bare JSON array); a bare
     *  folder/host is normalised to `<dir>/index.min.json` by the manager. */
    private fun addAniyomiRepo(raw: String) {
        val resolved = runCatching {
            com.hikari.app.aniyomi.AniyomiExtensionManager.resolveRepoUrl(raw)
        }.getOrNull()
        if (resolved.isNullOrBlank()) {
            setStatus("That doesn't look like an Aniyomi repo — paste a link to its index.min.json.", isError = true)
            return
        }
        addRepo(resolved, RepoKind.ANIYOMI)
    }

    /** Adds an IPTV playlist — an m3u/m3u8 URL, or a path to a local file. The
     *  playlist is parsed once up front, so a dead link says so here instead of
     *  showing an empty channel list later. */
    private fun addIptv(raw: String) {
        val url = raw.trim()
        if (url.isBlank()) return
        busy.isVisible = true
        setStatus("Reading playlist…", busy = true)
        AppShell.uiScope.launch {
            val preview = runCatching { com.hikari.app.iptv.IptvProvider.preview(url) }.getOrNull()
            val count = preview?.getOrNull() ?: 0
            val error = preview?.exceptionOrNull()
            Fx.run {
                busy.isVisible = false
                if (preview == null || count <= 0) {
                    val why = error?.message ?: "it lists no channels"
                    setStatus("Couldn't add that playlist — $why", isError = true)
                    AppShell.toast("Playlist could not be added", "error")
                    return@run
                }
                val host = url.substringAfter("://", "").substringBefore('/').ifBlank {
                    File(url).name.substringBeforeLast('.').ifBlank { "IPTV" }
                }
                val id = "iptv|" + url.hashCode()
                if (runCatching { AppShell.app.store.providers().any { it.id == id } }.getOrDefault(false).not()) {
                    AppShell.app.store.addProvider(
                        ProviderConfig(id = id, name = "IPTV · $host", type = ProviderType.IPTV, url = url)
                    )
                }
                AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                composerInput.clear()
                renderAll()
                val msg = "Added IPTV playlist ($count channel${if (count == 1) "" else "s"})"
                setStatus(msg)
                AppShell.toast(msg, "ok")
            }
        }
    }

    private fun installNuvioFromUrl(name: String, url: String) {
        installNuvioPlugin(PluginRef(name, url, null, null))
    }

    private fun installSkyFromUrl(name: String, url: String) {
        installSkyStreamPlugin(PluginRef(name, url, null, null))
    }

    private fun installAniyomiFromUrl(name: String, url: String) {
        installAniyomiPlugin(PluginRef(name, url, null, null))
    }

    /** Installs one nuvio scraper: download (with its repo sha256 when listed),
     *  then hand the bytes to [NuvioPluginManager.installScraper], which loads
     *  the module in the JS runtime before accepting it. */
    private fun installNuvioPlugin(plugin: PluginRef) {
        val url = plugin.url
        if (busyPlugins.containsKey(url)) return
        installErrors.remove(url)
        busyPlugins[url] = "Installing…"
        busy.isVisible = true
        setStatus("Installing ${plugin.name}…", busy = true)
        fillPlugins()
        AppShell.uiScope.launch {
            val result = runCatching {
                val bytes = Http.fetchBytesRobust(url)
                    ?: throw Exception("Download failed — check the URL")
                checkHash(bytes, plugin.fileHash)?.let { throw Exception(it) }
                val fileName = plugin.name.substringBeforeLast('.').ifBlank { "provider" } + ".js"
                com.hikari.app.nuvio.NuvioPluginManager.installScraper(
                    HikariApp.instance,
                    bytes,
                    fileName,
                    sourceUrl = url,
                    iconUrl = plugin.iconUrl,
                ).getOrThrow()
            }
            finishInstall(plugin.name, url, result)
        }
    }

    /** Installs one `.sky`: [SkyStreamPluginManager.install] unpacks the zip and
     *  validates the plugin's four exports in the JS runtime first. */
    private fun installSkyStreamPlugin(plugin: PluginRef) {
        val url = plugin.url
        if (busyPlugins.containsKey(url)) return
        installErrors.remove(url)
        busyPlugins[url] = "Installing…"
        busy.isVisible = true
        setStatus("Installing ${plugin.name}…", busy = true)
        fillPlugins()
        AppShell.uiScope.launch {
            val result = runCatching {
                val bytes = Http.fetchBytesRobust(url)
                    ?: throw Exception("Download failed — check the URL")
                com.hikari.app.skystream.SkyStreamPluginManager.install(
                    HikariApp.instance,
                    bytes,
                    sourceUrl = url,
                    iconUrl = plugin.iconUrl,
                ).getOrThrow()
            }
            finishInstall(plugin.name, url, result)
        }
    }

    /** Installs one Aniyomi `.apk`/`.ext`: the manager validates the manifest
     *  feature and registers one provider per bundled source. */
    private fun installAniyomiPlugin(plugin: PluginRef) {
        val url = plugin.url
        if (busyPlugins.containsKey(url)) return
        installErrors.remove(url)
        busyPlugins[url] = "Installing…"
        busy.isVisible = true
        setStatus("Installing ${plugin.name}…", busy = true)
        fillPlugins()
        AppShell.uiScope.launch {
            val result = runCatching {
                val bytes = Http.fetchBytesRobust(url)
                    ?: throw Exception("Download failed — check the URL")
                checkHash(bytes, plugin.fileHash)?.let { throw Exception(it) }
                com.hikari.app.aniyomi.AniyomiExtensionManager.install(
                    HikariApp.instance,
                    bytes,
                    sourceUrl = url,
                    iconUrl = plugin.iconUrl,
                ).getOrThrow()
            }
            finishInstall(plugin.name, url, result)
        }
    }

    /** A repo row's Install, routed to the manager for the open repo's kind. */
    private fun installFromRepo(plugin: PluginRef) {
        when (openRepo?.kind) {
            RepoKind.NUVIO -> installNuvioPlugin(plugin)
            RepoKind.SKYSTREAM -> installSkyStreamPlugin(plugin)
            RepoKind.ANIYOMI -> installAniyomiPlugin(plugin)
            else -> installPlugin(plugin.name, plugin.url, plugin.fileHash, plugin.jarHash)
        }
    }

    /** A repo row's Uninstall, routed to the manager for the open repo's kind.
     *  The managers remove by exact source URL, so each installed provider's own
     *  spelling of the URL is handed over (a repo build can move the file). */
    private fun uninstallFromRepo(plugin: PluginRef) {
        val kind = openRepo?.kind
        if (kind == null || kind == RepoKind.CS3 || kind == RepoKind.HIKARI) {
            uninstallPlugin(plugin.name, plugin.url)
            return
        }
        busyPlugins[plugin.url] = "Uninstalling…"
        fillPlugins()
        AppShell.uiScope.launch {
            val targets = providersFor(plugin.url).mapNotNull { it.extra }.distinct()
                .ifEmpty { listOf(plugin.url) }
            var removed = 0
            for (t in targets) removed += runCatching { uninstallByKind(kind, t) }.getOrDefault(0)
            Fx.run {
                busyPlugins.remove(plugin.url)
                val count = if (removed > 0) removed else targets.size
                setStatus("Uninstalled ${plugin.name} ($count provider${if (count == 1) "" else "s"}).")
                AppShell.toast("Uninstalled ${plugin.name}", "ok")
                renderAll()
            }
            reloadProvidersQuietly()
        }
    }

    private suspend fun uninstallByKind(kind: RepoKind, source: String): Int = when (kind) {
        RepoKind.NUVIO -> com.hikari.app.nuvio.NuvioPluginManager.uninstallScraper(HikariApp.instance, source)
        RepoKind.SKYSTREAM -> com.hikari.app.skystream.SkyStreamPluginManager.uninstall(HikariApp.instance, source)
        RepoKind.ANIYOMI -> com.hikari.app.aniyomi.AniyomiExtensionManager.uninstall(HikariApp.instance, source)
        else -> 0
    }

    /** Shared tail of every non-dex install: one busy-state cleanup, one row
     *  message, one status line, one toast. */
    private fun finishInstall(name: String, url: String, result: Result<Int>) {
        Fx.run {
            busyPlugins.remove(url)
            busy.isVisible = false
            val count = result.getOrNull()
            val isErr = count == null
            val message = if (isErr) {
                "Couldn't install $name: " +
                    (result.exceptionOrNull()?.message ?: "unknown error").take(300)
            } else {
                val n = if (count!! > 0) count else 1
                "Installed $name ($n provider${if (n == 1) "" else "s"})."
            }
            if (isErr) installErrors[url] = message.take(700) else installErrors.remove(url)
            setStatus(message, isErr)
            AppShell.toast(
                if (isErr) "$name could not be installed" else "$name installed",
                if (isErr) "error" else "ok",
            )
            renderAll()
        }
        reloadProvidersQuietly()
    }

    /** Verifies a repo's `sha256-<hex>` signature over [bytes]; returns a reason
     *  when it doesn't match, null when fine (or nothing to check). */
    private fun checkHash(bytes: ByteArray, expected: String?): String? {
        val want = expected?.removePrefix("sha256-")?.lowercase() ?: return null
        if (want.length != 64) return null
        val got = runCatching {
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        return if (got != want) "checksum mismatch (file corrupted or tampered)" else null
    }

    // ── installing / uninstalling ───────────────────────────────────────────

    private fun installFromUrl(raw: String) {
        val url = Http.normalizeUrl(raw)
        if (url.isBlank()) return
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith("repo.json")) {
            addRepo(url, RepoKind.CS3)
            return
        }
        if (path.endsWith("manifest.json")) {
            addRepo(url, RepoKind.NUVIO)
            return
        }
        if (path.endsWith("index.min.json")) {
            addRepo(url, RepoKind.ANIYOMI)
            return
        }
        if (path.endsWith(".m3u") || path.endsWith(".m3u8")) {
            addIptv(url)
            return
        }
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "ext.jar" }
        val name = fileName
            .removeSuffix(".hiki").removeSuffix(".cs3").removeSuffix(".jar")
            .removeSuffix(".sky").removeSuffix(".js").removeSuffix(".apk").removeSuffix(".ext")
            .replace(Regex("[-_.]+"), " ").trim().ifBlank { "Extension" }
        when {
            path.endsWith(".sky") -> installSkyFromUrl(name, url)
            path.endsWith(".js") -> installNuvioFromUrl(name, url)
            path.endsWith(".apk") || path.endsWith(".ext") -> installAniyomiFromUrl(name, url)
            else -> installPlugin(name, url)
        }
    }

    /** Providers installed from a given repo plugin URL. Hikari/CloudStream
     *  bundle providers share the download URL as their extra prefix
     *  (`<url>|<index>`); Nuvio/SkyStream/Aniyomi record the source URL whole. */
    private fun providersFor(url: String): List<ProviderConfig> =
        runCatching {
            val keys = sourceKeys(url)
            AppShell.app.store.providers().filter { cfg ->
                when (cfg.type) {
                    ProviderType.HIKARI, ProviderType.CS3 -> cfg.extra?.startsWith("$url|") == true
                    ProviderType.NUVIO, ProviderType.SKYSTREAM, ProviderType.ANIYOMI -> {
                        val extra = cfg.extra ?: return@filter false
                        keys.any { k -> extra == k || extra.startsWith("$k|") }
                    }
                    else -> false
                }
            }
        }.getOrDefault(emptyList())

    /**
     * The spellings of a source URL an install may have recorded. A repo build
     * can move the file (a new branch, `refs/heads/x` vs `x`, the jsDelivr
     * mirror) between listing and uninstall, and a literal comparison used to
     * greet an already-installed extension with an Install button again.
     */
    private fun sourceKeys(url: String): Set<String> {
        val u = url.trim()
        val out = linkedSetOf(u)
        out.add(u.replace("/refs/heads/", "/"))
        out.add(u.replace("https://cdn.jsdelivr.net/gh/", "https://raw.githubusercontent.com/"))
        out.add(u.removeSuffix("/"))
        return out
    }

    /** Downloads (auto-swapping .hiki → .jar), loads, and registers every
     *  provider inside the extension. Every candidate is verified (zip header
     *  + the repo's sha256 when it publishes one) BEFORE it is loaded, and a
     *  bad candidate falls through to the next one. Every path ends with a
     *  visible status — nothing ever fails silently. */
    private fun installPlugin(name: String, url: String, fileHash: String? = null, jarHash: String? = null) {
        // A second click while the first is still running would download the
        // same file twice and register the provider twice.
        if (busyPlugins.containsKey(url)) return
        var dl = url
        if (dl.endsWith(".hiki")) dl = dl.removeSuffix(".hiki") + ".jar"
        installErrors.remove(url)
        busyPlugins[url] = "Installing…"
        busy.isVisible = true
        setStatus("Installing $name…", busy = true)
        fillPlugins()
        AppShell.uiScope.launch {
            var statusText = "Install failed: unknown error"
            var isErr = true
            try {
                val safeName = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ext" }
                val dest = File(extDir, "$safeName.jar")
                // A reinstall or a repair of the build that is already on disk:
                // when the repo publishes a sha256 for it (every big repo does —
                // that is what the "signed" badge means) and the local file
                // matches, the download would only make the user wait for bytes
                // they already have. Re-registering the file IS the install.
                val reused = dest.isFile && dest.length() > 0L && fileHash != null &&
                    verifyDownload(dest, dl, fileHash, jarHash) == null
                if (!reused) dest.delete()
                // CloudStream repos publish the same plugin side by side as a
                // dex .cs3 and a JVM .jar (Hikari repos: .hiki/.jar). When the
                // preferred form 404s or is blocked on some mirror, the sibling
                // in the same folder is the same plugin — and both DexJar and
                // the URLClassLoader can load whichever one arrives.
                val stem = dl.substringBeforeLast('.')
                val candidates = linkedSetOf(dl)
                if (dl.endsWith(".jar")) {
                    candidates.add("$stem.cs3")
                    candidates.add("$stem.hiki")
                } else if (dl.endsWith(".cs3")) {
                    candidates.add("$stem.jar")
                }
                val attempts = StringBuilder()
                var registered: String? = null
                var loadFailure: String? = null
                for (c in candidates) {
                    val why = StringBuilder()
                    val ok = if (reused && c == dl) true else Http.downloadToRobust(c, dest) { tried, success, reason ->
                        if (!success) {
                            val host = tried.substringAfter("//").substringBefore('/')
                            if (why.isNotEmpty()) why.append(";  ")
                            why.append("$host — ${reason?.take(70) ?: "failed"}")
                        }
                    }
                    if (!ok) {
                        if (why.isNotEmpty()) attempts.append(why).append('\n')
                        continue
                    }
                    // A blocked/misbehaving network (or one of the proxy
                    // frontdoors) can serve an HTML error page or a tampered
                    // file with a 200 — a real extension is always a zip (PK
                    // header), and when the repo publishes a sha256 for this
                    // build it must match before the file is ever loaded.
                    val verifyFail = verifyDownload(dest, c, fileHash, jarHash)
                    if (verifyFail != null) {
                        attempts.append("${c.substringAfterLast('/').substringBefore('?')} — $verifyFail\n")
                        dest.delete()
                        continue
                    }
                    val result = registerExtension(name, safeName, dest, dl)
                    if (result != null) {
                        registered = result
                        break
                    }
                    // Report the reason from the loader that was actually
                    // supposed to handle this file: a dex archive's real error
                    // lives on Cs3PluginManager, and showing the Hikari loader's
                    // "manifest.json has no mainClass" for a .cs3 hid it.
                    val isDex = c.endsWith(".cs3") || c.endsWith(".hiki")
                    val primary = if (isDex) Cs3PluginManager.lastError else HikariPluginManager.lastError
                    val secondary = if (isDex) HikariPluginManager.lastError else Cs3PluginManager.lastError
                    loadFailure = listOfNotNull(primary, secondary).joinToString("  |  ")
                        .ifBlank { "not an extension" }
                    attempts.append("${c.substringAfterLast('/')} — not loadable (${loadFailure?.take(200)})\n")
                }
                if (registered != null) {
                    statusText = registered!!
                    isErr = false
                } else if (loadFailure != null && attempts.indexOf("not loadable") >= 0) {
                    statusText = "Couldn't load $name: ${loadFailure!!.take(300)}"
                } else {
                    statusText = "Download failed for $name — no mirror served the file:\n" +
                        (if (attempts.isEmpty()) "(no reasons reported)" else attempts.toString()) +
                        "If several hosts above say “blocked”/“SSL”, your network or DNS filters GitHub-family hosts — the ghfast.top/ghproxy.net rows should still get through."
                }
            } catch (t: Throwable) {
                statusText = "Install failed: ${t.message?.take(300) ?: t.javaClass.simpleName}"
            }
            Fx.run {
                busyPlugins.remove(url)
                busy.isVisible = false
                if (isErr) installErrors[url] = statusText.take(700) else installErrors.remove(url)
                setStatus(statusText, isErr)
                AppShell.toast(
                    if (isErr) "$name could not be installed" else "$name installed",
                    if (isErr) "error" else "ok",
                )
                // The extension is registered and usable NOW — render it as
                // installed immediately. Reloading every OTHER provider is
                // background work: waiting for it here is what made an install
                // that had already finished look like it was still going.
                renderAll()
            }
            reloadProvidersQuietly()
        }
    }

    /**
     * Refreshes the provider list off the critical path. Reuses already-loaded
     * provider instances (see ProviderManager), so this is a cheap incremental
     * pass rather than a full re-init of every installed extension.
     */
    private fun reloadProvidersQuietly() {
        AppShell.uiScope.launch {
            runCatching { AppShell.app.providers.refresh() }
            Fx.run { renderAll() }
        }
    }

    /** Null = the downloaded file is a plausible, untampered extension; a
     *  string says why it isn't. */
    private fun verifyDownload(dest: File, url: String, fileHash: String?, jarHash: String?): String? {
        val isZip = dest.inputStream().use { s ->
            val h = s.readNBytes(4)
            h.size == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
        }
        if (!isZip) return "served an error page, not an extension"
        val expected = when {
            url.endsWith(".cs3") || url.endsWith(".hiki") -> fileHash
            url.endsWith(".jar") -> jarHash ?: fileHash
            else -> null
        } ?: return null
        val want = expected.removePrefix("sha256-").lowercase()
        if (want.length != 64) return null
        val got = sha256Hex(dest) ?: return null
        return if (got != want) "checksum mismatch (file corrupted or tampered)" else null
    }

    private fun sha256Hex(f: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Registers every provider inside [dest] as its own provider config.
     *  Bundle extensions (a manifest with several mainClass entries) become
     *  several entries — e.g. the Anime bundle installs AniKoto, Anikage, ….
     *  Returns a success message, or null (failure reason is left on the
     *  plugin managers' lastError). */
    private fun registerExtension(name: String, safeName: String, dest: File, sourceUrl: String): String? {
        val hiki = HikariPluginManager.reload(HikariApp.instance, dest)
        if (hiki.isNotEmpty()) {
            hiki.forEachIndexed { idx, p ->
                val display = if (hiki.size > 1) "$name · ${p.name}" else name
                AppShell.app.store.addProvider(
                    ProviderConfig(
                        id = "hiki|$safeName|$idx",
                        name = display,
                        type = ProviderType.HIKARI,
                        url = dest.absolutePath,
                        extra = "$sourceUrl|$idx",
                    )
                )
            }
            return "Installed $name (${hiki.size} extension${if (hiki.size > 1) "s" else ""})."
        }

        val cs3 = Cs3PluginManager.reload(HikariApp.instance, dest)
        if (cs3.isNotEmpty()) {
            cs3.forEachIndexed { idx, api ->
                val display = if (cs3.size > 1) {
                    "$name · ${api.name.ifBlank { "Provider ${idx + 1}" }}"
                } else {
                    name
                }
                AppShell.app.store.addProvider(
                    ProviderConfig(
                        id = "cs3|$safeName|$idx",
                        name = display,
                        type = ProviderType.CS3,
                        url = dest.absolutePath,
                        extra = "$sourceUrl|$idx",
                    )
                )
            }
            return "Installed $name (${cs3.size} provider${if (cs3.size > 1) "s" else ""})."
        }
        return null
    }

    private fun uninstallPlugin(name: String, url: String) {
        val matches = providersFor(url)
        if (matches.isEmpty()) return
        busyPlugins[url] = "Uninstalling…"
        fillPlugins()
        // Removing an extension is entirely local work — the row must never wait
        // on the network or on any other extension to update.
        matches.forEach { AppShell.app.store.removeProvider(it.id) }
        val file = matches.first().url.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists()) runCatching { file.delete() }
        busyPlugins.remove(url)
        setStatus("Uninstalled $name (${matches.size} extension${if (matches.size > 1) "s" else ""}).")
        AppShell.toast("Uninstalled $name", "ok")
        renderAll()
        reloadProvidersQuietly()
    }

    private fun installFromDisk() {
        val chooser = FileChooser().apply {
            title = "Choose an extension"
            extensionFilters.add(
                FileChooser.ExtensionFilter(
                    "Extensions", "*.jar", "*.hiki", "*.cs3", "*.sky", "*.js", "*.apk", "*.ext", "*.m3u", "*.m3u8",
                )
            )
            extensionFilters.add(FileChooser.ExtensionFilter("JVM extensions", "*.jar", "*.hiki"))
            extensionFilters.add(FileChooser.ExtensionFilter("SkyStream / Nuvio", "*.sky", "*.js"))
            extensionFilters.add(FileChooser.ExtensionFilter("Aniyomi", "*.apk", "*.ext"))
            extensionFilters.add(FileChooser.ExtensionFilter("IPTV playlists", "*.m3u", "*.m3u8"))
            extensionFilters.add(FileChooser.ExtensionFilter("All files", "*.*"))
        }
        val file = chooser.showOpenDialog(null) ?: return
        val kind = file.name.substringAfterLast('.', "").lowercase()
        // Playlists and Aniyomi/SkyStream/Nuvio files are read from the path
        // itself — no copy into the extension dir is needed (the managers own
        // their own storage).
        if (kind == "m3u" || kind == "m3u8") {
            addIptv(file.absolutePath)
            return
        }
        busy.isVisible = true
        setStatus("Installing ${file.name}…", busy = true)
        AppShell.uiScope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            val result: Result<Int>? = when (kind) {
                "sky" -> bytes?.let {
                    runCatching {
                        com.hikari.app.skystream.SkyStreamPluginManager.install(HikariApp.instance, it).getOrThrow()
                    }
                }
                "js" -> bytes?.let {
                    runCatching {
                        com.hikari.app.nuvio.NuvioPluginManager.installScraper(HikariApp.instance, it, file.name).getOrThrow()
                    }
                }
                "apk", "ext" -> bytes?.let {
                    runCatching {
                        com.hikari.app.aniyomi.AniyomiExtensionManager.install(HikariApp.instance, it).getOrThrow()
                    }
                }
                else -> null
            }
            if (result != null || bytes == null) {
                finishInstall(file.nameWithoutExtension, file.absolutePath, result ?: Result.failure(Exception("Could not read the file")))
                return@launch
            }
            var statusText = "Install failed: unknown error"
            var isErr = true
            try {
                val dest = File(extDir, file.name.lowercase().replace(Regex("[^a-z0-9.]"), "-"))
                file.copyTo(dest, overwrite = true)
                val base = file.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ext" }
                val registered = registerExtension(file.nameWithoutExtension, base, dest, "")
                if (registered != null) {
                    statusText = registered
                    isErr = false
                } else {
                    statusText = "Couldn't load ${file.name}: ${HikariPluginManager.lastError ?: Cs3PluginManager.lastError ?: "not an extension"}"
                }
            } catch (t: Throwable) {
                statusText = "Install failed: ${t.message?.take(300) ?: t.javaClass.simpleName}"
            }
            Fx.run {
                busy.isVisible = false
                setStatus(statusText, isErr)
                AppShell.toast(if (isErr) "${file.name} could not be installed" else "${file.name} installed", if (isErr) "error" else "ok")
                renderAll()
            }
            reloadProvidersQuietly()
        }
    }

    // ── installed providers ─────────────────────────────────────────────────

    private fun renderInstalled() {
        val all = runCatching { AppShell.app.store.providers() }.getOrDefault(emptyList())
        val statuses = AppShell.app.providers.statuses.value
        val failed = statuses.count { !it.loaded }
        content.children.add(
            Ui.sectionHeader(
                "Installed",
                "${all.size} extensions" + if (failed > 0) " · $failed failed to load" else "",
                HBox(10.0, installedFilter).apply { alignment = Pos.CENTER_RIGHT },
            )
        )
        fillInstalled()
        content.children.add(installedBox)
    }

    /** Rebuilds only the installed list, so typing in the filter never
     *  re-parents the field (which would steal focus on every keystroke). */
    private fun fillInstalled() {
        installedBox.children.clear()
        val all = runCatching { AppShell.app.store.providers() }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
        val query = installedFilter.text.trim().lowercase()
        val list = all.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        if (all.isEmpty()) {
            installedBox.children.add(
                Ui.emptyState(Icons.EXTENSIONS, "Nothing installed yet", "Add a repo above and install the extensions you want.")
            )
        } else if (list.isEmpty()) {
            installedBox.children.add(themed("No extension matches “$query”.", "tiny"))
        } else {
            list.forEach { installedBox.children.add(installedRow(it)) }
        }
    }

    private fun installedRow(cfg: ProviderConfig): Node {
        val status = AppShell.app.providers.statuses.value.firstOrNull { it.id == cfg.id }
        val failing = status?.takeIf { !it.loaded }

        val icon = VBox(Icons.of(Icons.EXTENSIONS, 17.0)).apply {
            styleClass.add("repo-tile")
            alignment = Pos.CENTER
        }
        val name = themed(cfg.name, "src-name").apply {
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        val source = themed(
            cfg.extra?.substringBefore('|')?.takeIf { it.isNotBlank() }
                ?: cfg.url.takeIf { it.isNotBlank() }
                ?: cfg.type.name.lowercase(),
            "h-mono",
        ).apply { maxWidth = 420.0; minWidth = 0.0 }
        val info = VBox(3.0, name, source).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val badges = HBox(6.0).apply {
            alignment = Pos.CENTER_RIGHT
            children.add(Ui.badge(cfg.type.name, if (cfg.type == ProviderType.HIKARI) "badge-accent" else "badge"))
            children.add(
                if (failing != null) Ui.badge("not loaded", "badge-danger") else Ui.badge("loaded", "badge-ok")
            )
        }

        val toggle = CheckBox("Enabled").apply {
            isSelected = cfg.enabled
            setOnAction {
                runCatching { AppShell.app.store.setEnabled(cfg.id, isSelected) }
                AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                renderAll()
            }
        }
        val reload = Ui.iconButton(Icons.REFRESH, "Reload this extension", 15.0) {
            AppShell.uiScope.launch {
                AppShell.app.providers.refresh()
                Fx.run { renderAll() }
            }
            AppShell.toast("Reloaded providers")
        }
        val remove = Ui.iconButton(Icons.TRASH, "Remove", 15.0) {
            runCatching { AppShell.app.store.removeProvider(cfg.id) }
            if (cfg.url.isNotBlank() && cfg.type in setOf(ProviderType.HIKARI, ProviderType.CS3)) {
                runCatching { File(cfg.url).delete() }
            }
            AppShell.uiScope.launch { AppShell.app.providers.refresh() }
            setStatus("Removed ${cfg.name}")
            renderAll()
        }

        val row = HBox(12.0, icon, info, badges, toggle, reload, remove).apply {
            styleClass.add("src-row")
            alignment = Pos.CENTER_LEFT
        }
        return if (failing == null || failing.error.isNullOrBlank()) {
            row
        } else {
            VBox(6.0, row, themed("⚠ ${failing.error}", "tiny").apply {
                styleClass.add("h-danger")
                isWrapText = true
                maxWidth = 640.0
            })
        }
    }

    // ── misc ────────────────────────────────────────────────────────────────

    private fun setStatus(text: String, isError: Boolean = false, busy: Boolean = false) {
        statusLabel.text = text
        statusLabel.styleClass.remove("h-danger")
        if (isError) statusLabel.styleClass.add("h-danger")
        statusLabel.isWrapText = true
        statusLabel.maxWidth = 900.0
        // The status line is part of a long, scrolling page, so the shell's
        // top-bar chip carries the same text where it is always on screen.
        AppShell.activity(if (busy && !isError) text else null)
    }

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    /**
     * A repo's fetched manifest and parsed plugin list, cached on disk.
     *
     * A repo's contents change rarely, but the Extensions screen exists from the
     * moment the app starts (and used to fetch every repo then). Reading the
     * last-known manifest from here instead makes the screen instant: the cards,
     * names and plugin counts are on the first frame, and the network is only
     * touched for entries that are stale — quietly, in the background.
     */
    private class RepoCache {

        data class Entry(
            val resolved: String,
            val name: String,
            val description: String,
            val plugins: List<PluginRef>,
            val fetchedAt: Long,
        )

        private val dir: File = File(HikariApp.instance.filesDir, "cache/repos").apply { mkdirs() }

        private fun fileFor(url: String): File {
            // Stored by digest, so no repo URL ever has to be a legal file name.
            val hex = java.security.MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return File(dir, "$hex.json")
        }

        fun load(url: String): Entry? = runCatching {
            val f = fileFor(url)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            val plugins = ArrayList<PluginRef>()
            val arr = o.optJSONArray("plugins") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val u = p.optString("url")
                if (u.isBlank()) continue
                plugins.add(
                    PluginRef(
                        name = p.optString("name").ifBlank { u.substringAfterLast('/') },
                        url = u,
                        fileHash = jsonOpt(p, "fileHash"),
                        jarHash = jsonOpt(p, "jarHash"),
                        iconUrl = jsonOpt(p, "iconUrl"),
                    )
                )
            }
            Entry(
                resolved = o.optString("resolved").ifBlank { url },
                name = o.optString("name"),
                description = o.optString("description"),
                plugins = plugins,
                fetchedAt = o.optLong("fetchedAt"),
            )
        }.getOrNull()

        fun save(url: String, entry: Entry) {
            runCatching {
                val arr = JSONArray()
                for (p in entry.plugins) {
                    arr.put(JSONObject().apply {
                        put("name", p.name)
                        put("url", p.url)
                        p.fileHash?.let { put("fileHash", it) }
                        p.jarHash?.let { put("jarHash", it) }
                        p.iconUrl?.let { put("iconUrl", it) }
                    })
                }
                val o = JSONObject().apply {
                    put("resolved", entry.resolved)
                    put("name", entry.name)
                    put("description", entry.description)
                    put("fetchedAt", entry.fetchedAt)
                    put("plugins", arr)
                }
                val f = fileFor(url)
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(o.toString())
                    tmp.delete()
                }
            }
        }

        fun remove(url: String) {
            runCatching { fileFor(url).delete() }
        }

        fun clear() {
            runCatching { dir.listFiles()?.forEach { it.delete() } }
        }

        private fun jsonOpt(o: JSONObject, key: String): String? =
            o.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private companion object {
        /** How long a cached repo is trusted before it is refreshed in the
         *  background. Long on purpose: the point is that opening the app (or
         *  the Extensions screen) never waits on the network. */
        const val REPO_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        const val MODE_HIKARI = 0
        const val MODE_CS3 = 1
        const val MODE_STREMIO = 2
        const val MODE_SCRAPER = 3
        const val MODE_FILE = 4
        const val MODE_SKYSTREAM = 5
        const val MODE_NUVIO = 6
        const val MODE_ANIYOMI = 7
        const val MODE_IPTV = 8

        const val DEFAULT_HIKARI_REPO = "https://github.com/codegeasse1/hikari-extensions"

        val COMPOSER_MODES = listOf(
            "Hikari repo",
            "CloudStream repo",
            "SkyStream repo",
            "Nuvio repo",
            "Aniyomi repo",
            "IPTV playlist",
            "Stremio addon",
            "Universal scraper",
            "File / URL",
        )
        val COMPOSER_PROMPTS = listOf(
            "Hikari repo URL (repo.json)",
            "CloudStream repo.json URL",
            "SkyStream repo URL or shortcode",
            "Nuvio repo URL (manifest.json)",
            "Aniyomi repo URL (index.min.json)",
            "IPTV playlist URL (m3u / m3u8)",
            "Stremio addon manifest URL (…/manifest.json)",
            "Universal scraper JSON config URL",
            "Direct .jar / .cs3 / .hiki / .sky / .js / .apk / .m3u8 URL (or repo.json)",
        )
        val COMPOSER_LABELS = listOf(
            "Add repo", "Add repo", "Add repo", "Add repo", "Add repo", "Add playlist",
            "Add addon", "Add scraper", "Install",
        )

        const val HINT = "Desktop extensions are JVM .jar files — the same code the Android app ships as .hiki. " +
            "Paste a .hiki or .cs3 link anyway: the dex archive is auto-matched to its .jar build, and the repo's " +
            "sha256 signature (when published) is verified before anything is loaded. " +
            "A bundle extension (e.g. Anime) installs each of its sub-extensions separately. " +
            "SkyStream (.sky), Nuvio (.js) and Aniyomi (.apk) extensions run natively here too, and an " +
            "m3u/m3u8 playlist becomes an IPTV provider."
    }
}
