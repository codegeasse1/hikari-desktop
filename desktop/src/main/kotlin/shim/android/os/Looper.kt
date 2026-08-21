package android.os

import desktop.fx.Fx
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Looper private constructor() {

    private val delayExecutor = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "hikari-looper-delay").apply { isDaemon = true }
    }

    fun post(r: Runnable) {
        Fx.run { r.run() }
    }

    fun postDelayed(r: Runnable, delayMillis: Long) {
        delayExecutor.schedule({ Fx.run { r.run() } }, delayMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val mainLooper = Looper()
        @JvmStatic
        fun getMainLooper(): Looper = mainLooper
        @JvmStatic
        fun myLooper(): Looper? = if (Fx.isFxThread()) mainLooper else null
    }
}
