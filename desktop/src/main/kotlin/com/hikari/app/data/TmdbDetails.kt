package com.hikari.app.data

import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Detail-screen enrichment: TMDB rating + cast and the IMDb rating, for any
 * catalog item that resolves to a TMDB entry.
 *
 * Flow: [TmdbResolver.resolve] (title/year matching the nuvio stack already
 * uses) → TMDB details (vote_average, vote_count, imdb_id) → TMDB credits
 * (top cast) → Cinemeta `meta/{movie|series}/{imdbId}.json` (imdbRating —
 * TMDB does not publish IMDb's own score, Cinemeta does, keyless).
 *
 * Everything here is best-effort and cached in memory for the session: a title
 * that does not resolve keeps its provider meta, it just shows no rating strip.
 */
object TmdbDetails {

    data class Details(
        val tmdbRating: Double,
        val tmdbVotes: Int,
        val imdbId: String,
        val imdbRating: Double,
        val cast: List<String>,
    )

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Details?>()

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
        val details = TmdbResolver.apiGet("/${resolved.mediaType}/${resolved.tmdbId}", emptyMap())
            ?: return null
        val tmdbRating = details.optDouble("vote_average", 0.0)
        val tmdbVotes = details.optInt("vote_count", 0)
        var imdbId = details.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty()
        if (imdbId.isEmpty()) {
            // TV details carry no imdb_id inline — the external_ids endpoint does.
            val ext = TmdbResolver.apiGet("/${resolved.mediaType}/${resolved.tmdbId}/external_ids", emptyMap())
            imdbId = ext?.optString("imdb_id")?.takeIf { it.startsWith("tt") }.orEmpty()
        }
        val credits = TmdbResolver.apiGet("/${resolved.mediaType}/${resolved.tmdbId}/credits", emptyMap())
        val cast = ArrayList<String>()
        val arr = credits?.optJSONArray("cast")
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 10)) {
                val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) cast.add(name)
            }
        }
        val imdbRating = if (imdbId.isNotEmpty()) fetchImdbRating(imdbId, resolved.mediaType) else 0.0
        if (tmdbRating <= 0.0 && imdbRating <= 0.0 && cast.isEmpty()) return null
        return Details(tmdbRating, tmdbVotes, imdbId, imdbRating, cast)
    }

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
