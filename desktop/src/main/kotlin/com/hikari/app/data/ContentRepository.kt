package com.hikari.app.data

import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ContentRepository(private val manager: ProviderManager) {

    /** Like runCatching but re-throws CancellationException — a coroutine that
     *  gets cancelled (e.g. the user switches tabs while Home is loading every
     *  provider) must stop its work instead of swallowing the cancellation and
     *  keeping the network busy in the background. */
    private inline fun <T> cancellableCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private data class CachedRow(val row: CatalogRow, val at: Long)
    private val rowCache = HashMap<String, CachedRow>()
    private val ROW_CACHE_TTL_MS = 5 * 60_000L

    private companion object {
        /** How long [streamsFor] waits for the FIRST playable source before
         *  handing back what it has. Playback starts on the first server, not on
         *  the slowest extension; the rest of the sweep keeps running. */
        const val FIRST_SOURCE_WAIT_MS = 14_000L

        /** How long one provider gets to answer a stream lookup before it is
         *  left behind by the rest of the family. */
        const val SOURCE_BUDGET_MS = 40_000L

        /** The same, for the title-search wave (search + episodes + streams). */
        const val TITLE_BUDGET_MS = 55_000L

        /** Providers asked at the same time in a wave. More than this starves
         *  the shared HTTP pools for no visible gain. */
        const val SOURCES_CONCURRENCY = 4

        /** How many OTHER-engine providers the third wave asks. */
        const val CROSS_SWEEP_MAX = 12

        /** How long a finished sweep stays in memory (so re-opening a title is
         *  instant) before it is dropped. */
        const val SWEEP_KEEP_MS = 10 * 60_000L
    }

    /**
     * Loads Home rows. Catalogs inside a provider are fetched IN PARALLEL but
     * through a small semaphore so a slow network can't flood the IO pool with
     * hundreds of simultaneous requests (which froze the UI on weak devices).
     * Each catalog gets its own timeout so one dead catalog never eats the
     * whole provider's budget, and rows carry a stable unique key so addons
     * with several same-named catalogs (e.g. "Streaming Catalogs" → movies +
     * series both called "Netflix") can never crash the LazyColumn.
     *
     * Rows are delivered through [onRow] the moment each catalog finishes, so
     * the Home screen fills in progressively instead of waiting for the
     * slowest provider (the old all-or-nothing behaviour made "All providers"
     * sit on a spinner for a long time). Finished rows are cached (5 min TTL)
     * so re-visiting Home is instant; pass [force] to bypass the cache.
     */
    suspend fun homeRows(
        providerId: String? = null,
        force: Boolean = false,
        onRow: (CatalogRow) -> Unit = {},
    ): List<CatalogRow> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        // Bound the total work — this is what keeps Home feeling like a native
        // app instead of a webview. "All providers" shows the FIRST catalog of
        // up to 8 providers (one row each, so you see variety fast); a single
        // provider gets up to 10 of its catalog rows. Firing every catalog of
        // every installed extension at once froze/crashed the app on any
        // machine, however powerful.
        val allProviders = providerId == null
        val useProviders = if (allProviders) active.take(8) else active
        val catalogsLimit = if (allProviders) 1 else 10
        val itemsLimit = 20
        // GLOBAL gates shared by ALL providers (not per-provider): with dozens
        // of installed extensions, per-provider limits multiplied into hundreds
        // of concurrent network requests which saturated the IO pool and froze
        // the UI (ANR). 2 providers run their catalogs in parallel, and at most
        // 4 catalog fetches exist across the whole app at once.
        val providerGate = Semaphore(2)
        val catalogGate = Semaphore(4)
        val now = System.currentTimeMillis()
        val rows = coroutineScope {
            useProviders.map { p ->
                async {
                    cancellableCatching {
                        providerGate.withPermit {
                            withTimeoutOrNull(180_000) {
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
                                                val items = withTimeoutOrNull(120_000) {
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
                                                    val row = translateRows(listOf(raw))[0]
                                                    rowCache[key] = CachedRow(row, System.currentTimeMillis())
                                                    onRow(row)
                                                    row
                                                }
                                            }
                                        }
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        rows
    }

    /** Searches across every enabled provider, or only the given subset.
     *  `null`/empty = all providers.
     *
     *  Results STREAM IN as each provider finishes instead of waiting for ALL
     *  of them: a fast provider's hits appear immediately, and one dead/slow
     *  provider can no longer blank the whole screen or delay everything. The
     *  final emission is the full deduplicated aggregate. */
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
            // Searching across MANY providers at once (search-all runs every
            // installed extension) would fire hundreds of requests at the same
            // time and starve the IO pool — same ANR class as Home loading.
            // At most 4 providers search concurrently; the rest queue up.
            val gate = Semaphore(4)
            val jobs = active.map { p ->
                scope.async {
                    gate.withPermit {
                        val items = cancellableCatching {
                            // Generous per-provider budget — heavy scrapers (e.g.
                            // MRDS) fetch several pages AND download/decrypt every
                            // poster into a data: URI before returning, which can
                            // take 1-3 minutes on a slow network. CloudStream has
                            // no such cap, so it shows those results while a short
                            // cap here used to blank them ("Nothing matched").
                            withTimeoutOrNull(240_000) { p.search(query, page) } ?: emptyList()
                        }.getOrDefault(emptyList())
                        aggregate.value = (aggregate.value + items).distinctBy { it.uniqueId }
                    }
                }
            }
            // Poll-and-emit the running aggregate so the UI shows each
            // provider's hits the moment they land.
            val started = System.currentTimeMillis()
            var lastEmitted: List<MediaItem>? = null
            while (true) {
                val allDone = jobs.all { it.isCompleted }
                val timedOut = System.currentTimeMillis() - started > 250_000
                if (allDone || timedOut) {
                    emit(translateItems(aggregate.value))
                    break
                }
                val snapshot = aggregate.value
                if (snapshot !== lastEmitted) {
                    emit(snapshot)
                    lastEmitted = snapshot
                }
                delay(120)
            }
        } finally {
            scope.cancel()
        }
    }

    // ── server lookup: the cross-extension sweep ────────────────────────────
    //
    // What the user asks of this app (and what the Android app does): a title's
    // servers come from EVERY installed extension that can serve it, not only
    // from the one whose catalog the title was opened in. Five nuvio scrapers
    // installed = five scrapers asked; four Stremio addons = four addons asked,
    // exactly like the real Stremio client; and an extension of another engine
    // can still rescue a title by name.

    /** One running sweep, for one title/episode of one provider. */
    private class Sweep(val startedAt: Long) {
        val sources = java.util.Collections.synchronizedList(mutableListOf<StreamSource>())
        /** providerId → why that provider gave nothing (for the empty state). */
        val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Volatile
        var done = false

        fun snapshot(): List<StreamSource> = synchronized(sources) { ArrayList(sources) }

        fun add(list: List<StreamSource>): Boolean {
            if (list.isEmpty()) return false
            var added = false
            synchronized(sources) {
                val known = sources.mapTo(HashSet()) { it.infoHash ?: it.url }
                for (s in list) {
                    val key = s.infoHash ?: s.url
                    if (key.isBlank() || known.add(key)) {
                        sources.add(s)
                        added = true
                    }
                }
            }
            return added
        }
    }

    private val sweeps = java.util.concurrent.ConcurrentHashMap<String, Sweep>()
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sweepKey(item: MediaItem, episode: Episode?): String =
        item.uniqueId + "|" + (episode?.id ?: "")

    /**
     * The servers found so far for a title/episode, without waiting for
     * anything: a sweep keeps running after [streamsFor] has already returned
     * (playback starts on the first server, not on the slowest extension), and
     * this is how the detail screen and the player pick up the later ones.
     */
    fun sweepSnapshot(item: MediaItem, episode: Episode?): List<StreamSource> =
        sweeps[sweepKey(item, episode)]?.snapshot().orEmpty()

    /** `providerId → reason` for every provider in the sweep for this
     *  title/episode that came back with nothing playable. */
    fun sweepErrors(item: MediaItem, episode: Episode?): Map<String, String> =
        sweeps[sweepKey(item, episode)]?.errors?.toMap().orEmpty()

    /** The configured name of a provider id — what the "nothing found" list
     *  prints next to a reason. */
    fun providerName(id: String): String =
        manager.providers.value.firstOrNull { it.config.id == id }?.config?.name ?: id

    /** True while the sweep for this title/episode is still asking providers. */
    fun sweepRunning(item: MediaItem, episode: Episode?): Boolean =
        sweeps[sweepKey(item, episode)]?.done == false

    /**
     * Asks every provider that can serve this title, and returns as soon as
     * there is something playable.
     *
     * The sweep itself is three waves, because coverage and speed pull in
     * opposite directions:
     *  1. the origin's own engine family, in parallel, with the same id — nuvio
     *     providers all speak TMDB ids, Stremio addons all speak the id in the
     *     manifest, so this wave is exact and it is usually enough;
     *  2. a title search on the same family — a scraper can index the same film
     *     under a different id (or a localized title);
     *  3. a title sweep over the OTHER engines, so a CloudStream plugin can be
     *     rescued by a nuvio scraper the user also has installed.
     */
    suspend fun streamsFor(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val key = sweepKey(item, episode)
            val existing = sweeps[key]
            val sweep = if (existing != null && !existing.done) existing else {
                Sweep(System.currentTimeMillis()).also {
                    sweeps[key] = it
                    sweepScope.launch {
                        runCatching { runSweep(item, episode, it) }
                        it.done = true
                        // A finished sweep is kept for a while so re-opening the
                        // title is instant, then dropped (it can be hundreds of
                        // sources and it is stale anyway).
                        sweepScope.launch {
                            delay(SWEEP_KEEP_MS)
                            sweeps.remove(key, it)
                        }
                    }
                }
            }
            val deadline = System.currentTimeMillis() + FIRST_SOURCE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val now = sweep.snapshot()
                if (now.isNotEmpty()) return@withContext now
                if (sweep.done) break
                delay(90)
            }
            sweep.snapshot()
        }

    private suspend fun runSweep(item: MediaItem, episode: Episode?, sweep: Sweep) {
        val enabled = manager.providers.value.filter { it.config.enabled }
        if (enabled.isEmpty()) return
        val origin = manager.byId(item.providerId)
        val originType = origin?.config?.type

        // ── wave 1: the origin's engine family, same id ─────────────────────
        val family = enabled.filter { originType != null && it.config.type == originType }
        askAll(family, item, episode, sweep)

        // ── wave 2: the same family, by TITLE ───────────────────────────────
        if (sweep.snapshot().isEmpty()) {
            askAllByTitle(family, item, episode, sweep)
        }

        // ── wave 3: every other engine, by TITLE ────────────────────────────
        if (sweep.snapshot().isEmpty()) {
            val others = enabled
                .filter { it.config.type != originType }
                .sortedByDescending { it.config.id == item.providerId }
                .take(CROSS_SWEEP_MAX)
            askAllByTitle(others, item, episode, sweep)
        }
    }

    /** Asks [providers] for the item itself, in parallel, bounded by
     *  [ProviderGate] (one call per provider at a time) and a per-provider
     *  budget. */
    private suspend fun askAll(
        providers: List<ContentProvider>,
        item: MediaItem,
        episode: Episode?,
        sweep: Sweep,
    ) {
        coroutineScope {
            val gate = Semaphore(SOURCES_CONCURRENCY)
            providers.map { p ->
                async {
                    gate.withPermit {
                        val found = cancellableCatching {
                            withTimeoutOrNull(SOURCE_BUDGET_MS) {
                                com.hikari.app.providers.ProviderGate.withProvider(p.config.id) {
                                    p.getStreams(item, episode)
                                }
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                        if (found.isEmpty()) recordStreamError(p, sweep)
                        else sweep.add(found.map { tag(it, p, item) })
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * The same, for the engines that cannot resolve the origin's id: search
     * them by title, take the best match, and ask that for its streams.
     *
     * This is the cross-repo pass the Android app runs for every title, and it
     * is what turns "no playable source" into a list: a nuvio scraper knows the
     * film by TMDB id, a CloudStream plugin by its own slug, and neither can be
     * asked with the other's id.
     */
    private suspend fun askAllByTitle(
        providers: List<ContentProvider>,
        item: MediaItem,
        episode: Episode?,
        sweep: Sweep,
    ) {
        val query = item.searchTitle
        if (query.isBlank() || providers.isEmpty()) return
        coroutineScope {
            val gate = Semaphore(SOURCES_CONCURRENCY)
            providers.map { p ->
                async {
                    gate.withPermit {
                        val found = cancellableCatching {
                            withTimeoutOrNull(TITLE_BUDGET_MS) {
                                com.hikari.app.providers.ProviderGate.withProvider(p.config.id) {
                                    // No match on this engine = this engine cannot
                                    // serve the title by name either.
                                    val hit = bestMatch(item, p.search(query, 1))
                                        ?: return@withProvider emptyList()
                                    // A series needs the SAME episode at the other
                                    // end, not episode 1.
                                    if (episode != null) {
                                        val ep = matchEpisode(p, hit, episode)
                                            ?: return@withProvider emptyList()
                                        p.getStreams(hit, ep)
                                    } else {
                                        p.getStreams(hit, null)
                                    }
                                }
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                        if (found.isEmpty()) recordStreamError(p, sweep)
                        else sweep.add(found.map { tag(it, p, item) })
                    }
                }
            }.awaitAll()
        }
    }

    /** The candidate whose title (and year, when both know one) matches the
     *  item best — the same "is this the same film?" test the Android sweep
     *  uses, on normalized titles. */
    private fun bestMatch(item: MediaItem, candidates: List<MediaItem>): MediaItem? {
        if (candidates.isEmpty()) return null
        val want = normalizeTitle(item.searchTitle)
        if (want.isBlank()) return null
        fun score(c: MediaItem): Int {
            val got = normalizeTitle(c.title)
            var s = when {
                got == want -> 100
                got.startsWith(want) || want.startsWith(got) -> 80
                got.contains(want) || want.contains(got) -> 60
                else -> 0
            }
            if (s > 0 && item.year != null && c.year != null) {
                s += if (item.year == c.year) 20 else if (kotlin.math.abs(item.year - c.year) <= 1) 5 else -30
            }
            if (s > 0 && item.type != MediaType.UNKNOWN && c.type == item.type) s += 10
            return s
        }
        return candidates.map { it to score(it) }.filter { it.second >= 60 }
            .maxByOrNull { it.second }?.first
    }

    /** The episode of [hit] that corresponds to [want] (same season+number when
     *  the provider numbers seasons, same number otherwise). Null when the
     *  provider has no such episode — better than playing episode 1 of the
     *  wrong season. */
    private suspend fun matchEpisode(
        p: ContentProvider,
        hit: MediaItem,
        want: Episode,
    ): Episode? {
        val eps = cancellableCatching { p.getEpisodes(hit) }.getOrNull().orEmpty()
        if (eps.isEmpty()) return null
        return eps.firstOrNull { it.number == want.number && it.season == want.season }
            ?: eps.firstOrNull { it.number == want.number && it.season == 1 }
            ?: eps.firstOrNull { it.number == want.number }
    }

    private fun tag(source: StreamSource, p: ContentProvider, item: MediaItem): StreamSource =
        if (p.config.id == item.providerId) source
        else source.copy(name = p.config.name.trim().ifBlank { "Extension" } + " · " + source.name)

    private fun recordStreamError(p: ContentProvider, sweep: Sweep) {
        val why = runCatching { p.lastStreamError() }.getOrNull()
        if (!why.isNullOrBlank()) sweep.errors[p.config.id] = why
    }

    private fun normalizeTitle(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()

    /** Enriches an item with the origin addon's full meta (backdrop, overview,
     *  genres, year). If that addon's meta is thin, the next addon that knows
     *  the title fills in the gaps — so a banner/detail never stay blank just
     *  because one catalog addon serves minimal metadata. */
    suspend fun metaFor(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        var result = manager.byId(item.providerId)
            ?.let { withTimeoutOrNull(15_000) { cancellableCatching { it.getMeta(item) }.getOrDefault(item) } }
            ?: item
        if (result.backdropUrl != null && result.overview != null) return@withContext translateItem(result)
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        for (alt in others) {
            val r = withTimeoutOrNull(8_000) { cancellableCatching { alt.getMeta(result) }.getOrDefault(result) }
                ?: continue
            if (result.backdropUrl == null && r.backdropUrl != null) {
                result = result.copy(backdropUrl = r.backdropUrl)
            }
            if (result.overview == null && r.overview != null) result = result.copy(overview = r.overview)
            if (result.genres.isEmpty() && r.genres.isNotEmpty()) result = result.copy(genres = r.genres)
            if (result.year == null && r.year != null) result = result.copy(year = r.year)
            if (result.backdropUrl != null && result.overview != null) break
        }
        translateItem(result)
    }

    /** Episodes from the origin addon, falling back to the first other addon
     *  that can list them (some catalog addons serve videos for series via a
     *  different addon, e.g. Cinemeta-backed ids). */
    suspend fun episodesFor(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES) return@withContext null
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        val ordered = listOfNotNull(manager.byId(item.providerId)) + others
        for (p in ordered) {
            val eps = (withTimeoutOrNull(12_000) {
                cancellableCatching { p.getEpisodes(item) }.getOrNull() ?: emptyList()
            }) ?: emptyList()
            if (eps.isNotEmpty()) return@withContext translateEpisodes(item.providerId, eps)
        }
        null
    }

    // ---- Per-extension auto-translate (app content → English) ----
    // Only extensions with "always translate" on are touched; every other
    // provider's titles pass through untouched.

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
