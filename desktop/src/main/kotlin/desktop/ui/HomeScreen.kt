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

    /**
     * The featured card: the title's POSTER on the left (the same artwork the
     * grid thumbnail uses — a poster is high-resolution and crisp at this size,
     * where an upscaled backdrop was the blurry banner), and the details on the
     * right, with the page dots under the card.
     */
    private val heroPoster = ImageView().apply {
        isPreserveRatio = false
        isSmooth = true
    }
    private var heroPosterArt: javafx.scene.image.Image? = null
    private val heroPosterClip = Rectangle().apply { arcWidth = 16.0; arcHeight = 16.0 }
    private val heroPosterFrame = StackPane(heroPoster).apply {
        styleClass.add("hero-poster")
        clip = heroPosterClip
    }

    private fun paintHeroPoster() {
        Ui.coverImage(heroPoster, heroPosterArt, POSTER_W, POSTER_H)
    }

    private val heroOver = themed("Featured", "hero-over")
    private val heroTitle = themed("", "hero-title").apply {
        // Wrapping the title (instead of letting it declare its full width as a
        // minimum) is what keeps a long title — or a long provider name above it
        // — from making the whole page wider than the window.
        isWrapText = true
        maxWidth = 560.0
        minWidth = 0.0
        maxHeight = 74.0
    }
    private val heroMeta = themed("", "hero-meta").apply {
        isWrapText = true
        maxWidth = 560.0
        minWidth = 0.0
    }
    private val heroDesc = themed("", "hero-desc").apply {
        isWrapText = true
        maxWidth = 560.0
        minWidth = 0.0
        maxHeight = 68.0
        textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
    }
    private val heroActions = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroDots = HBox(7.0).apply { alignment = Pos.CENTER }
    private val heroStack = StackPane().apply {
        styleClass.add("hero-wrap")
        prefHeight = HERO_H
        minHeight = HERO_H
        maxHeight = HERO_H
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
    }
    private val heroDotsRow = StackPane(heroDots).apply {
        alignment = Pos.CENTER
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
    }
    /** The hero as it is laid out on the page: the card, then the dots. */
    private val heroBox = VBox(8.0, heroStack, heroDotsRow).apply {
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
    }
    private var heroItems: List<MediaItem> = emptyList()
    private var heroIndex = 0
    private var heroTimer: Timeline? = null

    /** The provider filter the hero's items were picked for. The hero is
     *  re-seeded whenever this changes, so it always features the provider the
     *  user has selected — not whichever provider happened to load first. */
    private var heroFilter: String? = null

    /** providerId → name, filled on every load so the hero can name the source
     *  of the title it is featuring without re-reading the store. */
    private var providerNamesById: Map<String, String> = emptyMap()

    // ── page ────────────────────────────────────────────────────────────────

    private val rowsBox = VBox(Theme.S4)
    /** The catalog's provider picker: chips by engine + a filter field + the
     *  list, and the only control that decides which provider the home page is
     *  built from. It calls back on every pick, so the catalog reloads. */
    private val providerPicker = ProviderPicker { load(force = true) }

    /** The provider picker, for the UI test (see AppShell.homeView). */
    val pickerForTest: ProviderPicker get() = providerPicker
    private val statusLabel = themed("", "tiny").apply {
        // A long status line (a dozen failed providers, "10 rows from 102
        // sources") must wrap: without this it declares a minimum width wider
        // than the window and slides the whole page sideways.
        isWrapText = true
        maxWidth = 900.0
        minWidth = 0.0
    }
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
        logsScroll.isVisible = false
        logsScroll.isManaged = false
        buildHero()
        content.children.addAll(
            // The provider picker (see ProviderPicker): a filter field, a chip
            // per engine and the list, opened from the button that shows the
            // current choice — the Android app's provider sheet, in the desktop
            // toolbar.
            // The toolbar sits at the right edge, but it is free to SCROLL rather
            // than squeeze: the picker button carries a provider's whole name,
            // and a name that does not fit used to be drawn as "…".
            HBox(
                Ui.spacer(),
                Ui.chipRow(
                    HBox(8.0, providerPicker.button, refreshBtn, logsBtn).apply {
                        alignment = Pos.CENTER_LEFT
                        minWidth = 0.0
                    }
                ),
            ).apply {
                alignment = Pos.CENTER_RIGHT
                padding = Insets(0.0, 0.0, 2.0, 0.0)
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
            },
            heroBox,
            statusLabel,
            errorLabel,
            rowsBox,
            logsScroll,
        )
        heroBox.isVisible = false
        heroBox.isManaged = false
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
            arcWidth = 30.0
            arcHeight = 30.0
        }
        clip.widthProperty().bind(heroStack.widthProperty())
        clip.heightProperty().bind(heroStack.heightProperty())
        heroStack.clip = clip
        // The poster is a fixed 2:3 card inside the featured card, so its art
        // never has to be re-cropped on resize.
        heroPoster.fitWidth = POSTER_W
        heroPoster.fitHeight = POSTER_H
        heroPosterClip.width = POSTER_W
        heroPosterClip.height = POSTER_H
        listOf(heroPosterFrame.prefWidthProperty(), heroPosterFrame.minWidthProperty(),
            heroPosterFrame.maxWidthProperty()).forEach { it.set(POSTER_W) }
        listOf(heroPosterFrame.prefHeightProperty(), heroPosterFrame.minHeightProperty(),
            heroPosterFrame.maxHeightProperty()).forEach { it.set(POSTER_H) }

        val body = VBox(8.0, heroOver, heroTitle, heroMeta, heroDesc, heroActions).apply {
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
        }
        HBox.setHgrow(body, Priority.ALWAYS)

        val layout = HBox(24.0, heroPosterFrame, body).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(CARD_PAD, 26.0, CARD_PAD, CARD_PAD)
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        heroStack.children.add(layout)
        heroStack.cursor = javafx.scene.Cursor.HAND

        val prev = Ui.iconButton(Icons.CHEVRON_LEFT, "Previous", 16.0) { stepHero(-1) }.apply { styleClass.add("round-btn") }
        val next = Ui.iconButton(Icons.CHEVRON_RIGHT, "Next", 16.0) { stepHero(1) }.apply { styleClass.add("round-btn") }
        // The arrows sit inside a clickable banner, so their clicks must not
        // also open the featured title.
        Ui.isolateClicks(prev)
        Ui.isolateClicks(next)
        val nav = HBox(8.0, prev, next).apply { alignment = Pos.CENTER_RIGHT }
        StackPane.setAlignment(nav, Pos.CENTER_RIGHT)
        StackPane.setMargin(nav, Insets(0.0, 4.0, 0.0, 0.0))
        heroDotsRow.children.add(nav)

        // Clicking the banner (anywhere that isn't a button) opens the title.
        heroStack.setOnMouseClicked { heroItems.getOrNull(heroIndex)?.let { AppShell.openDetail(it) } }
        heroStack.setOnMouseEntered { heroTimer?.pause() }
        heroStack.setOnMouseExited { heroTimer?.play() }
    }

    private fun setHeroItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        heroItems = items.take(6)
        heroIndex = 0
        heroBox.isVisible = true
        heroBox.isManaged = true
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
        // Name the source of the featured title, so it is obvious that the
        // banner follows the provider selector above it.
        val provider = providerNamesById[item.providerId]
        heroOver.text = if (provider.isNullOrBlank()) "Featured" else "Featured · $provider"
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
        heroPoster.opacity = 0.0
        heroPosterArt = null
        paintHeroPoster()
        val art = desktop.img.ImageLoader.artFor(item.posterUrl, item.backdropUrl)
        if (!art.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(
                art,
                onReady = { img ->
                    heroPosterArt = img
                    paintHeroPoster()
                    if (img != null) Ui.fade(heroPoster, 1.0, 300.0)
                },
                w = 480,
                h = 720,
            )
        } else {
            heroPoster.opacity = 1.0
        }
        heroActions.children.setAll(
            Ui.button("View Details", icon = Icons.PLAY, primary = true) { AppShell.openDetail(item) },
            Ui.isolateClicks(Ui.button("Add to list", icon = Icons.PLUS, ghost = true) { addToLibrary(item) }),
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
                val enabled = AppShell.app.store.providers().filter { it.enabled }
                    .distinctBy { it.name }
                    // Alphabetical, not install order: this is a list of names
                    // the user scans to find one, and the order they were added
                    // is meaningless to them.
                    .sortedBy { it.name.lowercase() }
                providerNamesById = enabled.associate { it.id to it.name }
                // The picker holds the user's choice, so it is read on the FX
                // thread from its LIVE state — there is no stale copy to
                // reconcile (the old combo box needed that dance because JavaFX
                // can deliver a popup's action event before the chosen item is
                // committed to `value`, which clobbered the user's pick back to
                // "All providers").
                val (chosen, filterId) = Fx.runBlock {
                    providerPicker.setProviders(enabled)
                    val cfg = providerPicker.selection
                    val c = providerPicker.selectedName()
                    // Re-seed the banner whenever the source filter changes, so
                    // the hero features the provider the user picked. Without
                    // this it kept showing whichever provider answered first and
                    // never changed again for the life of the screen.
                    if (c != heroFilter) {
                        heroFilter = c
                        heroSeeded = false
                        heroItems = emptyList()
                        heroIndex = 0
                        heroTimer?.stop()
                        heroBox.isVisible = false
                        heroBox.isManaged = false
                    }
                    c to cfg?.id
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
        /** The featured card's height; the 2:3 poster inside it fills it minus
         *  the card's padding. */
        const val HERO_H = 300.0
        const val CARD_PAD = 20.0
        const val POSTER_H = HERO_H - 2 * CARD_PAD
        const val POSTER_W = POSTER_H / 1.5
    }
}
