package android.support.v7.app

/**
 * Legacy support-library spelling of an AndroidX `AppCompatActivity`. See
 * [android.support.v4.app.FragmentActivity] for why the desktop keeps it.
 */
open class AppCompatActivity : android.support.v4.app.FragmentActivity() {

    override fun toString(): String = "HikariDesktopActivity"
}
