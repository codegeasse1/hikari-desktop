package android.graphics

import android.content.Context

/**
 * Desktop stand-in for `android.graphics.Color`. `parseColor` is the real
 * Android parser, so plugin code that computes shades from user colours keeps
 * working; nothing is ever painted.
 */
open class Color {

    companion object {
        const val BLACK = -0x1000000
        const val DKGRAY = -0xbbbbbc
        const val GRAY = -0x777778
        const val LTGRAY = -0x333334
        const val WHITE = -0x1
        const val RED = -0x10000
        const val GREEN = -0xff0100
        const val BLUE = -0xffff01
        const val YELLOW = -0x100
        const val CYAN = -0xff0001
        const val MAGENTA = -0xff01
        const val TRANSPARENT = 0

        @JvmStatic
        fun parseColor(colorString: String?): Int = parse(colorString)

        @JvmStatic
        fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue

        @JvmStatic
        fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue

        @JvmStatic
        fun alpha(color: Int): Int = color ushr 24

        @JvmStatic
        fun red(color: Int): Int = (color shr 16) and 0xff

        @JvmStatic
        fun green(color: Int): Int = (color shr 8) and 0xff

        @JvmStatic
        fun blue(color: Int): Int = color and 0xff

        /** Android's `parseColor`: `#rgb`, `#argb`, `#rrggbb`, `#aarrggbb`, or a
         *  known name. Unknown input is 0 (Android throws; a UI shim has no
         *  business killing the plugin over a colour typo). */
        private fun parse(s: String?): Int {
            val t = s?.trim() ?: return 0
            when (t.lowercase()) {
                "black" -> return BLACK
                "darkgray", "darkgrey" -> return DKGRAY
                "gray", "grey" -> return GRAY
                "lightgray", "lightgrey" -> return LTGRAY
                "white" -> return WHITE
                "red" -> return RED
                "green" -> return GREEN
                "blue" -> return BLUE
                "yellow" -> return YELLOW
                "cyan" -> return CYAN
                "magenta" -> return MAGENTA
                "aqua" -> return CYAN
                "fuchsia" -> return MAGENTA
                "lime" -> return GREEN
                "maroon" -> return -0x7f7f80
                "navy" -> return -0xffff80
                "olive" -> return -0x7f7f00
                "purple" -> return -0x7f0080
                "silver" -> return -0x3f3f40
                "teal" -> return -0xff7f80
                "transparent" -> return TRANSPARENT
            }
            if (t.isEmpty() || t[0] != '#') return 0
            val hex = t.substring(1)
            val v = hex.toLongOrNull(16) ?: return 0
            return when (hex.length) {
                3 -> {
                    val r = ((v shr 8) and 0xf).toInt()
                    val g = ((v shr 4) and 0xf).toInt()
                    val b = (v and 0xf).toInt()
                    argb(255, r * 17, g * 17, b * 17)
                }
                4 -> {
                    val a = ((v shr 12) and 0xf).toInt()
                    val r = ((v shr 8) and 0xf).toInt()
                    val g = ((v shr 4) and 0xf).toInt()
                    val b = (v and 0xf).toInt()
                    argb(a * 17, r * 17, g * 17, b * 17)
                }
                6 -> argb(255, ((v shr 16) and 0xff).toInt(), ((v shr 8) and 0xff).toInt(), (v and 0xff).toInt())
                8 -> argb(
                    ((v shr 24) and 0xff).toInt(),
                    ((v shr 16) and 0xff).toInt(),
                    ((v shr 8) and 0xff).toInt(),
                    (v and 0xff).toInt(),
                )
                else -> 0
            }
        }
    }
}

/**
 * Desktop stand-in for `android.graphics.Typeface`. The constants are real
 * objects because plugin code passes them straight back into `setTypeface`.
 */
open class Typeface private constructor(private val family: String?) {

    open fun getStyle(): Int = NORMAL

    override fun toString(): String = family ?: "Typeface"

    companion object {
        @JvmField val DEFAULT = Typeface("sans-serif")

        @JvmField val DEFAULT_BOLD = Typeface("sans-serif-bold")

        @JvmField val SANS_SERIF = Typeface("sans-serif")

        @JvmField val SERIF = Typeface("serif")

        @JvmField val MONOSPACE = Typeface("monospace")

        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        @JvmStatic
        fun create(familyName: String?, style: Int): Typeface = Typeface(familyName)

        @JvmStatic
        fun create(family: Typeface?, style: Int): Typeface = family ?: DEFAULT

        @JvmStatic
        fun defaultFromStyle(style: Int): Typeface =
            if (style and BOLD != 0) DEFAULT_BOLD else DEFAULT
    }
}

/**
 * Desktop stand-in for `android.graphics.Paint` (plugin UIs use it for
 * measuring/decoration; nothing is drawn).
 */
open class Paint(flags: Int = 0) {

    @JvmField var color: Int = Color.BLACK

    @JvmField var textSize: Float = 14f

    @JvmField var strokeWidth: Float = 0f

    @JvmField var alpha: Int = 255

    @JvmField var isAntiAlias: Boolean = false

    open fun setColor(color: Int) { this.color = color }
    open fun getColor(): Int = color
    open fun setTextSize(size: Float) { textSize = size }
    open fun setStrokeWidth(width: Float) { strokeWidth = width }
    open fun setAlpha(a: Int) { alpha = a }
    open fun setAntiAlias(b: Boolean) { isAntiAlias = b }
    open fun setTypeface(tf: Typeface?) {}
    open fun setStyle(style: Any?) {}
    open fun setStrokeCap(cap: Any?) {}
    open fun measureText(text: String?): Float = (text?.length ?: 0) * textSize * 0.5f

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val STROKE = 1
        const val FILL = 0
    }
}

/**
 * Desktop stand-in for `android.graphics.Bitmap`'s create helpers used by
 * plugin UIs (there is no pixel work to do on a headless view tree).
 */
open class Canvas(private val bitmap: Bitmap? = null) {
    open fun drawColor(color: Int) {}
    open fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint?) {}
}

/**
 * Desktop helper for `android.graphics.Rect` (plugin UIs measure into one).
 */
open class Rect {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    constructor()
    constructor(l: Int, t: Int, r: Int, b: Int) {
        left = l; top = t; right = r; bottom = b
    }

    open fun width(): Int = right - left
    open fun height(): Int = bottom - top
    open fun set(l: Int, t: Int, r: Int, b: Int) {
        left = l; top = t; right = r; bottom = b
    }
}
