package desktop

import com.hikari.app.HikariApp
import desktop.fx.Fx
import desktop.ui.AppShell
import desktop.ui.Screen
import desktop.ui.Theme
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
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
        // Frameless: the window chrome lives inside the app — AppShell's top bar
        // carries the title, search and controls, and WindowChrome contributes the
        // resize edges plus drag-to-maximise.
        stage.initStyle(StageStyle.UNDECORATED)
        runCatching {
            val icon = javaClass.getResourceAsStream("/hikari.png")
            if (icon != null) stage.icons.add(Image(icon))
        }
        val root = AppShell.create(stage)
        stage.scene = Theme.style(Scene(root, 1280.0, 780.0))
        stage.minWidth = 1024.0
        stage.minHeight = 660.0
        stage.show()
        AppShell.show(Screen.Home)
    }
}
