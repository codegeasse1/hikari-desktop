package desktop.ui

import com.hikari.app.data.CatalogRow
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.TmdbDetails
import desktop.fx.Fx
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
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
 * Home: a full-bleed cinematic hero (auto-rotating backdrop art of the
 * featured titles), a Continue Watching rail built from playback history, then
 * one headed rail per catalog with View All + paging arrows.
 *
 * Rows render the moment each catalog lands, so the page fills in progressively
 * instead of waiting on the slowest addon, and a failing catalog is skipped
 * rather than becoming fatal.
 */
class HomeScreenView {

    // ── hero ────────────────────────────────────────────────────────────────

    /** Two stacked artwork layers for the backdrop crossfade. */
    private val heroBgA = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
    private val heroBgB = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true; opacity = 0.0 }
    private var heroFgA = true
    private var heroArtA: javafx.scene.image.Image? = null
    private var heroArtB: javafx.scene.image.Image? = null
    private var heroGen = 0

    private fun paintHeroBg() {
        val w = heroStack.width
        val h = heroStack.height
        if (w > 0 && h > 0) {
            Ui.coverImage(heroBgA, heroArtA, w, h)
            Ui.coverImage(heroBgB, heroArtB, w, h)
        }
    }

    private val heroLogo = ImageView().apply {
        isPreserveRatio = true
        isSmooth = true
        fitHeight = 118.0
        isVisible = false
        isManaged = false
        isMouseTransparent = true
    }
    private val heroOver = themed("", "x-hero-over")
    private val heroTitle = themed("", "x-hero-title").apply {
        isWrapText = true
        maxWidth = 640.0
        minWidth = 0.0
    }
    private val heroMeta = themed("", "x-hero-meta").apply {
        isWrapText = true
        maxWidth = 640.0
        minWidth = 0.0
    }
    private val heroDesc = themed("", "x-hero-desc").apply {
        isWrapText = true
        maxWidth = 600.0
        minWidth = 0.0
    }
    private val heroActions = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroDots = HBox(7.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroStack = StackPane().apply {
        styleClass.add("x-hero")
        prefHeight = HERO_H
        minHeight = HERO_H
        maxHeight = HERO_H
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
    }
    private val heroBox = VBox(heroStack).apply {
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
    }
    private var heroItems: List<MediaItem> = emptyList()
    private var heroIndex = 0
    private var heroTimer: Timeline? = null
    /** Enriched TMDB data per hero item, so ratings/logos survive rotation. */
    private val heroEnrich = HashMap<String, TmdbDetails.Details?>()
    private var heroEnrichStarted = HashSet<String>()

    /** providerId → name, filled on every load so the hero can name the source
     *  of the title it is featuring without re-reading the store. */
    private var providerNamesById: Map<String, String> = emptyMap()

    /** The provider filter the hero's items were picked for. The hero is
     *  re-seeded whenever this changes, so it always features the provider the
     *  user has selected — not whichever provider happened to load first. */
    private var heroFilter: String? = null

    // ── continue watching ───────────────────────────────────────────────────

    private val cwSection = VBox(Theme.S3).apply {
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        isVisible = false
        isManaged = false
    }

    // ── page ────────────────────────────────────────────────────────────────

    private val rowsBox = VBox(Theme.S5)
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
            cwSection,
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

    /** Open Home showing this provider's catalog (from Explore rows). */
    fun showProvider(name: String) {
        pendingProvider = name
        AppShell.show(Screen.Home)
    }

    fun onShown() {
        load()
    }

    /** A provider Explore asked for while we were elsewhere. Consumed on the
     *  next load, after the picker list is fresh. */
    private var pendingProvider: String? = null

    // ── hero ────────────────────────────────────────────────────────────────

    private fun buildHero() {
        val clip = Rectangle().apply {
            arcWidth = 26.0
            arcHeight = 26.0
        }
        clip.widthProperty().bind(heroStack.widthProperty())
        clip.heightProperty().bind(heroStack.heightProperty())
        heroStack.clip = clip
        heroBgA.fitWidthProperty().bind(heroStack.widthProperty())
        heroBgA.fitHeightProperty().bind(heroStack.heightProperty())
        heroBgB.fitWidthProperty().bind(heroStack.widthProperty())
        heroBgB.fitHeightProperty().bind(heroStack.heightProperty())
        heroStack.widthProperty().addListener { _, _, _ -> paintHeroBg() }
        heroStack.heightProperty().addListener { _, _, _ -> paintHeroBg() }

        val scrim = Region().apply {
            styleClass.add("x-hero-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }
        val scrimL = Region().apply {
            styleClass.add("x-hero-scrim-l")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }

        val body = VBox(9.0, heroOver, heroLogo, heroTitle, heroMeta, heroDesc, heroActions).apply {
            alignment = Pos.BOTTOM_LEFT
            maxWidth = 680.0
            minWidth = 0.0
        }
        StackPane.setAlignment(body, Pos.BOTTOM_LEFT)
        StackPane.setMargin(body, Insets(0.0, 0.0, 30.0, 34.0))

        val prev = Ui.iconButton(Icons.CHEVRON_LEFT, "Previous", 16.0) { stepHero(-1) }.apply { styleClass.add("x-round-btn") }
        val next = Ui.iconButton(Icons.CHEVRON_RIGHT, "Next", 16.0) { stepHero(1) }.apply { styleClass.add("x-round-btn") }
        // The arrows sit inside a clickable banner, so their clicks must not
        // also open the featured title.
        Ui.isolateClicks(prev)
        Ui.isolateClicks(next)
        val nav = HBox(8.0, prev, next).apply { alignment = Pos.CENTER_RIGHT }
        StackPane.setAlignment(nav, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(nav, Insets(0.0, 22.0, 26.0, 0.0))

        // Dots ride bottom-right, left of the arrows: centered dots collided
        // with the action row on wide banners.
        val dotsWrap = HBox(10.0, heroDots).apply { alignment = Pos.CENTER_RIGHT }
        StackPane.setAlignment(dotsWrap, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(dotsWrap, Insets(0.0, 112.0, 32.0, 0.0))

        heroStack.children.addAll(heroBgA, heroBgB, scrim, scrimL, body, dotsWrap, nav)
        heroStack.cursor = javafx.scene.Cursor.HAND

        // Clicking the banner (anywhere that isn't a button) opens the title.
        heroStack.setOnMouseClicked { heroItems.getOrNull(heroIndex)?.let { AppShell.openDetail(it) } }
        heroStack.setOnMouseEntered { heroTimer?.pause() }
        heroStack.setOnMouseExited { heroTimer?.play() }
    }

    private fun setHeroItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        // Backdrop art first: the banner is a wide cinematic surface, and a
        // landscape backdrop fills it edge to edge where a poster cannot.
        val withArt = items.filter { !it.backdropUrl.isNullOrBlank() }
        heroItems = (if (withArt.size >= 3) withArt else items).take(8)
        heroIndex = 0
        heroBox.isVisible = true
        heroBox.isManaged = true
        showHero()
        heroTimer?.stop()
        heroTimer = Timeline(
            KeyFrame(Duration.seconds(9.0), javafx.event.EventHandler<javafx.event.ActionEvent> { stepHero(1) }),
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
        val myGen = ++heroGen
        // Name the source of the featured title, so it is obvious that the
        // banner follows the provider selector above it.
        val provider = providerNamesById[item.providerId]
        heroOver.text = if (provider.isNullOrBlank()) "Featured" else "Featured · $provider"
        heroOver.isVisible = true
        heroOver.isManaged = true
        val enrich = heroEnrich[item.uniqueId]
        if (enrich?.logoUrl != null) {
            heroLogo.isVisible = false
            heroLogo.isManaged = false
            desktop.img.ImageLoader.loadAsync(enrich.logoUrl, onReady = { img ->
                if (img != null && myGen == heroGen) {
                    heroLogo.image = img
                    heroLogo.isVisible = true
                    heroLogo.isManaged = true
                    heroTitle.isVisible = false
                    heroTitle.isManaged = false
                }
            }, w = 640, h = 200)
        } else {
            heroLogo.isVisible = false
            heroLogo.isManaged = false
            heroTitle.text = item.title
            heroTitle.isVisible = true
            heroTitle.isManaged = true
        }
        paintHeroMeta(item, enrich)
        heroDesc.text = item.overview?.replace(Regex("<[^>]*>"), "")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        // Backdrop first, poster as the fallback — never an empty banner.
        val art = desktop.img.ImageLoader.artFor(item.backdropUrl, item.posterUrl)
        if (!art.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(art, onReady = { img ->
                if (img != null && myGen == heroGen) crossfadeHero(img)
            }, w = 1600, h = 900)
        }
        heroActions.children.setAll(
            Ui.isolateClicks(Button("Watch Now").apply {
                styleClass.add("x-watch")
                // Belt and suspenders: the class owns the hover, but the
                // white-button identity is inline so it can never come out
                // dark no matter what else matches this button.
                style = "-fx-background-color: white; -fx-text-fill: #101218;"
                graphic = Icons.of(Icons.PLAY, 17.0)
                isFocusTraversable = false
                setOnAction { AppShell.openDetail(item) }
            }),
            Ui.isolateClicks(Ui.button("Add to list", icon = Icons.PLUS, ghost = true) {
                addToLibrary(item)
            }.apply { styleClass.add("x-glass-btn") }),
        )
        heroDots.children.setAll(heroItems.mapIndexed { index, _ ->
            Ui.isolateClicks(Region().apply {
                styleClass.add("x-hero-dot")
                if (index == heroIndex) styleClass.add("x-hero-dot-sel")
                setOnMouseClicked { heroIndex = index; showHero() }
            })
        })
        enrichHero(item)
    }

    private fun paintHeroMeta(item: MediaItem, enrich: TmdbDetails.Details?) {
        val parts = ArrayList<String>()
        val rating = enrich?.tmdbRating?.takeIf { it > 0.0 } ?: enrich?.imdbRating?.takeIf { it > 0.0 }
        val star = if (rating != null) "★ %.1f".format(rating) else null
        val meta = listOfNotNull(
            item.year?.toString(),
            when (item.type) {
                MediaType.SERIES -> "TV Series"
                MediaType.MOVIE -> "Movie"
                MediaType.UNKNOWN -> null
            },
            item.genres.take(2).joinToString(" • ").ifBlank { null },
        ).joinToString("   ")
        heroMeta.text = listOfNotNull(star, meta.ifBlank { null }).joinToString("    ")
    }

    /** Ratings + logo resolve in the background: the banner never waits on them. */
    private fun enrichHero(item: MediaItem) {
        if (heroEnrichStarted.contains(item.uniqueId)) return
        heroEnrichStarted.add(item.uniqueId)
        AppShell.uiScope.launch {
            val d = runCatching { TmdbDetails.load(item) }.getOrNull()
            heroEnrich[item.uniqueId] = d
            Fx.run {
                if (d?.overview?.isNotBlank() == true && heroDesc.text.isBlank()) {
                    heroDesc.text = d.overview
                }
                if (heroItems.getOrNull(heroIndex)?.uniqueId == item.uniqueId) showHero()
                else paintHeroMeta(item, d)
            }
        }
    }

    private fun crossfadeHero(img: javafx.scene.image.Image) {
        paintHeroBg()
        if (heroFgA) {
            heroArtB = img
            Ui.coverImage(heroBgB, img, heroStack.width, heroStack.height)
            Ui.fade(heroBgB, 1.0, 600.0)
            heroFgA = false
            javafx.animation.Timeline(
                javafx.animation.KeyFrame(Duration.millis(650.0),
                    javafx.event.EventHandler<javafx.event.ActionEvent> { heroBgA.opacity = 0.0; heroArtA = img }),
            ).play()
        } else {
            heroArtA = img
            Ui.coverImage(heroBgA, img, heroStack.width, heroStack.height)
            Ui.fade(heroBgA, 1.0, 600.0)
            heroFgA = true
            javafx.animation.Timeline(
                javafx.animation.KeyFrame(Duration.millis(650.0),
                    javafx.event.EventHandler<javafx.event.ActionEvent> { heroBgB.opacity = 0.0; heroArtB = img }),
            ).play()
        }
    }

    private fun addToLibrary(item: MediaItem) {
        if (runCatching { AppShell.app.store.favorites() }.getOrDefault(emptyList()).any { it.uniqueId == item.uniqueId }) {
            AppShell.toast("Already in your library")
        } else {
            runCatching { AppShell.app.store.addFavorite(item) }
            AppShell.toast("Added “${item.title}” to your library", "ok")
        }
    }

    // ── continue watching ───────────────────────────────────────────────────

    private fun buildContinueWatching() {
        val entries = runCatching { AppShell.app.store.history() }.getOrDefault(emptyList())
            .filter { it.positionMs > 30_000L && (it.durationMs <= 0L || it.positionMs < it.durationMs * 0.95) }
            .sortedByDescending { it.watchedAt }
            .take(12)
        if (entries.isEmpty()) {
            cwSection.isVisible = false
            cwSection.isManaged = false
            return
        }
        val row = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
            minWidth = 0.0
        }
        entries.forEach { row.children.add(continueCard(it)) }
        val scroll = Ui.rail(row)
        val clear = Ui.button("Clear History", ghost = true) {
            runCatching { AppShell.app.store.clearHistory() }
            buildContinueWatching()
        }.apply { styleClass.add("x-clear") }
        val viewAll = Button("View All").apply {
            styleClass.add("x-view-all")
            isFocusTraversable = false
            setOnAction { AppShell.show(Screen.History) }
        }
        val prev = Button().apply {
            styleClass.add("x-head-arrow")
            graphic = Icons.of(Icons.CHEVRON_LEFT, 15.0)
            isFocusTraversable = false
            setOnAction { Ui.nudge(scroll, -1) }
        }
        val next = Button().apply {
            styleClass.add("x-head-arrow")
            graphic = Icons.of(Icons.CHEVRON_RIGHT, 15.0)
            isFocusTraversable = false
            setOnAction { Ui.nudge(scroll, 1) }
        }
        val head = HBox(10.0,
            Theme.label("Continue Watching", size = 19.0, bold = true).apply { styleClass.add("x-rail-title") },
            Ui.spacer(), clear, viewAll, prev, next,
        ).apply { alignment = Pos.CENTER_LEFT; minWidth = 0.0 }
        cwSection.children.setAll(head, scroll)
        cwSection.isVisible = true
        cwSection.isManaged = true
    }

    private fun continueCard(e: HistoryEntry): VBox {
        val art = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
        val frame = StackPane(art).apply {
            styleClass.add("x-cw-art")
            clip = Rectangle(CW_W, CW_H).apply { arcWidth = 18.0; arcHeight = 18.0 }
            minWidth = CW_W; prefWidth = CW_W; maxWidth = CW_W
            minHeight = CW_H; prefHeight = CW_H; maxHeight = CW_H
        }
        art.fitWidth = CW_W
        art.fitHeight = CW_H
        if (!e.posterUrl.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(e.posterUrl, onReady = { img ->
                if (img != null) Ui.coverImage(art, img, CW_W, CW_H)
            }, w = 640, h = 360)
        }
        val scrim = Region().apply { styleClass.add("x-cw-scrim"); isMouseTransparent = true }
        val title = Theme.label(e.title, size = 14.5, bold = true).apply {
            styleClass.add("x-cw-title"); isWrapText = true; maxWidth = CW_W - 24.0; minWidth = 0.0; maxHeight = 40.0
        }
        val sub = Theme.label(
            e.episodeName.takeIf { it.isNotBlank() } ?: e.title, size = 11.5, dim = true
        ).apply { styleClass.add("x-cw-sub"); minWidth = 0.0; maxWidth = CW_W - 24.0 }
        val left = if (e.durationMs > 0L) formatLeft(e.durationMs - e.positionMs) else null
        val time = Theme.label(left ?: "", size = 11.0, dim = true).apply { styleClass.add("x-cw-time") }
        val bar = ProgressBar(if (e.durationMs > 0L) (e.positionMs.toDouble() / e.durationMs).coerceIn(0.0, 1.0) else 0.0).apply {
            styleClass.add("x-cw-prog"); prefWidth = CW_W - 24.0; maxWidth = CW_W - 24.0; minHeight = 4.0; prefHeight = 4.0; maxHeight = 4.0
            isMouseTransparent = true; isFocusTraversable = false
        }
        val texts = VBox(2.0, title, HBox(8.0, sub, Ui.spacer(), time).apply { alignment = Pos.CENTER_LEFT }, bar).apply {
            minWidth = 0.0; maxWidth = CW_W - 24.0
        }
        StackPane.setAlignment(texts, Pos.BOTTOM_LEFT)
        StackPane.setMargin(texts, Insets(0.0, 0.0, 12.0, 12.0))
        frame.children.addAll(scrim, texts)
        return VBox(frame).apply {
            styleClass.add("x-cw-card")
            cursor = javafx.scene.Cursor.HAND
            minWidth = CW_W; prefWidth = CW_W; maxWidth = CW_W
            setOnMouseClicked {
                AppShell.openDetail(
                    MediaItem(
                        providerId = e.providerId, id = e.mediaId, title = e.title, type = e.type,
                        posterUrl = e.posterUrl,
                    )
                )
            }
        }
    }

    private fun formatLeft(ms: Long): String {
        if (ms <= 0L) return ""
        val m = ms / 60_000L
        return if (m >= 60L) "${m / 60}h ${m % 60}m left" else "${m}m left"
    }

    // ── loading ─────────────────────────────────────────────────────────────

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
        buildContinueWatching()
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
                    // An Explore row asked for a provider while we were
                    // elsewhere: apply it now that the list is fresh, and the
                    // rest of this load reads it like a manual pick.
                    pendingProvider?.let { want ->
                        pendingProvider = null
                        providerPicker.select(want)
                    }
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

    private fun buildRow(row: CatalogRow): Region {
        val cards = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
            minWidth = 0.0
        }
        row.items.forEach { item ->
            cards.children.add(PosterCard.make(item, onOpen = { AppShell.openDetail(it) }))
        }
        val scroll = Ui.rail(cards)
        val viewAll = Button("View All").apply {
            styleClass.add("x-view-all")
            isFocusTraversable = false
            setOnAction { AppShell.show(Screen.Catalog(row.title, row.providerName, row.items)) }
        }
        val prev = Button().apply {
            styleClass.add("x-head-arrow")
            graphic = Icons.of(Icons.CHEVRON_LEFT, 15.0)
            isFocusTraversable = false
            setOnAction { Ui.nudge(scroll, -1) }
        }
        val next = Button().apply {
            styleClass.add("x-head-arrow")
            graphic = Icons.of(Icons.CHEVRON_RIGHT, 15.0)
            isFocusTraversable = false
            setOnAction { Ui.nudge(scroll, 1) }
        }
        val head = HBox(10.0,
            VBox(1.0,
                Theme.label(row.title, size = 19.0, bold = true).apply { styleClass.add("x-rail-title"); minWidth = 0.0 },
                Theme.label(row.providerName, size = 12.0, dim = true).apply { minWidth = 0.0 },
            ).apply { minWidth = 0.0 },
            Ui.spacer(), viewAll, prev, next,
        ).apply { alignment = Pos.CENTER_LEFT; minWidth = 0.0 }
        return VBox(Theme.S2, head, scroll).apply { minWidth = 0.0; maxWidth = Double.MAX_VALUE }
    }

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    private companion object {
        const val HERO_H = 440.0
        const val CW_W = 320.0
        const val CW_H = 180.0
    }
}
