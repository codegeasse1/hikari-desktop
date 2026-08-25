package desktop.player

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Minimal mpv IPC client over TCP (and fallback to Unix socket / named pipe).
 * mpv is launched with --input-ipc-server=tcp://127.0.0.1:PORT and this client
 * connects to it to control playback like CloudStream/Stremio players.
 *
 * Protocol: JSON line per command, e.g. {"command":["get_property","time-pos"]}\n
 * Response: {"data":123.4,"error":"success","request_id":1}
 */
class MpvIpcClient {

    private var socket: Socket? = null
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
            // Start reader thread for async events (we ignore events for now, just drain)
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
                        // else it's an event (property change) - ignore for now or could handle
                    }
                } catch (_: Exception) {
                } finally {
                    connected = false
                }
            }, "mpv-ipc-reader").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    fun close() {
        connected = false
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
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
