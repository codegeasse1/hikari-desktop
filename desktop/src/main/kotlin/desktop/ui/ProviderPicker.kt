package desktop.ui

import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Popup

/**
 * The catalog's provider picker — the desktop twin of the Android app's
 * "choose a provider" sheet.
 *
 * What it replaces: a plain combo box holding one flat, alphabetical list of
 * every enabled provider. With a hundred of them from six engines that is a
 * scroller you cannot search and cannot narrow — and the list is exactly what
 * chooses which catalog the whole home page is built from, so finding the right
 * entry is not a detail.
 *
 * What it is: a filter field, one chip per engine that actually has providers
 * installed (`All | Aniyomi | CloudStream | Hikari | Nuvio | …`), and the list —
 * the same three parts, in the same order, as the Android picker. Picking a row
 * selects that provider as the catalog source and calls [onPick], so the caller
 * reloads.
 *
 * Everything here is FX-thread only (it is all JavaFX nodes); [setProviders] is
 * called from the screen's own load, inside its `Fx.run` block.
 */
class ProviderPicker(private val onPick: () -> Unit) {

    companion object {
        /** The "no provider filter" label — also what the button shows before a
         *  pick, and the vocabulary the home status lines already use. */
        const val ALL = "All providers"
    }

    private var providers: List<ProviderConfig> = emptyList()
    private var kindFilter: ProviderType? = null

    /** The chosen provider, or null for [ALL]. Read on the FX thread. */
    var selection: ProviderConfig? = null
        private set

    /** What the caller shows and compares: the chosen provider's name, or [ALL]. */
    fun selectedName(): String = selection?.name ?: ALL

    /** The button that opens the sheet. Styled like the combo box it replaced,
     *  so the top bar looks the same. */
    val button: Button = Button(ALL).apply {
        styleClass.addAll("combo-box", "provider-picker")
        alignment = Pos.CENTER_LEFT
        graphic = Icons.of(Icons.CHEVRON_DOWN, 13.0)
        contentDisplay = ContentDisplay.RIGHT
        graphicTextGap = 10.0
        isFocusTraversable = false
        prefWidth = 260.0
        // Shrinkable: the picker + Refresh + Logs row must still fit a narrow
        // window instead of pushing the page wider than the viewport.
        minWidth = 150.0
        maxWidth = 340.0
        setOnAction { toggle() }
    }

    private val search = TextField().apply {
        styleClass.addAll("field", "provider-search")
        promptText = "Find a provider…"
        textProperty().addListener { _, _, _ -> rebuildList() }
    }
    private val chips = HBox(4.0).apply { styleClass.addAll("segmented", "provider-chips") }
    private val listBox = VBox(2.0).apply { styleClass.add("picker-list") }
    private val listScroll = ScrollPane(listBox).apply {
        styleClass.add("scroll-pane")
        isFitToWidth = true
        prefHeight = 300.0
        minHeight = 140.0
        maxHeight = 320.0
        minWidth = 0.0
    }
    private val pop = Popup().apply {
        isAutoHide = true
        isHideOnEscape = true
    }

    init {
        val body = VBox(Theme.S2, search, chips, listScroll).apply {
            styleClass.add("picker-pop")
            prefWidth = 360.0
            maxWidth = 420.0
            minWidth = 300.0
        }
        VBox.setVgrow(listScroll, Priority.ALWAYS)
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
            button.text = ALL
        }
        if (kindFilter != null && sorted.none { it.type == kindFilter }) kindFilter = null
        if (changed) {
            rebuildChips()
            if (pop.isShowing) rebuildList()
        }
    }

    private fun toggle() {
        if (pop.isShowing) {
            pop.hide()
            return
        }
        search.text = ""
        rebuildChips()
        rebuildList()
        val at = button.localToScreen(button.boundsInLocal)
        if (at != null) pop.show(button, at.minX, at.maxY + 4.0)
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
    }

    /** `All` first, then one chip per engine that actually has providers — a chip
     *  for an engine with nothing installed would filter to an empty list. */
    private fun rebuildChips() {
        val kinds = providers.map { it.type }.distinct().sortedBy { Ui.providerEngineLabel(it) }
        val index = kinds.indexOfFirst { it == kindFilter }.let { if (it < 0) 0 else it + 1 }
        if (index == 0) kindFilter = null
        val labels = listOf("All") + kinds.map { Ui.providerEngineLabel(it) }
        // Built in a throw-away box and MOVED here: re-parenting a node pulls it
        // out of its old parent's children list, so the copy matters — handing
        // `setAll` the box's live list would mutate it while it is iterated.
        val seg = Ui.segmented(labels, index) { picked ->
            kindFilter = if (picked <= 0) null else kinds.getOrNull(picked - 1)
            rebuildList()
        }
        chips.children.setAll(ArrayList<javafx.scene.Node>(seg.children))
    }

    private fun rebuildList() {
        val q = search.text.trim().lowercase()
        val shown = providers
            .filter { kindFilter == null || it.type == kindFilter }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
        val rows = ArrayList<javafx.scene.Node>(shown.size + 1)
        rows.add(row(null, selection == null))
        shown.forEach { rows.add(row(it, selection?.id == it.id)) }
        listBox.children.setAll(rows)
    }

    private fun row(cfg: ProviderConfig?, selected: Boolean): HBox {
        val name = Label(cfg?.name ?: ALL).apply {
            styleClass.add("src-name")
            isWrapText = false
            minWidth = 0.0
            maxWidth = 240.0
        }
        val right = if (cfg == null) {
            Label("every enabled provider").apply { styleClass.add("tiny") }
        } else {
            Ui.badge(Ui.providerEngineLabel(cfg.type), if (cfg.type == ProviderType.HIKARI) "badge-accent" else "badge")
        }
        return HBox(8.0, name, Ui.spacer(), right).apply {
            styleClass.add("picker-row")
            if (selected) styleClass.add("picker-row-sel")
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { pick(cfg) }
        }
    }

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

    /** Fires the chip with this label, as a click would. */
    fun pressChipForTest(label: String): Boolean {
        val b = chips.children.filterIsInstance<Button>().firstOrNull { it.text == label } ?: return false
        b.fire()
        return true
    }

    /** The row names the open sheet is showing ("All providers" first). */
    fun rowNamesForTest(): List<String> =
        listBox.children.filterIsInstance<HBox>()
            .mapNotNull { row -> row.children.filterIsInstance<Label>().firstOrNull()?.text }

    /** Picks the row for [name] (use [ALL] for the first row), as a click would. */
    fun selectForTest(name: String): Boolean {
        if (name == ALL) {
            pick(null)
            return true
        }
        val cfg = providers.firstOrNull { it.name == name } ?: return false
        pick(cfg)
        return true
    }

    private fun pick(cfg: ProviderConfig?) {
        selection = cfg
        button.text = cfg?.name ?: ALL
        pop.hide()
        rebuildList()
        onPick()
    }
}
