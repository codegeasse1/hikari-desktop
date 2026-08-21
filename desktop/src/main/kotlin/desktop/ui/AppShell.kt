package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.layout.BorderPane
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
    object Extensions : Screen()
    object Settings : Screen()
    data class Detail(val item: MediaItem) : Screen()
}

object AppShell {

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var centerStack: StackPane
    private lateinit var navButtons: MutableList<Pair<Screen, Button>>
    private var currentScreen: Screen = Screen.Home
    private val homeScreen = HomeScreenView()
    private val searchScreen = SearchScreenView()
    private val extensionsScreen = ExtensionsScreenView()
    private val settingsScreen = SettingsScreenView()

    fun create(stage: javafx.stage.Stage): Region {
        centerStack = StackPane()
        centerStack.style = "-fx-background-color: ${Theme.BG};"

        navButtons = mutableListOf()
        val sidebar = VBox(4.0).apply {
            styleClass.add("sidebar")
            minWidth = 170.0
            prefWidth = 170.0
            padding = Insets(14.0, 10.0, 14.0, 10.0)
        }
        val logo = Theme.label("Hikari", size = 22.0, bold = true)
        logo.style = logo.style + "; -fx-text-fill: ${Theme.ACCENT};"
        sidebar.children.add(logo)
        VBox.setMargin(logo, Insets(0.0, 0.0, 14.0, 8.0))

        fun navButton(screen: Screen, text: String) {
            val b = Button(text).apply {
                styleClass.add("nav-btn")
                maxWidth = Double.MAX_VALUE
                setOnAction {
                    show(screen)
                    refreshNav()
                }
            }
            navButtons.add(screen to b)
            sidebar.children.add(b)
        }
        navButton(Screen.Home, "Home")
        navButton(Screen.Search, "Search")
        navButton(Screen.Extensions, "Extensions")
        navButton(Screen.Settings, "Settings")

        val spacer = Region().apply { VBox.setVgrow(this, Priority.ALWAYS) }
        sidebar.children.add(spacer)
        refreshNav()

        val appPane = BorderPane().apply {
            left = sidebar
            center = centerStack
        }
        return WindowChrome(stage).build(appPane)
    }

    fun show(screen: Screen) {
        currentScreen = screen
        val node = viewFor(screen)
        centerStack.children.clear()
        centerStack.children.add(node)
        when (screen) {
            is Screen.Home -> homeScreen.onShown()
            is Screen.Search -> searchScreen.onShown()
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
        is Screen.Extensions -> extensionsScreen.root
        is Screen.Settings -> settingsScreen.root
        is Screen.Detail -> DetailScreenView(screen.item).root
    }

    private fun refreshNav() {
        navButtons.forEach { (screen, btn) ->
            val selected = when (screen) {
                is Screen.Home -> currentScreen is Screen.Home
                is Screen.Search -> currentScreen is Screen.Search
                is Screen.Extensions -> currentScreen is Screen.Extensions
                is Screen.Settings -> currentScreen is Screen.Settings
                else -> false
            }
            btn.styleClass.removeAll("nav-btn-selected")
            if (selected) btn.styleClass.add("nav-btn-selected")
        }
    }

    val app get() = HikariApp.instance
}
