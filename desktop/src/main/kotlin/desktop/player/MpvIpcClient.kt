package desktop.player

import java.io.*
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Minimal mpv IPC client over TCP and Windows named pipe.
 * mpv is launched with --input-ipc-server=<path> where path is either
 * tcp://127.0.0.1:PORT or \\.\pipe\mpv-xxx on Windows.
 *
 * Protocol: JSON line per command, e.g. {"command":["get_property","time-pos"]}\n
 * Response: {"data":123.4,"error":"success","request_id":1}
 */
class MpvIpcClient {

    private var socket: Socket? = null
    private var pipeRaf: RandomAccessFile? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val requestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, (JSONObject) -> Unit>()

    @Volatile var connected = false
        private set

    fun connectTcp(host: String = "127.0.0.1", port: Int, timeoutMs: Int = 5000): Boolean {
        return try {
            val s = Socket()
            s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5000
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            connected = true
            startReader()
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    fun connectPipe(pipePath: String, timeoutMs: Int = 5000): Boolean {
        // Windows named pipe: \\.\pipe\mpv-xxx
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var raf: RandomAccessFile? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    raf = RandomAccessFile(pipePath, "rw")
                    break
                } catch (e: Exception) {
                    Thread.sleep(200)
                }
            }
            if (raf == null) return false
            pipeRaf = raf

            // Wrap RAF as InputStream/OutputStream
            val input = object : InputStream() {
                override fun read(): Int {
                    return try { raf.read() } catch (_: Exception) { -1 }
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    return try { raf.read(b, off, len) } catch (_: Exception) { -1 }
                }
            }
            val output = object : OutputStream() {
                override fun write(b: Int) {
                    try { raf.write(b) } catch (_: Exception) {}
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    try { raf.write(b, off, len) } catch (_: Exception) {}
                }
            }

            writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            connected = true
            startReader()
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    fun connectAuto(ipcPath: String): Boolean {
        return if (ipcPath.startsWith("tcp://")) {
            try {
                val uri = java.net.URI(ipcPath)
                val port = uri.port
                val host = uri.host ?: "127.0.0.1"
                connectTcp(host, port)
            } catch (_: Exception) {
                // Try parse as tcp://127.0.0.1:port manually
                val m = Regex("""tcp://([^:]+):(\d+)""").find(ipcPath)
                if (m != null) {
                    connectTcp(m.groupValues[1], m.groupValues[2].toInt())
                } else false
            }
        } else if (ipcPath.startsWith("\\\\.\\pipe\\") || ipcPath.contains("mpv")) {
            connectPipe(ipcPath)
        } else {
            // Try as unix socket path? Fallback to tcp if contains :
            if (ipcPath.contains(":")) {
                val parts = ipcPath.split(":")
                val port = parts.last().toIntOrNull()
                if (port != null) {
                    connectTcp("127.0.0.1", port)
                } else false
            } else {
                false
            }
        }
    }

    private fun startReader() {
        Thread({
            try {
                while (connected) {
                    val line = reader?.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val rid = obj.optLong("request_id", -1)
                    if (rid != -1L) {
                        pending.remove(rid)?.invoke(obj)
                    }
                }
            } catch (_: Exception) {
            } finally {
                connected = false
            }
        }, "mpv-ipc-reader").apply { isDaemon = true; start() }
    }

    fun close() {
        connected = false
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        runCatching { pipeRaf?.close() }
        reader = null
        writer = null
        socket = null
        pipeRaf = null
        pending.clear()
    }

    private fun sendJson(obj: JSONObject, awaitResponse: Boolean = true, timeoutMs: Long = 3000): JSONObject? {
        val w = writer ?: return null
        if (!connected) return null
        val id = requestId.getAndIncrement()
        obj.put("request_id", id)
        var result: JSONObject? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        if (awaitResponse) {
            pending[id] = { resp ->
                result = resp
                latch.countDown()
            }
        }
        return try {
            synchronized(w) {
                w.write(obj.toString())
                w.write("\n")
                w.flush()
            }
            if (awaitResponse) {
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                pending.remove(id)
                result
            } else {
                null
            }
        } catch (e: Exception) {
            pending.remove(id)
            null
        }
    }

    fun getProperty(name: String): Any? {
        val cmd = JSONObject().apply {
            put("command", org.json.JSONArray().apply {
                put("get_property")
                put(name)
            })
        }
        val resp = sendJson(cmd) ?: return null
        return if (resp.optString("error") == "success") resp.opt("data") else null
    }

    fun setProperty(name: String, value: Any?): Boolean {
        val cmd = JSONObject().apply {
            put("command", org.json.JSONArray().apply {
                put("set_property")
                put(name)
                put(value)
            })
        }
        val resp = sendJson(cmd) ?: return false
        return resp.optString("error") == "success"
    }

    fun command(vararg args: Any): Boolean {
        val cmd = JSONObject().apply {
            put("command", org.json.JSONArray(args.toList()))
        }
        val resp = sendJson(cmd) ?: return false
        return resp.optString("error") == "success"
    }

    fun seek(seconds: Double, absolute: Boolean = false): Boolean {
        return if (absolute) {
            command("seek", seconds, "absolute")
        } else {
            command("seek", seconds)
        }
    }

    fun quit(): Boolean {
        return command("quit")
    }
}
