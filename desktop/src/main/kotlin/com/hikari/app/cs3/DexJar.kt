package com.hikari.app.cs3

import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Universal dex → JVM loader for CloudStream plugin archives.
 *
 * CloudStream ships its plugins as dex bytecode (.cs3/.hiki = a zip holding
 * `classes.dex` + `manifest.json`). A plain JVM cannot read dex, so bundled
 * bridge plugins (anime, cncverse, phisher, …) always came back empty on
 * desktop. This object translates ANY dex archive into a real JVM jar:
 *
 *   1. extract `classes.dex`,
 *   2. dex2jar converts it to JVM .class files (bytecode-level, so Kotlin
 *      plugins — lambdas, coroutines, inline functions — survive intact),
 *   3. every class is rewritten to class-file version 50 (Java 6): dex2jar
 *      emits Java-8 bytecode with no StackMapTable, which the Java-8+ split
 *      verifier rejects but the JVM's type-inference verifier happily checks,
 *   4. the archive's manifest.json + bundled resources are carried over,
 *   5. the result is cached next to the source and loaded via URLClassLoader.
 *
 * Already-JVM jars pass through unchanged. [lastError] carries the failure
 * reason so the UI can show it.
 */
object DexJar {

    private val cacheDir by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "hikari-dexjars")
        dir.mkdirs()
        dir
    }

    private data class Entry(val jar: File, val stamp: String)
    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    var lastError: String? = null
        private set

    /** True when the archive holds a dex payload (classes.dex). */
    fun isDexArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { z -> z.getEntry("classes.dex") != null }
    }.getOrDefault(false)

    /** Returns a loadable JVM jar URL for [file]: the file itself when it's
     *  already JVM bytecode, otherwise a dex-converted jar. Never throws. */
    fun toJvmJarUrl(file: File): URL {
        val converted = ensureJvmJar(file) ?: return file.toURI().toURL()
        return converted.toURI().toURL()
    }

    /** Converts and caches [file]; returns the original file when it's already
     *  a JVM jar. Null on conversion failure (see [lastError]). */
    fun ensureJvmJar(file: File): File? {
        if (!file.isFile) return null
        if (!isDexArchive(file)) return file
        val stamp = "${file.length()}-${file.lastModified()}"
        val key = file.absolutePath
        cache[key]?.let { if (it.stamp == stamp) return it.jar }
        return runCatching {
            val out = File(cacheDir, "dex-${Integer.toHexString(key.hashCode())}-${file.lastModified()}.jar")
            if (out.isFile) {
                cache[key] = Entry(out, stamp)
                return@runCatching out
            }
            convert(file, out)
            cache[key] = Entry(out, stamp)
            out
        }.getOrElse { e ->
            lastError = "dex conversion failed for ${file.name}: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    private fun convert(archive: File, out: File) {
        val tmp = File(cacheDir, "x-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dexFile = File(tmp, "classes.dex")
            ZipFile(archive).use { z ->
                z.getInputStream(z.getEntry("classes.dex")).use { ins ->
                    dexFile.outputStream().use { ous -> ins.copyTo(ous) }
                }
            }
            val classDir = File(tmp, "classes").apply { mkdirs() }
            com.googlecode.d2j.dex.Dex2jar.from(dexFile)
                .skipDebug(true)
                .topoLogicalSort(false)
                .to(classDir.toPath())

            val finalDir = File(tmp, "final").apply { mkdirs() }
            classDir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val rel = classDir.toPath().relativize(f.toPath()).toString()
                val target = File(finalDir, rel)
                target.parentFile?.mkdirs()
                if (f.extension == "class") {
                    target.writeBytes(downgradeVersion(f))
                } else {
                    f.copyTo(target, overwrite = true)
                }
            }

            // manifest.json + bundled resources (the extracted cs3 assets live
            // under cs3/…; they're only needed by bridge wrappers, but keep the
            // archive faithful anyway).
            ZipFile(archive).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == "classes.dex" || name.startsWith("META-INF/")) continue
                    val target = File(finalDir, name)
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { ins -> target.outputStream().use { ous -> ins.copyTo(ous) } }
                }
            }

            java.util.jar.JarOutputStream(out.outputStream()).use { jos ->
                finalDir.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = finalDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                    jos.putNextEntry(java.util.jar.JarEntry(rel))
                    f.inputStream().use { it.copyTo(jos) }
                    jos.closeEntry()
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Re-emits the class at version 50 so the JVM's type-inference verifier
     *  accepts it (dex2jar's Java-8 output has no StackMapTable frames). */
    private fun downgradeVersion(f: File): ByteArray {
        val reader = org.objectweb.asm.ClassReader(f.readBytes())
        val writer = org.objectweb.asm.ClassWriter(0)
        reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                super.visit(org.objectweb.asm.Opcodes.V1_6, access, name, signature, superName, interfaces)
            }
        }, 0)
        return writer.toByteArray()
    }
}
