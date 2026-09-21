package android.support.v4.app

import androidx.fragment.app.FragmentManager

/**
 * Legacy support-library spelling of an AndroidX `FragmentActivity`. Old
 * CloudStream plugins reference these pre-Jetifier names, so the desktop keeps
 * the old chain alive: `android.app.Activity` ← support-v4 `FragmentActivity` ←
 * support-v7 `AppCompatActivity` ← the AndroidX stand-ins. An instance of the
 * host activity is therefore castable through BOTH spellings.
 */
open class FragmentActivity : android.app.Activity() {

    private val legacyFragmentManager = FragmentManager()

    open fun getSupportFragmentManager(): FragmentManager = legacyFragmentManager

    open fun onBackPressed() {}
}
