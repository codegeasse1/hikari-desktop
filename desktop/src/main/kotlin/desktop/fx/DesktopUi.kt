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
}
