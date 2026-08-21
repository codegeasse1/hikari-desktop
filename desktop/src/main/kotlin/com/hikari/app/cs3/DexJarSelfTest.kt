package com.hikari.app.cs3

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
}
