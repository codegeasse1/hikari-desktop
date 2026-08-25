package desktop.ui

import com.hikari.app.data.Episode
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * The episode picker.
 *
 * Providers hand back episode names like "Swallowed Star Episode 239 English
 * Subtitles" — the series title, the word "Episode", the number and the sub/dub
 * boilerplate, repeated on every single row. Rendering those verbatim as
 * full-width buttons produced a tall column of near-identical sentences with a
 * horizontal scrollbar.
 *
 * So: [cleanTitle] strips everything the row's own number already tells you,
 * and the result decides the layout. When nothing meaningful survives (the
 * common case) episodes become a dense wrapping grid of numbered tiles; when
 * real titles exist they get wider tiles with the number as a badge. A quick
 * filter box and range chips replace the range combo box, and the grid wraps —
 * so it never scrolls sideways.
 */
class EpisodeGrid(
    private val seriesTitle: String,
    private val episodes: List<Episode>,
    private val onPick: (Episode) -> Unit,
) {

    /** Episodes per range chip. */
    private val groupSize = 50

    private val cleaned: List<String> = episodes.map { cleanTitle(seriesTitle, it) }

    /** Wide "number + title" tiles only pay for themselves when a decent share
     *  of episodes actually carry a distinct title. */
    private val titled: Boolean = run {
        val named = cleaned.count { it.isNotBlank() }
        named * 2 >= episodes.size && named > 0
    }

    private val grid = FlowPane().apply {
        hgap = 8.0
        vgap = 8.0
        padding = Insets(2.0, 0.0, 2.0, 0.0)
    }

    private val scroller = ScrollPane(grid).apply {
        isFitToWidth = true
        styleClass.add("scroll-pane")
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        maxHeight = if (titled) 430.0 else 300.0
        // Nested scrollers: once this one is at its end (or the episodes all
        // fit), let the wheel keep scrolling the page instead of dead-ending.
        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL) { e ->
            val up = parent
            val overflow = grid.boundsInLocal.height - viewportBounds.height
            val atEnd = (e.deltaY < 0 && vvalue >= 1.0) || (e.deltaY > 0 && vvalue <= 0.0)
            if (up != null && (overflow <= 1.0 || atEnd)) {
                e.consume()
                up.fireEvent(e.copyFor(e.source, up))
            }
        }
    }

    private val filterField = TextField().apply {
        styleClass.add("field")
        promptText = "Find episode…"
        prefWidth = 168.0
        textProperty().addListener { _, _, _ -> rebuild() }
    }

    private val countLabel = Label().apply { styleClass.add("episode-count") }
    private val rangeRow = FlowPane().apply {
        hgap = 6.0
        vgap = 6.0
    }

    private val tiles = HashMap<String, Button>()
    private val rangeChips = mutableListOf<Button>()
    private var rangeStart = 0
    private var rangeEnd = minOf(groupSize, episodes.size)
    private var allRanges = episodes.size <= groupSize
    private var selectedId: String? = null

    val root: VBox = VBox(10.0).apply { styleClass.add("episodes-section") }

    init {
        val heading = Label("Episodes").apply { styleClass.add("section-title") }
        val total = Label("${episodes.size}").apply { styleClass.add("episode-total") }
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val header = HBox(10.0, heading, total, spacer, countLabel, filterField).apply {
            alignment = Pos.CENTER_LEFT
        }
        buildRangeChips()
        root.children.addAll(header)
        if (rangeRow.children.size > 1) root.children.add(rangeRow)
        root.children.add(scroller)
        rebuild()
    }

    /**
     * Highlights an episode chosen from outside (e.g. the player dock's "next
     * episode"), scrolling its range into view so the selection is never
     * hidden behind a range chip the user isn't on.
     */
    fun select(ep: Episode) {
        selectedId = ep.id
        val idx = episodes.indexOfFirst { it.id == ep.id }
        if (idx >= 0 && !allRanges && (idx < rangeStart || idx >= rangeEnd) && filterField.text.isNullOrBlank()) {
            val g = idx / groupSize
            rangeStart = g * groupSize
            rangeEnd = minOf(rangeStart + groupSize, episodes.size)
            rangeChips.forEach { it.styleClass.remove("chip-selected") }
            rangeChips.getOrNull(g)?.styleClass?.add("chip-selected")
            rebuild()
        } else {
            tiles.values.forEach { it.styleClass.remove("episode-tile-selected") }
            tiles[ep.id]?.styleClass?.add("episode-tile-selected")
        }
        tiles[ep.id]?.let { scrollIntoView(it) }
    }

    /** Centres a tile in the scroller — the auto-played next episode is often
     *  just below the fold. Deferred, because a fresh [rebuild] hasn't laid out
     *  yet when this runs. */
    private fun scrollIntoView(tile: Button) {
        javafx.application.Platform.runLater {
            // A tile added moments ago has no bounds until the pane is laid
            // out; force that pass rather than guessing.
            scroller.applyCss()
            scroller.layout()
            val content = grid.boundsInLocal.height
            val viewport = scroller.viewportBounds.height
            if (content <= viewport || viewport <= 0.0) return@runLater
            val y = tile.boundsInParent.minY - (viewport - tile.boundsInParent.height) / 2.0
            scroller.vvalue = (y / (content - viewport)).coerceIn(0.0, 1.0)
        }
    }

    private fun buildRangeChips() {
        rangeRow.children.clear()
        rangeChips.clear()
        if (episodes.size <= groupSize) return
        fun chip(text: String, apply: () -> Unit): Button = Button(text).apply {
            styleClass.addAll("chip", "chip-plain")
            setOnAction {
                apply()
                rangeChips.forEach { it.styleClass.remove("chip-selected") }
                styleClass.add("chip-selected")
                rebuild()
            }
        }
        val groups = (episodes.size + groupSize - 1) / groupSize
        for (g in 0 until groups) {
            val start = g * groupSize
            val end = minOf(start + groupSize, episodes.size)
            val label = "${episodes[start].number}–${episodes[end - 1].number}"
            val c = chip(label) {
                allRanges = false
                rangeStart = start
                rangeEnd = end
            }
            rangeChips.add(c)
            rangeRow.children.add(c)
        }
        val allChip = chip("All") { allRanges = true }
        rangeChips.add(allChip)
        rangeRow.children.add(allChip)
        rangeChips.first().styleClass.add("chip-selected")
    }

    private fun rebuild() {
        grid.children.clear()
        tiles.clear()

        val query = filterField.text.orEmpty().trim().lowercase()
        val windowed = if (query.isNotEmpty() || allRanges) {
            // Filtering searches EVERY episode, not just the visible range —
            // typing "240" must find it whichever range chip is active.
            episodes.indices.toList()
        } else {
            (rangeStart until rangeEnd).toList()
        }
        val visible = windowed.filter { i ->
            if (query.isEmpty()) true
            else episodes[i].number.toString().contains(query) ||
                cleaned[i].lowercase().contains(query) ||
                episodes[i].name.orEmpty().lowercase().contains(query)
        }

        countLabel.text = when {
            query.isNotEmpty() -> "${visible.size} match${if (visible.size == 1) "" else "es"}"
            allRanges -> "showing all"
            else -> ""
        }

        if (visible.isEmpty()) {
            grid.children.add(Theme.label("No episode matches “${filterField.text}”.", dim = true))
            return
        }

        visible.forEach { i -> grid.children.add(tileFor(i)) }
    }

    private fun tileFor(index: Int): Button {
        val ep = episodes[index]
        val name = cleaned[index]
        val tile = Button().apply {
            styleClass.add("episode-tile")
            if (ep.id == selectedId) styleClass.add("episode-tile-selected")
            isMnemonicParsing = false
            setOnAction {
                selectedId = ep.id
                tiles.values.forEach { it.styleClass.remove("episode-tile-selected") }
                styleClass.add("episode-tile-selected")
                onPick(ep)
            }
        }
        val full = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}"
        tile.tooltip = Tooltip(full).apply { showDelay = javafx.util.Duration.millis(350.0) }

        if (titled) {
            val num = Label(ep.number.toString()).apply { styleClass.add("episode-tile-num") }
            val text = Label(name.ifBlank { "Episode ${ep.number}" }).apply {
                styleClass.add("episode-tile-name")
                isWrapText = false
                maxWidth = 168.0
            }
            tile.graphic = HBox(9.0, num, text).apply { alignment = Pos.CENTER_LEFT }
            tile.styleClass.add("episode-tile-wide")
            tile.prefWidth = 226.0
            tile.minWidth = 226.0
        } else {
            tile.text = ep.number.toString()
            tile.prefWidth = 62.0
            tile.minWidth = 62.0
        }
        tiles[ep.id] = tile
        return tile
    }

    companion object {

        /** Pure SEO/format boilerplate providers staple onto every title.
         *  Deliberately conservative — anything that could be a real word in a
         *  real episode title (full, free, part…) is left alone. */
        private val NOISE = Regex(
            """\b(english\s+sub(bed|titles?)?|english\s+dub(bed)?|english|subbed|subtitles?|dubbed|watch\s+online|watch|online|1080p|720p|480p|hd)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Strips everything from a provider's episode name that the tile's own
         * number already conveys: the series title, "Episode <n>" / "Ep <n>" /
         * "S01E05", and sub/dub/quality boilerplate. Returns "" when nothing
         * distinctive is left, which is the signal to render a bare number.
         */
        fun cleanTitle(seriesTitle: String, ep: Episode): String {
            var s = ep.name?.trim().orEmpty()
            if (s.isEmpty()) return ""

            // Drop the series title wherever it appears (providers put it in
            // front on nearly every row).
            val series = seriesTitle.trim()
            if (series.length >= 3) {
                s = s.replace(series, " ", ignoreCase = true)
                // …and its punctuation-insensitive form ("Re:Zero" vs "Re Zero",
                // "Swallowed-Star" vs "Swallowed Star").
                val words = series.split(Regex("""[\s:._\-–—|]+""")).filter { it.isNotBlank() }
                if (words.size > 1) {
                    val loose = Regex(
                        words.joinToString("""[\s:._\-–—|]+""") { Regex.escape(it) },
                        RegexOption.IGNORE_CASE,
                    )
                    s = loose.replace(s, " ")
                }
            }

            // "Episode 239", "Ep. 239", "E239", "S1E239", "#239", or a bare
            // leading number — all redundant next to the number badge.
            s = s.replace(Regex("""\bS\d{1,3}\s*[\s._-]?\s*E\d{1,4}\b""", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("""\b(episode|ep|epi|chapter|ch)\b\.?\s*#?\d{1,4}\b""", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("""^\s*[#\-–—]*\s*0*${ep.number}\b"""), " ")
            s = s.replace(Regex("""\bE\d{1,4}\b"""), " ")
            s = NOISE.replace(s, " ")

            // Tidy the leftovers: separators, empty brackets, stray punctuation.
            s = s.replace(Regex("""\(\s*\)|\[\s*]|\{\s*}"""), " ")
                .replace(Regex("""[\s._\-–—:|·,]+"""), " ")
                .trim()
                .trim('-', '–', '—', ':', '|', '·', ',', '.')
                .trim()

            // A pure number left over is the episode number again.
            if (s.isEmpty() || s.equals("episode", ignoreCase = true) || s.toIntOrNull() != null) return ""
            return s
        }
    }
}
