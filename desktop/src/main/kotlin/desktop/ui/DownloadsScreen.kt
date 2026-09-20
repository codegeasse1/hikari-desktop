package desktop.ui

import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.io.File

/**
 * Downloads.
 *
 * The storage panel is real — it reports the app data folder, how much of it is
 * used, and free disk space, and can reveal the folder in Explorer. The queue
 * itself is the landing place for the desktop download engine (the Android
 * `DownloadEngine` is a plain OkHttp m3u8/MP4 downloader, so it ports directly);
 * until that lands the screen shows its empty state rather than pretending.
 */
class DownloadsScreenView {

    private val body = StackPane()
    val root: ScrollPane = Ui.vScroll(body)

    private val app get() = AppShell.app

    fun onShown() {
        val column = VBox(Theme.S4)
        column.children.addAll(
            Ui.sectionHeader("Offline", "Downloads live in the app data folder and play without a network"),
            storagePanel(),
            Ui.sectionHeader("Queue"),
            Ui.emptyState(
                Icons.DOWNLOAD,
                "No downloads yet",
                "Tap the download button on any source to keep it offline. Downloads are stored uncompressed " +
                    "so they play instantly and never expire with the CDN.",
            ),
        )
        body.children.setAll(column)
    }

    private fun storagePanel(): Region {
        val dir = app.filesDir
        val downloads = File(dir, "downloads")
        val used = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)

        val tiles = HBox(Theme.S4,
            Ui.statTile(formatBytes(used), "Used by Hikari"),
            Ui.statTile(formatBytes(free), "Free on disk"),
            Ui.statTile(if (downloads.exists()) "Ready" else "Not created", "Downloads folder"),
        ).apply { alignment = Pos.CENTER_LEFT }

        val path = Theme.label(dir.absolutePath, size = 11.5, dim = true).apply {
            styleClass.add("h-mono")
            isWrapText = true
        }
        val open = Ui.button("Open folder", icon = Icons.FOLDER, ghost = true) {
            val ok = runCatching {
                java.awt.Desktop.getDesktop().open(dir)
            }.isSuccess
            AppShell.toast(
                if (ok) "Opened ${dir.absolutePath}" else "Couldn't open the folder — it's at ${dir.absolutePath}",
                if (ok) "ok" else "error",
            )
        }
        val clearCache = Ui.button("Clear watch cache", icon = Icons.TRASH, ghost = true) {
            AppShell.toast("Nothing cached to clear yet")
        }

        return Ui.panel(
            HBox(10.0, tiles, Ui.spacer(), HBox(8.0, open, clearCache).apply { alignment = Pos.CENTER_RIGHT })
                .apply { alignment = Pos.CENTER_LEFT },
            Ui.divider(),
            path,
        )
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
        return if (value >= 100 || unit == 0) "${value.toInt()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
    }

    init {
        VBox.setVgrow(root, Priority.ALWAYS)
    }
}
