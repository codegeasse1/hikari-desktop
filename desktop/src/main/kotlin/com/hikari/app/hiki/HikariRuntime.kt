package com.hikari.app.hiki

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderConfig
import com.hikari.ext.HikariProvider
import java.io.File

/**
 * Resolves a [HikariProvider] instance for a HIKARI provider config:
 *  - config.url blank      → built-in provider: extra is the fully-qualified
 *                            class name on the app classpath (the bundled YTS
 *                            demo).
 *  - config.url is a .hiki → external extension jar loaded via
 *                            [HikariPluginManager]; extra encodes the optional
 *                            source URL plus the provider index:
 *                            "<sourceUrl>|<index>" (or just "<index>").
 */
object HikariRuntime {

    fun providerFor(config: ProviderConfig): HikariProvider? {
        val path = config.url
        if (path.isBlank()) {
            val className = config.extra?.takeIf { it.isNotBlank() } ?: return null
            return try {
                Class.forName(className).getDeclaredConstructor().newInstance() as? HikariProvider
            } catch (t: Throwable) {
                null
            }
        }
        val file = File(path)
        if (!file.exists()) return null
        val list = HikariPluginManager.providersFor(HikariApp.instance, file)
        if (list.isEmpty()) return null
        val index = config.extra?.substringAfterLast('|')?.toIntOrNull() ?: 0
        list.getOrNull(index)?.let { return it }
        // Stale index after extension update (manifest order changed) — fall
        // back to matching by provider id embedded in config.id, else by name.
        // This is what makes an updated anime.jar / cncverse.hiki keep working
        // instead of "did not resolve" until the user re-adds every provider.
        val wantId = config.id.substringAfter('|', config.id).lowercase()
        list.firstOrNull { it.id.lowercase() == wantId }?.let { return it }
        list.firstOrNull { it.id.lowercase() == config.id.lowercase() }?.let { return it }
        val wantName = config.name.substringAfter('·').substringAfter('>').trim().lowercase()
        if (wantName.isNotBlank()) {
            list.firstOrNull { it.name.lowercase() == wantName }?.let { return it }
            list.firstOrNull { it.name.lowercase().contains(wantName) || wantName.contains(it.name.lowercase()) }?.let { return it }
        }
        return list.getOrNull(0)
    }
}
