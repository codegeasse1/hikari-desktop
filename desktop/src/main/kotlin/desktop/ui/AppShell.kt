package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.ui.theme.HikariThemeMode
import desktop.fx.Fx
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object Explore : Screen()
    object Search : Screen()
    object History : Screen()
    object Library : Screen()
    object Downloads : Screen()
    object Extensions : Screen()
    object Settings : Screen()
    data class Detail(val item: MediaItem) : Screen()
    data class Catalog(val title: String, val provider: String, val items: List<MediaItem>) : Screen()
}

/**
 * The application shell: a desktop-style left sidebar for navigation, a top bar
 * that carries the screen title, a global search box and the window controls,
 * and a content area that swaps one screen at a time. Also owns the shared
 * coroutine scope every screen launches work on, the toast host, and a small
 * screen history so "back" returns to where the user came from.
 */
object AppShell {

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var chrome: WindowChrome
    private lateinit var centerStack: StackPane
    private lateinit var titleLabel: Label
    private lateinit var subtitleLabel: Label
    private lateinit var toasts: Ui.Toasts
    private lateinit var navButtons: MutableList<Pair<Screen, Button>>
    private lateinit var themeButton: Button
    private lateinit var backButton: Button
    private lateinit var globalSearch: TextField
    private lateinit var activityBox: HBox
    private lateinit var activitySpinner: javafx.scene.control.ProgressIndicator
    private lateinit var activityLabel: Label
    private lateinit var playerLayer: StackPane
    private lateinit var windowStage: javafx.stage.Stage
    /** The floating side-rail dock, so discovery can take it over. */
    private var railHost: Region? = null
    private var currentScreen: Screen = Screen.Home
    private val backStack = java.util.ArrayDeque<Screen>()

    private val homeScreen = HomeScreenView()
    private val exploreScreen = ExploreScreenView()
    private val searchScreen = SearchScreenView()
    private val historyScreen = HistoryScreenView()
    private val libraryScreen = LibraryScreenView()
    private val downloadsScreen = DownloadsScreenView()
    private val extensionsScreen = ExtensionsScreenView()
    private val settingsScreen = SettingsScreenView()

    fun create(stage: javafx.stage.Stage): Region {
        windowStage = stage
        chrome = WindowChrome(stage)

        centerStack = StackPane().apply {
            styleClass.add("content-host")
            // The content host is the one place that decides how small the
            // window may get: pinned to 0/MAX, so no screen can inflate the
            // window's minimum size (a page of rails otherwise would).
            minWidth = 0.0
            minHeight = 0.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }
        val overlay = StackPane().apply { isMouseTransparent = true }
        toasts = Ui.Toasts(overlay)

        val body = BorderPane().apply {
            styleClass.add("app-root")
            left = sidebar().also { railHost = it }
            top = topbar()
            center = StackPane(centerStack, overlay)
        }

        // The player is an in-app layer, not a second window: it covers the
        // whole window while it is mounted, so starting a stream reads as the
        // app switching to a player view, with mpv rendering into a surface
        // this app owns (see desktop/player/WinShell.kt).
        playerLayer = StackPane().apply {
            styleClass.add("player-layer")
            minWidth = 0.0
            minHeight = 0.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isVisible = false
            isManaged = false
        }

        val root = chrome.build(body, playerLayer)
        root.sceneProperty().addListener { _, _, scene ->
            if (scene != null) {
                installShortcuts(scene)
                // The scene only exists once Main attaches it, so the saved theme
                // and accent classes have to be (re)applied here — show() already
                // ran before this point on first launch.
                Theme.refresh()
            }
        }
        installDownloadQueue()
        show(Screen.Home, remember = false)
        return root
    }

    /**
     * Wires the download queue into the shell: the persisted queue is read once
     * at launch (so paused downloads survive a restart), the user's concurrency
     * setting is applied, and a finished task raises a toast wherever the user
     * happens to be in the app — the queue keeps running on its own scope, not
     * tied to the Downloads screen being open.
     */
    private fun installDownloadQueue() {
        com.hikari.app.download.DownloadsRepository.onTaskFinished = { task ->
            when (task.status) {
                com.hikari.app.download.DownloadStatus.DONE ->
                    toast("Downloaded ${task.title}", "ok")
                com.hikari.app.download.DownloadStatus.FAILED ->
                    toast("Download failed: ${task.title} — ${task.error ?: "unknown error"}", "error")
                else -> Unit
            }
        }
        uiScope.launch {
            runCatching {
                val repo = com.hikari.app.download.DownloadsRepository
                repo.setMaxConcurrent(app.store.downloadConcurrency())
                repo.ensureLoaded()
            }
        }
    }

