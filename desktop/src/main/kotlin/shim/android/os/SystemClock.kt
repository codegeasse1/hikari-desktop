package android.os

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.currentTimeMillis()

    @JvmStatic
    fun uptimeMillis(): Long = System.currentTimeMillis()

    @JvmStatic
    fun currentThreadTimeMillis(): Long = System.nanoTime() / 1_000_000L

    @JvmStatic
    fun elapsedRealtimeNanos(): Long = System.nanoTime()
}
