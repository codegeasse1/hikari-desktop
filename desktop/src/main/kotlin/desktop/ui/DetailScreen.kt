package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import com.hikari.app.download.DownloadKind
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadTask
import com.hikari.app.download.DownloadsRepository
import desktop.fx.Fx
import desktop.player.DesktopPlayer
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
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
 *  - the banner sits at the top at a fixed height;
 *  - the LEFT column scrolls as a whole (synopsis, facts, and the full episode
 *    grid, which can run to hundreds of tiles);
 *  - the RIGHT column is the fixed-width "Watch" panel: it never leaves the
 *    screen and instead owns the vertical space *inside* itself, with the
 *    source list as the one growing, scrolling child.
 *
 * Every piece here is bounded on both axes (`Ui.fill` + explicit min sizes), so
 * the screen fits any window — the previous version let the episode grid and
 * the source list report their full content size as a minimum, which pushed the
 * panel off the window and made both unscrollable.
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
     * filter box you had to already know the number for). Now the grid shows the
     * page you are on, the picker lists every range ("1 - 30", "31 - 60", …) so
     * any part of the season is one click away, and Prev/Next walk the pages.
     * Searching still looks across the WHOLE season — the picker governs the
     * grid only when the search box is empty.
     */
    private var episodePage = 0

    // ── hero ────────────────────────────────────────────────────────────────

    private val heroImage = ImageView().apply {
        isPreserveRatio = false
        isSmooth = true
    }

    /** The banner's loaded artwork, kept so the cover-crop can be recomputed
     *  whenever the banner is resized (see [paintHero]). */
    private var heroArt: javafx.scene.image.Image? = null

    private fun applyHeroArt(img: javafx.scene.image.Image?) {
        heroArt = img
        paintHero()
    }

    private fun paintHero() {
        Ui.coverImage(heroImage, heroArt, hero.width, hero.height)
    }

    /**
     * The poster card on the RIGHT of the banner.
     *
     * The backdrop alone is a wide, short crop — it cut the artwork off at the
     * top and bottom and had nothing to say about the title as a *poster*. A 2:3
     * poster beside the details is what makes the header read like a store page:
     * artwork on the right, title/meta/actions on the left. It is sized from the
     * banner's width, so it can never push the title off the left edge.
     */
    private val heroPoster = ImageView().apply {
        isPreserveRatio = false
        isSmooth = true
    }
    private var heroPosterArt: javafx.scene.image.Image? = null

    private fun paintPoster() {
        Ui.coverImage(heroPoster, heroPosterArt, posterW, posterH)
    }

    private val posterClip = Rectangle().apply {
        arcWidth = 28.0
        arcHeight = 28.0
    }
    private val heroPosterFrame = StackPane(heroPoster).apply {
        styleClass.add("d-poster")
        clip = posterClip
        isVisible = false
        isManaged = false
    }
    private var posterW = 0.0
    private var posterH = 0.0

    /** Fits the poster to the room left beside the title; below ~720px there is
     *  none and the banner falls back to text only. */
    private fun sizePoster(heroWidth: Double) {
        val w = when {
            heroWidth >= 1040.0 -> 200.0
            heroWidth >= 880.0 -> 176.0
            heroWidth >= 720.0 -> 148.0
            else -> 0.0
        }
        val show = w > 0.0 && heroPosterArt != null
        if (w == posterW && show == heroPosterFrame.isVisible) return
        posterW = w
        posterH = w * 1.5
        heroPosterFrame.isVisible = show
        heroPosterFrame.isManaged = show
        if (!show) return
        heroPosterFrame.prefWidth = posterW
        heroPosterFrame.minWidth = posterW
        heroPosterFrame.maxWidth = posterW
        heroPosterFrame.prefHeight = posterH
        heroPosterFrame.minHeight = posterH
        heroPosterFrame.maxHeight = posterH
        posterClip.width = posterW
        posterClip.height = posterH
        paintPoster()
    }

    private val heroOver = themed("", "d-over")
    private val heroTitle = themed("", "d-title").apply {
        // Wrapped and width-bounded: an unwrapped 32px title declares its whole
        // text width as a *minimum*, which is what slid this screen (and its
        // window) sideways.
        isWrapText = true
        maxWidth = 560.0
        minWidth = 0.0
        maxHeight = 84.0
    }
    private val heroMeta = themed("", "d-meta").apply {
        isWrapText = true
        maxWidth = 560.0
        minWidth = 0.0
    }
    private val heroChips = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroActions = HBox(10.0,
        // The banner's Play button. It is the primary action on this screen and
        // it has to be reachable by a plain click, so it carries an id for the
        // UI test to find, measure and click (see UiShotTest's detail-back section
        // — the same wiring the back arrow uses).
        Ui.playButton("Play") { playFirst() }.apply { id = "heroPlayBtn" },
        Ui.button("Download", icon = Icons.DOWNLOAD, ghost = true) { downloadFirst() },
    ).apply { alignment = Pos.CENTER_LEFT }
    private val heroBody = VBox(10.0, heroOver, heroTitle, heroMeta, heroChips, heroActions).apply {
        alignment = Pos.BOTTOM_LEFT
        minWidth = 0.0
        maxWidth = 580.0
    }
    private val favouriteButton = Ui.button("Add to library", icon = Icons.HEART_OUTLINE, ghost = true) { toggleFavourite() }
    private val hero = StackPane()

    // ── left column ─────────────────────────────────────────────────────────

    private val leftColumn = VBox(Theme.S4)
    private val synopsis = themed("", "prose").apply {
        isWrapText = true
        styleClass.add("h-dim")
    }
    private val facts = VBox(9.0)

    private val episodeFilter = TextField().apply {
        styleClass.addAll("field", "ep-filter")
        promptText = "Find episode by number or name…"
        textProperty().addListener { _, _, _ -> renderEpisodeGrid() }
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
                renderEpisodeGrid()
            }
        }
    }
    private val episodePrev = Ui.button("Prev 30", ghost = true) {
        if (episodePage > 0) {
            episodePage--
            renderEpisodeGrid()
        }
    }
    private val episodeNext = Ui.button("Next 30", ghost = true) {
        val pages = maxOf(1, (episodes.size + EPISODE_PAGE - 1) / EPISODE_PAGE)
        if (episodePage < pages - 1) {
            episodePage++
            renderEpisodeGrid()
        }
    }
    private val episodeGrid = TilePane(7.0, 7.0).apply {
        prefColumns = 8
        prefTileWidth = 56.0
        prefTileHeight = 34.0
        styleClass.add("ep-grid-inner")
        minWidth = 0.0
    }
    private val episodeHint = themed("", "tiny").apply { isWrapText = true }

    private val episodeSection = VBox(Theme.S3)

    /** The scrolling column that holds the episodes, so the selected episode can
     *  be brought into view (see [revealSelected]). */
    private lateinit var leftScroll: ScrollPane

    /** Episode id → its tile, so the selected one can be scrolled to. */
    private val episodeTiles = HashMap<String, Button>()

    // ── watch panel ─────────────────────────────────────────────────────────

    private val panelSub = themed("", "watch-sub")
    private val nowPlayingTitle = themed("", "now-playing-title").apply {
        // Episode names run long ("Renegade Immortal (Xian Ni) Episode 1 English
        // Subtitles") and the panel is a fixed 396px — wrap to two lines rather
        // than cutting it off mid-word with an ellipsis.
        isWrapText = true
        maxHeight = 34.0
        minWidth = 0.0
    }
    private val nowPlayingSub = themed("", "now-playing-sub")
    private val nowPlaying = VBox(2.0, nowPlayingTitle, nowPlayingSub).apply {
        styleClass.add("now-playing")
        isVisible = false
        isManaged = false
    }
    private val sourcesHeader = themed("", "panel-title")
    private val sourcesCount = themed("", "watch-sub")
    private val sourcesBox = VBox(8.0)
    private val sourcesScroll = ScrollPane(sourcesBox).apply {
        isFitToWidth = true
        styleClass.add("scroll-pane")
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        minWidth = 0.0
        minHeight = 0.0
        maxWidth = Double.MAX_VALUE
    }
    private val watchPanel = VBox().apply {
        styleClass.add("watch")
        prefWidth = PANEL_W
        minWidth = PANEL_W
        maxWidth = PANEL_W
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
        val head = VBox(2.0, themed("Watch", "watch-title"), panelSub).apply { styleClass.add("watch-head") }
        val body = VBox(Theme.S3).apply {
            styleClass.add("watch-body")
            minHeight = 0.0
            maxHeight = Double.MAX_VALUE
            children.addAll(
                nowPlaying,
                Ui.divider(),
                HBox(10.0, sourcesHeader, Ui.spacer(), sourcesCount).apply { alignment = Pos.CENTER_LEFT },
                sourcesScroll,
            )
        }
        VBox.setVgrow(sourcesScroll, Priority.ALWAYS)
        VBox.setVgrow(body, Priority.ALWAYS)
        children.addAll(head, body)
    }

    val root: BorderPane = BorderPane().apply {
        padding = Insets(Theme.S4, Theme.S5, Theme.S5, Theme.S5)
    }

    init {
        root.sceneProperty().addListener { _, _, scene -> if (scene == null) scope.cancel() }
        buildHero()
        leftScroll = Ui.vScroll(leftColumn, Insets(0.0, 6.0, 24.0, 0.0))
        val side = HBox(Theme.S5, leftScroll, watchPanel).apply {
            alignment = Pos.TOP_LEFT
            minHeight = 0.0
        }
        HBox.setHgrow(leftScroll, Priority.ALWAYS)
        Ui.fill(leftColumn)
        val content = VBox(Theme.S4, hero, side).apply { minHeight = 0.0 }
        VBox.setVgrow(side, Priority.ALWAYS)
        Ui.fill(leftScroll)
        Ui.fill(side)
        Ui.fill(content)
        root.center = content
        // The screen is painted from the catalog item we were handed, BEFORE any
        // network call: the banner, the title block and the Synopsis/Details
        // panels are already on screen when the click's frame ends. The item a
        // grid hands over carries the title, year, genres, overview and artwork,
        // so `metaFor` only ever *enriches* what is already there. Opening a
        // title used to show an empty banner and a spinner while that round-trip
        // ran, which is what "loading takes a moment" looked like.
        renderHero(item)
        renderFacts(item)
        // The sources panel shows its own "Fetching sources…" spinner (see
        // loadStreams), and the Episodes section pops in when the season lands.
        renderPanel()
        load()
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private fun load() {
        scope.launch {
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
                renderHero(loaded)
                renderFacts(loaded)
                renderEpisodes()
                val first = episodes.firstOrNull { it.id == resumeId } ?: episodes.firstOrNull()
                if (first != null) {
                    selectedEpisode = first
                    renderEpisodeGrid()
                    updateNowPlaying()
                }
                renderPanel()
                loadStreams()
                revealSelected()
            }
        }
    }

    /**
     * Brings the selected episode's tile into view.
     *
     * Opening a series used to leave the episode list below the fold (under a
     * long synopsis) and, when resuming, the highlighted episode somewhere
     * inside a grid of hundreds — so the user could not tell where they were in
     * the series. The episodes panel is now the first thing in the scrolling
     * column, and this makes sure the *selected* tile is actually on screen.
     */
    private fun revealSelected() {
        val ep = selectedEpisode ?: return
        val tile = episodeTiles[ep.id] ?: return
        javafx.application.Platform.runLater {
            javafx.application.Platform.runLater {
                val viewport = leftScroll.viewportBounds.height
                val content = leftColumn.height
                if (viewport <= 0.0 || content <= viewport) return@runLater
                val inColumn = leftColumn.sceneToLocal(tile.localToScene(tile.boundsInLocal))
                val target = inColumn.minY + inColumn.height / 2.0 - viewport / 2.0
                leftScroll.vvalue = (target / (content - viewport)).coerceIn(0.0, 1.0)
            }
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

    private fun renderHero(m: MediaItem) {
        hero.isVisible = true
        hero.isManaged = true
        // The title/meta/action block appears with the details; until then the
        // banner is an empty surface with the back arrow on it (see init).
        heroBody.isVisible = true
        heroBody.isManaged = true
        val provider = runCatching {
            AppShell.app.store.providers().firstOrNull { it.id == m.providerId }?.name
        }.getOrNull()
        heroOver.text = provider.orEmpty()
        heroOver.isVisible = !provider.isNullOrBlank()
        heroOver.isManaged = heroOver.isVisible
        heroTitle.text = m.title
        heroMeta.text = listOfNotNull(
            m.year?.toString(),
            when (m.type) {
                MediaType.SERIES -> "Series"
                MediaType.MOVIE -> "Movie"
                MediaType.UNKNOWN -> null
            },
            if (episodes.isNotEmpty()) "${episodes.size} episodes" else null,
            m.genres.take(3).joinToString(" · ").ifBlank { null },
        ).joinToString("  ·  ")
        heroChips.children.setAll(m.genres.take(6).map { themed(it, "mchip") })
        // The poster card on the right of the banner: only a real poster goes
        // there — but when the provider leaves the poster empty (several anime
        // catalogs only fill the landscape image) the backdrop is used instead,
        // so the card shows the same artwork as the grid thumbnail rather than
        // an empty box.
        heroPosterArt = null
        val posterArt = desktop.img.ImageLoader.artFor(m.posterUrl, m.backdropUrl)
        if (!posterArt.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(
                posterArt,
                onReady = { img ->
                    heroPosterArt = img
                    sizePoster(hero.width)
                },
                w = 480,
                h = 720,
            )
        } else {
            sizePoster(hero.width)
        }
        val url = desktop.img.ImageLoader.artFor(m.backdropUrl, m.posterUrl)
        if (!url.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(url, onReady = { img -> applyHeroArt(img) }, w = 1600, h = 900)
        }
        synopsis.text = m.overview?.let { htmlToPlain(it) }?.takeIf { it.isNotBlank() }
            ?: "No synopsis available for this title."
        favourite = runCatching { AppShell.app.store.favorites().any { it.uniqueId == m.uniqueId } }.getOrDefault(false)
        refreshFavourite()
    }

    private fun renderFacts(m: MediaItem) {
        val provider = runCatching {
            AppShell.app.store.providers().firstOrNull { it.id == m.providerId }?.name
        }.getOrNull()
        val rows = listOfNotNull(
            provider?.let { "Provider" to it },
            "Type" to when (m.type) {
                MediaType.SERIES -> "Series"
                MediaType.MOVIE -> "Movie"
                MediaType.UNKNOWN -> "Unknown"
            },
            m.year?.let { "Released" to it.toString() },
            m.genres.takeIf { it.isNotEmpty() }?.let { "Genres" to it.joinToString(", ") },
            if (episodes.isNotEmpty()) "Episodes" to "${episodes.size}" else null,
            "Source id" to m.id,
        )
        facts.children.setAll(rows.map { (k, v) ->
            val value = themed(v, "label").apply {
                isWrapText = true
                maxWidth = 620.0
            }
            HBox(12.0, themed(k, "tiny").apply { prefWidth = 82.0; minWidth = 82.0 }, value).apply {
                alignment = Pos.TOP_LEFT
                minWidth = 0.0
            }
        })
        // Episodes come first: they are what the user opened the title for. A
        // long synopsis above them pushed the whole episode grid below the fold,
        // which is why opening a series gave no clue where the episodes (or the
        // one you left off on) were.
        val panels = mutableListOf<Node>()
        if (episodes.isNotEmpty()) panels.add(episodeSection)
        panels.add(Ui.panel(Ui.sectionHeader("Synopsis"), synopsis))
        panels.add(Ui.panel(Ui.sectionHeader("Details"), facts))
        leftColumn.children.setAll(panels)
    }

    private fun buildHero() {
        val clip = Rectangle().apply {
            arcWidth = 32.0
            arcHeight = 32.0
        }
        clip.widthProperty().bind(hero.widthProperty())
        clip.heightProperty().bind(hero.heightProperty())
        hero.clip = clip
        hero.styleClass.add("d-hero-wrap")
        hero.minWidth = 0.0
        hero.maxWidth = Double.MAX_VALUE
        // A sane height for the very first layout pass; the listener below fits
        // it to the window once the root has been laid out.
        hero.prefHeight = HERO_MIN
        hero.minHeight = HERO_MIN
        hero.maxHeight = HERO_MIN
        // The banner carries the poster as well as the title now, and the old
        // 248px ceiling left the two fighting for the same strip — the reason
        // the header looked "cut off". It takes a generous slice of the window
        // (clamped) instead. The window height is independent of this value, so
        // the listener settles in one pass.
        root.heightProperty().addListener { _, _, h ->
            val target = (h.toDouble() * 0.42).coerceIn(HERO_MIN, HERO_H)
            if (hero.prefHeight != target) {
                hero.prefHeight = target
                hero.minHeight = target
                hero.maxHeight = target
            }
        }
        // The artwork always *covers* the banner: a portrait poster used to be
        // letterboxed into a narrow strip in the middle of a wide banner, which
        // is what "the header image is collapsing" looked like. Re-cropped on
        // every resize instead of stretched.
        heroImage.isPreserveRatio = false
        heroImage.fitWidthProperty().bind(hero.widthProperty())
        heroImage.fitHeightProperty().bind(hero.heightProperty())
        hero.widthProperty().addListener { _, _, w ->
            paintHero()
            sizePoster(w.toDouble())
        }
        hero.heightProperty().addListener { _, _, _ -> paintHero() }

        val scrim = Region().apply {
            styleClass.add("d-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }
        val scrimR = Region().apply {
            styleClass.add("d-scrim-r")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            isMouseTransparent = true
        }

        // The banner's own back arrow. Two things about it are load-bearing:
        //
        //  - it carries NO tooltip. A JavaFX tooltip is a popup window of its
        //    own, and a click that arrives while one is up is spent dismissing it
        //    instead of pressing the button (see Ui.tooltip) — on a one-click
        //    control that reads as "the back button does not work". The arrow
        //    needs no label anyway: the shell's top bar spells "Back" out right
        //    above it.
        //  - it is added to the StackPane LAST (see the children.addAll at the
        //    end of this function), so nothing painted over the banner — the
        //    title block, the poster card, the scrims — can cover it or take its
        //    clicks.
        val back = Ui.iconButton(Icons.CHEVRON_LEFT, null, 18.0) { AppShell.back() }.apply {
            styleClass.addAll("round-btn", "hero-back")
            id = "heroBackBtn"
            accessibleText = "Back"
        }
        StackPane.setAlignment(back, Pos.TOP_LEFT)
        StackPane.setMargin(back, Insets(16.0, 0.0, 0.0, 16.0))

        // Nothing decorative may be a pick target on a banner that carries
        // buttons: the artwork and the poster card are pictures, so a click that
        // lands on them is a click the user meant for Play or Download.
        heroImage.isMouseTransparent = true
        heroPosterFrame.isMouseTransparent = true

        // Sized to its own content. A StackPane stretches a child out to the
        // child's max size, and a VBox's max size is unbounded — so, left alone,
        // this little container became an invisible full-banner box lying OVER the
        // whole hero, added after the title block and therefore above the Play
        // button in the paint order, and it swallowed every click meant for it.
        // Its own bounds are all it needs.
        val topRight = VBox(8.0, favouriteButton).apply {
            alignment = Pos.TOP_RIGHT
            maxWidth = javafx.scene.layout.Region.USE_PREF_SIZE
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
        }
        StackPane.setAlignment(topRight, Pos.TOP_RIGHT)
        StackPane.setMargin(topRight, Insets(16.0, 16.0, 0.0, 0.0))

        StackPane.setAlignment(heroBody, Pos.BOTTOM_LEFT)
        StackPane.setMargin(heroBody, Insets(0.0, 24.0, 22.0, 26.0))

        StackPane.setAlignment(heroPosterFrame, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(heroPosterFrame, Insets(0.0, 26.0, 24.0, 0.0))

        // Paint order IS click priority in JavaFX: the back arrow goes in last so
        // it is on top of everything the banner draws, and the title/action block
        // sits directly under it. The Play button is the point of this screen, so
        // nothing but the arrow may ever be painted over it.
        hero.children.addAll(heroImage, scrim, scrimR, heroPosterFrame, topRight, heroBody, back)
    }

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
        favouriteButton.text = if (favourite) "In library" else "Add to library"
        favouriteButton.graphic = Icons.of(if (favourite) Icons.HEART else Icons.HEART_OUTLINE, 16.0)
    }

    // ── episodes ────────────────────────────────────────────────────────────

    private fun renderEpisodes() {
        if (episodes.isEmpty()) {
            episodeSection.isVisible = false
            episodeSection.isManaged = false
            return
        }
        episodeSection.isVisible = true
        episodeSection.isManaged = true
        episodeSection.children.setAll(
            Ui.sectionHeader("Episodes", "${episodes.size} available — pick one to load its sources"),
            HBox(Theme.S2, episodeFilter, episodeRange, episodePrev, episodeNext).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(episodeFilter, Priority.ALWAYS)
            },
            episodeGrid,
            episodeHint,
        )
        renderEpisodeGrid()
    }

    private fun renderEpisodeGrid() {
        if (episodes.isEmpty()) {
            episodeGrid.children.setAll()
            episodeHint.text = ""
            return
        }
        val needle = episodeFilter.text.trim().lowercase()
        val matches = if (needle.isEmpty()) {
            episodes.indices.toList()
        } else {
            episodes.indices.filter { i ->
                val ep = episodes[i]
                ep.number.toString().contains(needle) ||
                    (i + 1).toString() == needle ||
                    ep.name?.lowercase()?.contains(needle) == true
            }
        }
        val searching = needle.isNotEmpty()
        val pages = maxOf(1, (episodes.size + EPISODE_PAGE - 1) / EPISODE_PAGE)
        if (episodePage >= pages) episodePage = pages - 1
        if (episodePage < 0) episodePage = 0
        // The range picker lists the whole season as 30-episode ranges, so any
        // part of it is one click away — and it follows the page the code
        // selects (resuming mid-season lands on that page's range).
        if (!searching) {
            val labels = (0 until pages).map { p ->
                val from = p * EPISODE_PAGE + 1
                val to = minOf(episodes.size, (p + 1) * EPISODE_PAGE)
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
        val shown = if (searching) {
            matches.take(EPISODE_PAGE * 4)
        } else {
            val from = episodePage * EPISODE_PAGE
            matches.filter { it in from until minOf(episodes.size, from + EPISODE_PAGE) }
        }
        episodeTiles.clear()
        val tiles = shown.map { i ->
            val ep = episodes[i]
            val text = ep.name?.takeIf { it.isNotBlank() }?.let { name ->
                // Strip the show title so a tile reads "160" instead of 60 characters.
                val trimmed = name.replace(meta.title, "").trim().trim('-', ':', '|', '·')
                numOf(trimmed) ?: ep.number.toString()
            } ?: ep.number.toString()
            val tile = Button(text).apply {
                styleClass.add("ep-num")
                prefWidth = 56.0
                prefHeight = 34.0
                isFocusTraversable = false
                if (ep.id == selectedEpisode?.id) styleClass.add("ep-num-sel")
                setOnAction { selectEpisode(ep) }
            }
            episodeTiles[ep.id] = tile
            tile
        }
        if (tiles.isEmpty()) {
            episodeGrid.children.setAll(themed("No episode matches “${episodeFilter.text.trim()}”.", "tiny"))
        } else {
            episodeGrid.children.setAll(tiles)
        }
        episodeHint.text = when {
            needle.isNotEmpty() ->
                "${matches.size} episode(s) match “${episodeFilter.text.trim()}” —" +
                    " clear the box to page through the season 30 at a time."
            episodes.size > shown.size ->
                "Showing episodes " + (episodePage * EPISODE_PAGE + 1) + " - " +
                    (episodePage * EPISODE_PAGE + shown.size) + " of " + episodes.size +
                    " — pick another range above, or search by number or name."
            else -> ""
        }
        episodeHint.isVisible = episodeHint.text.isNotBlank()
        episodeHint.isManaged = episodeHint.isVisible
        episodePrev.isDisable = searching || episodePage <= 0
        episodeNext.isDisable = searching || episodePage >= pages - 1
        episodeRange.isDisable = searching || pages <= 1
    }

    private fun numOf(text: String): String? = Regex("(\\d{1,4})").find(text)?.groupValues?.get(1)

    private fun selectEpisode(ep: Episode) {
        selectedEpisode = ep
        // Put the page holding this episode on screen: picking "Next episode",
        // or resuming at episode 187 of 379, must not leave the grid on page 1.
        val idx = episodes.indexOfFirst { it.id == ep.id }
        if (idx >= 0) episodePage = idx / EPISODE_PAGE
        renderEpisodeGrid()
        updateNowPlaying()
        loadStreams()
    }

    private fun updateNowPlaying() {
        val ep = selectedEpisode
        if (ep == null) {
            nowPlaying.isVisible = false
            nowPlaying.isManaged = false
            return
        }
        nowPlayingTitle.text = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}"
        nowPlayingSub.text = meta.title
        nowPlaying.isVisible = true
        nowPlaying.isManaged = true
    }

    private fun renderPanel() {
        panelSub.text = when {
            episodes.isNotEmpty() -> "${episodes.size} episodes · pick one, then a source"
            streams.isNotEmpty() -> "${streams.size} source(s)"
            else -> "Find a source for this title"
        }
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
        sourcesBox.children.setAll(Ui.loadingRow("Fetching sources…"))
        sourcesHeader.text = "Sources"
        sourcesCount.text = ""
        sweepPoll?.cancel()
        val ep = selectedEpisode
        scope.launch {
            val list = runCatching { AppShell.app.repository.streamsFor(meta, ep) }
                .getOrElse { t ->
                    Fx.run {
                        sourcesBox.children.setAll(
                            themed("Stream lookup failed: ${t.message ?: t.javaClass.simpleName}", "tiny")
                                .apply { styleClass.add("h-danger"); isWrapText = true }
                        )
                    }
                    emptyList()
                }
            Fx.run {
                streams = list
                renderStreams()
                renderPanel()
                if (pendingPlay) {
                    pendingPlay = false
                    // Play straight away: the race starts as soon as the
                    // sources land, instead of waiting for a click.
                    playBest(list)
                }
                if (pendingDownload) {
                    pendingDownload = false
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
                        renderStreams()
                        renderPanel()
                        // The player is open on this title: give it the new
                        // servers too, so its Source menu can move to one of them
                        // without a trip back here.
                        DesktopPlayer.updateSources(all)
                    }
                }
                if (!running) break
            }
            Fx.run { renderStreams() }
        }
        sweepPoll = job
    }

    private fun renderStreams() {
        if (streams.isEmpty()) {
            // An empty list says WHAT happened, per extension. "No playable
            // source found" with nothing under it is the answer that tells a
            // user nothing at all — the sweep knows which extensions it asked
            // and what each of them said (see ContentRepository.sweepErrors).
            val errors = AppShell.app.repository.sweepErrors(meta, selectedEpisode)
            val running = AppShell.app.repository.sweepRunning(meta, selectedEpisode)
            val body = buildString {
                append(
                    if (selectedEpisode == null) {
                        "No installed extension returned a stream for this title."
                    } else {
                        "No installed extension returned a stream for this episode."
                    }
                )
                if (running) append(" Still asking…")
                if (errors.isNotEmpty()) {
                    append("\n\n")
                    errors.entries.take(6).forEach { (id, why) ->
                        val name = AppShell.app.repository.providerName(id)
                        append("• $name — ${why.take(180)}\n")
                    }
                }
            }.trim()
            sourcesBox.children.setAll(Ui.emptyState(Icons.INFO, "No playable source found", body))
            sourcesCount.text = if (running) "searching…" else "0 found"
            return
        }
        sourcesCount.text = "${streams.size} found"
        sourcesBox.children.setAll(streams.map { sourceRow(it) })
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

        val qualityBadge = Ui.badge(quality.ifBlank { "SRC" }, if (quality.isNotBlank()) "badge-accent" else "badge")
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
        val row = HBox(9.0, qualityBox, info, grab, browser, play).apply {
            styleClass.add("src-row")
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
            // Nothing looked up yet: fetch, and start the fastest one the moment
            // the list lands (see [loadStreams]).
            pendingPlay = true
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
                renderEpisodeGrid()
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
        const val PANEL_W = 396.0

        /** Ceiling for the banner's height. It carries the poster as well as the
         *  title, so it wants a real slice of the window. */
        const val HERO_H = 430.0

        /** The banner never shrinks below this, so the title, the metadata and
         *  the poster all fit even on a short window. */
        const val HERO_MIN = 300.0

        /** Tiles rendered per "page" before the Show-more button appears. */
        const val EPISODE_PAGE = 30

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
