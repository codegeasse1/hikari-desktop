package android.view

import android.content.Context

/**
 * Desktop stand-in for `android.view.ViewGroup` (see [View] for why these
 * exist). Children are accepted and dropped; layout is never performed.
 */
open class ViewGroup(context: Context?) : View(context) {

    open fun addView(child: View?) {}
    open fun addView(child: View?, params: LayoutParams?) {}
    open fun addView(child: View?, index: Int, params: LayoutParams?) {}
    open fun removeView(child: View?) {}
    open fun removeViewAt(index: Int) {}
    open fun removeAllViews() {}
    open fun removeAllViewsInLayout() {}
    open fun bringChildToFront(child: View?) {}
    open fun getChildCount(): Int = 0
    open fun getChildAt(index: Int): View? = null
    open fun indexOfChild(child: View?): Int = -1
    open fun setClipChildren(clip: Boolean) {}
    open fun setDescendantFocusability(mode: Int) {}
    open fun setLayoutTransition(t: Any?) {}
    open fun setOnHierarchyChangeListener(l: Any?) {}
    open fun setAddStatesFromChildren(b: Boolean) {}
    open fun requestDisallowInterceptTouchEvent(b: Boolean) {}

    open class LayoutParams(width: Int, height: Int) {

        @JvmField
        var width: Int = width

        @JvmField
        var height: Int = height

        open fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {}

        companion object {
            const val MATCH_PARENT = -1
            const val FILL_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    open class MarginLayoutParams(width: Int, height: Int) : LayoutParams(width, height) {

        @JvmField
        var leftMargin: Int = 0

        @JvmField
        var topMargin: Int = 0

        @JvmField
        var rightMargin: Int = 0

        @JvmField
        var bottomMargin: Int = 0

        override fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
        }
    }
}
