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
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DetailScreenView(private val item: MediaItem) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val root: VBox = VBox(0.0).apply {
        padding = Insets(16.0, 24.0, 16.0, 24.0)
    }

    private val loading = ProgressBar(-1.0)
    private val errorLabel = Theme.label("", dim = true)
    private val body = VBox(16.0)
    private val streamsBox = VBox(10.0)

    private var episodes: List<Episode>? = null
    private var selectedEpisode: Episode? = null

    init {
        root.sceneProperty().addListener { _, _, newScene ->
            if (newScene == null) scope.cancel()
        }
        root.children.addAll(header(), loading, errorLabel, ScrollPane(body).apply {
            VBox.setVgrow(this, Priority.ALWAYS)
            isFitToWidth = true
            styleClass.add("scroll-pane")
        })
        load()
    }

    private fun header(): HBox {
        val back = Button("←").apply {
            styleClass.add("btn")
            setOnAction { AppShell.goHome() }
        }
        return HBox(12.0, back, Theme.label(item.title, size = 20.0, bold = true)).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun load() {
        loading.isVisible = true
        errorLabel.text = ""
        scope.launch {
            val meta = runCatching { AppShell.app.repository.metaFor(item) }.getOrDefault(item)
            val eps = if (meta.type == MediaType.SERIES) {
                runCatching { AppShell.app.repository.episodesFor(meta) }.getOrNull()
            } else null
            Fx.run {
                loading.isVisible = false
                renderMeta(meta)
                if (eps != null && eps.isNotEmpty()) {
                    episodes = eps
                    renderEpisodes(eps)
                    if (eps.size == 1) {
                        selectedEpisode = eps.first()
                        loadStreams(meta)
                    }
                } else {
                    loadStreams(meta)
                }
            }
        }
    }

    private fun renderMeta(meta: MediaItem) {
        body.children.clear()
        val headerImage = bannerFor(meta)
        val titleRow = VBox(4.0).apply {
            children.addAll(
                Theme.label(meta.title, size = 24.0, bold = true),
                Theme.label(
                    listOfNotNull(
                        meta.year?.toString(),
                        meta.type.name.lowercase().replaceFirstChar { it.uppercase() },
                        meta.genres.joinToString(", ").ifBlank { null },
                    ).joinToString(" · "),
                    size = 12.5,
                    dim = true,
                ),
            )
        }
        val overview = if (!meta.overview.isNullOrBlank()) {
            Theme.label(meta.overview, size = 14.0).apply { isWrapText = true }
        } else Label()
        streamsBox.children.clear()
        streamsBox.children.add(Theme.label("Loading sources…", dim = true))
        val streamsSection = VBox(8.0, Theme.label("Sources", size = 17.0, bold = true), streamsBox)
        if (headerImage != null) body.children.add(headerImage)
        body.children.addAll(titleRow, overview, streamsSection)
    }

    /** Wide backdrop banner at the top of the detail view (backdrop first,
     *  poster as fallback), styled to fill the content width. */
    private fun bannerFor(meta: MediaItem): Node? {
        val url = meta.backdropUrl ?: meta.posterUrl
        if (url.isNullOrBlank()) return null
        val img = javafx.scene.image.ImageView().apply {
            fitHeight = 230.0
            isPreserveRatio = false
            isSmooth = true
            styleClass.add("detail-banner")
            val w = root.widthProperty()
            fitWidthProperty().bind(w)
        }
        desktop.img.ImageLoader.loadAsync(url, onReady = { fx -> img.image = fx }, w = 1600, h = 300)
        return VBox(img)
    }

    private fun renderEpisodes(eps: List<Episode>) {
        val title = Theme.label("Episodes", size = 17.0, bold = true)
        val section = VBox(10.0, title)
        val group = 30
        val selector = javafx.scene.control.ComboBox<String>()
        selector.maxWidth = 260.0
        selector.prefWidth = 200.0
        selector.items.add("All episodes")
        val groups = (eps.size + group - 1) / group
        for (g in 0 until groups) {
            val start = g * group
            val end = minOf(start + group, eps.size)
            selector.items.add("${start + 1}-${end}")
        }
        val defaultToRange = eps.size > group
        selector.value = if (defaultToRange) "1-${minOf(group, eps.size)}" else "All episodes"
        section.children.addAll(
            selector,
            buildChipScroller(eps, 0, if (defaultToRange) group else eps.size),
        )
        selector.setOnAction {
            val sel = selector.value ?: return@setOnAction
            val replaceIdx = section.children.size - 1
            section.children[replaceIdx] = if (sel == "All episodes") {
                buildChipScroller(eps, 0, eps.size)
            } else {
                val start = sel.substringBefore("-").trim().toIntOrNull()?.minus(1) ?: return@setOnAction
                buildChipScroller(eps, start, minOf(start + group, eps.size))
            }
        }
        body.children.add(1, section)
    }

    private fun buildChipScroller(eps: List<Episode>, start: Int, end: Int): ScrollPane {
        val box = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("list-row")
        }
        for (i in start until end) {
            val ep = eps[i]
            val chip = Button(ep.name ?: "Ep ${ep.number}").apply {
                styleClass.add("episode-chip")
                if (selectedEpisode?.id == ep.id) styleClass.add("episode-chip-selected")
                userData = ep
                setOnAction {
                    selectedEpisode = ep
                    box.children.forEach { it.styleClass.remove("episode-chip-selected") }
                    styleClass.add("episode-chip-selected")
                    loadStreams(item)
                }
            }
            box.children.add(chip)
        }
        return ScrollPane(box).apply {
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            styleClass.add("scroll-pane")
        }
    }

    private fun loadStreams(meta: MediaItem) {
        Fx.requireFx()
        streamsBox.children.clear()
        streamsBox.children.add(Theme.label("Loading sources…", dim = true))
        scope.launch {
            val streams = runCatching {
                AppShell.app.repository.streamsFor(meta, selectedEpisode)
            }.getOrElse { t ->
                Fx.run { streamsBox.children.setAll(Theme.label("Stream lookup failed: ${t.message}", dim = true)) }
                emptyList()
            }
            Fx.run {
                renderStreams(meta, streams)
            }
        }
    }

    private fun renderStreams(meta: MediaItem, streams: List<StreamSource>) {
        streamsBox.children.clear()
        if (streams.isEmpty()) {
            streamsBox.children.add(Theme.label("No playable sources found.", dim = true))
            return
        }
        streams.forEach { s ->
            val name = Label(s.name).apply { maxWidth = 420.0; isWrapText = true }
            val play = Button("Play").apply {
                styleClass.add("btn")
                setOnAction {
                    HikariApp.instance.store.addHistory(
                        HistoryEntry(
                            providerId = meta.providerId,
                            mediaId = meta.id,
                            type = meta.type,
                            title = meta.title,
                            posterUrl = meta.posterUrl,
                            episodeId = selectedEpisode?.id ?: "",
                            episodeName = selectedEpisode?.name ?: "",
                            watchedAt = System.currentTimeMillis(),
                        )
                    )
                    // refresh: refetch the provider's stream list and relaunch
                    // with a fresh URL — signed live links (chaturbate) expire
                    // or get consumed within seconds, so a 403 is retried with a
                    // brand-new link instead of an error dialog.
                    DesktopPlayer.play(
                        "${meta.title} — ${s.name}",
                        s,
                        refresh = {
                            runCatching {
                                kotlinx.coroutines.runBlocking { AppShell.app.repository.streamsFor(meta, selectedEpisode) }
                            }.getOrNull()?.firstOrNull()
                        },
                    )
                }
            }
            val open = Button("Browser").apply {
                styleClass.add("btn")
                setOnAction { desktop.fx.DesktopUi.open(s.url) }
            }
            val badges = listOfNotNull(
                if (s.isM3u8) "HLS" else null,
                if (s.isMpd) "DASH" else null,
                if (s.isTorrent) "TORRENT" else null,
                if (s.externalUrl) "EXTERNAL" else null,
                if (s.ytId != null) "YOUTUBE" else null,
            ).joinToString(" · ").let { if (it.isBlank()) null else it }
            val row = HBox(12.0, name, play, open).apply {
                alignment = Pos.CENTER_LEFT
                styleClass.add("list-row")
            }
            val full = VBox(2.0, row)
            if (badges != null) full.children.add(Theme.label(badges, size = 11.0, dim = true))
            streamsBox.children.add(full)
        }
    }
}
