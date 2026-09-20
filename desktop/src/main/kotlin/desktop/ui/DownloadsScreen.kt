package desktop.ui

import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadTask
import com.hikari.app.download.DownloadsRepository
import desktop.fx.DesktopUi
import desktop.fx.Fx
import desktop.img.ImageLoader
import desktop.player.DesktopPlayer
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads.
 *
 * Two halves: a storage/behaviour panel that is real (it reports the app data
 * folder, its size, free disk space, where exported copies land, and how many
 * downloads run at once), and the live queue, which mirrors
 * [DownloadsRepository] — the same object the engine pumps. Progress rows
 * update in place as bytes land, and a finished download is playable offline
 * straight from here and (by default) also sitting in the user's own
 * Downloads/Hikari folder.
 */
class DownloadsScreenView {

    private val body = StackPane()
    val root: ScrollPane = Ui.vScroll(body)

    private val app get() = AppShell.app

    private val queueBox = VBox(Theme.S3)
    private val queueSummary = themed("", "section-sub")
    private val storageHost = VBox(Theme.S4)

    private var started = false

    /** True while this view is mounted in the shell's content area. Progress
     *  updates arrive several times a second, so they are dropped (rather than
     *  built and thrown away) whenever the user is looking at another screen. */
    private var attached = false

    fun onShown() {
        attached = true
        storageHost.children.setAll(storagePanel())
        render(DownloadsRepository.snapshot())
        AppShell.uiScope.launch { runCatching { DownloadsRepository.ensureLoaded() } }
        if (!started) {
            started = true
            AppShell.uiScope.launch {
                DownloadsRepository.tasks.collect { list -> Fx.run { render(list) } }
            }
        }
    }

    private fun render(tasks: List<DownloadTask>) {
        if (!attached) return
        val title = if (tasks.isEmpty()) "Queue" else "Queue · ${tasks.size}"
        val active = tasks.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        queueSummary.text = when {
            tasks.isEmpty() -> ""
            active > 0 -> "$active downloading · ${tasks.size} total"
            else -> "${tasks.size} in the list"
        }
        queueHeaderTitle.text = title
        val rows: List<Node> = if (tasks.isEmpty()) {
            listOf(
                Ui.emptyState(
                    Icons.DOWNLOAD,
                    "No downloads yet",
                    "Use the download button next to any source on a title's page. " +
                        "Downloads are stored uncompressed, so they play instantly and never expire with the CDN.",
                )
            )
        } else {
            tasks.map { taskRow(it) }
        }
        queueBox.children.setAll(rows)
    }

    private val queueHeaderTitle = Label("Queue").apply { styleClass.add("section-title") }

    private fun header(): Node {
        val text = VBox(1.0,
            queueHeaderTitle,
            themed("Pull a title down once and it plays without a network", "section-sub"),
        )
        return HBox(10.0, text, Ui.spacer(), queueSummary).apply { alignment = Pos.CENTER_LEFT }
    }

    // ── storage / behaviour panel ───────────────────────────────────────────

    private fun storagePanel(): Region {
        val dir = app.filesDir
        val downloads = File(dir, "downloads")
        val exportDir = DownloadsRepository.exportDir()
        val used = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        val stored = runCatching {
            downloads.listFiles()?.count { it.isDirectory } ?: 0
        }.getOrDefault(0)

        val tiles = HBox(Theme.S4,
            Ui.statTile(formatBytes(used), "Used by Hikari"),
            Ui.statTile(formatBytes(free), "Free on disk"),
            Ui.statTile(stored.toString(), "Titles stored"),
        ).apply { alignment = Pos.CENTER_LEFT }

        val path = themed(exportDir.absolutePath, "h-mono").apply { isWrapText = true }

        val openData = Ui.button("Open data folder", icon = Icons.FOLDER, ghost = true) {
            if (DesktopUi.openInFolder(dir.absolutePath)) {
                AppShell.toast("Opened ${dir.absolutePath}", "ok")
            } else {
                AppShell.toast("Couldn't open the folder — it's at ${dir.absolutePath}", "error")
            }
        }
        val openExport = Ui.button("Open Downloads", icon = Icons.FOLDER, ghost = true) {
            exportDir.mkdirs()
            if (DesktopUi.openInFolder(exportDir.absolutePath)) {
                AppShell.toast("Opened ${exportDir.absolutePath}", "ok")
            } else {
                AppShell.toast("Couldn't open the folder — it's at ${exportDir.absolutePath}", "error")
            }
        }

        val exportToggle = CheckBox("Also save a copy to my Downloads folder").apply {
            isSelected = runCatching { app.store.downloadToFolder() }.getOrDefault(true)
            selectedProperty().addListener { _, _, value ->
                runCatching { app.store.setDownloadToFolder(value) }
                AppShell.toast(
                    if (value) "New downloads will also be copied to Downloads/Hikari"
                    else "New downloads will stay inside Hikari only",
                    "ok",
                )
            }
        }

        val concurrency = Ui.segmented(
            listOf("1", "2", "3", "4"),
            initial = (DownloadsRepository.maxConcurrent() - 1).coerceIn(0, 3),
        ) { index ->
            val n = index + 1
            DownloadsRepository.setMaxConcurrent(n)
            runCatching { app.store.setDownloadConcurrency(n) }
        }

        return Ui.panel(
            HBox(10.0, tiles, Ui.spacer(), HBox(8.0, openData, openExport).apply { alignment = Pos.CENTER_RIGHT })
                .apply { alignment = Pos.CENTER_LEFT },
            Ui.divider(),
            HBox(12.0, themed("Copies land in", "tiny").apply { prefWidth = 108.0 }, path)
                .apply { alignment = Pos.CENTER_LEFT },
            Ui.divider(),
            HBox(14.0,
                exportToggle,
                Ui.spacer(),
                themed("Download at once", "tiny"),
                concurrency,
            ).apply { alignment = Pos.CENTER_LEFT },
        )
    }

