package desktop.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.layout.StackPane
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.Shape
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

/**
 * The app's icon set, drawn from primitives on a 24x24 grid.
 *
 * Every shape carries the `h-icon` class (filled) or `h-icon-out` (stroked), so
 * colour follows the active theme and accent purely through CSS — see the icon
 * rules in `theme.css`. Icons are built once per use and scaled to the requested
 * size; unknown names fall back to a neutral rounded square so a typo shows up as
 * a visible placeholder instead of an exception.
 */
object Icons {

    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DOWNLOAD = "download"
    const val HISTORY = "history"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val PLAYLIST = "playlist"

    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SKIP_NEXT = "skip-next"
    const val SKIP_PREV = "skip-prev"
    const val VOLUME = "volume"
    const val VOLUME_OFF = "volume-off"
    const val FULLSCREEN = "fullscreen"
    const val FULLSCREEN_EXIT = "fullscreen-exit"
    const val SUBTITLES = "subtitles"
    const val QUALITY = "quality"
    const val SPEED = "speed"
    const val PIP = "pip"

    const val MINIMIZE = "minimize"
    const val MAXIMIZE = "maximize"
    const val RESTORE = "restore"
    const val CLOSE = "close"

    const val CHEVRON_DOWN = "chevron-down"
    const val CHEVRON_LEFT = "chevron-left"
    const val CHEVRON_RIGHT = "chevron-right"
    const val PLUS = "plus"
    const val TRASH = "trash"
    const val REFRESH = "refresh"
    const val CHECK = "check"
    const val STAR = "star"
    const val HEART = "heart"
    const val HEART_OUTLINE = "heart-outline"
    const val FILTER = "filter"
    const val GRID = "grid"
    const val LIST = "list"
    const val EXTERNAL = "external"
    const val INFO = "info"
    const val WARNING = "warning"
    const val ERROR = "error"
    const val FOLDER = "folder"
    const val GLOBE = "globe"
    const val SUN = "sun"
    const val MOON = "moon"
    const val PALETTE = "palette"
    const val SHIELD = "shield"
    const val ZAP = "zap"
    const val CLOCK = "clock"
    const val MORE = "more"
    const val EDIT = "edit"
    const val COPY = "copy"
    const val UPLOAD = "upload"
    const val TV = "tv"
    const val FILM = "film"
    const val CAST = "cast"
    const val IMAGE = "image"
    const val LOCK = "lock"
    const val CLOUD = "cloud"
    const val SORT = "sort"
    const val TAG = "tag"
    const val USER = "user"
    const val SPARKLE = "sparkle"

    /** An icon node with a fixed [size]x[size] layout box, centred. */
    fun of(name: String, size: Double = 18.0): Node {
        val shapes = shapes(name)
        val group = Group(*shapes.toTypedArray()).apply {
            scaleX = size / 24.0
            scaleY = size / 24.0
            isMouseTransparent = true
        }
        return StackPane(group).apply {
            prefWidth = size
            prefHeight = size
            minWidth = size
            minHeight = size
            maxWidth = size
            maxHeight = size
            isMouseTransparent = true
        }
    }

    private fun filled(vararg shapes: Shape): List<Shape> =
        shapes.map { it.apply { styleClass.add("h-icon") } }

    private fun outline(vararg shapes: Shape): List<Shape> =
        shapes.map { it.apply { styleClass.add("h-icon-out") } }

    private fun mix(fills: List<Shape>, strokes: List<Shape>): List<Shape> =
        fills.map { it.apply { styleClass.add("h-icon") } } + strokes.map { it.apply { styleClass.add("h-icon-out") } }

