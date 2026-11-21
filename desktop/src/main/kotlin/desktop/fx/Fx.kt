package desktop.fx

import javafx.application.Platform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge to the JavaFX Application Thread. All JavaFX node access must happen
 * on this thread; everything else (okhttp, extensions, the WebView resolver)
 * hops onto it through [runBlock].
 */
object Fx {

    @Volatile
    private var started = false

    @Volatile
    private var fxThread: Thread? = null

    /** Must be called once, on the FX Application Thread (from Main.start). */
    fun onStart() {
        started = true
        fxThread = Thread.currentThread()
    }

    fun isFxThread(): Boolean = Platform.isFxApplicationThread()

    /** Post to the FX thread (no-op if already on it). */
    fun run(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block()
        else Platform.runLater { block() }
    }

    /**
     * Run on the FX thread and block until it completes. Must never be called
     * from the FX thread itself.
     */
    fun <T> runBlock(block: () -> T, timeoutMs: Long = 5_000L): T {
        if (Platform.isFxApplicationThread()) return block()
        val latch = CountDownLatch(1)
        val res = AtomicReference<Result<T>>()
        Platform.runLater {
            res.set(runCatching { block() })
            latch.countDown()
        }
        // Never hang forever: if the FX thread is wedged (long layout, modal),
        // fail fast instead of freezing the caller thread + IO pool with it.
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException("Fx.runBlock timed out after ${timeoutMs}ms")
        }
        return res.get().getOrThrow()
    }

    fun requireFx() {
        check(Platform.isFxApplicationThread()) { "Must run on the FX thread" }
    }
}
