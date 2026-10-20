package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.data.TmdbDetails
import com.hikari.app.net.Http
import com.hikari.app.download.DownloadKind
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadTask
import com.hikari.app.download.DownloadsRepository
import desktop.fx.Fx
import desktop.player.DesktopPlayer
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.effect.GaussianBlur
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The title screen.
 *
 * Layout rule — which region scrolls is decided once, and nothing else competes
 * for the same axis:
 *
 *  - the banner sits at the top at a fixed height (full-bleed artwork with a
 *    rotating backdrop slideshow, logo treatment, meta, ratings and actions);
 *  - the LEFT column scrolls as a whole (seasons + episode cards, cast & crew,
 *    directors, studios, screenshots, trailers);
 *  - the RIGHT column is the fixed-width "Watch" panel: it never leaves the
 *    screen and instead owns the vertical space *inside* itself, with the
 *    source list as the one growing, scrolling child.
 *
 * Every piece here is bounded on both axes (`Ui.fill` + explicit min sizes), so
 * the screen fits any window.
 */
/**
 * Test seam, set only by `desktop.uitest.UiShotTest`.
 *
 * The episode pager has to be looked at with a season of hundreds of episodes on
 * screen, and what is worth photographing is the REAL load path — so instead of
 * faking a finished screen (which would prove nothing about this file), the shot
 * harness hands [DetailScreenView.load] a season through here. Null in the
 * shipped app, where the season always comes from the providers.
 */
internal object DetailScreenTestSeam {
    @Volatile
    var season: ((MediaItem) -> Pair<MediaItem, List<Episode>>)? = null

    /** How many times the banner's Play button has actually been pressed.
     *  The UI test clicks it with a real mouse, so this is what tells "the click
     *  reached the button" apart from "something invisible covers it" — the
     *  failure mode this screen has already had twice. */
    val playPresses = java.util.concurrent.atomic.AtomicInteger()
}

