package desktop.ui

import com.hikari.app.data.ProviderConfig
import com.hikari.app.net.Http
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Screen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The catalog's provider picker — "Select Provider" / "Manage Home Screen Feed".
 *
 * Single mode (the toggle off): a card per provider — badge, name, catalog
 * count — and clicking one loads its catalog on Home straight away (plus
 * "Save & Close" to dismiss). "All providers" is the first card and clears
 * the filter. Multi mode (the toggle on): an ordered Active Providers Feed
 * (reorder with ↑/↓, drop with ×) plus the Available Plugins list (add with
 * +), and Home mixes rows from exactly those providers in that order.
 *
 * The choice is persisted (single id / ordered id list / on-off), so it
 * survives a restart — the old sheet forgot it every launch.
 *
 * Everything here is FX-thread only (it is all JavaFX nodes); [setProviders]
 * is called from the screen's own load, inside its `Fx.run` block.
 */
class ProviderPicker(private val onPick: () -> Unit) {

    companion object {
        /** The "no provider filter" label — also what the button shows before a
         *  pick, and the vocabulary the home status lines already use. */
        const val ALL = "All providers"

        /** Content-kind chips above the grid. They are finders, not partitions:
         *  most providers serve movies AND series, so those two match every
         *  general provider while Anime/Docs/Live match by name. */
        private val KINDS = listOf("All", "Movies", "Series", "Anime", "Docs", "Live")
    }

    private var providers: List<ProviderConfig> = emptyList()
    private var kindFilter: String? = null
    private var query: String = ""

    /** The chosen provider, or null for [ALL]. Read on the FX thread. */
    var selection: ProviderConfig? = null
        private set

    /** Multi-feed state: on/off plus the ORDERED active ids. */
    var multiOn: Boolean = false
        private set
    private var activeIds: ArrayList<String> = ArrayList()

    /** What the caller shows and compares: the chosen provider's name, [ALL],
     *  or "Mix of N" for a multi feed. */
    fun selectedName(): String = when {
        multiOn && activeIds.isNotEmpty() -> "Mix of ${activeIds.size}"
        else -> selection?.name ?: ALL
    }

    /** True once the user (or the persisted state) has picked something. */
    fun hasChoice(): Boolean = selection != null || (multiOn && activeIds.isNotEmpty())

    /** The ordered active ids of the multi feed. */
    fun multiIds(): List<String> = activeIds.toList()

    /** The button that opens the sheet. Styled like the combo box it replaced,
     *  so the top bar looks the same. */
    val button: Button = Button(ALL).apply {
        styleClass.addAll("combo-box", "provider-picker")
        alignment = Pos.CENTER_LEFT
        graphic = Icons.of(Icons.CHEVRON_DOWN, 13.0)
        contentDisplay = ContentDisplay.RIGHT
        graphicTextGap = 10.0
        isFocusTraversable = false
        prefWidth = 300.0
        // The chosen provider's name is the button's whole point, so the button
        // is never shrunk below what that name needs: the toolbar it lives in
        // scrolls instead (see HomeScreen), which is what keeps "Anime · Animeko"
        // from being drawn as "Anim…".
        minWidth = javafx.scene.layout.Region.USE_PREF_SIZE
        maxWidth = 560.0
        setOnAction { toggle() }
    }

