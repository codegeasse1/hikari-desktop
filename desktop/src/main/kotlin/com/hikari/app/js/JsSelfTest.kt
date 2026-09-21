package com.hikari.app.js

/**
 * CI self-test for the V8-backed JS engine.
 *
 * The SkyStream and nuvio runtimes are ported from the Android app, which runs
 * plugins in QuickJS. The desktop runs them in V8 instead (Rhino cannot parse
 * `async`), and every one of those plugins is written with `async/await` and
 * resolves its answer through a host bridge — so the engine has to prove four
 * things before a release carries it:
 *
 *   1. a script runs and a host function is callable from JS,
 *   2. `async function` / `await` parse and settle,
 *   3. microtasks are drained at the end of an evaluation (the `await` chain
 *      resumes without the host having to poll),
 *   4. a promise created and awaited in ONE evaluation can be resolved from a
 *      LATER evaluation — the pump pattern both runtimes are built on.
 *
 * Exits non-zero on failure so the workflow fails the build.
 */
object JsSelfTest {

    private var failures = 0

    private fun check(name: String, actual: Any?, expected: Any?) {
        if (actual == expected) {
            println("  ok   $name = $actual")
        } else {
            println("  FAIL $name = $actual (expected $expected)")
            failures++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("JsSelfTest: javet = ${runCatching { com.caoccao.javet.interop.V8Host.getV8Instance().javetVersion }.getOrElse { "?" }}")

        val js = try {
            JsRuntime.create()
        } catch (e: Throwable) {
            println("FAIL: V8 could not start: ${e.message ?: e.javaClass.simpleName}")
            System.exit(1)
            return
        }

        try {
            js.evaluationTimeoutMillis = 15_000L

            // 1. plain script + a host function
            js.function("__host") { a -> "got:" + a.joinToString("-") { it?.toString().orEmpty() } }
            check("script", js.evaluate<String>("'a' + 'b'", "t1", false), "ab")
            check("host fn", js.evaluate<String>("__host('x', 'y')", "t2", false), "got:x-y")

            // 2 + 3. async/await, with the answer travelling through a microtask
            js.evaluateVoid(
                """
                globalThis.__asyncResult = null;
                globalThis.__runAsync = function () {
                  globalThis.__asyncResult = 'pending';
                  (async function () {
                    var a = await Promise.resolve(1);
                    var b = __host('p');
                    globalThis.__asyncResult = a + '|' + b;
                  })();
                };
                """.trimIndent(),
                "async-def",
            )
            js.evaluate<String>("__runAsync(); 'go'", "async-run", false)
            check("async/await", js.evaluate<String>("globalThis.__asyncResult", "async-read", false), "1|got:p")

            // 4. resolve a promise across two evaluations (the pump pattern)
            js.evaluateVoid(
                """
                globalThis.__resolved = null;
                globalThis.__start = function () {
                  globalThis.__pending = new Promise(function (r) { globalThis.__resolve = r; });
                  globalThis.__pending.then(function (v) { globalThis.__resolved = 'v=' + v; });
                  return 'started';
                };
                """.trimIndent(),
                "pump-def",
            )
            check("pump start", js.evaluate<String>("__start()", "pump-1", false), "started")
            check("pump pending", js.evaluate<String>("globalThis.__resolved", "pump-2", false), null)
            js.evaluateVoid("globalThis.__resolve('late')", "pump-3")
            check("pump resolve", js.evaluate<String>("globalThis.__resolved", "pump-4", false), "v=late")

            // timer-style numeric result (SkyStream's __skyFireTimer returns a number)
            check("number", (js.evaluate<Any?>("1 + 2", "t3", false) as? Number)?.toLong(), 3L)
            check("boolean", js.evaluate<Any?>("true", "t4", false), true)

            // a thrown error must surface as a failure, not a silent null
            val threw = runCatching { js.evaluate<Any?>("throw new Error('boom')", "t5", false) }.isFailure
            check("throws", threw, true)
        } catch (e: Throwable) {
            println("FAIL: ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            runCatching { js.close() }
        }

        if (failures == 0) {
            println("JsSelfTest: ALL OK")
        } else {
            println("JsSelfTest: $failures FAILURE(S)")
            System.exit(1)
        }
    }
}
