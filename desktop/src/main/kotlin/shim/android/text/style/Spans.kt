package android.text.style

/**
 * Desktop stand-in for `android.text.style.ForegroundColorSpan`. Plugin UIs
 * colour a stretch of text with it; the span is accepted and forgotten by
 * [android.text.SpannableStringBuilder].
 */
open class ForegroundColorSpan(private val color: Int) {

    open fun getForegroundColor(): Int = color

    override fun toString(): String = "ForegroundColorSpan($color)"
}

open class BackgroundColorSpan(private val color: Int) {

    open fun getBackgroundColor(): Int = color
}

open class StyleSpan(private val style: Int) {

    open fun getStyle(): Int = style
}

open class TypefaceSpan(private val family: String?) {

    open fun getFamily(): String? = family

    override fun toString(): String = "TypefaceSpan($family)"
}

open class AbsoluteSizeSpan(private val size: Int) {

    open fun getSize(): Int = size
}

open class RelativeSizeSpan(private val proportion: Float) {

    open fun getSizeChange(): Float = proportion
}
