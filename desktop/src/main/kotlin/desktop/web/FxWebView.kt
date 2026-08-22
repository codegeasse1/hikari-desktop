package desktop.web

import desktop.fx.Fx
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Headless-ish JavaFX WebEngine wrapper. The desktop replacement for the
 * Android WebView used by the embed resolver (WebViewResolver) and the
 * rendered-HTML helper (HikariNet.getStringRendered).
 *
 * JavaFX's WebEngine has no per-request interceptor, so request capture works
 * by injecting a small recorder script (fetch / XHR / video.src hooks) into
 * the page and polling it, plus regex-scanning the rendered DOM. That covers
 * the player pages that set video.src or fetch the manifest via XHR/fetch.
 */
object FxWebView {

    private var webView: WebView? = null
    private var initializing = false

    /** The embedded WebEngine is a single shared resource — every navigation
     *  (rendered HTML, request capture, image capture) is serialized through
     *  this lock so two callers can't clobber each other's in-flight page. */
    private val engineLock = Any()

    fun ensure() {
        Fx.runBlock {
            if (webView == null && !initializing) {
                initializing = true
                try {
                    webView = WebView().apply {
                        prefWidth = 1280.0
                        prefHeight = 720.0
                        isContextMenuEnabled = false
                    }
                } finally {
                    initializing = false
                }
            }
        }
    }

    /** WebEngine.setUserAgent is only available from JavaFX 21 — the version
     *  this app builds against. */
    fun setUserAgent(ua: String) {
        ensure()
        Fx.runBlock {
            runCatching { webView!!.engine.setUserAgent(ua) }
        }
    }

    private val engine: WebEngine
        get() {
            ensure()
            return webView!!.engine
        }

