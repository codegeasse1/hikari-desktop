package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.ui.theme.HikariThemeMode
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
    object Search : Screen()
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
    private lateinit var globalSearch: TextField
    private var currentScreen: Screen = Screen.Home
    private val backStack = java.util.ArrayDeque<Screen>()

    private val homeScreen = HomeScreenView()
    private val searchScreen = SearchScreenView()
    private val libraryScreen = LibraryScreenView()
    private val downloadsScreen = DownloadsScreenView()
    private val extensionsScreen = ExtensionsScreenView()
    private val settingsScreen = SettingsScreenView()

    fun create(stage: javafx.stage.Stage): Region {
        chrome = WindowChrome(stage)

        centerStack = StackPane().apply { styleClass.add("content-host") }
        val overlay = StackPane().apply { isMouseTransparent = true }
        toasts = Ui.Toasts(overlay)

        val body = BorderPane().apply {
            styleClass.add("app-root")
            left = sidebar()
            top = topbar()
            center = StackPane(centerStack, overlay)
        }

        val root = chrome.build(body)
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
        val box = VBox(3.0).apply {
            styleClass.add("sidebar")
            minWidth = 216.0
            prefWidth = 216.0
            maxWidth = 216.0
        }

        val mark = Region().apply { styleClass.add("sidebar-logo-mark") }
        val brand = VBox(0.0,
            Theme.label("Hikari", size = 17.0, bold = true).apply { styleClass.add("sidebar-logo") },
            Theme.label("your media, your way", size = 10.5, dim = true),
        )
        box.children.add(HBox(10.0, mark, brand).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 6.0, 16.0, 6.0)
        })

        navButtons = mutableListOf()
        fun nav(screen: Screen, icon: String, text: String) {
            val button = Button(text).apply {
                styleClass.add("nav-btn")
                graphic = Icons.of(icon, 17.0)
                maxWidth = Double.MAX_VALUE
                isFocusTraversable = false
                setOnAction { show(screen) }
            }
            navButtons.add(screen to button)
            box.children.add(button)
        }

        box.children.add(Theme.overline("Watch").apply { styleClass.add("sidebar-section") })
        nav(Screen.Home, Icons.HOME, "Home")
        nav(Screen.Search, Icons.SEARCH, "Search")
        nav(Screen.Library, Icons.LIBRARY, "Library")
        nav(Screen.Downloads, Icons.DOWNLOAD, "Downloads")

        box.children.add(Theme.overline("Sources").apply { styleClass.add("sidebar-section") })
        nav(Screen.Extensions, Icons.EXTENSIONS, "Extensions")
        nav(Screen.Settings, Icons.SETTINGS, "Settings")

        val grow = Region().apply { VBox.setVgrow(this, Priority.ALWAYS) }
        box.children.add(grow)
        box.children.add(sidebarFooter())
        return box
    }

    private fun sidebarFooter(): Region {
        val providers = runCatching {
            val enabled = app.store.providers().count { it.enabled }
            val repos = app.store.repos().size
            "$enabled providers · $repos repos"
        }.getOrDefault("no data yet")
        return VBox(2.0,
            Theme.label("Hikari Desktop", size = 11.5, bold = true),
            Theme.label("v${desktop.Build.VERSION} · $providers", size = 10.5, dim = true),
        ).apply {
            styleClass.add("sidebar-foot")
            padding = Insets(9.0, 11.0, 9.0, 11.0)
        }
    }

    private fun topbar(): Region {
        titleLabel = Theme.label("", size = 13.5, bold = true).apply { styleClass.add("topbar-title") }
        subtitleLabel = Theme.label("", size = 11.5, dim = true).apply { styleClass.add("topbar-sub") }
        val titles = VBox(0.0, titleLabel, subtitleLabel)

        val searchBox = Ui.searchInput("Search movies, shows, anime…", 268.0)
        globalSearch = searchBox.children[0] as TextField
        globalSearch.setOnAction { runGlobalSearch() }
        globalSearch.focusedProperty().addListener { _, _, focused ->
            if (focused && globalSearch.text.isBlank() && currentScreen !is Screen.Search) show(Screen.Search)
        }

        themeButton = Ui.iconButton(Icons.MOON, "Toggle light / dark", size = 16.0) { toggleTheme() }

        val left = HBox(10.0, titles).apply { alignment = Pos.CENTER_LEFT }
        left.padding = Insets(0.0, 0.0, 0.0, 14.0)
        left.minWidth = 150.0
        chrome.makeDraggable(left)
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val actions = HBox(2.0, searchBox, themeButton).apply { alignment = Pos.CENTER_RIGHT }
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
        scene.accelerators[KeyCodeCombination(KeyCode.ESCAPE)] = Runnable { back() }
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

    fun show(screen: Screen, remember: Boolean = true) {
        if (remember && screen != currentScreen) {
            backStack.addLast(currentScreen)
            while (backStack.size > 24) backStack.pollFirst()
        }
        currentScreen = screen
        Theme.refresh()
        val node = viewFor(screen)
        centerStack.children.clear()
        centerStack.children.add(node)
        Ui.fadeIn(node)
        titleLabel.text = titleFor(screen)
        subtitleLabel.text = subtitleFor(screen)
        when (screen) {
            is Screen.Home -> homeScreen.onShown()
            is Screen.Search -> searchScreen.onShown()
            is Screen.Library -> libraryScreen.onShown()
            is Screen.Downloads -> downloadsScreen.onShown()
            is Screen.Extensions -> extensionsScreen.onShown()
            is Screen.Settings -> settingsScreen.onShown()
            is Screen.Detail -> Unit
            is Screen.Catalog -> Unit
        }
        refreshNav()
    }

    /** Returns to the previously shown screen (Escape / the in-page back arrow). */
    fun back() {
        val previous = backStack.pollLast()
        show(previous ?: Screen.Home, remember = false)
    }

    fun openDetail(item: MediaItem) {
        show(Screen.Detail(item))
    }

    fun goHome() {
        show(Screen.Home)
    }

    private fun viewFor(screen: Screen): Node = when (screen) {
        is Screen.Home -> homeScreen.root
        is Screen.Search -> searchScreen.root
        is Screen.Library -> libraryScreen.root
        is Screen.Downloads -> downloadsScreen.root
        is Screen.Extensions -> extensionsScreen.root
        is Screen.Settings -> settingsScreen.root
        is Screen.Detail -> DetailScreenView(screen.item).root
        is Screen.Catalog -> CatalogScreenView(screen.title, screen.provider, screen.items).root
    }

    private fun titleFor(screen: Screen): String = when (screen) {
        is Screen.Home -> "Home"
        is Screen.Search -> "Search"
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
        is Screen.Search -> "Search every installed provider at once"
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
                is Screen.Search -> currentScreen is Screen.Search
                is Screen.Library -> currentScreen is Screen.Library
                is Screen.Downloads -> currentScreen is Screen.Downloads
                is Screen.Extensions -> currentScreen is Screen.Extensions
                is Screen.Settings -> currentScreen is Screen.Settings
                else -> false
            }
            button.styleClass.remove("nav-btn-selected")
            if (selected) button.styleClass.add("nav-btn-selected")
        }
        refreshThemeButton()
    }

    val app get() = HikariApp.instance
}
