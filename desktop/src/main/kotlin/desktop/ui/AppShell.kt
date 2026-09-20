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

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    object Downloads : Screen()
    object Extensions : Screen()
    object Settings : Screen()
    data class Detail(val item: MediaItem) : Screen()
}

/**
 * The application shell: a desktop-style left sidebar for navigation, a top bar
 * that carries the screen title plus the window controls, and a content area that
 * swaps one screen at a time. Also owns the shared coroutine scope every screen
 * launches work on, and the toast host.
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
    private var currentScreen: Screen = Screen.Home

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
        show(Screen.Home)
        return root
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
            Theme.label("universal streaming", size = 10.5, dim = true),
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

        themeButton = Ui.iconButton(Icons.MOON, "Toggle light / dark", size = 16.0) { toggleTheme() }
        val search = Ui.iconButton(Icons.SEARCH, "Search  (Ctrl+K)", size = 16.0) { show(Screen.Search) }

        val left = HBox(10.0, titles).apply { alignment = Pos.CENTER_LEFT }
        left.padding = Insets(0.0, 0.0, 0.0, 14.0)
        chrome.makeDraggable(left)
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val actions = HBox(2.0, search, themeButton).apply { alignment = Pos.CENTER_RIGHT }
        return HBox(left, spacer, actions, chrome.controls()).apply {
            styleClass.add("topbar")
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun installShortcuts(scene: Scene) {
        scene.accelerators[KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Search) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Home) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Search) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Library) }
        scene.accelerators[KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.CONTROL_DOWN)] = Runnable { show(Screen.Downloads) }
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
        if (::toasts.isInitialized) toasts.show(message, kind)
    }

    fun show(screen: Screen) {
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
        }
        refreshNav()
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
    }

    private fun titleFor(screen: Screen): String = when (screen) {
        is Screen.Home -> "Home"
        is Screen.Search -> "Search"
        is Screen.Library -> "Library"
        is Screen.Downloads -> "Downloads"
        is Screen.Extensions -> "Extensions"
        is Screen.Settings -> "Settings"
        is Screen.Detail -> screen.item.title
    }

    private fun subtitleFor(screen: Screen): String = when (screen) {
        is Screen.Home -> "Catalogs from every enabled source"
        is Screen.Search -> "Search every installed provider at once"
        is Screen.Library -> "Favourites and continue watching"
        is Screen.Downloads -> "Offline copies and exports"
        is Screen.Extensions -> "Addons, scrapers and plugin repositories"
        is Screen.Settings -> "Appearance, network and data"
        is Screen.Detail -> "Details, episodes and sources"
    }

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