    /** Loads [url] and returns the rendered DOM HTML (null on failure/timeout). */
    fun renderedHtml(url: String, timeoutMs: Long = 25_000): String? = synchronized(engineLock) {
        ensure()
        Fx.runBlock {
            runCatching { engine.load(url) }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastHtml: String? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700)
            val state = Fx.runBlock { loadState() }
            if (state == "FAILED") {
                // keep polling briefly — challenge pages reload and retry
                continue
            }
            val html = Fx.runBlock { runCatching { engine.executeScript("document.documentElement.outerHTML") as String }.getOrNull() }
            if (html != null) {
                lastHtml = html
                if (!looksLikeChallengePage(html)) return html
            }
            if (System.currentTimeMillis() > deadline - 1200) break
        }
        return lastHtml
    }

    /**
     * Fetches an image's bytes through the embedded WebEngine — the app's
     * plain OkHttp stack is TLS-fingerprinted (Cloudflare etc.) on several
     * poster CDNs that serve real browsers fine, so this is the fallback the
     * poster loader uses when its own HTTP request fails or returns a
     * challenge page. The image URL is loaded AS THE DOCUMENT (making it the
     * document's origin, so a same-origin canvas is never tainted), drawn to a
     * canvas and returned as a base64 JPEG data URL — null on failure/timeout.
     */
    fun imageBytes(url: String, timeoutMs: Long = 20_000): ByteArray? = synchronized(engineLock) {
        ensure()
        Fx.runBlock {
            runCatching { engine.load(url) }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var emptiesAfterLoad = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            val state = Fx.runBlock { loadState() }
            val v = Fx.runBlock {
                runCatching { engine.executeScript(IMAGE_BYTES_JS) as? String }.getOrNull()
            }
            if (!v.isNullOrBlank()) {
                val comma = v.indexOf(',')
                if (comma < 0) return null
                return runCatching {
                    java.util.Base64.getDecoder().decode(v.substring(comma + 1))
                }.getOrNull()
            }
            // A WAF/error page is usually served as a *document* that loads in
            // well under a second — once the load has SUCCEEDED and still has
            // no drawable image a few polls later, it's a page, not a poster.
            // Fail fast instead of holding the shared engine for the full
            // timeout on every doomed poster.
            if (state == "FAILED") return null
            if (state == "SUCCEEDED") {
                emptiesAfterLoad++
                if (emptiesAfterLoad >= 8) return null
            }
        }
        null
    }

    private val IMAGE_BYTES_JS = """
        (function(){
          try {
            var img = null;
            try { img = document.querySelector('img'); } catch(e){}
            if (!img) { try { img = document.images[0]; } catch(e){} }
            if (!img && document.body && document.body.tagName === 'IMG') img = document.body;
            if (!img || !img.naturalWidth || !img.naturalHeight) return '';
            var w = Math.min(img.naturalWidth, 640);
            var h = Math.max(1, Math.round(img.naturalHeight * (w / img.naturalWidth)));
            var c = document.createElement('canvas');
            c.width = w; c.height = h;
            c.getContext('2d').drawImage(img, 0, 0, w, h);
            var url = c.toDataURL('image/jpeg', 0.85);
            if (url && url.length > 64) return url;
            return '';
          } catch(e) { return ''; }
        })();
    """.trimIndent()

    /** Result of a request-capture run. */
    data class Capture(
        val requests: List<CapturedRequest> = emptyList(),
        val cookie: String = "",
    )

    data class CapturedRequest(val url: String, val method: String)

    /**
     * Loads [url] and captures every request whose URL matches [capture] (or
     * [additional]). Polls the recorder script + DOM until a match or timeout.
     */
    fun resolve(
        url: String,
        capture: Regex,
        additional: List<Regex>,
        script: String?,
        timeoutMs: Long,
    ): Capture = synchronized(engineLock) {
        ensure()
        Fx.runBlock {
            runCatching { engine.load(url) }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val hits = mutableListOf<CapturedRequest>()
        var cookie = ""
        var lastAll = emptyList<CapturedRequest>()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(600)
            val tick = Fx.runBlock { runCatching { poll() }.getOrNull() }
            if (tick != null) {
                lastAll = tick.requests
                cookie = tick.cookie
                val matched = tick.requests.filter {
                    capture.containsMatchIn(it.url) || additional.any { a -> a.containsMatchIn(it.url) }
                }
                hits += matched
                if (hits.isNotEmpty()) break
            }
            if (script != null) {
                Fx.runBlock { runCatching { engine.executeScript(script) } }
            }
        }
        Capture(
            requests = (hits + lastAll).distinctBy { it.url },
            cookie = cookie,
        )
    }

    private fun loadState(): String = runCatching {
        engine.loadWorker.state.name
    }.getOrDefault("UNKNOWN")

    private val RECORDER_JS = """
        (function(){
          if (window.__hkHooked) return;
          window.__hkReqs = [];
          function push(m, u){ try { window.__hkReqs.push({m:m, u:String(u)}); } catch(e){} }
          try {
            var f = window.fetch;
            if (f && !f.__hk) {
              window.fetch = function(){
                var arg = arguments[0];
                try { push('fetch', arg && arg.url ? arg.url : arg); } catch(e){}
                return f.apply(this, arguments);
              };
              window.fetch.__hk = true;
            }
          } catch(e){}
          try {
            var oo = XMLHttpRequest.prototype;
            var ooOpen = oo.open;
            if (!ooOpen.__hk) {
              oo.open = function(m, u){ try { push('xhr', u); } catch(e){} return ooOpen.apply(this, arguments); };
              oo.open.__hk = true;
            }
          } catch(e){}
          try {
            var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
            if (d && d.set && !d.set.__hk) {
              Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: true,
                get: function(){ return d.get.call(this); },
                set: function(v){ try { push('video', v); } catch(e){} return d.set.call(this, v); }
              });
            }
          } catch(e){}
          window.__hkHooked = true;
        })();
    """.trimIndent()

    private val POLL_JS = """
        (function(){
          try { $RECORDER_JS } catch(e){}
          var reqs = [];
          try {
            for (var i=0;i<(window.__hkReqs||[]).length;i++){
              var r = window.__hkReqs[i];
              if (r && r.u) reqs.push({m:r.m, u:r.u});
            }
          } catch(e){}
          var html = '';
          try { html = document.documentElement.outerHTML; } catch(e){}
          return JSON.stringify({reqs: reqs, cookie: document.cookie || '', html: html});
        })();
    """.trimIndent()

    private fun poll(): Capture {
        val raw = runCatching { engine.executeScript(POLL_JS) as? String }.getOrNull()
        if (raw == null) return Capture()
        return runCatching {
            val o = JSONObject(raw)
            val reqs = JSONArray(o.optString("reqs"))
            val out = mutableListOf<CapturedRequest>()
            for (i in 0 until reqs.length()) {
                val r = reqs.optJSONObject(i) ?: continue
                val u = r.optString("u")
                if (u.isNotBlank()) out += CapturedRequest(u, r.optString("m", "get"))
            }
            // also sweep the DOM html for stream-ish URLs matching our regexes
            val html = o.optString("html")
            if (html.isNotEmpty()) {
                Regex("""(https?://[^"'\\s<>]+?(?:\.m3u8|\.mp4|master\.m3u8|index\.m3u8)[^"'\\s<>]*)""")
                    .findAll(html)
                    .forEach { m -> out += CapturedRequest(m.value.trim(), "dom") }
            }
            Capture(out.distinctBy { it.url }, o.optString("cookie"))
        }.getOrDefault(Capture())
    }

    private fun looksLikeChallengePage(html: String): Boolean {
        val probe = html.take(40_000)
        return CHALLENGE_MARKERS.any { probe.contains(it, ignoreCase = true) }
    }

    private val CHALLENGE_MARKERS = listOf(
        "cf-chl-", "challenge-platform", "cdn-cgi/challenge-platform",
        "cf-browser-verification", "cf-mitigated", "cf-turnstile",
        "Just a moment", "Attention Required!", "enablejs",
        "Pardon Our Interruption", "Checking your browser",
        "checking your browser", "Verify you are human", "verify you are human",
        "hcaptcha", "h-captcha", "Access Denied", "datadome", "detectportal",
    )
}
