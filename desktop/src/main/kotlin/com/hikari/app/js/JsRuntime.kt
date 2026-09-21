package com.hikari.app.js

import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.exceptions.JavetException
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.primitive.V8ValueDouble
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueLong
import com.caoccao.javet.values.primitive.V8ValueNull
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.primitive.V8ValueUndefined
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A single JavaScript VM, on V8 (javet).
 *
 * The Android app runs SkyStream `.sky` plugins and nuvio providers in QuickJS
 * (`com.dokar.quickjs`). Both plugin families are plain JS written with
 * `async/await`, so the desktop needs a real ES2020 engine — Rhino, which the
 * desktop already has for the CloudStream runtime, cannot even parse an
 * `async function`. This class is deliberately shaped like the QuickJS API the
 * two runtimes were written against — `function(name) { args -> … }`,
 * `evaluate<T>(js, name, marshall)`, `evaluationTimeoutMillis`, `close()` — so
 * the ported runtime code stays a faithful copy of the Android original
 * instead of being rewritten around a different engine's idioms.
 *
 * One engine per call: the caller [close]es it, so a hung or crashing plugin
 * can only take its own VM down.
 *
 * Microtasks: V8 in javet's V8 mode has no event loop of its own, so
 * [V8Runtime.await] drains the pending microtask queue instead. Every
 * [evaluate] does that after running, which is what lets a plugin's `await`
 * chain (and any follow-up fetch it triggers) make progress between the host's
 * pump rounds.
 */
class JsRuntime private constructor(private val v8: V8Runtime) {

    /** Wall-clock budget for one [evaluate]; the runtime is terminated when it
     *  expires (0 = no limit). Mirrors QuickJs.evaluationTimeoutMillis. */
    var evaluationTimeoutMillis: Long = 0L

    private val watchdogs: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hikari-js-watchdog").apply { isDaemon = true }
        }
    }

    /**
     * Binds a Kotlin lambda as a global JS function.
     *
     * `args` are the call's arguments converted to plain Kotlin values (String,
     * Int, Long, Double, Boolean or null); the returned value is converted back.
     * Host functions must be synchronous — the plugin's call is a direct V8
     * call on the engine thread — which is exactly how the fetch bridge works.
     */
    fun function(name: String, body: (List<Any?>) -> Any?) {
        val callable = object : IJavetDirectCallable.NoThisAndResult<JavetException> {
            override fun call(vararg v8Values: V8Value?): V8Value? {
                val args = ArrayList<Any?>(v8Values.size)
                for (v in v8Values) args.add(readPrimitive(v))
                return toV8Value(body(args))
            }
        }
        val context = JavetCallbackContext(name, JavetCallbackType.DirectCallNoThisAndResult, callable)
        // V8 keeps a native handle to the callback context for the lifetime of
        // the function object, so the Java side has to stay reachable too.
        keepAlive.add(context)
        v8.globalObject.set(name, v8.createV8ValueFunction(context))
    }

    /** Strong refs to every [JavetCallbackContext] this VM handed to V8. */
    private val keepAlive = ArrayList<Any>()

    /**
     * Runs a script and returns its completion value as a Kotlin primitive.
     *
     * `marshall` is accepted for source compatibility with the QuickJS call
     * sites, which pass `false` to mean "hand back the raw value, don't
     * JSON-stringify it" — which is what this always does.
     *
     * Non-primitive results (the Promise an async IIFE returns, an object, an
     * array) deliberately come back as null: every caller either ignores the
     * value or wants a timer's number, and converting a live object would drag
     * the whole V8 graph through the converter for nothing.
     */
    fun <T> evaluate(js: String, name: String = "", marshall: Boolean = true): T? {
        val watchdog = arm()
        try {
            val raw: V8Value = v8.getExecutor(js).execute<V8Value>(true)
            // No event loop in V8 mode — this is what drains the microtask
            // queue, i.e. what resumes the plugin's `await`s.
            runCatching { v8.await(V8AwaitMode.RunTillNoMoreTasks) }
            @Suppress("UNCHECKED_CAST")
            return toKotlin(raw) as T?
        } finally {
            watchdog?.cancel(false)
        }
    }

    /** [evaluate] for statements: the completion value is discarded. */
    fun evaluateVoid(js: String, name: String = "") {
        evaluate<Any?>("$js\n;void 0;\n", name, false)
    }

    fun close() {
        runCatching { v8.close() }
        if (watchdogsInitialized) runCatching { watchdogs.shutdownNow() }
    }

    /** True once [arm] has had to start the watchdog thread, so [close] never
     *  spins one up just to shut it down again. */
    @Volatile
    private var watchdogsInitialized = false

    /** Schedules a terminate for [evaluationTimeoutMillis] from now, if a
     *  budget is set. V8 termination raises inside the running script. */
    private fun arm(): ScheduledFuture<*>? {
        val budget = evaluationTimeoutMillis
        if (budget <= 0L) return null
        watchdogsInitialized = true
        return runCatching {
            watchdogs.schedule({
                runCatching { v8.terminateExecution() }
            }, budget, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Reads a V8 value as a plain Kotlin primitive.
     *
     * Callback arguments are V8-owned: javet releases them itself once the
     * callback returns, so this deliberately does not close them. The value
     * [evaluate] gets back IS ours, and [toKotlin] releases that one.
     */
    private fun readPrimitive(v: V8Value?): Any? = when (v) {
        null -> null
        is V8ValueNull -> null
        is V8ValueUndefined -> null
        is V8ValueBoolean -> v.value
        is V8ValueInteger -> v.value
        is V8ValueLong -> v.value
        is V8ValueDouble -> v.value
        is V8ValueString -> v.value
        else -> null
    }

    private fun toKotlin(v: V8Value?): Any? = try {
        readPrimitive(v)
    } finally {
        runCatching { v?.close() }
    }

    /**
     * Converts a host-function result back into V8.
     *
     * The primitives are built with V8's own creators rather than the generic
     * `V8Runtime.toV8Value` converter: the converter is for marshalling arbitrary
     * Java objects and is the one path that does not survive being called from
     * inside a V8 callback (the conversion throws, and the callback then reports
     * `undefined` to the plugin).
     */
    private fun toV8Value(value: Any?): V8Value = when (value) {
        null -> v8.createV8ValueUndefined()
        is String -> v8.createV8ValueString(value)
        is Boolean -> v8.createV8ValueBoolean(value)
        is Int -> v8.createV8ValueInteger(value)
        is Long -> v8.createV8ValueLong(value)
        is Double -> v8.createV8ValueDouble(value)
        is Float -> v8.createV8ValueDouble(value.toDouble())
        is Number -> v8.createV8ValueDouble(value.toDouble())
        else -> v8.createV8ValueString(value.toString())
    }

    companion object {

        /** True once the platform's V8 native library has loaded. Engines are
         *  reported as unavailable rather than crashing the whole app if the
         *  bundled V8 cannot load (a missing VC++ runtime, an unsupported CPU)
         *  — everything that is not a JS extension keeps working. */
        @Volatile
        var lastError: String? = null
            private set

        val available: Boolean
            get() = lastError == null

        /** Boots a fresh VM. Throws [JavetException] when V8 is unavailable. */
        fun create(): JsRuntime {
            try {
                val v8 = V8Host.getV8Instance().createV8Runtime<V8Runtime>()
                lastError = null
                return JsRuntime(v8)
            } catch (e: Throwable) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "V8 could not start"}"
                throw e
            }
        }
    }
}
