package com.hikari.app.cs3

import com.hikari.app.net.Http
import java.io.File

/**
 * CI gate for REAL third-party CloudStream extensions.
 *
 * [DexJarSelfTest] proves the dex→JVM pipeline with the app's own bundled
 * plugins. Those are written by us and load cleanly, which is why they never
 * caught the failure this test exists for: a plugin from a public repo whose
 * `load()` reaches into the Android UI framework. The dex→jar conversion
 * succeeds, the plugin class instantiates, and then the JVM's verifier cannot
 * resolve `androidx.appcompat.app.AppCompatActivity` (or a widget the plugin's
 * own settings/donation screen builds) — so a perfectly good provider is
 * reported as "couldn't load" and the user gets no extension.
 *
 * The plugin URLs below are the ones users actually install from public repos
 * (the CNCVerse repo is the one that reported the failure), so a regression in
 * the Android stand-ins — or a new one the shims don't cover yet — fails the
 * build instead of shipping. Timing is printed for each stage (download,
 * dex→jar, load) because install latency is a user-visible feature too.
 */
object RealPluginSelfTest {

    private val PLUGINS = listOf(
        "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/CastleTvProvider.cs3",
        "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/CineTvProvider.cs3",
    )

    private val REPOS = listOf(
        "https://github.com/codegeasse1/hikari-extensions",
        "https://github.com/codegeasse1/codegeasse-cloudstream-repos",
        "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        val app = com.hikari.app.HikariApp()
        app.init()
        val dir = File(System.getProperty("java.io.tmpdir"), "hikari-real-plugins").apply { mkdirs() }

        for (url in PLUGINS) {
            val name = url.substringAfterLast('/')
            println("RealPluginSelfTest: $name")
            try {
                val t0 = System.currentTimeMillis()
                val bytes = download(url)
                if (bytes == null) {
                    println("FAIL $name: download failed")
                    failures++
                    continue
                }
                val file = File(dir, name)
                file.writeBytes(bytes)
                val tDownload = System.currentTimeMillis()
                val converted = DexJar.ensureJvmJar(file)
                val tConvert = System.currentTimeMillis()
                if (converted == null) {
                    println("FAIL $name: dex conversion failed: ${DexJar.lastError}")
                    failures++
                    continue
                }
                val apis = Cs3PluginManager.reload(app, file)
                val tLoad = System.currentTimeMillis()
                println(
                    "  $name: ${bytes.size}B download=${tDownload - t0}ms " +
                        "convert=${tConvert - tDownload}ms load=${tLoad - tConvert}ms " +
                        "providers=${apis.size}",
                )
                Cs3PluginManager.lastWarning?.let {
                    println("  warning: ${it.replace('\n', ' ').take(300)}")
                }
                if (apis.isEmpty()) {
                    println("FAIL $name: registered no providers: ${Cs3PluginManager.lastError}")
                    failures++
                } else {
                    apis.forEach { api -> println("  provider: ${api.name} (${api.javaClass.name})") }
                }
            } catch (t: Throwable) {
                println("FAIL $name: ${t.javaClass.simpleName}: ${t.message}")
                failures++
            }
        }

        for (repo in REPOS) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { Http.fetchRepoJson(repo) }
            val ms = System.currentTimeMillis() - t0
            val pair = r.getOrNull()?.getOrNull()
            if (pair != null) {
                println("  repo.json: ${pair.second.length}B in ${ms}ms via ${pair.first}")
            } else {
                val why = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "no candidate served a repo.json"
                println("WARN repo.json fetch failed in ${ms}ms: $why")
            }
        }

        if (failures > 0) {
            println("RealPluginSelfTest: FAILED ($failures failure(s))")
            System.exit(1)
        }
        println("RealPluginSelfTest: OK")
    }

    private fun download(url: String): ByteArray? {
        runCatching { Http.fetchBytesRobust(url) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        return runCatching { java.net.URI.create(url).toURL().openStream().use { it.readBytes() } }.getOrNull()
    }
}
