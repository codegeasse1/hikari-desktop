package com.hikari.app.cs3

import com.lagradost.cloudstream3.MainAPI
import java.io.File
import java.net.URLClassLoader

/**
 * CI self-test for the universal dex loader. Downloads a real desktop plugin
 * from the official extensions release, dex-translates one bundled .cs3,
 * loads it, reads its manifest and instantiates the plugin class. Fails (non
 * zero exit) when any step breaks, so the workflow catches a broken
 * dex→JVM pipeline at build time instead of after a release.
 */
object DexJarSelfTest {

    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        try {
            val jarUrl = args.getOrNull(0)
                ?: "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/anime.jar"
            println("DexJarSelfTest: downloading $jarUrl")
            val jarBytes = java.net.URI.create(jarUrl).toURL().openStream().use { it.readBytes() }
            val dir = File(System.getProperty("java.io.tmpdir"), "hikari-dexjars").apply { mkdirs() }
            val jar = File(dir, "self-test-anime.jar")
            jar.writeBytes(jarBytes)
            println("anime.jar: ${jarBytes.size} bytes")

            val cs3 = File(dir, "self-test-AniKoto.cs3")
            java.util.zip.ZipFile(jar).use { z ->
                z.getInputStream(z.getEntry("cs3/anime/AniKoto.cs3")).use { ins ->
                    cs3.outputStream().use { ous -> ins.copyTo(ous) }
                }
            }
            if (!cs3.isFile || cs3.length() == 0L) {
                println("FAIL: cs3/anime/AniKoto.cs3 missing from anime.jar")
                System.exit(1)
            }
            println("AniKoto.cs3: ${cs3.length()} bytes, isDexArchive=${DexJar.isDexArchive(cs3)}")

            val converted = DexJar.ensureJvmJar(cs3)
            if (converted == null) {
                println("FAIL: dex conversion returned null: ${DexJar.lastError}")
                System.exit(1)
                return
            }
            println("converted -> $converted (${converted.length()} bytes)")

            val loader = URLClassLoader(arrayOf(converted.toURI().toURL()), DexJar::class.java.classLoader)
            val manifestText = loader.getResourceAsStream("manifest.json")?.bufferedReader()?.use { it.readText() }
            if (manifestText.isNullOrBlank()) {
                println("FAIL: no manifest.json in converted jar")
                failures++
            } else {
                val root = org.json.JSONObject(manifestText)
                val className = root.optString("pluginClassName").takeIf { it.isNotBlank() }
                    ?: root.optString("pluginClass")
                println("manifest pluginClassName = $className")
                if (className.isBlank()) {
                    println("FAIL: manifest has no pluginClassName")
                    failures++
                } else {
                    val clazz = loader.loadClass(className)
                    println("loaded class: ${clazz.name} (super=${clazz.superclass?.name})")
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    println("instantiated: ${instance.javaClass.name}")
                }
            }

            val cs3Count = java.util.zip.ZipFile(jar).use { z ->
                z.entries().asSequence().count { it.name.startsWith("cs3/") && it.name.endsWith(".cs3") }
            }
            println("bundled cs3 plugins in anime.jar: $cs3Count")

            // Full runtime check: build the real app runtime (CloudStream wiring,
            // Conscrypt, Http, store) and load() the plugin exactly like the
            // desktop app does. load() must register providers — the exact step
            // that was breaking in-app (extensions installed + enabled but no
            // catalogs). load()/provider registration is a HARD gate so a broken
            // plugin pipeline fails the build; the catalog probe below is
            // diagnostic (it makes a real network fetch, so it's warning-only).
            try {
                val app = com.hikari.app.HikariApp()
                app.init()
                val apis = com.hikari.app.cs3.Cs3PluginManager.reload(app, converted)
                println("full load() registered ${apis.size} providers")
                if (apis.isEmpty()) {
                    val details = com.hikari.app.cs3.Cs3PluginManager.lastError
                        ?: "load() completed but registered no providers"
                    println("FAIL: plugin load() registered no providers: $details")
                    failures++
                } else {
                    println("first provider: ${apis[0].javaClass.name}")
                    probeCatalog(apis[0])
                }
            } catch (t: Throwable) {
                println("FAIL: full-load runtime check threw: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace()
                failures++
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            failures++
        }
        if (failures > 0) {
            println("DexJarSelfTest: FAILED ($failures failure(s))")
            System.exit(1)
        }
        println("DexJarSelfTest: OK")
    }

    /** Diagnostic (non-gating): asks the first registered provider for its home
     *  catalog over the real network and prints a summary, so the CI log shows
     *  whether catalog fetches work end-to-end (network → plugin → MainAPI).
     *  Suspended getMainPage is bridged through a Continuation; any failure here
     *  is warning-only. */
    private fun probeCatalog(api: MainAPI) {
        try {
            val method = api.javaClass.methods.firstOrNull {
                it.name == "getMainPage" && it.parameterCount == 3
            } ?: api.javaClass.methods.firstOrNull {
                it.name == "getMainPage" && it.parameterCount == 2
            }
            if (method == null) {
                println("WARN: no getMainPage on ${api.javaClass.name}")
                return
            }
            val reqClass = method.parameterTypes[1]
            // Build the home-catalog request directly. This CS3 build's
            // MainPageRequest is a data class (name, data, isHorizontal) — no
            // page/url — and a null name means "home page". It's the same class
            // the plugin's getMainPage expects (resolved via the parent
            // classloader), so a direct instance works with the reflective call.
            val req: Any? = runCatching {
                com.lagradost.cloudstream3.MainPageRequest("", "", false)
            }.getOrNull()
            if (req == null) {
                println("WARN: couldn't build MainPageRequest for ${reqClass.name}")
                return
            }
            val outcome = runCatching {
                if (method.parameterCount == 3) {
                    // Suspend fun getMainPage(int, MainPageRequest, Continuation).
                    // resumeWith is invoked exactly once — synchronously when the
                    // coroutine completes without suspending, later from another
                    // thread when it suspends — so unconditionally awaiting the
                    // latch covers both cases.
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val box = java.util.concurrent.atomic.AtomicReference<Any?>()
                    val thrown = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                    val cont = object : kotlin.coroutines.Continuation<Any?> {
                        override val context: kotlin.coroutines.CoroutineContext
                            get() = kotlin.coroutines.EmptyCoroutineContext
                        override fun resumeWith(result: kotlin.Result<Any?>) {
                            result.exceptionOrNull()?.let { thrown.set(it) }
                            if (result.isSuccess) box.set(result.getOrNull())
                            latch.countDown()
                        }
                    }
                    val invokeErr: Throwable? = try {
                        method.invoke(api, 0, req, cont)
                        null
                    } catch (t: Throwable) {
                        t
                    }
                    if (invokeErr != null) throw invokeErr
                    if (!latch.await(90, java.util.concurrent.TimeUnit.SECONDS)) {
                        return@runCatching "catalog probe timed out (90s)"
                    }
                    thrown.get()?.let { throw it }
                    box.get()
                } else {
                    val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                        Thread(r, "hikari-probe").apply { isDaemon = true }
                    }
                    try {
                        val fut = exec.submit<Any?> { method.invoke(api, 0, req) }
                        runCatching { fut.get(90, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    } finally {
                        exec.shutdownNow()
                    }
                }
            }
            val res = outcome.getOrNull()
            val summary = res?.let { r ->
                if (r is List<*>) "catalog returned ${r.size} home lists"
                else r.javaClass.methods.firstOrNull {
                    it.name.startsWith("get") && it.returnType == List::class.java
                }?.invoke(r)?.let { "catalog returned ${(it as? List<*>)?.size ?: -1} home lists" }
                    ?: "catalog returned ${r.javaClass.simpleName}"
            } ?: "catalog probe: ${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "?"}: " +
                "${outcome.exceptionOrNull()?.message ?: "no result"}"
            println("probe: $summary")
        } catch (t: Throwable) {
            println("WARN: catalog probe failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
