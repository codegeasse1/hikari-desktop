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
import javafx.scene.input.KeyCode
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
    )

    private val repoData = HashMap<String, RepoData>()

    /** Built once so the add-source option toggles survive re-renders. */
    private val addGrid = addOptionsGrid()

    /** The repo whose plugin list is currently shown (folder drill-down). */
    private var openRepo: Cs3Repo? = null

    /** Per-repo-plugin install outcome, shown right under its row so a failed
     *  install says why where the user is looking. Keyed by plugin URL. */
    private val installErrors = HashMap<String, String>()

    private fun setStatus(text: String, isError: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = "-fx-text-fill: ${if (isError) "#ff8f8f" else Theme.FG_DIM};"
    }

    init {
        busy.isVisible = false
        renderAll()
    }

    private fun Section(title: String, body: Node): VBox =
        VBox(8.0, Theme.label(title, size = 16.0, bold = true), body)

    private fun renderAll() {
        content.children.clear()
        content.children.add(
            HBox(12.0, Theme.label("Extensions", size = 26.0, bold = true), busy).apply {
                alignment = Pos.CENTER_LEFT
            }
        )
        val repo = openRepo
        if (repo != null) renderRepoDetail(repo) else renderMain()
        content.children.add(statusLabel)
    }

    /** Main view: add-sources grid, repo folders, installed list. */
    private fun renderMain() {
        content.children.add(addGrid)
        renderRepos()
        content.children.add(Section("Extension repos", reposBox))
        renderInstalled()
        content.children.add(Section("Installed extensions", installedBox))
    }

    /** Drill-down view: one repo's plugins. Clicking a repo folder lands here. */
    private fun renderRepoDetail(repo: Cs3Repo) {
        val data = repoData[repo.url]
        val back = Button("← Back").apply {
            styleClass.add("btn")
            setOnAction {
                openRepo = null
                renderAll()
            }
        }
        val refresh = Button("↻ Reload").apply {
            styleClass.add("btn")
            setOnAction { refreshRepo(repo.url) }
        }
        val remove = Button("🗑 Remove repo").apply {
            styleClass.addAll("btn", "btn-danger")
            setOnAction {
                AppShell.app.store.removeCs3Repo(repo.url)
                repoData.remove(repo.url)
                if (openRepo?.url == repo.url) openRepo = null
                renderAll()
            }
        }
        val title = Theme.label(data?.name?.ifBlank { null } ?: repoDisplayName(repo), size = 20.0, bold = true)
        content.children.add(
            HBox(10.0, back, title, refresh, remove).apply {
                alignment = Pos.CENTER_LEFT
            }
        )

        val pluginsBox = VBox(8.0)
        when {
            data == null -> pluginsBox.children.add(Theme.label("Loading plugins…", dim = true))
            data.plugins.isEmpty() ->
                pluginsBox.children.add(Theme.label("No installable plugins found in this repo.", dim = true))
            else -> {
                if (data.description.isNotBlank()) {
                    pluginsBox.children.add(Theme.label(data.description, size = 11.5, dim = true))
                }
                data.plugins.forEach { pr ->
                    val info = VBox(2.0).apply {
                        children.add(Theme.label(pr.name, size = 14.0, bold = true))
                        children.add(Theme.label(pr.url, size = 10.5, dim = true))
                    }
                    val installed = providersFor(pr.url)
                    val btn = Button(if (installed.isEmpty()) "Install" else "Uninstall (${installed.size})").apply {
                        styleClass.addAll("btn", if (installed.isEmpty()) "btn-primary" else "btn-danger")
                        setOnAction {
                            if (installed.isEmpty()) {
                                installPlugin(pr.name, pr.url, pr.fileHash, pr.jarHash)
                            } else {
                                uninstallPlugin(pr.name, pr.url)
                            }
                        }
                    }
                    val row = HBox(10.0, info, btn).apply {
                        VBox.setVgrow(info, Priority.ALWAYS)
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("list-row")
                    }
                    val cell = VBox(4.0).apply {
                        children.add(row)
                        // Per-plugin result — an install that failed must say
                        // WHY right where the button is, not only in the tiny
                        // status line at the bottom of the page.
                        installErrors[pr.url]?.let { err ->
                            children.add(
                                Theme.label("⚠ $err", size = 11.0, dim = true)
                                    .apply { style = style + "; -fx-text-fill: #ff9a9a;" }
                            )
                        }
                    }
                    pluginsBox.children.add(cell)
                }
            }
        }
        content.children.add(pluginsBox)
    }

    // ── Add-sources grid (Android-style option list) ────────────────────────

    private fun addOptionsGrid(): VBox {
        fun option(text: String, primary: Boolean, input: HBox): VBox {
            val inputRow = input.apply { isVisible = false; isManaged = false }
            val btn = Button(text).apply {
                styleClass.addAll("btn", if (primary) "btn-primary" else "")
                maxWidth = Double.MAX_VALUE
                setOnAction {
                    // Always reveal the row — never hide a row the user filled in.
                    inputRow.isVisible = true
                    inputRow.isManaged = true
                    inputRow.lookupAll(".field").firstOrNull()?.let { it.requestFocus() }
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
                "The “Install .jar” options are just the direct form of that. " +
                "A bundle extension (e.g. Anime) installs each of its sub-extensions separately.",
            size = 11.5, dim = true,
        ).apply { isWrapText = true }
        return VBox(8.0, grid, hint)
    }

    private fun urlInput(prompt: String, buttonText: String, initial: String = "", onGo: (String) -> Unit): HBox {
        val input = TextField().apply {
            styleClass.add("field")
            promptText = prompt
            text = initial
            // Wide enough that long repo/plugin URLs are fully visible instead
            // of clipped with a hidden horizontal scroll.
            prefWidth = 360.0
            minWidth = 200.0
            HBox.setHgrow(this, Priority.ALWAYS)
            // Selecting the whole URL on focus shows the tail (the meaningful
            // part) even in a narrow window, and makes retyping/retrying easy.
            focusedProperty().addListener { _, _, focused -> if (focused) selectAll() }
            // Enter submits.
            setOnAction { onGo(text) }
            // Pasting a URL submits immediately — the user never has to hunt
            // for the button (the button is there too, and can't collapse).
            setOnKeyReleased { e ->
                val pasted = (e.code == KeyCode.V && e.isControlDown) ||
                    (e.code == KeyCode.INSERT && e.isShiftDown)
                if (pasted && text.isNotBlank()) onGo(text)
            }
        }
        val btn = Button(buttonText).apply {
            styleClass.addAll("btn", "btn-primary")
            // Never let layout shrink the label to a clipped "…" — the button
            // must always read as the submit action.
            minWidth = 110.0
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
    ) { addRepo(Http.normalizeUrl(it), com.hikari.app.data.RepoKind.HIKARI) }

    private fun csRepoInput(): HBox = urlInput("repo.json URL (CloudStream)", "Add repo") { addRepo(Http.normalizeUrl(it), com.hikari.app.data.RepoKind.CS3) }

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
            renderAll()
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
            renderAll()
            setStatus("Scraper added: $name")
        }
    }

    private fun hikiUrlInput(): HBox = urlInput("Direct .hiki URL (installs its .jar)", "Install") { installFromUrl(it) }
    private fun cs3UrlInput(): HBox = urlInput("Direct .cs3 URL (installs its .jar)", "Install") { installFromUrl(it) }
    private fun jarUrlInput(): HBox = urlInput("Direct .jar URL (GitHub raw / jsDelivr / Drive)", "Install") { installFromUrl(it) }

    // ── Repos as folders (Android-style: click a folder to go inside) ───────

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

    private fun repoFolder(repo: Cs3Repo): HBox {
        val folder = Button("📁  " + repoDisplayName(repo)).apply {
            styleClass.add("repo-folder")
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
            setOnAction {
                openRepo = repo
                if (repoData[repo.url] == null) loadRepoData(repo.url)
                renderAll()
            }
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
                if (openRepo?.url == repo.url) openRepo = null
                renderAll()
            }
        }
        return HBox(6.0, folder, refresh, remove).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun refreshRepo(url: String) {
        repoData.remove(url)
        loadRepoData(url)
    }

    private fun loadRepoData(url: String) {
        busy.isVisible = true
        setStatus("Fetching repo…")
        AppShell.uiScope.launch {
            val result = Http.fetchRepoJson(url) { step -> Fx.run { setStatus(step) } }
            Fx.run {
                busy.isVisible = false
                val pair = result.getOrNull()
                if (pair == null) {
                    setStatus(
                        "Couldn't load that repo — ${Http.humanMessage(result.exceptionOrNull())}",
                        isError = true,
                    )
                    return@run
                }
                val resolved = pair.first
                val text = pair.second
                val root = runCatching { JSONObject(text) }.getOrNull()
                val name = root?.optString("name").orEmpty().ifBlank { Http.repoDisplayName(resolved) }
                val description = root?.optString("description").orEmpty()
                val plugins = parsePlugins(root, resolved)
                repoData[url] = RepoData(resolved, name, description, plugins)
                if (resolved != url) repoData[resolved] = repoData[url]!!
                setStatus("")
                renderAll()
            }
        }
    }

    /** Reads the repo's `plugins` array, resolving relative plugin URLs
     *  against the repo.json ([baseUrl] — the URL that actually served it),
     *  converting .hiki plugin URLs to their .jar desktop builds, and keeping
     *  the repo's sha256 signatures for post-download verification.
     *  `plugins` is already a JSONArray — org.json's `new JSONArray(jsonArray)`
     *  throws, so never re-wrap an existing array. */
    private fun parsePlugins(root: JSONObject?, baseUrl: String = ""): List<PluginRef> {
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
            // CloudStream v2 plugin lists may carry paths relative to the
            // plugins.json/repo.json that referenced them — resolve them the
            // way the Android client does instead of handing OkHttp a
            // scheme-less URL it must reject.
            if (baseUrl.isNotBlank()) url = Http.resolveRelativeTo(url, baseUrl)
            if (url.endsWith(".hiki")) url = url.removeSuffix(".hiki") + ".jar"
            out += PluginRef(
                name = name,
                url = url,
                fileHash = p.optString("fileHash").ifBlank { null },
                jarHash = p.optString("jarHash").ifBlank { null },
            )
        }
        return out
    }

    /** Validates + stores a repo, then opens its folder so the plugins appear
     *  immediately (Android behaviour). [kind] is the row the user pressed
     *  (Hikari repo vs CloudStream repo); a CloudStream v2 manifest
     *  (`pluginLists`) is always stored as CS3 even if pasted into the Hikari
     *  row, so the kind always reflects what the repo actually is. */
    private fun addRepo(url: String, kind: com.hikari.app.data.RepoKind) {
        if (url.isBlank()) return
        busy.isVisible = true
        setStatus("Checking $url…")
        AppShell.uiScope.launch {
            val result = Http.fetchRepoJson(url) { step -> Fx.run { setStatus(step) } }
            Fx.run {
                busy.isVisible = false
                val pair = result.getOrNull()
                if (pair == null) {
                    setStatus(
                        "Couldn't load that repo — ${Http.humanMessage(result.exceptionOrNull())}",
                        isError = true,
                    )
                    return@run
                }
                val resolved = pair.first
                val name = Http.repoDisplayName(resolved)
                val root = runCatching { JSONObject(pair.second) }.getOrNull()
                // The two official repos are both served from the same CDN host
                // (user.uploads.dev), so display-name matching ALONE would treat
                // the CloudStream repo as the already-added Hikari repo and just
                // open it instead of adding it. Match the kind too — a Hikari
                // repo can never satisfy a CloudStream add (or vice-versa).
                val effKind = if (root?.has("pluginLists") == true) {
                    com.hikari.app.data.RepoKind.CS3
                } else {
                    kind
                }
                val existing = AppShell.app.store.repos().firstOrNull {
                    it.url == resolved ||
                        (it.kind == effKind && Http.repoDisplayName(it.url) == name)
                }
                if (existing != null && existing.url != resolved) {
                    // Same repo (or same kind+name) but a DIFFERENT link: replace the
                    // stored entry so it picks up the new link instead of re-opening the
                    // old one (otherwise adding a fresh Hikari link kept showing the stale
                    // repo). Same link still just opens it.
                    AppShell.app.store.removeCs3Repo(existing.url)
                    repoData.remove(existing.url)
                }
                if (existing != null && existing.url == resolved) {
                    openRepo = existing
                    if (repoData[existing.url] == null) loadRepoData(existing.url)
                    renderAll()
                    setStatus("That repo is already added — opened it.", isError = true)
                    return@run
                }
                AppShell.app.store.addCs3Repo(
                    Cs3Repo(url = resolved, name = name, kind = effKind)
                )
                // Seed the cache with the already-fetched data and open it.
                repoData[resolved] = RepoData(
                    url = resolved,
                    name = root?.optString("name").orEmpty().ifBlank { name },
                    description = root?.optString("description").orEmpty(),
                    plugins = parsePlugins(root, resolved),
                )
                openRepo = Cs3Repo(url = resolved, name = name, kind = effKind)
                setStatus("")
                renderAll()
            }
        }
    }

    // ── Installing / uninstalling plugins ───────────────────────────────────

    private fun installFromUrl(raw: String) {
        val url = Http.normalizeUrl(raw)
        if (url.isBlank()) return
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "ext.jar" }
        val name = fileName
            .removeSuffix(".hiki").removeSuffix(".cs3").removeSuffix(".jar")
            .replace(Regex("[-_.]+"), " ").trim().ifBlank { "Extension" }
        installPlugin(name, url)
    }

    /** Providers installed from a given repo plugin URL (bundle providers all
     *  share the download URL as their extra prefix). Covers both Hikari jars
     *  and CloudStream .cs3 installs — both record the source URL in extra. */
    private fun providersFor(url: String): List<ProviderConfig> =
        AppShell.app.store.providers().filter {
            it.type in setOf(ProviderType.HIKARI, ProviderType.CS3) &&
                it.extra?.startsWith("$url|") == true
        }

    /** Downloads (auto-swapping .hiki → .jar), loads, and registers every
     *  provider inside the extension. Every candidate is verified (zip header
     *  + the repo's sha256 when it publishes one) BEFORE it is loaded, and a
     *  bad candidate falls through to the next one. Every path ends with a
     *  visible status — nothing ever fails silently. */
    private fun installPlugin(name: String, url: String, fileHash: String? = null, jarHash: String? = null) {
        var dl = url
        if (dl.endsWith(".hiki")) dl = dl.removeSuffix(".hiki") + ".jar"
        installErrors.remove(url)
        busy.isVisible = true
        setStatus("Installing $name…")
        AppShell.uiScope.launch {
            var statusText = "Install failed: unknown error"
            var isErr = true
            try {
                val safeName = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ext" }
                val dest = File(extDir, "$safeName.jar")
                dest.delete()
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
                    val ok = Http.downloadToRobust(c, dest) { tried, success, reason ->
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
                    // Record the row's primary URL (not whichever sibling
                    // format arrived) so the Install/Uninstall state and
                    // providersFor() keep matching this row afterwards.
                    val result = registerExtension(name, safeName, dest, dl)
                    if (result != null) {
                        registered = result
                        break
                    }
                    loadFailure = HikariPluginManager.lastError ?: Cs3PluginManager.lastError ?: "not an extension"
                    attempts.append("${c.substringAfterLast('/')} — not loadable (${loadFailure?.take(90)})\n")
                }
                if (registered != null) {
                    statusText = registered!!
                    isErr = false
                } else if (loadFailure != null && attempts.indexOf("not loadable") >= 0) {
                    statusText = "Couldn't load $name: ${loadFailure!!.take(300)}"
                } else {
                    statusText = "Download failed for $name — no mirror served the file:" +
                        (if (attempts.isEmpty()) " (no reasons reported)" else "\n$attempts") +
                        "If several hosts above say “blocked”/“SSL”, your network or DNS filters GitHub-family hosts — the ghfast.top/ghproxy.net rows should still get through; if nothing does, use a VPN or a repo that mirrors plugins (the built-in Hikari repo does)."
                }
            } catch (t: Throwable) {
                statusText = "Install failed: ${t.message?.take(300) ?: t.javaClass.simpleName}"
            }
            AppShell.app.providers.refresh()
            Fx.run {
                busy.isVisible = false
                if (isErr) installErrors[url] = statusText.take(700) else installErrors.remove(url)
                setStatus(statusText, isErr)
                renderAll()
            }
        }
    }

    /** Null = the downloaded file is a plausible, untampered extension; a
     *  string says why it isn't. Checks the zip (PK) header and — when the
     *  repo publishes one — the sha256 for the downloaded format (fileHash
     *  for dex archives, jarHash for JVM jars; the Android app's rule). */
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
        if (want.length != 64) return null // not a sha256-<hex> value — nothing to enforce
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
            // A .cs3 can register several MainAPIs (one plugin archive, many
            // providers) — register each with its own config so every one is
            // individually disableable/uninstallable, and record the source
            // URL in extra (matches providersFor, so the repo row flips to
            // "Uninstall" after a successful install).
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
        matches.forEach { AppShell.app.store.removeProvider(it.id) }
        val file = matches.first().url.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists()) file.delete()
        AppShell.uiScope.launch { AppShell.app.providers.refresh() }
        setStatus("Uninstalled $name (${matches.size} extension${if (matches.size > 1) "s" else ""}).")
        renderAll()
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
            AppShell.app.providers.refresh()
            Fx.run {
                busy.isVisible = false
                setStatus(statusText, isErr)
                renderAll()
            }
        }
    }

    // ── Installed providers ─────────────────────────────────────────────────

    fun onShown() {
        renderAll()
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
                renderAll()
            }
            val remove = Button("Remove").apply {
                styleClass.addAll("btn", "btn-danger")
                setOnAction {
                    AppShell.app.store.removeProvider(cfg.id)
                    if (cfg.url.isNotBlank() && cfg.type in setOf(ProviderType.HIKARI, ProviderType.CS3)) {
                        runCatching { File(cfg.url).delete() }
                    }
                    AppShell.uiScope.launch { AppShell.app.providers.refresh() }
                    renderAll()
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
