package com.hikari.app.aniyomi

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * CI self-test for the report behind the screenshots: Aniyomi extensions that
 * will not install.
 *
 * Two of them, and both are the app's fault, not the repos':
 *
 *  1. the red trace on the official repo —
 *     `eu.kanade.tachiyomi.animeextension.all.jellyfin.JellyfinFactory could
 *     not be linked: java.lang.NoClassDefFoundError:
 *     androidx/preference/Preference$OnPreferenceChangeListener` — and the row
 *     that says *none of its sources could be loaded*. A class named by an
 *     extension (or by a `catch` clause, or by a method signature) is resolved
 *     while the JVM LINKS that class, so a missing `androidx.preference` type
 *     does not mean "no settings dialog on the desktop": it means the extension
 *     cannot load at all. Measured over 96 real extensions from the live repos,
 *     `PreferenceScreen` is named by 91 of them, `Preference` by 91,
 *     `ListPreference` by 89, `Preference.OnPreferenceChangeListener` by 88,
 *     `MultiSelectListPreference` by 44, `EditTextPreference` by 22,
 *     `SwitchPreferenceCompat` by 18, `EditTextPreference.OnBindEditTextListener`
 *     by 18 and `CheckBoxPreference` by 1; twelve also name
 *     `app.cash.quickjs.QuickJs` (their own JS engine);
 *  2. "Download failed — check the URL" for an extension that is right there in
 *     the repo's index but is not served at the path the index gives.
 *
 * What this checks, against the live repos and the real loader:
 *
 *  A. the shim's SURFACE — every type and every method descriptor the corpus
 *     actually calls, resolved reflectively. A signature that drifts (a nullable
 *     that becomes non-nullable, a method that moves) is a
 *     `NoSuchMethodError`/`NoClassDefFoundError` for real extensions, and this
 *     fails the build with the descriptor named;
 *  B. the shim WORKS — a `ListPreference` reads back its declared default and
 *     its stored value through `PreferenceManager.getDefaultSharedPreferences`,
 *     and a change listener receives the new value;
 *  C. the extensions the report named (Jellyfin, GoogleDriveIndex, GoogleDrive)
 *     DOWNLOAD and INSTALL through the app's own path
 *     (`Http.downloadPluginFile` → `AniyomiExtensionManager.install`) and
 *     register their sources;
 *  D. the same for a QuickJS-using extension from each community repo, so the
 *     `app.cash.quickjs.QuickJs` adapter is exercised, not just present;
 *  E. a repo whose index names files it does not serve (measured: the
 *     OsmerGalarragaTKD `aniyomi-ext` repo publishes a 253-entry index and no
 *     `apk/` directory at all) reports the truth — the repository no longer
 *     holds the file — instead of sending the user to check a URL that was never
 *     wrong.
 *
 * Needs the network (that is the point), so a runner without internet fails
 * loudly instead of passing quietly.
 */
