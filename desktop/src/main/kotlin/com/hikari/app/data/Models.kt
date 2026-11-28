package com.hikari.app.data

enum class ProviderType {
    STREMIO, UNIVERSAL, CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, IPTV,
    VEGA, MANGA, SORA, ANYMEX, ANYMEX_MANGA, ZANGETSU, FORK_VIDEO;

    /** Which section of the player's server chooser a source from this engine
     * belongs to, so the picker reads as one group per engine. */
    val groupLabel: String
        get() = when (this) {
            STREMIO -> "Stremio"
            NUVIO -> "Nuvio"
            VEGA -> "Vega"
            CS3 -> "CloudStream"
            SKYSTREAM -> "SkyStream"
            ANIYOMI -> "Aniyomi"
            MANGA -> "Manga"
            SORA -> "Sora"
            ANYMEX, ANYMEX_MANGA -> "Anymex"
            ZANGETSU -> "Zangetsu"
            FORK_VIDEO -> "Fork"
            IPTV -> "IPTV"
            HIKARI, UNIVERSAL -> "Hikari"
        }
}

data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val url: String = "",
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val extra: String? = null,
    val nsfw: Boolean? = null,
    val lang: String? = null,
)

/** A CloudStream-style plugin repository (repo.json → pluginLists → plugin list). */
enum class RepoKind { CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, VEGA, SORA, ANYMEX, LNREADER, ZANGETSU, KOTATSU }

/** A plugin repository, either CloudStream (.cs3) or Hikari (.hiki) style. */
data class Cs3Repo(
    val url: String,
    val name: String,
    val description: String = "",
    val kind: RepoKind = RepoKind.CS3,
    val iconUrl: String = "",
)

/** A single installable plugin entry from a CloudStream repository. */
data class Cs3RepoPlugin(
    val name: String,
    val description: String = "",
    val url: String,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
    val version: Int = 1,
    val tvTypes: List<String> = emptyList(),
    val fileHash: String? = null,
    /**
     * Where to find this entry's icon when the repo listing itself declares
     * none. SkyStream `.sky` entries carry an `addons` array of Stremio
     * manifest URLs, and that manifest's `logo` is the extension's real icon
     * (the `.sky`'s own plugin.json has no icon field at all) — the first
     * addon URL is captured here so the row can resolve a logo lazily.
     */
    val iconManifest: String? = null,
    /** The extension's own site host, the last-resort icon source: the first
     *  real entry in `domains`, else the `baseUrl`/`url` host. Null when the
     *  listing names only a placeholder host (see the SkyStream manager). */
    val iconHost: String? = null,
    val contentKind: String = "",
    val pluginLang: String? = null,
    val pkg: String = "",
    val sourceMeta: String = "",
    val nsfw: Boolean = false,
)

/** Per-repo plugin-list loading state shown in the Extensions screen. */
data class RepoLoadState(
    val loading: Boolean = false,
    val error: String? = null,
)

enum class MediaType { MOVIE, SERIES, UNKNOWN }

fun isSiteScraperProviderId(providerId: String): Boolean {
    val p = providerId.lowercase()
    return p.startsWith("cs3|") || p.startsWith("hiki|") || p.startsWith("sky|") ||
        p.startsWith("aniyomi|") || p.startsWith("vega|") || p.startsWith("sora|") ||
        p.startsWith("anymex|") || p.startsWith("uni|") || p.startsWith("forkvideo|")
}

fun screenTypeFor(item: MediaItem, episodeCount: Int?): MediaType {
    if (episodeCount == null || episodeCount <= 0 || !isSiteScraperProviderId(item.providerId)) return item.type
    return when {
        episodeCount == 1 && item.type != MediaType.MOVIE -> MediaType.MOVIE
        episodeCount >= 2 && item.type != MediaType.SERIES -> MediaType.SERIES
        else -> item.type
    }
}

object SingleVideoEvidence {
    private const val MAX_IDS = 300
    private val ids = LinkedHashMap<String, Unit>(MAX_IDS, 0.75f, true)
    @Synchronized fun note(uniqueId: String) {
        if (uniqueId.isBlank()) return
        ids[uniqueId] = Unit
        while (ids.size > MAX_IDS) ids.remove(ids.keys.first())
    }
    @Synchronized fun isSingle(uniqueId: String): Boolean = ids.containsKey(uniqueId)
}

/** A clone-site row: a normal provider config with per-host overrides. */
data class CloneEntry(
    val id: String,
    val name: String,
    val cloneOfId: String,
    val mainNew: String? = null,
    val hosts: Map<String, String> = emptyMap(),
)

data class ProviderFolder(val name: String, val ids: List<String>)

/** A user-added website opened in the ad-free web view. */
data class Site(
    val name: String,
    val url: String,
)

/** A Tampermonkey-style userscript that runs inside the WebView only. */
data class Userscript(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val code: String,
)

