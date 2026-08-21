package com.hikari.app.providers

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val configs = store.providers()
        val statuses = mutableListOf<ProviderStatus>()
        val loaded = configs.mapNotNull { c ->
            try {
                val p = instantiate(c)
                if (p == null) {
                    statuses += ProviderStatus(c.id, c.name, c.type, loaded = false, error = "no provider instance")
                    null
                } else {
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

    fun instantiate(c: ProviderConfig): ContentProvider? = when (c.type) {
        ProviderType.STREMIO -> StremioAddon(c)
        ProviderType.UNIVERSAL -> UniversalScraper(c)
        ProviderType.CS3 -> Cs3MainApiProvider(c)
        ProviderType.HIKARI -> HikariProviderAdapter(c)
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}
