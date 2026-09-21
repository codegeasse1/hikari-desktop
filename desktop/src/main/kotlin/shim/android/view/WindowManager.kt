package android.view

/**
 * Desktop stand-in for `android.view.WindowManager` — the layout-params object
 * plugin dialogs poke at (`window.attributes.width = …`).
 */
open class WindowManager {

    open class LayoutParams {
        @JvmField var width: Int = ViewGroup.LayoutParams.MATCH_PARENT
        @JvmField var height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var gravity: Int = 0
        @JvmField var horizontalMargin: Float = 0f
        @JvmField var verticalMargin: Float = 0f
        @JvmField var dimAmount: Float = 0f
        @JvmField var flags: Int = 0
        @JvmField var softInputMode: Int = 0
        @JvmField var alpha: Float = 1f

        fun setTitle(title: CharSequence?) = Unit
    }
}
