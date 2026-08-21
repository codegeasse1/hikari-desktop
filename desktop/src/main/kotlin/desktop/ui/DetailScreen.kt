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
    private val episodesBox = HBox(8.0).apply { }
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
        body.children.addAll(titleRow, overview, streamsSection)
    }

    private fun renderEpisodes(eps: List<Episode>) {
        val title = Theme.label("Episodes", size = 17.0, bold = true)
        episodesBox.children.clear()
        episodesBox.alignment = Pos.CENTER_LEFT
        episodesBox.styleClass.add("list-row")
        val max = 200
        val shown = eps.take(max)
        shown.forEach { ep ->
            val chip = Button(ep.name ?: "Ep ${ep.number}").apply {
                styleClass.add("episode-chip")
                userData = ep
                setOnAction {
                    selectedEpisode = ep
                    episodesBox.children.forEach { it.styleClass.remove("episode-chip-selected") }
                    styleClass.add("episode-chip-selected")
                    loadStreams(item)
                }
            }
            episodesBox.children.add(chip)
        }
        if (eps.size > max) {
            episodesBox.children.add(Theme.label("+${eps.size - max} more", size = 12.0, dim = true))
        }
        val wrap = ScrollPane(episodesBox).apply {
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            styleClass.add("scroll-pane")
        }
        body.children.add(1, title)
        body.children.add(2, wrap)
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
                    DesktopPlayer.play("${meta.title} — ${s.name}", s)
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
