package desktop.ui

import com.hikari.app.data.CatalogRow
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import desktop.fx.Fx
import javafx.animation.FadeTransition
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: a featured hero banner pulled from whatever the newest catalogs
 * returned, followed by one horizontal rail per catalog.
 *
 * Rows render the moment each catalog lands, so the page fills in progressively
 * instead of waiting on the slowest addon, and a failing catalog is skipped
 * rather than becoming fatal.
 */
class HomeScreenView {

    // ── hero ────────────────────────────────────────────────────────────────

    private val heroImage = ImageView().apply {
        isPreserveRatio = false
        isSmooth = true
        fitHeight = HERO_H
    }
    private val heroOver = themed("Featured", "hero-over")
    private val heroTitle = themed("", "hero-title")
    private val heroMeta = themed("", "hero-meta")
    private val heroDesc = themed("", "hero-desc").apply {
        isWrapText = true
        maxWidth = 620.0
        maxHeight = 54.0
    }
    private val heroActions = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroDots = HBox(7.0).apply { alignment = Pos.CENTER_RIGHT }
    private val heroStack = StackPane().apply {
        styleClass.add("hero-wrap")
        prefHeight = HERO_H
        minHeight = HERO_H
        maxHeight = HERO_H
    }
    private var heroItems: List<MediaItem> = emptyList()
    private var heroIndex = 0
    private var heroTimer: Timeline? = null

    /** The banner's loaded artwork, kept so the cover-crop can be recomputed
     *  whenever the banner is resized (see [paintHeroArt]). */
    private var heroArt: javafx.scene.image.Image? = null

    private fun paintHeroArt() {
        Ui.coverImage(heroImage, heroArt, heroStack.width, heroStack.height)
    }

    // ── page ────────────────────────────────────────────────────────────────

    private val rowsBox = VBox(Theme.S4)
    private val providerBox = ComboBox<String>()
    private val statusLabel = themed("", "tiny")
    private val errorLabel = themed("", "tiny").apply {
        styleClass.add("h-danger")
        isWrapText = true
    }
    private val logsLabel = themed("", "h-mono").apply { isWrapText = true }
    private val logsScroll = ScrollPane(logsLabel).apply {
        prefHeight = 260.0
        isFitToWidth = true
        styleClass.add("scroll-pane")
        // A ScrollPane reports its content's size as its own minimum, and this
        // one holds up to 400 wrapped log lines — left alone it would grow to
        // thousands of pixels tall and push the page apart.
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
    }
    private val logsBtn = Ui.button("Logs", icon = Icons.LIST, ghost = true) { toggleLogs() }
    private val refreshBtn = Ui.button("Refresh", icon = Icons.REFRESH, primary = true) { load(force = true) }
    private val content = VBox(Theme.S4)
    val root: ScrollPane = Ui.vScroll(content)

    private var loadJob: Job? = null
    private var gen = 0
    private var logsOpen = false

    init {
        providerBox.run {
            styleClass.add("combo-box")
            minWidth = 220.0
            setOnAction { load(force = true) }
        }
        logsScroll.isVisible = false
        logsScroll.isManaged = false
        buildHero()
        content.children.addAll(
            HBox(8.0, providerBox, refreshBtn, logsBtn).apply {
                alignment = Pos.CENTER_RIGHT
                padding = Insets(0.0, 0.0, 2.0, 0.0)
            },
            heroStack,
            statusLabel,
            errorLabel,
            rowsBox,
            logsScroll,
        )
        heroStack.isVisible = false
        heroStack.isManaged = false
        // The auto-rotating banner is the only long-lived animation on this
        // screen: stop it (and restart it on return) instead of letting it tick
        // against a detached scene graph.
        root.sceneProperty().addListener { _, _, scene ->
            if (scene == null) heroTimer?.stop() else heroTimer?.play()
        }
    }

    fun onShown() {
        load()
    }

    // ── hero ────────────────────────────────────────────────────────────────