    private fun shapes(name: String): List<Shape> = when (name) {

        HOME -> outline(
            SVGPath().apply { content = "M3.4 11.4 L12 3.8 L20.6 11.4" },
            SVGPath().apply { content = "M5.9 9.9 V20.2 H18.1 V9.9" },
        )

        SEARCH -> outline(
            Circle(10.6, 10.6, 6.2),
            Line(15.2, 15.2, 20.2, 20.2),
        )

        LIBRARY -> filled(
            Rectangle(6.0, 8.0).apply { x = 3.6; y = 4.6; arcWidth = 2.0; arcHeight = 2.0 },
            Rectangle(6.0, 8.0).apply { x = 9.0; y = 4.6; arcWidth = 2.0; arcHeight = 2.0 },
            Rectangle(3.2, 8.0).apply { x = 16.2; y = 4.6; arcWidth = 2.0; arcHeight = 2.0 },
            Polygon(3.2, 13.6, 6.6, 12.7, 9.8, 20.4, 6.4, 21.3),
        )

        DOWNLOAD -> mix(
            listOf(Polygon(8.2, 10.6, 15.8, 10.6, 12.0, 15.8)),
            listOf(
                Line(12.0, 3.6, 12.0, 14.2),
                Line(4.4, 20.0, 19.6, 20.0),
            ),
        )

        HISTORY -> outline(
            Circle(12.0, 12.0, 8.3),
            Line(12.0, 7.4, 12.0, 12.5),
            Line(12.0, 12.5, 15.6, 14.6),
        )

        EXTENSIONS -> outline(
            Rectangle(7.2, 7.2).apply { x = 3.9; y = 3.9; arcWidth = 3.0; arcHeight = 3.0 },
            Rectangle(7.2, 7.2).apply { x = 12.9; y = 3.9; arcWidth = 3.0; arcHeight = 3.0 },
            Rectangle(7.2, 7.2).apply { x = 3.9; y = 12.9; arcWidth = 3.0; arcHeight = 3.0 },
            Line(13.2, 16.5, 19.8, 16.5),
            Line(16.5, 13.2, 16.5, 19.8),
        )

        SETTINGS -> mix(
            listOf(
                Circle(8.6, 6.8, 2.3),
                Circle(15.4, 12.0, 2.3),
                Circle(9.8, 17.2, 2.3),
            ),
            listOf(
                Line(3.4, 6.8, 20.6, 6.8),
                Line(3.4, 12.0, 20.6, 12.0),
                Line(3.4, 17.2, 20.6, 17.2),
            ),
        )

        PLAYLIST -> mix(
            listOf(
                Polygon(15.6, 8.4, 21.6, 11.6, 15.6, 14.8),
            ),
            listOf(
                Line(3.4, 6.0, 21.6, 6.0),
                Line(3.4, 11.6, 13.0, 11.6),
                Line(3.4, 17.2, 13.0, 17.2),
            ),
        )

        PLAY -> filled(Polygon(7.6, 4.6, 20.0, 12.0, 7.6, 19.4))

        PAUSE -> filled(
            Rectangle(4.4, 14.4).apply { x = 6.8; y = 4.8; arcWidth = 2.4; arcHeight = 2.4 },
            Rectangle(4.4, 14.4).apply { x = 12.8; y = 4.8; arcWidth = 2.4; arcHeight = 2.4 },
        )

        SKIP_NEXT -> filled(
            Polygon(5.6, 5.0, 15.4, 12.0, 5.6, 19.0),
            Rectangle(3.0, 14.0).apply { x = 16.4; y = 5.0; arcWidth = 2.0; arcHeight = 2.0 },
        )

        SKIP_PREV -> filled(
            Polygon(18.4, 5.0, 8.6, 12.0, 18.4, 19.0),
            Rectangle(3.0, 14.0).apply { x = 4.6; y = 5.0; arcWidth = 2.0; arcHeight = 2.0 },
        )

        VOLUME -> mix(
            listOf(Polygon(3.2, 9.2, 7.4, 9.2, 11.8, 5.0, 11.8, 19.0, 7.4, 14.8, 3.2, 14.8)),
            listOf(
                SVGPath().apply { content = "M15.2 9.0 A4.4 4.4 0 0 1 15.2 15.0" },
                SVGPath().apply { content = "M17.8 6.4 A8.0 8.0 0 0 1 17.8 17.6" },
            ),
        )

        VOLUME_OFF -> mix(
            listOf(Polygon(3.2, 9.2, 7.4, 9.2, 11.8, 5.0, 11.8, 19.0, 7.4, 14.8, 3.2, 14.8)),
            listOf(
                Line(15.4, 9.6, 20.6, 14.8),
                Line(20.6, 9.6, 15.4, 14.8),
            ),
        )

        FULLSCREEN -> outline(
            SVGPath().apply { content = "M4.2 9.2 V4.2 H9.2" },
            SVGPath().apply { content = "M14.8 4.2 H19.8 V9.2" },
            SVGPath().apply { content = "M19.8 14.8 V19.8 H14.8" },
            SVGPath().apply { content = "M9.2 19.8 H4.2 V14.8" },
        )

        FULLSCREEN_EXIT -> outline(
            SVGPath().apply { content = "M9.2 4.2 V9.2 H4.2" },
            SVGPath().apply { content = "M19.8 9.2 H14.8 V4.2" },
            SVGPath().apply { content = "M14.8 19.8 V14.8 H19.8" },
            SVGPath().apply { content = "M4.2 14.8 H9.2 V19.8" },
        )

        QUALITY -> mix(
            listOf(
                Rectangle(2.6, 3.6).apply { x = 5.4; y = 12.6; arcWidth = 1.0; arcHeight = 1.0 },
                Rectangle(2.6, 6.8).apply { x = 10.5; y = 9.4; arcWidth = 1.0; arcHeight = 1.0 },
                Rectangle(2.6, 10.0).apply { x = 15.6; y = 6.2; arcWidth = 1.0; arcHeight = 1.0 },
            ),
            listOf(
                Rectangle(20.4, 14.0).apply { x = 1.8; y = 3.4; arcWidth = 3.0; arcHeight = 3.0 },
                Line(8.4, 21.4, 15.6, 21.4),
            ),
        )

        SUBTITLES -> mix(
            listOf(
                Rectangle(5.6, 2.0).apply { x = 6.4; y = 13.4; arcWidth = 1.4; arcHeight = 1.4 },
                Rectangle(4.8, 2.0).apply { x = 13.2; y = 13.4; arcWidth = 1.4; arcHeight = 1.4 },
                Rectangle(10.6, 1.8).apply { x = 6.6; y = 9.8; arcWidth = 1.4; arcHeight = 1.4 },
            ),
            listOf(Rectangle(18.2, 13.4).apply { x = 2.9; y = 5.3; arcWidth = 5.0; arcHeight = 5.0 }),
        )

        SPEED -> mix(
            listOf(Circle(12.0, 12.0, 1.5)),
            listOf(
                Circle(12.0, 12.0, 8.3),
                Line(12.0, 12.0, 16.4, 8.6),
            ),
        )

        PIP -> mix(
            listOf(Rectangle(7.4, 5.6).apply { x = 12.4; y = 12.2; arcWidth = 2.0; arcHeight = 2.0 }),
            listOf(Rectangle(18.2, 14.2).apply { x = 2.9; y = 4.9; arcWidth = 5.0; arcHeight = 5.0 }),
        )

        MINIMIZE -> outline(Line(6.0, 12.0, 18.0, 12.0))

        MAXIMIZE -> outline(Rectangle(13.0, 13.0).apply { x = 5.5; y = 5.5; arcWidth = 3.0; arcHeight = 3.0 })

        RESTORE -> outline(
            Rectangle(11.0, 11.0).apply { x = 8.0; y = 8.0; arcWidth = 3.0; arcHeight = 3.0 },
            SVGPath().apply { content = "M4.8 16.2 V4.8 H16.2" },
        )

        CLOSE -> outline(
            Line(6.4, 6.4, 17.6, 17.6),
            Line(17.6, 6.4, 6.4, 17.6),
        )

        CHEVRON_DOWN -> outline(SVGPath().apply { content = "M6.2 9.6 L12.0 15.4 L17.8 9.6" })
        CHEVRON_LEFT -> outline(SVGPath().apply { content = "M14.6 5.2 L8.8 12.0 L14.6 18.8" })
        CHEVRON_RIGHT -> outline(SVGPath().apply { content = "M9.4 5.2 L15.2 12.0 L9.4 18.8" })

        PLUS -> outline(
            Line(12.0, 5.4, 12.0, 18.6),
            Line(5.4, 12.0, 18.6, 12.0),
        )

        TRASH -> outline(
            Line(4.4, 6.9, 19.6, 6.9),
            SVGPath().apply { content = "M9.3 6.9 V4.6 H14.7 V6.9" },
            SVGPath().apply { content = "M6.6 6.9 L7.7 20.0 H16.3 L17.4 6.9" },
            Line(10.3, 10.4, 10.3, 16.8),
            Line(13.7, 10.4, 13.7, 16.8),
        )

        REFRESH -> mix(
            listOf(Polygon(15.4, 2.4, 21.0, 6.6, 15.4, 10.0)),
            listOf(SVGPath().apply { content = "M20.2 8.6 A8.4 8.4 0 1 1 19.4 5.0" }),
        )

        CHECK -> outline(SVGPath().apply { content = "M4.8 12.6 L9.6 17.4 L19.2 6.6" })

        STAR -> filled(
            SVGPath().apply {
                content = "M12 2.6l2.9 5.9 6.5.95-4.7 4.6 1.1 6.5L12 17.5l-5.8 3.05 1.1-6.5-4.7-4.6 6.5-.95z"
            }
        )

        HEART -> filled(heartPath())

        HEART_OUTLINE -> outline(heartPath())

        FILTER -> outline(
            SVGPath().apply { content = "M4.0 5.2 H20.0 L13.9 12.9 V19.6 L10.1 17.6 V12.9 Z" }
        )

        GRID -> outline(
            Rectangle(6.8, 6.8).apply { x = 3.6; y = 3.6; arcWidth = 2.6; arcHeight = 2.6 },
            Rectangle(6.8, 6.8).apply { x = 13.6; y = 3.6; arcWidth = 2.6; arcHeight = 2.6 },
            Rectangle(6.8, 6.8).apply { x = 3.6; y = 13.6; arcWidth = 2.6; arcHeight = 2.6 },
            Rectangle(6.8, 6.8).apply { x = 13.6; y = 13.6; arcWidth = 2.6; arcHeight = 2.6 },
        )

        LIST -> mix(
            listOf(
                Circle(5.0, 6.2, 1.7),
                Circle(5.0, 12.0, 1.7),
                Circle(5.0, 17.8, 1.7),
            ),
            listOf(
                Line(9.0, 6.2, 20.4, 6.2),
                Line(9.0, 12.0, 20.4, 12.0),
                Line(9.0, 17.8, 20.4, 17.8),
            ),
        )

        EXTERNAL -> outline(
            SVGPath().apply { content = "M13.8 4.2 H19.8 V10.2" },
            Line(19.8, 4.2, 11.4, 12.6),
            SVGPath().apply { content = "M17.6 14.4 V19.0 H5.0 V6.4 H9.6" },
        )

        INFO -> mix(
            listOf(Circle(12.0, 7.8, 1.2)),
            listOf(
                Circle(12.0, 12.0, 8.4),
                Line(12.0, 11.0, 12.0, 16.6),
            ),
        )

        WARNING -> mix(
            listOf(Circle(12.0, 17.2, 1.2)),
            listOf(
                SVGPath().apply { content = "M12 3.4 L21.4 19.8 H2.6 Z" },
                Line(12.0, 9.4, 12.0, 14.4),
            ),
        )

        ERROR -> outline(
            Circle(12.0, 12.0, 8.4),
            Line(9.0, 9.0, 15.0, 15.0),
            Line(15.0, 9.0, 9.0, 15.0),
        )

        FOLDER -> outline(
            SVGPath().apply {
                content = "M3.4 7.4 A1.6 1.6 0 0 1 5.0 5.8 H9.4 L11.4 8.2 H19.0 A1.6 1.6 0 0 1 20.6 9.8 V18.4 A1.6 1.6 0 0 1 19.0 20.0 H5.0 A1.6 1.6 0 0 1 3.4 18.4 Z"
            }
        )

        GLOBE -> outline(
            Circle(12.0, 12.0, 8.4),
            Line(3.6, 12.0, 20.4, 12.0),
            SVGPath().apply { content = "M12 3.6 C15.6 7.0 15.6 17.0 12 20.4 C8.4 17.0 8.4 7.0 12 3.6" },
        )

        SUN -> mix(
            listOf(Circle(12.0, 12.0, 4.4)),
            listOf(
                Line(12.0, 2.6, 12.0, 4.8),
                Line(12.0, 19.2, 12.0, 21.4),
                Line(2.6, 12.0, 4.8, 12.0),
                Line(19.2, 12.0, 21.4, 12.0),
                Line(5.4, 5.4, 7.0, 7.0),
                Line(17.0, 17.0, 18.6, 18.6),
                Line(18.6, 5.4, 17.0, 7.0),
                Line(7.0, 17.0, 5.4, 18.6),
            ),
        )

        MOON -> outline(
            SVGPath().apply { content = "M20.2 14.6 A8.6 8.6 0 0 1 9.4 3.8 A8.6 8.6 0 1 0 20.2 14.6 Z" }
        )

        PALETTE -> mix(
            listOf(
                Circle(9.0, 9.4, 1.5),
                Circle(14.8, 8.6, 1.5),
                Circle(8.2, 14.6, 1.5),
            ),
            listOf(Circle(12.0, 12.0, 8.4)),
        )

        SHIELD -> outline(
            SVGPath().apply {
                content = "M12 3.0 L20.0 6.2 V12.0 C20.0 17.0 16.4 20.0 12 21.2 C7.6 20.0 4.0 17.0 4.0 12.0 V6.2 Z"
            }
        )

        ZAP -> filled(
            Polygon(13.6, 2.6, 6.2, 13.4, 11.0, 13.4, 9.6, 21.4, 17.8, 10.2, 12.8, 10.2)
        )

        CLOCK -> outline(
            Circle(12.0, 12.0, 8.4),
            Line(12.0, 7.0, 12.0, 12.5),
            Line(12.0, 12.5, 16.0, 14.5),
        )

        MORE -> filled(
            Circle(12.0, 5.6, 1.8),
            Circle(12.0, 12.0, 1.8),
            Circle(12.0, 18.4, 1.8),
        )

        EDIT -> outline(
            SVGPath().apply {
                content = "M4.4 19.6 L5.6 15.0 L15.8 4.8 A2.4 2.4 0 0 1 19.2 8.2 L9.0 18.4 Z"
            }
        )

        COPY -> outline(
            Rectangle(11.6, 11.6).apply { x = 8.6; y = 8.6; arcWidth = 3.0; arcHeight = 3.0 },
            SVGPath().apply { content = "M6.0 15.4 H4.4 V3.4 H16.4 V5.0" },
        )

        UPLOAD -> mix(
            listOf(Polygon(7.8, 9.0, 12.0, 4.0, 16.2, 9.0)),
            listOf(
                Line(12.0, 4.4, 12.0, 16.0),
                Line(4.4, 20.0, 19.6, 20.0),
            ),
        )

        TV -> outline(
            Rectangle(18.2, 13.4).apply { x = 2.9; y = 4.6; arcWidth = 4.0; arcHeight = 4.0 },
            Line(8.8, 21.0, 15.2, 21.0),
            Line(12.0, 18.2, 12.0, 21.0),
        )

        FILM -> mix(
            listOf(
                Rectangle(5.6, 4.0).apply { x = 2.9; y = 8.0; arcWidth = 1.2; arcHeight = 1.2 },
                Rectangle(5.6, 4.0).apply { x = 15.5; y = 8.0; arcWidth = 1.2; arcHeight = 1.2 },
            ),
            listOf(
                Rectangle(18.2, 15.0).apply { x = 2.9; y = 4.5; arcWidth = 4.0; arcHeight = 4.0 },
                Line(8.7, 4.5, 8.7, 19.5),
                Line(15.3, 4.5, 15.3, 19.5),
                Line(2.9, 12.0, 21.1, 12.0),
            ),
        )

        CAST -> outline(
            SVGPath().apply { content = "M3.6 19.6 H8.2" },
            SVGPath().apply { content = "M3.6 15.4 A4.2 4.2 0 0 1 7.8 19.6" },
            SVGPath().apply { content = "M3.6 11.2 A8.4 8.4 0 0 1 12.0 19.6" },
            Rectangle(9.4, 9.4).apply { x = 11.6; y = 5.4; arcWidth = 3.4; arcHeight = 3.4 },
        )

        IMAGE -> mix(
            listOf(Circle(8.6, 9.6, 1.7)),
            listOf(
                Rectangle(18.2, 15.0).apply { x = 2.9; y = 4.5; arcWidth = 4.0; arcHeight = 4.0 },
                SVGPath().apply { content = "M4.4 17.2 L9.8 11.8 L13.4 15.2 L16.4 12.4 L20.0 16.0" },
            ),
        )

        LOCK -> outline(
            Rectangle(14.2, 10.2).apply { x = 4.9; y = 10.4; arcWidth = 3.4; arcHeight = 3.4 },
            SVGPath().apply { content = "M8.2 10.4 V7.8 A3.8 3.8 0 0 1 15.8 7.8 V10.4" },
        )

        CLOUD -> outline(
            SVGPath().apply {
                content = "M7.2 18.8 A4.4 4.4 0 0 1 7.4 10.0 A5.6 5.6 0 0 1 17.8 10.6 A4.1 4.1 0 0 1 17.4 18.8 Z"
            }
        )

        SORT -> outline(
            Line(4.0, 7.0, 20.4, 7.0),
            Line(4.0, 12.0, 14.6, 12.0),
            Line(4.0, 17.0, 9.4, 17.0),
        )

        TAG -> mix(
            listOf(Circle(8.4, 8.4, 1.6)),
            listOf(
                SVGPath().apply { content = "M4.0 11.6 V4.0 H11.6 L20.4 12.8 L12.8 20.4 Z" },
            ),
        )

        USER -> mix(
            listOf(Circle(12.0, 8.6, 3.8)),
            listOf(SVGPath().apply { content = "M4.8 20.4 A7.2 7.2 0 0 1 19.2 20.4" }),
        )

        SPARKLE -> filled(
            SVGPath().apply {
                content = "M12 2.6l1.7 5.1 5.1 1.7-5.1 1.7L12 16.2l-1.7-5.1L5.2 9.4l5.1-1.7z M18.6 15.4l.8 2.4 2.4.8-2.4.8-.8 2.4-.8-2.4-2.4-.8 2.4-.8z"
            }
        )

        else -> outline(Rectangle(14.0, 14.0).apply { x = 5.0; y = 5.0; arcWidth = 4.0; arcHeight = 4.0 })
    }

    private fun heartPath(): SVGPath = SVGPath().apply {
        content = "M12 20.8 C12 20.8 3.2 14.6 3.2 8.9 C3.2 6.0 5.4 3.9 8.2 3.9 C10.2 3.9 11.5 5.1 12 6.3 " +
            "C12.5 5.1 13.8 3.9 15.8 3.9 C18.6 3.9 20.8 6.0 20.8 8.9 C20.8 14.6 12 20.8 12 20.8 Z"
    }

    /** Helper for callers that build their own stroked shapes. */
    fun styledStroke(shape: Shape, shapeClass: String): Shape = shape.apply {
        styleClass.add(shapeClass)
        strokeLineCap = StrokeLineCap.ROUND
        strokeLineJoin = StrokeLineJoin.ROUND
    }
}
