package desktop.fx

import android.net.Uri
import java.awt.Desktop

object DesktopUi {
    fun open(url: String) {
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    fun open(uri: Uri) = open(uri.toString())

    fun openBrowser(url: String) {
        open(url)
    }

    /** Opens a file with whatever the OS has registered for it. Returns false
     *  when the path is gone or the platform refuses. */
    fun openFile(path: String): Boolean = runCatching {
        val f = java.io.File(path)
        if (!f.exists()) return@runCatching false
        Desktop.getDesktop().open(f)
        true
    }.getOrDefault(false)

    /** Reveals a file (or opens the folder itself) in the file manager — the
     *  behaviour people expect from "Open folder" on a download. */
    fun openInFolder(path: String): Boolean = runCatching {
        val f = java.io.File(path)
        if (!f.exists()) return@runCatching false
        val dir = if (f.isDirectory) f else f.parentFile
        Desktop.getDesktop().open(dir)
        true
    }.getOrDefault(false)
}
