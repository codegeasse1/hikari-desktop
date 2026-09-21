package android.view

import android.graphics.drawable.Drawable

/**
 * Desktop stand-in for `android.view.Window`. Plugin dialogs style "their"
 * window and then show themselves; there is no window here to style, and
 * nothing is ever displayed (see [View]).
 */
open class Window {

    @JvmField
    var attributes: WindowManager.LayoutParams = WindowManager.LayoutParams()

    open fun setBackgroundDrawable(d: Drawable?) {}
    open fun setDimAmount(amount: Float) {}
    open fun setLayout(width: Int, height: Int) {}
    open fun setGravity(gravity: Int) {}
    open fun setBackgroundDrawableResource(res: Int) {}
    open fun setBackgroundDrawableResource(res: Int, ignored: Int) {}
    open fun addFlags(flags: Int) {}
    open fun addFlags(flags: Int, ignored: Int) {}
    open fun clearFlags(flags: Int) {}
    open fun setSoftInputMode(mode: Int) {}
    open fun setWindowAnimations(res: Int) {}
    open fun setStatusBarColor(color: Int) {}
    open fun setNavigationBarColor(color: Int) {}
    open fun setFlags(flags: Int, mask: Int) {}
    open fun setContentView(view: View?) {}
    open fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {}
    open fun addContentView(view: View?, params: ViewGroup.LayoutParams?) {}
    open fun getDecorView(): View? = null
    open fun getAttributes(): WindowManager.LayoutParams = attributes
    open fun isFloating(): Boolean = false
    open fun getStatusBarColor(): Int = 0
}