    private val titleLabel = Label("Select Provider").apply { styleClass.add("x-pd-title") }
    private val subLabel = Label("Choose a provider to display on your Home Screen.").apply {
        styleClass.add("x-pd-sub")
    }
    private val multiSwitch = Button().apply {
        styleClass.add("x-switch")
        isFocusTraversable = false
        graphic = Region().apply { styleClass.add("x-switch-knob") }
        setOnAction { setMulti(!multiOn, fire = true) }
    }
    private val search = TextField().apply {
        styleClass.addAll("field", "provider-search")
        promptText = "Find a provider…"
        textProperty().addListener { _, _, v ->
            query = v.trim().lowercase()
            rebuildMain()
        }
    }
    private val chips = HBox(6.0).apply { styleClass.addAll("segmented", "provider-chips") }
    private val chipsScroll = Ui.chipRow(chips)
    private val mainBox = VBox(10.0).apply { minWidth = 0.0 }
    private val mainScroll = ScrollPane(mainBox).apply {
        styleClass.addAll("scroll-pane", "x-pd-scroll")
        isFitToWidth = true
        prefHeight = 400.0
        minHeight = 200.0
        maxHeight = 480.0
        minWidth = 0.0
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
    }
    private val saveClose = Ui.button("Save & Close", primary = true) { pop.hide() }
    private val foot = HBox(Ui.spacer(), saveClose).apply {
        alignment = Pos.CENTER_RIGHT
        minWidth = 0.0
    }
    private val body = VBox(12.0).apply {
        styleClass.addAll("picker-pop", "x-pd")
        padding = Insets(22.0, 24.0, 20.0, 24.0)
        minWidth = 0.0
    }
    private val pop = Popup().apply {
        isAutoHide = true
        isHideOnEscape = true
    }

    /** provider id → its "N catalogs" subtitle label, for the background fill. */
    private val countLabels = HashMap<String, Label>()
    /** provider id → catalog count, kept for the session. */
    private val countCache = HashMap<String, Int>()
    private var countJob: Job? = null

    init {
        val head = HBox(12.0,
            VBox(3.0, titleLabel, subLabel).apply { minWidth = 0.0 },
            Ui.spacer(),
            VBox(4.0,
                Label("Enable Multi-Provider Feed").apply { styleClass.add("x-pd-multi-t") },
                HBox(multiSwitch).apply { alignment = Pos.CENTER_RIGHT },
            ).apply { alignment = Pos.CENTER_RIGHT },
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        HBox.setHgrow(head.children[1] as Region, Priority.ALWAYS)
        body.children.addAll(head, search, chipsScroll, mainScroll, foot)
        pop.content.add(body)
    }

    /**
     * The providers the catalog can be built from. Called on every load (the
     * list changes as extensions are installed), so it is also where a selection
     * that no longer exists is dropped — a picker showing a provider that was
     * just uninstalled would leave the screen loading from nothing.
     */
    fun setProviders(list: List<ProviderConfig>) {
        val sorted = list.distinctBy { it.name }.sortedBy { it.name.lowercase() }
        val changed = sorted.size != providers.size ||
            sorted.zip(providers).any { (a, b) -> a.id != b.id || a.name != b.name || a.type != b.type }
        providers = sorted
        val cur = selection
        if (cur != null && sorted.none { it.id == cur.id }) {
            selection = null
            refreshButton()
        }
        val kept = activeIds.filter { id -> sorted.any { it.id == id } }
        if (kept.size != activeIds.size) {
            activeIds = ArrayList(kept)
            if (activeIds.isEmpty()) multiOn = false
        }
        if (changed) {
            rebuildChips()
            if (pop.isShowing) rebuildMain()
        }
    }

    /** Opens the sheet (used by Home's "Manage" banner action). */
    fun open() {
        if (!pop.isShowing) toggle()
    }

    private fun toggle() {
        if (pop.isShowing) {
            pop.hide()
            return
        }
        search.text = ""
        query = ""
        kindFilter = null
        rebuildChips()
        rebuildMain()
        // The dialog is wide: keep it on screen even when the picker button
        // sits at the window's right edge.
        val vb = runCatching { Screen.getPrimary().visualBounds }.getOrNull()
        val wantW = 880.0
        val w = if (vb != null) wantW.coerceAtMost(vb.width - 40.0) else wantW
        body.prefWidth = w
        body.maxWidth = w
        val at = button.localToScreen(button.boundsInLocal)
        if (at != null) {
            val x = if (vb != null) (at.minX).coerceIn(vb.minX + 8.0, (vb.maxX - w - 8.0).coerceAtLeast(vb.minX + 8.0)) else at.minX
            pop.show(button, x, at.maxY + 4.0)
        } else {
            // No screen coordinates (button not laid out yet): anchor to its
            // window instead — Popup.show takes a Window, never a Button.
            button.scene?.window?.let { pop.show(it) }
        }
        // A Popup is a window of its own: its scene does NOT inherit the app's
        // stylesheets, and this sheet defines the colours the picker is drawn
        // with as looked-up colours. Copying the owner's list at show time is
        // both correct and theme-aware (an accent change re-applies on the next
        // open).
        runCatching {
            val owner = AppShell.stage?.scene ?: return@runCatching
            pop.scene?.stylesheets?.setAll(owner.stylesheets)
        }
        // The field is what the user reaches for first; a picker that opens
        // without keyboard focus makes a hundred-item list feel like the combo
        // box it replaced.
        javafx.application.Platform.runLater { search.requestFocus() }
        ensureCounts()
    }

    // ── filtering ─────────────────────────────────────────────────────────

    private fun kindMatches(p: ProviderConfig): Boolean {
        val kind = kindFilter ?: return true
        val n = p.name.lowercase()
        val isAnime = n.contains("anime") || n.contains("donghua") || n.contains("hanime") ||
            n.startsWith("ani") || n.contains("otaku")
        val isDocs = n.contains("docu") || n.contains("documentary")
        val isLive = n.contains("live") || n.contains("iptv") || n.contains("sport") ||
            n.contains("cricket") || n.contains("news")
        return when (kind) {
            "Anime" -> isAnime
            "Docs" -> isDocs
            "Live" -> isLive
            "Movies" -> n.contains("movie") || n.contains("film") || n.contains("cinema") ||
                n.contains("yts") || n.contains("hub") || n.contains("mirror") ||
                n.contains("flix") || n.contains("multiplex") || n.contains("hollywood") ||
                n.contains("bollywood") || n.contains("vidsrc") || n.contains("land") ||
                (!isAnime && !isDocs && !isLive)
            "Series" -> n.contains("series") || n.contains("show") || n.contains("drama") ||
                n.contains("episode") || n.contains("season") || n.contains("web") ||
                n.contains("tv") || (!isAnime && !isDocs && !isLive)
            else -> true
        }
    }

    private fun shownProviders(): List<ProviderConfig> =
        providers
            .filter { kindMatches(it) }
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }

