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
        // Fit the window to the screen. A fixed 1280x780 window is taller than
        // the usable area on a 1366x768 laptop — and because the window is
        // frameless, anything below the screen edge is simply unreachable.
        val bounds = javafx.stage.Screen.getPrimary().visualBounds
        val w = minOf(1280.0, bounds.width - 60.0)
        val h = minOf(820.0, bounds.height - 60.0)
        stage.scene = Theme.style(Scene(root, w, h))
        stage.minWidth = minOf(900.0, w)
        stage.minHeight = minOf(560.0, h)
        stage.x = bounds.minX + (bounds.width - w) / 2
        stage.y = bounds.minY + (bounds.height - h) / 2
        stage.show()
        AppShell.show(Screen.Home)
    }
}
