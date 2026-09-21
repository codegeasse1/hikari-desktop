package androidx.fragment.app

/**
 * AndroidX spelling of a fragment-hosting activity. Sits between the legacy
 * support-library stand-in and [androidx.appcompat.app.AppCompatActivity] so the
 * whole chain is one real class hierarchy (see
 * [android.support.v4.app.FragmentActivity]).
 */
open class FragmentActivity : android.support.v7.app.AppCompatActivity() {

    private val fragmentManager = FragmentManager()

    override fun getSupportFragmentManager(): FragmentManager = fragmentManager

    open fun getFragmentManager(): FragmentManager = fragmentManager

    open fun onAttachFragment(fragment: Fragment?) {}
}