    // ── building ──────────────────────────────────────────────────────────

    private fun rebuildChips() {
        val labels = KINDS
        val index = labels.indexOf(kindFilter).let { if (it < 0) 0 else it }
        if (index == 0) kindFilter = null
        val seg = Ui.segmented(labels, index) { picked ->
            kindFilter = if (picked <= 0) null else labels.getOrNull(picked)
            rebuildMain()
        }
        Ui.keepChipLabels(seg)
        chips.children.setAll(ArrayList<javafx.scene.Node>(seg.children))
        chipsScroll.hvalue = 0.0
    }

    private fun rebuildMain() {
        titleLabel.text = if (multiOn) "Manage Home Screen Feed" else "Select Provider"
        subLabel.text = if (multiOn) "Mix, reorder, and customize multiple providers."
        else "Choose a provider to display on your Home Screen."
        refreshSwitch()
        countLabels.clear()
        if (multiOn) buildMulti() else buildGrid()
    }

    private fun badgeFor(name: String, icon: String? = null): StackPane {
        val initial = Label(name.trim().firstOrNull()?.uppercase() ?: "?").apply {
            styleClass.add("x-pd-initial")
        }
        val node: javafx.scene.Node = if (icon != null) Icons.of(icon, 20.0) else initial
        return StackPane(node).apply {
            styleClass.add("x-pd-badge")
            alignment = Pos.CENTER
            minWidth = 44.0; prefWidth = 44.0; maxWidth = 44.0
            minHeight = 44.0; prefHeight = 44.0; maxHeight = 44.0
        }
    }

    /** A provider's card badge: its extension's own icon when it names one
     *  (see [ExtensionsScreenView.providerIconFor]), the initial tile while
     *  it loads or when none exists. */
    private fun badgeFor(p: ProviderConfig): StackPane =
        Ui.provBadge(p.name, runCatching { AppShell.extensionsView.providerIconFor(p) }.getOrNull(), 44.0)

