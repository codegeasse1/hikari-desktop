package com.hikari.app

import android.app.Application
import com.hikari.app.data.AppStore
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import com.hikari.app.providers.ProviderManager
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SettingsJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Desktop HikariApp. Mirrors the Android Application class but with no Android
 * runtime: the shim android.app.Application provides cacheDir/filesDir, and
 * init() wires the CloudStream runtime exactly like HikariApp.onCreate did.
 */
class HikariApp : Application() {

    companion object {
        @Volatile
        lateinit var instance: HikariApp
            private set

        /** Stack trace of the last uncaught crash (shown as a Home banner). */
        @Volatile
        var lastCrash: String? = null
            private set

        /**
         * The host activity, exactly as the Android app exposes it.
         *
         * It is not decorative: CloudStream plugins receive it in
         * `Plugin.load(context)`, cast it to an Activity — often straight to
         * `AppCompatActivity` — and read its shared preferences / fragment
         * manager. A null (or a bare Application) here made every plugin that
         * touches its activity fail to load on the desktop. The desktop's
         * stand-in is a real object of that class (it inherits the whole
         * Activity/AppCompat/FragmentActivity chain — see
         * [androidx.appcompat.app.DesktopActivity]); nothing it does is drawn.
         */
        @Volatile
        var mainActivity: Any? = androidx.appcompat.app.DesktopActivity.instance()
            private set
    }

    lateinit var store: AppStore
        private set
    lateinit var providers: ProviderManager
        private set
    lateinit var repository: ContentRepository
        private set

    @Volatile
    var elementBlocks: List<String> = emptyList()

    val homeTabRequest = MutableStateFlow(0)

    @Volatile
    var webViewUseDefaultUa = false

    @Volatile
    var webViewCustomUa: String? = null