class DetailScreenView(private val item: MediaItem) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var meta: MediaItem = item
    private var episodes: List<Episode> = emptyList()
    private var selectedEpisode: Episode? = null
    private var streams: List<StreamSource> = emptyList()
    private var enrich: TmdbDetails.Details? = null
    /** TMDB season stills for the current season, by episode number. */
    private var stills: Map<Int, TmdbDetails.SeasonEpisode> = emptyMap()
    private var stillsSeason = -1
    /** Episode ids present in history — the watched check on the cards. */
    private var watchedSet: Set<String> = emptySet()

    /** The job that keeps picking up servers found after the first one (see
     *  [pollSweep]). */
    private var sweepPoll: Job? = null

    private var pendingPlay = false
    private var pendingDownload = false
    private var favourite = false

    /** When the playback position was last written to history (mpv reports it
     *  about once a second; history only needs the occasional checkpoint). */
    @Volatile
    private var lastPositionSave = 0L

    /**
     * One PAGE of episodes at a time — 30 by default — plus a range picker.
     *
     * A season with 400 episodes used to scroll as one endless grid (and a
     * filter box you had to already know the number for). Now the page renders
     * as episode cards, the picker lists every range ("1 - 30", "31 - 60", …)
     * so any part of the season is one click away, and Prev/Next walk the
     * pages. Searching still looks across the WHOLE season — the picker governs
     * the cards only when the search box is empty.
     */
    private var episodePage = 0
    private var currentSeason = 1
    private var seasonList: List<Int> = listOf(1)
    private var sortAsc = true
    private var antiSpoiler = false
    private var castExpanded = false
    private var shotsExpanded = false

    // ── hero ────────────────────────────────────────────────────────────────

    private val heroBgA = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
    private val heroBgB = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true; opacity = 0.0 }
    private var heroFgA = true
    private var heroArtA: Image? = null
    private var heroArtB: Image? = null
    private var bgUrls: List<String> = emptyList()
    private var bgIndex = 0
    private var bgTimer: Timeline? = null
    private var bgGen = 0

    private fun paintHeroBg() {
        val w = hero.width
        val h = hero.height
        if (w > 0 && h > 0) {
            Ui.coverImageTop(heroBgA, heroArtA, w, h)
            Ui.coverImageTop(heroBgB, heroArtB, w, h)
        }
    }

    private fun crossfadeHero(img: Image) {
        paintHeroBg()
        if (heroFgA) {
            heroArtB = img
            Ui.coverImageTop(heroBgB, img, hero.width, hero.height)
            Ui.fade(heroBgB, 1.0, 700.0)
            heroFgA = false
            Timeline(KeyFrame(Duration.millis(750.0), EventHandler { heroBgA.opacity = 0.0; heroArtA = img })).play()
        } else {
            heroArtA = img
            Ui.coverImageTop(heroBgA, img, hero.width, hero.height)
            Ui.fade(heroBgA, 1.0, 700.0)
            heroFgA = true
            Timeline(KeyFrame(Duration.millis(750.0), EventHandler { heroBgB.opacity = 0.0; heroArtB = img })).play()
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
    private val heroOver = themed("", "x-d-over")
    private val heroTitle = themed("", "x-d-title").apply {
        isWrapText = true
        maxWidth = 640.0
        minWidth = 0.0
    }
    private val heroMetaRow = HBox(9.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroOverview = themed("", "x-d-overview").apply {
        isWrapText = true
        maxWidth = 580.0
        minWidth = 0.0
    }
    /** TMDB/IMDb rating pills — filled once [TmdbDetails] resolves the title. */
    private val heroRatings = HBox(8.0).apply {
        alignment = Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }
    private val playButton = Button("Play").apply {
        styleClass.add("x-play")
        graphic = Icons.of(Icons.PLAY, 17.0)
        isFocusTraversable = false
        id = "heroPlayBtn"
        setOnAction { playFirst() }
    }
    private val bookmarkButton = Button().apply {
        styleClass.add("x-glass-sq")
        isFocusTraversable = false
        setOnAction { toggleFavourite() }
    }
    private val downloadButton = Button().apply {
        styleClass.add("x-glass-sq")
        graphic = Icons.of(Icons.DOWNLOAD, 17.0)
        isFocusTraversable = false
        setOnAction { downloadFirst() }
    }
    private val heroActions = HBox(10.0, playButton, bookmarkButton, downloadButton).apply {
        alignment = Pos.CENTER_LEFT
    }
    private val heroBody = VBox(10.0, heroOver, heroLogo, heroTitle, heroMetaRow, heroRatings, heroOverview, heroActions).apply {
        alignment = Pos.BOTTOM_LEFT
        minWidth = 0.0
        maxWidth = 660.0
    }
    /**
     * The hero block's wrapper in the scroll flow (see the scroll-over-hero
     * layout in init): restores the banner's old insets once the block is a
     * normal scrolling child instead of a StackPane overlay.
     */
    private val heroBodyWrap = VBox(heroBody).apply {
        padding = Insets(0.0, 24.0, 26.0, 8.0)
        minWidth = 0.0
    }
    private val hero = StackPane()
    /**
     * Transparent headroom at the top of the scroll content: the fixed
     * backdrop shows through it, so the title block sits over the lower art
     * and the whole page slides OVER the image as it scrolls.
     */
    private val pageTop = Region().apply {
        isMouseTransparent = true
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        minHeight = 300.0
        prefHeight = 300.0
        maxHeight = 300.0
    }
    /** Extra dim over the fixed backdrop, grows as the page scrolls over it. */
    private val scrollDim = Region().apply {
        styleClass.add("x-d-scrolldim")
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
        isMouseTransparent = true
        opacity = 0.0
    }
    /** The banner's back arrow — a fixed overlay so it never scrolls away. */
    private lateinit var backBtn: Button

    // ── left column ─────────────────────────────────────────────────────────

    private val leftColumn = VBox(Theme.S5)

    private val seasonPills = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val seasonCount = themed("", "x-season-count")
    private val markWatchedBtn = Ui.button("Mark Season Watched", ghost = true) { markSeasonWatched() }
        .apply { styleClass.add("x-tool") }
    private val spoilerBtn = Ui.button("Anti-spoiler", ghost = true) { toggleSpoiler() }
        .apply { styleClass.add("x-tool") }
    private val sortBtn = Ui.button("Sort ▼", ghost = true) { toggleSort() }
        .apply { styleClass.add("x-tool") }
    private val seasonRow = HBox(10.0, seasonPills, seasonCount, Ui.spacer(), markWatchedBtn, spoilerBtn, sortBtn).apply {
        alignment = Pos.CENTER_LEFT
        minWidth = 0.0
        isVisible = false
        isManaged = false
    }

    private val epRow = HBox(Theme.S4).apply {
        alignment = Pos.TOP_LEFT
        padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
        minWidth = 0.0
    }
    private val epRail = Ui.rail(epRow).apply {
        isVisible = false
        isManaged = false
    }

    private val episodeFilter = TextField().apply {
        styleClass.addAll("field", "ep-filter")
        promptText = "Find episode by number or name…"
        textProperty().addListener { _, _, _ -> renderEpisodeCards() }
    }

    /** "1 - 30", "31 - 60", … — every range the season can be read in. */
    private val episodeRange = javafx.scene.control.ComboBox<String>().apply {
        styleClass.add("ep-range")
        isFocusTraversable = false
        prefWidth = 132.0
        // A listener on the VALUE, not onAction: JavaFX only fires onAction for a
        // range the user picked, so a range selected by code (the page an episode
        // lives on) would silently not load the episodes it names.
        valueProperty().addListener { _, _, label ->
            if (label == null) return@addListener
            val i = items.indexOf(label)
            if (i >= 0 && i != episodePage) {
                episodePage = i
                renderEpisodeCards()
            }
        }
    }
    private val episodePrev = Ui.button("Prev 30", ghost = true) {
        if (episodePage > 0) {
            episodePage--
            renderEpisodeCards()
        }
    }
    private val episodeNext = Ui.button("Next 30", ghost = true) {
        val pages = maxOf(1, (orderedSeasonEps().size + EPISODE_PAGE - 1) / EPISODE_PAGE)
        if (episodePage < pages - 1) {
            episodePage++
            renderEpisodeCards()
        }
    }
    private val episodeHint = themed("", "tiny").apply { isWrapText = true }
    private val pagerRow = HBox(Theme.S2, episodeFilter, episodeRange, episodePrev, episodeNext).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(episodeFilter, Priority.ALWAYS)
        isVisible = false
        isManaged = false
    }

    private val castSection = VBox(Theme.S3).apply { isVisible = false; isManaged = false; minWidth = 0.0 }
    private val crewSection = VBox(Theme.S3).apply { isVisible = false; isManaged = false; minWidth = 0.0 }
    private val studioSection = VBox(Theme.S3).apply { isVisible = false; isManaged = false; minWidth = 0.0 }
    private val shotsSection = VBox(Theme.S3).apply { isVisible = false; isManaged = false; minWidth = 0.0 }
    private val trailersSection = VBox(Theme.S3).apply { isVisible = false; isManaged = false; minWidth = 0.0 }

    /** The scrolling column that holds the episodes, so the selected episode can
     *  be brought into view (see [revealSelected]). */
    private lateinit var leftScroll: ScrollPane

    /** Episode id → its number badge, so the selected one can be scrolled to. */
    private val episodeTiles = HashMap<String, Button>()
    /** Episode id → its card frame, for the selected glow. */
    private val episodeCards = HashMap<String, VBox>()

    // ── source chooser sheet ────────────────────────────────────────────────
    // The reference shows NO sources panel on the detail screen: clicking an
    // episode opens the source list full-screen OVER the series art — the
    // show's logo/title, an episode pill, "Found N sources • …" with an
    // N SOURCES pill, glassy rows (name, quality badge, READY), and
    // Skip Loading + Cancel. This sheet is that.

    private val srcDlgBg = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
    private var srcDlgArt: Image? = null
    private val srcDlgLogo = ImageView().apply {
        isPreserveRatio = true; isSmooth = true; fitHeight = 96.0
        isVisible = false; isManaged = false; isMouseTransparent = true
    }
    private val srcDlgTitle = themed("", "x-src-sheet-title").apply { isWrapText = true; minWidth = 0.0 }
    private val srcDlgSub = themed("", "x-src-sub").apply { isWrapText = true; minWidth = 0.0 }
    private val srcDlgPill = themed("", "x-src-sheet-pill").apply { isWrapText = true; minWidth = 0.0 }
    private val srcDlgStatus = themed("Discovering streaming sources…", "x-src-sheet-status").apply {
        isWrapText = true; minWidth = 0.0
    }
    private val srcDlgCount = themed("", "x-src-sheet-count")
    private val srcDlgBar = ProgressBar(-1.0).apply {
        styleClass.add("x-src-sheet-bar")
        prefWidth = 640.0; maxWidth = 640.0
        minHeight = 4.0; prefHeight = 4.0; maxHeight = 4.0
        isMouseTransparent = true; isFocusTraversable = false
    }
    private val srcDlgEye = Ui.iconButton(Icons.EYE, "Blur the backdrop", 16.0) { toggleSheetBlur() }.apply {
        styleClass.add("x-src-sheet-eye")
    }
    private var srcDlgBlurred = false
    private val srcDlgRows = VBox(8.0).apply { minWidth = 0.0 }
    private val srcDlgScroll = ScrollPane(srcDlgRows).apply {
        isFitToWidth = true
        styleClass.addAll("scroll-pane", "x-src-sheet-scroll")
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
        prefHeight = 300.0
    }
    private val srcDlgSkip = Button("⏭   Skip Loading").apply {
        styleClass.add("x-src-sheet-skip")
        isFocusTraversable = false
        setOnAction { skipLoading() }
    }
    private val srcDlgCancel = Button("✕   Cancel").apply {
        styleClass.add("x-src-sheet-cancel")
        isFocusTraversable = false
        setOnAction { closeSourceDialog() }
    }
    private val srcDlgCard = VBox(Theme.S3).apply {
        styleClass.add("x-src-sheet-col")
        minWidth = 0.0
        maxWidth = 700.0
        prefWidth = 700.0
    }
    private var srcDlgEp: Episode? = null
    private val srcDlgRoot = StackPane().apply {
        styleClass.add("x-src-sheet-root")
        isVisible = false
        isManaged = false
    }

    // ── source discovery overlay ────────────────────────────────────────────

    private val discBg = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
    private var discArt: Image? = null
    private val discLogo = ImageView().apply {
        isPreserveRatio = true; isSmooth = true; fitHeight = 110.0
        isVisible = false; isManaged = false; isMouseTransparent = true
    }
    private val discTitle = themed("", "x-disc-title").apply { isWrapText = true; minWidth = 0.0 }
    private val discPill = themed("", "x-disc-pill").apply { isWrapText = true; minWidth = 0.0 }
    private val discStatus1 = themed("Discovering streaming sources…", "x-disc-status")
    private val discStatus2 = themed("", "x-disc-status2")
    private val discBar = ProgressBar(-1.0).apply {
        styleClass.add("x-disc-bar")
        prefWidth = 460.0; maxWidth = 460.0
        minHeight = 4.0; prefHeight = 4.0; maxHeight = 4.0
        isMouseTransparent = true; isFocusTraversable = false
    }
    private val discCancel = Button("✕   Cancel").apply {
        styleClass.add("x-disc-cancel")
        isFocusTraversable = false
        setOnAction { cancelDiscovery() }
    }
    private val discRoot = StackPane().apply {
        styleClass.add("x-disc-root")
        isVisible = false
        isManaged = false
    }

    val root: BorderPane = BorderPane().apply {
        padding = Insets(Theme.S4, Theme.S5, Theme.S5, Theme.S5)
    }

    init {
        root.sceneProperty().addListener { _, _, scene ->
            if (scene == null) {
                scope.cancel()
                bgTimer?.stop()
            } else {
                bgTimer?.play()
            }
        }
        buildHero()
        buildDiscovery()
        buildSourceDialog()
        leftScroll = Ui.vScroll(leftColumn, Insets(0.0, 6.0, 24.0, 0.0)).apply {
            styleClass.add("x-detail-scroll")
        }
        Ui.fill(leftColumn)
        Ui.fill(leftScroll)
        // Scroll-over-hero: the backdrop is a FIXED full-bleed background and
        // the scroll content (headroom + the hero block + every section) slides
        // OVER it — the page never scrolls "below" the image.
        Ui.fill(hero)
        val bg = StackPane(hero).apply { minWidth = 0.0; minHeight = 0.0 }
        Ui.fill(bg)
        // The back arrow floats above the scroll (pick-transparent everywhere
        // but the button itself) so it is always on screen and never covered.
        val backOverlay = StackPane(backBtn).apply {
            isPickOnBounds = false
            minWidth = 0.0
            minHeight = 0.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }
        StackPane.setAlignment(backBtn, Pos.TOP_LEFT)
        StackPane.setMargin(backBtn, Insets(16.0, 0.0, 0.0, 16.0))
        leftScroll.vvalueProperty().addListener { _, _, v ->
            scrollDim.opacity = (v.toDouble() * 0.55).coerceIn(0.0, 0.55)
        }
        root.heightProperty().addListener { _, _, _ -> layoutSpacer() }
        val center = StackPane(bg, leftScroll, backOverlay, discRoot, srcDlgRoot).apply {
            minWidth = 0.0; minHeight = 0.0
        }
        Ui.fill(center)
        root.center = center
        AppShell.detailCloser = { closeTopOverlay() }
        javafx.application.Platform.runLater { layoutSpacer() }
        // The screen is painted from the catalog item we were handed, BEFORE any
        // network call: the banner, the title block and the ratings area are
        // already on screen when the click's frame ends. The item a grid hands
        // over carries the title, year, genres, overview and artwork, so
        // `metaFor` only ever *enriches* what is already there. Opening a
        // title used to show an empty banner and a spinner while that round-trip
        // ran, which is what "loading takes a moment" looked like.
        renderHero(item)
        renderSections()
        load()
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private fun load() {
        scope.launch {
            // Ratings + cast + artwork resolve in the background (TMDB search)
            // from the FIRST frame: the old code waited for the provider's own
            // meta + episode calls to finish before even starting the TMDB
            // lookup, which is why the banner sat on the extension's artwork
            // for seconds and then visibly swapped. Fired twice (here and below
            // with the loaded meta) because the loaded title can differ; the
            // lookup itself is cached by title, so the second call is free when
            // nothing changed.
            loadEnrich(item)
            // A UI shot has to page through a season of hundreds of episodes to
            // prove the pager works; the shot harness hands one in through this
            // seam instead of waiting on (and depending on) a provider. Null in
            // the real app — see [DetailScreenTestSeam].
            val seam = DetailScreenTestSeam.season?.invoke(item)
            val loaded = seam?.first
                ?: runCatching { AppShell.app.repository.metaFor(item) }.getOrDefault(item)
            val eps = seam?.second
                ?: if (loaded.type == MediaType.SERIES) {
                    runCatching { AppShell.app.repository.episodesFor(loaded) }.getOrNull().orEmpty()
                } else emptyList()
            // Resume where the user left off when we know that episode.
            val resumeId = runCatching {
                AppShell.app.store.history()
                    .firstOrNull {
                        it.providerId == loaded.providerId && it.mediaId == loaded.id && it.episodeId.isNotBlank()
                    }?.episodeId
            }.getOrNull()
            Fx.run {
                meta = loaded
                episodes = dedupe(eps)
                watchedSet = runCatching {
                    AppShell.app.store.history()
                        .filter { it.providerId == loaded.providerId && it.mediaId == loaded.id && it.episodeId.isNotBlank() }
                        .map { it.episodeId }.toSet()
                }.getOrDefault(emptySet())
                initSeasons(resumeId)
                renderHero(loaded)
                renderSections()
                val first = episodes.firstOrNull { it.id == resumeId } ?: orderedSeasonEps().firstOrNull()
                if (first != null) {
                    selectedEpisode = first
                    val idx = orderedSeasonEps().indexOfFirst { it.id == first.id }
                    if (idx >= 0) episodePage = idx / EPISODE_PAGE
                    renderEpisodeCards()
                    updateNowPlaying()
                }
                loadStreams()
                revealSelected()
            }
            // Ratings + cast + artwork resolve in the background (TMDB search):
            // the screen never waits on them, they pop into the banner and the
            // sections below when they land.
            loadEnrich(loaded)
        }
    }

    private fun initSeasons(resumeId: String?) {
        val ss = episodes.map { it.season }.distinct().sorted()
        seasonList = if (ss.isEmpty()) listOf(1) else ss
        val resumeSeason = resumeId?.let { id -> episodes.firstOrNull { it.id == id }?.season }
        currentSeason = resumeSeason ?: seasonList.first()
        episodePage = 0
    }

    /**
     * Brings the selected episode's card into view.
     *
     * Opening a series used to leave the episode list below the fold (under a
     * long synopsis) and, when resuming, the highlighted episode somewhere
     * inside a grid of hundreds — so the user could not tell where they were in
     * the series. The episode rail sits near the top of the scrolling column,
     * and this scrolls the rail itself so the *selected* card is on screen.
     */
    private fun revealSelected() {
        val ep = selectedEpisode ?: return
        if (episodeTiles[ep.id] == null) return
        javafx.application.Platform.runLater {
            val cards = epRow.children.filterIsInstance<VBox>()
            val idx = cards.indexOfFirst { it.properties["epId"] == ep.id }
            if (idx < 0 || cards.size <= 1) return@runLater
            epRail.hvalue = (idx.toDouble() / (cards.size - 1)).coerceIn(0.0, 1.0)
        }
    }

    /** Some providers emit the same episode twice (raw id + rewritten url). */
    private fun dedupe(eps: List<Episode>): List<Episode> {
        val unique = LinkedHashMap<String, Episode>()
        eps.forEach { unique[it.id.ifBlank { it.name ?: "${it.number}" }] = it }
        val list = unique.values.toList()
        // Providers hand episodes over newest-first at least as often as not, so
        // a 160-episode series opened on "Episode 160" — which the user reads as
        // "the episodes are the wrong way round". Sort by the episode number
        // whenever the numbering is meaningful (all numbered, and out of order).
        val ordered = list.all { it.number > 0 } && list.size > 1 &&
            list.zipWithNext().any { (a, b) -> a.number > b.number }
        return if (ordered) list.sortedBy { it.number } else list
    }

    // ── hero ────────────────────────────────────────────────────────────────

    private fun renderHero(m: MediaItem) {
        val provider = runCatching {
            AppShell.app.store.providers().firstOrNull { it.id == m.providerId }?.name
        }.getOrNull()
        heroOver.text = provider.orEmpty()
        heroOver.isVisible = !provider.isNullOrBlank()
        heroOver.isManaged = heroOver.isVisible
        heroTitle.text = m.title
        heroTitle.isVisible = true
        heroTitle.isManaged = true
        heroLogo.isVisible = false
        heroLogo.isManaged = false
        renderMeta(m, null)
        heroOverview.text = m.overview?.let { htmlToPlain(it) }?.takeIf { it.isNotBlank() }
            ?: "No synopsis available for this title."
        // TMDB artwork owns the banner: it is painted the moment it lands
        // (see [renderEnrich]), never the provider's own low-quality art. The
        // provider art is only the fallback when TMDB has nothing for the
        // title (see [paintProviderArt]).
        bgUrls = emptyList()
        bgIndex = 0
        bgTimer?.stop()
        favourite = runCatching { AppShell.app.store.favorites().any { it.uniqueId == m.uniqueId } }.getOrDefault(false)
        refreshFavourite()
        updatePlayLabel()
    }

    private fun renderMeta(m: MediaItem, d: TmdbDetails.Details?) {
        val cells = ArrayList<Node>()
        m.year?.let { cells.add(themed(it.toString(), "x-d-meta")) }
        cells.add(themed(when (m.type) {
            MediaType.SERIES -> "TV Series"
            MediaType.MOVIE -> "Movie"
            MediaType.UNKNOWN -> ""
        }, "x-d-meta"))
        val cert = d?.certification.orEmpty()
        if (cert.isNotBlank()) cells.add(themed(cert, "x-d-cert"))
        val runtime = d?.runtime.orEmpty()
        if (runtime.isNotBlank()) cells.add(themed(runtime, "x-d-meta"))
        val genres = m.genres.take(3).joinToString(", ")
        if (genres.isNotBlank()) cells.add(themed(genres, "x-d-meta"))
        heroMetaRow.children.setAll(cells.flatMap { listOf(it, themed("•", "x-d-dot")) }.dropLast(1))
        heroMetaRow.isVisible = cells.isNotEmpty()
        heroMetaRow.isManaged = cells.isNotEmpty()
    }

    /** Last-resort banner art: the provider's own backdrop/poster, used only
     *  when TMDB has nothing for the title — a low-quality image beats an
     *  empty banner, but it must never flash before TMDB art that is about to
     *  arrive (see [renderHero]). */
    private fun paintProviderArt() {
        if (bgUrls.isNotEmpty()) return
        val urls = listOfNotNull(
            desktop.img.ImageLoader.artFor(meta.backdropUrl, null),
            desktop.img.ImageLoader.artFor(meta.posterUrl, null),
        ).distinct()
        if (urls.isEmpty()) return
        bgUrls = urls
        bgIndex = 0
        val gen = ++bgGen
        desktop.img.ImageLoader.loadAsync(urls.first(), onReady = { img ->
            if (img != null && gen == bgGen) {
                heroArtA = img
                paintHeroBg()
                heroBgA.opacity = 1.0
                heroFgA = true
            }
        }, w = 1600, h = 900)
        startBgTimer()
    }

    private fun startBgTimer() {
        bgTimer?.stop()
        if (bgUrls.size < 2) return
        bgTimer = Timeline(
            KeyFrame(Duration.seconds(9.0), EventHandler {
                bgIndex = (bgIndex + 1) % bgUrls.size
                val gen = ++bgGen
                val url = bgUrls[bgIndex]
                desktop.img.ImageLoader.loadAsync(url, onReady = { img ->
                    if (img != null && gen == bgGen) crossfadeHero(img)
                }, w = 1600, h = 900)
            }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun buildHero() {
        val clip = Rectangle().apply {
            arcWidth = 26.0
            arcHeight = 26.0
        }
        clip.widthProperty().bind(hero.widthProperty())
        clip.heightProperty().bind(hero.heightProperty())
        hero.clip = clip
        hero.styleClass.add("x-d-hero")
        hero.minWidth = 0.0
        hero.maxWidth = Double.MAX_VALUE
        hero.minHeight = 0.0
        hero.maxHeight = Double.MAX_VALUE
        heroBgA.fitWidthProperty().bind(hero.widthProperty())
        heroBgA.fitHeightProperty().bind(hero.heightProperty())
        heroBgB.fitWidthProperty().bind(hero.widthProperty())
        heroBgB.fitHeightProperty().bind(hero.heightProperty())
        hero.widthProperty().addListener { _, _, _ -> paintHeroBg() }
        hero.heightProperty().addListener { _, _, _ -> paintHeroBg() }

        val scrim = Region().apply {
            styleClass.add("x-d-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }
        val scrimL = Region().apply {
            styleClass.add("x-d-scrim-l")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }

        // The screen's own back arrow. Two things about it are load-bearing:
        //
        //  - it carries NO tooltip. A JavaFX tooltip is a popup window of its
        //    own, and a click that arrives while one is up is spent dismissing it
        //    instead of pressing the button (see Ui.tooltip) — on a one-click
        //    control that reads as "the back button does not work".
        //  - it lives in a fixed overlay ABOVE the scroll (see init), so nothing
        //    the page draws — the title block, the scrims, scrolled content —
        //    can ever cover it or take its clicks.
        backBtn = Ui.iconButton(Icons.CHEVRON_LEFT, null, 18.0) { AppShell.back() }.apply {
            styleClass.addAll("x-round-btn", "hero-back")
            id = "heroBackBtn"
            accessibleText = "Back"
        }

        // Nothing decorative may be a pick target on a screen that carries
        // buttons: the artwork is a picture, so a click that lands on it is a
        // click the user meant for Play or Download.
        heroBgA.isMouseTransparent = true
        heroBgB.isMouseTransparent = true
        heroLogo.isMouseTransparent = true

        // Paint order IS click priority in JavaFX: the scroll dim goes on last
        // so it grades everything beneath it, and it is mouse-transparent so it
        // never takes a click. The back arrow lives in its own fixed overlay
        // above the scroll (see init) — never inside this stack.
        hero.children.addAll(heroBgA, heroBgB, scrim, scrimL, scrollDim)
    }

    /** Sizes the scroll headroom from the window, so the title block sits over
     *  the lower art at any window height. */
    private fun layoutSpacer() {
        val h = (root.height * 0.42).coerceIn(240.0, 400.0)
        if (h <= 0.0) return
        pageTop.minHeight = h
        pageTop.prefHeight = h
        pageTop.maxHeight = h
    }

    // ── discovery overlay ───────────────────────────────────────────────────

    private fun buildDiscovery() {
        discBg.fitWidthProperty().bind(discRoot.widthProperty())
        discBg.fitHeightProperty().bind(discRoot.heightProperty())
        discRoot.widthProperty().addListener { _, _, w ->
            Ui.coverImage(discBg, discArt, w.toDouble(), discRoot.height)
        }
        discRoot.heightProperty().addListener { _, _, h ->
            Ui.coverImage(discBg, discArt, discRoot.width, h.toDouble())
        }
        val scrim = Region().apply {
            styleClass.add("x-disc-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }
        val center = VBox(10.0, discLogo, discTitle, discPill).apply {
            alignment = Pos.CENTER
            minWidth = 0.0
            maxWidth = 720.0
        }
        val statuses = VBox(6.0, discStatus1, discBar, discStatus2).apply {
            alignment = Pos.CENTER
            minWidth = 0.0
            padding = Insets(26.0, 0.0, 0.0, 0.0)
        }
        val page = VBox(0.0, center, statuses, VBox(discCancel).apply {
            alignment = Pos.CENTER
            padding = Insets(40.0, 0.0, 0.0, 0.0)
        }).apply {
            alignment = Pos.CENTER
            minWidth = 0.0
            maxWidth = 760.0
        }
        discRoot.children.addAll(discBg, scrim, page)
        StackPane.setAlignment(page, Pos.CENTER)
    }

    private fun showDiscovery() {
        val m = meta
        val ep = selectedEpisode
        discArt = heroArtA ?: heroArtB
        Ui.coverImage(discBg, discArt, discRoot.width.coerceAtLeast(800.0), discRoot.height.coerceAtLeast(500.0))
        val logoImg = heroLogo.image
        if (enrich?.logoUrl != null && logoImg != null) {
            discLogo.image = logoImg
            discLogo.isVisible = true
            discLogo.isManaged = true
            discTitle.isVisible = false
            discTitle.isManaged = false
        } else {
            discLogo.isVisible = false
            discLogo.isManaged = false
            discTitle.text = m.title
            discTitle.isVisible = true
            discTitle.isManaged = true
        }
        discPill.text = if (ep != null && m.type == MediaType.SERIES) {
            "S${ep.season}:E${ep.number} • " + (ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}")
        } else {
            m.title
        }
        discStatus1.text = "Discovering streaming sources…"
        discStatus2.text = ""
        discBar.progress = -1.0
        discRoot.isVisible = true
        discRoot.isManaged = true
        Ui.fadeIn(discRoot, 220.0)
    }

    private fun hideDiscovery() {
        discRoot.isVisible = false
        discRoot.isManaged = false
    }

    private fun cancelDiscovery() {
        pendingPlay = false
        pendingDownload = false
        sweepPoll?.cancel()
        hideDiscovery()
    }

    // ── favourites ──────────────────────────────────────────────────────────

    private fun toggleFavourite() {
        val m = meta
        if (favourite) {
            runCatching { AppShell.app.store.removeFavorite(m.uniqueId) }
            favourite = false
            AppShell.toast("Removed from library", "ok")
        } else {
            runCatching { AppShell.app.store.addFavorite(m) }
            favourite = true
            AppShell.toast("Added to library", "ok")
        }
        refreshFavourite()
    }

    private fun refreshFavourite() {
        bookmarkButton.graphic = Icons.of(if (favourite) Icons.HEART else Icons.HEART_OUTLINE, 17.0)
    }

    // ── sections ────────────────────────────────────────────────────────────

    private fun renderSections() {
        val isSeries = meta.type == MediaType.SERIES
        val hasEps = episodes.isNotEmpty()
        seasonRow.isVisible = isSeries && hasEps
        seasonRow.isManaged = seasonRow.isVisible
        epRail.isVisible = isSeries && hasEps
        epRail.isManaged = epRail.isVisible
        pagerRow.isVisible = isSeries && hasEps
        pagerRow.isManaged = pagerRow.isVisible
        if (isSeries && hasEps) {
            renderSeasonRow()
            renderEpisodeCards()
        }
        renderCast()
        renderCrew()
        renderStudios()
        renderShots()
        renderTrailers()
        val panels = mutableListOf<Node>()
        if (isSeries && hasEps) {
            // The range picker sits ABOVE the cards it pages (season pills,
            // then the search/range/prev/next row, then the episode grid),
            // not below them where it reads as an afterthought.
            panels.add(seasonRow)
            panels.add(pagerRow)
            panels.add(epRail)
            panels.add(episodeHint)
        }
        if (castSection.isVisible) panels.add(castSection)
        if (crewSection.isVisible) panels.add(crewSection)
        if (studioSection.isVisible) panels.add(studioSection)
        if (shotsSection.isVisible) panels.add(shotsSection)
        if (trailersSection.isVisible) panels.add(trailersSection)
        // The hero block scrolls WITH the page, over the fixed backdrop: the
        // headroom shows pure art, then the title block, then the season.
        leftColumn.children.setAll(listOf(pageTop, heroBodyWrap) + panels)
    }

    // ── episodes ────────────────────────────────────────────────────────────

    /** The current season's episodes in display order (see [sortAsc]). */
    private fun orderedSeasonEps(): List<Episode> {
        val list = episodes.filter { it.season == currentSeason }
        return if (sortAsc) list else list.reversed()
    }

    private fun renderSeasonRow() {
        seasonPills.children.setAll(seasonList.map { s ->
            Button("Season $s").apply {
                styleClass.add("x-season-pill")
                if (s == currentSeason) styleClass.add("x-season-sel")
                isFocusTraversable = false
                setOnAction { selectSeason(s) }
            }
        })
        val count = episodes.count { it.season == currentSeason }
        seasonCount.text = "• $count Episodes"
        spoilerBtn.styleClass.remove("x-tool-on")
        if (antiSpoiler) spoilerBtn.styleClass.add("x-tool-on")
        sortBtn.text = if (sortAsc) "Sort ▼" else "Sort ▲"
    }

    private fun selectSeason(s: Int) {
        currentSeason = s
        episodePage = 0
        episodeFilter.text = ""
        stills = emptyMap()
        stillsSeason = -1
        val first = orderedSeasonEps().firstOrNull()
        if (first != null) {
            selectedEpisode = first
            updateNowPlaying()
            loadStreams()
        }
        renderSeasonRow()
        renderEpisodeCards()
        loadSeasonStills()
    }

    private fun toggleSpoiler() {
        antiSpoiler = !antiSpoiler
        renderSeasonRow()
        renderEpisodeCards()
    }

    private fun toggleSort() {
        sortAsc = !sortAsc
        episodePage = 0
        renderSeasonRow()
        renderEpisodeCards()
    }

    private fun markSeasonWatched() {
        val list = orderedSeasonEps()
        if (list.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            list.forEach { ep ->
                AppShell.app.store.addHistory(
                    HistoryEntry(
                        providerId = meta.providerId,
                        mediaId = meta.id,
                        type = meta.type,
                        title = meta.title,
                        posterUrl = meta.posterUrl,
                        episodeId = ep.id,
                        episodeName = ep.name ?: "",
                        watchedAt = now,
                    )
                )
            }
        }
        watchedSet = watchedSet + list.map { it.id }.toSet()
        renderEpisodeCards()
        AppShell.toast("Season $currentSeason marked as watched", "ok")
    }

    private fun renderEpisodeCards() {
        val list = orderedSeasonEps()
        if (episodes.isEmpty() || meta.type != MediaType.SERIES) {
            epRow.children.setAll()
            episodeHint.text = ""
            return
        }
        val needle = episodeFilter.text.trim().lowercase()
        val matches = if (needle.isEmpty()) {
            list.indices.toList()
        } else {
            episodes.indices.filter { i ->
                val ep = episodes[i]
                ep.number.toString().contains(needle) ||
                    (i + 1).toString() == needle ||
                    ep.name?.lowercase()?.contains(needle) == true
            }
        }
        val searching = needle.isNotEmpty()
        val pages = maxOf(1, (list.size + EPISODE_PAGE - 1) / EPISODE_PAGE)
        if (episodePage >= pages) episodePage = pages - 1
        if (episodePage < 0) episodePage = 0
        // The range picker lists the whole season as 30-episode ranges, so any
        // part of it is one click away — and it follows the page the code
        // selects (resuming mid-season lands on that page's range).
        if (!searching) {
            val labels = (0 until pages).map { p ->
                val from = p * EPISODE_PAGE + 1
                val to = minOf(list.size, (p + 1) * EPISODE_PAGE)
                "$from - $to"
            }
            if (episodeRange.items.size != labels.size) {
                val keep = episodePage
                episodeRange.items.setAll(labels)
                if (keep in labels.indices) episodeRange.selectionModel.select(keep)
            } else if (episodeRange.selectionModel.selectedIndex != episodePage) {
                episodeRange.selectionModel.select(episodePage)
            }
        }
        val shownIdx = if (searching) {
            matches.take(EPISODE_PAGE * 4)
        } else {
            val from = episodePage * EPISODE_PAGE
            val slice = (from until minOf(list.size, from + EPISODE_PAGE))
            // Map back to `episodes` indices: `list` may be reversed.
            slice.map { episodes.indexOf(list[it]) }
        }
        episodeTiles.clear()
        episodeCards.clear()
        epRow.children.setAll(shownIdx.map { episodes[it] }.map { episodeCard(it) })
        if (epRow.children.isEmpty()) {
            epRow.children.setAll(themed("No episode matches “${episodeFilter.text.trim()}”.", "tiny"))
        }
        episodeHint.text = when {
            needle.isNotEmpty() ->
                "${matches.size} episode(s) match “${episodeFilter.text.trim()}” —" +
                    " clear the box to page through the season 30 at a time."
            list.size > shownIdx.size ->
                "Showing episodes " + (episodePage * EPISODE_PAGE + 1) + " - " +
                    (episodePage * EPISODE_PAGE + shownIdx.size) + " of " + list.size +
                    " — pick another range above, or search by number or name."
            else -> ""
        }
        episodeHint.isVisible = episodeHint.text.isNotBlank()
        episodeHint.isManaged = episodeHint.isVisible
        episodePrev.isDisable = searching || episodePage <= 0
        episodeNext.isDisable = searching || episodePage >= pages - 1
        episodeRange.isDisable = searching || pages <= 1
    }

    private fun episodeCard(ep: Episode): VBox {
        // Stills by number first; providers that number a season absolutely
        // (a 247-episode "season 1" TMDB splits into seasons of ~100) miss the
        // number map past TMDB's count, so fall back to the episode's POSITION
        // in the season; and when nothing has any art at all, the show's own
        // backdrop/poster beats a blank tile (spoiler mode still blurs it).
        val still = stills[ep.number] ?: stillByPosition(ep)
        // Anti-spoiler BLURS the still, it never removes it: the card keeps
        // its artwork with a heavy gaussian blur, and the big number behind
        // only shows when there is no artwork at all.
        // TMDB's high-resolution still first (w780), the provider's thumbnail
        // only as the fallback — provider stills are low-resolution thumbs,
        // and the TMDB file is the same episode in full quality.
        val imgUrl = still?.stillUrl?.takeIf { it.isNotBlank() }
            ?: (ep.image?.takeIf { it.isNotBlank() }
            ?: meta.backdropUrl?.takeIf { it.isNotBlank() }
            ?: meta.posterUrl?.takeIf { it.isNotBlank() })
        // Big number behind the still: titles without any artwork (a provider
        // with no images that TMDB cannot resolve either) still read as episode
        // cards instead of empty black boxes — the still covers it when one loads.
        val bigNum = Label(ep.number.toString()).apply {
            styleClass.add("x-ep-bignum")
            isMouseTransparent = true
        }
        val art = ImageView().apply {
            isPreserveRatio = false; isSmooth = true; isMouseTransparent = true
            if (antiSpoiler) effect = SPOILER_BLUR
        }
        val frame = StackPane(bigNum, art).apply {
            styleClass.add("x-ep-still")
            clip = Rectangle(EP_CARD_W, EP_STILL_H).apply { arcWidth = 16.0; arcHeight = 16.0 }
            minWidth = EP_CARD_W; prefWidth = EP_CARD_W; maxWidth = EP_CARD_W
            minHeight = EP_STILL_H; prefHeight = EP_STILL_H; maxHeight = EP_STILL_H
        }
        art.fitWidth = EP_CARD_W
        art.fitHeight = EP_STILL_H
        if (!imgUrl.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(imgUrl, onReady = { img ->
                if (img != null) Ui.coverImage(art, img, EP_CARD_W, EP_STILL_H)
            }, w = 960, h = 540)
        } else {
            art.styleClass.add("x-ep-ph")
        }
        val rating = still?.rating?.takeIf { it > 0.0 }
        if (rating != null) {
            val badge = Label("★ %.1f".format(rating)).apply { styleClass.add("x-ep-badge") }
            StackPane.setAlignment(badge, Pos.TOP_LEFT)
            StackPane.setMargin(badge, Insets(8.0, 0.0, 0.0, 8.0))
            frame.children.add(badge)
        }
        // The card's number badge doubles as the UI test's tile hook: exactly
        // one `.ep-num` button with a pure integer label per card (see
        // UiShotTest.episodeNumbers).
        val num = Button(ep.number.toString()).apply {
            styleClass.addAll("ep-num", "x-ep-num")
            if (ep.id == selectedEpisode?.id) styleClass.add("ep-num-sel")
            isFocusTraversable = false
            setOnAction { selectEpisode(ep) }
        }
        episodeTiles[ep.id] = num
        StackPane.setAlignment(num, Pos.TOP_RIGHT)
        StackPane.setMargin(num, Insets(8.0, 8.0, 0.0, 0.0))
        frame.children.add(Ui.isolateClicks(num))
        if (watchedSet.contains(ep.id)) {
            val check = HBox(4.0, Icons.of(Icons.CHECK, 12.0), Label("Watched").apply { styleClass.add("x-ep-watched-t") }).apply {
                styleClass.add("x-ep-watched")
                alignment = Pos.CENTER_LEFT
                isMouseTransparent = true
            }
            StackPane.setAlignment(check, Pos.BOTTOM_LEFT)
            StackPane.setMargin(check, Insets(0.0, 0.0, 8.0, 8.0))
            frame.children.add(check)
        }
        val titleText = when {
            antiSpoiler -> "Episode ${ep.number}"
            !still?.name.isNullOrBlank() && (ep.name.isNullOrBlank() || ep.name!!.length > 60) -> still!!.name
            !ep.name.isNullOrBlank() -> cleanEpTitle(ep)
            !still?.name.isNullOrBlank() -> still!!.name
            else -> "Episode ${ep.number}"
        }
        val over = themed("S${ep.season} • EPISODE ${ep.number}", "x-ep-over")
        val title = themed(titleText, "x-ep-title").apply {
            isWrapText = true; minWidth = 0.0; maxWidth = EP_CARD_W; maxHeight = 40.0
        }
        val sub = themed(
            if (antiSpoiler) "No description available."
            else (still?.overview?.takeIf { it.isNotBlank() } ?: "No description available."),
            "x-ep-sub",
        ).apply { isWrapText = true; minWidth = 0.0; maxWidth = EP_CARD_W; maxHeight = 34.0 }
        val card = VBox(8.0, frame, VBox(3.0, over, title, sub).apply { minWidth = 0.0 }).apply {
            styleClass.add("x-ep-card")
            if (ep.id == selectedEpisode?.id) styleClass.add("x-ep-sel")
            cursor = javafx.scene.Cursor.HAND
            minWidth = EP_CARD_W; prefWidth = EP_CARD_W; maxWidth = EP_CARD_W
            properties["epId"] = ep.id
            setOnMouseClicked { selectEpisode(ep) }
        }
        episodeCards[ep.id] = card
        return card
    }

    /** TMDB still by position in the season, for episodes whose absolute
     *  provider numbering runs past TMDB's per-season count (see
     *  [episodeCard]). Approximate — a wrong still beats a blank tile. */
    private fun stillByPosition(ep: Episode): TmdbDetails.SeasonEpisode? {
        if (stills.isEmpty()) return null
        val ordered = stills.values.sortedBy { it.number }
        val idx = orderedSeasonEps().indexOfFirst { it.id == ep.id }
        return ordered.getOrNull(idx)
    }

    private fun cleanEpTitle(ep: Episode): String {
        val name = ep.name?.trim().orEmpty()
        if (name.isBlank()) return "Episode ${ep.number}"
        // Strip the show title so a card reads "Kissa Zeher Ka: Part 1" instead
        // of 60 characters.
        val trimmed = name.replace(meta.title, "").trim().trim('-', ':', '|', '·').trim()
        return trimmed.ifBlank { "Episode ${ep.number}" }
    }

    private fun selectEpisode(ep: Episode) {
        selectedEpisode = ep
        // Put the page holding this episode on screen: picking "Next episode",
        // or resuming at episode 187 of 379, must not leave the cards on page 1.
        val idx = orderedSeasonEps().indexOfFirst { it.id == ep.id }
        if (idx >= 0) episodePage = idx / EPISODE_PAGE
        renderEpisodeCards()
        updateNowPlaying()
        loadStreams()
        // The reference shows no sources list on the page: the list opens when
        // an episode is picked. (The auto-play path sets pendingPlay first, so
        // it never pops the chooser — it goes straight to discovery.)
        if (!pendingPlay) openSourceDialog(ep)
    }

    private fun updateNowPlaying() {
        updatePlayLabel()
        if (srcDlgRoot.isVisible) renderDialogRows()
    }

    private fun updatePlayLabel() {
        val ep = selectedEpisode
        playButton.text = if (meta.type == MediaType.SERIES && ep != null) {
            "Play S${ep.season} E${ep.number}"
        } else {
            "Play"
        }
    }

    // ── enrichment ──────────────────────────────────────────────────────────

    /**
     * TMDB/IMDb enrichment for the banner and the lower sections (reference
     * parity: the detail screen shows TMDB + IMDb data, not just the
     * provider's meta).
     *
     * Runs off the load path: a title that does not resolve keeps its provider
     * meta and simply shows no enriched sections — enrichment must never block
     * or break the screen.
     */
    private fun loadEnrich(m: MediaItem) {
        scope.launch {
            val details = TmdbDetails.load(m)
            Fx.run {
                if (details != null) {
                    enrich = details
                    renderMeta(meta, details)
                    renderEnrich(details)
                    renderSections()
                    if (meta.type == MediaType.SERIES) loadSeasonStills()
                } else if (enrich == null) {
                    // TMDB has nothing for this title: fall back to the
                    // provider's own art instead of an empty banner.
                    paintProviderArt()
                }
            }
        }
    }

    private fun renderEnrich(d: TmdbDetails.Details) {
        // A provider with no synopsis borrows TMDB's — the banner never shows
        // "No synopsis available" when the database has the real text.
        if (d.overview.isNotBlank() &&
            (heroOverview.text.isBlank() || heroOverview.text.startsWith("No synopsis"))
        ) {
            heroOverview.text = d.overview
        }
        val pills = ArrayList<Node>()
        if (d.imdbRating > 0.0 || d.imdbId.isNotBlank()) {
            val label = if (d.imdbRating > 0.0) "IMDb %.1f".format(d.imdbRating) else "IMDb ↗"
            pills.add(Button(label).apply {
                styleClass.add("x-pill-imdb")
                isFocusTraversable = false
                setOnAction { desktop.fx.DesktopUi.open(TmdbDetails.imdbUrl(d.imdbId)) }
            })
        }
        if (d.tmdbRating > 0.0) {
            val votes = if (d.tmdbVotes > 0) " (" + formatVotes(d.tmdbVotes) + ")" else ""
            pills.add(themed("TMDB %.1f%s".format(d.tmdbRating, votes), "x-pill-tmdb"))
        }
        heroRatings.children.setAll(pills)
        heroRatings.isVisible = pills.isNotEmpty()
        heroRatings.isManaged = pills.isNotEmpty()
        // Logo treatment: the show's own artwork instead of a text title.
        if (!d.logoUrl.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(d.logoUrl, onReady = { img ->
                if (img != null) {
                    heroLogo.image = img
                    heroLogo.isVisible = true
                    heroLogo.isManaged = true
                    heroTitle.isVisible = false
                    heroTitle.isManaged = false
                }
            }, w = 640, h = 200)
        }
        // Rotating backdrops: the title's own artwork, painted the moment it
        // lands — the first TMDB backdrop crossfades in immediately instead
        // of waiting for the rotation timer, so the banner never shows the
        // provider's art first and swaps seconds later.
        if (d.backdrops.isNotEmpty()) {
            bgUrls = d.backdrops
            bgIndex = 0
            val gen = ++bgGen
            desktop.img.ImageLoader.loadAsync(d.backdrops.first(), onReady = { img ->
                if (img != null && gen == bgGen) crossfadeHero(img)
            }, w = 1920, h = 1080)
            startBgTimer()
        } else {
            paintProviderArt()
        }
    }

    private fun loadSeasonStills() {
        val d = enrich ?: return
        if (d.mediaType != "tv") return
        if (stillsSeason == currentSeason) return
        stillsSeason = currentSeason
        scope.launch {
            val eps = TmdbDetails.seasonEpisodes(d.tmdbId, currentSeason)
            Fx.run {
                if (stillsSeason == currentSeason) {
                    stills = eps.associateBy { it.number }
                    renderEpisodeCards()
                }
            }
        }
    }

    private fun formatVotes(v: Int): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
        v >= 1_000 -> "%.1fk".format(v / 1_000.0)
        else -> v.toString()
    }

    // ── cast / crew / studios / shots / trailers ────────────────────────────

    private fun renderCast() {
        val cast = enrich?.cast.orEmpty()
        if (cast.isEmpty()) {
            castSection.isVisible = false; castSection.isManaged = false; return
        }
        val shown = if (castExpanded) cast else cast.take(8)
        val row = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
            minWidth = 0.0
        }
        shown.forEach { row.children.add(castCard(it.name, it.character.ifBlank { null }, it.photoUrl)) }
        if (!castExpanded && cast.size > 8) {
            row.children.add(viewAllCard("View All", "Full Roster") { castExpanded = true; renderSections() })
        }
        val head = Ui.sectionHeader("Cast & Crew", null,
            if (!castExpanded && cast.size > 8) viewAllLink { castExpanded = true; renderSections() } else null)
        castSection.children.setAll(head, Ui.rail(row))
        castSection.isVisible = true; castSection.isManaged = true
    }

    private fun renderCrew() {
        val crew = enrich?.crew.orEmpty()
        if (crew.isEmpty()) {
            crewSection.isVisible = false; crewSection.isManaged = false; return
        }
        val row = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
            minWidth = 0.0
        }
        crew.take(9).forEach { row.children.add(castCard(it.name, null, it.photoUrl, it.job.uppercase())) }
        crewSection.children.setAll(Ui.sectionHeader("Directors & Creators"), Ui.rail(row))
        crewSection.isVisible = true; crewSection.isManaged = true
    }

    private fun castCard(name: String, character: String?, photoUrl: String?, badge: String? = null): VBox {
        val art = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
        val frame = StackPane(art).apply {
            styleClass.add("x-cast-photo")
            clip = Rectangle(CAST_W, CAST_H).apply { arcWidth = 18.0; arcHeight = 18.0 }
            minWidth = CAST_W; prefWidth = CAST_W; maxWidth = CAST_W
            minHeight = CAST_H; prefHeight = CAST_H; maxHeight = CAST_H
        }
        art.fitWidth = CAST_W
        art.fitHeight = CAST_H
        if (!photoUrl.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(photoUrl, onReady = { img ->
                if (img != null) Ui.coverImage(art, img, CAST_W, CAST_H)
            }, w = 220, h = 330)
        } else {
            art.styleClass.add("x-ep-ph")
        }
        if (!badge.isNullOrBlank()) {
            val b = Label(badge).apply { styleClass.add("x-cast-badge") }
            StackPane.setAlignment(b, Pos.TOP_LEFT)
            StackPane.setMargin(b, Insets(8.0, 0.0, 0.0, 8.0))
            frame.children.add(b)
        }
        val nameL = themed(name, "x-cast-name").apply { isWrapText = true; minWidth = 0.0; maxWidth = CAST_W }
        val charL = themed(if (!character.isNullOrBlank()) "as $character" else "", "x-cast-char").apply {
            isWrapText = true; minWidth = 0.0; maxWidth = CAST_W; maxHeight = 30.0
        }
        return VBox(6.0, frame, nameL, charL).apply {
            minWidth = CAST_W; prefWidth = CAST_W; maxWidth = CAST_W
        }
    }

    private fun viewAllLink(onClick: () -> Unit): Button = Button("View All →").apply {
        styleClass.add("x-link")
        isFocusTraversable = false
        setOnAction { onClick() }
    }

    private fun viewAllCard(title: String, sub: String, onClick: () -> Unit): VBox {
        val arrow = StackPane(Icons.of(Icons.CHEVRON_RIGHT, 22.0)).apply { styleClass.add("x-viewall-arrow") }
        val box = VBox(8.0, arrow,
            themed(title, "x-viewall-t").apply { textAlignment = javafx.scene.text.TextAlignment.CENTER },
            themed(sub, "x-viewall-s").apply { textAlignment = javafx.scene.text.TextAlignment.CENTER },
        ).apply {
            styleClass.add("x-viewall")
            alignment = Pos.CENTER
            minWidth = CAST_W; prefWidth = CAST_W; maxWidth = CAST_W
            minHeight = CAST_H; prefHeight = CAST_H; maxHeight = CAST_H
            cursor = javafx.scene.Cursor.HAND
            setOnMouseClicked { onClick() }
        }
        return VBox(6.0, box, themed("", "x-cast-name"), themed("", "x-cast-char")).apply {
            minWidth = CAST_W; prefWidth = CAST_W; maxWidth = CAST_W
        }
    }

    private fun renderStudios() {
        val studios = enrich?.studios.orEmpty()
        if (studios.isEmpty()) {
            studioSection.isVisible = false; studioSection.isManaged = false; return
        }
        val row = HBox(10.0).apply { alignment = Pos.CENTER_LEFT; minWidth = 0.0 }
        studios.forEach { st ->
            val chip = HBox(8.0).apply {
                styleClass.add("x-studio")
                alignment = Pos.CENTER
                if (!st.logoUrl.isNullOrBlank()) {
                    val logo = ImageView().apply { isPreserveRatio = true; isSmooth = true; fitHeight = 26.0; isMouseTransparent = true }
                    children.add(logo)
                    desktop.img.ImageLoader.loadAsync(st.logoUrl, onReady = { img -> if (img != null) logo.image = img }, w = 240, h = 60)
                } else {
                    children.add(themed(st.name, "x-studio-t"))
                }
            }
            row.children.add(chip)
        }
        studioSection.children.setAll(
            themed("PRODUCTION STUDIOS", "x-over").apply { styleClass.add("h-upper") },
            row,
        )
        studioSection.isVisible = true; studioSection.isManaged = true
    }

    private fun renderShots() {
        val shots = enrich?.backdrops.orEmpty()
        if (shots.size < 2) {
            shotsSection.isVisible = false; shotsSection.isManaged = false; return
        }
        val shown = if (shotsExpanded) shots else shots.take(5)
        val big = shotView(shown[0], SHOT_BIG_W, SHOT_BIG_H)
        val grid = GridPane().apply { hgap = 8.0; vgap = 8.0; minWidth = 0.0 }
        shown.drop(1).take(4).forEachIndexed { i, url ->
            val last = i == 3 && !shotsExpanded && shots.size > 5
            grid.add(shotView(url, SHOT_SM_W, SHOT_SM_H, if (last) "+${shots.size - 5} more" else null) {
                if (last) {
                    shotsExpanded = true; renderSections()
                } else {
                    desktop.fx.DesktopUi.open(url.replace("/w1280", "/original"))
                }
            }, i % 2, i / 2)
        }
        val mosaic = HBox(8.0, big, grid).apply { alignment = Pos.TOP_LEFT; minWidth = 0.0 }
        big.setOnMouseClicked { desktop.fx.DesktopUi.open(shown[0].replace("/w1280", "/original")) }
        val head = HBox(10.0,
            themed("Screenshots", "x-sect").apply { styleClass.add("section-title") },
            themed(shots.size.toString(), "x-count"),
        ).apply { alignment = Pos.CENTER_LEFT }
        shotsSection.children.setAll(head, mosaic)
        shotsSection.isVisible = true; shotsSection.isManaged = true
    }

    private fun shotView(url: String, w: Double, h: Double, more: String? = null, onClick: (() -> Unit)? = null): StackPane {
        val art = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
        val frame = StackPane(art).apply {
            styleClass.add("x-shot")
            clip = Rectangle(w, h).apply { arcWidth = 14.0; arcHeight = 14.0 }
            minWidth = w; prefWidth = w; maxWidth = w
            minHeight = h; prefHeight = h; maxHeight = h
            cursor = javafx.scene.Cursor.HAND
        }
        art.fitWidth = w
        art.fitHeight = h
        desktop.img.ImageLoader.loadAsync(url, onReady = { img ->
            if (img != null) Ui.coverImage(art, img, w, h)
        }, w = w.toInt(), h = h.toInt())
        if (more != null) {
            val label = themed(more.split(" ")[0], "x-shot-more-n")
            val sub = themed("more", "x-shot-more-s")
            val moreBox = VBox(0.0, label, sub).apply {
                styleClass.add("x-shot-more")
                alignment = Pos.CENTER
                maxWidth = Double.MAX_VALUE; maxHeight = Double.MAX_VALUE
            }
            frame.children.add(moreBox)
        }
        if (onClick != null) frame.setOnMouseClicked { onClick() }
        return frame
    }

    private fun renderTrailers() {
        val trailers = enrich?.trailers.orEmpty()
        if (trailers.isEmpty()) {
            trailersSection.isVisible = false; trailersSection.isManaged = false; return
        }
        val row = HBox(Theme.S4).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(Theme.S1, Theme.S1, Theme.S2, Theme.S1)
            minWidth = 0.0
        }
        trailers.forEach { row.children.add(trailerCard(it)) }
        val head = HBox(10.0,
            themed("Videos & Trailers", "x-sect").apply { styleClass.add("section-title") },
            themed(trailers.size.toString(), "x-count"),
        ).apply { alignment = Pos.CENTER_LEFT }
        trailersSection.children.setAll(head, Ui.rail(row))
        trailersSection.isVisible = true; trailersSection.isManaged = true
    }

    private fun trailerCard(t: TmdbDetails.Trailer): VBox {
        val art = ImageView().apply { isPreserveRatio = false; isSmooth = true; isMouseTransparent = true }
        val frame = StackPane(art).apply {
            styleClass.add("x-trailer")
            clip = Rectangle(TRAIL_W, TRAIL_H).apply { arcWidth = 14.0; arcHeight = 14.0 }
            minWidth = TRAIL_W; prefWidth = TRAIL_W; maxWidth = TRAIL_W
            minHeight = TRAIL_H; prefHeight = TRAIL_H; maxHeight = TRAIL_H
            cursor = javafx.scene.Cursor.HAND
        }
        art.fitWidth = TRAIL_W
        art.fitHeight = TRAIL_H
        desktop.img.ImageLoader.loadAsync(t.thumbUrl, onReady = { img ->
            if (img != null) Ui.coverImage(art, img, TRAIL_W, TRAIL_H)
        }, w = 480, h = 270)
        val play = StackPane(Icons.of(Icons.PLAY, 20.0)).apply {
            styleClass.add("x-trailer-play")
            isMouseTransparent = true
        }
        frame.children.add(play)
        val tags = HBox(6.0).apply { alignment = Pos.TOP_LEFT; isMouseTransparent = true }
        if (t.official) tags.children.add(Label("OFFICIAL").apply { styleClass.add("x-tag-off") })
        tags.children.add(Label("TRAILER").apply { styleClass.add("x-tag") })
        StackPane.setAlignment(tags, Pos.TOP_LEFT)
        StackPane.setMargin(tags, Insets(8.0, 0.0, 0.0, 8.0))
        frame.children.add(tags)
        frame.setOnMouseClicked { desktop.fx.DesktopUi.open(t.watchUrl) }
        val name = themed(t.name, "x-trailer-t").apply {
            isWrapText = true; minWidth = 0.0; maxWidth = TRAIL_W; maxHeight = 36.0
        }
        return VBox(6.0, frame, name).apply {
            minWidth = TRAIL_W; prefWidth = TRAIL_W; maxWidth = TRAIL_W
        }
    }

    // ── source chooser dialog ───────────────────────────────────────────────

    private fun buildSourceDialog() {
        srcDlgBg.fitWidthProperty().bind(srcDlgRoot.widthProperty())
        srcDlgBg.fitHeightProperty().bind(srcDlgRoot.heightProperty())
        srcDlgRoot.widthProperty().addListener { _, _, w ->
            Ui.coverImage(srcDlgBg, srcDlgArt, w.toDouble(), srcDlgRoot.height)
        }
        srcDlgRoot.heightProperty().addListener { _, _, h ->
            Ui.coverImage(srcDlgBg, srcDlgArt, srcDlgRoot.width, h.toDouble())
        }
        val scrim = Region().apply {
            styleClass.add("x-src-sheet-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }
        val head = VBox(8.0, srcDlgLogo, srcDlgTitle, srcDlgPill).apply {
            alignment = Pos.CENTER
            minWidth = 0.0
            maxWidth = 680.0
        }
        val statusRow = HBox(10.0, srcDlgStatus, Ui.spacer(), srcDlgCount, Ui.isolateClicks(srcDlgEye)).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = 680.0
        }
        val buttons = HBox(12.0, Ui.isolateClicks(srcDlgSkip), Ui.isolateClicks(srcDlgCancel)).apply {
            alignment = Pos.CENTER
            minWidth = 0.0
        }
        srcDlgCard.children.addAll(head, statusRow, srcDlgBar, srcDlgScroll, buttons)
        srcDlgCard.alignment = Pos.CENTER
        val page = StackPane(srcDlgCard).apply {
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            padding = Insets(28.0, 24.0, 28.0, 24.0)
        }
        StackPane.setAlignment(srcDlgCard, Pos.CENTER)
        srcDlgRoot.children.addAll(srcDlgBg, scrim, page)
        srcDlgRoot.setOnMouseClicked { e ->
            if (e.target === srcDlgRoot) closeSourceDialog()
        }
    }

    private fun toggleSheetBlur() {
        srcDlgBlurred = !srcDlgBlurred
        srcDlgBg.effect = if (srcDlgBlurred) SPOILER_BLUR else null
    }

    /** "Skip Loading": play the best of what has landed so far, now. */
    private fun skipLoading() {
        val list = streams
        if (list.isEmpty()) {
            AppShell.toast("Still looking for sources…")
            return
        }
        playBest(list)
    }

    /** Closes the topmost overlay for Escape (sheet first, discovery second). */
    fun closeTopOverlay(): Boolean {
        if (srcDlgRoot.isVisible) {
            closeSourceDialog()
            return true
        }
        if (discRoot.isVisible) {
            cancelDiscovery()
            return true
        }
        return false
    }

    private fun openSourceDialog(ep: Episode) {
        srcDlgEp = ep
        srcDlgBlurred = false
        srcDlgBg.effect = null
        srcDlgArt = heroArtA ?: heroArtB
        Ui.coverImage(srcDlgBg, srcDlgArt, srcDlgRoot.width.coerceAtLeast(800.0), srcDlgRoot.height.coerceAtLeast(500.0))
        val logoImg = heroLogo.image
        if (enrich?.logoUrl != null && logoImg != null) {
            srcDlgLogo.image = logoImg
            srcDlgLogo.isVisible = true
            srcDlgLogo.isManaged = true
            srcDlgTitle.isVisible = false
            srcDlgTitle.isManaged = false
        } else {
            srcDlgLogo.isVisible = false
            srcDlgLogo.isManaged = false
            srcDlgTitle.text = meta.title
            srcDlgTitle.isVisible = true
            srcDlgTitle.isManaged = true
        }
        srcDlgPill.text = if (meta.type == MediaType.SERIES) {
            "S${ep.season}:E${ep.number} • " + (ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}")
        } else {
            meta.title
        }
        srcDlgSub.text = if (meta.type == MediaType.SERIES) {
            "S${ep.season} • Episode ${ep.number}" + (ep.name?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        } else {
            meta.title
        }
        renderDialogRows()
        srcDlgRoot.isVisible = true
        srcDlgRoot.isManaged = true
        Ui.fadeIn(srcDlgCard, 160.0)
    }

    private fun closeSourceDialog() {
        srcDlgRoot.isVisible = false
        srcDlgRoot.isManaged = false
        srcDlgEp = null
    }

    private fun renderDialogRows() {
        val ep = srcDlgEp ?: return
        if (streams.isEmpty()) {
            val errors = AppShell.app.repository.sweepErrors(meta, ep)
            val running = AppShell.app.repository.sweepRunning(meta, ep)
            val body = buildString {
                append(if (running) "Asking every installed extension…" else "No installed extension returned a stream for this episode.")
                if (errors.isNotEmpty()) {
                    append("\n\n")
                    errors.entries.take(6).forEach { (id, why) ->
                        append("• ${AppShell.app.repository.providerName(id)} — ${why.take(180)}\n")
                    }
                }
            }.trim()
            srcDlgStatus.text = if (running) "Discovering streaming sources…" else "No sources found"
            srcDlgCount.text = if (running) "searching…" else "0 SOURCES"
            srcDlgBar.progress = -1.0
            srcDlgBar.isVisible = running
            srcDlgBar.isManaged = running
            srcDlgRows.children.setAll(
                if (running) Ui.loadingRow("Fetching sources…")
                else Ui.emptyState(Icons.INFO, "No playable source found", body),
            )
            return
        }
        val running = AppShell.app.repository.sweepRunning(meta, ep)
        srcDlgStatus.text = if (running) {
            "Found ${streams.size} source${if (streams.size == 1) "" else "s"} • Finding more…"
        } else {
            "Found ${streams.size} source${if (streams.size == 1) "" else "s"} • Select a source to play"
        }
        srcDlgCount.text = "${streams.size} SOURCES"
        srcDlgBar.progress = 1.0
        srcDlgBar.isVisible = running
        srcDlgBar.isManaged = running
        srcDlgRows.children.setAll(streams.map { sourceRow(it) })
    }

    // ── sources ─────────────────────────────────────────────────────────────

    /**
     * Fetches this title's servers and then KEEPS WATCHING.
     *
     * Sources are collected across every installed extension of the title's
     * engine (see [com.hikari.app.data.ContentRepository.streamsFor]), and that
     * sweep returns as soon as the first server is playable — so the list on
     * screen keeps growing for a few seconds after it appears. A second server
     * that arrives late is exactly the one the user needs when the first one is
     * dead, so the panel (and the player's Source menu, when it is open) is
     * updated as they land instead of freezing the list at "whatever answered
     * first".
     */
    private fun loadStreams() {
        sweepPoll?.cancel()
        val ep = selectedEpisode
        if (srcDlgRoot.isVisible) renderDialogRows()
        scope.launch {
            val list = runCatching { AppShell.app.repository.streamsFor(meta, ep) }
                .getOrElse { t ->
                    Fx.run {
                        if (pendingPlay || pendingDownload) {
                            hideDiscovery()
                            pendingPlay = false
                            pendingDownload = false
                            AppShell.toast("Stream lookup failed: ${t.message ?: "unknown error"}", "error")
                        }
                        if (srcDlgRoot.isVisible) renderDialogRows()
                    }
                    emptyList()
                }
            Fx.run {
                streams = list
                if (srcDlgRoot.isVisible) renderDialogRows()
                if (pendingPlay) {
                    if (list.isEmpty()) {
                        pendingPlay = false
                        hideDiscovery()
                        AppShell.toast("No playable source found for this title", "error")
                    } else {
                        // Play straight away: the race starts as soon as the
                        // sources land, instead of waiting for a click.
                        discStatus2.text = "Discovering high-speed playback sources…"
                        playBest(list)
                    }
                }
                if (pendingDownload) {
                    pendingDownload = false
                    hideDiscovery()
                    downloadFirst()
                }
            }
            pollSweep(ep)
        }
    }

    /** Polls the running sweep for servers that landed after the first one, and
     *  shows them. Stops when the sweep is done (or the screen goes away). */
    private fun pollSweep(ep: Episode?) {
        val repo = AppShell.app.repository
        val job = scope.launch {
            while (true) {
                delay(1200L)
                val all = repo.sweepSnapshot(meta, ep)
                val running = repo.sweepRunning(meta, ep)
                if (all.size > streams.size) {
                    Fx.run {
                        streams = all
                        if (srcDlgRoot.isVisible) renderDialogRows()
                        // The player is open on this title: give it the new
                        // servers too, so its Source menu can move to one of them
                        // without a trip back here.
                        DesktopPlayer.updateSources(all)
                    }
                }
                if (!running) break
            }
            Fx.run { if (srcDlgRoot.isVisible) renderDialogRows() }
        }
        sweepPoll = job
    }

    private fun sourceRow(source: StreamSource): HBox {
        val quality = qualityOf(source)
        val badges = listOfNotNull(
            if (source.isM3u8) "HLS" else null,
            if (source.isMpd) "DASH" else null,
            if (source.isTorrent) "TORRENT" else null,
            if (source.externalUrl) "EXTERNAL" else null,
            if (source.ytId != null) "YOUTUBE" else null,
            source.subtitles.takeIf { it.isNotEmpty() }?.let { "SUBS ${it.size}" },
        ).joinToString(" · ")

        val qualityBadge = Ui.badge(quality.ifBlank { "SRC" }, if (quality.isNotBlank()) "x-src-q" else "badge")
        val qualityBox = VBox(qualityBadge).apply {
            alignment = Pos.CENTER
            prefWidth = 54.0
            minWidth = 54.0
            maxWidth = 54.0
        }
        val info = VBox(1.0,
            themed(source.name, "src-name").apply {
                isWrapText = true
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            },
            themed(badges.ifBlank { source.url.take(80) }, "src-meta").apply {
                isWrapText = true
                textOverrun = javafx.scene.control.OverrunStyle.ELLIPSIS
            },
        ).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val browser = Ui.isolateClicks(Ui.iconButton(Icons.EXTERNAL, "Open in browser", 15.0) {
            desktop.fx.DesktopUi.open(source.url)
        })
        val grab = Ui.isolateClicks(Ui.iconButton(Icons.DOWNLOAD, "Download this source", 15.0) {
            download(source)
        }).apply {
            isDisable = !downloadable(source)
        }
        val play = Button().apply {
            styleClass.add("src-go")
            graphic = Icons.of(Icons.PLAY, 15.0)
            isFocusTraversable = false
            setOnAction { play(source) }
        }
        val ready = themed("READY", "x-src-ready")
        val row = HBox(9.0, qualityBox, info, Ui.spacer(), ready, grab, browser, play).apply {
            styleClass.addAll("src-row", "x-src-sheet-row")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        row.setOnMouseClicked { play(source) }
        return row
    }

    private fun qualityOf(source: StreamSource): String {
        val name = source.name.lowercase()
        Regex("(\\d{3,4}p)").find(name)?.let { return it.groupValues[1].uppercase() }
        if (name.contains("4k") || name.contains("2160")) return "4K"
        if (name.contains("1080")) return "1080p"
        if (name.contains("720")) return "720p"
        return ""
    }

    private fun playFirst() {
        DetailScreenTestSeam.playPresses.incrementAndGet()
        val list = streams
        if (list.isNotEmpty()) {
            playBest(list)
        } else {
            // Nothing looked up yet: the discovery screen opens while the
            // sweep runs, and the fastest source starts the moment it lands.
            pendingPlay = true
            showDiscovery()
            loadStreams()
        }
    }

    /**
     * What the Play button does.
     *
     * With "Play straight away" on (Settings → Playback, the default), the
     * server race in [pickFastest] decides: whichever source answers first is
     * started, without making the user choose one. With it off, the provider's
     * own order wins.
     *
     * Either way the PLAYER is handed every source that was found, so its
     * Source menu can switch servers mid-playback — the race only decides where
     * playback starts.
     */
    private fun playBest(list: List<StreamSource>) {
        if (list.isEmpty()) return
        if (!AppShell.app.store.playFastest()) {
            play(list.first())
            return
        }
        scope.launch {
            val best = runCatching { pickFastest(list) }.getOrNull() ?: list.first()
            Fx.run { play(best) }
        }
    }

    /**
     * "The fastest server it finds" — measured, not guessed.
     *
     * The provider's ordering says nothing about which server is reachable from
     * THIS machine right now: a host that is blocked, throttled or simply slow
     * is the reason a video "takes ages to start" while three better servers sit
     * below it in the list. So the first few usable sources are asked
     * concurrently for their first bytes ([Http.streamLatencyMs]) and the
     * quickest answer wins; every probe is in flight at the same time, so the
     * cost is one round-trip, not a sum.
     *
     * Sources that must not be probed are left alone and keep the provider's
     * order: torrents and YouTube/external links (not media URLs), and signed
     * single-use links, whose first request would burn the token the player
     * needs.
     */
    private suspend fun pickFastest(list: List<StreamSource>): StreamSource? = withContext(Dispatchers.IO) {
        val probeable = list.filter {
            it.url.startsWith("http") && !it.externalUrl && it.ytId == null && !it.isTorrent &&
                !Http.isSignedStreamUrl(it.url)
        }.take(MAX_PROBE_SOURCES)
        if (probeable.size < 2) return@withContext probeable.firstOrNull()
        val measured = probeable.map { s ->
            async {
                s to runCatching { Http.streamLatencyMs(s.url, s.headers, PROBE_BUDGET_MS) }.getOrNull()
            }
        }.awaitAll()
        val winner = measured.filter { it.second != null }.minByOrNull { it.second ?: Long.MAX_VALUE }?.first
        println(
            "play: server race -> " + measured.joinToString(", ") { (s, ms) ->
                s.name.take(24) + "=" + (ms?.toString() ?: "no answer")
            } + "  winner=" + (winner?.name ?: "(provider order)"),
        )
        winner ?: probeable.first()
    }

    /** The banner's Download button: queue the best downloadable source, waiting
     *  for the source list when it hasn't arrived yet. */
    private fun downloadFirst() {
        val first = streams.firstOrNull { downloadable(it) }
        if (first != null) {
            download(first)
            return
        }
        if (streams.isEmpty() && !pendingDownload) {
            pendingDownload = true
            showDiscovery()
            loadStreams()
            return
        }
        AppShell.toast("No downloadable source found for this title", "error")
    }

    /** True when a source is something the download engine can actually fetch:
     *  an http(s) media URL, not a browser-only blob, a torrent, DASH or an
     *  external page. */
    private fun downloadable(source: StreamSource): Boolean {
        val url = source.url.trim()
        if (url.isBlank()) return false
        if (source.externalUrl || source.ytId != null) return false
        if (source.isTorrent || source.infoHash != null || source.isMpd) return false
        if (url.startsWith("blob:") || url.startsWith("data:")) return false
        return true
    }

    private fun download(source: StreamSource) {
        if (!downloadable(source)) {
            AppShell.toast("That source can only be played in a browser", "error")
            return
        }
        val ep = selectedEpisode
        // The kind decides whether the finished download also lands as a single
        // file in the user's own Downloads folder (the default) or stays inside
        // Hikari only.
        val kind = if (runCatching { AppShell.app.store.downloadToFolder() }.getOrDefault(true)) {
            DownloadKind.EXPORT
        } else {
            DownloadKind.OFFLINE
        }
        val label = ep?.name?.takeIf { it.isNotBlank() } ?: ep?.let { "Episode ${it.number}" } ?: ""
        val task = DownloadTask(
            id = DownloadTask.idFor(meta.providerId, meta.id, ep?.id ?: "", kind),
            title = meta.title,
            episodeLabel = label,
            poster = meta.posterUrl,
            providerId = meta.providerId,
            mediaId = meta.id,
            episodeId = ep?.id ?: "",
            sourceName = source.name,
            url = source.url,
            headers = source.headers,
            isM3u8 = source.isM3u8 || source.url.substringBefore('?').lowercase().contains(".m3u8"),
            subtitles = source.subtitles,
            kind = kind,
            status = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
        )
        DownloadsRepository.enqueue(task)
        AppShell.toast(
            if (label.isBlank()) "Downloading ${source.name}…" else "Downloading ${meta.title} · $label…",
            "ok",
        )
    }

    private fun play(source: StreamSource) {
        hideDiscovery()
        closeSourceDialog()
        val ep = selectedEpisode
        lastPositionSave = 0L
        runCatching {
            HikariApp.instance.store.addHistory(
                HistoryEntry(
                    providerId = meta.providerId,
                    mediaId = meta.id,
                    type = meta.type,
                    title = meta.title,
                    posterUrl = meta.posterUrl,
                    episodeId = ep?.id ?: "",
                    episodeName = ep?.name ?: "",
                    watchedAt = System.currentTimeMillis(),
                )
            )
        }
        watchedSet = if (ep != null) watchedSet + ep.id else watchedSet
        // Signed live links expire quickly, so a failed first attempt refetches
        // the provider's stream list instead of showing an error dialog.
        DesktopPlayer.play(
            "${meta.title} — ${source.name}",
            source,
            refresh = {
                runCatching {
                    kotlinx.coroutines.runBlocking { AppShell.app.repository.streamsFor(meta, ep) }
                }.getOrNull()?.firstOrNull()
            },
            // The player's own bar drives playback over mpv's IPC, so the
            // screen hands it the two things it alone knows: what comes next,
            // and where this episode got to.
            next = nextEpisodeAction(),
            position = { positionMs, durationMs -> savePosition(ep, positionMs, durationMs) },
            // The player's Source menu: every source this episode offered, so a
            // dead server can be swapped for another without leaving playback.
            sources = streams,
            onPickSource = { picked -> if (picked.url != source.url) play(picked) },
            // The player's centered Episodes picker: the whole season, so an
            // episode can be switched mid-playback. Picking one reselects it
            // here and replays (streams reload, then auto-play — the same path
            // the banner's Play button takes).
            episodes = episodes,
            currentEpisodeId = ep?.id.orEmpty(),
            onPickEpisode = { picked ->
                Fx.run {
                    pendingPlay = true
                    selectEpisode(picked)
                }
            },
        )
    }

    /** The player's "Next episode": advance to the following episode and play
     *  its first source once it loads. Null on the last episode (or a movie), so
     *  the player hides the button. */
    private fun nextEpisodeAction(): (() -> Unit)? {
        val current = selectedEpisode ?: return null
        val idx = episodes.indexOfFirst { it.id == current.id }
        if (idx < 0 || idx + 1 >= episodes.size) return null
        val next = episodes[idx + 1]
        return {
            Fx.run {
                selectedEpisode = next
                renderEpisodeCards()
                updateNowPlaying()
                pendingPlay = true
                loadStreams()
            }
        }
    }

    /** Records the playback position on the title's history row, so a re-open
     *  resumes where it left off. mpv reports the position about once a second,
     *  so the write is throttled. */
    private fun savePosition(ep: Episode?, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastPositionSave < POSITION_SAVE_INTERVAL_MS) return
        lastPositionSave = now
        runCatching {
            HikariApp.instance.store.addHistory(
                HistoryEntry(
                    providerId = meta.providerId,
                    mediaId = meta.id,
                    type = meta.type,
                    title = meta.title,
                    posterUrl = meta.posterUrl,
                    episodeId = ep?.id ?: "",
                    episodeName = ep?.name ?: "",
                    positionMs = positionMs,
                    durationMs = durationMs,
                    watchedAt = now,
                )
            )
        }
    }

    private fun htmlToPlain(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?[a-zA-Z][^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    private companion object {
        /** The anti-spoiler blur, shared by every episode still (and the
         *  sources sheet's eye toggle): heavy enough that no frame is
         *  recognisable, light enough to keep the card's colour. */
        val SPOILER_BLUR = GaussianBlur(26.0)

        /** Ceiling for the banner's height. It carries the logo treatment as
         *  well as the title, so it wants a real slice of the window. */
        const val HERO_H = 520.0

        /** The banner never shrinks below this, so the title, the metadata and
         *  the actions all fit even on a short window. */
        const val HERO_MIN = 380.0

        /** Cards rendered per "page" before the pager moves on. */
        const val EPISODE_PAGE = 30

        const val EP_CARD_W = 300.0
        const val EP_STILL_H = 168.0
        const val CAST_W = 132.0
        const val CAST_H = 198.0
        const val SHOT_BIG_W = 560.0
        const val SHOT_BIG_H = 315.0
        const val SHOT_SM_W = 272.0
        const val SHOT_SM_H = 153.0
        const val TRAIL_W = 320.0
        const val TRAIL_H = 180.0

        /** Minimum gap between two history position writes while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 10_000L

        /** How many of a title's sources the "fastest server" race probes.
         *  Enough to find a good one among the usual spread (three or four
         *  mirrors per provider), few enough that the race is one round-trip. */
        const val MAX_PROBE_SOURCES = 6

        /** How long one probe may take before that server is out of the race.
         *  The probes run in parallel, so this is also the race's worst case —
         *  and a server that needs longer than this to say hello is not the
         *  fastest one anyway. */
        const val PROBE_BUDGET_MS = 2_500L
    }
}
