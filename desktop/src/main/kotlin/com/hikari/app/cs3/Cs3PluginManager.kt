@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.hikari.app.cs3

import android.content.Context
import com.hikari.app.HikariApp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.io.InputStreamReader
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads CloudStream-style plugin archives on the desktop JVM. Both forms are
 * supported:
 *
 *  - JVM jars (`.jar`) are opened with a URLClassLoader directly,
 *  - Android dex archives (`.cs3`/`.hiki` with `classes.dex`) are first
 *    translated to JVM bytecode by [DexJar], so every dex plugin runs too.
 *
 * Then the CloudStream contract runs exactly as the Android PathClassLoader
 * does it:
 *
 *  1. read `manifest.json` → `pluginClassName` (+ `requiresResources`),
 *  2. instantiate that class with a no-arg constructor,
 *  3. set `filename`, call `load()`,
 *  4. collect the MainAPIs the plugin registered (via `APIHolder.allProviders`).
 *
 * The whole CloudStream runtime ships in `libs/cloudstream3.jar` (the JVM
 * artifact), so plugins get their genuine extractors, M3u8Helper, nicehttp
 * etc. for free.
 *
 * Instances are cached per file path. The last failure (if any) is surfaced on
 * [lastError] so the UI can show the REAL reason a plugin refused to load.
 */
object Cs3PluginManager {

    /** Loaded plugin instances by absolute path (CS3 ExtensionLoader parity). */
    val plugins: java.util.concurrent.ConcurrentHashMap<String, BasePlugin> = java.util.concurrent.ConcurrentHashMap()

    /** One lock per plugin path, so two screens cannot load the same file twice. */
    private val pluginLoadLocks: java.util.concurrent.ConcurrentHashMap<String, Any> = java.util.concurrent.ConcurrentHashMap()

    /** Class loader -> plugin display name, for cross-plugin class resolution. */
    val classLoaders: java.util.concurrent.ConcurrentHashMap<ClassLoader, String> = java.util.concurrent.ConcurrentHashMap()

    /** Class loader -> jar file it was loaded from. */
    val classLoaderToJar: java.util.concurrent.ConcurrentHashMap<ClassLoader, java.io.File> = java.util.concurrent.ConcurrentHashMap()

    /** Class loader -> class names inside its jar (for getCallingPluginName). */
    val classLoaderToClassNames: java.util.concurrent.ConcurrentHashMap<ClassLoader, Set<String>> = java.util.concurrent.ConcurrentHashMap()

    private val cache = ConcurrentHashMap<String, List<MainAPI>>()

    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val loadLock = java.util.concurrent.locks.ReentrantLock()

    private val lastFail = ConcurrentHashMap<String, Long>()

    private const val FAIL_RETRY_MS = 60_000L

    @Volatile
    var lastError: String? = null
        private set

    /** Non-fatal trouble from the last load: the providers came up, but
     *  `load()` itself did not finish (see [loadFile]). */
    @Volatile
    var lastWarning: String? = null
        private set

    /** Set by [loadFile] when `load()` threw; the providers it had already
     *  registered are still returned. */
    private var loadFailure: Throwable? = null

    private val errorDetails = StringBuilder()

