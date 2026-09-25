package android.content

import java.io.File

/**
 * Desktop stand-in for `android.content.ContextWrapper`.
 *
 * `ContextWrapper extends Context`, and on Android almost every context an
 * extension is handed is one: it does `(context as ContextWrapper)
 * .baseContext` to unwrap an activity's theme wrapper, and 28 of the 96
 * extensions in the corpus name the type — enough that it has to exist for them
 * to link at all, and enough that a cast to it has to SUCCEED, which is why the
 * desktop's own [android.app.Application] is one too.
 *
 * Everything it is asked for is delegated to [baseContext] when there is one, so
 * unwrapping a wrapper gives the same files/preferences/package it started
 * from.
 */
open class ContextWrapper(base: Context?) : Context() {

    private var baseContext: Context? = base

    open fun getBaseContext(): Context? = baseContext

    open fun attachBaseContext(base: Context?) {
        baseContext = base
    }

    override val applicationContext: Context
        get() = baseContext?.applicationContext ?: this

    override val packageName: String
        get() = baseContext?.packageName ?: super.packageName

    override val filesDir: File
        get() = baseContext?.filesDir ?: super.filesDir

    override val cacheDir: File
        get() = baseContext?.cacheDir ?: super.cacheDir

    override val classLoader: ClassLoader
        get() = baseContext?.classLoader ?: super.classLoader

    override val packageManager: android.content.pm.PackageManager
        get() = baseContext?.packageManager ?: super.packageManager

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext?.getSharedPreferences(name, mode) ?: super.getSharedPreferences(name, mode)

    override fun getResources(): android.content.res.Resources =
        baseContext?.getResources() ?: super.getResources()

    override fun getString(resId: Int): String = baseContext?.getString(resId) ?: super.getString(resId)

    override fun getSystemService(name: String): Any? =
        baseContext?.getSystemService(name) ?: super.getSystemService(name)

    override fun startActivity(intent: Intent) {
        (baseContext ?: this).startActivity(intent)
    }

    override fun toString(): String = "ContextWrapper($baseContext)"
}
