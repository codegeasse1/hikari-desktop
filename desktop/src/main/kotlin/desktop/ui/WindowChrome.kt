package desktop.ui

import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Screen
import javafx.stage.Stage

/**
 * Custom window chrome for the frameless (UNDECORATED) stage: a draggable title
 * bar with minimize/maximize/close buttons plus invisible resize edges, so the
 * window always has standard OS-style controls regardless of environment.
 */
class WindowChrome(private val stage: Stage) {

    private val root = StackPane()
    private var maximized = false
    private var restoreX = 0.0
    private var restoreY = 0.0
    private var restoreW = 0.0
    private var restoreH = 0.0
    private var dragOffX = 0.0
    private var dragOffY = 0.0

    private val maxBtn = Button("□").apply {
        styleClass.addAll("win-btn", "win-btn-max")
        onAction = { toggleMaximize() }
    }

    fun build(body: Region): Region {
        val title = Label("Hikari").apply { styleClass.add("win-title") }
        val minBtn = Button("—").apply {
            styleClass.addAll("win-btn", "win-btn-min")
            onAction = { stage.isIconified = true }
        }
        val closeBtn = Button("✕").apply {
            styleClass.addAll("win-btn", "win-btn-close")
            onAction = { stage.close() }
        }
        val controls = HBox(minBtn, maxBtn, closeBtn).apply {
            alignment = Pos.CENTER_RIGHT
            spacing = 0.0
        }
        val titleBar = BorderPane().apply {
            styleClass.add("win-titlebar")
            left = title
            right = controls
            setOnMousePressed { e ->
                if (!maximized) {
                    dragOffX = e.screenX - stage.x
                    dragOffY = e.screenY - stage.y
                }
            }
            setOnMouseDragged { e ->
                if (!maximized) {
                    stage.x = e.screenX - dragOffX
                    stage.y = e.screenY - dragOffY
                }
            }
            setOnMouseClicked { e ->
                if (e.clickCount == 2) toggleMaximize()
            }
        }

        val chrome = BorderPane().apply {
            top = titleBar
            center = body
        }
        root.children.add(chrome)
        addResizeEdges()
        return root
    }

    private fun toggleMaximize() {
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
            val vb = Screen.getPrimary().visualBounds
            stage.x = vb.minX
            stage.y = vb.minY
            stage.width = vb.width
            stage.height = vb.height
            maximized = true
        }
        maxBtn.text = if (maximized) "❐" else "□"
    }

    /** Edge bitmask: 1=W, 2=E, 4=N, 8=S. Corners combine two. */
    private fun resizeEdge(edge: Int): Region {
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
                    prefHeight = 6.0
                    minHeight = 6.0
                    maxHeight = 6.0
                    maxWidth = Double.MAX_VALUE
                }
                edge == 1 || edge == 2 -> {
                    prefWidth = 6.0
                    minWidth = 6.0
                    maxWidth = 6.0
                    maxHeight = Double.MAX_VALUE
                }
                else -> {
                    prefWidth = 6.0
                    prefHeight = 6.0
                    minWidth = 6.0
                    minHeight = 6.0
                    maxWidth = 6.0
                    maxHeight = 6.0
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
                if ((edge and 2) != 0) {
                    nw = ww + dx
                }
                if ((edge and 4) != 0) {
                    nh = wh - dy
                    ny = wy + dy
                }
                if ((edge and 8) != 0) {
                    nh = wh + dy
                }
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
