package android.content

import android.net.Uri
import android.content.pm.PackageManager
import android.content.res.Resources
import java.io.File

open class Context {

    open val applicationContext: Context get() = this

    open val cacheDir: File
        get() = File(System.getProperty("user.home"), ".hikari/cache").apply { mkdirs() }

    open val filesDir: File
        get() = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }

    open val classLoader: ClassLoader get() = Context::class.java.classLoader

    /** The desktop package manager — see [PackageManager], which reads an
     *  `.apk`'s binary AndroidManifest.xml itself. */
    open val packageManager: PackageManager get() = PackageManager()

    open val packageName: String get() = "com.hikari.desktop"

    /** Resources back the display metrics plugin UIs measure with — see
     *  [Resources]. */
    private val resources = Resources()

    open fun getResources(): Resources = resources

    open fun getString(resId: Int): String = ""

    open fun getSystemService(name: String): Any? = null

    /** One JSON file per preference name under `~/.hikari/prefs/` (see
     *  [SharedPreferences]); the same file is handed back for the same name. */
    private val prefs = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.computeIfAbsent(name) { SharedPreferences(SharedPreferences.fileFor(filesDir, it)) }

    open fun startActivity(intent: Intent) {
        val url = intent.data?.toString()
        if (!url.isNullOrBlank()) {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        }
    }

    override fun toString(): String = "DesktopContext"

    companion object {
        const val MODE_PRIVATE = 0
    }
}