fun main() {
    println("AniyomiExtensionSelfTest: start")
    var failures = 0
    var warns = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println(if (ok) "  OK   $name" else "  FAIL $name  $detail")
        if (!ok) failures++
    }
    fun warn(name: String, detail: String = "") {
        println("  WARN $name  $detail")
        warns++
    }

    runCatching { HikariApp().init() }
    Http.init()
    val context = HikariApp.instance

    // ── A. the shim's surface: what the corpus links against ────────────────
    //
    // These are the exact member references found in 96 real extensions, as
    // `ownerClass.method` + parameter/return types (JVM names). They are listed
    // rather than load-tested because a descriptor is what the verifier
    // resolves: a method that exists with the wrong parameter type is the same
    // failure as one that does not exist.
    val loader = Http::class.java.classLoader
    val types = listOf(
        "androidx.preference.Preference",
        "androidx.preference.PreferenceScreen",
        "androidx.preference.PreferenceGroup",
        "androidx.preference.PreferenceCategory",
        "androidx.preference.DialogPreference",
        "androidx.preference.TwoStatePreference",
        "androidx.preference.SwitchPreferenceCompat",
        "androidx.preference.CheckBoxPreference",
        "androidx.preference.EditTextPreference",
        "androidx.preference.SeekBarPreference",
        "androidx.preference.ListPreference",
        "androidx.preference.MultiSelectListPreference",
        "androidx.preference.PreferenceManager",
        "androidx.preference.PreferenceDataStore",
        "androidx.preference.Preference\$OnPreferenceChangeListener",
        "androidx.preference.Preference\$OnPreferenceClickListener",
        "androidx.preference.EditTextPreference\$OnBindEditTextListener",
        "app.cash.quickjs.QuickJs",
        "android.content.ContextWrapper",
        "android.content.ActivityNotFoundException",
        "android.util.AttributeSet",
        "android.text.Spanned",
        "android.text.Spannable",
        "android.text.Editable",
        "android.text.TextWatcher",
        "android.text.SpannableString",
        "android.text.Html",
        "android.webkit.WebView",
        "android.webkit.WebViewClient",
        "android.webkit.WebSettings",
        "android.webkit.WebResourceRequest",
        "android.webkit.WebResourceResponse",
        "android.webkit.ValueCallback",
    )
    val missingTypes = types.filter { runCatching { Class.forName(it, false, loader) }.isFailure }
    check(
        "every Android/JS type the extensions name exists in this build",
        missingTypes.isEmpty(),
        "missing: " + missingTypes.joinToString(", "),
    )

    // Each entry is `ownerClass#method(paramTypes):returnType`, in JVM names,
    // exactly as the descriptor appears in the extensions' bytecode, with the
    // number of the 96 extensions that reference it. They are listed rather than
    // load-tested because a descriptor is what the verifier resolves: a method
    // that exists with the wrong parameter type is the same failure as one that
    // does not exist.
    val refs = listOf(
        // Preference / PreferenceScreen — the base every source uses.
        "androidx.preference.PreferenceScreen#addPreference(androidx.preference.Preference):boolean|91",
        "androidx.preference.PreferenceScreen#getContext():android.content.Context|91",
        "androidx.preference.Preference#setKey(java.lang.String):void",
        "androidx.preference.Preference#getKey():java.lang.String",
        "androidx.preference.Preference#setTitle(java.lang.CharSequence):void",
        "androidx.preference.Preference#setSummary(java.lang.CharSequence):void",
        "androidx.preference.Preference#setEnabled(boolean):void",
        "androidx.preference.Preference#setDefaultValue(java.lang.Object):void",
        "androidx.preference.Preference#setOnPreferenceChangeListener(androidx.preference.Preference\$OnPreferenceChangeListener):void",
        // ListPreference — the "quality / server / domain" setting.
        "androidx.preference.ListPreference#setEntries([Ljava.lang.CharSequence;):void|89",
        "androidx.preference.ListPreference#setEntryValues([Ljava.lang.CharSequence;):void|89",
        "androidx.preference.ListPreference#findIndexOfValue(java.lang.String):int|63",
        "androidx.preference.ListPreference#getEntryValues():[Ljava.lang.CharSequence;|63",
        // MultiSelectListPreference — "which languages".
        "androidx.preference.MultiSelectListPreference#setValues(java.util.Set):void",
        "androidx.preference.MultiSelectListPreference#getValues():java.util.Set",
        // EditTextPreference / TwoStatePreference (the switch and checkbox base).
        "androidx.preference.EditTextPreference#setText(java.lang.String):void|1",
        "androidx.preference.EditTextPreference#getText():java.lang.String",
        "androidx.preference.EditTextPreference#setOnBindEditTextListener(androidx.preference.EditTextPreference\$OnBindEditTextListener):void|18",
        "androidx.preference.TwoStatePreference#isChecked():boolean",
        "androidx.preference.TwoStatePreference#setChecked(boolean):void",
        // PreferenceManager.getDefaultSharedPreferences — a STATIC method, and
        // the call almost every source's settings are READ through.
        "androidx.preference.PreferenceManager#getDefaultSharedPreferences(android.content.Context):android.content.SharedPreferences",
        // The JS engine twelve extensions run their own scripts on.
        "app.cash.quickjs.QuickJs#evaluate(java.lang.String):java.lang.Object|12",
        "app.cash.quickjs.QuickJs#set(java.lang.String,java.lang.Class,java.lang.Object):void|3",
        // The text types a source's own helpers are built on.
        "android.text.Editable#replace(int,int,java.lang.CharSequence):android.text.Editable",
        "android.text.Editable#insert(int,java.lang.CharSequence):android.text.Editable",
        "android.text.Editable#append(java.lang.CharSequence):android.text.Editable",
        "android.text.TextWatcher#afterTextChanged(android.text.Editable):void|18",
    )

    val badRefs = ArrayList<String>()
    for (spec in refs) {
        val hits = spec.substringAfter('|', "").toIntOrNull() ?: 0
        val plain = spec.substringBefore('|')
        val owner = plain.substringBefore('#')
        val name = plain.substringAfter('#').substringBefore('(')
        val params = plain.substringAfter('(').substringBeforeLast(')')
            .split(',').filter { it.isNotBlank() }
        val ret = plain.substringAfterLast(':')
        val cls = runCatching { Class.forName(owner, false, loader) }.getOrNull()
        if (cls == null) {
            badRefs.add("$owner (class missing)")
            continue
        }
        val found = cls.methods.any { m ->
            m.name == name &&
                m.parameterTypes.map { it.name } == params &&
                m.returnType.name == ret
        }
        if (!found) {
            val sig = "$owner.$name(${params.joinToString(",")}):$ret" +
                (if (hits > 0) "  [referenced by $hits of 96 extensions]" else "")
            badRefs.add(sig)
        }
    }
    check(
        "every member the extensions call exists with the exact descriptor they were compiled against",
        badRefs.isEmpty(),
        badRefs.joinToString(" | "),
    )
    check(
        "QuickJs.create() is the static method the extensions call",
        runCatching {
            Class.forName("app.cash.quickjs.QuickJs", false, loader)
                .getMethod("create").let { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType.name == "app.cash.quickjs.QuickJs" }
        }.getOrDefault(false),
    )

    // ── B. the shim WORKS: a real settings round-trip ───────────────────────
    //
    // Present-but-inert is the failure mode this file exists to prevent, so the
    // shim is exercised the way a source does: declare a key + default, store a
    // value through the SAME SharedPreferences `getDefaultSharedPreferences`
    // hands out, and read it back.
    runCatching {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val pref = androidx.preference.ListPreference(context)
        pref.setKey("hikari-self-test-quality")
        pref.setDefaultValue("1080p")
        pref.setEntries(arrayOf<CharSequence>("360p", "720p", "1080p"))
        pref.setEntryValues(arrayOf<CharSequence>("360", "720", "1080"))
        val declared = pref.getValue()
        prefs.edit().putString("hikari-self-test-quality", "720").apply()
        val stored = pref.getValue()
        var seen: Any? = null
        pref.setOnPreferenceChangeListener { _, newValue ->
            seen = newValue
            true
        }
        val allowed = pref.callChangeListener("480")
        println("      default=\"$declared\" stored=\"$stored\" findIndexOfValue(1080)=${pref.findIndexOfValue("1080")} listenerSaw=$seen allowed=$allowed")
        check("a ListPreference reads back its DECLARED default", declared == "1080p", "" + declared)
        check("a ListPreference reads back the value that was STORED", stored == "720", "" + stored)
        check(
            "findIndexOfValue maps a stored value back to its row",
            pref.findIndexOfValue("1080") == 2,
            "" + pref.findIndexOfValue("1080"),
        )
        check(
            "a registered change listener receives the new value",
            seen == "480" && allowed,
            "seen=$seen allowed=$allowed",
        )
        prefs.edit().remove("hikari-self-test-quality").apply()
    }.onFailure { check("the preference shim can be exercised at all", false, it.toString()) }

    // ── C/D. install real extensions through the app's own path ─────────────
    //
    // Each repo is fetched the way the Extensions screen fetches it, its entries
    // are turned into download URLs the way `repoPlugin` builds them, the file is
    // fetched the way the installer fetches it (with the index-drift repair), and
    // the bytes go into the REAL loader — the same call the Install button makes.
    class Repo(
        val label: String,
        val indexUrl: String,
        val want: List<String>,
        /** True when every entry named here MUST install: the extensions the
         *  report named, on the small, stable official repo. A community repo's
         *  own site/API problems are reported as warnings — but a missing
         *  ANDROID type is our bug and fails even there (see [shimGap]). */
        val strict: Boolean,
    )

    val repos = listOf(
        Repo(
            "aniyomiorg (the official repo, the screenshot's red trace)",
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
            listOf(
                "eu.kanade.tachiyomi.animeextension.all.jellyfin",
                "eu.kanade.tachiyomi.animeextension.all.googledriveindex",
                "eu.kanade.tachiyomi.animeextension.all.googledrive",
            ),
            strict = true,
        ),
        Repo(
            "aniyomi-addons/anime-extensions-repo (a community repo)",
            "https://raw.githubusercontent.com/aniyomi-addons/anime-extensions-repo/repo/index.min.json",
            listOf(
                "eu.kanade.tachiyomi.animeextension.fr.wiflix",
                "eu.kanade.tachiyomi.animeextension.en.animekai",
            ),
            strict = false,
        ),
        Repo(
            "adly98/aniyomi-ar-extensions (a community repo)",
            "https://raw.githubusercontent.com/adly98/aniyomi-ar-extensions/repo/index.min.json",
            listOf("eu.kanade.tachiyomi.animeextension.ar.faselhd"),
            strict = false,
        ),
    )

    var installedOk = 0
    var attempted = 0
    var strictAttempted = 0
    var strictInstalled = 0
    var shimGaps = 0

    /**
     * A load failure that names a type this app is supposed to PROVIDE is the
     * app's bug wherever it happens; anything else (the site moved, the
     * extension's own code throws, a newer extensions-lib) is the extension's
     * own outcome and is reported without failing the build.
     */
    fun shimGap(why: String): Boolean {
        if (!Regex("NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|ClassNotFoundException|IncompatibleClassChangeError")
                .containsMatchIn(why)
        ) return false
        return Regex("android[x]?/|android\\.|androidx\\.|app\\.cash\\.quickjs|tachiyomi/core|com\\.lagradost")
            .containsMatchIn(why)
    }

    for (repo in repos) {
        val fetched = Http.fetchRepoJson(repo.indexUrl).getOrNull()
            ?: Http.fetchStringRobust(repo.indexUrl).getOrNull()?.let { repo.indexUrl to it }
        if (fetched == null) {
            check("${repo.label} index is reachable", false, repo.indexUrl)
            continue
        }
        val listed = runCatching { JSONArray(fetched.second) }.getOrNull()
        if (listed == null) {
            check("${repo.label} index parses as an Aniyomi index array", false, fetched.first)
            continue
        }
        val baseUrl = fetched.first.trimEnd('/').removeSuffix("/index.min.json")
        println("— ${repo.label}: ${listed.length()} extension(s), served by ${fetched.first}")

        for (pkg in repo.want) {
            val entry = (0 until listed.length())
                .mapNotNull { listed.optJSONObject(it) }
                .firstOrNull { it.optString("pkg") == pkg }
            if (entry == null) {
                check("$pkg is listed by its repo", false, "not in ${repo.indexUrl}")
                continue
            }
            // Exactly the URL the Extensions screen builds for the row.
            val built = AniyomiExtensionManager.repoPlugin(entry, baseUrl)
            if (built == null) {
                check("$pkg resolves to a download URL", false, "repoPlugin returned null")
                continue
            }
            attempted++
            if (repo.strict) strictAttempted++
            println("    $pkg")
            println("      url: ${built.url}")

            val dl = Http.downloadPluginFile(built.url)
            val bytes = dl.bytes
            if (bytes == null) {
                val why = (dl.error ?: "no reason") + "  (url=${built.url})"
                if (repo.strict) {
                    check("$pkg downloads", false, why)
                } else {
                    warn("$pkg downloads", why)
                }
                continue
            }
            if (dl.usedUrl != built.url) println("      (index drift repaired: fetched ${dl.usedUrl})")
            // What the extension actually names, from its own dex — printed so
            // the log says which shim this row is exercising.
            println("      needs: " + dexNeeds(bytes) + "  (${bytes.size} bytes)")

            val result = runCatching { runBlocking { AniyomiExtensionManager.install(context, bytes, built.url, built.iconUrl) } }
            val count = result.getOrNull()?.getOrNull()
            if (count == null) {
                val why = result.exceptionOrNull()?.message
                    ?: result.getOrNull()?.exceptionOrNull()?.message
                    ?: AniyomiExtensionManager.lastError
                    ?: "no reason recorded"
                if (repo.strict || shimGap(why)) {
                    if (shimGap(why)) shimGaps++
                    check("$pkg installs and its sources load", false, why.take(400))
                } else {
                    warn("$pkg is loadable on this build", why.take(200))
                }
                continue
            }
            println("      installed: $count provider(s) registered")
            val rows = context.store.providers().filter {
                it.type == ProviderType.ANIYOMI && AniyomiExtensionManager.packageOf(it) == pkg
            }
            check(
                "$pkg registers one provider per source it publishes",
                rows.size == count && rows.all { it.name.isNotBlank() },
                "expected $count row(s), found ${rows.size}",
            )
            installedOk++
            if (repo.strict) strictInstalled++
        }
    }

    check(
        "every extension the report named installs ($strictInstalled/$strictAttempted)",
        strictAttempted == 3 && strictInstalled == strictAttempted,
        "installed=$strictInstalled of $strictAttempted",
    )
    check(
        "no extension failed because of a type this app is supposed to provide",
        shimGaps == 0,
        "$shimGaps shim gap(s)",
    )

    // ── E. an index that names files the repository does not serve ──────────
    //
    // Measured, not invented: `OsmerGalarragaTKD/aniyomi-ext` publishes a
    // 253-entry `index.min.json` and — on BOTH of its branches — no `apk/`
    // directory at all. Every one of those entries is an install that can never
    // succeed, and all of them used to say "Download failed — check the URL".
    val driftIndex = "https://raw.githubusercontent.com/OsmerGalarragaTKD/aniyomi-ext/repo/index.min.json"
    val driftBody = Http.fetchStringRobust(driftIndex).getOrNull()
    val driftList = driftBody?.let { runCatching { JSONArray(it) }.getOrNull() }
    if (driftList == null || driftList.length() == 0) {
        warn("the drifting repo's index is reachable", driftIndex)
    } else {
        val base = driftIndex.trimEnd('/').removeSuffix("/index.min.json")
        val entry = driftList.optJSONObject(0)
        val built = entry?.let { AniyomiExtensionManager.repoPlugin(it, base) }
        val url = built?.url
        println("— index drift: ${driftList.length()} entries, first is ${built?.name} → $url")
        if (url != null) {
            // GitHub has to be askable for the "the repo does not serve it"
            // answer to be knowable. When it is not (a rate limit, a proxy),
            // that is a warning, not a failure of this app.
            val files = Http.repoFileList(url)
            if (files == null) {
                warn("GitHub could not be asked what ${url.substringAfter("github.com/")} holds", "repair checks are unverified this run")
            } else {
                check(
                    "the drifting repo really does not serve that file",
                    Http.fileGoneFromRepo(url),
                    "the file was found after all",
                )
                val dl = Http.downloadPluginFile(url)
                check("that file cannot be downloaded", dl.bytes == null, "got ${dl.bytes?.size} bytes")
                check(
                    "the failure names the CAUSE (the repo no longer publishes it), not \"check the URL\"",
                    dl.error?.contains("no longer holds this file") == true,
                    "said: ${dl.error}",
                )
            }
        }
    }

    // ── the honest-failure rule, in one place ───────────────────────────────
    //
    // A file that is genuinely not there and a host that is unreachable must not
    // read the same way: that is what made every drift look like the user's
    // mistake. A URL under a repository that never had the file says so; a URL
    // on a host that cannot be reached says THAT.
    val vanished = Http.downloadPluginFile(
        "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/apk/aniyomi-all.thisdoesnotexist-v1.apk"
    )
    println("— a file the official repo does not hold → ${vanished.error}")
    check(
        "a deleted-upstream file is reported as deleted upstream",
        vanished.bytes == null && vanished.error?.contains("no longer holds this file") == true,
        "" + vanished.error,
    )
    val unreachable = Http.downloadPluginFile("https://hikari-self-test-no-such-host.invalid/plugin.apk")
    println("— an unreachable host → ${unreachable.error}")
    check(
        "an unreachable host is NOT reported as a deleted extension",
        unreachable.bytes == null && unreachable.error?.contains("no longer holds this file") != true,
        "" + unreachable.error,
    )

    println(
        "AniyomiExtensionSelfTest: " +
            (if (failures == 0) "OK" else "$failures FAILED") +
            " (installed $installedOk extension(s), $warns warning(s))"
    )
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}

/**
 * The `android`/`androidx`/JS types an extension's own dex names, so the log
 * says which part of the shim a row exercised. Reads the dex string table the
 * way the loader meets it (the APK's `classes*.dex` entries are DEFLATED, so
 * the archive has to be opened first).
 */
private fun dexNeeds(apk: ByteArray): String {
    val found = LinkedHashSet<String>()
    val probes = linkedMapOf(
        "androidx/preference/Preference" to "preference",
        "app/cash/quickjs/QuickJs" to "quickjs",
        "android/content/ActivityNotFoundException" to "ActivityNotFound",
        "android/text/TextWatcher" to "TextWatcher",
        "android/content/ContextWrapper" to "ContextWrapper",
        "android/webkit/WebView" to "WebView",
    )
    runCatching {
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (Regex("classes\\d*\\.dex").matches(entry.name)) {
                    val text = String(zip.readBytes(), Charsets.ISO_8859_1)
                    for ((needle, label) in probes) if (text.contains(needle)) found.add(label)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
    return if (found.isEmpty()) "nothing unusual" else found.joinToString(", ")
}