    // ── queue rows ──────────────────────────────────────────────────────────

    private fun taskRow(t: DownloadTask): Region {
        val thumb = ImageView().apply {
            fitWidth = 52.0
            fitHeight = 74.0
            isPreserveRatio = false
            isSmooth = true
            clip = Rectangle(52.0, 74.0).apply { arcWidth = 10.0; arcHeight = 10.0 }
        }
        if (!t.poster.isNullOrBlank()) {
            ImageLoader.loadAsync(t.poster, onReady = { img -> if (img != null) thumb.image = img }, w = 160, h = 240)
        }
        val thumbBox = StackPane(thumb).apply { styleClass.add("dl-thumb") }

        val title = themed(
            if (t.episodeLabel.isBlank()) t.title else "${t.title} · ${t.episodeLabel}",
            "dl-title",
        ).apply { isWrapText = true; maxWidth = 460.0 }

        val bar = ProgressBar().apply {
            styleClass.add("dl-bar")
            prefWidth = 340.0
            maxWidth = Double.MAX_VALUE
            progress = when {
                t.status == DownloadStatus.DONE -> 1.0
                t.status == DownloadStatus.RUNNING && t.progress <= 0f -> -1.0
                else -> t.progress.toDouble()
            }
        }

        val middle = VBox(6.0, title, bar, themed(statusLine(t), "dl-sub"))
        HBox.setHgrow(middle, Priority.ALWAYS)

        val actions = HBox(6.0).apply { alignment = Pos.CENTER_RIGHT }
        when (t.status) {
            DownloadStatus.RUNNING, DownloadStatus.CONVERTING, DownloadStatus.QUEUED ->
                actions.children.add(Ui.iconButton(Icons.PAUSE, "Pause", 15.0) { DownloadsRepository.pause(t.id) })
            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                actions.children.add(Ui.iconButton(Icons.PLAY, "Resume", 15.0) { DownloadsRepository.resume(t.id) })
            DownloadStatus.DONE -> {
                if (t.playableOffline) {
                    actions.children.add(Ui.iconButton(Icons.PLAY, "Play offline", 15.0) { playOffline(t) })
                }
                if (t.playableSaved) {
                    actions.children.add(Ui.iconButton(Icons.FOLDER, "Show the exported file", 15.0) { showSaved(t) })
                }
            }
        }
        actions.children.add(Ui.iconButton(Icons.TRASH, "Remove", 15.0) { DownloadsRepository.remove(t.id) })

        return HBox(13.0, thumbBox, middle, actions).apply {
            styleClass.add("dl-row")
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun statusLine(t: DownloadTask): String = when (t.status) {
        DownloadStatus.QUEUED -> "Waiting in the queue"
        DownloadStatus.RUNNING -> {
            val pct = (t.progress * 100).toInt().coerceIn(0, 100)
            val parts = mutableListOf<String>()
            parts.add("$pct%")
            if (t.doneDurationMs > 0L && t.durationMs > 0L) {
                parts.add("${clock(t.doneDurationMs)} / ${clock(t.durationMs)}")
            }
            if (t.bytesPerSec > 0L) parts.add("${formatBytes(t.bytesPerSec)}/s")
            if (t.bytesPerSec > 0L && t.bytesTotal > 0L) {
                val left = ((t.bytesTotal - t.bytesDone) / t.bytesPerSec).coerceAtLeast(0)
                if (left > 0) parts.add("about ${clock(left * 1000L)} left")
            }
            parts.joinToString("  ·  ")
        }
        DownloadStatus.CONVERTING -> "Wrapping up into a single playable file…"
        DownloadStatus.PAUSED -> "Paused — resume any time, what's already on disk is kept"
        DownloadStatus.DONE -> {
            val where = t.savedUri?.let { File(it).name } ?: "stored in Hikari"
            "Ready to play offline  ·  $where"
        }
        DownloadStatus.FAILED -> t.error?.takeIf { it.isNotBlank() } ?: "Download failed"
    }

    private fun playOffline(t: DownloadTask) {
        val path = t.localPath ?: return
        DesktopPlayer.playFile(
            if (t.episodeLabel.isBlank()) t.title else "${t.title} — ${t.episodeLabel}",
            path,
        )
    }

    private fun showSaved(t: DownloadTask) {
        val path = t.savedUri ?: return
        if (DesktopUi.openInFolder(path)) {
            AppShell.toast("Revealed ${File(path).name}", "ok")
        } else {
            AppShell.toast("The copy is at $path", "error")
        }
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100 || unit == 0) "${value.toInt()} ${units[unit]}"
        else String.format("%.1f %s", value, units[unit])
    }

    private fun themed(text: String, cls: String): Label = Label(text).apply { styleClass.add(cls) }

    init {
        VBox.setVgrow(root, Priority.ALWAYS)
        root.sceneProperty().addListener { _, _, scene -> attached = scene != null }
        val column = VBox(Theme.S4).apply {
            children.addAll(
                Ui.sectionHeader("Offline", "Downloads are stored uncompressed so they play instantly and never expire"),
                storageHost,
                header(),
                queueBox,
            )
        }
        body.children.setAll(column)
    }
}
