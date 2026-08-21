package android.content

import android.net.Uri
import java.io.File

open class Context {

    open val applicationContext: Context get() = this

    open val cacheDir: File
        get() = File(System.getProperty("user.home"), ".hikari/cache").apply { mkdirs() }

    open val filesDir: File
        get() = File(System.getProperty("user.home"), ".hikari").apply { mkdirs() }

    open val classLoader: ClassLoader get() = Context::class.java.classLoader

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
