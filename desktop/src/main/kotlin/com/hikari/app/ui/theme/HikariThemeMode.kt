package com.hikari.app.ui.theme

/** Theme mode key persisted by AppStore. Desktop keeps the same keys. */
enum class HikariThemeMode(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    ;

    companion object {
        fun fromKey(key: String): HikariThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}