    fun init() {
        instance = this
        com.hikari.app.util.LiveLogs.install()
        installCrashHandler()
        initCloudStream(this)
        store = AppStore(filesDir)
        providers = ProviderManager(store)
        repository = ContentRepository(providers)
        registerAniyomiSingletons()
        Http.init()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { elementBlocks = store.elementBlocks() }
            runCatching { webViewUseDefaultUa = store.webviewUseDefaultUa() }
            runCatching { webViewCustomUa = store.webviewCustomUa() }
            runCatching {
                com.hikari.app.cs3.HikariExtractorRegistry.register()
            }
            runCatching {
                if (store.providers().none { it.type == ProviderType.HIKARI }) {
                    store.addProvider(
                        ProviderConfig(
                            id = "hiki|yts",
                            name = "YTS (Hikari)",
                            type = ProviderType.HIKARI,
                            iconUrl = null,
                            extra = "com.hikari.ext.providers.YtsProvider",
                        )
                    )
                }
            }
            runCatching {
                if (store.providers().none { it.type == ProviderType.STREMIO }) {
                    store.addProvider(
                        ProviderConfig(
                            id = "stremio|yts",
                            name = "YTS (Stremio)",
                            type = ProviderType.STREMIO,
                            url = "https://v3-cinemeta.strem.io/manifest.json",
                        )
                    )
                }
            }
            providers.refresh()
            // Seed the bundled engine repos once, exactly like the Android app:
            // the Yoru (nuvio), SkyStream and Aniyomi default repos are how
            // those engines are discovered at all, and a first run can't be
            // expected to know their URLs. Non-fatal — every call is already
            // wrapped in its own runCatching.
            runCatching { com.hikari.app.nuvio.NuvioPluginManager.seedDefaults(this@HikariApp, store) }
            runCatching { com.hikari.app.skystream.SkyStreamPluginManager.seedDefaults(this@HikariApp, store) }
            runCatching { com.hikari.app.aniyomi.AniyomiExtensionManager.seedDefaults(this@HikariApp, store) }
            runCatching { providers.refresh() }
            runCatching { com.hikari.app.data.Translator.init(store) }
        }
    }

    private fun installCrashHandler() {
        runCatching {
            val file = File(cacheDir, "crash.log")
            if (file.exists()) {
                val text = file.readText()
                // Surface the crash on the launch right after it happened, then
                // clear it — a crash report from an older launch must not keep
                // announcing itself on every startup (it makes a working build
                // look broken, and a stale report from a fixed bug confuses).
                if (text.contains(desktop.Build.DATE)) lastCrash = text.take(1600)
                file.delete()
            }
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val trace = "[${desktop.Build.DATE}] ${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTrace.take(12).joinToString("\n") { "    at $it" }
                File(cacheDir, "crash.log").writeText(trace)
                lastCrash = trace
            }
            System.err.println("Uncaught on ${thread.name}")
            t.printStackTrace()
        }
    }

    fun clearCrash() {
        lastCrash = null
        runCatching { File(cacheDir, "crash.log").delete() }
    }

    /**
     * Primes the Injekt container with the singletons an Aniyomi extension can
     * ask for — `Application` (every `ConfigurableAnimeSource` /
     * `AnimeHttpSource` preference accessor is
     * `Injekt.get<Application>().getSharedPreferences(...)`, and a source that
     * cannot get its preferences throws before it can list anything), plus the
     * `Json`, `NetworkHelper` and `JavaScriptEngine` that the JSON helpers, the
     * shared OkHttp stack and the JS-driven sources inject.
     *
     * All singletons, so every installed extension shares the app's one OkHttp
     * stack instead of building its own. A lookup that was never registered
     * throws at the extension's own call site, which the provider turns into a
     * per-source error rather than a crash.
     */
    private fun registerAniyomiSingletons() {
        runCatching {
            uy.kohesive.injekt.Injekt.addSingleton<Application>(this)
            uy.kohesive.injekt.Injekt.addSingleton<android.content.Context>(this)
            uy.kohesive.injekt.Injekt.addSingletonFactory<kotlinx.serialization.json.Json> {
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            }
            uy.kohesive.injekt.Injekt.addSingletonFactory<eu.kanade.tachiyomi.network.NetworkHelper> {
                eu.kanade.tachiyomi.network.NetworkHelper(this)
            }
            uy.kohesive.injekt.Injekt.addSingletonFactory<eu.kanade.tachiyomi.network.JavaScriptEngine> {
                eu.kanade.tachiyomi.network.JavaScriptEngine(this)
            }
        }.onFailure {
            System.err.println("Aniyomi singleton registration failed: $it")
        }
    }

    /** UA string the WebViews should advertise. Desktop has no Android WebView
     *  default, so the custom UA wins when set; otherwise the desktop Chrome
     *  UA (the one the WAFs expect from a desktop browser). */
    fun effectiveWebViewUa(pluginUa: String? = null): String {
        val custom = webViewCustomUa?.trim()
        if (!custom.isNullOrBlank()) return custom
        if (!webViewUseDefaultUa && !pluginUa.isNullOrBlank()) return pluginUa
        return Http.UA
    }

    private fun initCloudStream(context: android.content.Context) {
        try {
            try {
                com.lagradost.cloudstream3.CloudStreamApp.setContext(context)
            } catch (t: Throwable) {
                System.err.println("CloudStreamApp.setContext failed: $t")
            }
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (_: Throwable) {
            }

            fun build(ignoreSSL: Boolean) = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                // Plugins fetch their catalogs/streams through these clients, so
                // they must behave like the app's own network layer: DoH-first
                // DNS + the OS system proxy (fixes filtered-resolver / proxy-only
                // networks). TLS is explicitly Conscrypt everywhere — the JDK's
                // lazy sun.security.ssl class-init fails fatally on Windows
                // (NoClassDefFoundError: SSLSessionImpl) once Conscrypt is the
                // default provider, so the JDK TLS stack is never touched.
                // The WebView fallback interceptor re-fetches WAF challenge
                // pages (Cloudflare etc.) inside the real browser so plugins
                // get content instead of an empty catalog.
                .dns(com.hikari.app.net.HikariDns)
                .proxySelector(java.net.ProxySelector.getDefault())
                .addInterceptor(com.hikari.app.net.WebViewFallbackInterceptor())
                .apply {
                    if (ignoreSSL) ignoreAllSSLErrors()
                    else com.hikari.app.net.Http.applyConscryptTls(this)
                }
                .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024))
                .build()

            val kt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
            fun wire(getter: String, ignoreSSL: Boolean) {
                val req = kt.getMethod(getter).invoke(null) as Requests
                req.baseClient = build(ignoreSSL)
            }
            wire("getApp", ignoreSSL = false)
            wire("getInsecureApp", ignoreSSL = true)
            // The CloudStream PRODUCTION client the plugins themselves call through
            // (`com.lagradost.cloudstream3.app`) is a SEPARATE Requests object from
            // getApp/getInsecureApp — unless it's pointed at the same resilient
            // stack it keeps a bare default OkHttpClient: system DNS (can be ISP-
            // filtered), no system proxy, and no WAF fallback — so catalogs from
            // Cloudflare-fronted anime/tube/… sites come back empty even after a
            // successful install. Route it through the same DoH+proxy+Conscrypt+
            // WebView-fallback client used everywhere else.
            runCatching { com.lagradost.cloudstream3.app.baseClient = build(false) }
            MainAPI.settingsForProvider = SettingsJson()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
                } catch (t: Throwable) {
                    System.err.println("extractor registry init failed: $t")
                }
            }
        } catch (t: Throwable) {
            System.err.println("CloudStream runtime init failed: $t")
        }
    }
}
