package android.view

import android.animation.TimeInterpolator

/**
 * Desktop stand-in for `android.view.ViewPropertyAnimator`: every chained call
 * is accepted and returns the animator, and `start()` finishes immediately.
 */
open class ViewPropertyAnimator(private val view: View? = null) {

    open fun alpha(value: Float): ViewPropertyAnimator = this
    open fun scaleX(value: Float): ViewPropertyAnimator = this
    open fun scaleY(value: Float): ViewPropertyAnimator = this
    open fun translationX(value: Float): ViewPropertyAnimator = this
    open fun translationY(value: Float): ViewPropertyAnimator = this
    open fun rotation(value: Float): ViewPropertyAnimator = this
    open fun rotationX(value: Float): ViewPropertyAnimator = this
    open fun rotationY(value: Float): ViewPropertyAnimator = this
    open fun x(value: Float): ViewPropertyAnimator = this
    open fun y(value: Float): ViewPropertyAnimator = this
    open fun z(value: Float): ViewPropertyAnimator = this
    open fun setDuration(duration: Long): ViewPropertyAnimator = this
    open fun setStartDelay(delay: Long): ViewPropertyAnimator = this
    open fun setInterpolator(i: TimeInterpolator?): ViewPropertyAnimator = this
    open fun setListener(l: Any?): ViewPropertyAnimator = this
    open fun setUpdateListener(l: Any?): ViewPropertyAnimator = this
    open fun withStartAction(action: Runnable?): ViewPropertyAnimator = this
    open fun withEndAction(action: Runnable?): ViewPropertyAnimator {
        runCatching { action?.run() }
        return this
    }

    open fun start() {}

    open fun cancel() {}
}
