package android.webkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import java.io.InputStream

/**
 * Desktop stand-ins for the `android.webkit` classes an extension names.
 *
 * Measured over 96 real Aniyomi extensions: two of them build a `WebView` to
 * solve a site's login/anti-bot page (`WebView`, `WebViewClient`, `WebSettings`,
 * `WebResourceRequest`, `WebResourceResponse`). The desktop has no Android view
 * system to put one in, but the TYPES have to exist: an extension's class is
 * linked — and its methods verified — as a whole, so a source whose helper names
 * `WebView` fails to load entirely when the class is missing, even if the helper
 * is never called.
 *
 * These are inert objects, not a browser. A source that genuinely needs a real
 * WebView to pass a challenge will still not pass it here; it will at least
 * load, list its catalogue and play what does not need the challenge, instead of
 * reporting that it could not be installed.
 */
open class WebView(context: Context?) : View(context) {

    private val webSettings = WebSettings()

    private var webViewClient: WebViewClient? = null

    private var currentUrl: String? = null

    private var pageTitle: String? = null

    open fun getSettings(): WebSettings = webSettings

    open fun setWebViewClient(client: WebViewClient?) {
        webViewClient = client
    }

    open fun getWebViewClient(): WebViewClient? = webViewClient

    open fun setWebChromeClient(client: WebChromeClient?) = Unit

    open fun loadUrl(url: String?) {
        currentUrl = url
        // A page that never loads can never finish: the "finished" callbacks are
        // what a source's login helper waits for, so they are delivered straight
        // away with the URL it asked for. Nothing is fetched — the desktop WebView
        // is not a browser — but the helper's own flow completes instead of
        // hanging until its timeout.
        runCatching { webViewClient?.onPageStarted(this, url, null) }
        runCatching { webViewClient?.onPageFinished(this, url) }
    }

    open fun loadUrl(url: String?, additionalHttpHeaders: MutableMap<String, String>?) = loadUrl(url)

    open fun postUrl(url: String?, postData: ByteArray?) {
        loadUrl(url)
    }

    open fun loadData(data: String?, mimeType: String?, encoding: String?) = Unit

    open fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String?,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) = Unit

    open fun evaluateJavascript(script: String?, resultCallback: ValueCallback<String>?) {
        runCatching { resultCallback?.onReceiveValue(null) }
    }

    open fun getUrl(): String? = currentUrl

    open fun getOriginalUrl(): String? = currentUrl

    open fun getTitle(): String? = pageTitle

    open fun getProgress(): Int = 100

    open fun canGoBack(): Boolean = false

    open fun canGoForward(): Boolean = false

    open fun goBack() = Unit

    open fun goForward() = Unit

    open fun reload() = Unit

    open fun stopLoading() = Unit

    open fun clearCache(includeDiskFiles: Boolean) = Unit

    open fun clearHistory() = Unit

    open fun clearFormData() = Unit

    open fun destroy() = Unit

    open fun pauseTimers() = Unit

    open fun resumeTimers() = Unit

    open fun onPause() = Unit

    open fun onResume() = Unit

    open fun getFavicon(): Bitmap? = null

    open fun addJavascriptInterface(obj: Any?, name: String?) = Unit

    open fun removeJavascriptInterface(name: String?) = Unit
}

/** Android's `android.webkit.WebViewClient`, the class a login helper
 *  subclasses. Every callback is a no-op the subclass may override. */
open class WebViewClient {

    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

    open fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

    open fun onPageFinished(view: WebView?, url: String?) = Unit

    open fun onLoadResource(view: WebView?, url: String?) = Unit

    open fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = null

    open fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = null

    open fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) = Unit

    open fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: Any?) = Unit

    open fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) = Unit

    open fun onReceivedSslError(view: WebView?, handler: Any?, error: Any?) = Unit

    open fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = Unit

    open fun onFormResubmission(view: WebView?, dontResend: Any?, resend: Any?) = Unit
}

/** Android's `android.webkit.WebChromeClient`. */
open class WebChromeClient {

    open fun onProgressChanged(view: WebView?, newProgress: Int) = Unit

    open fun onReceivedTitle(view: WebView?, title: String?) = Unit

    open fun onJsAlert(view: WebView?, url: String?, message: String?, result: Any?): Boolean = false

