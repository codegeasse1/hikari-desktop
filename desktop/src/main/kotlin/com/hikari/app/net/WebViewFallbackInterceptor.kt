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
    companion object {
        /** At most 2 concurrent real-browser WAF passes: every pass holds the
         *  single shared WebEngine for up to 30s, and a Home load can surface
         *  dozens of challenged requests at once. Queued passes used to pile
         *  up for minutes, starving the OkHttp pool (catalogs timing out as
         *  "no rows") while each poll round-tripped the FX thread. Over the
         *  cap the raw challenge page goes back to the plugin immediately. */
        private val browserPasses = java.util.concurrent.Semaphore(2)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        try {
            // WAFs answer with statuses other than 200 too — a 200
            // "Just a moment" page, or a 401/403/429/502/503 bot-check —
            // every one of those is an HTML page the plugin can't parse into a
            // catalog. Any HTML challenge body is re-fetched in a real browser.
            val code = response.code
            val challengeStatus = code == 200 || code == 401 || code == 403 || code == 429 || code == 502 || code == 503
            if (!challengeStatus) return response
            val ct = (response.header("Content-Type") ?: "").lowercase()
            if (!ct.contains("html")) return response
            val peek = response.peekBody(200 * 1024).bytes()
            if (!FxWebView.isChallengeHtml(String(peek, Charsets.UTF_8))) return response
            // A real browser can usually beat the challenge — render the page,
            // unless two passes are already running (see browserPasses).
            val url = request.url.toString()
            if (!browserPasses.tryAcquire()) {
                com.hikari.app.util.LiveLogs.warn(
                    "waf",
                    "Challenge page for $url but the browser is busy (2 passes running) — serving raw page."
                )
                return response
            }
            val rendered = try {
                runCatching {
                    Fx.runBlock {
                        runCatching { FxWebView.setUserAgent(HikariApp.instance.effectiveWebViewUa()) }
                    }
                    FxWebView.renderedHtml(url, 30_000)
                }.getOrNull()
            } finally {
                browserPasses.release()
            }
            if (rendered.isNullOrBlank()) {
                response.close()
                com.hikari.app.util.LiveLogs.error(
                    "waf",
                    "Browser pass for $url returned nothing — serving the raw challenge page back to the plugin (catalog may be empty)."
                )
                return Response.Builder()
                    .request(request)
                    .protocol(response.protocol)
                    .code(code)
                    .message(response.message)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(String(peek, Charsets.UTF_8).toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            response.close()
            com.hikari.app.util.LiveLogs.log(
                "waf",
                "Browser passed the check for $url — serving ${rendered.length} rendered bytes back to the plugin."
            )
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
