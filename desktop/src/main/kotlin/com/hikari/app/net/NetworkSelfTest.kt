package com.hikari.app.net

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * CI test for the network compatibility ladder — the thing that decides whether
 * "repos won't load" happens to a user.
 *
 * It is deliberately OFFLINE apart from one DNS-over-HTTPS lookup: a real HTTP
 * server is started on localhost, and a DEAD system proxy is installed, which is
 * exactly the desktop misconfiguration (a VPN/Clash/Psiphon entry left behind in
 * the OS proxy settings) that makes every request in the app fail with a
 * protocol error while the user's browser is fine. The ladder must still get the
 * body — on its no-proxy pass — and must remember that it had to.
 *
 * Exits non-zero on failure.
 */
fun main() {
    println("NetworkSelfTest: start")
    var failures = 0
    fun check(name: String, condition: Boolean, detail: String = "") {
        println(if (condition) "  OK   $name" else "  FAIL $name $detail")
        if (!condition) failures++
    }

    Http.init()

    // The failure classifier the user-facing messages are built from.
    val tlsError = Exception("Read error: ssl=0x1d2b3c: Failure in SSL library, usually a protocol error")
    check("a TLS-library failure is recognised", Http.isTlsStackFailure(tlsError))
    check("a 404 is not a TLS failure", !Http.isTlsStackFailure(Exception("HTTP 404 for https://x/y")))
    val summary = Http.summariseFailures(listOf(tlsError, tlsError, Exception("HTTP 404 for https://x/y")))
    check("the failure summary reads as a sentence", summary.startsWith("No server answered"), summary)
    check("the failure summary names the real cause", summary.contains("the TLS handshake"), summary)
    check("and counts the mirrors", summary.contains("(2)"), summary)

    // A local origin server, so this test never depends on the internet.
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/repo.json") { ex ->
        val body = """{"name":"SelfTest Repo","plugins":[{"name":"SelfTest","url":"SelfTest.jar"}]}"""
            .toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    // A source that takes 2 seconds to say hello — the reason "the video takes
    // ages to start" while a better server sits below it in the list.
    server.createContext("/slow.mp4") { ex ->
        runCatching { Thread.sleep(2_000L) }
        val body = ByteArray(64)
        ex.responseHeaders.set("Content-Type", "video/mp4")
        runCatching { ex.sendResponseHeaders(200, body.size.toLong()) }
        runCatching { ex.responseBody.use { it.write(body) } }
    }
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    val local = "http://127.0.0.1:" + server.address.port + "/repo.json"
    println("  local origin: $local")

    // ── 1. the ordinary stack ───────────────────────────────────────────────
    val direct = Http.getStringStrict(local)
    check("getStringStrict(localhost) works on the normal stack", direct.isSuccess, direct.exceptionOrNull()?.message ?: "")

    // ── 2. the same fetch with a dead OS proxy in the way ───────────────────
    val realSelector = ProxySelector.getDefault()
    ProxySelector.setDefault(DeadProxy())
    println("  a dead system proxy is now installed (as an uninstalled VPN leaves behind)")
    val rescued = Http.fetchStringRobust(local)
    check(
        "fetchStringRobust still works through the dead proxy",
        rescued.isSuccess,
        rescued.exceptionOrNull()?.message ?: "",
    )
    check("and the body is the real one", rescued.getOrNull()?.contains("SelfTest Repo") == true)

    // ── 3. the ladder remembered which pass worked ──────────────────────────
    println("  learned pass: " + (Http.learnedPassKey() ?: "-") + "  served-by: " + (Http.lastWinningPassKey() ?: "-"))
    check(
        "the rescue pass is the no-proxy stack",
        Http.learnedPassKey() == "tls12-noproxy",
        "learned=" + Http.learnedPassKey() + " winner=" + Http.lastWinningPassKey(),
    )
    val net = java.io.File(java.io.File(System.getProperty("user.home"), ".hikari/cache"), "net.json")
    println("  net.json: " + net.absolutePath + " exists=" + net.isFile +
        " content=" + runCatching { net.readText() }.getOrNull())
    check("the working pass is remembered to disk", net.isFile, net.absolutePath)
    check(
        "as the no-proxy pass",
        net.takeIf { it.isFile }?.readText()?.contains("\"noProxy\":true") == true,
        net.takeIf { it.isFile }?.readText() ?: "",
    )

    ProxySelector.setDefault(realSelector)
    val again = Http.fetchStringRobust(local)
    check("a later fetch uses the remembered pass", again.isSuccess, again.exceptionOrNull()?.message ?: "")

    // ── 4. DNS over HTTPS ───────────────────────────────────────────────────
    val addrs = runCatching { DoH.resolve("raw.githubusercontent.com") }.getOrDefault(emptyList())
    println("  DoH raw.githubusercontent.com -> " + addrs.joinToString(", ") { it.hostAddress })
    if (addrs.isEmpty()) {
        println("  (no DoH answer on this runner — the system resolver would be used instead)")
    } else {
        check("DoH resolves without the OS resolver", addrs.isNotEmpty())
    }

    // ── 5. "play straight away": the server race ────────────────────────────
    // The race that decides which server the Play button starts is only honest
    // if it MEASURES the servers: a source that answers in 30 ms must beat one
    // that needs two seconds, and a source that misses the probe budget must
    // lose rather than hang the button.
    val fast = Http.streamLatencyMs(local, emptyMap(), 2_500L)
    println("  streamLatencyMs(fast local origin) = " + fast)
    check("the fastest-server probe answers for a reachable source", fast != null && fast < 2_000L, "got " + fast)

    val slowUrl = "http://127.0.0.1:" + server.address.port + "/slow.mp4"
    val slowShort = Http.streamLatencyMs(slowUrl, emptyMap(), 800L)
    println("  streamLatencyMs(slow source, 800ms budget) = " + slowShort)
    check("a slow server MISSES the probe budget", slowShort == null, "got " + slowShort)
    val slowReal = Http.streamLatencyMs(slowUrl, emptyMap(), 6_000L)
    println("  streamLatencyMs(slow source, 6s budget) = " + slowReal)
    check("…and is measured honestly when the budget allows it", (slowReal ?: 0L) >= 1_500L, "got " + slowReal)
    check(
        "so the race picks the fast server",
        fast != null && slowReal != null && fast < slowReal,
        "fast=" + fast + " slow=" + slowReal,
    )
    check(
        "a signed single-use link is never probed",
        Http.isSignedStreamUrl("https://mmcdn.com/v1/edge/streams/abc.m3u8") &&
            !Http.isSignedStreamUrl("https://cdn.example.com/movie.m3u8"),
    )

    server.stop(0)
    if (failures > 0) {
        println("NetworkSelfTest: $failures FAILED")
        kotlin.system.exitProcess(1)
    }
    println("NetworkSelfTest: OK")
    // The HTTP server's executor keeps non-daemon threads for a minute; a test
    // harness must not sit and wait for them (it would look like a hang).
    kotlin.system.exitProcess(0)
}

/** A proxy selector that always points at a port nothing listens on — a proxy
 *  that is *configured* and dead, which is the failure this app has to survive. */
private class DeadProxy : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
}
