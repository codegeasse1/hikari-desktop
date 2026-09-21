package com.hikari.app.providers

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One line of truth about a configured provider: did it start, and why not? */
data class ProviderStatus(
    val id: String,
    val name: String,
    val type: ProviderType,
    val loaded: Boolean,
    val error: String?,
)

class ProviderManager(private val store: AppStore) {

    private val _providers = MutableStateFlow<List<ContentProvider>>(emptyList())
    val providers: StateFlow<List<ContentProvider>> = _providers.asStateFlow()

    private val _statuses = MutableStateFlow<List<ProviderStatus>>(emptyList())
    val statuses: StateFlow<List<ProviderStatus>> = _statuses.asStateFlow()

    /** True once the first [refresh] has finished, so screens don't wait for a
     *  provider list that legitimately starts out empty on a fresh install. */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    /**
     * Already-instantiated providers, keyed by everything that can change what
     * an instance *is* (id, source url, and — for a local extension file — its
     * size+mtime, so reinstalling an updated build re-loads it).
     *
     * Instantiating a CS3 provider runs its plugin's dex→JVM conversion and
     * calls `load()`, which can take a second or more each. Re-creating all of
     * them on every refresh made an extension install feel like a stall long
     * after the download had finished (and uninstall just as bad), because the
     * refresh that follows it re-loaded every OTHER extension too.
     */
    private val instances = java.util.concurrent.ConcurrentHashMap<String, ContentProvider>()

    private val refreshLock = Mutex()

    suspend fun refresh() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val configs = store.providers()
            val keys = configs.associateWith { cacheKey(it) }
            // Drop the instances of providers that are gone or have changed.
            instances.keys.retainAll(keys.values.toHashSet())
            val statuses = mutableListOf<ProviderStatus>()
            val loaded = configs.mapNotNull { c ->
                val key = keys.getValue(c)
                instances[key]?.let { cached ->
                    statuses += ProviderStatus(c.id, c.name, c.type, loaded = true, error = null)
                    return@mapNotNull cached
                }
                try {
                    val p = instantiate(c)
                    if (p == null) {
                        statuses += ProviderStatus(c.id, c.name, c.type, loaded = false, error = "no provider instance")
                        null
                    } else {
                        instances[key] = p
                        statuses += ProviderStatus(c.id, c.name, c.type, loaded = true, error = null)
                        p
                    }
                } catch (t: Throwable) {
                    // One broken addon must never blank the whole list — skip it,
                    // but record WHY so the UI can show it instead of a blank Home.
                    val msg = t.message?.take(400) ?: t.javaClass.simpleName
                    statuses += ProviderStatus(c.id, c.name, c.type, loaded = false, error = msg)
                    System.err.println("Provider init failed for ${c.name} (${c.type}): $t")
                    null
                }
            }
            _providers.value = loaded
            _statuses.value = statuses
            _initialized.value = true
        }
    }

    /** Everything that identifies the *instance* a config needs. */
    private fun cacheKey(c: ProviderConfig): String {
        val f = c.url.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it) }.getOrNull() }
        val stamp = if (f != null && f.isFile) "${f.length()}:${f.lastModified()}" else ""
        return "${c.id}|${c.url}|$stamp"
    }

    /** Forgets one provider's cached instance (used after a reinstall). */
    fun invalidate(id: String) {
        instances.keys.removeAll { it.substringBefore('|') == id }
    }

    fun instantiate(c: ProviderConfig): ContentProvider? = when (c.type) {
        ProviderType.STREMIO -> StremioAddon(c)
        ProviderType.UNIVERSAL -> UniversalScraper(c)
        ProviderType.CS3 -> Cs3MainApiProvider(c)
        ProviderType.HIKARI -> HikariProviderAdapter(c)
        ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider(c)
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper(c)
        ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider(c)
        ProviderType.IPTV -> com.hikari.app.iptv.IptvProvider(c)
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}
