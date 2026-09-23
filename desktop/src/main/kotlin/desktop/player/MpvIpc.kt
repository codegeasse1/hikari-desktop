package desktop.player

import org.json.JSONObject
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A client for mpv's JSON IPC.
 *
 * mpv is the app's video engine (it plays the HLS/MPEG-TS/CDN streams JavaFX's
 * own media stack refuses). Launching it with `--input-ipc-server=<pipe>` turns
 * that one-way hand-off into a conversation: the app can ask where playback is
 * (`time-pos`, `duration`), drive it (`seek`, `set pause`), read what the file
 * actually is (`track-list`, `video-format`), and — the reason this exists — be
 * TOLD when playback ends, so the next episode can start by itself and the
 * position can be saved for a resume.
 *
 * Transport is a Windows named pipe (`\\.\pipe\<name>`) or, elsewhere, a Unix
 * domain socket. Both are just byte streams carrying one JSON object per line;
 * mpv answers a request with `request_id`, and pushes `event`/`property-change`
 * messages unsolicited, so one reader thread fans everything out.
 */
class MpvIpc(private val target: String, private val windowsPipe: Boolean) {

    /** Property changes and events, delivered on the reader thread. */
    @Volatile
    var onProperty: ((name: String, value: Any?) -> Unit)? = null

    @Volatile
    var onEvent: ((event: String, data: JSONObject) -> Unit)? = null

    private var pipe: RandomAccessFile? = null
    private var channel: SocketChannel? = null

    /**
     * Commands waiting to go out, drained by one writer thread.
     *
     * A named-pipe write BLOCKS when the other end stops draining it — observed
     * on the CI runner, where a single `get_property` call sat inside
     * WriteFile for eight minutes and the whole test hung with it. The callers
     * here are the player's controls (and [PlayerWindow] posts from the JavaFX
     * thread), so a blocked write is a frozen UI. Queueing instead of writing
     * inline means a wedged pipe costs the commands — which is all "the player
     * has stopped listening" can mean — and never the app.
     */
    private val outbox = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null

    /** When the writer entered a write that has not come back yet. */
    @Volatile
    private var writingSince = 0L

    /** True once the current stuck write has been noted in the log (see [send]). */
    @Volatile
    private var stuckNoted = false

    private val nextRequestId = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, ((JSONObject) -> Unit)?>()

    @Volatile
    private var closed = false

    private var reader: Thread? = null

    /** A write that has not come back in this long means the pipe is not being
     *  drained; the connection is treated as dead rather than queueing forever. */
    private val WRITE_STUCK_MS = 10_000L

    /** Most commands that may be waiting to go out at once. */
    private val MAX_QUEUED = 64

    /**
     * The last few commands and connection events, timestamped — what the player
     * report carries.
     *
     * "Did the click even reach mpv?" is the first question about a control that
     * does nothing, and it cannot be answered from a screenshot: a fire-and-forget
     * command that was dropped looks exactly like a button whose handler never
     * ran. This is the record that tells them apart.
     */
    private val recent = java.util.Collections.synchronizedList(ArrayList<String>())

    /** [recent] as report lines, oldest first. */
    fun recentLog(): List<String> = synchronized(recent) { ArrayList(recent) }

    /** False once the transport has been found unusable (or closed) — a caller
     *  polling for a reply can stop instead of waiting out its deadline. */
    fun isAlive(): Boolean = !closed

    private fun note(line: String) {
        val stamp = runCatching { java.time.LocalTime.now().toString().take(8) }.getOrDefault("--:--:--")
        synchronized(recent) {
            recent.add("$stamp $line")
            while (recent.size > 60) recent.removeAt(0)
        }
    }

    /** Connects, retrying while mpv comes up (it creates the pipe a moment
     *  after it is launched). Returns false if it never appeared. */
    fun connect(timeoutMs: Long = 6_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (open()) {
                note("connected ($target)")
                startReader()
                return true
            }
            lastError = lastFailure
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        lastFailure = lastError
        return false
    }

