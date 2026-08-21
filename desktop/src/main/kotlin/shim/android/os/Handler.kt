package android.os

open class Handler(private val targetLooper: Looper) {

    constructor() : this(Looper.getMainLooper())

    val looper: Looper get() = targetLooper

    fun post(r: Runnable): Boolean {
        targetLooper.post(r)
        return true
    }

    fun post(r: () -> Unit): Boolean = post(Runnable { r() })

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        targetLooper.postDelayed(r, delayMillis)
        return true
    }

    fun postDelayed(r: () -> Unit, delayMillis: Long): Boolean =
        postDelayed(Runnable { r() }, delayMillis)
}