    open fun onJsConfirm(view: WebView?, url: String?, message: String?, result: Any?): Boolean = false

    open fun onConsoleMessage(message: Any?): Boolean = false
}

/** Android's `android.webkit.WebSettings` — every setter is accepted and
 *  forgotten (there is no rendering engine behind it). */
open class WebSettings {

    open fun setJavaScriptEnabled(flag: Boolean) = Unit

    open fun setDomStorageEnabled(flag: Boolean) = Unit

    open fun setDatabaseEnabled(flag: Boolean) = Unit

    open fun setUseWideViewPort(flag: Boolean) = Unit

    open fun setLoadWithOverviewMode(flag: Boolean) = Unit

    open fun setSupportZoom(flag: Boolean) = Unit

    open fun setBuiltInZoomControls(flag: Boolean) = Unit

    open fun setDisplayZoomControls(flag: Boolean) = Unit

    open fun setSupportMultipleWindows(flag: Boolean) = Unit

    open fun setMediaPlaybackRequiresUserGesture(flag: Boolean) = Unit

    open fun setMixedContentMode(mode: Int) = Unit

    open fun setCacheMode(mode: Int) = Unit

    open fun setUserAgentString(ua: String?) = Unit

    open fun getUserAgentString(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    open fun setAllowFileAccess(flag: Boolean) = Unit

    open fun setAllowContentAccess(flag: Boolean) = Unit

    open fun setJavaScriptCanOpenWindowsAutomatically(flag: Boolean) = Unit

    open fun setBlockNetworkImage(flag: Boolean) = Unit

    open fun setImageBlockingEnabled(flag: Boolean) = Unit

    open fun getJavaScriptEnabled(): Boolean = true

    open fun getDomStorageEnabled(): Boolean = true

    companion object {
        const val LOAD_DEFAULT = -1
        const val LOAD_CACHE_ELSE_NETWORK = 1
        const val LOAD_NO_CACHE = 2
        const val LOAD_CACHE_ONLY = 3
        const val MIXED_CONTENT_ALWAYS_ALLOW = 0
        const val MIXED_CONTENT_NEVER_ALLOW = 1
        const val MIXED_CONTENT_COMPATIBILITY_MODE = 2
    }
}

/** Android's `android.webkit.WebResourceRequest`. */
interface WebResourceRequest {
    fun getUrl(): Uri?
    fun isForMainFrame(): Boolean = false
    fun isRedirect(): Boolean = false
    fun hasGesture(): Boolean = false
    fun getMethod(): String = "GET"
    fun getRequestHeaders(): Map<String, String> = emptyMap()
}

/** Android's `android.webkit.WebResourceResponse` — what a client returns to
 *  answer a request from its own bytes. */
open class WebResourceResponse {

    private var mimeType: String?
    private var encoding: String?
    private var statusCode = 200
    private var reasonPhrase: String? = null
    private var data: InputStream?
    private var responseHeaders: MutableMap<String, String> = HashMap()

    constructor(mimeType: String?, encoding: String?, data: InputStream?) {
        this.mimeType = mimeType
        this.encoding = encoding
        this.data = data
    }

    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String?,
        responseHeaders: MutableMap<String, String>?,
        data: InputStream?,
    ) {
        this.mimeType = mimeType
        this.encoding = encoding
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
        this.data = data
        if (responseHeaders != null) this.responseHeaders = responseHeaders
    }

    open fun getMimeType(): String? = mimeType

    open fun setMimeType(mimeType: String?) {
        this.mimeType = mimeType
    }

    open fun getEncoding(): String? = encoding

    open fun setEncoding(encoding: String?) {
        this.encoding = encoding
    }

    open fun getStatusCode(): Int = statusCode

    open fun getReasonPhrase(): String? = reasonPhrase

    open fun setStatusCodeAndReasonPhrase(statusCode: Int, reasonPhrase: String?) {
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
    }

    open fun getResponseHeaders(): MutableMap<String, String> = responseHeaders

    open fun setResponseHeaders(headers: MutableMap<String, String>?) {
        responseHeaders = headers ?: HashMap()
    }

    open fun getData(): InputStream? = data

    open fun setData(data: InputStream?) {
        this.data = data
    }
}

/** Android's `android.webkit.ValueCallback` — a one-shot result callback. */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T?)
}
