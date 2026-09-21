package com.hikari.app

/**
 * Desktop stand-in for the Gradle-generated `BuildConfig` the Android app gets.
 *
 * Vendored Aniyomi code (and, through it, `eu.kanade.tachiyomi.AppInfo`) reads
 * the HOST app's version: some extensions put it in a User-Agent, and a few
 * gate a work-around on "host version >= N". Those checks must see the same
 * numbers they would on Android, so this mirrors the Android app's gradle
 * `versionCode`/`versionName` rather than the desktop release number (which
 * lives in [desktop.Build] and is what the UI shows).
 */
object BuildConfig {
    const val VERSION_CODE = 164
    const val VERSION_NAME = "0.9.8"
}
