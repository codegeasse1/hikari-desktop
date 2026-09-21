package com.hikari.app.data

import com.hikari.app.util.LiveLogs

/**
 * The desktop half of Android's `Logs` object: the extension loaders call
 * `Logs.logError(tag, message, throwable)` to record a failure that the Settings
 * log view shows. On the desktop that is [LiveLogs], which the app's own log
 * panel already reads — plus stderr, so a headless run (a CI self-test) still
 * prints the reason.
 */
object Logs {

    fun logError(tag: String, message: String, error: Throwable? = null) {
        System.err.println("E/$tag: $message")
        error?.printStackTrace()
        runCatching { LiveLogs.error(tag, message, error) }
    }

    fun log(tag: String, message: String) {
        runCatching { LiveLogs.log(tag, message) }
    }

    fun logWarn(tag: String, message: String) {
        runCatching { LiveLogs.warn(tag, message) }
    }
}
