package app.cash.quickjs

import com.hikari.app.js.JsRuntime
import java.io.Closeable

/**
 * Desktop stand-in for `app.cash.quickjs.QuickJs` — the JS engine Aniyomi
 * extensions run their own scripts on.
 *
 * Measured over 96 real extensions from the live Aniyomi repositories: twelve of
 * them name `app.cash.quickjs.QuickJs` — the sites whose player or page needs a
 * small script run (an obfuscated link decoder, a token builder). On Android the
 * class is a JNI wrapper around QuickJS, and the desktop has no such library, so
 * those extensions failed to link (`NoClassDefFoundError:
 * app/cash/quickjs/QuickJs`) and could not be installed at all.
 *
 * The desktop already runs every JS engine it has on V8 — see
 * [com.hikari.app.js.JsRuntime], the same VM the SkyStream and nuvio runtimes
 * use — so this is a thin adapter over it: `create()` boots a VM, `evaluate()`
 * runs a script and hands back its value, `set()` binds a value as a global, and
 * `close()` tears the VM down. That is the whole API the twelve extensions use
 * (`create` 12/12, `evaluate` 12/12, `set` 3/12, `close` 1/12).
 *
 * One difference worth knowing: V8 (through javet) hands back strings, numbers,
 * booleans and null, but not live JS objects. An extension whose script returns
 * an OBJECT gets `null` here rather than Android's opaque `JSValue` wrapper —
 * scripts that end in a string (every one in the corpus) are unaffected.
 */
class QuickJs private constructor(private val runtime: JsRuntime?) : Closeable {

    @Volatile private var closed = false

    /** Runs [script] and returns its completion value, as a plain JVM value. */
    fun evaluate(script: String): Any? {
        if (closed) return null
        val rt = runtime ?: return null
        return runCatching { rt.evaluate<Any?>(script, "quickjs", false) }.getOrNull()
    }

    /**
     * Binds [value] as a global called [name] before the script runs.
     *
     * Android's signature takes the value's `Class` as well (it uses it to pick
     * the Java↔JS conversion); the value itself is all the desktop adapter needs.
     */
    @Suppress("UNUSED_PARAMETER")
    fun set(name: String, type: Class<*>, value: Any?) {
        if (closed) return
        val rt = runtime ?: return
        runCatching { rt.setGlobal(name, value) }
    }

    /** Also the spelling Android's QuickJs exposes (`set(name, value)`). */
    fun set(name: String, value: Any?) {
        if (closed) return
        val rt = runtime ?: return
        runCatching { rt.setGlobal(name, value) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { runtime?.close() }
    }

    companion object {

        /** Why the last [create] could not start a VM, when it could not: the
         *  bundled V8 missing its native library, an unsupported CPU, a missing
         *  VC++ runtime. Null after a VM started. */
        @Volatile
        var lastError: String? = null
            private set

        /**
         * Boots a JS VM for one extension.
         *
         * When V8 cannot start, this returns a QuickJs whose `evaluate` gives
         * null instead of throwing: an extension that decodes a link with JS can
         * then fall back to its own non-JS path and report its own reason, which
         * is a better outcome than an install-time failure for a machine problem.
         */
        @JvmStatic
        fun create(): QuickJs {
            val rt = runCatching { JsRuntime.create() }.getOrElse { e ->
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "V8 could not start"}"
                return QuickJs(null)
            }
            lastError = null
            return QuickJs(rt)
        }

        /** True once a VM has been started successfully on this machine. */
        val available: Boolean get() = JsRuntime.available
    }
}
