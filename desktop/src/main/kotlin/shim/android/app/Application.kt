package android.app

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.Window

/**
 * The desktop's application context.
 *
 * It is a [android.content.ContextWrapper], exactly as Android's Application is:
 * extensions unwrap the context they are handed (`(context as ContextWrapper)
 * .baseContext`) often enough that a context which is not one would throw a
 * ClassCastException where the real platform succeeds.
 */
open class Application : android.content.ContextWrapper(null)

/**
 * Minimal Activity shim.
 *
 * CloudStream plugins cast the `Context` they are handed to an Activity (and
 * often straight to `AppCompatActivity` — see
 * [androidx.appcompat.app.DesktopActivity], which the desktop passes to
 * `Plugin.load()`), then reach for the pieces of an Activity they need. Those
 * pieces are here so the plugin's own code keeps running instead of dying on a
 * NoSuchMethodError; nothing is ever displayed.
 */
open class Activity : android.content.ContextWrapper(null) {

    private val activityWindow = Window()

    private var finished = false

    @JvmField
    var intent: Intent? = null

    @JvmField
    var title: CharSequence? = null

    open fun getWindow(): Window = activityWindow

    open fun getLayoutInflater(): Any? = null

    open fun findViewById(id: Int): View? = null

    open fun isFinishing(): Boolean = finished

    open fun isDestroyed(): Boolean = finished

    open fun isChangingConfigurations(): Boolean = false

    open fun finish() {
        finished = true
    }

    open fun finishAffinity() {
        finished = true
    }

    open fun runOnUiThread(action: Runnable?) {
        runCatching { action?.run() }
    }

    open fun getIntent(): Intent? = intent

    open fun setIntent(i: Intent?) {
        intent = i
    }

    open fun getTitle(): CharSequence? = title

    open fun setTitle(t: CharSequence?) {
        title = t
    }

    open fun setTitle(id: Int) {}

    open fun setResult(resultCode: Int) {}

    open fun setResult(resultCode: Int, data: Intent?) {}

    open fun recreate() {}

    override fun toString(): String = "DesktopActivity"
}
