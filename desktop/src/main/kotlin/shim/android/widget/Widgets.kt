package android.widget

import android.content.Context
import android.view.ViewGroup

/**
 * Desktop stand-ins for the handful of `android.widget` classes CloudStream
 * plugin UIs are built from. They are inert (see [android.view.View]): the point
 * is that a plugin whose `load()` builds a settings/donation layout — or merely
 * references one — runs to completion on the desktop instead of dying with
 * `NoClassDefFoundError` before it registers anything.
 */
open class LinearLayout(context: Context?) : ViewGroup(context) {

    @JvmField var orientation: Int = HORIZONTAL

    open fun setOrientation(o: Int) { orientation = o }
    open fun getOrientation(): Int = orientation
    open fun setGravity(gravity: Int) {}
    open fun setWeightSum(weight: Float) {}
    open fun setBaselineAligned(b: Boolean) {}
    open fun setDividerDrawable(d: android.graphics.drawable.Drawable?) {}
    open fun setShowDividers(flags: Int) {}
    open fun setDividerPadding(padding: Int) {}

    open class LayoutParams : android.view.ViewGroup.MarginLayoutParams {
        @JvmField var weight: Float = 0f
        @JvmField var gravity: Int = -1

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, weight: Float) : super(width, height) {
            this.weight = weight
        }
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

open class FrameLayout(context: Context?) : ViewGroup(context) {

    open fun setForeground(d: android.graphics.drawable.Drawable?) {}
    open fun setForegroundGravity(gravity: Int) {}
    open fun setMeasureAllChildren(b: Boolean) {}

    open class LayoutParams : android.view.ViewGroup.MarginLayoutParams {
        @JvmField var gravity: Int = -1

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : super(width, height) {
            this.gravity = gravity
        }
    }
}

open class RelativeLayout(context: Context?) : ViewGroup(context) {

    open class LayoutParams : android.view.ViewGroup.MarginLayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
    }
}

open class ScrollView(context: Context?) : FrameLayout(context) {
    open fun setFillViewport(b: Boolean) {}
    open fun setSmoothScrollingEnabled(b: Boolean) {}
    open fun setOverScrollMode(mode: Int) {}
}

open class TextView(context: Context?) : android.view.View(context) {

    @JvmField var text: CharSequence? = null

    @JvmField var textSize: Float = 14f

    @JvmField var currentTextColor: Int = 0

    open fun setText(t: CharSequence?) { text = t }
    open fun setText(resId: Int) {}
    open fun setText(t: CharSequence?, type: Any?) {}
    open fun getText(): CharSequence? = text
    open fun setTextColor(color: Int) { currentTextColor = color }
    open fun setTextColor(color: Int, ignored: Int) {}
    open fun setTextColor(colors: android.content.res.ColorStateList?) {}
    open fun setTextSize(size: Float) { textSize = size }
    open fun setTextSize(unit: Int, size: Float) { textSize = size }
    open fun setTextAppearance(res: Int) {}
    open fun setTypeface(tf: android.graphics.Typeface?) {}
    open fun setTypeface(tf: android.graphics.Typeface?, style: Int) {}
    open fun setGravity(gravity: Int) {}
    open fun setLineSpacing(add: Float, mult: Float) {}
    open fun setLetterSpacing(spacing: Float) {}
    open fun setMaxLines(lines: Int) {}
    open fun setLines(lines: Int) {}
    open fun setSingleLine() {}
    open fun setSingleLine(b: Boolean) {}
    open fun setEllipsize(where: Any?) {}
    open fun setTextIsSelectable(b: Boolean) {}
    open fun setHorizontallyScrolling(b: Boolean) {}
    open fun setAllCaps(b: Boolean) {}
    open fun setHint(hint: CharSequence?) {}
    open fun setFontFeatureSettings(s: String?) {}
    open fun append(t: CharSequence?) {}
    open fun setTextAppearance(context: Context?, resId: Int) {}
}

open class EditText(context: Context?) : TextView(context) {
    open fun setSelection(index: Int) {}
    open fun setInputType(type: Int) {}
    open fun setHintTextColor(color: Int) {}
}

open class Button(context: Context?) : TextView(context)

open class ImageView(context: Context?) : android.view.View(context) {

    @JvmField var drawable: android.graphics.drawable.Drawable? = null

    open fun setImageDrawable(d: android.graphics.drawable.Drawable?) { drawable = d }
    open fun setImageBitmap(b: android.graphics.Bitmap?) {}
    open fun setImageResource(resId: Int) {}
    open fun setScaleType(t: Any?) {}
    open fun setColorFilter(color: Int) {}
    open fun setAdjustViewBounds(b: Boolean) {}
    open fun setAlpha(a: Int) {}

    companion object {
        const val CENTER_CROP = 6
        const val FIT_CENTER = 3
    }
}

open class ProgressBar(context: Context?) : android.view.View(context) {

    @JvmField var progress: Int = 0

    open fun setProgress(p: Int) { progress = p }
    open fun setMax(max: Int) {}
    open fun setIndeterminate(b: Boolean) {}
    open fun setProgressDrawable(d: android.graphics.drawable.Drawable?) {}
    open fun setProgressTintList(list: android.content.res.ColorStateList?) {}
}

open class Toast(private val context: Context?) {

    open fun show() {}
    open fun cancel() {}
    open fun setDuration(d: Int) {}
    open fun setView(v: android.view.View?) {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast(context)

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast = Toast(context)
    }
}
