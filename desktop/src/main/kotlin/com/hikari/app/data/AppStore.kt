package com.hikari.app.data

import com.hikari.app.net.AdBlocker
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Desktop AppStore. Same public API as the Android version, but backed by a
 * single JSON file (hikari.json in the app data dir) instead of DataStore.
 * All reads/writes are synchronous; every Flow re-reads through a version
 * ticker so collectors see updates.
 */
class AppStore(private val dir: File) {

    private val file = File(dir, "hikari.json")
    private val lock = Any()
    private var json = JSONObject()
    private val version = MutableStateFlow(0L)

    init {
        synchronized(lock) {
            if (file.exists()) {
                runCatching { json = JSONObject(file.readText()) }.onFailure {
                    // Never delete user data on a corrupt write: keep a timestamped
                    // backup so favorites/history survive a crash mid-save.
                    runCatching {
                        val bak = File(dir, "hikari.json.corrupt-${System.currentTimeMillis()}.bak")
                        file.copyTo(bak, overwrite = true)
                    }
                    runCatching { file.delete() }
                    json = JSONObject()
                }
            }
        }
    }

    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hikari-store-save").apply { isDaemon = true } }
    @Volatile private var saveQueued = false
    private fun save() {
        // Disk writes used to run on the CALLER thread â usually the FX thread â
        // so every favorite/history/theme toggle cost a file write + JSON
        // serialize mid-frame. Version bumps instantly (Flows update now); the
        // file lands coalesced on a daemon thread. A JVM exit before the flush
        // loses at most the last ~300ms of toggles, which reload as defaults.
        version.value++
        synchronized(lock) {
            if (saveQueued) return
            saveQueued = true
        }
        saver.execute {
            runCatching { Thread.sleep(250L) }
            val snapshot: String = synchronized(lock) { saveQueued = false; json.toString() }
            runCatching {
                file.parentFile?.mkdirs()
                // Atomic write: crash mid-save must never truncate hikari.json.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun get(key: String): String? = synchronized(lock) {
        if (json.has(key)) json.optString(key) else null
    }

    private fun put(key: String, value: Any?) {
        synchronized(lock) {
            if (value == null) json.remove(key) else json.put(key, value)
        }
        save()
    }

    private fun <T> flowOf(mapper: () -> T): Flow<T> = version.map { mapper() }

    private object K {
        const val PROVIDERS = "providers"
        const val FAVORITES = "favorites"
        const val CS3_REPOS = "cs3Repos"
        const val SITES = "sites"
        const val USERS = "userscripts"
        const val THEME = "theme"
        const val HISTORY = "history"
        const val HISTORY_PAUSED = "historyPaused"
        const val ELEMENT_BLOCKS = "elementBlocks"
        const val AD_ENABLED = "adEnabled"
        const val AD_LISTS = "adLists"
        const val AD_BLOCK = "adBlock"
        const val AD_WHITE = "adWhite"
        const val WEBVIEW_REDIRECT = "webviewRedirect"
        const val WEBVIEW_POPUP = "webviewPopup"
        const val WEBVIEW_REDIRECT_ALLOW = "webviewRedirectAllow"
        const val WEBVIEW_DEFAULT_UA = "webviewDefaultUa"
        const val WEBVIEW_CUSTOM_UA = "webviewCustomUa"
        const val HOME_PROVIDER = "homeProvider"
        const val HOME_MULTI_IDS = "homeMultiIds"
        const val HOME_MULTI_ON = "homeMultiOn"
        const val HOME_LAYOUT = "homeLayout"
        const val TRANSLATE_PROVIDERS = "translateProviders"
        const val TRANSLATE_CACHE = "translateCache"
        const val DL_CONCURRENCY = "downloadConcurrency"
        const val DL_TO_FOLDER = "downloadToFolder"
        const val PLAY_FASTEST = "playFastest"
        const val SWEEP_ALL = "sweepAllSources"
        const val SWEEP_ENGINES = "sweepEngines"
        const val SWEEP_SOLO = "sweepSoloProviders"
        const val SWEEP_SHOW_ALL = "sweepShowAllFirst"
        const val PLAYER_VO = "playerVo"
        const val NSFW_ENABLED = "nsfwEnabled"
        const val SLOW_CONNECTION = "slowConnection"
        const val COLLECTIONS = "collections"
        const val CLONE_HOSTS = "cloneHosts"
        const val PROVIDER_KIND_ORDER = "providerKindOrder"
        const val MDB_ENABLED = "mdbEnabled"
        const val MDBLIST_API_KEY = "mdblistApiKey"
        const val MDB_IMDB = "mdbImdb"
        const val MDB_TMDB = "mdbTmdb"
        const val MDB_TOMATOES = "mdbTomatoes"
        const val MDB_METACRITIC = "mdbMetacritic"
        const val MDB_TRAKT = "mdbTrakt"
        const val MDB_LETTERBOXD = "mdbLetterboxd"
        const val MDB_AUDIENCE = "mdbAudience"
        const val MDB_MAL = "mdbMal"
        const val EXT_VERIFY_WEBVIEW = "extVerifyWebview"
        const val DNS_PROVIDER = "dnsProvider"
        const val CUSTOM_DNS = "customDns"
        const val LANGUAGE = "appLanguage"
    }

    /**
     * "Play straight away" (default on): clicking Play picks the FASTEST server
     * that answers for the title and starts it, instead of the first entry the
     * provider happened to list, and without waiting for the user to choose one.
     * The player's Source menu still lists every server, so a bad pick is one
     * click from being changed â see [desktop.ui.DetailScreenView].
     */
    fun playFastestFlow(): Flow<Boolean> = flowOf { playFastest() }

    fun playFastest(): Boolean = (get(K.PLAY_FASTEST) ?: "true").toBoolean()

    fun setPlayFastest(value: Boolean) {
        put(K.PLAY_FASTEST, value)
    }

    /**
     * Server sweep scope (Settings â Playback and servers).
     *
     * - [sweepAllSources]: one master switch â when on, a title's servers are
     *   asked from EVERY enabled provider no matter the engine.
     * - [sweepEngines]: extra engine families to include by title, as
     *   [ProviderType] names (e.g. "CS3", "NUVIO", "HIKARI"). The origin's own
     *   family is always included; these add MORE families to the title waves.
     * - [sweepSoloProviders]: provider ids that stay solo â a title opened
     *   from one of these never fans out to other providers, only its own
     *   servers are shown (e.g. 51CG/MRDS when the user excludes them).
     * - [sweepShowAllFirst]: "show all servers first" instead of playing
     *   directly â Play opens the server list and the user picks one, while
     *   late servers keep arriving in the background.
     */
    fun sweepAllSources(): Boolean = (get(K.SWEEP_ALL) ?: "false").toBoolean()

    fun setSweepAllSources(value: Boolean) {
        put(K.SWEEP_ALL, value)
    }

    fun sweepEngines(): Set<String> = parseStringList(get(K.SWEEP_ENGINES)).toSet()

    fun setSweepEngine(name: String, enabled: Boolean) {
        val cur = sweepEngines()
        val next = if (enabled) cur + name else cur - name
        put(K.SWEEP_ENGINES, encodeStringList(next.toList()))
    }

    fun sweepSoloProviders(): Set<String> = parseStringList(get(K.SWEEP_SOLO)).toSet()

    fun setSweepSoloProvider(id: String, solo: Boolean) {
        val cur = sweepSoloProviders()
        val next = if (solo) cur + id else cur - id
        put(K.SWEEP_SOLO, encodeStringList(next.toList()))
    }

    fun sweepShowAllFirst(): Boolean = (get(K.SWEEP_SHOW_ALL) ?: "false").toBoolean()

    fun setSweepShowAllFirst(value: Boolean) {
        put(K.SWEEP_SHOW_ALL, value)
    }

    fun downloadConcurrency(): Int =
        (get(K.DL_CONCURRENCY) ?: "3").toIntOrNull()?.coerceIn(1, 10) ?: 3

    fun setDownloadConcurrency(n: Int) {
        put(K.DL_CONCURRENCY, n.coerceIn(1, 10).toString())
    }

    /** When true (the default), a finished download is ALSO written as one
     *  playable file into the user's Downloads/Hikari folder. */
    fun downloadToFolder(): Boolean = (get(K.DL_TO_FOLDER) ?: "true").toBoolean()

    fun setDownloadToFolder(value: Boolean) {
        put(K.DL_TO_FOLDER, value)
    }

    fun homeProviderFlow(): Flow<String> = flowOf { homeProvider() }
    fun homeProvider(): String = get(K.HOME_PROVIDER) ?: ""
    fun setHomeProvider(id: String) {
        put(K.HOME_PROVIDER, id)
    }

    /**
     * Multi-provider home feed (see the provider picker's "Enable
     * Multi-Provider Feed"): the ordered provider ids Home mixes rows from,
     * and whether the mix is on. Empty/off = the single [homeProvider] choice
     * (or every enabled provider when that is blank too).
     */
    fun homeMultiIds(): List<String> = parseStringList(get(K.HOME_MULTI_IDS))
    fun setHomeMultiIds(ids: List<String>) {
        put(K.HOME_MULTI_IDS, encodeStringList(ids))
    }
    fun homeMultiOn(): Boolean = (get(K.HOME_MULTI_ON) ?: "false").toBoolean()
    fun setHomeMultiOn(on: Boolean) {
        put(K.HOME_MULTI_ON, on.toString())
    }

    /**
     * Home catalog layout: "poster" (classic vertical 2:3 posters, the
     * default) or "cinema" (16:9 landscape cards, fed by high-quality TMDB
     * backdrop art). See Settings â Home and [HomeScreenView].
     */
    fun homeLayoutFlow(): Flow<String> = flowOf { homeLayout() }
    fun homeLayout(): String = (get(K.HOME_LAYOUT) ?: "poster").lowercase().takeIf { it == "cinema" } ?: "poster"
    fun setHomeLayout(layout: String) {
        put(K.HOME_LAYOUT, if (layout == "poster") "poster" else "cinema")
    }

    /**
     * The mpv video-output profile that last managed to show a picture on this
     * machine ("auto", "nohwdec", "sdl"). The player tries profiles in order
     * when mpv dies on arrival and remembers the winner here, so a machine
     * whose GPU output crashes starts right the first time afterwards.
     */
    fun playerVo(): String = (get(K.PLAYER_VO) ?: "auto").lowercase()
    fun setPlayerVo(profile: String) {
        put(K.PLAYER_VO, profile.lowercase().takeIf { it.isNotBlank() } ?: "auto")
    }

    fun translateProvidersFlow(): Flow<Set<String>> = flowOf { translateProviders() }
    fun translateProviders(): Set<String> = parseStringList(get(K.TRANSLATE_PROVIDERS)).toSet()
    fun setTranslateProvider(id: String, enabled: Boolean) {
        val cur = translateProviders()
        val next = if (enabled) cur + id else cur - id
        put(K.TRANSLATE_PROVIDERS, encodeStringList(next.toList()))
    }

    fun translateCache(): List<Pair<String, String>> = parsePairs(get(K.TRANSLATE_CACHE))
    fun setTranslateCache(list: List<Pair<String, String>>) {
        put(K.TRANSLATE_CACHE, encodePairs(list))
    }

    private fun encodePairs(list: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((k, v) in list) arr.put(JSONArray().put(k).put(v))
        return arr.toString()
    }

    private fun parsePairs(s: String?): List<Pair<String, String>> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                val k = pair.optString(0)
                val v = pair.optString(1)
                if (k.isBlank() || v.isBlank()) null else k to v
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun themeFlow(): Flow<String> = flowOf { theme() }
    fun theme(): String = get(K.THEME) ?: HikariThemeMode.DARK.key
    fun setTheme(key: String) {
        put(K.THEME, key)
    }

    fun adEnabledFlow(): Flow<Boolean> = flowOf { adEnabled() }
    fun adEnabled(): Boolean = (get(K.AD_ENABLED) ?: "true").toBoolean()
    fun setAdEnabled(enabled: Boolean) {
        put(K.AD_ENABLED, enabled)
    }

    fun adListsFlow(): Flow<List<AdBlocker.HostList>> = flowOf { adLists() }
    fun adLists(): List<AdBlocker.HostList> = parseHostLists(get(K.AD_LISTS))
    fun setAdLists(list: List<AdBlocker.HostList>) {
        put(K.AD_LISTS, encodeHostLists(list))
    }

    fun adBlockFlow(): Flow<List<String>> = flowOf { adBlock() }
    fun adBlock(): List<String> = parseStringList(get(K.AD_BLOCK))
    fun setAdBlock(list: List<String>) {
        put(K.AD_BLOCK, encodeStringList(list))
    }

    fun adWhiteFlow(): Flow<List<String>> = flowOf { adWhite() }
    fun adWhite(): List<String> = parseStringList(get(K.AD_WHITE))
    fun setAdWhite(list: List<String>) {
        put(K.AD_WHITE, encodeStringList(list))
    }

    fun webviewRedirectFlow(): Flow<Boolean> = flowOf { webviewRedirect() }
    fun webviewRedirect(): Boolean = (get(K.WEBVIEW_REDIRECT) ?: "true").toBoolean()
    fun setWebviewRedirect(enabled: Boolean) {
        put(K.WEBVIEW_REDIRECT, enabled)
    }

    fun webviewPopupFlow(): Flow<Boolean> = flowOf { webviewPopup() }
    fun webviewPopup(): Boolean = (get(K.WEBVIEW_POPUP) ?: "true").toBoolean()
    fun setWebviewPopup(enabled: Boolean) {
        put(K.WEBVIEW_POPUP, enabled)
    }

    fun webviewRedirectAllowFlow(): Flow<List<String>> = flowOf { webviewRedirectAllow() }
    fun webviewRedirectAllow(): List<String> = parseStringList(get(K.WEBVIEW_REDIRECT_ALLOW))
    fun setWebviewRedirectAllow(list: List<String>) {
        put(K.WEBVIEW_REDIRECT_ALLOW, encodeStringList(list))
    }

    fun webviewUseDefaultUaFlow(): Flow<Boolean> = flowOf { webviewUseDefaultUa() }
    fun webviewUseDefaultUa(): Boolean = (get(K.WEBVIEW_DEFAULT_UA) ?: "true").toBoolean()
    fun webviewCustomUaFlow(): Flow<String> = flowOf { webviewCustomUa() }
    fun webviewCustomUa(): String = get(K.WEBVIEW_CUSTOM_UA) ?: ""
    fun setWebViewUa(useDefault: Boolean, customUa: String) {
        put(K.WEBVIEW_DEFAULT_UA, useDefault)
        put(K.WEBVIEW_CUSTOM_UA, customUa)
    }

    private fun encodeHostLists(list: List<AdBlocker.HostList>): String {
        val arr = JSONArray()
        for (l in list) {
            arr.put(JSONObject().put("name", l.name).put("url", l.url))
        }
        return arr.toString()
    }

    private fun parseHostLists(s: String?): List<AdBlocker.HostList> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else AdBlocker.HostList(o.optString("name").ifBlank { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        return arr.toString()
    }

    private fun parseStringList(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun userscriptsFlow(): Flow<List<Userscript>> = flowOf { userscripts() }
    fun userscripts(): List<Userscript> = parseUserscripts(get(K.USERS))
    fun setUserscripts(list: List<Userscript>) {
        put(K.USERS, encodeUserscripts(list))
    }

    private fun parseUserscripts(s: String?): List<Userscript> {
        if (s.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Userscript>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("code")
                if (code.isBlank()) continue
                out += Userscript(
                    id = o.optString("id"),
                    name = o.optString("name").ifBlank { "Userscript" },
                    enabled = o.optBoolean("enabled", true),
                    code = code,
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun encodeUserscripts(list: List<Userscript>): String {
        val arr = JSONArray()
        for (u in list) {
            arr.put(
                JSONObject()
                    .put("id", u.id)
                    .put("name", u.name)
                    .put("enabled", u.enabled)
                    .put("code", u.code)
            )
        }
        return arr.toString()
    }

    fun providersFlow(): Flow<List<ProviderConfig>> = flowOf { providers() }
    fun providers(): List<ProviderConfig> = parseProviders(get(K.PROVIDERS))

    /** Wipes every stored setting/provider/repo and deletes downloaded
     *  extensions, restoring the factory-fresh state (defaults re-added on the
     *  next app start). */
    fun reset() {
        synchronized(lock) { json = JSONObject() }
        save()
        runCatching { File(dir, "extensions").deleteRecursively() }
    }
    fun saveProviders(list: List<ProviderConfig>) {
        put(K.PROVIDERS, encodeProviders(list))
    }
    fun addProvider(c: ProviderConfig) {
        saveProviders(providers().filter { it.id != c.id } + c)
    }
    fun removeProvider(id: String) {
        saveProviders(providers().filter { it.id != id })
    }
    fun setEnabled(id: String, enabled: Boolean) {
        saveProviders(providers().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Locked read-modify-write of the provider list. Rebuilding the list from
     *  a snapshot read OUTSIDE the lock drops anything installed in between â
     *  which is exactly how an uninstall used to silently remove an extension
     *  the user had just added. */
    fun updateProviders(transform: (List<ProviderConfig>) -> List<ProviderConfig>) {
        synchronized(lock) {
            saveProviders(transform(providers()))
        }
    }

    fun reposFlow(): Flow<List<Cs3Repo>> = flowOf { repos() }
    fun repos(): List<Cs3Repo> = parseRepos(get(K.CS3_REPOS))
    fun addCs3Repo(r: Cs3Repo) {
        saveRepos(repos().filter { it.url != r.url } + r)
    }
    fun removeCs3Repo(url: String) {
        saveRepos(repos().filter { it.url != url })
    }
    private fun saveRepos(list: List<Cs3Repo>) {
        put(K.CS3_REPOS, encodeRepos(list))
    }

    fun slowConnectionFlow(): Flow<Boolean> = flowOf { (get(K.SLOW_CONNECTION) ?: "false").toBoolean() }
    fun slowConnection(): Boolean = (get(K.SLOW_CONNECTION) ?: "false").toBoolean()
    fun setSlowConnection(on: Boolean) = put(K.SLOW_CONNECTION, on)

    fun nsfwEnabledFlow(): Flow<Boolean> = flowOf { (get(K.NSFW_ENABLED) ?: "true").toBoolean() }
    fun nsfwEnabled(): Boolean = (get(K.NSFW_ENABLED) ?: "true").toBoolean()
    fun setNsfwEnabled(on: Boolean) = put(K.NSFW_ENABLED, on)

    fun collectionsFlow(): Flow<List<Collection>> = flowOf { parseCollections(get(K.COLLECTIONS)) }
    fun collections(): List<Collection> = parseCollections(get(K.COLLECTIONS))
    fun collection(id: String): Collection? = collections().firstOrNull { it.id == id }
    fun saveCollections(list: List<Collection>) = put(K.COLLECTIONS, encodeCollections(list))
    fun upsertCollection(c: Collection): List<Collection> {
        val next = collections().filter { it.id != c.id } + c
        saveCollections(next)
        return next
    }
    fun removeCollection(id: String) = saveCollections(collections().filter { it.id != id })
    fun newId(prefix: String): String =
        prefix + "-" + System.currentTimeMillis().toString(36) + "-" + (1000 + (Math.random() * 8999).toInt())

    fun cloneHostsFlow(): Flow<Map<String, CloneEntry>> = flowOf { parseCloneHosts(get(K.CLONE_HOSTS)) }
    fun cloneHosts(): Map<String, CloneEntry> = parseCloneHosts(get(K.CLONE_HOSTS))
    fun saveClone(entry: CloneEntry) {
        val current = cloneHosts().toMutableMap()
        current[entry.id] = entry
        put(K.CLONE_HOSTS, encodeCloneHosts(current))
    }
    fun removeClone(id: String) {
        val current = cloneHosts().toMutableMap()
        current.remove(id)
        put(K.CLONE_HOSTS, encodeCloneHosts(current))
    }

    fun providerKindOrderFlow(): Flow<List<String>> = flowOf {
        get(K.PROVIDER_KIND_ORDER)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
    fun providerKindOrder(): List<String> =
        get(K.PROVIDER_KIND_ORDER)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    fun setProviderKindOrder(keys: List<String>) = put(K.PROVIDER_KIND_ORDER, keys.joinToString(","))

    // ---- Ratings / MDBList (see data/Ratings) ----

    fun mdbEnabledFlow(): Flow<Boolean> = flowOf { mdbEnabled() }
    fun mdbEnabled(): Boolean = (get(K.MDB_ENABLED) ?: "false").toBoolean()
    fun setMdbEnabled(value: Boolean) = put(K.MDB_ENABLED, value)

    fun mdbListApiKeyFlow(): Flow<String> = flowOf { mdbListApiKey() }
    fun mdbListApiKey(): String = get(K.MDBLIST_API_KEY).orEmpty()
    fun setMdbListApiKey(value: String) = put(K.MDBLIST_API_KEY, value.trim())

    fun mdbProviderFlow(name: String): Flow<Boolean> = flowOf { mdbProvider(name) }
    fun mdbProvider(name: String): Boolean = when (name) {
        "imdb" -> get(K.MDB_IMDB)?.toBoolean() ?: true
        "tmdb" -> get(K.MDB_TMDB)?.toBoolean() ?: true
        "tomatoes" -> get(K.MDB_TOMATOES)?.toBoolean() ?: true
        "metacritic" -> get(K.MDB_METACRITIC)?.toBoolean() ?: true
        "trakt" -> get(K.MDB_TRAKT)?.toBoolean() ?: true
        "letterboxd" -> get(K.MDB_LETTERBOXD)?.toBoolean() ?: true
        "audience" -> get(K.MDB_AUDIENCE)?.toBoolean() ?: true
        "mal" -> get(K.MDB_MAL)?.toBoolean() ?: true
        else -> true
    }
    fun setMdbProvider(name: String, value: Boolean) {
        val key = when (name) {
            "imdb" -> K.MDB_IMDB
            "tmdb" -> K.MDB_TMDB
            "tomatoes" -> K.MDB_TOMATOES
            "metacritic" -> K.MDB_METACRITIC
            "trakt" -> K.MDB_TRAKT
            "letterboxd" -> K.MDB_LETTERBOXD
            "audience" -> K.MDB_AUDIENCE
            "mal" -> K.MDB_MAL
            else -> return
        }
        put(key, value)
    }

    // ---- Extension verification pages (see net/ExtensionVerifyGuard) ----

    fun extensionVerifyWebviewFlow(): Flow<Boolean> = flowOf { extensionVerifyWebview() }
    fun extensionVerifyWebview(): Boolean = (get(K.EXT_VERIFY_WEBVIEW) ?: "false").toBoolean()
    fun setExtensionVerifyWebview(value: Boolean) = put(K.EXT_VERIFY_WEBVIEW, value)

    // ---- Network DNS (see net/NetTuning, net/DnsProviders) ----

    fun dnsProviderFlow(): Flow<String> = flowOf { dnsProvider() }
    fun dnsProvider(): String = get(K.DNS_PROVIDER).orEmpty().ifBlank { "system" }
    fun setDnsProvider(key: String) = put(K.DNS_PROVIDER, key)

    fun customDnsFlow(): Flow<String> = flowOf { customDns() }
    fun customDns(): String = get(K.CUSTOM_DNS).orEmpty()
    fun setCustomDns(url: String) = put(K.CUSTOM_DNS, url)

    // ---- App language (i18n UI reads this when it lands; stored now) ----

    fun languageFlow(): Flow<String> = flowOf { language() }
    fun language(): String = get(K.LANGUAGE).orEmpty()
    fun setLanguage(tag: String) = put(K.LANGUAGE, tag)

    /** The URL a provider row was installed from (its repo manifest entry),
     *  or "" for rows that did not come from a repo. One-to-one with Android. */
    fun sourceUrlOf(c: ProviderConfig): String {
        val extra = c.extra ?: return ""
        if (!extra.startsWith("http")) return ""
        return if (c.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
    }

    // ---- Backup/restore surface (see data/BackupManager) ----

    /** Every stored preference as a typed record. Device-local keys never
     *  leave the device (see [DeviceLocal]). */
    fun snapshotPreferences(): List<PrefRecord> = synchronized(lock) {
        val out = ArrayList<PrefRecord>()
        val names = json.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (DeviceLocal.contains(k)) continue
            when (val v = json.opt(k)) {
                null, JSONObject.NULL -> Unit
                is String -> out += PrefRecord(k, "s", v)
                is Boolean -> out += PrefRecord(k, "b", v)
                is Int, is Long -> out += PrefRecord(k, "l", (v as Number).toLong())
                is Float, is Double -> out += PrefRecord(k, "d", (v as Number).toDouble())
                is JSONArray -> out += PrefRecord(k, "ss", (0 until v.length()).map { v.optString(it) })
                else -> Unit
            }
        }
        out
    }

    /** Applies backup records through [put] (so flows update and the file is
     *  written). Returns how many were applied. */
    fun restorePreferences(records: List<PrefRecord>): Int {
        var applied = 0
        for (r in records) {
            if (DeviceLocal.contains(r.key)) continue
            val ok = runCatching {
                when (r.type) {
                    "s" -> put(r.key, r.value as? String ?: return@runCatching)
                    "b" -> put(r.key, r.value as? Boolean ?: return@runCatching)
                    "i", "l" -> put(r.key, (r.value as? Number)?.toLong() ?: return@runCatching)
                    "f", "d" -> put(r.key, (r.value as? Number)?.toDouble() ?: return@runCatching)
                    "ss" -> put(r.key, JSONArray(((r.value as? List<*>)?.filterIsInstance<String>()).orEmpty()))
                    else -> return@runCatching
                }
            }.isSuccess
            if (ok) applied++
        }
        return applied
    }

    fun favoritesFlow(): Flow<List<MediaItem>> = flowOf { favorites() }
    fun favorites(): List<MediaItem> = parseMedia(get(K.FAVORITES))
    fun addFavorite(m: MediaItem) {
        val list = favorites().filter { it.uniqueId != m.uniqueId } + m
        put(K.FAVORITES, encodeMedia(list))
    }
    fun removeFavorite(id: String) {
        put(K.FAVORITES, encodeMedia(favorites().filter { f -> f.uniqueId != id }))
    }

    fun sitesFlow(): Flow<List<Site>> = flowOf { sites() }
    fun sites(): List<Site> = parseSites(get(K.SITES))
    fun addSite(s: Site) {
        put(K.SITES, encodeSites(sites().filter { it.url != s.url } + s))
    }
    fun removeSite(url: String) {
        put(K.SITES, encodeSites(sites().filter { it.url != url }))
    }

    private fun encodeSites(list: List<Site>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("name", s.name).put("url", s.url))
        }
        return arr.toString()
    }

    private fun parseSites(s: String?): List<Site> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Site(name = o.optString("name").ifBlank { url }, url = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAll() {
        put(K.PROVIDERS, null)
        put(K.FAVORITES, null)
        put(K.CS3_REPOS, null)
        put(K.SITES, null)
        put(K.HISTORY, null)
    }

    fun historyFlow(): Flow<List<HistoryEntry>> = flowOf { history() }
    fun history(): List<HistoryEntry> = parseHistory(get(K.HISTORY))
    fun addHistory(e: HistoryEntry) {
        val next = (listOf(e) + history().filter { it.uniqueKey != e.uniqueKey }).take(200)
        put(K.HISTORY, encodeHistory(next))
    }
    fun clearHistory() {
        put(K.HISTORY, "[]")
    }
    fun removeHistory(uniqueKey: String) {
        val next = history().filter { it.uniqueKey != uniqueKey }
        put(K.HISTORY, encodeHistory(next))
    }

    fun historyPausedFlow(): Flow<Boolean> = flowOf { historyPaused() }
    fun historyPaused(): Boolean = (get(K.HISTORY_PAUSED) ?: "false").toBoolean()
    fun setHistoryPaused(paused: Boolean) {
        put(K.HISTORY_PAUSED, paused)
    }

    fun elementBlocksFlow(): Flow<List<String>> = flowOf { elementBlocks() }
    fun elementBlocks(): List<String> = parseStringList(get(K.ELEMENT_BLOCKS))
    fun addElementBlock(selector: String) {
        val cur = elementBlocks()
        if (selector in cur) return
        put(K.ELEMENT_BLOCKS, encodeStringList((cur + selector).take(200)))
    }
    fun removeLastElementBlock(): String? {
        val cur = elementBlocks()
        if (cur.isEmpty()) return null
        val last = cur.last()
        put(K.ELEMENT_BLOCKS, encodeStringList(cur.dropLast(1)))
        return last
    }
    fun clearElementBlocks() {
        put(K.ELEMENT_BLOCKS, "[]")
    }

    private fun encodeHistory(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject()
                    .put("pid", h.providerId)
                    .put("id", h.mediaId)
                    .put("type", h.type.name)
                    .put("title", h.title)
                    .put("poster", h.posterUrl ?: "")
                    .put("eid", h.episodeId)
                    .put("ename", h.episodeName)
                    .put("pos", h.positionMs)
                    .put("dur", h.durationMs)
                    .put("at", h.watchedAt)
            )
        }
        return arr.toString()
    }

    private fun parseHistory(s: String?): List<HistoryEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null
                else HistoryEntry(
                    providerId = o.optString("pid"),
                    mediaId = id,
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    title = o.optString("title"),
                    posterUrl = o.optString("poster").ifBlank { null },
                    episodeId = o.optString("eid"),
                    episodeName = o.optString("ename"),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    watchedAt = o.optLong("at", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeProviders(list: List<ProviderConfig>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("type", c.type.name)
                    .put("url", c.url)
                    .put("iconUrl", c.iconUrl ?: "")
                    .put("enabled", c.enabled)
                    .put("extra", c.extra ?: "")
                    .put("nsfw", c.nsfw ?: JSONObject.NULL)
                    .put("lang", c.lang ?: "")
            )
        }
        return arr.toString()
    }

    private fun parseProviders(s: String?): List<ProviderConfig> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ProviderConfig(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = runCatching { ProviderType.valueOf(o.optString("type")) }
                        .getOrDefault(ProviderType.STREMIO),
                    url = o.optString("url"),
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    enabled = o.optBoolean("enabled", true),
                    extra = o.optString("extra").ifBlank { null },
                    nsfw = o.opt("nsfw")?.takeUnless { it == JSONObject.NULL }?.let { o.optBoolean("nsfw") },
                    lang = o.optString("lang").ifBlank { null },
                )
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeRepos(list: List<Cs3Repo>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("description", r.description)
                    .put("kind", r.kind.name)
                    .put("iconUrl", r.iconUrl)
            )
        }
        return arr.toString()
    }

    private fun parseRepos(s: String?): List<Cs3Repo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Cs3Repo(
                    url = o.optString("url"),
                    name = o.optString("name").ifBlank { o.optString("url") },
                    description = o.optString("description"),
                    kind = runCatching { RepoKind.valueOf(o.optString("kind", "CS3")) }
                        .getOrDefault(RepoKind.CS3),
                    iconUrl = o.optString("iconUrl"),
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeCollections(list: List<Collection>): String {
        val arr = JSONArray()
        for (c in list) {
            val folders = JSONArray()
            for (f in c.folders) {
                val sources = JSONArray()
                for (s in f.sources) {
                    sources.put(
                        JSONObject()
                            .put("kind", s.kind.name)
                            .put("title", s.title)
                            .put("pid", s.providerId)
                            .put("cid", s.catalogId)
                            .put("type", s.type.name)
                            .put("raw", s.rawType)
                            .put("preset", s.tmdbPreset)
                            .put("spec", s.tmdbSpec)
                            .put("items", s.itemsJson)
                            .put("uid", s.uid)
                    )
                }
                folders.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("name", f.name)
                        .put("coverKind", f.coverKind)
                        .put("coverValue", f.coverValue)
                        .put("tileShape", f.tileShape)
                        .put("hideTitle", f.hideTitle)
                        .put("gifAlways", f.gifAlways)
                        .put("heroBackdrop", f.heroBackdropUrl)
                        .put("titleLogo", f.titleLogoUrl)
                        .put("sources", sources)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("coverKind", c.coverKind)
                    .put("coverValue", c.coverValue)
                    .put("tileShape", c.tileShape)
                    .put("pinToTop", c.pinToTop)
                    .put("viewMode", c.viewMode)
                    .put("showAllTab", c.showAllTab)
                    .put("backdrop", c.backdropUrl)
                    .put("folders", folders)
            )
        }
        return arr.toString()
    }

    private fun parseCollections(s: String?): List<Collection> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val foldersArr = o.optJSONArray("folders")
                val folders = ArrayList<CollectionFolder>()
                if (foldersArr != null) {
                    for (j in 0 until foldersArr.length()) {
                        val fo = foldersArr.optJSONObject(j) ?: continue
                        val fid = fo.optString("id")
                        val fname = fo.optString("name")
                        if (fid.isBlank() || fname.isBlank()) continue
                        val sourcesArr = fo.optJSONArray("sources")
                        val sources = ArrayList<CatalogSource>()
                        if (sourcesArr != null) {
                            for (k in 0 until sourcesArr.length()) {
                                val so = sourcesArr.optJSONObject(k) ?: continue
                                val kind = runCatching {
                                    CatalogSourceKind.valueOf(so.optString("kind", "TMDB"))
                                }.getOrDefault(CatalogSourceKind.TMDB)
                                val source = CatalogSource(
                                    kind = kind,
                                    title = so.optString("title"),
                                    providerId = so.optString("pid"),
                                    catalogId = so.optString("cid"),
                                    type = runCatching { MediaType.valueOf(so.optString("type")) }
                                        .getOrDefault(MediaType.UNKNOWN),
                                    rawType = so.optString("raw"),
                                    tmdbPreset = so.optString("preset"),
                                    tmdbSpec = so.optString("spec"),
                                    itemsJson = so.optString("items"),
                                    uid = so.optString("uid"),
                                )
                                val usable = when (kind) {
                                    CatalogSourceKind.TMDB ->
                                        TmdbPresets.byKey(source.tmdbPreset) != null ||
                                            (source.spec?.type != null)
                                    CatalogSourceKind.ITEMS -> source.itemCount > 0
                                    CatalogSourceKind.PROVIDER ->
                                        source.providerId.isNotBlank() &&
                                            source.catalogId.isNotBlank()
                                }
                                if (usable) sources.add(source)
                            }
                        }
                        folders.add(
                            CollectionFolder(
                                id = fid,
                                name = fname,
                                sources = sources.distinctBy { it.key },
                                coverKind = CoverKinds.normalize(fo.optString("coverKind")),
                                coverValue = fo.optString("coverValue"),
                                tileShape = TileShapes.normalize(fo.optString("tileShape")),
                                hideTitle = fo.optBoolean("hideTitle", false),
                                gifAlways = fo.optBoolean("gifAlways", false),
                                heroBackdropUrl = fo.optString("heroBackdrop"),
                                titleLogoUrl = fo.optString("titleLogo"),
                            )
                        )
                    }
                }
                Collection(
                    id = id,
                    name = name,
                    folders = folders,
                    coverKind = CoverKinds.normalize(o.optString("coverKind")),
                    coverValue = o.optString("coverValue"),
                    tileShape = TileShapes.normalize(o.optString("tileShape")),
                    pinToTop = o.optBoolean("pinToTop", false),
                    viewMode = CollectionViewModes.normalize(o.optString("viewMode")),
                    showAllTab = o.optBoolean("showAllTab", true),
                    backdropUrl = o.optString("backdrop"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCloneHosts(s: String?): Map<String, CloneEntry> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, CloneEntry>()
            val ids = o.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val e = o.optJSONObject(id) ?: continue
                val hosts = LinkedHashMap<String, String>()
                val ho = e.optJSONObject("hosts")
                if (ho != null) {
                    val hk = ho.keys()
                    while (hk.hasNext()) {
                        val k = hk.next()
                        hosts[k] = ho.optString(k)
                    }
                }
                out[id] = CloneEntry(
                    id = id,
                    name = e.optString("name"),
                    cloneOfId = e.optString("orig"),
                    mainNew = e.optString("main").takeIf { it.isNotBlank() },
                    hosts = hosts,
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun encodeCloneHosts(map: Map<String, CloneEntry>): String {
        val o = JSONObject()
        for ((id, e) in map) {
            val hosts = JSONObject()
            for ((k, v) in e.hosts) hosts.put(k, v)
            o.put(id, JSONObject().put("name", e.name).put("orig", e.cloneOfId)
                .put("main", e.mainNew.orEmpty()).put("hosts", hosts))
        }
        return o.toString()
    }

    private fun encodeMedia(list: List<MediaItem>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("pid", m.providerId)
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("type", m.type.name)
                    .put("poster", m.posterUrl ?: "")
                    .put("year", m.year ?: 0)
                    .put("overview", m.overview ?: "")
                    .put("backdrop", m.backdropUrl ?: "")
                    .put("rawType", m.rawType)
                    .put("rating", m.rating ?: JSONObject.NULL)
                    .put("originalTitle", m.originalTitle)
                    .put("nsfw", m.nsfw)
                    .put("quality", m.quality ?: "")
                    .put("nextEpisodeDate", m.nextEpisodeDate ?: "")
                    .put("metadataSource", m.metadataSource ?: "")
                    .put("genres", JSONArray(m.genres))
            )
        }
        return arr.toString()
    }

    private fun parseMedia(s: String?): List<MediaItem> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                MediaItem(
                    providerId = o.optString("pid"),
                    id = o.optString("id"),
                    title = o.optString("title"),
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    posterUrl = o.optString("poster").ifBlank { null },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    overview = o.optString("overview").ifBlank { null },
                    backdropUrl = o.optString("backdrop").ifBlank { null },
                    rawType = o.optString("rawType"),
                    rating = o.opt("rating")?.takeUnless { it == JSONObject.NULL }?.let { o.optDouble("rating") },
                    originalTitle = o.optString("originalTitle"),
                    nsfw = o.optBoolean("nsfw", false),
                    quality = o.optString("quality").ifBlank { null },
                    nextEpisodeDate = o.optString("nextEpisodeDate").ifBlank { null },
                    metadataSource = o.optString("metadataSource").ifBlank { null },
                    genres = o.optJSONArray("genres")?.let { arr ->
                        (0 until arr.length()).mapNotNull { index -> arr.optString(index).takeIf { it.isNotBlank() } }
                    }.orEmpty(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** One stored preference in a backup file's own terms. */
data class PrefRecord(
    val key: String,
    /** "s" string · "b" boolean · "i"/"l" int/long · "f"/"d" float/double · "ss" strings. */
    val type: String,
    val value: Any?,
)

/** Preference keys that must not leave (or arrive on) another device. The
 *  desktop keeps no device-bound secrets yet (no app lock, no per-device
 *  player tuning), so nothing is excluded today — the gate exists so a key
 *  that must stay local later has one obvious place to be listed. */
object DeviceLocal {
    val KEYS: Set<String> = setOf()
    fun contains(key: String): Boolean = key in KEYS
}