    private val loadExecutor =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "cs3-load").apply { isDaemon = true }
        }

    private const val LOAD_TIMEOUT_S = 45L

    /**
     * A class loader that searches every loaded plugin before the app loader:
     * Jackson/kotlin-reflect deserializing a plugin-defined inner class across
     * a class-loader boundary is what made whole catalogs come back empty.
     */
    fun createCompositeClassLoader(fallback: ClassLoader): ClassLoader {
        val pluginLoaders = classLoaders.keys.toList()
        return object : ClassLoader(fallback) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                for (loader in pluginLoaders) {
                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    /** Which loaded plugin is calling us, via its class loader first. */
    fun getCallingPluginName(): String? {
        runCatching {
            val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            val name = walker.walk { stream ->
                stream.map { it.declaringClass }
                    .filter { clazz ->
                        val loader = clazz.classLoader
                        loader != null && classLoaders.containsKey(loader)
                    }
                    .map { clazz -> classLoaders[clazz.classLoader] }
                    .findFirst()
                    .orElse(null)
            }
            if (name != null) return name
        }
        val trace = Thread.currentThread().stackTrace
        for (element in trace) {
            val className = element.className
            if (className.startsWith("com.hikari.") || className.startsWith("com.lagradost.") ||
                className.startsWith("java.") || className.startsWith("kotlin.")
            ) continue
            for ((loader, name) in classLoaders) {
                if (classLoaderToClassNames[loader]?.contains(className) == true) return name
            }
        }
        return null
    }

    /** Forgets a plugin so the next use reloads it from disk. */
    fun unloadPlugin(absolutePath: String) {
        cache.remove(absolutePath)
        plugins.remove(absolutePath)
        lastFail.remove(absolutePath)
    }

    private fun recordLoader(classLoader: ClassLoader, name: String, jar: java.io.File) {
        classLoaders[classLoader] = name
        classLoaderToJar[classLoader] = jar
        runCatching {
            java.util.jar.JarFile(jar).use { jf ->
                classLoaderToClassNames[classLoader] = jf.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .toSet()
            }
        }
    }

    private fun record(what: String, e: Throwable) {
        val line = "$what: ${e.javaClass.simpleName}: ${e.message}"
        if (errorDetails.length < 4000) {
            errorDetails.append(line).append("\n")
        }
        System.err.println("Cs3PluginManager: $line")
    }

    fun apisFor(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        cache[path]?.let { return it }
        val failAt = lastFail[path]
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) return emptyList()
        loadLock.lock()
        try {
            cache[path]?.let { return it }
            if (path in loading) return emptyList()
            loading.add(path)
            try {
                val apis = loadFile(context, file)
                if (apis.isNotEmpty()) {
                    cache[path] = apis
                    lastFail.remove(path)
                } else {
                    lastFail[path] = System.currentTimeMillis()
                }
                return apis
            } finally {
                loading.remove(path)
            }
        } finally {
            loadLock.unlock()
        }
    }

    fun reload(context: Context, file: File): List<MainAPI> {
        val path = file.absolutePath
        loadLock.lock()
        try {
            loading.add(path)
            val apis = loadFile(context, file)
            if (apis.isNotEmpty()) {
                cache[path] = apis
                lastFail.remove(path)
            } else {
                cache.remove(path)
                lastFail[path] = System.currentTimeMillis()
            }
            return apis
        } finally {
            loading.remove(path)
            loadLock.unlock()
        }
    }

    private fun loadFile(context: Context, file: File): List<MainAPI> {
        errorDetails.setLength(0)
        lastError = null
        lastWarning = null
        loadFailure = null
        val path = file.absolutePath

        val jvmJar = try {
            val url = DexJar.toJvmJarUrlOrNull(file)
                ?: throw IllegalStateException(DexJar.lastError ?: "this extension's dex payload couldn't be converted to JVM bytecode")
            java.io.File(url.toURI())
        } catch (e: Throwable) {
            record("URLClassLoader failed", e)
            return fail()
        }
        val classLoader = try {
            URLClassLoader(arrayOf(jvmJar.toURI().toURL()), createCompositeClassLoader(context.classLoader))
        } catch (e: Throwable) {
            record("URLClassLoader failed", e)
            return fail()
        }

        val manifestText = try {
            val stream = classLoader.getResourceAsStream("manifest.json")
            if (stream == null) {
                record("manifest missing", RuntimeException("no manifest.json in ${file.name}"))
                return fail()
            }
            stream.use { InputStreamReader(it).readText() }
        } catch (e: Throwable) {
            record("manifest read failed", e)
            return fail()
        }

        val manifest = try {
            AppUtils.parseJson(manifestText, BasePlugin.Manifest::class)
        } catch (e: Throwable) {
            record("manifest read failed", e)
            return fail()
        }

        if (manifest.pluginClassName.isNullOrBlank()) {
            // A Hikari extension (manifest.json lists `mainClass`) is simply not
            // a CloudStream plugin — that is not an error for THIS loader, and
            // recording it as one used to hide the real reason from the loader
            // that was supposed to handle the file.
            if (manifestText.contains("\"mainClass\"")) return emptyList()
            record(
                "manifest has no pluginClassName",
                RuntimeException("manifest.json in ${file.name} has no pluginClassName — this is not a CloudStream plugin"),
            )
            return fail()
        }

        val instance = try {
            @Suppress("UNCHECKED_CAST")
            val pluginClass =
                classLoader.loadClass(manifest.pluginClassName) as Class<out BasePlugin>
            pluginClass.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            record("loadClass/instantiate ${manifest.pluginClassName} failed", e)
            return fail()
        }
        plugins[path] = instance
        recordLoader(classLoader, file.nameWithoutExtension, jvmJar)

        try {
            APIHolder.allProviders.removeAll { it.sourcePlugin == path }
            extractorApis.removeAll { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("cleanup old registrations failed", e)
        }

        try {
            instance.filename = path
            val task = java.util.concurrent.Callable<Any?> {
                if (instance is Plugin) {
                    instance.load(HikariApp.mainActivity as? android.app.Activity ?: context)
                } else {
                    instance.load()
                }
                null
            }
            val future = loadExecutor.submit(task)
            try {
                future.get(LOAD_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                record("load() timed out after ${LOAD_TIMEOUT_S}s", RuntimeException("${manifest.pluginClassName}.load() hung"))
                return fail()
            } catch (e: Throwable) {
                // A plugin whose load() dies inside its own bootstrap — a
                // donation/notice dialog, a settings screen, anything that
                // expects the Android UI framework — has usually already
                // registered its providers, and those providers work fine
                // (they are network code). Throwing the whole extension away
                // on that exception is what turned "this plugin shows a
                // dialog" into "couldn't load <name>" and no extension at all.
                // Keep whatever it registered and carry the reason along.
                future.cancel(true)
                record("load() threw", e)
                loadFailure = e
            }
        } catch (e: Throwable) {
            record("load() threw", e)
            loadFailure = e
        }

        val apis = try {
            APIHolder.allProviders.filter { it.sourcePlugin == path }
        } catch (e: Throwable) {
            record("collecting providers failed", e)
            return fail()
        }

        apis.forEach { api ->
            runCatching {
                var done = false
                var c: Class<*>? = api.javaClass
                while (c != null && !done) {
                    runCatching { c.getField("app").set(api, null); done = true }
                    if (!done) runCatching {
                        c.getDeclaredField("app").apply { isAccessible = true }
                            .set(api, null); done = true
                    }
                    c = c.superclass
                }
            }
        }
        if (apis.isEmpty()) {
            val details = errorDetails.toString().trim()
            lastError = if (details.isNotBlank()) {
                details
            } else {
                "Plugin loaded but registered no providers"
            }
        } else if (loadFailure != null) {
            // Success with a caveat: the providers are registered and usable,
            // but load() did not finish. Surface it as a warning, not a failure.
            lastError = null
            lastWarning = errorDetails.toString().trim().ifBlank { null }
        }
        return apis
    }

    private fun fail(): List<MainAPI> {
        val details = errorDetails.toString().trim()
        lastError = if (details.isNotBlank()) details else "Unknown error loading plugin"
        return emptyList()
    }
}
