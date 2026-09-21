package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.Rect

/**
 * Desktop stand-ins for the drawables CloudStream plugin UIs hand to
 * `setBackground(...)`. They are inert objects (see [android.view.View]): the
 * point is that building a plugin's layout never fails on the desktop.
 */
open class Drawable(protected val tag: String = "drawable") {

    @JvmField var alpha: Int = 255

    @JvmField var bounds: Rect = Rect()

    open fun setAlpha(alpha: Int) { this.alpha = alpha }
    open fun getAlpha(): Int = alpha
    open fun setBounds(l: Int, t: Int, r: Int, b: Int) { bounds = Rect(l, t, r, b) }
    open fun setBounds(b: Rect?) { if (b != null) bounds = b }
    open fun getBounds(): Rect = bounds
    open fun setColorFilter(color: Int) {}
    open fun setColorFilter(color: Int, mode: Any?) {}
    open fun setColorFilter(filter: Any?) {}
    open fun clearColorFilter() {}
    open fun mutate(): Drawable = this
    open fun setTint(color: Int) {}
    open fun setTintList(list: android.content.res.ColorStateList?) {}
    open fun setTintMode(mode: Any?) {}
    open fun setVisible(visible: Boolean, restart: Boolean): Boolean = true
    open fun getIntrinsicWidth(): Int = -1
    open fun getIntrinsicHeight(): Int = -1
    open fun getOpacity(): Int = -1
    open fun invalidateSelf() {}
    open fun draw(canvas: Canvas?) {}

    open fun isStateful(): Boolean = false

    override fun toString(): String = tag
}

open class ColorDrawable(color: Int = 0) : Drawable("ColorDrawable") {

    private var fillColor: Int = color

    open fun setColor(color: Int) { fillColor = color }
    open fun getColor(): Int = fillColor
}

open class GradientDrawable(orientation: Orientation? = null, colors: IntArray? = null) :
    Drawable("GradientDrawable") {

    private var colors2: IntArray = colors ?: IntArray(0)
    private var orientation2: Orientation = orientation ?: Orientation.TOP_BOTTOM
    private var shape2: Int = RECTANGLE

    constructor(orientation: Orientation?, colors: IntArray?, ignored: Int) : this(orientation, colors)

    open fun setGradientType(t: Int) {}
    open fun setOrientation(o: Orientation?) { orientation2 = o ?: orientation2 }
    open fun setColors(colors: IntArray?) { colors2 = colors ?: IntArray(0) }
    open fun setColor(color: Int) {}
    open fun setCornerRadius(radius: Float) {}
    open fun setCornerRadii(radii: FloatArray?) {}
    open fun setShape(shape: Int) { shape2 = shape }
    open fun setStroke(width: Int, color: Int) {}
    open fun setStroke(width: Int, color: android.content.res.ColorStateList?) {}
    open fun setSize(width: Int, height: Int) {}
    open fun setGradientRadius(radius: Float) {}

    enum class Orientation {
        TOP_BOTTOM,
        TR_BL,
        RIGHT_LEFT,
        BR_TL,
        BOTTOM_TOP,
        BL_TR,
        LEFT_RIGHT,
        TL_BR,
    }

    companion object {
        const val RECTANGLE = 0
        const val OVAL = 1
        const val LINE = 2
        const val RING = 3
    }
}

open class LayerDrawable(layers: Array<Drawable>?) : Drawable("LayerDrawable") {
    open fun setLayerInset(index: Int, l: Int, t: Int, r: Int, b: Int) {}
}

open class RippleDrawable(color: android.content.res.ColorStateList?, content: Drawable?, mask: Drawable?) :
    Drawable("RippleDrawable")
