@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.content.Context
import com.hikari.app.HikariApp
import desktop.fx.DesktopUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * Shadow of CloudStream's CloudStreamApp (desktop edition). The jar ships the
 * DESKTOP artifact of CloudStream, whose CloudStreamApp is compiled against
 * Coil 3 and dies on the JVM with NoClassDefFoundError the moment a plugin
 * touches it — so the jar class is dropped (cloudstreamJarClean) and replaced
 * by this self-contained implementation, which provides the Companion API
 * plugins actually call: `context`, the persisted key/value store
 * (`getKey`/`setKey`/…), activity lookup and `openBrowser`.
 */
class CloudStreamApp : android.app.Application() {

    companion object {
        @Volatile
        private var _context: WeakReference<Context>? = null

        @Volatile
        private var _exceptionHandler: ExceptionHandler? = null

        /** Current host context (wired by HikariApp at startup). */
        val context: Context?
            get() = _context?.get()

        fun setContext(context: Context) {
            _context = WeakReference(context.applicationContext ?: context)
        }

        fun getExceptionHandler(): ExceptionHandler? = _exceptionHandler

        fun setExceptionHandler(handler: ExceptionHandler?) {
            _exceptionHandler = handler
        }

        fun getActivity(context: Context): android.app.Activity? = null

        // ---- persisted key/value store (mirrors CloudStream's plugin prefs) ----

        private val prefs: JSONObject? by lazy {
            runCatching {
                val f = File(HikariApp.instance.filesDir, "cloudstream_keys.json")
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            }.getOrNull()
        }

        private fun persist() {
            runCatching {
                val f = File(HikariApp.instance.filesDir, "cloudstream_keys.json")
                f.parentFile?.mkdirs()
                f.writeText(prefs.toString())
            }
        }

        private fun read(key: String): Any? {
            val p = prefs ?: return null
            if (!p.has(key)) return null
            val raw = p.optString(key)
            return try {
                val o = JSONObject(raw)
                when {
                    o.has("s") -> o.optString("s")
                    o.has("n") -> o.opt("n")
                    o.has("a") -> {
                        val arr = o.optJSONArray("a")
                        (0 until arr.length()).map { arr.opt(it) }
                    }
                    else -> o.opt("v")
                }
            } catch (_: Throwable) {
                raw
            }
        }

        private fun write(key: String, value: Any?) {
            val p = prefs ?: return
            if (value == null) {
                p.remove(key)
                persist()
                return
            }
            val o = JSONObject()
            try {
                when (value) {
                    is String -> o.put("s", value)
                    is Number, is Boolean -> o.put("n", value)
                    is List<*> -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it as? Any ?: JSONObject.NULL) }
                        o.put("a", arr)
                    }
                    else -> o.put("s", value.toString())
                }
                p.put(key, o.toString())
                persist()
            } catch (_: Throwable) {
            }
        }

        fun setKey(key: String, value: Any?) = write(key, value)

        fun setKey(key: String, type: String, value: Any?) = write(key, value)

        fun setKeyClass(key: String, value: Any?) = write(key, value)

        fun getKey(key: String): Any? = read(key)

        fun getKey(key: String, default: Any?): Any? = read(key) ?: default

        fun getKey(key: String, default: String?): Any? = read(key) ?: default

        fun getKey(key: String, type: String, default: Any?): Any? = read(key) ?: default

        fun getKeyClass(key: String, clazz: Class<*>): Any? = read(key)

        fun getKeys(key: String): List<Any?> =
            read(key) as? List<*> ?: emptyList()

        fun removeKey(key: String) {
            val p = prefs ?: return
            p.remove(key)
            persist()
        }

        fun removeKey(key: String, subKey: String) {
            val p = prefs ?: return
            p.remove(key)
            persist()
        }

        fun removeKeys(key: String): Int {
            val p = prefs ?: return 0
            val toRemove = p.keys().asSequence().filter { it == key || it.startsWith("$key.") || it.startsWith("$key$") }.toList()
            toRemove.forEach { p.remove(it) }
            persist()
            return toRemove.size
        }

        fun openBrowser(url: String, newTab: Boolean, fragment: Any?) {
            DesktopUi.open(url)
        }

        fun openBrowser(url: String, activity: Any?) {
            DesktopUi.open(url)
        }
    }
}
