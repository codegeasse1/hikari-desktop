package dalvik.system

import com.hikari.app.cs3.DexJar
import java.io.File
import java.net.URLClassLoader

/**
 * Desktop stand-in for Android's dalvik PathClassLoader. CloudStream bridge
 * plugins call `PathClassLoader(pluginPath, context.classLoader)` to open a
 * bundled .cs3 — on the JVM this shim loads JVM jars directly and routes
 * dex archives (classes.dex) through [DexJar]'s dex→JVM translation, so any
 * dex plugin runs unmodified.
 */
open class PathClassLoader(dexPath: String, parent: ClassLoader) :
    URLClassLoader(arrayOf(DexJar.toJvmJarUrl(File(dexPath))), parent) {

    /** Android's 3-arg form (dexPath, librarySearchPath, parent). */
    constructor(dexPath: String, librarySearchPath: String?, parent: ClassLoader) : this(dexPath, parent)
}
