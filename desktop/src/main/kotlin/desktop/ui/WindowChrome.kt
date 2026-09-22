package desktop.ui

import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Screen
import javafx.stage.Stage

/**
 * Window chrome for the frameless (UNDECORATED) stage.
 *
 * The title bar itself lives in [AppShell]'s top bar so it can also carry the
 * screen title, search and theme toggle; this class contributes the invisible
 * resize edges around the window plus the standard minimise / restore / close
 * controls ([controls]) and the drag-to-maximise behaviour.
 */
class WindowChrome(private val stage: Stage) {

    private val root = StackPane()
    private var maximized = false
    private var restoreX = 0.0
    private var restoreY = 0.0
    private var restoreW = 0.0
    private var restoreH = 0.0

    /** Every maximise button ever handed out. [controls] is called more than
     *  once (the app's top bar, and the player's own strip while it covers the
     *  window), and each caller needs its OWN button instance: a JavaFX node has
     *  one parent, so sharing one would silently remove it from the first caller
     *  — which is how the top bar's maximise button used to disappear for good
     *  after the first play. */
    private val maximizeButtons = mutableListOf<Button>()

    /** The minimise / maximise / close cluster for the window's top bar. */
    fun controls(): HBox {
        val minimize = Ui.iconButton(Icons.MINIMIZE, "Minimise", size = 13.0) { stage.isIconified = true }
        val maximize = Ui.iconButton(
            if (maximized) Icons.RESTORE else Icons.MAXIMIZE,
            "Maximise",
            size = 13.0,
        ) { toggleMaximize() }
        val close = Ui.iconButton(Icons.CLOSE, "Close", size = 13.0) { stage.close() }
        maximizeButtons.add(maximize)
        minimize.styleClass.add("win-btn")
        maximize.styleClass.add("win-btn")
        close.styleClass.addAll("win-btn", "win-btn-close")
        return HBox(minimize, maximize, close).apply {
            alignment = Pos.CENTER_RIGHT
            spacing = 0.0
            styleClass.add("win-controls")
        }
    }

    /**
     * Wraps [body] in the window chrome. [overlay] — when given — is layered
     * ABOVE the body but BELOW the resize edges, so a full-window overlay (the
     * player) still leaves the window's resize borders usable.
     */
    fun build(body: Region, overlay: Region? = null): Region {
        val chrome = StackPane(body)
        root.children.setAll(chrome)
        if (overlay != null) root.children.add(overlay)
        addResizeEdges()
        return root
    }

    /**
     * Lets [node] act as the title bar: drag to move the window, double-click to
     * maximise. Attach this to the title area only — never over buttons, which
     * would swallow the drag.
     */
    fun makeDraggable(node: javafx.scene.Node) {
        var offX = 0.0
        var offY = 0.0
        node.setOnMousePressed { e ->
            if (!maximized) {
                offX = e.screenX - stage.x
                offY = e.screenY - stage.y
            }
        }
        node.setOnMouseDragged { e ->
            if (!maximized) {
                stage.x = e.screenX - offX
                stage.y = e.screenY - offY
            }
        }
        node.setOnMouseClicked { e ->
            if (e.clickCount == 2) toggleMaximize()
        }
    }

    /** True while the window is maximised; the top bar uses this to disable dragging. */
    fun isMaximized(): Boolean = maximized

    fun toggleMaximize() {
        if (maximized) {
            stage.x = restoreX
            stage.y = restoreY
            stage.width = restoreW
            stage.height = restoreH
            maximized = false
        } else {
            restoreX = stage.x
            restoreY = stage.y
            restoreW = stage.width
            restoreH = stage.height
            val bounds = Screen.getPrimary().visualBounds
            stage.x = bounds.minX
            stage.y = bounds.minY
            stage.width = bounds.width
            stage.height = bounds.height
            maximized = true
        }
        maximizeButtons.forEach {
            it.graphic = Icons.of(if (maximized) Icons.RESTORE else Icons.MAXIMIZE, 13.0)
        }
    }

    /** Edge bitmask: 1=W, 2=E, 4=N, 8=S. Corners combine two. */
    private fun resizeEdge(edge: Int): Region {
        val thickness = 5.0
        val r = Region().apply {
            cursor = when (edge) {
                1 -> Cursor.W_RESIZE
                2 -> Cursor.E_RESIZE
                4 -> Cursor.N_RESIZE
                8 -> Cursor.S_RESIZE
                5 -> Cursor.NW_RESIZE
                6 -> Cursor.NE_RESIZE
                9 -> Cursor.SW_RESIZE
                10 -> Cursor.SE_RESIZE
                else -> Cursor.DEFAULT
            }
            when {
                edge == 4 || edge == 8 -> {
                    prefHeight = thickness
                    minHeight = thickness
                    maxHeight = thickness
                    maxWidth = Double.MAX_VALUE
                }
                edge == 1 || edge == 2 -> {
                    prefWidth = thickness
                    minWidth = thickness
                    maxWidth = thickness
                    maxHeight = Double.MAX_VALUE
                }
                else -> {
                    prefWidth = thickness
                    prefHeight = thickness
                    minWidth = thickness
                    minHeight = thickness
                    maxWidth = thickness
                    maxHeight = thickness
                }
            }
            var ox = 0.0
            var oy = 0.0
            var wx = 0.0
            var wy = 0.0
            var ww = 0.0
            var wh = 0.0
            setOnMousePressed { e ->
                if (maximized) return@setOnMousePressed
                ox = e.screenX
                oy = e.screenY
                wx = stage.x
                wy = stage.y
                ww = stage.width
                wh = stage.height
            }
            setOnMouseDragged { e ->
                if (maximized) return@setOnMouseDragged
                val dx = e.screenX - ox
                val dy = e.screenY - oy
                var nx = wx
                var ny = wy
                var nw = ww
                var nh = wh
                if ((edge and 1) != 0) {
                    nw = ww - dx
                    nx = wx + dx
                }
                if ((edge and 2) != 0) nw = ww + dx
                if ((edge and 4) != 0) {
                    nh = wh - dy
                    ny = wy + dy
                }
                if ((edge and 8) != 0) nh = wh + dy
                val minW = stage.minWidth
                val minH = stage.minHeight
                if (nw < minW) {
                    if ((edge and 1) != 0) nx = wx + ww - minW
                    nw = minW
                }
                if (nh < minH) {
                    if ((edge and 4) != 0) ny = wy + wh - minH
                    nh = minH
                }
                stage.x = nx
                stage.y = ny
                stage.width = nw
                stage.height = nh
            }
        }
        StackPane.setAlignment(
            r,
            when (edge) {
                5 -> Pos.TOP_LEFT
                4 -> Pos.TOP_CENTER
                6 -> Pos.TOP_RIGHT
                1 -> Pos.CENTER_LEFT
                2 -> Pos.CENTER_RIGHT
                9 -> Pos.BOTTOM_LEFT
                8 -> Pos.BOTTOM_CENTER
                10 -> Pos.BOTTOM_RIGHT
                else -> Pos.CENTER
            }
        )
        return r
    }

    private fun addResizeEdges() {
        root.children.addAll(
            resizeEdge(5),
            resizeEdge(4),
            resizeEdge(6),
            resizeEdge(1),
            resizeEdge(2),
            resizeEdge(9),
            resizeEdge(8),
            resizeEdge(10),
        )
    }
}
