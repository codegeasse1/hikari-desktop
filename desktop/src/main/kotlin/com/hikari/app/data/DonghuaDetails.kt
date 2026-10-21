package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Anime/donghua metadata fallback for titles TMDB mistranslates.
 *
 * TMDB's search index is live-action-first: a generic English title like "Big
 * Brother" resolves to the sitcom (wrong poster, wrong overview, 165 fake
 * episodes) while every donghua around it resolves fine. Anime databases know
 * these titles natively, so anime candidates try them FIRST — a confident hit
 * wins outright and TMDB is never consulted for that title.
 *
 * Chain: AniList GraphQL (no key, best donghua coverage) → Bangumi (no key,
 * strongest for Chinese-original entries) → null (caller keeps TMDB/provider
 * art as before). The result is mapped into [TmdbDetails.Details] so the
 * detail screen, hero and art-upgrade paths work unchanged; [tmdbId] carries
 * an "anilist:"/"bangumi:" prefix so the UI labels the score honestly and
 * never calls TMDB seasons with a foreign id.
 */
object DonghuaDetails {

    /** True when the item plausibly is anime/donghua (provider- or type-tagged). */
    fun isAnimeCandidate(item: MediaItem): Boolean {
        if (item.rawType.contains("anime", ignoreCase = true)) return true
        val p = item.providerId.lowercase()
        return p.contains("anime") || p.contains("donghua")
    }

    suspend fun load(item: MediaItem): TmdbDetails.Details? = withContext(Dispatchers.IO) {
        runCatching { fromAniList(item) }.getOrNull()
            ?: runCatching { fromBangumi(item) }.getOrNull()
    }

    // ── AniList ──────────────────────────────────────────────────────────

    private fun fromAniList(item: MediaItem): TmdbDetails.Details? {
        val queries = linkedSetOf<String>()
        item.allTitles.forEach { queries.add(it) }
        TmdbMeta.queryVariants(item.searchTitle).take(2).forEach { queries.add(it) }
        var best: JSONObject? = null
        var bestScore = 0
        for (q in queries) {
            if (q.isBlank()) continue
            val media = anilistSearch(q) ?: continue
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val names = anilistNames(m)
                if (names.isEmpty()) continue
                var score = 0
                for (v in queries) for (n in names) {
                    score = maxOf(score, TmdbMeta.titleScore(v, n))
                }
                if (score == 0) continue
                if (item.year != null && item.year > 0) {
                    val y = m.optInt("seasonYear", 0)
                    if (y == item.year) score += 25
                    else if (y > 0 && kotlin.math.abs(y - item.year) <= 1) score += 8
                }
                // Animation-only index: no genre penalty needed, but a
                // series-length donghua matching a series-length entry wins
                // over a movie-length one.
                if (score > bestScore) {
                    bestScore = score
                    best = m
                }
            }
            if (bestScore >= 40) break
        }
        val m = best ?: return null
        if (bestScore <= 0) return null
        val id = m.optInt("id", 0)
        if (id <= 0) return null
        val title = m.optJSONObject("title")
        val cover = m.optJSONObject("coverImage")
        val coverUrl = cover?.optString("extraLarge")?.takeIf { it.isNotBlank() && it != "null" }
            ?: cover?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }
        val banner = m.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" }
        val score10 = m.optInt("averageScore", 0) / 10.0
        val studios = ArrayList<TmdbDetails.Studio>()
        m.optJSONObject("studios")?.optJSONArray("nodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (n.isNotBlank() && n != "null") studios.add(TmdbDetails.Studio(n, null))
            }
        }
        val backdrops = listOfNotNull(banner, coverUrl).distinct()
        val posters = listOfNotNull(coverUrl)
        if (score10 <= 0.0 && backdrops.isEmpty() && studios.isEmpty()) return null
        return TmdbDetails.Details(
            tmdbId = "anilist:$id",
            mediaType = "tv",
            tmdbRating = score10,
            tmdbVotes = 0,
            imdbId = "",
            imdbRating = 0.0,
            overview = stripHtml(m.optString("description")),
            cast = emptyList(),
            crew = emptyList(),
            studios = studios,
            backdrops = backdrops,
            logoUrl = null,
            trailers = emptyList(),
            certification = "",
            runtime = "",
            seasons = 1,
            posters = posters,
        )
    }

    private fun anilistSearch(query: String): org.json.JSONArray? {
        val gql = "{\"query\":\"query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { id title { romaji english native } description coverImage { extraLarge large } bannerImage averageScore episodes seasonYear studios { nodes { name } } } } }\",\"variables\":{\"search\":" + JSONObject.quote(query) + "}}"
        val text = Http.postString("https://graphql.anilist.co", gql) ?: return null
        return runCatching {
            JSONObject(text).optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
        }.getOrNull()
    }

    private fun anilistNames(m: JSONObject): List<String> {
        val t = m.optJSONObject("title") ?: return emptyList()
        return listOf(t.optString("romaji"), t.optString("english"), t.optString("native"))
            .filter { it.isNotBlank() && it != "null" }
    }

    // ── Bangumi ──────────────────────────────────────────────────────────

    private fun fromBangumi(item: MediaItem): TmdbDetails.Details? {
        val queries = linkedSetOf<String>()
        item.allTitles.forEach { queries.add(it) }
        TmdbMeta.queryVariants(item.searchTitle).take(2).forEach { queries.add(it) }
        var best: JSONObject? = null
        var bestScore = 0
        for (q in queries) {
            if (q.isBlank()) continue
            val enc = runCatching { java.net.URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: continue
            val text = Http.getString(
                "https://api.bgm.tv/search/subject/$enc?type=2&responseGroup=large",
                mapOf("User-Agent" to "hikari-donghua-fallback/1.0"),
            ) ?: continue
            val arr = runCatching { JSONObject(text).optJSONArray("list") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val names = listOf(s.optString("name"), s.optString("name_cn"))
                    .filter { it.isNotBlank() && it != "null" }
                if (names.isEmpty()) continue
                var score = 0
                for (v in queries) for (n in names) {
                    score = maxOf(score, TmdbMeta.titleScore(v, n))
                }
                if (score == 0) continue
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            if (bestScore >= 40) break
        }
        val s = best ?: return null
        if (bestScore <= 0) return null
        val id = s.optLong("id", 0L)
        if (id <= 0L) return null
        val images = s.optJSONObject("images")
        val coverUrl = images?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("common")?.takeIf { it.isNotBlank() && it != "null" }
        val score10 = s.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0
        val summary = s.optString("summary").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val backdrops = listOfNotNull(coverUrl).distinct()
        if (score10 <= 0.0 && backdrops.isEmpty() && summary.isBlank()) return null
        return TmdbDetails.Details(
            tmdbId = "bangumi:$id",
            mediaType = "tv",
            tmdbRating = score10,
            tmdbVotes = s.optJSONObject("rating")?.optInt("total", 0) ?: 0,
            imdbId = "",
            imdbRating = 0.0,
            overview = summary.trim(),
            cast = emptyList(),
            crew = emptyList(),
            studios = emptyList(),
            backdrops = backdrops,
            logoUrl = null,
            trailers = emptyList(),
            certification = "",
            runtime = "",
            seasons = 1,
            posters = listOfNotNull(coverUrl),
        )
    }

    private fun stripHtml(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        return raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }
}
