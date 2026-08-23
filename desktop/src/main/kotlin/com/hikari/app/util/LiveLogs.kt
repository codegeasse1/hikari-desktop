package com.hikari.app.util

import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Bounded in-memory log catchers. Every Hikari subsystem (catalog loads, the
 * WebView WAF fallback, stream resolution, the CloudStream runtime) writes here so
 * the UI can show the REAL reason something failed ("returned no catalog rows",
 * "no sources") instead of a guess. A tee on System.out/System.err additionally
 * captures anything the loaded plugins themselves print, so plugin-level errors are
 * visible too — this is the "real logs" the user asked for.
 *
 * All access is thread-safe; the buffer holds the most recent [CAP] entries.
 */
object LiveLogs {

    private const val CAP = 800

    data class Entry(val time: Long, val tag: String, val level: String, val text: String)

    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val listeners = ConcurrentLinkedDeque<(Entry) -> Unit>()
    @Volatile private var installedOut: PrintStream? = null

    fun log(tag: String, msg: String) = push("INFO", tag, msg)
    fun warn(tag: String, msg: String) = push("WARN", tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) {
        val text = if (t == null) msg
        else buildString {
            append(msg)
            append("\n")
            append("${t.javaClass.simpleName}: ${t.message}")
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth < 4) {
                if (c !== t) append("\nCaused by: ${c.javaClass.simpleName}: ${c.message}")
                c.stackTrace.take(3).forEach { append("\n    at $it") }
                c = c.cause
                depth++
            }
        }
        push("ERROR", tag, text)
    }

    private fun push(level: String, tag: String, text: String) {
        if (text.isBlank()) return
        val e = Entry(System.currentTimeMillis(), tag, level, text)
        buffer.addLast(e)
        while (buffer.size > CAP) buffer.pollFirst()
        for (l in listeners) runCatching { l(e) }
    }

    /** Redirect System.out/System.err into the buffer (in addition to the console). */
    @Synchronized
    fun install() {
        if (installedOut != null) return
        val origOut = System.out
        val origErr = System.err
        val out = object : PrintStream(origOut) {
            override fun println(x: String) { origOut.println(x); push("INFO", "stdout", x) }
            override fun print(x: String) { origOut.print(x); push("INFO", "stdout", x) }
        }
        val err = object : java.io.PrintStream(origErr) {
            override fun println(x: String) { origErr.println(x); push("ERROR", "stderr", x) }
            override fun print(x: String) { origErr.print(x); push("ERROR", "stderr", x) }
        }
        System.setOut(out)
        System.setErr(err)
        installedOut = out
        log("hikari", "Log catcher installed")
    }

    fun recentText(limit: Int = CAP): String =
        buffer.toList().asReversed().take(limit).asReversed()
            .joinToString("\n") { e ->
                val ts = java.time.Instant.ofEpochMilli(e.time)
                val time = ts.toString().substring(11, 19)
                "[$time][${e.tag}][${e.level}] ${e.text}"
            }

    fun reset() { buffer.clear() }
}
