package desktop.player

import com.hikari.app.net.DoH
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tiny loopback HTTP/HTTPS-CONNECT proxy that mpv routes its network traffic
 * through (--http-proxy/--https-proxy). mpv's own HTTP stack uses the OS
 * resolver, so a DNS-filtered HLS/CDN domain fails silently in the player even
 * though the app and browser reach it. Routing every mpv connection through
 * this proxy means hostnames are resolved by the app's DoH-first resolver
 * ([DoH]) — the same "Secure DNS" the browser uses — and then streamed over a
 * plain TCP tunnel, headers untouched.
 *
 * CONNECT (https) requests become a pure byte relay; plain http requests are
 * forwarded verbatim so mpv's own headers (Referer/Cookie/UA) stay intact.
 */
object LocalProxy {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port = 0

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "hikari-net-proxy").apply { isDaemon = true }
    }

    /** Starts (once) the proxy on an ephemeral loopback port and returns it. */
    fun start(): Int {
        if (port > 0) return port
        synchronized(this) {
            if (port > 0) return port
            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            executor.submit { acceptLoop(ss) }
            return port
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val sock = try {
                ss.accept()
            } catch (e: Exception) {
                return
            }
            executor.submit { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 90_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val head = readHead(input) ?: return
            val lines = head.toString(Charsets.ISO_8859_1).split("\r\n")
            val req = lines.firstOrNull()?.split(" ") ?: return
            if (req.size < 3) return
            val method = req[0]
            val target = req[1]

            if (method == "CONNECT") {
                val host = connectHost(target)
                val portNum = connectPort(target)
                val addr = resolve(host) ?: return
                val upstream = Socket()
                val connected = runCatching { upstream.connect(InetSocketAddress(addr, portNum), 20_000) }.isSuccess
                if (!connected) {
                    runCatching {
                        output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        output.flush()
                    }
                    return
                }
                upstream.tcpNoDelay = true
                upstream.soTimeout = 90_000
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                output.flush()
                relay(input, upstream.getOutputStream(), upstream.getInputStream(), output)
                runCatching { upstream.close() }
            } else {
                val host = plainHost(lines) ?: return
                val portNum = plainPort(host)
                val hostOnly = if (host.startsWith("[")) host.substring(1, host.indexOf(']')) else host.substringBeforeLast(":", host)
                val addr = resolve(hostOnly) ?: return
                val upstream = Socket()
                val connected = runCatching { upstream.connect(InetSocketAddress(addr, portNum), 20_000) }.isSuccess
                if (!connected) return
                upstream.tcpNoDelay = true
                upstream.soTimeout = 90_000
                upstream.getOutputStream().write(head)
                upstream.getOutputStream().flush()
                relay(input, upstream.getOutputStream(), upstream.getInputStream(), output)
                runCatching { upstream.close() }
            }
        } catch (e: Exception) {
            // one bad connection must never take the proxy down
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Reads the request head up to and including \r\n\r\n. */
    private fun readHead(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        val b = ByteArray(1)
        var matched = 0
        val pattern = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val n = runCatching { input.read(b) }.getOrDefault(-1)
            if (n < 0) return null
            buf.write(b, 0, n)
            matched = if (b[0] == pattern[matched].toByte()) matched + 1 else if (b[0] == 13.toByte()) 1 else 0
            if (matched == 4) return buf.toByteArray()
        }
    }

    private fun connectHost(target: String): String =
        if (target.startsWith("[")) target.substring(1, target.indexOf(']'))
        else target.substringBeforeLast(":", target)

    private fun connectPort(target: String): Int =
        if (target.startsWith("[")) target.substringAfter(']').removePrefix(":").toIntOrNull() ?: 443
        else target.substringAfterLast(":", "443").toIntOrNull() ?: 443

    /** Origin host:port from the Host header (authority form) for plain http. */
    private fun plainHost(lines: List<String>): String? {
        val host = lines.firstNotNullOfOrNull { l ->
            val i = l.indexOf(':')
            if (i > 0 && l.substring(0, i).trim().equals("Host", ignoreCase = true)) l.substring(i + 1).trim() else null
        }
        return host?.takeIf { it.isNotBlank() }
    }

    private fun plainPort(host: String): Int = when {
        host.startsWith("[") -> host.substringAfter(']').removePrefix(":").toIntOrNull() ?: 80
        else -> host.substringAfterLast(":", "80").toIntOrNull() ?: 80
    }

    private fun resolve(host: String): InetAddress? {
        val doh = runCatching { DoH.resolve(host) }.getOrDefault(emptyList())
        if (doh.isNotEmpty()) return doh[0]
        return runCatching { InetAddress.getAllByName(host) }.getOrNull()?.firstOrNull()
    }

    /** Bidirectional relay: client<->upstream until either side closes. */
    private fun relay(sockIn: InputStream, upOut: OutputStream, upIn: InputStream, sockOut: OutputStream) {
        val a = Thread({
            try {
                copy(sockIn, upOut)
            } catch (e: Exception) {
            } finally {
                runCatching { upOut.close() }
            }
        }, "hikari-proxy-a").apply { isDaemon = true }
        val b = Thread({
            try {
                copy(upIn, sockOut)
            } catch (e: Exception) {
            } finally {
                runCatching { sockOut.close() }
            }
        }, "hikari-proxy-b").apply { isDaemon = true }
        a.start()
        b.start()
        try {
            a.join()
            b.join()
        } catch (e: InterruptedException) {
            // give up waiting; the daemon threads close everything themselves
        }
    }

    private fun copy(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n < 0) return
            dst.write(buf, 0, n)
            dst.flush()
        }
    }
}
