package android.view

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Desktop stand-in for `android.view.View`.
 *
 * CloudStream plugins bundle their own settings/donation UI, and several of them
 * run part of it from `Plugin.load()` — so a plugin whose dex references a view
 * class fails to load at all on the desktop (`NoClassDefFoundError`, from the
 * JVM's type-inference verifier, before a single line of the plugin runs). The
 * Android host has these classes; the desktop doesn't, so it provides inert
 * versions: every setter is accepted and forgotten, and anything that returns an
 * object returns a real instance of the stand-in instead of null (a null from
 * `ValueAnimator.ofFloat` would NPE the caller and land right back on the
 * "couldn't load" path).
 *
 * Nothing here is ever displayed — the desktop UI is JavaFX, and a plugin's
 * Android view tree has no window to attach to. The only job is to let the
 * plugin's own code run to completion.
 */
open class View(val ctx: Context?) {

    @JvmField
    var layoutParams: ViewGroup.LayoutParams? = null

    @JvmField
    var id: Int = NO_ID

    @JvmField
    var visibility: Int = VISIBLE

    @JvmField
    var alpha: Float = 1f

    @JvmField
    var tag: Any? = null

    @JvmField
    var elevation: Float = 0f

    @JvmField
    var isClickable: Boolean = false

    @JvmField
    var isEnabled: Boolean = true

    private var onClick: OnClickListener? = null
    private var onLongClick: OnLongClickListener? = null

    fun getContext(): Context? = ctx

    open fun setBackground(d: Drawable?) {}
    open fun setBackgroundColor(color: Int) {}
    open fun setBackgroundResource(res: Int) {}
    open fun setLayoutParams(params: ViewGroup.LayoutParams?) { layoutParams = params }
    fun getLayoutParams(): ViewGroup.LayoutParams? = layoutParams
    open fun setOnClickListener(l: OnClickListener?) { onClick = l }
    open fun setOnLongClickListener(l: OnLongClickListener?) { onLongClick = l }
    open fun performClick(): Boolean {
        onClick?.onClick(this)
        return true
    }

    open fun setVisibility(v: Int) { visibility = v }
    fun getVisibility(): Int = visibility
    open fun setAlpha(a: Float) { alpha = a }
    open fun setScaleX(f: Float) {}
    open fun setScaleY(f: Float) {}
    open fun setTranslationX(f: Float) {}
    open fun setTranslationY(f: Float) {}
    open fun setRotation(f: Float) {}
    open fun setClickable(b: Boolean) { isClickable = b }
    open fun setFocusable(b: Boolean) {}
    open fun setEnabled(b: Boolean) { isEnabled = b }
    open fun setSelected(b: Boolean) {}
    open fun setPadding(l: Int, t: Int, r: Int, b: Int) {}
    open fun setPaddingRelative(l: Int, t: Int, r: Int, b: Int) {}
    open fun setMinimumWidth(w: Int) {}
    open fun setMinimumHeight(h: Int) {}
    open fun setContentDescription(text: CharSequence?) {}
    open fun setTag(t: Any?) { tag = t }
    fun getTag(): Any? = tag
    open fun setElevation(f: Float) { elevation = f }
    open fun setClipToOutline(b: Boolean) {}
    open fun setClipToPadding(b: Boolean) {}
    open fun setLayerType(layerType: Int, paint: Any?) {}
    open fun setOutlineProvider(provider: Any?) {}
    open fun setImportantForAccessibility(mode: Int) {}
    open fun setSystemUiVisibility(flags: Int) {}

    open fun bringToFront() {}
    open fun invalidate() {}
    open fun requestLayout() {}
    open fun requestFocus() {}
    open fun forceLayout() {}
    open fun post(action: Runnable): Boolean {
        runCatching { action.run() }
        return true
    }
    open fun postDelayed(action: Runnable, delayMillis: Long): Boolean = post(action)
    open fun removeCallbacks(action: Runnable) {}

    /** Animators are real objects so chained calls (`animate().alpha(0f).start()`)
     *  keep working; they simply finish instantly. */
    open fun animate(): ViewPropertyAnimator = ViewPropertyAnimator(this)

    fun getWidth(): Int = 0
    fun getHeight(): Int = 0
    fun getMeasuredWidth(): Int = 0
    fun getMeasuredHeight(): Int = 0
    fun getParent(): ViewGroup? = null
    fun getRootView(): View? = null

    interface OnClickListener {
        fun onClick(v: View?)
    }

    interface OnLongClickListener {
        fun onLongClick(v: View?): Boolean
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val NO_ID = -1
        const val TEXT_ALIGNMENT_TEXT_START = 2
        const val TEXT_ALIGNMENT_VIEW_START = 5
    }
}
