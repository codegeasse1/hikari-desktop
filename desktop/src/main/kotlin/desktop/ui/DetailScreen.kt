package desktop.ui

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import desktop.fx.Fx
import desktop.player.DesktopPlayer
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.image.ImageView
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The title screen.
 *
 * Layout rule: the banner owns the top, the *left* column is the reading column
 * (synopsis + facts) and scrolls on its own, and the *right* column is the
 * fixed-width "Watch" panel that never leaves the screen. Picking an episode
 * re-fetches its sources *inside that same panel*, so choosing a source is one
 * click away instead of a scroll to the bottom of the page.
 */
class DetailScreenView(private val item: MediaItem) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var meta: MediaItem = item
    private var episodes: List<Episode> = emptyList()
    private var selectedEpisode: Episode? = null
    private var streams: List<StreamSource> = emptyList()
    private var pendingPlay = false
    private var favourite = false

    private val heroHeight = 300.0

    // ── hero ────────────────────────────────────────────────────────────────

    private val heroImage = ImageView().apply {
        isPreserveRatio = false
        isSmooth = true
        fitHeight = heroHeight
    }
    private val heroTitle = themed("", "d-title")
    private val heroMeta = themed("", "d-meta")
    private val heroChips = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val heroActions = HBox(10.0, Ui.playButton("Play") { playFirst() }).apply { alignment = Pos.CENTER_LEFT }
    private val heroBody = VBox(10.0, heroTitle, heroMeta, heroChips, heroActions).apply { alignment = Pos.BOTTOM_LEFT }
    private val favouriteButton = Ui.button("Add to library", icon = Icons.HEART_OUTLINE, ghost = true) { toggleFavourite() }
    private val hero = StackPane()

    // ── left column ─────────────────────────────────────────────────────────

    private val leftColumn = VBox(Theme.S4)
    private val synopsis = themed("", "prose").apply {
        isWrapText = true
        styleClass.add("h-dim")
    }
    private val facts = VBox(9.0)

    // ── watch panel ─────────────────────────────────────────────────────────

    private val panelSub = themed("", "watch-sub")
    private val episodeTools = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val rangeBox = ComboBox<String>().apply {
        styleClass.add("combo-box")
        prefWidth = 168.0
        minWidth = 130.0
        isFocusTraversable = false
    }
    private val episodeFilter = TextField().apply {
        styleClass.add("field")
        promptText = "Find episode…"
        HBox.setHgrow(this, Priority.ALWAYS)
        textProperty().addListener { _, _, _ -> renderEpisodeGrid() }
    }
    private val episodeTiles = TilePane(7.0, 7.0).apply {
        prefColumns = 6
        prefTileWidth = 54.0
        prefTileHeight = 34.0
        styleClass.add("ep-grid-inner")
    }
    private val episodeScroll = ScrollPane(episodeTiles).apply {
        prefHeight = 214.0
        minHeight = 120.0
        maxHeight = 214.0
        isFitToWidth = true
        styleClass.addAll("scroll-pane", "ep-grid")
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
    }
    private val nowPlaying = VBox(2.0,
        themed("", "now-playing-title"),
        themed("", "now-playing-sub"),
    ).apply {
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
    }
    private val watchPanel = VBox().apply {
        styleClass.add("watch")
        prefWidth = PANEL_W
        minWidth = PANEL_W
        maxWidth = PANEL_W
        val head = VBox(2.0, themed("Watch", "watch-title"), panelSub).apply {
            styleClass.add("watch-head")
        }
        val body = VBox(Theme.S3).apply {
            styleClass.add("watch-body")
            children.addAll(
                episodeTools,
                episodeScroll,
                nowPlaying,
                Ui.divider(),
                HBox(10.0, sourcesHeader, sourcesCount).apply { alignment = Pos.CENTER_LEFT },
                sourcesScroll,
            )
        }
        VBox.setVgrow(sourcesScroll, Priority.ALWAYS)
        VBox.setVgrow(body, Priority.ALWAYS)
        children.addAll(head, body)
    }

    val root: VBox = VBox(Theme.S4).apply {
        padding = Insets(Theme.S4, Theme.S5, Theme.S5, Theme.S5)
    }

    init {
        root.sceneProperty().addListener { _, _, scene -> if (scene == null) scope.cancel() }
        buildHero()
        val side = HBox(Theme.S5, Ui.vScroll(leftColumn, Insets(0.0, 6.0, 24.0, 0.0)), watchPanel).apply {
            VBox.setVgrow(this, Priority.ALWAYS)
        }
        HBox.setHgrow(side.children[0], Priority.ALWAYS)
        VBox.setVgrow(side, Priority.ALWAYS)
        root.children.addAll(hero, side)
        hero.isVisible = false
        hero.isManaged = false
        leftColumn.children.add(Ui.loadingRow("Loading details…"))
        panelSub.text = "Loading sources…"
        load()
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private fun load() {
        scope.launch {
            val loaded = runCatching { AppShell.app.repository.metaFor(item) }.getOrDefault(item)
            val eps = if (loaded.type == MediaType.SERIES) {
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
                loadStreams()
            }
        }
    }

    /** Some providers emit the same episode twice (raw id + rewritten url). */
    private fun dedupe(eps: List<Episode>): List<Episode> {
        val unique = LinkedHashMap<String, Episode>()
        eps.forEach { unique[it.id.ifBlank { it.name ?: "${it.number}" }] = it }
        return unique.values.toList()
    }

    private fun renderHero(m: MediaItem) {
        hero.isVisible = true
        hero.isManaged = true
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
        val url = m.backdropUrl ?: m.posterUrl
        // A real (wide) backdrop fills the banner; a portrait poster is
        // letterboxed instead of stretched.
        heroImage.isPreserveRatio = m.backdropUrl.isNullOrBlank()
        if (!url.isNullOrBlank()) {
            desktop.img.ImageLoader.loadAsync(url, onReady = { img -> if (img != null) heroImage.image = img }, w = 1600, h = 620)
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
                maxWidth = 420.0
            }
            HBox(12.0, themed(k, "tiny").apply { prefWidth = 82.0; minWidth = 82.0 }, value).apply {
                alignment = Pos.TOP_LEFT
            }
        })
        leftColumn.children.setAll(
            Ui.panel(
                Ui.sectionHeader("Synopsis"),
                synopsis,
            ),
            Ui.panel(
                Ui.sectionHeader("Details"),
                facts,
            ),
        )
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
        hero.prefHeight = heroHeight
        hero.minHeight = heroHeight
        hero.maxHeight = heroHeight
        heroImage.fitWidthProperty().bind(hero.widthProperty())

        val scrim = Region().apply {
            styleClass.add("d-scrim")
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }

        val back = Ui.iconButton(Icons.CHEVRON_LEFT, "Back", 18.0) { AppShell.back() }.apply {
            styleClass.add("round-btn")
        }
        StackPane.setAlignment(back, Pos.TOP_LEFT)
        StackPane.setMargin(back, Insets(16.0, 0.0, 0.0, 16.0))

        val topRight = VBox(8.0, favouriteButton).apply { alignment = Pos.TOP_RIGHT }
        StackPane.setAlignment(topRight, Pos.TOP_RIGHT)
        StackPane.setMargin(topRight, Insets(16.0, 16.0, 0.0, 0.0))

        heroBody.maxWidth = 720.0
        StackPane.setAlignment(heroBody, Pos.BOTTOM_LEFT)
        StackPane.setMargin(heroBody, Insets(0.0, 24.0, 22.0, 24.0))

        hero.children.addAll(heroImage, scrim, back, topRight, heroBody)
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
            episodeTools.isVisible = false
            episodeTools.isManaged = false
            episodeScroll.isVisible = false
            episodeScroll.isManaged = false
            panelSub.text = "Find a source for this title"
            return
        }
        episodeTools.isVisible = true
        episodeTools.isManaged = true
        val pageSize = 60
        rangeBox.items.clear()
        val pages = (episodes.size + pageSize - 1) / pageSize
        if (pages <= 1) {
            rangeBox.items.add("All ${episodes.size} episodes")
        } else {
            for (p in 0 until pages) {
                val from = p * pageSize + 1
                val to = minOf(from + pageSize - 1, episodes.size)
                rangeBox.items.add("$from–$to")
            }
        }
        // Start on the page that holds the selected episode, so the highlight is visible.
        val selIndex = episodes.indexOfFirst { it.id == selectedEpisode?.id }
        rangeBox.value = rangeBox.items.getOrElse(if (selIndex >= 0) selIndex / pageSize else 0) { rangeBox.items.first() }
        rangeBox.setOnAction { renderEpisodeGrid() }
        episodeTools.children.setAll(rangeBox, episodeFilter)
        renderEpisodeGrid()
        panelSub.text = "${episodes.size} episodes"
    }

    private fun pageRange(): Pair<Int, Int> {
        val pageSize = 60
        val label = rangeBox.value
        if (label == null || label.startsWith("All")) return 0 to episodes.size
        val from = label.substringBefore("–").trim().toIntOrNull()?.minus(1) ?: 0
        return from.coerceIn(0, maxOf(0, episodes.size - 1)) to minOf(from + pageSize, episodes.size)
    }

    private fun renderEpisodeGrid() {
        if (episodes.isEmpty()) return
        val needle = episodeFilter.text.trim().lowercase()
        val (from, to) = pageRange()
        val tiles = mutableListOf<Node>()
        for (i in from until to) {
            val ep = episodes.getOrNull(i) ?: continue
            val matches = needle.isEmpty() ||
                ep.number.toString().contains(needle) ||
                ep.name?.lowercase()?.contains(needle) == true
            if (!matches) continue
            val text = ep.name?.takeIf { it.isNotBlank() }?.let { name ->
                // Strip the show title so a tile reads "160" instead of 60 characters.
                val trimmed = name.replace(meta.title, "").trim().trim('-', ':', '|', '·')
                numOf(trimmed) ?: ep.number.toString()
            } ?: ep.number.toString()
            val button = Button(text).apply {
                styleClass.add("ep-num")
                prefWidth = 54.0
                prefHeight = 34.0
                isFocusTraversable = false
                if (ep.id == selectedEpisode?.id) styleClass.add("ep-num-sel")
                Tooltip.install(this, Ui.tooltip(ep.name ?: "Episode ${ep.number}"))
                setOnAction { selectEpisode(ep) }
            }
            tiles.add(button)
        }
        episodeTiles.children.setAll(tiles)
        if (tiles.isEmpty()) {
            episodeTiles.children.setAll(themed("No episode matches “$needle”.", "tiny"))
        }
    }

    private fun numOf(text: String): String? = Regex("(\\d{1,4})").find(text)?.groupValues?.get(1)

    private fun selectEpisode(ep: Episode) {
        selectedEpisode = ep
        renderEpisodeGrid()
        updateNowPlaying()
        loadStreams()
    }

    private fun updateNowPlaying() {
        val ep = selectedEpisode ?: return
        val parent = nowPlaying.children
        if (parent.size >= 2) {
            (parent[0] as Label).text = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}"
            (parent[1] as Label).text = meta.title
        }
        nowPlaying.isVisible = true
        nowPlaying.isManaged = true
    }

    // ── sources ─────────────────────────────────────────────────────────────

    private fun loadStreams() {
        sourcesBox.children.setAll(Ui.loadingRow("Fetching sources…"))
        sourcesHeader.text = "Sources"
        sourcesCount.text = ""
        scope.launch {
            val list = runCatching { AppShell.app.repository.streamsFor(meta, selectedEpisode) }
                .getOrElse { t ->
                    Fx.run {
                        sourcesBox.children.setAll(
                            themed("Stream lookup failed: ${t.message ?: t.javaClass.simpleName}", "tiny")
                                .apply { styleClass.add("h-danger") }
                        )
                    }
                    emptyList()
                }
            Fx.run {
                streams = list
                renderStreams()
                if (pendingPlay) {
                    pendingPlay = false
                    playFirst()
                }
            }
        }
    }

    private fun renderStreams() {
        if (streams.isEmpty()) {
            sourcesBox.children.setAll(
                Ui.emptyState(
                    Icons.INFO,
                    "No playable source found",
                    if (selectedEpisode == null) {
                        "This provider returned no streams for the title itself."
                    } else {
                        "No source answered for this episode. Try another episode, or reload after installing more extensions."
                    },
                )
            )
            sourcesCount.text = "0 found"
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
            prefWidth = 58.0
            minWidth = 58.0
        }
        val info = VBox(1.0,
            themed(source.name, "src-name").apply { isWrapText = true; maxWidth = 210.0 },
            themed(badges.ifBlank { source.url.take(70) }, "src-meta"),
        ).apply { maxWidth = 220.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val browser = Ui.iconButton(Icons.EXTERNAL, "Open in browser", 15.0) {
            desktop.fx.DesktopUi.open(source.url)
        }
        val play = Button().apply {
            styleClass.add("src-go")
            graphic = Icons.of(Icons.PLAY, 15.0)
            isFocusTraversable = false
            setOnAction { play(source) }
        }
        val row = HBox(11.0, qualityBox, info, browser, play).apply {
            styleClass.add("src-row")
            alignment = Pos.CENTER_LEFT
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
        val first = streams.firstOrNull()
        if (first != null) {
            play(first)
        } else {
            pendingPlay = true
            loadStreams()
        }
    }

    private fun play(source: StreamSource) {
        val ep = selectedEpisode
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
        )
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
    }
}
