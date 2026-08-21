package desktop

import com.hikari.app.HikariApp
import desktop.fx.Fx
import desktop.ui.AppShell
import desktop.ui.Screen
import desktop.ui.Theme
import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.StageStyle

fun main() {
    val app = HikariApp()
    app.init()
    Application.launch(HikariDesktopApp::class.java)
}

class HikariDesktopApp : Application() {

    override fun start(stage: Stage) {
        Fx.onStart()
        stage.title = "Hikari"
        // Frameless window: the in-app WindowChrome draws the title bar and
        // the minimize/maximize/close buttons, so they're always visible no
        // matter what the OS/session provides.
        stage.initStyle(StageStyle.UNDECORATED)
        val root = AppShell.create(stage)
        stage.scene = Theme.style(Scene(root, 1280.0, 780.0))
        stage.minWidth = 960.0
        stage.minHeight = 620.0
        stage.show()
        AppShell.show(Screen.Home)
    }
}
