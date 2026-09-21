package android.view.animation

import android.content.Context
import android.animation.TimeInterpolator

/**
 * Desktop stand-in for `android.view.animation.DecelerateInterpolator` (and the
 * handful of sibling interpolators plugin animations name). The maths is the
 * real thing; nothing consumes the value because nothing is ever drawn.
 */
open class DecelerateInterpolator(factor: Float = 1f) : TimeInterpolator {

    private val mFactor = if (factor == 0f) 1f else factor

    constructor(context: Context?, attrs: Any?) : this(1f)

    override fun getInterpolation(input: Float): Float {
        val f = input / mFactor
        return if (f <= 1f) 1f - (1f - f) * (1f - f) else 1f
    }
}

open class AccelerateInterpolator(private val mFactor: Float = 1f) : TimeInterpolator {
    constructor(context: Context?, attrs: Any?) : this(1f)

    override fun getInterpolation(input: Float): Float = input * input
}

open class LinearInterpolator : TimeInterpolator {
    constructor()
    constructor(context: Context?, attrs: Any?)
    override fun getInterpolation(input: Float): Float = input
}

open class AccelerateDecelerateInterpolator : TimeInterpolator {
    constructor()
    constructor(context: Context?, attrs: Any?)
    override fun getInterpolation(input: Float): Float =
        ((kotlin.math.cos((input + 1f) * kotlin.math.PI) / 2.0) + 0.5).toFloat()
}
