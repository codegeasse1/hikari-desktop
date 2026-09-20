package desktop.ui

import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import javafx.animation.FadeTransition
import javafx.animation.Interpolator
import javafx.animation.ScaleTransition
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.Tooltip
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.Duration

/**
 * The poster card used by every grid and rail.
 *
 * Composition matches the Android app's artwork: a 2:3 frame with rounded
 * corners, a bottom scrim, floating badges, a centre play button that fades in on
 * hover, an optional continue-watching progress bar, and title/meta underneath.
 * Images stream in through [desktop.img.ImageLoader] so a slow CDN never blocks
 * the UI thread.
 */
object PosterCard {

    private const val RADIUS = 12.0

    fun make(
        item: MediaItem,
        onOpen: (MediaItem) -> Unit,
        width: Double = Theme.POSTER_W,
        height: Double = width * Theme.POSTER_RATIO,
        progress: Double? = null,
        cornerBadge: String? = null,
        showTitle: Boolean = true,
        onMore: ((MediaItem) -> Unit)? = null,
    ): VBox {
        val image = ImageView().apply {
            fitWidth = width
            fitHeight = height
            isPreserveRatio = true
            isSmooth = true
            styleClass.add("poster-img")
            clip = Rectangle(width, height).apply {
                arcWidth = RADIUS * 2
                arcHeight = RADIUS * 2
            }
        }
        desktop.img.ImageLoader.loadAsync(
            item.posterUrl,
            onReady = { img -> if (img != null) image.image = img },
            w = (width * 2).toInt(),
            h = (height * 2).toInt(),
        )

        val scrim = Region().apply {
            styleClass.add("poster-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }

        val badgeRow = HBox(5.0).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("poster-badges")
        }
        item.year?.let { badgeRow.children.add(Ui.badge(it.toString(), "badge-glass")) }
        if (item.type == MediaType.SERIES) badgeRow.children.add(Ui.badge("SERIES", "badge-glass"))
        cornerBadge?.let { badgeRow.children.add(Ui.badge(it, "badge-glass")) }

        val moreButton = onMore?.let { handler ->
            Ui.iconButton(Icons.MORE, "More", size = 16.0) { handler(item) }.apply { opacity = 0.0 }
        }

        val topBar = HBox(badgeRow).apply {
            alignment = Pos.TOP_LEFT
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(badgeRow, Priority.ALWAYS)
            if (moreButton != null) children.add(moreButton)
        }

        val playButton = StackPane(Icons.of(Icons.PLAY, 20.0)).apply {
            styleClass.add("poster-play")
            opacity = 0.0
            isMouseTransparent = true
        }

        val frameChildren = mutableListOf<Node>(image, scrim)
        progress?.let { fraction ->
            val track = Region().apply {
                styleClass.add("poster-progress-track")
                maxWidth = Double.MAX_VALUE
            }
            val fill = Region().apply {
                styleClass.add("poster-progress-fill")
                val w = (width - 20.0) * fraction.coerceIn(0.0, 1.0)
                prefWidth = w
                minWidth = w
                maxWidth = w
            }
            val bar = StackPane(track, fill).apply {
                alignment = Pos.CENTER_LEFT
                prefWidth = width - 20.0
                minWidth = width - 20.0
                maxWidth = width - 20.0
                prefHeight = 4.0
                minHeight = 4.0
                maxHeight = 4.0
                isMouseTransparent = true
            }
            StackPane.setAlignment(bar, Pos.BOTTOM_CENTER)
            StackPane.setMargin(bar, javafx.geometry.Insets(0.0, 0.0, 10.0, 0.0))
            frameChildren.add(bar)
        }
        StackPane.setAlignment(topBar, Pos.TOP_CENTER)
        frameChildren.add(topBar)
        frameChildren.add(playButton)

        val frame = StackPane().apply {
            styleClass.add("poster-frame")
            prefWidth = width
            prefHeight = height
            minWidth = width
            minHeight = height
            maxWidth = width
            maxHeight = height
            children.addAll(frameChildren)
        }

        val title = Label(item.title).apply {
            styleClass.add("poster-title")
            isWrapText = true
            maxWidth = width
            prefWidth = width
            textOverrun = OverrunStyle.ELLIPSIS
            maxHeight = 34.0
        }
        val meta = Label(metaLine(item)).apply {
            styleClass.add("poster-meta")
            maxWidth = width
            prefWidth = width
            textOverrun = OverrunStyle.ELLIPSIS
        }

        val card = VBox(Theme.S2).apply {
            styleClass.add("poster-card")
            cursor = Cursor.HAND
            prefWidth = width
            minWidth = width
            maxWidth = width
            children.add(frame)
            if (showTitle) {
                children.add(title)
                children.add(meta)
            }
            Tooltip.install(this, Ui.tooltip(tooltipText(item)))
            setOnMouseClicked { onOpen(item) }
        }

        card.setOnMouseEntered {
            ScaleTransition(Duration.millis(130.0), card).apply {
                toX = 1.035
                toY = 1.035
                interpolator = Interpolator.EASE_OUT
                play()
            }
            fade(playButton, 1.0)
            moreButton?.let { fade(it, 1.0) }
        }
        card.setOnMouseExited {
            ScaleTransition(Duration.millis(140.0), card).apply {
                toX = 1.0
                toY = 1.0
                interpolator = Interpolator.EASE_OUT
                play()
            }
            fade(playButton, 0.0)
            moreButton?.let { fade(it, 0.0) }
        }

        return card
    }

    private fun fade(node: Node, to: Double) {
        FadeTransition(Duration.millis(140.0), node).apply {
            toValue = to
            play()
        }
    }

    private fun metaLine(item: MediaItem): String {
        val parts = mutableListOf<String>()
        when (item.type) {
            MediaType.MOVIE -> parts.add("Movie")
            MediaType.SERIES -> parts.add("Series")
            MediaType.UNKNOWN -> {}
        }
        item.year?.let { parts.add(it.toString()) }
        if (item.genres.isNotEmpty()) parts.add(item.genres.take(2).joinToString(", "))
        return parts.joinToString(" · ")
    }

    private fun tooltipText(item: MediaItem): String {
        val overview = item.overview?.takeIf { it.isNotBlank() }?.let {
            "\n\n" + it.take(240) + if (it.length > 240) "…" else ""
        } ?: ""
        return item.title + overview
    }
}

/**
 * Legacy entry point kept so existing call sites (`HomeScreen`, `SearchScreen`)
 * keep compiling while the newer [PosterCard.make] options are adopted screen by
 * screen.
 */
fun posterCard(media: MediaItem, onClick: () -> Unit): VBox =
    PosterCard.make(media, onOpen = { onClick() })

/** A headed, horizontally scrolling poster rail — the home screen's building block. */
object PosterRail {

    fun of(
        title: String,
        subtitle: String?,
        items: List<MediaItem>,
        onOpen: (MediaItem) -> Unit,
        onMore: ((MediaItem) -> Unit)? = null,
        trailing: Node? = null,
    ): VBox {
        val row = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = javafx.geometry.Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
        }
        items.forEach { item ->
            row.children.add(PosterCard.make(item, onOpen, onMore = onMore))
        }
        return VBox(Theme.S3, Ui.sectionHeader(title, subtitle, trailing), Ui.rail(row))
    }
}
