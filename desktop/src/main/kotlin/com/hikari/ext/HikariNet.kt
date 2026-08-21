package com.hikari.ext

import com.hikari.app.HikariApp
import desktop.fx.Fx
import desktop.web.FxWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A request the page fired that matched the capture regex. */
data class HikariWebViewResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Full HTTP response summary (for callers that must inspect errors). */
data class HikariResponse(
    val status: Int,
    val url: String,
    val body: String? = null,
)

/**
 * The helper library Hikari extensions are written against. All helpers are
 * plain functions over the app's hardened networking stack (redirects,
 * generous timeouts, browser User-Agent, Conscrypt TLS setup), so extensions
 * never have to fight CDNs by themselves.
 */
object HikariNet {

    /** Browser-like headers for scraping (desktop Chrome fingerprint). */
    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to com.hikari.app.net.Http.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /** GET and return the response body as text (null on any failure). */
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            com.hikari.app.net.Http.getString(url, headers)
        }

    /** POST a string body and return the response body as text (null on any
     *  failure). */
    suspend fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = withContext(Dispatchers.IO) {
        com.hikari.app.net.Http.postString(url, body, headers, contentType)
    }

    /** POST a JSON object and parse the response as JSON (null on failure). */
    suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = withContext(Dispatchers.IO) {
        postString(url, body.toString(), headers + mapOf("Content-Type" to "application/json; charset=utf-8"))
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** Base64 decode (standard alphabet, tolerant of whitespace). Null-safe. */
    fun base64Decode(s: String): ByteArray? = runCatching {
        android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    }.getOrNull()

    /** Base64 encode (standard alphabet). */
    fun base64Encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    /**
     * GET like [getString], but when the plain HTTP client gets blocked (a
     * Cloudflare/DataDome challenge page or a hard failure) it re-fetches the
     * page inside a real (embedded) browser and returns the rendered HTML.
     */
    suspend fun getStringSmart(url: String, headers: Map<String, String> = emptyMap()): String? {
        val plain = getString(url, headers)
        if (plain != null && !looksLikeChallengePage(plain)) return plain
        return getStringRendered(url)
    }

    /** GET and return the rendered DOM HTML by loading [url] in the embedded WebEngine. */
    suspend fun getStringRendered(url: String, timeoutMs: Long = 25_000): String? =
        withContext(Dispatchers.IO) {
            Fx.runBlock { runCatching { FxWebView.setUserAgent(HikariApp.instance.effectiveWebViewUa()) } }
            FxWebView.renderedHtml(url, timeoutMs)
        }

    /** Distinguishes a WAF challenge page from real content. */
    private fun looksLikeChallengePage(html: String): Boolean {
        val probe = html.take(40_000)
        return CHALLENGE_MARKERS.any { probe.contains(it, ignoreCase = true) }
    }

    private val CHALLENGE_MARKERS = listOf(
        "cf-chl-",
        "challenge-platform",
        "cdn-cgi/challenge-platform",
        "cf-browser-verification",
        "cf-mitigated",
        "cf-turnstile",
        "Just a moment",
        "Attention Required!",
        "enablejs",
        "Pardon Our Interruption",
        "Checking your browser",
        "checking your browser",
        "Verify you are human",
        "verify you are human",
        "hcaptcha",
        "h-captcha",
        "Access Denied",
        "datadome",
        "detectportal",
    )

    /** GET and parse the response as JSON (null on failure). */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            getString(url, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            com.hikari.app.net.Http.getBytes(url, headers)
        }

    /** Full response (status + body) for callers that must see error codes. */
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): HikariResponse? =
        withContext(Dispatchers.IO) {
            try {
                com.hikari.app.net.Http.get(url, headers).use { r ->
                    HikariResponse(r.code, r.request.url.toString(), r.body?.string())
                }
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * Runs [url] in the embedded WebEngine and returns every request the page
     * fired whose URL matches [capture] (or [additional]). This is the helper
     * that makes StreamHG/hgcloud-style embeds work: the player page's own JS
     * runs in a browser, and the m3u8 (or master.txt) it requests comes back
     * as a [HikariWebViewResult] (URL + headers), ready to hand to the player.
     */
    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex> = emptyList(),
        timeoutMs: Long = 60_000,
        script: String? = null,
    ): List<HikariWebViewResult> = withContext(Dispatchers.IO) {
        val app = HikariApp.instance
        Fx.runBlock { runCatching { FxWebView.setUserAgent(app.effectiveWebViewUa()) } }
        val cap = FxWebView.resolve(url, capture, additional, script, timeoutMs)
        buildList {
            cap.requests.forEach { r ->
                val headers = mutableMapOf<String, String>()
                headers["Referer"] = url
                if (cap.cookie.isNotBlank()) headers["Cookie"] = cap.cookie
                headers["User-Agent"] = app.effectiveWebViewUa()
                add(HikariWebViewResult(r.url, headers))
            }
        }
    }
}
