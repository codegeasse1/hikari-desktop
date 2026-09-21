package android.webkit

import java.util.concurrent.ConcurrentHashMap

/** Minimal `android.webkit.ValueCallback`, which some of the shimmed APIs take. */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

/**
 * A desktop cookie store with `CookieManager`'s shape.
 *
 * `Aniyomi`'s `AndroidCookieJar` is the OkHttp CookieJar every extension's
 * network stack is built on, and it is written straight against this class. The
 * desktop has no WebView cookie store to share with, so the cookies live here —
 * in memory for the session, which is what a browser cookie store effectively is
 * for a scraping extension.
 */
object CookieManager {

    private val byHost = ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: url.trim().lowercase()

    @JvmStatic
    fun getInstance(): CookieManager = this

    fun setAcceptCookie(accept: Boolean) {}

    fun acceptCookie(): Boolean = true

    fun setAcceptThirdPartyCookies(webView: Any?, accept: Boolean) {}

    fun setCookie(url: String, value: String?) {
        val host = hostOf(url)
        val jar = byHost.computeIfAbsent(host) { ConcurrentHashMap() }
        val cookie = value ?: return
        val name = cookie.substringBefore("=").trim()
        if (name.isEmpty()) return
        if (cookie.contains("Max-Age=0", ignoreCase = true) || cookie.contains("Max-Age=-1", ignoreCase = true)) {
            jar.remove(name)
        } else {
            jar[name] = cookie
        }
    }

    fun getCookie(url: String): String? {
        val jar = byHost[hostOf(url)] ?: return null
        if (jar.isEmpty()) return null
        return jar.values.joinToString("; ")
    }

    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        byHost.clear()
        runCatching { callback?.onReceiveValue(true) }
    }

    fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        runCatching { callback?.onReceiveValue(true) }
    }

    fun flush() {}
}
