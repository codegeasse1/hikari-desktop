package com.hikari.app.net

import org.json.JSONObject

/**
 * Checks GitHub for a newer build.
 *
 * The release the app is published to is `continuous`, and it is NOT the
 * repository's "latest" release — so the old check (`/releases/latest`, compared
 * against a hardcoded 0.1.0) answered the wrong question. It reads the release
 * directly, picks the NEWEST `Hikari-<version>.exe` asset by version, compares
 * that with the build identity this exe was stamped with (see `desktop.Build`),
 * and hands back the ASSET url, so updating is one click on the right file
 * instead of choosing between forty installers on the release page.
 */
object Updater {

    const val REPO = "codegeasse1/hikari-desktop"

    private const val RELEASE = "continuous"

    data class UpdateInfo(
        val available: Boolean,
        val current: String,
        val latest: String,
        val url: String,
    )

    suspend fun checkForUpdate(): UpdateInfo? {
        val body = Http.getString("https://api.github.com/repos/$REPO/releases/tags/$RELEASE")
            ?: return null
        return runCatching {
            val o = JSONObject(body)
            val page = o.optString("html_url").ifBlank { "https://github.com/$REPO/releases" }
            var bestVersion = desktop.Build.VERSION
            var bestUrl = page
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val m = Regex("hikari-(\\d+(?:\\.\\d+)+)\\.exe", RegexOption.IGNORE_CASE)
                        .find(name) ?: continue
                    val version = m.groupValues[1]
                    if (parseVersion(version) > parseVersion(bestVersion)) {
                        bestVersion = version
                        bestUrl = a.optString("browser_download_url").ifBlank { page }
                    }
                }
            }
            val available = parseVersion(bestVersion) > parseVersion(desktop.Build.VERSION)
            UpdateInfo(
                available = available,
                current = desktop.Build.VERSION,
                latest = if (available) bestVersion else desktop.Build.VERSION,
                url = bestUrl,
            )
        }.getOrNull()
    }

    /** "0.1.196" -> a comparable number (major*10^4 + minor*10^2 + patch). */
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
