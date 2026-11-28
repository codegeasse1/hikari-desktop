package com.hikari.app.net

/** Shared network budget tuning mirrored from Android's Settings state. */
object NetTuning {
    private const val SLOW_MULTIPLIER = 3L

    @Volatile
    var slowConnection: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun setSlowConnection(enabled: Boolean) {
        if (slowConnection == enabled) return
        slowConnection = enabled
        listeners.forEach { runCatching { it() } }
    }

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun timeout(baseMs: Long): Long =
        if (slowConnection) baseMs * SLOW_MULTIPLIER else baseMs

    fun attempts(): Int = if (slowConnection) 2 else 1
}
