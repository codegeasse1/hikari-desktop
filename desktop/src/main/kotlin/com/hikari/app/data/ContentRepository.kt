package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class ContentRepository(private val manager: ProviderManager) {

    private inline fun <T> cancellableCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private data class CachedRow(val row: CatalogRow, val at: Long)
    private val rowCache = ConcurrentHashMap<String, CachedRow>()
    private val ROW_CACHE_TTL_MS = 10 * 60_000L // 10 min like CloudStream

    private data class CachedMeta(val item: MediaItem, val at: Long)
    private val metaCache = ConcurrentHashMap<String, CachedMeta>()
    private val META_CACHE_TTL_MS = 10 * 60_000L

    private data class CachedEps(val eps: List<Episode>, val at: Long)
    private val epsCache = ConcurrentHashMap<String, CachedEps>()
    private val EPS_CACHE_TTL_MS = 10 * 60_000L

    /**
     * Superfast Home rows - like CloudStream Android app.
     * - Increased concurrency: 6 providers, 12 catalogs parallel (was 2 and 4)
     * - Reduced timeouts: 60s provider, 30s catalog (was 180s and 120s)
     * - All providers show 2 rows each for variety (was 1)
     * - 10 min cache TTL
     * - Progressive delivery via onRow
     */
    suspend fun homeRows(
        providerId: String? = null,
        force: Boolean = false,
        onRow: (CatalogRow) -> Unit = {},
    ): List<CatalogRow> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        val allProviders = providerId == null
        val useProviders = if (allProviders) active.take(12) else active
        val catalogsLimit = if (allProviders) 2 else 14
        val itemsLimit = 30
        val providerGate = Semaphore(6) // was 2
        val catalogGate = Semaphore(12) // was 4
        val now = System.currentTimeMillis()
        val rows = coroutineScope {
            useProviders.map { p ->
                async {
                    cancellableCatching {
                        providerGate.withPermit {
                            withTimeoutOrNull(60_000) { // was 180s
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(catalogsLimit)
                                coroutineScope {
                                    catalogs.map { c ->
                                        async {
                                            catalogGate.withPermit {
                                                val key = "${p.config.id}|${c.type}|${c.id}"
                                                val cached = rowCache[key]
                                                if (!force && cached != null && now - cached.at < ROW_CACHE_TTL_MS) {
                                                    onRow(cached.row)
                                                    return@async cached.row
                                                }
                                                val items = withTimeoutOrNull(30_000) { // was 120s
                                                    cancellableCatching { p.getCatalog(c, 1) }.getOrDefault(emptyList())
                                                }.orEmpty().distinctBy { it.uniqueId }.take(itemsLimit)
                                                if (items.isEmpty()) null
                                                else {
                                                    val raw = CatalogRow(
                                                        providerId = p.config.id,
                                                        providerName = p.config.name,
                                                        title = c.name,
                                                        items = items,
                                                        key = key,
                                                        catalogId = c.id,
                                                        type = c.type,
                                                        rawType = c.rawType,
                                                    )
                                                    val row = translateRows(listOf(raw)).firstOrNull() ?: raw
                                                    rowCache[key] = CachedRow(row, System.currentTimeMillis())
                                                    onRow(row)
                                                    row
                                                }
                                            }
                                        }
                                    }.awaitAll().filterNotNull()
                                }
                            } ?: emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        rows
    }

    fun searchStreaming(
        query: String,
        page: Int = 1,
        providerIds: Set<String>? = null,
    ): Flow<List<MediaItem>> = flow {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerIds.isNullOrEmpty() || it.config.id in providerIds)
        }
        if (active.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val aggregate = MutableStateFlow<List<MediaItem>>(emptyList())
            val gate = Semaphore(6) // was 4
            val jobs = active.map { p ->
                scope.async {
                    gate.withPermit {
                        val items = cancellableCatching {
                            withTimeoutOrNull(60_000) { // was 240s
                                p.search(query, page)
                            } ?: emptyList()
                        }.getOrDefault(emptyList())
                        aggregate.value = (aggregate.value + items).distinctBy { it.uniqueId }
                    }
                }
            }
            val started = System.currentTimeMillis()
            var lastEmitted: List<MediaItem>? = null
            while (true) {
                val allDone = jobs.all { it.isCompleted }
                val timedOut = System.currentTimeMillis() - started > 70_000 // was 250s
                if (allDone || timedOut) {
                    emit(translateItems(aggregate.value))
                    break
                }
                val snapshot = aggregate.value
                if (snapshot !== lastEmitted && snapshot.isNotEmpty()) {
                    emit(snapshot)
                    lastEmitted = snapshot
                }
                delay(80) // was 120
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Superfast streams - first-non-empty-wins, 20s timeout (was 45s), 50ms poll (was 80ms)
     */
    suspend fun streamsFor(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val all = manager.providers.value.filter { it.config.enabled }
            val origin = manager.byId(item.providerId)
            val targets = if (origin?.config?.type == ProviderType.STREMIO) {
                all.filter { p -> p.config.id == item.providerId || p.config.type == ProviderType.STREMIO }
            } else {
                listOfNotNull(origin)
            }
            if (targets.isEmpty()) return@withContext emptyList()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val jobs = targets.map { p ->
                    scope.async {
                        cancellableCatching {
                            withTimeoutOrNull(20_000) { // was 45s
                                p.getStreams(item, episode)
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }
                var result: List<StreamSource> = emptyList()
                val started = System.currentTimeMillis()
                while (true) {
                    for (j in jobs) {
                        if (j.isCompleted) {
                            val r = runCatching { j.getCompleted() }.getOrDefault(emptyList())
                            if (r.isNotEmpty()) {
                                result = r
                                break
                            }
                        }
                    }
                    if (result.isNotEmpty() || jobs.all { it.isCompleted }) break
                    if (System.currentTimeMillis() - started > 20_000) break
                    delay(50) // was 80
                }
                jobs.forEach { it.cancel() }
                result.distinctBy { it.infoHash ?: it.url }
            } finally {
                scope.cancel()
            }
        }

    /**
     * Superfast meta - cached 10 min, 8s timeout, parallel fallback 5s
     */
    suspend fun metaFor(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val cacheKey = "${item.providerId}|${item.id}"
        val cached = metaCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.at < META_CACHE_TTL_MS) {
            return@withContext translateItem(cached.item)
        }

        var result = manager.byId(item.providerId)
            ?.let { withTimeoutOrNull(8_000) { // was 15s
                cancellableCatching { it.getMeta(item) }.getOrDefault(item)
            } } ?: item

        if (result.backdropUrl != null && result.overview != null) {
            metaCache[cacheKey] = CachedMeta(result, System.currentTimeMillis())
            return@withContext translateItem(result)
        }

        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        if (others.isNotEmpty()) {
            val deferred = others.map { alt ->
                async {
                    withTimeoutOrNull(5_000) { // was 8s
                        cancellableCatching { alt.getMeta(result) }.getOrNull()
                    }
                }
            }
            for (d in deferred) {
                val r = d.await() ?: continue
                if (result.backdropUrl == null && r.backdropUrl != null) {
                    result = result.copy(backdropUrl = r.backdropUrl)
                }
                if (result.overview == null && r.overview != null) result = result.copy(overview = r.overview)
                if (result.genres.isEmpty() && r.genres.isNotEmpty()) result = result.copy(genres = r.genres)
                if (result.year == null && r.year != null) result = result.copy(year = r.year)
                if (result.backdropUrl != null && result.overview != null) break
            }
        }

        metaCache[cacheKey] = CachedMeta(result, System.currentTimeMillis())
        translateItem(result)
    }

    /**
     * Superfast episodes - parallel first-non-empty-wins, cached 10 min.
     * Was sequential 12s each (4-5 sec delay), now parallel 8s.
     */
    suspend fun episodesFor(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES) return@withContext null

        val cacheKey = "${item.providerId}|${item.id}"
        val cached = epsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.at < EPS_CACHE_TTL_MS) {
            return@withContext translateEpisodes(item.providerId, cached.eps)
        }

        val origin = manager.byId(item.providerId)
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        val allProviders = listOfNotNull(origin) + others

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = allProviders.map { p ->
                scope.async {
                    withTimeoutOrNull(8_000) { // was 12s
                        cancellableCatching { p.getEpisodes(item) }.getOrNull()
                    } ?: emptyList()
                }
            }

            var result: List<Episode> = emptyList()
            val started = System.currentTimeMillis()
            while (true) {
                for (j in jobs) {
                    if (j.isCompleted) {
                        val eps = runCatching { j.getCompleted() }.getOrDefault(emptyList())
                        if (eps.isNotEmpty()) {
                            result = eps
                            break
                        }
                    }
                }
                if (result.isNotEmpty() || jobs.all { it.isCompleted }) break
                if (System.currentTimeMillis() - started > 8_000) break
                delay(50)
            }
            jobs.forEach { it.cancel() }

            if (result.isNotEmpty()) {
                epsCache[cacheKey] = CachedEps(result, System.currentTimeMillis())
                return@withContext translateEpisodes(item.providerId, result)
            }
            null
        } finally {
            scope.cancel()
        }
    }

    // ---- Translate ----
    private suspend fun translateRows(rows: List<CatalogRow>): List<CatalogRow> {
        val on = Translator.enabledIds()
        if (on.isEmpty()) return rows
        return rows.map { row ->
            if (row.providerId !in on) return@map row
            val newTitle = Translator.translate(row.title)
            val items = translateItems(row.items)
            if (newTitle == row.title && items === row.items) row
            else row.copy(title = newTitle, items = items)
        }
    }

    private suspend fun translateItems(items: List<MediaItem>): List<MediaItem> {
        val on = Translator.enabledIds()
        if (on.isEmpty()) return items
        val toTranslate = items.filter { it.providerId in on }
        if (toTranslate.isEmpty()) return items
        val translations = Translator.translateAll(toTranslate.map { it.title })
        var anyChanged = false
        val changed = toTranslate.mapIndexed { i, it ->
            val t = translations[i]
            if (t != it.title) {
                anyChanged = true
                it.copy(title = t)
            } else it
        }
        if (!anyChanged) return items
        val byId = changed.associateBy { it.uniqueId }
        return items.map { byId[it.uniqueId] ?: it }
    }

    private suspend fun translateItem(item: MediaItem): MediaItem {
        if (item.providerId !in Translator.enabledIds()) return item
        val title = Translator.translate(item.title)
        val overview = item.overview?.let { Translator.translate(it) }
        if (title == item.title && overview == item.overview) return item
        return item.copy(title = title, overview = overview)
    }

    private suspend fun translateEpisodes(providerId: String, eps: List<Episode>): List<Episode> {
        if (providerId !in Translator.enabledIds()) return eps
        val names = eps.map { it.name ?: "" }
        val translations = Translator.translateAll(names)
        var anyChanged = false
        val out = eps.mapIndexed { i, e ->
            val t = translations[i]
            if (e.name != null && t.isNotEmpty() && t != e.name) {
                anyChanged = true
                e.copy(name = t)
            } else e
        }
        return if (anyChanged) out else eps
    }
}
