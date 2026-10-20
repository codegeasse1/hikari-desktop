package com.hikari.app.data

import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Detail-screen enrichment: TMDB rating + cast + artwork + trailers, and the
 * IMDb rating, for any catalog item that resolves to a TMDB entry.
 *
 * Flow: [TmdbResolver.resolve] (title/year matching the nuvio stack already
 * uses) → TMDB details (vote_average, vote_count, imdb_id, studios, seasons,
 * runtime) → credits (cast with character + photo, directors/writers) →
 * images (backdrops for the rotating banner, logo treatment, profiles) →
 * videos (trailers) → certifications → Cinemeta `meta/{movie|series}/{imdbId}.json`
 * (imdbRating — TMDB does not publish IMDb's own score, Cinemeta does, keyless).
 *
 * Everything here is best-effort and cached in memory for the session: a title
 * that does not resolve keeps its provider meta, it just shows no enriched
 * sections.
 */
object TmdbDetails {

    data class CastMember(val name: String, val character: String, val photoUrl: String?)
    data class CrewMember(val name: String, val job: String, val photoUrl: String?)
    data class Studio(val name: String, val logoUrl: String?)

    data class Trailer(val name: String, val youtubeKey: String, val official: Boolean) {
        val thumbUrl: String get() = "https://i.ytimg.com/vi/$youtubeKey/hqdefault.jpg"
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$youtubeKey"
    }

    /** One episode of a TV season, for the episode cards' stills/titles. */
    data class SeasonEpisode(
        val number: Int,
        val name: String,
        val overview: String,
        val stillUrl: String?,
        val rating: Double,
    )

    data class Details(
        val tmdbId: String,
        val mediaType: String, // "movie" | "tv"
        val tmdbRating: Double,
        val tmdbVotes: Int,
        val imdbId: String,
        val imdbRating: Double,
        val overview: String,
        val cast: List<CastMember>,
        val crew: List<CrewMember>,
        val studios: List<Studio>,
        val backdrops: List<String>,
        val logoUrl: String?,
        val trailers: List<Trailer>,
        val certification: String,
        val runtime: String,
        val seasons: Int,
        /** TMDB poster files (w500) — the high-quality art the home cinema
         *  cards upgrade to when a provider only serves a thumbnail. */
        val posters: List<String> = emptyList(),
    ) {
        /** Plain cast names — the old banner "Starring" line. */
        val castNames: List<String> get() = cast.map { it.name }
    }

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Details?>()
    private val seasonMemory = java.util.concurrent.ConcurrentHashMap<String, List<SeasonEpisode>>()

    private fun cacheKey(item: MediaItem): String =
        "tmdbdet|" + item.providerId + "|" + item.type + "|" + item.searchTitle.lowercase() + "|" + (item.year ?: 0)

    suspend fun load(item: MediaItem): Details? = withContext(Dispatchers.IO) {
        val key = cacheKey(item)
        if (memory.containsKey(key)) return@withContext memory[key]
        val details = runCatching { loadNetwork(item) }.getOrNull()
        // ConcurrentHashMap forbids null values: only successes are cached,
        // a miss simply resolves again next time the title is opened.
        if (details != null) memory[key] = details
        details
    }

    private suspend fun loadNetwork(item: MediaItem): Details? {
        val resolved = TmdbResolver.resolve(item) ?: return null
        val base = "/${resolved.mediaType}/${resolved.tmdbId}"
        return coroutineScope {
            val detailsD = async { TmdbResolver.apiGet(base, emptyMap()) }
            val creditsD = async { TmdbResolver.apiGet("$base/credits", emptyMap()) }
            // No language filter: a zh-tagged donghua backdrop beats a provider
            // thumbnail, and the parsers prefer English/unflagged art first.
            val imagesD = async { TmdbResolver.apiGet("$base/images", emptyMap()) }
            val videosD = async { TmdbResolver.apiGet("$base/videos", emptyMap()) }
            val certD = async {
                if (resolved.mediaType == "tv") TmdbResolver.apiGet("$base/content_ratings", emptyMap())
                else TmdbResolver.apiGet("$base/release_dates", emptyMap())
            }
            val details = detailsD.await() ?: return@coroutineScope null
            val tmdbRating = details.optDouble("vote_average", 0.0)
            val tmdbVotes = details.optInt("vote_count", 0)
            var imdbId = details.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty()
            if (imdbId.isEmpty()) {
                // TV details carry no imdb_id inline — the external_ids endpoint does.
                val ext = TmdbResolver.apiGet("$base/external_ids", emptyMap())
                imdbId = ext?.optString("imdb_id")?.takeIf { it.startsWith("tt") }.orEmpty()
            }
            val cast = parseCast(creditsD.await())
            val crew = parseCrew(creditsD.await())
            val studios = parseStudios(details)
            val backdrops = parseBackdrops(imagesD.await())
            val logo = parseLogo(imagesD.await())
            val posters = parsePosters(imagesD.await())
            val trailers = parseTrailers(videosD.await())
            val certification = parseCertification(certD.await(), resolved.mediaType)
            val runtime = parseRuntime(details, resolved.mediaType)
            val seasons = if (resolved.mediaType == "tv") details.optInt("number_of_seasons", 1).coerceAtLeast(1) else 1
            val imdbRating = if (imdbId.isNotEmpty()) fetchImdbRating(imdbId, resolved.mediaType) else 0.0
            if (tmdbRating <= 0.0 && imdbRating <= 0.0 && cast.isEmpty() && backdrops.isEmpty()) return@coroutineScope null
            Details(
                tmdbId = resolved.tmdbId,
                mediaType = resolved.mediaType,
                tmdbRating = tmdbRating,
                tmdbVotes = tmdbVotes,
                imdbId = imdbId,
                imdbRating = imdbRating,
                overview = details.optString("overview"),
                cast = cast,
                crew = crew,
                studios = studios,
                backdrops = backdrops,
                logoUrl = logo,
                trailers = trailers,
                certification = certification,
                runtime = runtime,
                seasons = seasons,
                posters = posters,
            )
        }
    }

    /** Season stills/titles/overviews for the episode cards (one call per season). */
    suspend fun seasonEpisodes(tmdbId: String, season: Int): List<SeasonEpisode> = withContext(Dispatchers.IO) {
        val key = "season|$tmdbId|$season"
        seasonMemory[key]?.let { return@withContext it }
        val list = runCatching {
            val obj = TmdbResolver.apiGet("/tv/$tmdbId/season/$season", emptyMap()) ?: return@runCatching emptyList()
            val arr = obj.optJSONArray("episodes") ?: return@runCatching emptyList()
            val out = ArrayList<SeasonEpisode>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                out.add(
                    SeasonEpisode(
                        number = e.optInt("episode_number", i + 1),
                        name = e.optString("name"),
                        overview = e.optString("overview"),
                        stillUrl = img(e.optString("still_path"), "w780"),
                        rating = e.optDouble("vote_average", 0.0),
                    )
                )
            }
            out.toList()
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) seasonMemory[key] = list
        list
    }

    private fun parseCast(credits: JSONObject?): List<CastMember> {
        val out = ArrayList<CastMember>()
        val arr = credits?.optJSONArray("cast") ?: return out
        for (i in 0 until minOf(arr.length(), 14)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(CastMember(name, o.optString("character"), img(o.optString("profile_path"), "w185")))
        }
        return out
    }

    private fun parseCrew(credits: JSONObject?): List<CrewMember> {
        val out = ArrayList<CrewMember>()
        val seen = HashSet<String>()
        val arr = credits?.optJSONArray("crew") ?: return out
        // Directors first, then writers/creators — the order the row reads in.
        val rank = mapOf("Director" to 0, "Creator" to 1, "Writer" to 2, "Screenplay" to 3, "Story" to 4, "Novel" to 5)
        val want = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (rank.containsKey(o.optString("job"))) want.add(o)
        }
        want.sortBy { rank[it.optString("job")] ?: 9 }
        for (o in want) {
            val name = o.optString("name")
            if (name.isBlank() || !seen.add(name)) continue
            out.add(CrewMember(name, o.optString("job"), img(o.optString("profile_path"), "w185")))
            if (out.size >= 9) break
        }
        return out
    }

    private fun parseStudios(details: JSONObject): List<Studio> {
        val out = ArrayList<Studio>()
        val arr = details.optJSONArray("production_companies") ?: return out
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(Studio(name, img(o.optString("logo_path"), "w200")))
        }
        return out
    }

    private fun parseBackdrops(images: JSONObject?): List<String> {
        val arr = images?.optJSONArray("backdrops") ?: return emptyList()
        val pref = ArrayList<Pair<Double, String>>()
        val rest = ArrayList<Pair<Double, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("file_path")
            if (p.isBlank()) continue
            // English/unflagged art first (what the banner wants), then art in
            // any language: a zh-tagged donghua backdrop still beats a
            // provider thumbnail stretched over the hero.
            val lang = o.optString("iso_639_1")
            val slot = if (lang == "en" || lang.isBlank() || lang == "null") pref else rest
            slot.add(o.optDouble("vote_average", 0.0) to p)
        }
        return (pref.sortedByDescending { it.first } + rest.sortedByDescending { it.first })
            .take(8).mapNotNull { img(it.second, "w1280") }
    }

    private fun parseLogo(images: JSONObject?): String? {        val arr = images?.optJSONArray("logos") ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("file_path")
            if (p.isBlank()) continue
            if (fallback == null) fallback = p
            val lang = o.optString("iso_639_1")
            if (lang == "en" || lang.isBlank() || lang == "null") return img(p, "w500")
        }
        return img(fallback, "w500")
    }

    private fun parsePosters(images: JSONObject?): List<String> {
        val arr = images?.optJSONArray("posters") ?: return emptyList()
        val pref = ArrayList<Pair<Double, String>>()
        val rest = ArrayList<Pair<Double, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("file_path")
            if (p.isBlank()) continue
            val lang = o.optString("iso_639_1")
            val slot = if (lang == "en" || lang.isBlank() || lang == "null") pref else rest
            slot.add(o.optDouble("vote_average", 0.0) to p)
        }
        return (pref.sortedByDescending { it.first } + rest.sortedByDescending { it.first })
            .take(4).mapNotNull { img(it.second, "w500") }
    }

    private fun parseTrailers(videos: JSONObject?): List<Trailer> {
        val arr = videos?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Trailer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("site") != "YouTube") continue
            val type = o.optString("type")
            if (type != "Trailer" && type != "Teaser") continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out.add(Trailer(o.optString("name").ifBlank { type }, key, o.optBoolean("official", false)))
        }
        return out.sortedByDescending { it.official }.take(8)
    }

    private fun parseCertification(obj: JSONObject?, mediaType: String): String {
        if (obj == null) return ""
        return if (mediaType == "tv") {
            val arr = obj.optJSONArray("results") ?: return ""
            var fb = ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = o.optString("rating")
                if (r.isBlank()) continue
                if (fb.isBlank()) fb = r
                if (o.optString("iso_3166_1") == "US") return r
            }
            fb
        } else {
            val arr = obj.optJSONArray("results") ?: return ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("iso_3166_1") != "US") continue
                val rel = o.optJSONArray("release_dates") ?: continue
                for (j in 0 until rel.length()) {
                    val c = rel.optJSONObject(j)?.optString("certification").orEmpty()
                    if (c.isNotBlank()) return c
                }
            }
            ""
        }
    }

    private fun parseRuntime(details: JSONObject, mediaType: String): String {
        if (mediaType != "tv") {
            val min = details.optInt("runtime", 0)
            if (min > 0) return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
            return ""
        }
        val arr = details.optJSONArray("episode_run_time")
        val min = if (arr != null && arr.length() > 0) arr.optInt(0, 0) else 0
        return if (min > 0) "${min}m" else ""
    }

    private fun img(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** Cinemeta publishes IMDb's own score keylessly, addressed by IMDb id. */
    private fun fetchImdbRating(imdbId: String, mediaType: String): Double {
        val kind = if (mediaType == "tv") "series" else "movie"
        val text = Http.getString(
            "https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json",
            mapOf("Accept" to "application/json"),
        ) ?: return 0.0
        val meta = runCatching { JSONObject(text).optJSONObject("meta") }.getOrNull() ?: return 0.0
        return meta.optString("imdbRating").toDoubleOrNull()
            ?: meta.optDouble("imdbRating", 0.0)
    }

    fun imdbUrl(imdbId: String): String = "https://www.imdb.com/title/$imdbId/"

    fun tmdbUrl(tmdbId: String, mediaType: String): String =
        "https://www.themoviedb.org/" + (if (mediaType == "tv") "tv" else "movie") + "/$tmdbId"
}
