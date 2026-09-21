package android.animation

/**
 * Desktop stand-in for `android.animation.TimeInterpolator`.
 */
interface TimeInterpolator {
    fun getInterpolation(input: Float): Float
}

/**
 * Desktop stand-in for `android.animation.ValueAnimator`.
 *
 * Plugin animations are driven from `load()`/UI code; a null here would NPE the
 * caller, so the factory returns a real (inert) animator and every chained
 * setter returns it. The listeners are invoked once, immediately, so plugin code
 * that does its real work in `onAnimationUpdate` (a common "count up" idiom)
 * reaches its end state instead of waiting for frames that never come.
 */
open class ValueAnimator {

    private val updateListeners = ArrayList<AnimatorUpdateListener>()
    private val endListeners = ArrayList<AnimatorListener>()

    @JvmField
    var duration: Long = 300L

    @JvmField
    var startDelay: Long = 0L

    @JvmField
    var repeatCount: Int = 0

    @JvmField
    var repeatMode: Int = RESTART

    private var running = false

    open fun setDuration(duration: Long): ValueAnimator {
        this.duration = duration
        return this
    }

    open fun getDuration(): Long = duration

    open fun setStartDelay(delay: Long) {
        startDelay = delay
    }

    open fun setInterpolator(i: TimeInterpolator?) {}

    open fun setRepeatCount(count: Int) {
        repeatCount = count
    }

    open fun setRepeatMode(mode: Int) {}

    open fun addUpdateListener(l: AnimatorUpdateListener?) {
        if (l != null) updateListeners.add(l)
    }

    open fun removeAllUpdateListeners() {
        updateListeners.clear()
    }

    open fun removeUpdateListener(l: AnimatorUpdateListener?) {
        updateListeners.remove(l)
    }

    open fun addListener(l: AnimatorListener?) {
        if (l != null) endListeners.add(l)
    }

    open fun removeAllListeners() {
        endListeners.clear()
    }

    open fun setEvaluator(e: Any?) {}

    open fun start() {
        running = true
        for (l in updateListeners.toList()) runCatching { l.onAnimationUpdate(this) }
        for (l in endListeners.toList()) runCatching { l.onAnimationEnd(this) }
        running = false
    }

    open fun cancel() {
        running = false
    }

    open fun end() {}

    open fun reverse() {}

    open fun resume() {}

    open fun pause() {}

    open fun isRunning(): Boolean = running

    open fun isStarted(): Boolean = running

    /** Android returns a boxed Float here and plugin code casts it, so the value
     *  must actually be one. */
    open fun getAnimatedValue(): Any = 1f

    open fun getAnimatedValue(propertyName: String?): Any = 1f

    open fun getAnimatedFraction(): Float = 1f

    interface AnimatorUpdateListener {
        fun onAnimationUpdate(animation: ValueAnimator?)
    }

    interface AnimatorListener {
        fun onAnimationStart(animation: ValueAnimator?)
        fun onAnimationEnd(animation: ValueAnimator?)
        fun onAnimationCancel(animation: ValueAnimator?)
        fun onAnimationRepeat(animation: ValueAnimator?)
    }

    companion object {
        const val RESTART = 1
        const val REVERSE = 2
        const val INFINITE = -1

        @JvmStatic
        fun ofFloat(vararg values: Float): ValueAnimator = ValueAnimator()

        @JvmStatic
        fun ofInt(vararg values: Int): ValueAnimator = ValueAnimator()

        @JvmStatic
        fun ofArgb(vararg values: Int): ValueAnimator = ValueAnimator()

        @JvmStatic
        fun ofObject(evaluator: Any?, vararg values: Any?): ValueAnimator = ValueAnimator()
    }
}
