package desktop.ui

import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox

object Theme {

    val BG = "#0e0f13"
    val BG_ELEV = "#171922"
    val BG_CARD = "#1d2028"
    val FG = "#e8eaf0"
    val FG_DIM = "#9aa0ae"
    val ACCENT = "#b06aff"
    val ACCENT_2 = "#4d9fff"
    val BORDER = "#2a2e3a"

    fun label(text: String, size: Double = 14.0, bold: Boolean = false, dim: Boolean = false): Label =
        Label(text).apply {
            style =
                "-fx-text-fill: ${if (dim) FG_DIM else FG};" +
                    "-fx-font-size: ${size}px;" +
                    (if (bold) "-fx-font-weight: bold;" else "")
        }

    /** Applies the global theme (fill + theme.css) to an existing Scene. */
    fun style(scene: Scene): Scene = scene.apply {
        fill = javafx.scene.paint.Color.web(BG)
        val css = Theme::class.java.getResource("/theme.css")
        if (css != null) stylesheets.add(css.toExternalForm())
    }

    fun scene(root: javafx.scene.Parent): Scene = style(Scene(root))

    fun centerBox(vararg children: javafx.scene.Node): VBox = VBox(12.0, *children).apply {
        alignment = Pos.CENTER
    }
}