    private fun sidebar(): Region {
        val box = VBox(6.0).apply {
            styleClass.add("x-side")
            minWidth = 68.0
            prefWidth = 68.0
            maxWidth = 68.0
        }

        val version = runCatching { "v${desktop.Build.VERSION} · ${desktop.Build.COMMIT}" }.getOrDefault("Hikari")
        // The Hikari mark: the app icon itself, clipped round, in the logo
        // slot — with the gradient tile as the fallback when the resource is
        // missing (a logo that fails to load must never blank the rail).
        val markGraphic: javafx.scene.Node = runCatching {
            val stream = AppShell::class.java.getResourceAsStream("/hikari.png")
                ?: return@runCatching null
            val img = javafx.scene.image.Image(stream)
            if (img.isError || img.width <= 0.0) return@runCatching null
            javafx.scene.image.ImageView(img).apply {
                fitWidth = 32.0
                fitHeight = 32.0
                isPreserveRatio = true
                isSmooth = true
                clip = javafx.scene.shape.Rectangle(32.0, 32.0).apply {
                    arcWidth = 10.0
                    arcHeight = 10.0
                }
            }
        }.getOrNull() ?: Region().apply { styleClass.add("x-side-mark") }
        val mark = Button().apply {
            styleClass.add("x-side-logo")
            graphic = markGraphic
            isFocusTraversable = false
            setOnAction { show(Screen.Home) }
        }
        // Tooltips here are safe: nothing overlaps the rail, so no popup can
        // ever eat a click meant for a button under it.
        Ui.tooltip(version).let { mark.tooltip = it }
        box.children.add(HBox(mark).apply {
            alignment = Pos.CENTER
            padding = Insets(10.0, 0.0, 12.0, 0.0)
        })

        navButtons = mutableListOf()
        fun nav(screen: Screen, icon: String, text: String) {
            val button = Button().apply {
                styleClass.addAll("nav-btn", "x-side-btn")
                graphic = Icons.of(icon, 20.0)
                accessibleText = text
                isFocusTraversable = false
                setOnAction { show(screen) }
            }
            Ui.tooltip(text).let { button.tooltip = it }
            navButtons.add(screen to button)
            // Every tab owns an identical slot and sits dead-center in it, so
            // no icon can drift high/low of its selected box.
            box.children.add(HBox(button).apply {
                alignment = Pos.CENTER
                minHeight = 52.0
                prefHeight = 52.0
                maxHeight = 52.0
            })
        }

        nav(Screen.Home, Icons.HOME, "Home")
        nav(Screen.Explore, Icons.EXPLORE, "Explore")
        nav(Screen.Search, Icons.SEARCH, "Search")
        nav(Screen.Library, Icons.LIBRARY, "Library")
        nav(Screen.Downloads, Icons.DOWNLOAD, "Downloads")
        nav(Screen.History, Icons.HISTORY, "History")

        box.children.add(Region().apply {
            styleClass.add("x-side-sep")
            minHeight = 1.0
            maxHeight = 1.0
            HBox.setMargin(this, Insets(8.0, 16.0, 8.0, 16.0))
        })
        nav(Screen.Extensions, Icons.EXTENSIONS, "Extensions")
        nav(Screen.Settings, Icons.SETTINGS, "Settings")

        val grow = Region().apply { VBox.setVgrow(this, Priority.ALWAYS) }
        box.children.add(grow)
        // Floating pill dock (reference shot 775): the rail floats off the
        // window edges with a gap all round instead of docking flush
        // full-height — same buttons, detached pill.
        VBox.setVgrow(box, Priority.ALWAYS)
        return VBox(box).apply {
            styleClass.add("x-side-float")
            padding = Insets(12.0, 0.0, 12.0, 12.0)
            minWidth = 92.0
            prefWidth = 92.0
            maxWidth = 92.0
            minHeight = 0.0
        }
    }

