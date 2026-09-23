package com.hikari.app.cs3

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
 *   3. every class has its class-file version rewritten to 50 (Java 6): dex2jar
 *      emits Java-8 bytecode with no StackMapTable, which the Java-8+ split
 *      verifier rejects but the JVM's type-inference verifier happily checks,
 *   4. the archive's manifest.json + bundled resources are carried over,
 *   5. the result is cached under a hash of the CONTENT and loaded via
 *      URLClassLoader.
 *
 * Already-JVM jars pass through unchanged. [lastError] carries the failure
 * reason so the UI can show it.
 *
 * ── why this file is shaped the way it is ─────────────────────────────────
 *
 * Translation is the single slowest thing an extension install does — measured
 * on CI at 4.3 s for one 88 KB `.cs3` (an 88 KB archive holds a ~225 KB dex),
 * and it is CPU work that grows with the plugin. It used to run on the visible
 * path of every install, which is exactly the "installing an extension takes a
 * minute" the user reported. So the work here is:
 *
 *  - done ONCE per distinct content ([contentDigest]), not once per file: a
 *    reinstall writes the same bytes to a new file with a new mtime, and the old
 *    path+mtime key made that a full re-translation,
 *  - written in as few passes as possible (dex2jar writes one zip; the classes
 *    are copied straight into the output jar, with the version patched at its
 *    fixed offset in the header rather than re-parsed by ASM),
 *  - serialised per content, so an install and the first catalog fetch that uses
 *    the extension cannot both translate the same dex.
 */
object DexJar {

    /**
     * Where converted jars are kept between runs.
     *
     * `~/.hikari/dexjars` rather than the system temp directory: this is the
     * expensive result, and a temp cleaner that deletes it means a plugin that
     * has to be translated again. The temp directory stays as the fallback for a
     * machine whose home directory cannot be written to.
     */
    private val cacheDir: File by lazy {
        val candidates = ArrayList<File>()
        val home = System.getProperty("user.home").orEmpty()
        if (home.isNotBlank()) candidates.add(File(File(home, ".hikari"), "dexjars"))
        candidates.add(File(System.getProperty("java.io.tmpdir"), "hikari-dexjars"))
        for (dir in candidates) {
            runCatching { dir.mkdirs() }
            if (dir.isDirectory && dir.canWrite()) return@lazy dir
        }
        candidates.last()
    }

    private data class Entry(val jar: File, val stamp: String)

    /** Source file path → its converted jar and the content stamp it was made from. */
    private val cache = ConcurrentHashMap<String, Entry>()

    /** One lock per distinct content, so two callers cannot translate the same
     *  dex at the same time (see [ensureJvmJar]). */
    private val locks = ConcurrentHashMap<String, Any>()

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

    /** [toJvmJarUrl] that reports failure instead of silently falling back to
     *  the dex archive (which a URLClassLoader can open but whose classes can
     *  never load — the confusing ClassNotFoundException that used to be shown
     *  as the install error). */
    fun toJvmJarUrlOrNull(file: File): URL? =
        if (isDexArchive(file)) ensureJvmJar(file)?.toURI()?.toURL() else file.toURI().toURL()

