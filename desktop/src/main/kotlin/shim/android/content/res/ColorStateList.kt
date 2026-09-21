package android.content.res

/**
 * Desktop stand-in for `android.content.res.ColorStateList` — plugin UIs tint
 * their (inert) views with one of these.
 */
open class ColorStateList(colors: IntArray? = null, states: Array<IntArray>? = null) {

    @JvmField
    var defaultColor: Int = colors?.firstOrNull() ?: 0

    open fun getColorForState(state: IntArray?, defaultValue: Int): Int = defaultColor

    open fun getDefaultColor(): Int = defaultColor

    open fun withAlpha(alpha: Int): ColorStateList = this

    open fun isStateful(): Boolean = false

    companion object {
        @JvmStatic
        fun valueOf(color: Int): ColorStateList = ColorStateList(intArrayOf(color))

        @JvmStatic
        fun valueOf(states: Array<IntArray>?, colors: IntArray?): ColorStateList = ColorStateList(colors, states)
    }
}
