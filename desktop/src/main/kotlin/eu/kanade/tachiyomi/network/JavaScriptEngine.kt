package eu.kanade.tachiyomi.network

import android.content.Context
import com.hikari.app.js.JsRuntime
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Util for evaluating JavaScript in sources.
 *
 * Aniyomi runs this on `app.cash.quickjs`; the desktop runs every JS engine on
 * V8 ([com.hikari.app.js.JsRuntime]), the same VM the nuvio and SkyStream
 * runtimes use, so the class keeps Aniyomi's exact public shape — that is all
 * an extension's bytecode links against — and delegates to it.
 */
class JavaScriptEngine(context: Context) {

    @Suppress("UNUSED", "UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T = withIOContext {
        val qjs = JsRuntime.create()
        try {
            qjs.evaluate<Any?>(script, "extension.js", false) as T
        } finally {
            runCatching { qjs.close() }
        }
    }
}