    private var lastFailure: Throwable? = null

    private fun open(): Boolean = try {
        if (windowsPipe) {
            val f = RandomAccessFile("\\\\.\\pipe\\$target", "rw")
            pipe = f
            true
        } else {
            val c = SocketChannel.open(StandardProtocolFamily.UNIX)
            c.connect(UnixDomainSocketAddress.of(Path.of(target)))
            channel = c
            true
        }
    } catch (e: Throwable) {
        lastFailure = e
        false
    }

    private fun startReader() {
        startWriter()
        val t = Thread({ readLoop() }, "hikari-mpv-ipc")
        t.isDaemon = true
        reader = t
        t.start()
    }

    private fun readLoop() {
        val line = StringBuilder()
        val buffer = ByteBuffer.allocate(8192)
        val bytes = ByteArray(8192)
        try {
            while (!closed) {
                val read = if (channel != null) {
                    buffer.clear()
                    val n = channel!!.read(buffer)
                    if (n <= 0) -1 else {
                        buffer.flip()
                        buffer.get(bytes, 0, n)
                        n
                    }
                } else {
                    pipe!!.read(bytes)
                }
                if (read < 0) break
                for (i in 0 until read) {
                    val ch = bytes[i].toInt().toChar()
                    if (ch == '\n') {
                        val text = line.toString().trim()
                        line.setLength(0)
                        if (text.isNotEmpty()) dispatch(text)
                    } else if (ch != '\r') {
                        line.append(ch)
                    }
                }
            }
        } catch (e: Throwable) {
            if (!closed) onEvent?.invoke("ipc-closed", JSONObject().put("error", e.message ?: "closed"))
        }
    }

