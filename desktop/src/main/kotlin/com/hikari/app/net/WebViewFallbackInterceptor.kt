package com.hikari.app.net

import com.hikari.app.HikariApp
import desktop.fx.Fx
import desktop.web.FxWebView
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor for the CloudStream runtime's clients. Several WAFs
 * (Cloudflare and friends) challenge the app's plain TLS stack and answer
 * plugin catalog/API requests with a bot-check HTML page — the plugin then
 * parses nothing and catalogs come back empty ("returned no catalog rows").
 * When a response looks like such a challenge page, the URL is re-fetched
 * inside the embedded WebEngine (a real browser that can pass the check) and
 * the rendered HTML is served back to the plugin as a normal 200 response.
 */
class WebViewFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        try {
            if (response.code != 200) return response
            val ct = (response.header("Content-Type") ?: "").lowercase()
            if (!ct.contains("html")) return response
            val peek = response.peekBody(200 * 1024).bytes()
            if (!FxWebView.isChallengeHtml(String(peek, Charsets.UTF_8))) return response
            // A real browser can usually beat the challenge — render the page.
            val url = request.url.toString()
            val rendered = runCatching {
                Fx.runBlock {
                    runCatching { FxWebView.setUserAgent(HikariApp.instance.effectiveWebViewUa()) }
                }
                FxWebView.renderedHtml(url, 30_000)
            }.getOrNull()
            if (rendered.isNullOrBlank()) return response // hand back the challenge page
            response.close()
            return Response.Builder()
                .request(request)
                .protocol(response.protocol)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(rendered.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        } catch (t: Throwable) {
            return response
        }
    }
}