    private fun topbar(): Region {
        titleLabel = Theme.label("", size = 13.5, bold = true).apply { styleClass.add("topbar-title") }
        subtitleLabel = Theme.label("", size = 11.5, dim = true).apply { styleClass.add("topbar-sub") }
        val titles = VBox(0.0, titleLabel, subtitleLabel).apply { isPickOnBounds = true }

        val searchBox = Ui.searchInput("Search movies, shows, anime…", 268.0)
        globalSearch = searchBox.children[0] as TextField
        globalSearch.setOnAction { runGlobalSearch() }
        // Not focus-traversable so it can't steal focus on launch (or on Tab),
        // and so the search screen can never be pushed in front of the screen
        // the user just opened. Clicking it still focuses it; Ctrl+K focuses it.
        globalSearch.isFocusTraversable = false

        themeButton = Ui.iconButton(Icons.MOON, "Toggle light / dark", size = 16.0) { toggleTheme() }

        // A single always-visible place for "something is happening": an
        // extension install finishes at the bottom of a long scrolling page,
        // which is exactly where the user is not looking. Anything long-running
        // calls AppShell.activity("Installing Foo…").
        activitySpinner = javafx.scene.control.ProgressIndicator().apply {
            styleClass.add("spinner")
            prefWidth = 15.0
            prefHeight = 15.0
            minWidth = 15.0
            minHeight = 15.0
            maxWidth = 15.0
            maxHeight = 15.0
        }
        activityLabel = Theme.label("", size = 11.5, dim = true).apply { styleClass.add("topbar-activity") }
        activityBox = HBox(7.0, activitySpinner, activityLabel).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("activity-chip")
            isVisible = false
            isManaged = false
        }

