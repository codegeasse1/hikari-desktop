package com.hikari.app.data

import android.content.Context
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.net.ExtensionVerifyGuard
import com.hikari.app.net.Http
import com.hikari.app.net.NetTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Desktop BackupManager — one-to-one behavior and file format with Android.
 *
 * The backup JSON is byte-compatible with Android's: same `format`/`version`,
 * same `prefs` records, same `refs`/`files`/`profiles` sections, same
 * CloudStream-import path — a backup made on one app restores on the other.
 *
 * Desktop mechanics: Android's `android.util.JsonReader/JsonWriter` streaming
 * pair becomes a small built-in writer ([MiniJsonWriter]) plus a parse-once
 * `JSONObject` restore (backups are kilobytes-to-few-MB; the walk still streams
 * one file at a time on the way out). `android.util.Base64` becomes
 * `java.util.Base64`. Downloads use [Http.fetchBytesRobust]. Deferred: no
 * `MetaCache` epoch bump on the desktop yet (no MetaCache), no i18n
 * `LanguageManager` apply (no language UI yet), and "save to Downloads" writes
 * to the OS Downloads folder instead of MediaStore.
 */
object BackupManager {

    /** Marks a file as ours — a restore refuses anything without this. */
    private const val FORMAT = "hikari-backup"

    /** Bumped only for a change a previous version cannot read. A restore of a
     *  NEWER format is refused by name instead of silently restoring half of
     *  it. */
    private const val FORMAT_VERSION = 1

    private val FILE_ROOTS = listOf(
        "cs3", "hiki",
        "nuvio/scrapers", "nuvio/settings",
        "manga/exts", "aniyomi/exts",
        "anymex/modules", "lnreader/plugins",
        "skystream/plugins", "sora/modules",
        "vega/providers", "zangetsu/providers",
    )

    /** Nothing legitimate in those directories is anywhere near this big; the
     *  cap keeps a corrupt/hostile file from being expanded into the app's
     *  storage. */
    private const val MAX_FILE_BYTES = 12L * 1024L * 1024L

    /** A hostile file could list references forever; the cap stops that from
     *  becoming thousands of downloads. A real set is hundreds. */
    private const val MAX_REFS = 5000

    data class Report(
        val ok: Boolean,
        /** One line for the user ("Restored." / "That file is not a Hikari backup."). */
        val message: String,
        /** Counts and the first failure, for the status line and the log. */
        val detail: String = "",
    )

    /** `hikari-backup-2026-09-17-1318.json` — sorted by name = sorted by date. */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        "hikari-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".json"

    // ------------------------------------------------------------- backup --

