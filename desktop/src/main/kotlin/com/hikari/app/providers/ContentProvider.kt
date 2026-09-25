package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource

interface ContentProvider {
    val config: ProviderConfig

    suspend fun catalogs(): List<CatalogRef>
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>

    /**
     * Why this provider's last [getStreams] call came back empty, or null when
     * it did not (or when this provider cannot say).
     *
     * An empty server list with no explanation is the worst state a streaming
     * app can leave a user in: the app knows the difference between "this
     * extension has no such title", "the site answered 403" and "the scraper
     * threw", and it says so here. The detail screen collects these from every
     * provider the sweep asked (see [ContentRepository.sweepErrors]).
     */
    fun lastStreamError(): String? = null
}
