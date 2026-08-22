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

        /** Kept for plugin compatibility (the Android host exposed its
         *  activity here). Desktop has no activity; always null. */
        @Volatile
        var mainActivity: Any? = null
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
        installCrashHandler()
        initCloudStream(this)
        store = AppStore(filesDir)
        providers = ProviderManager(store)
        repository = ContentRepository(providers)
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
            runCatching { com.hikari.app.data.Translator.init(store) }
        }
    }

    private fun installCrashHandler() {
        runCatching {
            val file = File(cacheDir, "crash.log")
            if (file.exists()) lastCrash = file.readText().take(1600)
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val trace = "${t.javaClass.simpleName}: ${t.message}\n" +
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
                .dns(com.hikari.app.net.HikariDns)
                .proxySelector(java.net.ProxySelector.getDefault())
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
