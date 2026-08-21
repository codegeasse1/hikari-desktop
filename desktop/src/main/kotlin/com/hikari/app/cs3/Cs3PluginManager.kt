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

    private val cache = ConcurrentHashMap<String, List<MainAPI>>()

    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val loadLock = java.util.concurrent.locks.ReentrantLock()

    private val lastFail = ConcurrentHashMap<String, Long>()

    private const val FAIL_RETRY_MS = 60_000L

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = StringBuilder()

    private val loadExecutor =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "cs3-load").apply { isDaemon = true }
        }

    private const val LOAD_TIMEOUT_S = 45L

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
        val path = file.absolutePath

        val classLoader = try {
            URLClassLoader(arrayOf(DexJar.toJvmJarUrl(file)), context.classLoader)
        } catch (e: Throwable) {
            record("URLClassLoader failed", e)
            return fail()
        }

        val manifest = try {
            val stream = classLoader.getResourceAsStream("manifest.json")
            if (stream == null) {
                record("manifest missing", RuntimeException("no manifest.json in ${file.name}"))
                return fail()
            }
            stream.use {
                AppUtils.parseJson(InputStreamReader(it).readText(), BasePlugin.Manifest::class)
            }
        } catch (e: Throwable) {
            record("manifest read failed", e)
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
                future.cancel(true)
                record("load() threw", e)
                return fail()
            }
        } catch (e: Throwable) {
            record("load() threw", e)
            return fail()
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
        }
        return apis
    }

    private fun fail(): List<MainAPI> {
        val details = errorDetails.toString().trim()
        lastError = if (details.isNotBlank()) details else "Unknown error loading plugin"
        return emptyList()
    }
}