    /** Builds the backup. Runs the file walk on IO; throws only on something
     *  genuinely broken (the caller reports it). */
    suspend fun export(app: HikariApp): ByteArray = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "outbox/backup-tmp.json")
        exportToFile(app, tmp)
        try {
            tmp.readBytes()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    suspend fun exportToFile(app: HikariApp, dest: File): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        java.io.FileOutputStream(tmp).buffered(32 * 1024).use { raw ->
            writeBackupJson(app, raw, onProgress = null)
        }
        if (dest.exists()) dest.delete()
        // renameTo silently returns false across filesystems and on odd OEMs —
        // an unchecked failure used to hand the saver an empty/missing file.
        if (!tmp.renameTo(dest)) {
            tmp.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            runCatching { tmp.delete() }
        }
        dest.length()
    }

    suspend fun exportToStream(
        app: HikariApp,
        dest: OutputStream,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        val counting = CountingOutputStream(dest.buffered(64 * 1024))
        counting.use { writeBackupJson(app, it, onProgress) }
        counting.count
    }

    /** Counts what passes through, so the streamed export can report its size
     *  without a second pass over the bytes. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            out.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /** Minimal JSON writer with the same call shape Android's JsonWriter gives
     *  the backup code (begin/end object/array, name, value, nullValue), so
     *  the writer below stays line-for-line with Android's. */
    private class MiniJsonWriter(private val out: Writer) : Closeable {
        private val stack = ArrayDeque<Boolean>()
        private var pendingName: String? = null

        fun setIndent(@Suppress("UNUSED_PARAMETER") indent: String) = Unit

        private fun beforeValue() {
            val name = pendingName
            if (name != null) {
                pendingName = null
                if (stack.isNotEmpty() && !stack.last()) out.write(",")
                out.write(JSONObject.quote(name))
                out.write(":")
                stack[stack.size - 1] = false
            } else if (stack.isNotEmpty()) {
                if (!stack.last()) out.write(",")
                stack[stack.size - 1] = false
            }
        }

        fun beginObject(): MiniJsonWriter {
            beforeValue()
            out.write("{")
            stack.addLast(true)
            return this
        }

        fun endObject(): MiniJsonWriter {
            out.write("}")
            if (stack.isNotEmpty()) stack.removeLast()
            return this
        }

        fun beginArray(): MiniJsonWriter {
            beforeValue()
            out.write("[")
            stack.addLast(true)
            return this
        }

        fun endArray(): MiniJsonWriter {
            out.write("]")
            if (stack.isNotEmpty()) stack.removeLast()
            return this
        }

        fun name(n: String): MiniJsonWriter {
            pendingName = n
            return this
        }

        fun nullValue(): MiniJsonWriter {
            beforeValue()
            out.write("null")
            return this
        }

        fun value(v: String?): MiniJsonWriter {
            if (v == null) return nullValue()
            beforeValue()
            out.write(JSONObject.quote(v))
            return this
        }

        fun value(v: Boolean): MiniJsonWriter {
            beforeValue()
            out.write(if (v) "true" else "false")
            return this
        }

        fun value(v: Long): MiniJsonWriter {
            beforeValue()
            out.write(v.toString())
            return this
        }

        fun value(v: Double): MiniJsonWriter {
            beforeValue()
            out.write(v.toString())
            return this
        }

        fun value(v: Number): MiniJsonWriter {
            beforeValue()
            out.write(v.toString())
            return this
        }

        override fun close() {
            out.flush()
            out.close()
        }
    }

    private suspend fun writeBackupJson(
        app: HikariApp,
        raw: OutputStream,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ) {
        MiniJsonWriter(raw.writer(Charsets.UTF_8)).use { w ->
            w.setIndent("")
            w.beginObject()
            w.name("format").value(FORMAT)
            w.name("version").value(FORMAT_VERSION.toLong())
            w.name("app").value(BuildConfig.VERSION_NAME)
            w.name("code").value(BuildConfig.VERSION_CODE.toLong())
            w.name("createdAt").value(System.currentTimeMillis())
            w.name("prefs").beginArray()
            for (r in app.store.snapshotPreferences()) {
                w.beginObject()
                w.name("key").value(r.key)
                w.name("type").value(r.type)
                w.name("value")
                when (r.type) {
                    "s" -> if (r.value == null) w.nullValue() else w.value(r.value as String)
                    "b" -> if (r.value == null) w.nullValue() else w.value((r.value as Boolean))
                    "i", "l" -> if (r.value == null) w.nullValue() else w.value((r.value as Number).toLong())
                    "f", "d" -> if (r.value == null) w.nullValue() else {
                        val d = (r.value as Number).toDouble()
                        if (d.isFinite()) w.value(d) else w.nullValue()
                    }
                    "ss" -> {
                        val list = r.value as? List<*> ?: (r.value as? Set<*>)?.toList()
                        if (list == null) {
                            w.nullValue()
                        } else {
                            w.beginArray()
                            for (s in list) w.value(s?.toString())
                            w.endArray()
                        }
                    }
                    "bin" -> if (r.value == null) w.nullValue() else w.value(r.value as String)
                    else -> w.nullValue()
                }
                w.endObject()
            }
            w.endArray()
            // Extension files used to be embedded below as base64 — with a
            // few hundred installed that single array reached ~235 MB. A file
            // the app can fetch again (every repo-installed extension: its
            // provider row remembers the source URL — see
            // [collectExtensionRefs]) now travels as a small {path, url}
            // reference instead, and only files with no known source
            // (sideloaded) still carry bytes. A typical backup is kilobytes
            // again; the restore re-downloads the references.
            val rels = collectFileRels(app.filesDir)
            val refUrls = collectExtensionRefs(app)
            val refEntries = ArrayList<Pair<String, String>>()
            val embedRels = ArrayList<String>()
            for (rel in rels) {
                val u = refUrls[rel]
                if (u != null) refEntries.add(rel to u) else embedRels.add(rel)
            }
            w.name("refs").beginArray()
            for ((rel, u) in refEntries) {
                w.beginObject()
                w.name("path").value(rel)
                w.name("url").value(u)
                w.endObject()
            }
            w.endArray()
            w.name("files").beginArray()
            writeBackupFiles(app, w, onProgress, embedRels)
            w.endArray()
            w.name("profiles").beginObject()
            val profiles = exportProfiles(app.filesDir)
            val profileNames = runCatching {
                val list = ArrayList<String>()
                val keys = profiles.keys()
                while (keys.hasNext()) list.add(keys.next() as String)
                list
            }.getOrDefault(emptyList())
            for (name in profileNames) {
                val obj = runCatching { JSONObject(profiles.optString(name)) }.getOrNull()
                if (obj == null) continue
                w.name(name)
                writeJsonObject(w, obj)
            }
            w.endObject()
            w.endObject()
        }
    }

    private const val BACKUP_ENCODE_PARALLEL = 16

    private suspend fun writeBackupFiles(
        app: HikariApp,
        w: MiniJsonWriter,
        onProgress: ((done: Int, total: Int) -> Unit)?,
        rels: List<String>,
    ) {
        if (rels.isEmpty()) {
            onProgress?.invoke(0, 0)
            return
        }
        var done = 0
        onProgress?.invoke(0, rels.size)
        for (chunk in rels.chunked(BACKUP_ENCODE_PARALLEL)) {
            val encoded = coroutineScope {
                chunk.map { rel -> async { rel to encodeBackupFile(app.filesDir, rel) } }.awaitAll()
            }
            for ((rel, b64) in encoded) {
                done++
                if (b64 == null) continue
                w.beginObject()
                w.name("path").value(rel)
                w.name("data").value(b64)
                w.endObject()
            }
            onProgress?.invoke(done, rels.size)
        }
        onProgress?.invoke(done, rels.size)
    }

    /** One extension file as base64, or null when it cannot be read (a locked
     *  or half-written file must not cost the user their settings). */
    private fun encodeBackupFile(filesDir: File, rel: String): String? {
        val bytes = runCatching { File(filesDir, rel).readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun writeJsonValue(w: MiniJsonWriter, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> w.nullValue()
            is JSONObject -> writeJsonObject(w, v)
            is JSONArray -> {
                w.beginArray()
                for (i in 0 until v.length()) writeJsonValue(w, v.opt(i))
                w.endArray()
            }
            is Boolean -> w.value(v)
            is Number -> w.value(v)
            else -> w.value(v.toString())
        }
    }

    private fun writeJsonObject(w: MiniJsonWriter, o: JSONObject) {
        w.beginObject()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next() as String
            w.name(k)
            writeJsonValue(w, o.opt(k))
        }
        w.endObject()
    }

    fun readCapped(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** Extension-file paths (relative) for the backup walk — names only, so the
     *  writer can stream one file at a time (see [exportToFile]). */
    /** File names that are never user data: partial writes, logs and hidden
     *  bookkeeping that only cost backup time (a 1300-file walk is dominated
     *  by these, not by extensions). Skipped, never restored. */
    private fun isBackupTransient(name: String): Boolean {
        if (name.startsWith(".")) return true
        val low = name.lowercase()
        return low.endsWith(".tmp") || low.endsWith(".part") || low.endsWith(".bak") ||
            low.endsWith(".log") || low.endsWith(".temp") || name == "kv.json-journal"
    }

    private fun collectFileRels(filesDir: File): List<String> {
        val out = ArrayList<String>()
        for (relRoot in FILE_ROOTS) {
            val dir = File(filesDir, relRoot)
            if (!dir.isDirectory) continue
            val walk = ArrayDeque<File>()
            walk.addLast(dir)
            while (walk.isNotEmpty()) {
                val d = walk.removeFirst()
                val children = runCatching { d.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        val dn = child.name
                        if (dn.startsWith(".") || dn == "__pycache__") continue
                        walk.addLast(child)
                        continue
                    }
                    if (isBackupTransient(child.name)) continue
                    if (child.length() <= 0L || child.length() > MAX_FILE_BYTES) continue
                    out.add(child.relativeTo(filesDir).invariantSeparatorsPath)
                }
            }
        }
        out.sort()
        return out
    }

    private suspend fun collectExtensionRefs(app: HikariApp): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val providers = runCatching { app.store.providers() }.getOrDefault(emptyList())
        for (p in providers) {
            if (p.type != ProviderType.CS3 && p.type != ProviderType.HIKARI &&
                p.type != ProviderType.NUVIO && p.type != ProviderType.MANGA &&
                p.type != ProviderType.ANIYOMI && p.type != ProviderType.ANYMEX &&
                p.type != ProviderType.ANYMEX_MANGA
            ) continue
            val url = runCatching { app.store.sourceUrlOf(p) }.getOrDefault("")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (p.url.isBlank()) continue
            if (!File(p.url).isFile) continue
            val rel = runCatching {
                File(p.url).relativeTo(app.filesDir).invariantSeparatorsPath
            }.getOrNull() ?: continue
            if (!allowedPath(rel)) continue
            if (rel == "nuvio/settings" || rel.startsWith("nuvio/settings/")) continue
            // Several rows share one file (a .cs3 holds many providers, a
            // .hiki holds a bundle): first wins, the rest add nothing.
            out.putIfAbsent(rel, url)
        }
        return out
    }

    private fun exportProfiles(filesDir: File): JSONObject {
        val out = JSONObject()
        val dir = File(filesDir, "profiles")
        if (!dir.isDirectory) return out
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return out
        for (f in kids) {
            if (!f.isFile || f.length() <= 0L || f.length() > 4L * 1024L * 1024L) continue
            val name = f.name
            if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            // Must still be JSON — a half-written snapshot must not poison a backup.
            if (runCatching { JSONObject(text) }.getOrNull() == null) continue
            out.put(name, text)
        }
        return out
    }

    private val PROF_SNAP_RE = Regex("^[A-Za-z0-9_-]{1,64}\\.json$")

    private fun restoreProfiles(filesDir: File, obj: JSONObject?): String {
        if (obj == null) return ""
        val dir = File(filesDir, "profiles").apply { mkdirs() }
        var n = 0
        for (raw in obj.keys()) {
            val name = raw as? String ?: continue
            if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) continue
            val text = obj.optString(name).takeIf { it.isNotBlank() } ?: continue
            if (runCatching { JSONObject(text) }.getOrNull() == null) continue
            if (runCatching { File(dir, name).writeText(text) }.isFailure) continue
            if (name != "registry.json") n++
        }
        // The registry names every profile the snapshots hold: without it the
        // snapshots are unreachable, so it travels or nothing does.
        if (!File(dir, "registry.json").isFile) return ""
        return if (n == 1) "1 profile" else n.toString() + " profiles"
    }


    // ------------------------------------------------------------ restore --

    suspend fun restore(app: HikariApp, bytes: ByteArray): Report =
        restoreStream(app, ByteArrayInputStream(bytes))

    suspend fun restoreStream(app: HikariApp, input: InputStream): Report = withContext(Dispatchers.IO) {
        // Desktop restore parses the same JSON the writer emits (see
        // [writeBackupJson]) in one pass instead of Android's streaming
        // JsonReader — same sections, same validation, same refusal rules.
        val bytes = readCapped(input, 512L * 1024L * 1024L)
            ?: return@withContext Report(false, "That file is not a Hikari backup.", "file too large")
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return@withContext Report(false, "Could not read that file.")
        val root = runCatching {
            JSONObject(text.trimStart('\uFEFF', ' ', '\n', '\r', '\t'))
        }.getOrNull()
            ?: return@withContext Report(false, "That file is not a Hikari backup.")
        val format: String? = root.optString("format").takeIf { it.isNotBlank() }
        val version = root.optInt("version", -1)
        val fromApp = root.optString("app").orEmpty()
        val fromCode = root.optInt("code", 0)
        val prefsArr = root.optJSONArray("prefs")
        val refsArr = root.optJSONArray("refs")
        val filesArr = root.optJSONArray("files")
        val profilesObj = root.optJSONObject("profiles")
        val sawSections = prefsArr != null || refsArr != null || filesArr != null || profilesObj != null
        val records = ArrayList<PrefRecord>()
        var deviceLocal = 0
        var skipped = 0
        var identical = 0
        val staged = ArrayList<Pair<File, File>>()
        val profileTexts = HashMap<String, String>()
        val refs = ArrayList<Pair<String, String>>()
        fun cleanup() {
            for ((_, tmp) in staged) runCatching { tmp.delete() }
            staged.clear()
        }
        if (prefsArr != null) {
            for (i in 0 until prefsArr.length()) {
                val o = prefsArr.optJSONObject(i) ?: continue
                val key = o.optString("key").orEmpty()
                if (key.isBlank()) continue
                val type = o.optString("type").orEmpty()
                val value: Any? = if (o.isNull("value")) {
                    null
                } else when (type) {
                    "ss" -> {
                        val arr = o.optJSONArray("value")
                        if (arr == null) null else ArrayList<String>(arr.length()).also { list ->
                            for (j in 0 until arr.length()) {
                                list.add(if (arr.isNull(j)) "" else arr.optString(j))
                            }
                        }
                    }
                    "i", "l" -> when (val v = o.opt("value")) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
                        is Boolean -> if (v) 1L else 0L
                        else -> null
                    }
                    "f", "d" -> when (val v = o.opt("value")) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull()
                        else -> null
                    }
                    "b" -> when (val v = o.opt("value")) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> when (v.trim().trim('"').lowercase()) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        }
                        else -> null
                    }
                    else -> o.optString("value", null)
                }
                if (DeviceLocal.contains(key)) {
                    deviceLocal++
                    continue
                }
                records.add(PrefRecord(key, type, value))
            }
        }
        if (refsArr != null) {
            // Repo-installed extensions travel as {path, url} references, not
            // bytes (see the writer above). An older Hikari never wrote this
            // section and a newer one reads old files without it — both
            // directions stay compatible because unknown sections are skipped
            // by the other side.
            for (i in 0 until refsArr.length()) {
                val o = refsArr.optJSONObject(i) ?: continue
                val rel = o.optString("path").orEmpty()
                val u = o.optString("url", null)?.trim().orEmpty()
                if (!allowedPath(rel) ||
                    (!u.startsWith("http://") && !u.startsWith("https://")) ||
                    refs.size >= MAX_REFS
                ) {
                    skipped++
                    continue
                }
                refs.add(rel to u)
            }
        }
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) {
                val o = filesArr.optJSONObject(i) ?: continue
                val rel = o.optString("path").orEmpty()
                val data = if (o.isNull("data")) null else o.optString("data", null)
                if (!allowedPath(rel)) {
                    skipped++
                    continue
                }
                val decoded = runCatching {
                    if (data.isNullOrEmpty()) null
                    else java.util.Base64.getDecoder().decode(data)
                }.getOrNull()
                if (decoded == null || decoded.isEmpty() || decoded.size.toLong() > MAX_FILE_BYTES) {
                    skipped++
                    continue
                }
                val target = File(app.filesDir, rel)
                if (target.isFile && target.length() == decoded.size.toLong() &&
                    runCatching { target.readBytes().contentEquals(decoded) }.getOrDefault(false)
                ) {
                    identical++
                    continue
                }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".restore-part")
                if (runCatching { tmp.writeBytes(decoded) }.isFailure) {
                    runCatching { tmp.delete() }
                    skipped++
                    continue
                }
                staged.add(target to tmp)
            }
        }
        if (profilesObj != null) {
            val names = profilesObj.keys()
            while (names.hasNext()) {
                val name = names.next() as? String ?: continue
                if (name != "registry.json" && !PROF_SNAP_RE.matches(name)) continue
                val obj = profilesObj.optJSONObject(name)
                    ?: runCatching { JSONObject(profilesObj.optString(name)) }.getOrNull()
                    ?: continue
                profileTexts[name] = obj.toString()
            }
        }
        val legacy = format.isNullOrBlank() && sawSections
        if (!format.isNullOrBlank() && format != FORMAT && !legacy) {
            cleanup()
            return@withContext Report(false, "That file is not a Hikari backup.")
        }
        if (!sawSections) {
            cleanup()
            return@withContext Report(false, "That file is not a Hikari backup.")
        }
        if (version > FORMAT_VERSION) {
            cleanup()
            return@withContext Report(
                false,
                "That backup was made by a newer Hikari (format $version). Update the app first.",
            )
        }
        var applied = 0
        runCatching { applied = app.store.restorePreferences(records) }.onFailure {
            cleanup()
            return@withContext Report(false, "Could not restore your settings.", it.message.orEmpty())
        }
        if (deviceLocal > 0) {
            Logs.log(
                "Backup",
                "kept $deviceLocal device-local setting(s) of this device's own (app lock / layout)",
            )
        }
        val fromName = fromApp.ifBlank { "another Hikari" }
        var written = 0
        for ((target, tmp) in staged) {
            val ok = runCatching {
                if (target.exists() && !target.delete()) false else tmp.renameTo(target)
            }.getOrDefault(false)
            if (ok) written++ else {
                runCatching { tmp.delete() }
                skipped++
            }
        }
        staged.clear()
        val profilesObj2 = JSONObject().apply {
            for ((k, v) in profileTexts) put(k, v)
        }
        val profilesLine = restoreProfiles(app.filesDir, profilesObj2)
        var refTotal = 0
        if (refs.isNotEmpty()) {
            refTotal = refs.size
            // The references resolve in the background (bounded, best-effort
            // — one dead host must not fail the restore) and the providers
            // reload once the pass ends. The restore itself already reported
            // by then; the log carries the outcome.
            refreshScope.launch {
                var ok = 0
                for (chunk in refs.chunked(6)) {
                    val done = coroutineScope {
                        chunk.map { (rel, url) -> async { downloadRefFile(app, rel, url) } }.awaitAll()
                    }
                    ok += done.count { it }
                }
                // Desktop has no MetaCache epoch to bump yet (no MetaCache);
                // the providers reload is what picks up fetched builds.
                runCatching { app.providers.refresh() }
                Logs.log("Backup", "background extension fetch — $ok/${refs.size} restored")
            }
        }
        if (written > 0 || refTotal > 0) {
            refreshLiveState(app, deferProviders = true)
        } else {
            refreshLiveState(app)
        }
        val detail = "settings: $applied" +
            (if (profilesLine.isNotBlank()) " · " + profilesLine else "") +
            (if (deviceLocal > 0) " · device-local kept: $deviceLocal" else "") +
            " · files: $written" +
            (if (identical > 0) " · unchanged: $identical" else "") +
            (if (skipped > 0) " · skipped: $skipped" else "") +
            (if (refTotal > 0) " · extensions: $refTotal downloading" else "") +
            " · from $fromName $fromCode"
        Logs.log("Backup", "restore ok — $detail")
        Report(
            true,
            when {
                refTotal > 0 -> "Restored. Your sources are downloading."
                written > 0 -> "Restored. Your sources are reloading."
                else -> "Restored your settings."
            },
            detail,
        )
    }

    private suspend fun downloadRefFile(app: HikariApp, rel: String, url: String): Boolean {
        val fetched: ByteArray? = runCatching { Http.fetchBytesRobust(url) }.getOrNull()
        if (fetched == null || fetched.isEmpty() || fetched.size.toLong() > MAX_FILE_BYTES) {
            Logs.log("Backup", "extension refetch failed for $rel (no answer)")
            return false
        }
        val bytes: ByteArray = fetched
        val target = File(app.filesDir, rel)
        // Already there with identical bytes (a re-restore onto the same set):
        // skip the write like the embedded path does.
        if (target.isFile && target.length() == bytes.size.toLong() &&
            runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)
        ) return true
        return runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".restore-part")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) {
                true
            } else {
                // renameTo silently fails across filesystems / odd OEMs.
                tmp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                runCatching { tmp.delete() }
                target.isFile
            }
        }.getOrDefault(false).also { ok ->
            if (!ok) Logs.log("Backup", "extension refetch failed for $rel (write)")
        }
    }

    // ------------------------------------------- CloudStream backup import --

    /** The key CloudStream's own backup/restore round-trips its repo list under. */
    private const val CS_REPOS_KEY = "REPOSITORIES_KEY"

    /** The typed maps a CloudStream DataStore dump is made of. Their presence is
     *  what identifies the file as a CloudStream backup. */
    private val CS_MAP_TYPES = listOf("_String", "_Int", "_Bool", "_Long", "_Float", "_StringSet")

    /** Nothing sane reaches this; the cap stops a hand-edited file from turning
     *  into thousands of DataStore writes. */
    private const val CS_MAX_REPOS = 500

    suspend fun restoreCloudStream(app: HikariApp, bytes: ByteArray): Report =
        withContext(Dispatchers.IO) {
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                ?: return@withContext Report(false, "Could not read that file.")
            val root = runCatching {
                JSONObject(text.trimStart('\uFEFF', ' ', '\n', '\r', '\t'))
            }.getOrNull()
                ?: return@withContext Report(false, "That file is not a CloudStream backup.")
            if (!looksLikeCloudStream(root)) {
                return@withContext Report(false, "That file is not a CloudStream backup.")
            }

            val repos = cloudStreamRepos(root)
            if (repos.isEmpty()) {
                return@withContext Report(
                    false,
                    "No repositories found in that CloudStream backup.",
                    "Nothing to import — the file has no repo list in it.",
                )
            }

            val known = runCatching { app.store.repos() }.getOrDefault(emptyList())
                .mapTo(HashSet()) { normRepoUrl(it.url) }
            var added = 0
            var duplicates = 0
            for (repo in repos) {
                val key = normRepoUrl(repo.url)
                if (key.isBlank() || !known.add(key)) {
                    duplicates++
                    continue
                }
                if (runCatching { app.store.addCs3Repo(repo) }.isSuccess) added++ else duplicates++
            }
            if (added > 0) {
                // The repo list is live in the store, but the plugin lists
                // behind each repo are what the Extensions screen shows.
                runCatching { app.providers.refresh() }
            }

            val detail = "cloudstream: found ${repos.size}, added $added, already present $duplicates"
            Logs.log("Backup", "cloudstream restore — $detail")
            Report(
                true,
                when {
                    added == 0 -> "Those ${repos.size} repositories are already in your list."
                    added == 1 -> "Added 1 repository from CloudStream."
                    else -> "Added $added repositories from CloudStream."
                } + " Open Sources & Extensions to install their plugins.",
                detail,
            )
        }

    /** True when the file has the shape of a CloudStream DataStore dump (either
     *  bucket's typed maps, the repo key, or a bare repos array for a file
     *  someone extracted by hand). */
    private fun looksLikeCloudStream(root: JSONObject): Boolean {
        for (bucket in listOf("datastore", "settings")) {
            val map = root.optJSONObject(bucket) ?: continue
            if (CS_MAP_TYPES.any { map.optJSONObject(it) != null }) return true
        }
        if (root.has(CS_REPOS_KEY)) return true
        return root.optJSONArray("repositories") != null || root.optJSONArray("repos") != null
    }

    private fun cloudStreamRepos(root: JSONObject): List<Cs3Repo> {
        val found = LinkedHashMap<String, Cs3Repo>()
        @Suppress("UNUSED_VARIABLE")
        val usedFallback = false

        fun offer(value: Any?) {
            for (repo in reposIn(value)) {
                val key = normRepoUrl(repo.url)
                if (key.isNotBlank() && !found.containsKey(key)) found[key] = repo
            }
        }

        // 1. The key CloudStream itself uses, in either bucket (and any other
        //    top-level object, in case a fork nests it differently).
        for (bucket in listOf("datastore", "settings")) {
            val map = root.optJSONObject(bucket) ?: continue
            for (type in CS_MAP_TYPES) {
                val typed = map.optJSONObject(type) ?: continue
                val names = typed.names() ?: continue
                for (i in 0 until names.length()) {
                    val name = names.optString(i)
                    if (name == CS_REPOS_KEY || name.contains("repositor", ignoreCase = true)) {
                        offer(typed.opt(name))
                    }
                }
            }
        }

        // 2. Some forks/older formats keep the array at the top level.
        offer(root.opt("repositories"))
        offer(root.opt("repos"))

        if (found.isEmpty()) {
            largestRepoArray(root)?.let { scanned ->
                offer(scanned)
                if (found.isNotEmpty()) {
                    Logs.log("Backup", "cloudstream repos found by scanning the file")
                }
            }
        }
        return found.values.take(CS_MAX_REPOS)
    }

    /** The `{iconUrl, name, url}` objects inside [value], which may be the array
     *  itself, a JSON string holding it (how CloudStream stores it), a
     *  `_StringSet` wrapper, or an object with a list field. */
    private fun reposIn(value: Any?): List<Cs3Repo> {
        val arr = asRepoArray(value) ?: return emptyList()
        val out = ArrayList<Cs3Repo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = listOf("url", "apiUrl", "repoUrl", "repo")
                .firstNotNullOfOrNull { field ->
                    o.optString(field).trim().takeIf { it.startsWith("http", ignoreCase = true) }
                } ?: continue
            val name = listOf("name", "displayName", "title")
                .firstNotNullOfOrNull { o.optString(it).trim().takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            out.add(Cs3Repo(url = url, name = name.ifBlank { url }, kind = RepoKind.CS3))
        }
        return out
    }

    /** [value] as a repo-shaped [JSONArray], or null. */
    private fun asRepoArray(value: Any?): JSONArray? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is JSONArray) return value.takeIf { looksLikeRepoArray(it) }
        if (value is JSONObject) {
            value.optJSONArray("_StringSet")?.let { return asRepoArray(it) }
            for (field in listOf("repositories", "repos", "list", "items", "value")) {
                value.opt(field)?.let { return asRepoArray(it) }
            }
            return null
        }
        val s = value.toString().trim()
        if (!s.startsWith("[")) return null
        return runCatching { JSONArray(s) }.getOrNull()?.takeIf { looksLikeRepoArray(it) }
    }

    /** An array where at least half the entries are objects with an http url. */
    private fun looksLikeRepoArray(arr: JSONArray): Boolean {
        if (arr.length() == 0) return false
        var hits = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = listOf("url", "apiUrl", "repoUrl", "repo")
                .firstNotNullOfOrNull { o.optString(it).trim().takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            if (url.startsWith("http", ignoreCase = true)) hits++
        }
        return hits > 0 && hits * 2 >= arr.length()
    }

    private fun largestRepoArray(root: JSONObject): JSONArray? {
        var best: JSONArray? = null
        var visited = 0

        fun walk(value: Any?, depth: Int) {
            if (depth > 8 || visited > 20_000) return
            visited++
            when (value) {
                is JSONObject -> {
                    val names = value.names() ?: return
                    for (i in 0 until names.length()) walk(value.opt(names.optString(i)), depth + 1)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                }
                is String -> {
                    val s = value.trim()
                    if (s.length < 3 || !s.startsWith("[")) return
                    val arr = runCatching { JSONArray(s) }.getOrNull() ?: return
                    if (!looksLikeRepoArray(arr)) return
                    if ((best?.length() ?: -1) < arr.length()) best = arr
                }
            }
        }
        walk(root, 0)
        return best
    }

    /** URL identity for the duplicate check: no surrounding space, no trailing
     *  slash, host case folded. */
    private fun normRepoUrl(url: String): String =
        url.trim().trimEnd('/').lowercase(Locale.US)

    /** Background scope for deferred post-restore work: the app object has
     *  no lifecycle scope of its own. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun refreshLiveState(app: HikariApp, deferProviders: Boolean = false) {
        runCatching { app.elementBlocks = app.store.elementBlocks() }
        runCatching {
            app.webViewUseDefaultUa = app.store.webviewUseDefaultUa()
            app.webViewCustomUa = app.store.webviewCustomUa()
        }
        runCatching { NetTuning.setSlowConnection(app.store.slowConnection()) }
        runCatching {
            NetTuning.setDnsProvider(app.store.dnsProvider())
            NetTuning.setCustomDns(app.store.customDns())
        }
        // Desktop has no LanguageManager/i18n UI yet (deferred with the
        // language settings screen): the language pref is stored and travels
        // in backups, it just isn't applied to a UI layer that doesn't exist.
        // The restored pref may be the permissive one; re-assert it against the
        // extensions' own switches (see ExtensionVerifyGuard).
        runCatching { ExtensionVerifyGuard.apply(app, app.store.extensionVerifyWebview()) }
        if (deferProviders) {
            refreshScope.launch { runCatching { app.providers.refresh() } }
        } else {
            runCatching { app.providers.refresh() }
        }
    }

    private fun allowedPath(rel: String): Boolean {
        if (rel.isBlank() || rel.length > 200) return false
        if (rel.startsWith("/") || rel.startsWith("~") || rel.contains('\\') || rel.contains(':')) return false
        val parts = rel.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val root = FILE_ROOTS.firstOrNull { rel == it || rel.startsWith("$it/") } ?: return false
        // nn must have something under the root: bare "cs3" is not a file.
        return rel != root
    }

    // ------------------------------------------------------- saving to disk --

    /** Desktop "save to Downloads": no MediaStore on the desktop, so the file
     *  is copied into the OS Downloads folder. Returns the file name, or null
     *  when the copy failed. Same signature as Android for call-site parity. */
    fun saveToDownloads(@Suppress("UNUSED_PARAMETER") context: Context, file: File, name: String): String? =
        runCatching {
            val downloads = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
            val safe = name.trim().takeIf { it.isNotBlank() } ?: file.name
            val dest = File(downloads, safe)
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.name
        }.getOrNull()
}