    /** Converts and caches [file]; returns the original file when it's already
     *  a JVM jar. Null on conversion failure (see [lastError]). */
    fun ensureJvmJar(file: File): File? {
        if (!file.isFile) return null
        if (!isDexArchive(file)) return file
        val key = file.absolutePath
        // The identity of the WORK is the bytes, not the path: reinstalling an
        // extension writes the same archive to the same name with a new mtime,
        // and keying on the path+mtime made that a whole re-translation.
        val stamp = contentDigest(file) ?: ("len:" + file.length())
        cache[key]?.let { if (it.stamp == stamp && it.jar.isFile && it.jar.length() > 0L) return it.jar }
        val lock = locks.computeIfAbsent(stamp) { Any() }
        synchronized(lock) {
            cache[key]?.let { if (it.stamp == stamp && it.jar.isFile && it.jar.length() > 0L) return it.jar }
            val out = File(cacheDir, "dex-" + stamp.take(24) + ".jar")
            if (out.isFile && out.length() > 0L) {
                cache[key] = Entry(out, stamp)
                return out
            }
            // Convert to a temp name and rename: a jar that is only half written
            // (the app was closed mid-conversion, the process was killed) must
            // never be cached as the real thing, or that plugin stays broken
            // forever with a "manifest missing" that makes no sense.
            return runCatching {
                val tmp = File(cacheDir, out.name + ".part-" + System.nanoTime())
                try {
                    convert(file, tmp)
                    if (!tmp.isFile || tmp.length() == 0L) {
                        throw IllegalStateException("conversion produced an empty jar")
                    }
                    out.delete()
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                    }
                } finally {
                    runCatching { tmp.delete() }
                }
                cache[key] = Entry(out, stamp)
                out
            }.getOrElse { e ->
                lastError = "dex conversion failed for ${file.name}: ${e.javaClass.simpleName}: ${e.message}"
                null
            }
        }
    }

    /**
     * Translates one dex archive into a JVM jar at [out].
     *
     * Three passes become one: dex2jar is pointed at a ZIP target (it writes a
     * zip whenever the target is not an existing directory — see
     * [com.googlecode.d2j.dex.Dex2jar.to]), and the classes are streamed from
     * that zip straight into the output jar, with the class-file version patched
     * in place. There is no intermediate directory of `.class` files, no second
     * walk over it, and no ASM round trip per class.
     */
    private fun convert(archive: File, out: File) {
        val work = File(cacheDir, "work-" + System.nanoTime() + ".zip")
        val dex = File(cacheDir, "work-" + System.nanoTime() + ".dex")
        try {
            ZipFile(archive).use { z ->
                val entry = z.getEntry("classes.dex")
                    ?: throw IllegalStateException("no classes.dex in ${archive.name}")
                z.getInputStream(entry).use { ins ->
                    dex.outputStream().use { ous -> ins.copyTo(ous) }
                }
            }
            com.googlecode.d2j.dex.Dex2jar.from(dex)
                .skipDebug(true)
                .topoLogicalSort(false)
                .to(work.toPath())

            JarOutputStream(BufferedOutputStream(out.outputStream(), 1 shl 16)).use { jos ->
                // 1. the translated classes.
                ZipFile(work).use { z ->
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val name = e.name
                        if (name.startsWith("META-INF/")) continue
                        val bytes = z.getInputStream(e).use { it.readBytes() }
                        if (name.endsWith(".class")) patchVersion(bytes)
                        jos.putNextEntry(JarEntry(name))
                        jos.write(bytes)
                        jos.closeEntry()
                    }
                }
                // 2. manifest.json + bundled resources (the extracted cs3 assets
                //    live under cs3/…; they're only needed by bridge wrappers,
                //    but keep the archive faithful anyway).
                ZipFile(archive).use { z ->
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val name = e.name
                        if (name == "classes.dex" || name.startsWith("META-INF/")) continue
                        jos.putNextEntry(JarEntry(name))
                        z.getInputStream(e).use { ins -> ins.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
            }
        } finally {
            runCatching { work.delete() }
            runCatching { dex.delete() }
        }
    }

    /**
     * Rewrites a class file's version to 50 (Java 6), in place.
     *
     * dex2jar emits Java-8 class files with no StackMapTable, which the Java-8+
     * split verifier rejects; version 50 is the last one the JVM verifies by
     * type inference, and it accepts them. The version sits at a fixed offset in
     * the class-file header — minor at bytes 4..5, major at 6..7 — so this is
     * the whole job. It used to be an ASM ClassReader→ClassWriter round trip per
     * class, which re-parsed and re-encoded every one of them to change two
     * bytes.
     */
    private fun patchVersion(bytes: ByteArray) {
        if (bytes.size < 8) return
        if (bytes[0] != 0xCA.toByte() || bytes[1] != 0xFE.toByte() ||
            bytes[2] != 0xBA.toByte() || bytes[3] != 0xBE.toByte()
        ) {
            return
        }
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 50
    }

    /** sha256 of a file's bytes, hex. Null when the file cannot be read. */
    private fun contentDigest(file: File): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream(), 1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
