package com.hikari.app.download

import com.hikari.app.net.HikariDns
import com.hikari.app.net.Http
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The HTTP stack the download engine fetches with.
 *
 * Deliberately its own client rather than the shared [Http] one: a download
 * fans out over many parallel connections (several segments of the video
 * rendition, plus the audio rendition), and the OkHttp default of 5 requests
 * per host would serialize them into a fraction of the available bandwidth.
 * Everything else matches the rest of the app — Conscrypt TLS, DoH-first DNS,
 * the OS proxy — so a stream the catalog could see downloads too.
 */
object DownloadHttp {

    /** Segments in flight per host. The engine itself caps a single rendition at
     *  6 and runs video + audio concurrently, so 16 leaves headroom without
     *  letting a queue of tasks stampede one CDN. */
    private const val MAX_PER_HOST = 16

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = MAX_PER_HOST
        }
        Http.applyConscryptTls(OkHttpClient.Builder())
            .dispatcher(dispatcher)
            .dns(HikariDns)
            .proxySelector(java.net.ProxySelector.getDefault())
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
