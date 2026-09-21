package android.view

/**
 * Desktop stand-in for `android.view.Gravity`. Plugin UIs set gravity on their
 * (inert) layouts, so the constants just have to exist with Android's values.
 */
object Gravity {

    const val NO_GRAVITY = 0
    const val AXIS_SPECIFIED = 0x0001
    const val CENTER_HORIZONTAL = 0x0001
    const val CENTER_VERTICAL = 0x0010
    const val CENTER = CENTER_HORIZONTAL or CENTER_VERTICAL
    const val LEFT = 0x0003
    const val RIGHT = 0x0005
    const val TOP = 0x0030
    const val BOTTOM = 0x0050
    const val START = 0x00800003
    const val END = 0x00800005
    const val FILL = 0x0077
    const val FILL_HORIZONTAL = 0x0007
    const val FILL_VERTICAL = 0x0070
    const val CLIP_HORIZONTAL = 0x0008
    const val CLIP_VERTICAL = 0x0080
}
