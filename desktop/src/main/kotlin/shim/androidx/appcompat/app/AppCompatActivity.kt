package androidx.appcompat.app

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager

/**
 * Desktop stand-in for `androidx.appcompat.app.AppCompatActivity`.
 *
 * CloudStream plugins treat the `Context` handed to `Plugin.load()` as the host
 * activity: they cast it to `AppCompatActivity`, read its
 * `supportFragmentManager`, and show a donation/notice dialog through it. The
 * Android host really is one, so the plugin works there; on the desktop the
 * class did not exist at all and every such plugin failed to load
 * (`NoClassDefFoundError: androidx/appcompat/app/AppCompatActivity`).
 *
 * The inheritance chain deliberately runs through the LEGACY support-library
 * spellings ([android.support.v7.app.AppCompatActivity] and
 * [android.support.v4.app.FragmentActivity]) as well, so an old plugin's
 * `context as android.support.v7.app.AppCompatActivity` also succeeds against
 * the same object.
 */
open class AppCompatActivity : androidx.fragment.app.FragmentActivity() {

    open fun getSupportActionBar(): Any? = null

    open fun setSupportActionBar(bar: Any?) {}

    open fun setContentView(view: View?) {}

    open fun setContentView(layoutResID: Int) {}

    open fun setContentView(view: View?, params: android.view.ViewGroup.LayoutParams?) {}

    open fun onCreate(savedInstanceState: Bundle?) {}

    open fun onResume() {}

    open fun onPause() {}

    open fun onStart() {}

    open fun onStop() {}

    open fun onDestroy() {}

    open fun onOptionsItemSelected(item: Any?): Boolean = false

    open fun invalidateOptionsMenu() {}

    override fun getSupportFragmentManager(): FragmentManager = super.getSupportFragmentManager()

    override fun toString(): String = "HikariDesktopActivity"
}

/**
 * The single concrete "activity" the desktop hands to plugin load hooks. One
 * instance per process, mirroring an Android app whose activity outlives a
 * plugin load.
 */
class DesktopActivity : AppCompatActivity() {

    companion object {

        @Volatile
        private var singleton: DesktopActivity? = null

        /** The process-wide host activity, created on first use. */
        fun instance(): DesktopActivity = singleton ?: synchronized(this) {
            singleton ?: DesktopActivity().also { singleton = it }
        }
    }
}
