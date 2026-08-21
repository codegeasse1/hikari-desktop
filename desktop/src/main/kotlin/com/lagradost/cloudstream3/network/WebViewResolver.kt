@file:Suppress("unused")

package com.lagradost.cloudstream3.network

import com.hikari.app.HikariApp
import com.lagradost.cloudstream3.USER_AGENT
import desktop.fx.Fx
import desktop.web.FxWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream

/**
 * Desktop WebViewResolver. Same public API as the Android shadow (see the
 * Android app's build.gradle.kts note on the jar's stub), but backed by a
 * JavaFX WebEngine instead of an Android WebView. Runs the embed page in a
 * hidden WebEngine, captures every request whose URL matches [interceptUrl]
 * (and the ones matching [additionalUrls]), then hands the caller the
 * captured request so `app.get(...)` resolves to the real stream.
 *
 * Limitations vs Android: JavaFX's WebKit cannot solve every Cloudflare
 * challenge and has no per-request interceptor, so capture relies on the
 * page's own fetch/XHR/video.src hooks + DOM scanning.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

    companion object {
        const val DEFAULT_TIMEOUT = 60_000L

        @Volatile
        var webViewUserAgent: String? = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fixedRequest = runBlocking { resolveUsingWebView(request) }.first
        return chain.proceed(fixedRequest ?: request)
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String>,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = runCatching {
        val builder = Request.Builder().url(url)
        builder.method(
            method,
            if (method.equals("GET", true) || method.equals("HEAD", true)) null
            else RequestBody.create(null, ByteArray(0))
        )
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        resolveUsingWebView(builder.build(), requestCallBack)
    }.getOrDefault(null to emptyList())

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val first = resolveOnce(request, requestCallBack)
        if (first.first != null || first.second.isNotEmpty()) return first
        return resolveOnce(request, requestCallBack)
    }

    private suspend fun resolveOnce(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = withContext(Dispatchers.IO) {
        val url = request.url.toString()
        val app = HikariApp.instance
        Fx.runBlock {
            runCatching { FxWebView.setUserAgent(app.effectiveWebViewUa(userAgent)) }
        }
        val cap = FxWebView.resolve(
            url = url,
            capture = interceptUrl,
            additional = additionalUrls,
            script = script,
            timeoutMs = timeout,
        )
        val fixed = java.util.concurrent.atomic.AtomicReference<Request?>(null)
        val extras = mutableListOf<Request>()
        for (r in cap.requests) {
            val req = toOkhttpRequest(r.url, r.method, url, cap.cookie) ?: continue
            if (interceptUrl.containsMatchIn(r.url)) {
                if (fixed.get() == null) {
                    fixed.set(req)
                    requestCallBack(req)
                }
            } else {
                extras.add(req)
                if (requestCallBack(req)) {
                    fixed.set(req)
                }
            }
        }
        fixed.get() to extras
    }

    private fun toOkhttpRequest(
        url: String,
        method: String,
        pageUrl: String,
        cookie: String,
    ): Request? = runCatching {
        val builder = Request.Builder().url(url)
        builder.method(
            method.uppercase(),
            if (method.equals("GET", true) || method.equals("HEAD", true)) null
            else RequestBody.create(null, ByteArray(0))
        )
        builder.header("User-Agent", HikariApp.instance.effectiveWebViewUa(userAgent))
        builder.header("Referer", pageUrl)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        builder.build()
    }.getOrNull()
}