data class MediaItem(
    val providerId: String,
    val id: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
    /** The addon's OWN type string (e.g. "tv", "anime", "channel"). The Stremio
     *  protocol puts this literal string in /catalog /meta /stream URLs, and
     *  many addons refuse requests sent with a different type segment. */
    val rawType: String = "",
    /**
     * The title a PROVIDER knows this item by, when it differs from [title].
     *
     * [title] is what the USER reads and, with a TMDB content language set, is
     * TMDB's localized name; the extensions still index the ORIGINAL name, so
     * searching them with the localized one finds nothing. TMDB hands us
     * `original_title`/`original_name` in the same response it localizes from,
     * so this is filled in for every TMDB-sourced item. Blank for an extension
     * item, whose own [title] already IS the name its sites use.
     */
    val originalTitle: String = "",
    val rating: Double? = null,
    val nsfw: Boolean = false,
    val quality: String? = null,
    val nextEpisodeDate: String? = null,
    val metadataSource: String? = null,
) {
    val uniqueId: String get() = "$providerId|$type|$id"

    /** The name to ASK PROVIDERS with: the original/English title when the item
     *  carries one, else the display title. Nothing user-facing prints this —
     *  it exists so a title localized for display never becomes the search
     *  key (see [originalTitle]). */
    val searchTitle: String get() = originalTitle.trim().ifBlank { title.trim() }

    /** Every name this item is known by, display name first and the original
     *  name last. Lookups that can afford to try more than one name walk this. */
    val allTitles: List<String>
        get() = listOf(title.trim(), originalTitle.trim())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
}

data class Episode(
    val number: Int,
    val id: String,
    val name: String? = null,
    val image: String? = null,
    /** Season this episode belongs to (1 when a provider has no season info). */
    val season: Int = 1,
    val overview: String? = null,
    val released: String? = null,
    val rating: Double? = null,
    val runtime: Int? = null,
)

/** TMDB cast, trailer, production and collection data used by rich detail views. */
data class CastMember(val name: String, val character: String? = null, val profileUrl: String? = null)
data class Trailer(val youtubeKey: String, val name: String, val type: String = "Trailer", val thumbnailUrl: String? = null)
data class CompanyRef(val id: String, val name: String, val logoUrl: String? = null, val isNetwork: Boolean = false)
data class TitleCollection(val id: String, val name: String, val items: List<MediaItem> = emptyList())
data class TitleDetails(
    val status: String? = null,
    val runtimeMinutes: Int? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val voteCount: Int? = null,
    val certification: String? = null,
    val country: String? = null,
    val language: String? = null,
    val releaseDate: String? = null,
    val director: String? = null,
    val writers: List<String> = emptyList(),
    val imdbId: String? = null,
    val genres: List<String> = emptyList(),
)
data class TitleExtras(
    val details: TitleDetails? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val castIsCharacters: Boolean = false,
    val localizedTitle: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val companies: List<CompanyRef> = emptyList(),
    val collection: TitleCollection? = null,
)

/** A single watch-history entry — what the user played and where they left off. */
data class HistoryEntry(
    val providerId: String,
    val mediaId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val episodeId: String = "",
    val episodeName: String = "",
    val episodeNumber: Int = 0,
    val seasonNumber: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watchedAt: Long = 0L,
) {
    /** Dedup key: one entry per video (movie = mediaId, series = per episode). */
    val uniqueKey: String get() = "$providerId|$type|$mediaId|$episodeId"
}

