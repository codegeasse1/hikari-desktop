package android.util

/**
 * Desktop stand-in for `android.util.DisplayMetrics`. Plugin UIs convert dp to
 * pixels with these; the desktop reports a 1× density so the maths is identity.
 */
open class DisplayMetrics {

    @JvmField var widthPixels: Int = 1280

    @JvmField var heightPixels: Int = 720

    @JvmField var density: Float = 1f

    @JvmField var scaledDensity: Float = 1f

    @JvmField var densityDpi: Int = 160

    @JvmField var xdpi: Float = 160f

    @JvmField var ydpi: Float = 160f

    @JvmField var noncompatWidthPixels: Int = widthPixels

    @JvmField var noncompatHeightPixels: Int = heightPixels

    @JvmField var noncompatDensity: Float = density

    @JvmField var noncompatDensityDpi: Int = densityDpi

    open fun setTo(m: DisplayMetrics?) {
        if (m == null) return
        widthPixels = m.widthPixels
        heightPixels = m.heightPixels
        density = m.density
        scaledDensity = m.scaledDensity
        densityDpi = m.densityDpi
    }

    companion object {
        const val DENSITY_LOW = 120
        const val DENSITY_MEDIUM = 160
        const val DENSITY_HIGH = 240
        const val DENSITY_XHIGH = 320
    }
}