    private fun dispatch(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.has("request_id")) {
            waiting.remove(json.optLong("request_id"))?.invoke(json)
            return
        }
        val event = json.optString("event")
        if (event.isBlank()) return
        if (event == "property-change") {
            val name = json.optString("name")
            val value = json.opt("data")?.takeIf { it !== JSONObject.NULL }
            if (name != "time-pos") note("< " + name + " = " + value)
            onProperty?.invoke(name, value)
        }
        onEvent?.invoke(event, json)
    }

    /** Sends one command and waits for mpv's reply. Null when it did not answer
     *  in time — a dropped reply must never wedge the caller. */
    fun command(timeoutMs: Long = 4_000L, vararg args: Any): JSONObject? {
        if (closed) return null
        val id = nextRequestId.getAndIncrement()
        val verb = args.firstOrNull()?.toString().orEmpty()
        // Reads are polled and would drown the log; the commands the user
        // triggered (pause, seek, track picks) are what it is for.
        val worthLogging = verb.isNotEmpty() && verb != "get_property" && verb != "observe_property"
        if (worthLogging) note("-> " + args.joinToString(" "))
        val payload = JSONObject()
        payload.put("command", org.json.JSONArray(args.toList()))
        payload.put("request_id", id)
        val latch = CountDownLatch(1)
        var response: JSONObject? = null
        waiting[id] = { r ->
            response = r
            latch.countDown()
        }
        return try {
            if (!send(payload)) {
                note("!! " + verb + " was not sent: the connection is closed")
                return null
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (response == null) {
                waiting.remove(id)
                if (worthLogging) note("!! no reply to " + verb + " in " + timeoutMs + " ms")
            }
            response
        } catch (e: Throwable) {
            waiting.remove(id)
            null
        }
    }

    fun setProperty(name: String, value: Any) {
        command(2_000L, "set_property", name, value)
    }

    /** Fire-and-forget command (seek during a scrub must not queue up replies). */
    fun post(vararg args: Any) {
        note("-> " + args.joinToString(" "))
        val payload = JSONObject()
        payload.put("command", org.json.JSONArray(args.toList()))
        runCatching { send(payload) }
    }

    fun observe(name: String, id: Long) {
        command(2_000L, "observe_property", id, name)
    }

    fun getProperty(name: String): Any? =
        command(2_500L, "get_property", name)?.opt("data")?.takeIf { it !== JSONObject.NULL }

    fun getPropertyString(name: String): String? = getProperty(name)?.toString()?.takeIf { it != "null" }

    /** Queues one command. Returns false when the connection is (or has just
     *  been found to be) unusable. */
    private fun send(payload: JSONObject): Boolean {
        if (closed) return false
        val since = writingSince
        if (since != 0L && System.currentTimeMillis() - since > WRITE_STUCK_MS) {
            // A write that has not come back in this long means the pipe is not
            // being drained: mpv stops servicing its command pipe while it is
            // busy building a video output (measured on the CI runner — one
            // write sat inside WriteFile for eight minutes, and the probe in
            // FloatingBarsSelfTest shows the same pipe answering in 200 ms once
            // mpv is idle again).
            //
            // A SLOW pipe is not a DEAD one, and the old answer here — declaring
            // the connection dead — cost every control for the rest of the
            // stream, which is exactly the reported "pause worked once, and then
            // pressing it again did nothing". So the pipe is left alone: the
            // answer and property observations keep flowing, this command is
            // queued, and the queue (bounded, newest kept) drains the moment the
            // pipe starts moving again — in order, so a `cycle` followed by the
            // `set_property` that fixes its result still ends on the right state.
            if (!stuckNoted) {
                stuckNoted = true
                note("!! the command pipe is not draining — commands are queued, not lost")
            }
        } else if (since == 0L) {
            stuckNoted = false
        }
        // Bounded: a backed-up queue means the player is not reading, and the
        // newest command (a pause, a seek) is the one that matters.
        while (outbox.size >= MAX_QUEUED) outbox.poll()
        return outbox.offer((payload.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    /** The single thread that actually writes to the pipe — see [outbox]. */
    private fun startWriter() {
        if (writer?.isAlive == true) return
        val t = Thread({
            var alive = true
            while (!closed && alive) {
                val bytes = try {
                    outbox.poll(500L, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                writingSince = System.currentTimeMillis()
                try {
                    val ch = channel
                    if (ch != null) {
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) ch.write(buffer)
                    } else {
                        pipe!!.write(bytes)
                    }
                } catch (e: Throwable) {
                    writingSince = 0L
                    lastFailure = e
                    alive = false
                    fail("the player's command pipe closed: " + (e.message ?: e.javaClass.simpleName))
                }
                writingSince = 0L
            }
        }, "hikari-mpv-ipc-w")
        t.isDaemon = true
        writer = t
        t.start()
    }

    /** Marks the connection dead and tells the owner, once. */
    private fun fail(message: String) {
        if (closed) return
        closed = true
        note("!! connection dead: " + message)
        outbox.clear()
        runCatching { onEvent?.invoke("ipc-closed", JSONObject().put("error", message)) }
        runCatching { reader?.interrupt() }
        shutdownHandles()
    }

    /**
     * Closes the transport from a daemon thread.
     *
     * Closing a stream whose WRITE is still blocked inside the kernel blocks the
     * caller too (`FileDescriptor.closeAll` waits for the in-flight I/O), and
     * this is reached from the player's teardown — so a wedged pipe must cost
     * one leaked daemon thread, never a frozen app. Observed on CI: `close()`
     * sat in here for eight minutes with the thread dump pointing at
     * `RandomAccessFile`.
     */
    private fun shutdownHandles() {
        val channel0 = channel
        val pipe0 = pipe
        Thread({
            runCatching { channel0?.close() }
            runCatching { pipe0?.close() }
        }, "hikari-mpv-ipc-close").apply { isDaemon = true; start() }
    }

    fun close() {
        closed = true
        outbox.clear()
        runCatching { writer?.interrupt() }
        runCatching { reader?.interrupt() }
        shutdownHandles()
        waiting.clear()
    }
}
