package com.hikari.app.net

import org.json.JSONObject

/**
 * Checks GitHub for a newer build. Desktop builds compare the `continuous`
 * release tag against the app's current version, like the Android app.
 */
object Updater {

    const val CURRENT_VERSION = "0.1.0"

    const val REPO = "codegeasse1/hikari-desktop"

    data class UpdateInfo(
        val available: Boolean,
        val current: String,
        val latest: String,
        val url: String,
    )

    suspend fun checkForUpdate(): UpdateInfo? {
        val url = "https://api.github.com/repos/$REPO/releases/latest"
        val body = Http.getString(url) ?: return null
        return runCatching {
            val o = JSONObject(body)
            val latest = o.optString("tag_name").removePrefix("v").ifBlank { return@runCatching null }
            val page = o.optString("html_url").ifBlank { "https://github.com/$REPO/releases" }
            val available = parseVersion(latest) > parseVersion(CURRENT_VERSION)
            UpdateInfo(available, CURRENT_VERSION, latest, page)
        }.getOrNull()
    }

    /** "0.1.0" → 1000*100 + 100*1 + 0 style comparison (major*10^4 + minor*10^2 + patch). */
    private fun parseVersion(v: String): Long {
        val parts = v.trim().trimStart('v').split(".")
        var n = 0L
        parts.take(3).forEachIndexed { i, p ->
            n += (p.toLongOrNull() ?: 0L) * pow10(2 * (2 - i))
        }
        return n
    }

    private fun pow10(e: Int): Long {
        var r = 1L
        repeat(e) { r *= 10 }
        return r
    }
}
