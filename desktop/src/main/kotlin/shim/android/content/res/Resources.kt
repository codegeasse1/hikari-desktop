package android.content.res

import android.util.DisplayMetrics

/**
 * Desktop stand-in for `android.content.res.Resources`. Plugin UIs read the
 * display metrics to size their views; there is nothing to size, so the metrics
 * report a neutral 1× density.
 */
open class Resources {

    private val metrics = DisplayMetrics()

    open fun getDisplayMetrics(): DisplayMetrics = metrics

    open fun getConfiguration(): Configuration = Configuration()

    open fun getDimension(id: Int): Float = 0f

    open fun getDimensionPixelSize(id: Int): Int = 0

    open fun getString(id: Int): String = ""

    open fun getString(id: Int, vararg formatArgs: Any?): String = ""

    open fun getStringArray(id: Int): Array<String> = emptyArray()

    open fun getColor(id: Int): Int = 0

    open fun getColor(id: Int, theme: Any?): Int = 0

    open fun getDrawable(id: Int): android.graphics.drawable.Drawable? = null

    open fun getBoolean(id: Int): Boolean = false

    open fun getInteger(id: Int): Int = 0

    open fun openRawResource(id: Int): java.io.InputStream =
        java.io.ByteArrayInputStream(ByteArray(0))

    companion object {
        const val ID_NULL = 0
    }
}

/**
 * Desktop stand-in for `android.content.res.Configuration`.
 */
open class Configuration {

    @JvmField var orientation: Int = ORIENTATION_PORTRAIT

    @JvmField var uiMode: Int = UI_MODE_TYPE_UNDEFINED

    @JvmField var screenWidthDp: Int = 411

    @JvmField var screenHeightDp: Int = 891

    @JvmField var fontScale: Float = 1f

    @JvmField var densityDpi: Int = 160

    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val UI_MODE_TYPE_UNDEFINED = 0
        const val UI_MODE_TYPE_NORMAL = 1
    }
}