    private fun buildHero() {
        val clip = Rectangle().apply {
            arcWidth = 36.0
            arcHeight = 36.0
        }
        clip.widthProperty().bind(heroStack.widthProperty())
        clip.heightProperty().bind(heroStack.heightProperty())
        heroStack.clip = clip
        heroImage.isPreserveRatio = false
        heroImage.fitWidthProperty().bind(heroStack.widthProperty())
        heroImage.fitHeightProperty().bind(heroStack.heightProperty())
        heroStack.widthProperty().addListener { _, _, _ -> paintHeroArt() }
        heroStack.heightProperty().addListener { _, _, _ -> paintHeroArt() }

        val scrimV = Region().apply {
            styleClass.add("hero-scrim-v")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            // Decoration only — it must never swallow the hero's own click.
            isMouseTransparent = true
        }
        val scrimH = Region().apply {
            styleClass.add("hero-scrim-h")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }

        val body = VBox(10.0, heroOver, heroTitle, heroMeta, heroDesc, heroActions).apply {
            alignment = Pos.BOTTOM_LEFT
            maxWidth = 640.0
        }
        StackPane.setAlignment(body, Pos.BOTTOM_LEFT)
        StackPane.setMargin(body, Insets(0.0, 24.0, 26.0, 30.0))

        val prev = Ui.iconButton(Icons.CHEVRON_LEFT, "Previous", 16.0) { stepHero(-1) }.apply { styleClass.add("round-btn") }
        val next = Ui.iconButton(Icons.CHEVRON_RIGHT, "Next", 16.0) { stepHero(1) }.apply { styleClass.add("round-btn") }
        // The arrows sit inside a clickable banner, so their clicks must not
        // also open the featured title.
        Ui.isolateClicks(prev)
        Ui.isolateClicks(next)
        val nav = HBox(14.0, heroDots, prev, next).apply { alignment = Pos.CENTER_RIGHT }
        StackPane.setAlignment(nav, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(nav, Insets(0.0, 30.0, 32.0, 0.0))

        heroStack.children.addAll(heroImage, scrimV, scrimH, body, nav)
        heroStack.cursor = javafx.scene.Cursor.HAND
        // Clicking the banner (anywhere that isn't a button) opens the title.
        heroStack.setOnMouseClicked { heroItems.getOrNull(heroIndex)?.let { AppShell.openDetail(it) } }
        heroStack.setOnMouseEntered { heroTimer?.pause() }
        heroStack.setOnMouseExited { heroTimer?.play() }
    }

    private fun setHeroItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        heroItems = items.take(6)
        heroIndex = 0
        heroStack.isVisible = true
        heroStack.isManaged = true
        showHero()
        heroTimer?.stop()
        heroTimer = Timeline(
            KeyFrame(Duration.seconds(10.0), javafx.event.EventHandler<javafx.event.ActionEvent> { stepHero(1) }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun stepHero(delta: Int) {
        if (heroItems.isEmpty()) return
        heroIndex = ((heroIndex + delta) % heroItems.size + heroItems.size) % heroItems.size
        showHero()
        if (delta != 0 && heroTimer?.cycleCount == Timeline.INDEFINITE) {
            heroTimer?.stop()
            heroTimer?.playFromStart()
        }
    }

    private fun showHero() {
        val item = heroItems.getOrNull(heroIndex) ?: return
        heroTitle.text = item.title
        heroMeta.text = listOfNotNull(
            item.year?.toString(),
            when (item.type) {
                MediaType.SERIES -> "Series"
                MediaType.MOVIE -> "Movie"
                MediaType.UNKNOWN -> null
            },
            item.genres.take(3).joinToString(" · ").ifBlank { null },
        ).joinToString("  ·  ")
        heroDesc.text = item.overview?.replace(Regex("<[^>]*>"), "")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        heroImage.opacity = 0.0
        val onImageReady: (javafx.scene.image.Image?) -> Unit = { img ->
            heroArt = img
            paintHeroArt()
            if (img != null) {
                Ui.fade(heroImage, 1.0, 300.0)
            }
        }
        val art = desktop.img.ImageLoader.artFor(item.backdropUrl, item.posterUrl)
        if (!art.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(art, onReady = onImageReady, w = 1600, h = 700)
        }
        heroActions.children.setAll(
            Ui.playButton("Watch now") { AppShell.openDetail(item) },
            Ui.button("Add to list", icon = Icons.PLUS, ghost = true) { addToLibrary(item) },
            Ui.button("Details", icon = Icons.INFO, ghost = true) { AppShell.openDetail(item) },
        )
        heroDots.children.setAll(heroItems.mapIndexed { index, _ ->
            Ui.isolateClicks(Region().apply {
                styleClass.add("hero-dot")
                if (index == heroIndex) styleClass.add("hero-dot-sel")
                setOnMouseClicked { heroIndex = index; showHero() }
            })
        })
    }

    private fun addToLibrary(item: MediaItem) {
        if (runCatching { AppShell.app.store.favorites() }.getOrDefault(emptyList()).any { it.uniqueId == item.uniqueId }) {
            AppShell.toast("Already in your library")
        } else {
            runCatching { AppShell.app.store.addFavorite(item) }
            AppShell.toast("Added “${item.title}” to your library", "ok")
        }
    }

    private fun toggleLogs() {
        logsOpen = !logsOpen
        logsScroll.isVisible = logsOpen
        logsScroll.isManaged = logsOpen
        if (logsOpen) refreshLogs()
    }

    private fun refreshLogsButton() {
        val text = com.hikari.app.util.LiveLogs.recentText()
        val lines = text.count { it == '\n' } + 1
        logsBtn.text = if (text.isBlank()) "Logs" else "Logs ($lines)"
    }

    private fun refreshLogs() {
        refreshLogsButton()
        logsLabel.text = com.hikari.app.util.LiveLogs.recentText(400)
        if (logsLabel.text.isBlank()) logsLabel.text = "(no logs captured yet — load a catalog first)"
        logsScroll.vvalue = 1.0
    }

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        val selected = providerBox.value ?: ""
        val myGen = ++gen
        loadJob = AppShell.uiScope.launch {
            try {
                Fx.run {
                    errorLabel.text = ""
                    statusLabel.text = "Loading…"
                    rowsBox.children.setAll(Ui.skeletonRail(), Ui.skeletonRail())
                }
                // The startup provider refresh runs in the background — wait for
                // it to finish once, so the first screen reflects real state.
                var attempts = 0
                while (attempts < 12 && !AppShell.app.providers.initialized.value) {
                    delay(500)
                    attempts++
                }
                val enabled = AppShell.app.store.providers().filter { it.enabled }.distinctBy { it.name }
                val providerNames = enabled.map { it.name }
                // Resolve the ACTIVE choice on the FX thread from the ComboBox's
                // LIVE value. JavaFX can deliver a popup's action event before
                // the chosen item is committed to `value`, so a value captured at
                // load() start can be stale ("All providers") and would clobber
                // the user's pick back to "All providers".
                val (chosen, filterId) = Fx.runBlock {
                    val items = providerBox.items
                    val all = listOf("All providers") + providerNames
                    for (n in all) if (!items.contains(n)) items.add(n)
                    val current = providerBox.value
                    val c = when {
                        current != null && current in items -> current
                        selected in items -> selected
                        else -> "All providers"
                    }
                    providerBox.value = c
                    val cfg = enabled.firstOrNull { it.name == c }
                    c to cfg?.id?.takeIf { c != "All providers" }
                }
                val firstRow = booleanArrayOf(false)
                val rows = AppShell.app.repository.homeRows(filterId, force = force) { row ->
                    Fx.run {
                        if (myGen == gen) {
                            if (!firstRow[0]) {
                                firstRow[0] = true
                                rowsBox.children.clear()
                            }
                            if (!heroSeeded) {
                                val candidates = row.items.filter {
                                    !it.posterUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank()
                                }
                                if (candidates.isNotEmpty()) {
                                    heroSeeded = true
                                    setHeroItems(candidates)
                                }
                            }
                            runCatching { rowsBox.children.add(buildRow(row)) }
                        }
                    }
                }
                Fx.run { render(rows, filterId, chosen) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Fx.run {
                    statusLabel.text = ""
                    errorLabel.text = "Failed to load home rows: ${t.message}"
                }
            }
        }
    }

    /** The first catalog that lands supplies the hero. */
    private var heroSeeded = false

    private fun render(rows: List<CatalogRow>, filterId: String?, chosen: String? = null) {
        statusLabel.text = ""
        if (!heroSeeded) {
            val candidates = rows.flatMap { it.items }
                .filter { !it.posterUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank() }
                .distinctBy { it.title }
            if (candidates.isNotEmpty()) {
                heroSeeded = true
                setHeroItems(candidates)
            }
        }
        val statuses = AppShell.app.providers.statuses.value
        val failed = statuses.filter { !it.loaded }
        val enabledCount = statuses.size
        when {
            enabledCount == 0 -> {
                errorLabel.text = ""
                rowsBox.children.setAll(
                    Ui.emptyState(
                        Icons.EXTENSIONS,
                        "No sources yet",
                        "Add a Stremio addon, paste a universal scraper, or install a CloudStream repo from the Extensions screen.",
                        Ui.button("Open Extensions", icon = Icons.EXTENSIONS, primary = true) {
                            AppShell.show(Screen.Extensions)
                        },
                    )
                )
            }

            failed.isNotEmpty() -> {
                errorLabel.text = ""
                statusLabel.text = buildString {
                    append("${enabledCount} provider(s) enabled, ${statuses.count { it.loaded }} loaded, ${failed.size} failed to start: ")
                    append(failed.joinToString("  ·  ") { "${it.name}: ${it.error ?: "unknown error"}" })
                }
                if (rows.isEmpty()) fallback("Nothing loaded from the enabled sources.")
            }

            rows.isEmpty() -> {
                errorLabel.text = ""
                val who = chosen?.takeIf { it != "All providers" } ?: statuses.firstOrNull { it.id == filterId }?.name
                statusLabel.text = (if (who != null) "'$who' returned no catalog rows" else "No catalog rows loaded") +
                    " — open the logs for the real reason."
                fallback("No catalog rows loaded.")
            }

            else -> {
                errorLabel.text = ""
                statusLabel.text = "${rows.size} row(s) from ${statuses.count { it.loaded }} loaded source(s)"
            }
        }
        refreshLogsButton()
    }

    private fun fallback(message: String) {
        if (rowsBox.children.isNotEmpty()) return
        rowsBox.children.setAll(
            Ui.emptyState(Icons.INFO, message, "Check the logs for the underlying error, then try Refresh.")
        )
        if (!logsOpen) toggleLogs()
    }

    private fun buildRow(row: CatalogRow): Region = PosterRail.of(
        title = row.title,
        subtitle = row.providerName,
        items = row.items,
        onOpen = { AppShell.openDetail(it) },
        onSeeAll = {
            AppShell.show(Screen.Catalog(row.title, row.providerName, row.items))
        },
    )

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    private companion object {
        const val HERO_H = 320.0
    }
}
