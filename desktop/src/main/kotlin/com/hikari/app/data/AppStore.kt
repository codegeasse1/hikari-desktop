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
                runCatching { json = JSONObject(file.readText()) }.onFailure { file.delete() }
            }
        }
    }

    private fun save() {
        synchronized(lock) {
            runCatching { file.parentFile?.mkdirs(); file.writeText(json.toString()) }
            version.value++
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
        const val TRANSLATE_PROVIDERS = "translateProviders"
        const val TRANSLATE_CACHE = "translateCache"
    }

    fun homeProviderFlow(): Flow<String> = flowOf { homeProvider() }
    fun homeProvider(): String = get(K.HOME_PROVIDER) ?: ""
    fun setHomeProvider(id: String) {
        put(K.HOME_PROVIDER, id)
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
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