        // A nested screen (a title, a catalog) needs an obvious way out that is
        // not the sidebar: the banner's own arrow scrolls with the page, so the
        // shell carries one that is always on screen. It steps back exactly one
        // screen (see [back]) — never straight to Home.
        backButton = Ui.button("Back", icon = Icons.CHEVRON_LEFT, ghost = true) { back() }.apply {
            styleClass.add("topbar-back")
            isVisible = false
            isManaged = false
        }
        val left = HBox(10.0, Ui.isolateClicks(backButton), titles).apply { alignment = Pos.CENTER_LEFT }
        left.padding = Insets(0.0, 0.0, 0.0, 14.0)
        left.minWidth = 150.0
        // Only the titles drag the window. The drag handler used to cover the
        // whole left cluster, including the Back button — a 1px mouse movement
        // on press turned a click into a window drag, so Back looked dead.
        chrome.makeDraggable(titles)
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val actions = HBox(2.0, activityBox, searchBox, themeButton).apply { alignment = Pos.CENTER_RIGHT }
        return HBox(left, spacer, actions, chrome.controls()).apply {
            styleClass.add("topbar")
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun runGlobalSearch() {
        val query = globalSearch.text.trim()
        if (query.isEmpty()) return
        show(Screen.Search)
        searchScreen.searchWith(query)
    }

    private fun installShortcuts(scene: Scene) {
        scene.accelerators[KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN)] = Runnable { openSearch() }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Home) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Search) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Library) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Downloads) }
        // Escape closes an in-page overlay first (sources sheet, discovery),
        // and only goes back when there is nothing to close.
        scene.accelerators[KeyCodeCombination(KeyCode.ESCAPE)] = Runnable {
            if (detailCloser?.invoke() != true) back()
        }
        // "/" focuses global search — but never while the user is typing in a
        // field, where "/" is text, not a shortcut.
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED) { e ->
            if (e.code == KeyCode.SLASH && !e.isControlDown && !e.isAltDown && !e.isMetaDown) {
                val t = e.target
                if (t is javafx.scene.control.TextInputControl) return@addEventFilter
                openSearch()
                e.consume()
            }
        }
    }

    private fun openSearch() {
        show(Screen.Search)
        if (::globalSearch.isInitialized) globalSearch.requestFocus()
    }

    private fun toggleTheme() {
        Theme.setMode(if (Theme.mode == HikariThemeMode.LIGHT) HikariThemeMode.DARK else HikariThemeMode.LIGHT)
        refreshThemeButton()
    }

    private fun refreshThemeButton() {
        if (::themeButton.isInitialized) {
            themeButton.graphic = Icons.of(
                if (Theme.mode == HikariThemeMode.LIGHT) Icons.MOON else Icons.SUN,
                16.0,
            )
        }
    }

    fun toast(message: String, kind: String = "") {
        // Any background thread may report something worth a toast (a finished
        // download, an extension install), so hop to the FX thread here rather
        // than at every call site.
        desktop.fx.Fx.run {
            if (::toasts.isInitialized) toasts.show(message, kind)
        }
    }

    /**
     * Shows — or, with null, hides — the busy chip in the top bar.
     *
     * Long-running work (installing an extension, adding a repo, reloading all
     * providers) reports itself here so the user always has a visible answer to
     * "is anything happening?", wherever they have scrolled to. Safe to call
     * from any thread.
     *
     * The chip ALWAYS expires on its own (12s): a code path that reports work
     * and then hangs — a loader that never returns — used to leave its label
     * ("Installed X", "Fetching repo…") in the top bar for the rest of the
     * session. Genuinely long work re-reports as it goes; a stale label is
     * worse than none.
     */
    private var activityGen = 0
    fun activity(text: String?) {
        val label = text?.takeIf { it.isNotBlank() }
        val gen = ++activityGen
        desktop.fx.Fx.run {
            if (::activityBox.isInitialized) {
                if (label != null) activityLabel.text = label
                activityBox.isVisible = label != null
                activityBox.isManaged = label != null
            }
        }
        if (label != null) {
            uiScope.launch {
                kotlinx.coroutines.delay(12_000L)
                if (activityGen == gen) activity(null)
            }
        }
    }

    /**
     * Closes the topmost in-page overlay (the detail screen's sources sheet or
     * discovery), for Escape. Set by the detail screen while it is up, cleared
     * whenever another screen shows. True when something was closed.
     */
    var detailCloser: (() -> Boolean)? = null

    /**
     * The live catalog view, when one is on screen: the Browse page fills it
     * the moment the provider's rows arrive (see ExploreScreen.openCatalog).
     * Cleared on every navigation away, so a late load never fills a screen
     * the user already left.
     */
    var catalogView: CatalogScreenView? = null

    fun show(screen: Screen, remember: Boolean = true) {
        if (remember && screen != currentScreen) {
            backStack.addLast(currentScreen)
            while (backStack.size > 24) backStack.pollFirst()
        }
        currentScreen = screen
        // A takeover state (source discovery) hides the side rail; leaving its
        // screen must never leave the rail hidden behind.
        setRailVisible(true)
        // An overlay closer belongs to the screen that registered it: leaving
        // the detail screen must not leave its closer behind.
        if (screen !is Screen.Detail) detailCloser = null
        // The live catalog view belongs to its screen the same way: a load
        // finishing after the user left must find nothing to fill.
        if (screen !is Screen.Catalog) catalogView = null
        Theme.refresh()
        val node = try {
            viewFor(screen)
        } catch (t: Throwable) {
            // A screen that fails to build must never leave the content area
            // blank (or silently keep the previous screen) with the exception
            // lost in the event dispatch — show the failure instead.
            errorView(screen, t)
        }
        if (node is Region) Ui.fill(node)
        centerStack.children.clear()
        centerStack.children.add(node)
        Ui.fadeIn(node)
        titleLabel.text = titleFor(screen)
        subtitleLabel.text = subtitleFor(screen)
        when (screen) {
            is Screen.Home -> homeScreen.onShown()
            is Screen.Explore -> exploreScreen.onShown()
            is Screen.Search -> searchScreen.onShown()
            is Screen.History -> historyScreen.onShown()
            is Screen.Library -> libraryScreen.onShown()
            is Screen.Downloads -> downloadsScreen.onShown()
            is Screen.Extensions -> extensionsScreen.onShown()
            is Screen.Settings -> settingsScreen.onShown()
            is Screen.Detail -> Unit
            is Screen.Catalog -> Unit
        }
        refreshNav()
    }

    private fun errorView(screen: Screen, t: Throwable): Region {
        AppShell.toast("Couldn't open ${titleFor(screen)}: ${t.message ?: t.javaClass.simpleName}", "error")
        return Ui.vScroll(
            Ui.emptyState(
                Icons.ERROR,
                "This screen failed to open",
                (t.message ?: t.javaClass.simpleName) + "\n\n" + t.stackTrace.take(6).joinToString("\n") { "at $it" },
            )
        )
    }

    /** The screen currently on screen. Read by the UI tests, which have to be
     *  able to tell "the arrow navigated" from "the arrow did nothing". */
    val current: Screen get() = currentScreen

    /** Returns to the previously shown screen (Escape / the in-page back arrow). */
    fun back() {
        var previous = backStack.pollLast()
        // Never step back into the screen we are already looking at: a Detail
        // screen opened from another Detail screen would otherwise make the arrow
        // a visible no-op ("the back button does not work").
        var guard = 0
        while (previous == currentScreen && guard++ < 24) previous = backStack.pollLast()
        show(previous ?: Screen.Home, remember = false)
    }

    fun openDetail(item: MediaItem) {
        show(Screen.Detail(item))
    }

    /**
     * Discovery and other full-takeover states hide the side rail so only the
     * top bar's Back button stays on screen. Restored when they resolve.
     */
    fun setRailVisible(visible: Boolean) {
        Fx.run {
            railHost?.let {
                it.isVisible = visible
                it.isManaged = visible
            }
        }
    }

    /** Jumps straight to Settings → Logs and diagnostics (Home's Logs button). */
    fun openSettingsLogs() {
        settingsScreen.revealLogsOnShown = true
        show(Screen.Settings)
    }

    fun goHome() {
        show(Screen.Home)
    }

    private fun viewFor(screen: Screen): Node = when (screen) {
        is Screen.Home -> homeScreen.root
        is Screen.Explore -> exploreScreen.root
        is Screen.Search -> searchScreen.root
        is Screen.History -> historyScreen.root
        is Screen.Library -> libraryScreen.root
        is Screen.Downloads -> downloadsScreen.root
        is Screen.Extensions -> extensionsScreen.root
        is Screen.Settings -> settingsScreen.root
        is Screen.Detail -> DetailScreenView(screen.item).root
        is Screen.Catalog -> CatalogScreenView(screen.title, screen.provider, screen.items)
            .also { catalogView = it }.root
    }

    private fun titleFor(screen: Screen): String = when (screen) {
        is Screen.Home -> "Home"
        is Screen.Explore -> "Explore"
        is Screen.Search -> "Search"
        is Screen.History -> "History"
        is Screen.Library -> "Library"
        is Screen.Downloads -> "Downloads"
        is Screen.Extensions -> "Extensions"
        is Screen.Settings -> "Settings"
        // The detail screen's own banner carries the title, so the top bar shows
        // where the title came from instead of repeating it.
        is Screen.Detail -> providerName(screen.item.providerId)
        is Screen.Catalog -> screen.title
    }

    private fun subtitleFor(screen: Screen): String = when (screen) {
        is Screen.Home -> "Catalogs from every enabled source"
        is Screen.Explore -> "Browse catalogs across providers"
        is Screen.Search -> "Search every installed provider at once"
        is Screen.History -> "Everything you have watched"
        is Screen.Library -> "Favourites and continue watching"
        is Screen.Downloads -> "Offline copies and exports"
        is Screen.Extensions -> "Addons, scrapers and plugin repositories"
        is Screen.Settings -> "Appearance, network and data"
        is Screen.Detail -> when (screen.item.type.name.lowercase()) {
            "series" -> "Series · details, episodes and sources"
            "movie" -> "Movie · details and sources"
            else -> "Details and sources"
        }
        is Screen.Catalog -> "From ${screen.provider}"
    }

    private fun providerName(providerId: String): String =
        runCatching { app.store.providers().firstOrNull { it.id == providerId }?.name }.getOrNull()
            ?: "Details"

    private fun refreshNav() {
        navButtons.forEach { (screen, button) ->
            val selected = when (screen) {
                is Screen.Home -> currentScreen is Screen.Home
                is Screen.Explore -> currentScreen is Screen.Explore
                is Screen.Search -> currentScreen is Screen.Search
                is Screen.History -> currentScreen is Screen.History
                is Screen.Library -> currentScreen is Screen.Library
                is Screen.Downloads -> currentScreen is Screen.Downloads
                is Screen.Extensions -> currentScreen is Screen.Extensions
                is Screen.Settings -> currentScreen is Screen.Settings
                else -> false
            }
            button.styleClass.remove("nav-btn-selected")
            if (selected) button.styleClass.add("nav-btn-selected")
        }
        if (::backButton.isInitialized) {
            val nested = currentScreen is Screen.Detail || currentScreen is Screen.Catalog
            backButton.isVisible = nested
            backButton.isManaged = nested
        }
        refreshThemeButton()
    }

    val app get() = HikariApp.instance

    /** The home screen, for the UI test: it has to drive the provider picker
     *  (whose sheet lives in a `Popup`, i.e. a window of its own) the way a user
     *  does. Nothing in the app itself reaches for this. */
    val homeView get() = homeScreen

    /** The Extensions screen, for the UI test: it has to check that a repaint
     *  (which is what every install ends with) leaves the page scrolled where the
     *  user left it. Nothing in the app itself reaches for this. */
    val extensionsView get() = extensionsScreen

    // ── window / player-layer hooks ─────────────────────────────────────────

    /** The layer the player mounts itself into (see [desktop.player.PlayerWindow]). */
    val playerHost: StackPane get() = playerLayer

    /** The application window, for the player's fullscreen toggle and geometry. */
    val stage: javafx.stage.Stage get() = windowStage

    /** The main window's minimise/maximise/close cluster, so the player's own
     *  bar carries the same controls as the app's top bar would. */
    fun windowControls(): HBox = chrome.controls()

    /** Lets a node drag the app window (used by the player's bar). */
    fun makeDraggable(node: Node) {
        chrome.makeDraggable(node)
    }

    fun toggleWindowMaximize() {
        chrome.toggleMaximize()
    }
}