data class SubtitleSource(
    val lang: String,
    val url: String,
    val name: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class DrmSpec(
    val kid: String? = null,
    val key: String? = null,
    val uuid: String? = null,
    val kty: String? = null,
    val licenseUrl: String? = null,
    val keyRequestParameters: Map<String, String> = emptyMap(),
)

data class StreamSource(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleSource> = emptyList(),
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
    val isM3u8: Boolean = false,
    val isMpd: Boolean = false,
    /** Torrent file index (from Stremio stream.fileIdx) — which file inside
     *  the torrent to play. */
    val fileIdx: Int? = null,
    /** Extra peer sources: tracker URLs / DHT nodes (from Stremio stream.sources). */
    val trackers: List<String> = emptyList(),
    /** YouTube video id (from Stremio stream.ytId) — played in the web view. */
    val ytId: String? = null,
    /** True when the URL should be opened in a browser (externalUrl), not the player. */
    val externalUrl: Boolean = false,
    val drm: DrmSpec? = null,
    val provider: String = "",
    /** The installed provider id and display name, used to group and prioritise
     * the originating provider's servers in the player source picker. */
    val providerId: String = "",
    val providerName: String = "",
    val details: String = "",
    val pageUrl: String = "",
    val torrentFileHint: String? = null,
    val torrentFileSize: Long = 0L,
    val text: String = "",
) {
    fun identityKey(): String =
        infoHash?.takeIf { it.isNotBlank() }?.let { h ->
            val idx = fileIdx?.let { "i$it" }
                ?: ("n:" + (torrentFileHint?.trim().orEmpty().ifBlank { name.trim() }.take(120) +
                    (if (torrentFileSize > 0) "#$torrentFileSize" else "")))
            "h${h.lowercase()}#$idx"
        } ?: "u$url"
}

data class CatalogRef(
    val providerId: String,
    val type: MediaType,
    val id: String,
    val name: String,
    /** Addon's literal catalog type string — used verbatim in Stremio URLs. */
    val rawType: String = "",
)

enum class CatalogSourceKind { TMDB, PROVIDER, ITEMS }

data class CatalogSource(
    val kind: CatalogSourceKind = CatalogSourceKind.TMDB,
    val title: String = "",
    val providerId: String = "",
    val catalogId: String = "",
    val type: MediaType = MediaType.UNKNOWN,
    val rawType: String = "",
    val tmdbPreset: String = "",
    val tmdbSpec: String = "",
    val itemsJson: String = "",
    val uid: String = "",
) {
    val spec: TmdbSpec? get() = if (kind == CatalogSourceKind.TMDB) TmdbSpec.decode(tmdbSpec) else null
    val itemCount: Int get() = if (kind == CatalogSourceKind.ITEMS)
        runCatching { org.json.JSONArray(itemsJson).length() }.getOrDefault(0) else 0
    val key: String get() = when (kind) {
        CatalogSourceKind.TMDB -> "tmdb|" + tmdbSpec.ifBlank { tmdbPreset }
        CatalogSourceKind.ITEMS -> "items|" + uid.ifBlank { itemsJson.hashCode().toString(16) }
        CatalogSourceKind.PROVIDER -> "prov|$providerId|$type|$catalogId"
    }
}

data class LibraryCategory(val id: String, val name: String, val builtIn: Boolean = false) {
    companion object {
        const val MOVIES = "cat-movies"
        const val SERIES = "cat-series"
        const val ACTION = "cat-action"
        const val ROMANCE = "cat-romance"
        val DEFAULTS = listOf(
            LibraryCategory(MOVIES, "Movies", true),
            LibraryCategory(SERIES, "Series", true),
            LibraryCategory(ACTION, "Action", true),
            LibraryCategory(ROMANCE, "Romance", true),
        )
    }
}

data class CollectionFolder(
    val id: String,
    val name: String,
    val sources: List<CatalogSource> = emptyList(),
    val coverKind: String = CoverKinds.NONE,
    val coverValue: String = "",
    val tileShape: String = TileShapes.POSTER,
    val hideTitle: Boolean = false,
    val gifAlways: Boolean = false,
    val heroBackdropUrl: String = "",
    val titleLogoUrl: String = "",
)

object CollectionViewModes {
    const val ROWS = "rows"
    const val TABS = "tabs"
    val ALL = listOf(ROWS, TABS)
    fun normalize(key: String?): String = if (key in ALL) key as String else ROWS
}

object TileShapes {
    const val POSTER = "poster"
    const val SQUARE = "square"
    const val WIDE = "wide"
    val ALL = listOf(POSTER, SQUARE, WIDE)
    fun normalize(key: String?): String = if (key in ALL) key as String else POSTER
    fun aspect(key: String?): Float = when (normalize(key)) {
        SQUARE -> 1f
        WIDE -> 16f / 9f
        else -> 2f / 3f
    }
}

object CoverKinds {
    const val NONE = "none"
    const val EMOJI = "emoji"
    const val URL = "url"
    const val GIF = "gif"
    val ALL = listOf(NONE, EMOJI, URL, GIF)
    fun normalize(kind: String?): String = if (kind in ALL) kind as String else NONE
}

data class Collection(
    val id: String,
    val name: String,
    val folders: List<CollectionFolder> = emptyList(),
    val coverKind: String = CoverKinds.NONE,
    val coverValue: String = "",
    val tileShape: String = TileShapes.POSTER,
    val pinToTop: Boolean = false,
    val viewMode: String = CollectionViewModes.ROWS,
    val showAllTab: Boolean = true,
    val backdropUrl: String = "",
) {
    val isEmpty: Boolean get() = folders.isEmpty()
    val allSources: List<CatalogSource> get() = folders.flatMap { it.sources }.distinctBy { it.key }
}

data class CatalogRow(
    val providerId: String = "",
    val providerName: String,
    val title: String,
    val items: List<MediaItem>,
    /** Stable unique key for LazyColumn rows — must never collide, even when an
     *  addon exposes several catalogs with the same display name (e.g.
     *  "Streaming Catalogs" has both a movies and a series catalog named
     *  "Netflix"). */
    val key: String = "",
    /** The originating catalog's own id/type — lets "Show All" re-fetch the
     *  whole catalog with paging instead of only the home row's first page. */
    val catalogId: String = "",
    val type: MediaType = MediaType.UNKNOWN,
    val rawType: String = "",
)