    private fun subLabelFor(p: ProviderConfig): Label {
        val cached = countCache[p.id]
        return Label(countText(p, cached)).apply {
            styleClass.add("x-pd-sub2")
            // The origin ("Hikari · owner/repo") is the whole point of the
            // sub-line — it wraps onto two lines instead of drawing as "…".
            isWrapText = true
            minWidth = 0.0
            countLabels[p.id] = this
        }
    }

    /**
     * Where a provider comes from, as read on its card: engine + short
     * source ("Hikari · owner/repo"). A card that names only the provider
     * cannot tell two same-named providers from different repos apart — the
     * origin is what does.
     */
    private fun originText(p: ProviderConfig): String {
        val engine = Ui.providerEngineLabel(p.type)
        val src = sourceText(p)
        return if (src.isNullOrBlank()) engine else "$engine · $src"
    }

    /** The short source behind a provider: its install URL's owner/repo for
     *  GitHub addresses (see Http.repoDisplayName), the host otherwise. Null
     *  when the provider records no URL at all (built-ins). */
    private fun sourceText(p: ProviderConfig): String? {
        val raw = p.extra?.substringBefore('|')?.trim().orEmpty()
        val cand = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            p.url.startsWith("http://") || p.url.startsWith("https://") -> p.url
            else -> return null
        }
        return Http.repoDisplayName(cand).takeIf { it.isNotBlank() }
    }

    /** The card's sub-line: origin plus the catalog count once it has been
     *  counted in the background (see [ensureCounts]). */
    private fun countText(p: ProviderConfig, n: Int?): String {
        val base = originText(p)
        if (n == null) return base
        val tail = "$n catalog${if (n == 1) "" else "s"}"
        return "$base · $tail"
    }

    private fun providerCard(p: ProviderConfig): HBox {
        val name = Label(p.name).apply {
            styleClass.add("x-pd-name")
            isWrapText = true
            minWidth = 0.0
        }
        val texts = VBox(3.0, name, subLabelFor(p)).apply { minWidth = 0.0 }
        HBox.setHgrow(texts, Priority.ALWAYS)
        val tune = Icons.of(Icons.SLIDERS, 17.0).apply { opacity = 0.75 }
        return HBox(12.0, badgeFor(p), texts, tune).apply {
            styleClass.add("x-pd-card")
            if (selection?.id == p.id) styleClass.add("x-pd-card-sel")
            alignment = Pos.CENTER_LEFT
            padding = Insets(12.0, 14.0, 12.0, 12.0)
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            prefWidth = 264.0
            cursor = javafx.scene.Cursor.HAND
            setOnMouseClicked { pick(p) }
        }
    }

    private fun buildGrid() {
        val grid = TilePane(10.0, 10.0).apply {
            styleClass.add("x-pd-grid")
            prefColumns = 3
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }
        // "All providers" first: every enabled provider, no filter.
        val allName = Label(ALL).apply { styleClass.add("x-pd-name"); isWrapText = true; minWidth = 0.0 }
        val allSub = Label("every enabled provider").apply { styleClass.add("x-pd-sub2"); isWrapText = true; minWidth = 0.0 }
        val allTexts = VBox(3.0, allName, allSub).apply { minWidth = 0.0 }
        HBox.setHgrow(allTexts, Priority.ALWAYS)
        val allCard = HBox(12.0, badgeFor("All", Icons.GRID), allTexts, Icons.of(Icons.SLIDERS, 17.0).apply { opacity = 0.75 }).apply {
            styleClass.add("x-pd-card")
            if (selection == null) styleClass.add("x-pd-card-sel")
            alignment = Pos.CENTER_LEFT
            padding = Insets(12.0, 14.0, 12.0, 12.0)
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            prefWidth = 264.0
            cursor = javafx.scene.Cursor.HAND
            setOnMouseClicked { pick(null) }
        }
        grid.children.add(allCard)
        val shown = shownProviders()
        if (shown.isEmpty()) {
            grid.children.add(
                Label("No provider matches.").apply { styleClass.add("x-pd-empty") }
            )
        } else {
            shown.forEach { grid.children.add(providerCard(it)) }
        }
        mainBox.children.setAll(grid)
    }

    private fun buildMulti() {
        val byId = providers.associateBy { it.id }
        val active = activeIds.mapNotNull { byId[it] }
        val activeSet = activeIds.toSet()
        val available = shownProviders().filter { it.id !in activeSet }

        val leftList = VBox(8.0).apply { minWidth = 0.0 }
        if (active.isEmpty()) {
            leftList.children.add(
                Label("Nothing here yet — add providers from the right.").apply {
                    styleClass.add("x-pd-empty")
                    isWrapText = true
                }
            )
        } else {
            active.forEachIndexed { index, p ->
                leftList.children.add(activeRow(index, active.size, p))
            }
        }
        val left = VBox(8.0,
            Label("Active Providers Feed").apply { styleClass.add("x-pd-col-h") },
            ScrollPane(leftList).apply {
                styleClass.addAll("scroll-pane", "x-pd-col-scroll")
                isFitToWidth = true
                prefHeight = 300.0
                minHeight = 160.0
                maxHeight = 340.0
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            },
        ).apply { minWidth = 0.0; maxWidth = Double.MAX_VALUE }
        HBox.setHgrow(left, Priority.ALWAYS)

        val rightList = VBox(8.0).apply { minWidth = 0.0 }
        if (available.isEmpty()) {
            rightList.children.add(
                Label("Every matching provider is already in the feed.").apply {
                    styleClass.add("x-pd-empty")
                    isWrapText = true
                }
            )
        } else {
            available.forEach { p -> rightList.children.add(availableRow(p)) }
        }
        val right = VBox(8.0,
            Label("Available Plugins").apply { styleClass.add("x-pd-col-h") },
            ScrollPane(rightList).apply {
                styleClass.addAll("scroll-pane", "x-pd-col-scroll")
                isFitToWidth = true
                prefHeight = 300.0
                minHeight = 160.0
                maxHeight = 340.0
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            },
        ).apply { minWidth = 0.0; maxWidth = Double.MAX_VALUE }
        HBox.setHgrow(right, Priority.ALWAYS)

        mainBox.children.setAll(
            HBox(14.0, left, right).apply { minWidth = 0.0 }
        )
    }

    private fun activeRow(index: Int, total: Int, p: ProviderConfig): HBox {
        val num = Label("${index + 1}.").apply { styleClass.add("x-pd-num") }
        val name = Label(p.name).apply { styleClass.add("x-pd-name"); isWrapText = true; minWidth = 0.0 }
        val sub = Label("Catalogs: all active").apply { styleClass.add("x-pd-sub2"); isWrapText = true; minWidth = 0.0 }
        val texts = VBox(3.0, name, sub).apply { minWidth = 0.0 }
        HBox.setHgrow(texts, Priority.ALWAYS)
        val up = Ui.iconButton(Icons.ARROW_UP, "Move up", 14.0) {
            moveActive(p.id, -1)
        }.apply { isDisable = index == 0 }
        val down = Ui.iconButton(Icons.ARROW_DOWN, "Move down", 14.0) {
            moveActive(p.id, 1)
        }.apply { isDisable = index == total - 1 }
        val remove = Button().apply {
            styleClass.add("x-pd-x")
            graphic = Icons.of(Icons.CLOSE, 14.0)
            isFocusTraversable = false
            setOnAction { dropActive(p.id) }
        }
        return HBox(8.0, num, badgeFor(p), texts, up, down, remove).apply {
            styleClass.add("x-pd-active")
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 10.0, 10.0, 10.0)
            minWidth = 0.0
        }
    }

    private fun availableRow(p: ProviderConfig): HBox {
        val name = Label(p.name).apply { styleClass.add("x-pd-name"); isWrapText = true; minWidth = 0.0 }
        val sub = subLabelFor(p)
        // Installed-but-disabled providers are offered too (with a mark):
        // adding one enables it, so the feed can mix in anything installed.
        if (!p.enabled) sub.text = "${sub.text} · disabled"
        val texts = VBox(3.0, name, sub).apply { minWidth = 0.0 }
        HBox.setHgrow(texts, Priority.ALWAYS)
        val add = Button().apply {
            styleClass.add("x-pd-plus")
            graphic = Icons.of(Icons.PLUS, 15.0)
            isFocusTraversable = false
            setOnAction { addActive(p.id) }
        }
        return HBox(10.0, badgeFor(p), texts, add).apply {
            styleClass.add("x-pd-avail")
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 12.0, 10.0, 10.0)
            minWidth = 0.0
        }
    }

    private fun refreshSwitch() {
        multiSwitch.styleClass.remove("x-switch-on")
        if (multiOn) multiSwitch.styleClass.add("x-switch-on")
    }

    private fun refreshButton() {
        button.text = selectedName()
        button.tooltip = Ui.tooltip(selectedName())
    }

    // ── catalog counts ────────────────────────────────────────────────────
    // A card names its catalog count ("23 catalogs"). Listing catalogs can hit
    // the network (a Stremio manifest), so it happens in the background AFTER
    // the dialog is already open, one provider at a time with a short budget,
    // and a provider that will not answer keeps its "…".

    private fun ensureCounts() {
        countJob?.cancel()
        val missing = providers.filter { !countCache.containsKey(it.id) }
        if (missing.isEmpty()) return
        countJob = AppShell.uiScope.launch {
            for (p in missing) {
                if (!pop.isShowing) break
                val n = withTimeoutOrNull(2_500L) {
                    runCatching { AppShell.app.providers.byId(p.id)?.catalogs()?.size }.getOrNull()
                }
                if (n != null) {
                    countCache[p.id] = n
                    val label = countLabels[p.id]
                    if (label != null) {
                        // Recompose the whole sub-line (origin + count): the
                        // row may have been rebuilt since, and the "disabled"
                        // mark must survive the count landing.
                        val cfg = providers.firstOrNull { it.id == p.id } ?: p
                        val text = countText(cfg, n) + (if (cfg.enabled) "" else " · disabled")
                        desktop.fx.Fx.run { label.text = text }
                    }
                }
            }
        }
    }

    // ── picking ───────────────────────────────────────────────────────────

    /** Turns multi mode on/off. With it off, the single [selection] (or All)
     *  decides the catalog; with it on, the [activeIds] feed does. */
    fun setMulti(on: Boolean, fire: Boolean = true) {
        // Switching ON with an empty feed starts from the current single pick
        // (or everything when that is All), so the toggle never blanks Home.
        if (on && activeIds.isEmpty()) {
            val sel = selection
            activeIds = if (sel != null) ArrayList(listOf(sel.id))
            else ArrayList(providers.map { it.id })
        }
        multiOn = on && activeIds.isNotEmpty()
        persist()
        refreshButton()
        rebuildMain()
        if (fire) onPick()
    }

    private fun moveActive(id: String, delta: Int) {
        val i = activeIds.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= activeIds.size) return
        val tmp = activeIds[i]
        activeIds[i] = activeIds[j]
        activeIds[j] = tmp
        persist()
        rebuildMain()
        onPick()
    }

    private fun dropActive(id: String) {
        if (!activeIds.remove(id)) return
        if (activeIds.isEmpty()) multiOn = false
        persist()
        refreshButton()
        rebuildMain()
        onPick()
    }

    private fun addActive(id: String) {
        if (activeIds.contains(id)) return
        // Adding a disabled provider enables it first: the feed only loads
        // from enabled providers, so adding without enabling would silently
        // contribute nothing.
        val cfg = providers.firstOrNull { it.id == id }
        if (cfg != null && !cfg.enabled) {
            runCatching { AppShell.app.store.setEnabled(id, true) }
            providers = providers.map { if (it.id == id) it.copy(enabled = true) else it }
        }
        activeIds.add(id)
        persist()
        rebuildMain()
        onPick()
    }

    private fun persist() {
        runCatching {
            val store = AppShell.app.store
            store.setHomeMultiOn(multiOn)
            store.setHomeMultiIds(activeIds.toList())
            store.setHomeProvider(selection?.id ?: "")
        }
    }

    private fun applySelection(cfg: ProviderConfig?, fire: Boolean) {
        // Picking a disabled provider enables it: a pick that loads nothing
        // would read as a broken picker.
        if (cfg != null && !cfg.enabled) {
            runCatching { AppShell.app.store.setEnabled(cfg.id, true) }
            providers = providers.map { if (it.id == cfg.id) it.copy(enabled = true) else it }
        }
        selection = cfg
        if (cfg != null) multiOn = false
        persist()
        refreshButton()
        pop.hide()
        rebuildMain()
        if (fire) onPick()
    }

    private fun pick(cfg: ProviderConfig?) = applySelection(cfg, fire = true)

    // ── test hooks ──────────────────────────────────────────────────────────
    // The sheet is a Popup — a window with a scene of its own — so a UI test
    // cannot reach its nodes through the app's scene graph, and the position of
    // a popup window depends on the display. These let it drive the sheet the
    // way the row's own click handler does.

    fun isOpenForTest(): Boolean = pop.isShowing

    fun sheetRootForTest(): javafx.scene.Parent? = pop.content.firstOrNull() as? javafx.scene.Parent

    /** The chip labels of the open sheet ("All" first). */
    fun chipLabelsForTest(): List<String> =
        chips.children.filterIsInstance<Button>().mapNotNull { it.text }

    /**
     * `(label, laid-out width, width its text needs)` for every chip.
     *
     * A squeezed chip still reports its full `text` — the ellipsis is a drawing
     * decision — so the UI test needs these three numbers to tell a readable
     * chip row from one that has been crushed into "…".
     */
    fun chipWidthsForTest(): List<Triple<String, Double, Double>> =
        chips.children.filterIsInstance<Button>().map {
            Triple(it.text ?: "", it.width, it.prefWidth(-1.0))
        }

    /** The horizontally scrollable chip row (see [chipsScroll]). */
    fun chipRowForTest(): ScrollPane = chipsScroll

    /** Fires the chip with this label, as a click would. */
    fun pressChipForTest(label: String): Boolean {
        val b = chips.children.filterIsInstance<Button>().firstOrNull { it.text == label } ?: return false
        b.fire()
        return true
    }

    /** The card names the open sheet is showing ("All providers" first). */
    fun rowNamesForTest(): List<String> =
        if (multiOn) {
            activeIds.mapNotNull { id -> providers.firstOrNull { it.id == id }?.name } +
                shownProviders().filter { p -> activeIds.none { it == p.id } }.map { it.name }
        } else {
            listOf(ALL) + shownProviders().map { it.name }
        }

    /** Picks the card for [name] (use [ALL] for the first card), as a click would. */
    fun selectForTest(name: String): Boolean {
        if (name == ALL) {
            pick(null)
            return true
        }
        val cfg = providers.firstOrNull { it.name == name } ?: return false
        pick(cfg)
        return true
    }

    /** Picks the card for [name] WITHOUT firing the pick callback — for callers
     *  that are already inside a load and will read the selection themselves
     *  (see HomeScreenView.showProvider). */
    fun select(name: String): Boolean {
        val cfg = if (name == ALL) null else providers.firstOrNull { it.name == name } ?: return false
        applySelection(cfg, fire = false)
        return true
    }

    /** Picks by provider id (the persisted form), silently. */
    fun selectById(id: String): Boolean {
        val cfg = providers.firstOrNull { it.id == id } ?: return false
        applySelection(cfg, fire = false)
        return true
    }

    /** Best-effort name match for an Explore row: exact, case-insensitive,
     *  then substring — silently. */
    fun selectBestEffort(want: String): Boolean {
        val w = want.trim()
        if (w.isEmpty()) return false
        providers.firstOrNull { it.name == w }?.let { applySelection(it, fire = false); return true }
        providers.firstOrNull { it.name.equals(w, ignoreCase = true) }?.let { applySelection(it, fire = false); return true }
        providers.firstOrNull { it.name.contains(w, ignoreCase = true) }?.let { applySelection(it, fire = false); return true }
        return false
    }

    /** Restores a persisted multi feed, silently. False when none of the ids
     *  exist any more. */
    fun setMultiSilent(ids: List<String>): Boolean {
        val live = ids.filter { id -> providers.any { it.id == id } }
        if (live.isEmpty()) return false
        activeIds = ArrayList(live)
        multiOn = true
        selection = null
        persist()
        refreshButton()
        rebuildMain()
        return true
    }
}
