package androidx.fragment.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View

/**
 * Desktop stand-ins for the AndroidX fragment classes plugin UIs are built from.
 *
 * `DialogFragment` matters more than it looks: several CloudStream plugins show
 * a donation/permission dialog from `Plugin.load()`. On Android those classes
 * exist, so the plugin loads; on the desktop the JVM's verifier could not even
 * resolve them and the whole plugin failed with `NoClassDefFoundError` — i.e.
 * the extension appeared to be broken. These are inert but real objects:
 * lifecycle callbacks are no-ops, fragments are never attached, and the
 * transaction chain accepts and drops everything.
 */
open class Fragment {

    private var arguments: Bundle? = null

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroyView() {}
    open fun onDestroy() {}
    open fun onDetach() {}
    open fun onViewCreated(view: View?, savedInstanceState: Bundle?) {}
    open fun onCreateView(container: android.view.ViewGroup?, savedInstanceState: Bundle?): View? = null
    open fun onActivityCreated(savedInstanceState: Bundle?) {}
    open fun onSaveInstanceState(outState: Bundle) {}
    open fun setHasOptionsMenu(hasMenu: Boolean) {}
    open fun setRetainInstance(retain: Boolean) {}
    open fun setCancelable(cancelable: Boolean) {}

    open fun getView(): View? = null
    open fun getActivity(): android.app.Activity? = null
    open fun getContext(): Context? = null
    open fun getHost(): Any? = null
    open fun getParentFragment(): Fragment? = null
    open fun getChildFragmentManager(): FragmentManager = FragmentManager()
    open fun isAdded(): Boolean = false
    open fun isVisible(): Boolean = false
    open fun isResumed(): Boolean = false
    open fun isDetached(): Boolean = false
    open fun getTag(): String? = null
    open fun getId(): Int = 0
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    open fun requireContext(): Context = Context()
    open fun requireActivity(): android.app.Activity = android.app.Activity()

    open fun setArguments(args: Bundle?) {
        arguments = args
    }

    open fun getArguments(): Bundle? = arguments
}

open class DialogFragment : Fragment() {

    private var dialog: Dialog? = null
    private var shown = false

    open fun getDialog(): Dialog? = dialog

    open fun setStyle(style: Int, theme: Int) {}
    open fun setShowsDialog(showsDialog: Boolean) {}

    open fun show(manager: FragmentManager?, tag: String?) {
        shown = true
        onCreate(null)
        onStart()
    }

    open fun showNow(manager: FragmentManager?, tag: String?) = show(manager, tag)

    open fun show(transaction: FragmentTransaction?, tag: String?) {
        shown = true
        onCreate(null)
        onStart()
    }

    open fun dismiss() {
        shown = false
    }

    open fun dismissAllowingStateLoss() {
        shown = false
    }

    open fun isShowing(): Boolean = shown
}

/**
 * Desktop stand-in for `androidx.fragment.app.FragmentManager`. Transactions are
 * never recorded; nothing is displayed.
 */
open class FragmentManager {

    private val empty = emptyList<Fragment>()

    open fun beginTransaction(): FragmentTransaction = FragmentTransaction()

    open fun executePendingTransactions(): Boolean = false

    open fun findFragmentById(id: Int): Fragment? = null

    open fun findFragmentByTag(tag: String?): Fragment? = null

    open fun getFragments(): List<Fragment> = empty

    open fun getBackStackEntryCount(): Int = 0

    open fun popBackStack(): Boolean = false

    open fun addOnBackStackChangedListener(listener: Any?) {}

    open fun registerFragmentLifecycleCallbacks(cb: Any?, recursive: Boolean) {}
}

/**
 * Desktop stand-in for `androidx.fragment.app.FragmentTransaction`: every call
 * in the fluent chain returns the transaction and does nothing.
 */
open class FragmentTransaction {

    open fun add(containerViewId: Int, fragment: Fragment?): FragmentTransaction = this
    open fun add(containerViewId: Int, fragment: Fragment?, tag: String?): FragmentTransaction = this
    open fun add(fragment: Fragment?, tag: String?): FragmentTransaction = this
    open fun replace(containerViewId: Int, fragment: Fragment?): FragmentTransaction = this
    open fun replace(containerViewId: Int, fragment: Fragment?, tag: String?): FragmentTransaction = this
    open fun remove(fragment: Fragment?): FragmentTransaction = this
    open fun show(fragment: Fragment?): FragmentTransaction = this
    open fun hide(fragment: Fragment?): FragmentTransaction = this
    open fun attach(fragment: Fragment?): FragmentTransaction = this
    open fun detach(fragment: Fragment?): FragmentTransaction = this
    open fun addToBackStack(name: String?): FragmentTransaction = this
    open fun setCustomAnimations(a: Int, b: Int): FragmentTransaction = this
    open fun setCustomAnimations(a: Int, b: Int, c: Int, d: Int): FragmentTransaction = this
    open fun setTransition(transition: Int): FragmentTransaction = this
    open fun setReorderingAllowed(b: Boolean): FragmentTransaction = this
    open fun setPrimaryNavigationFragment(fragment: Fragment?): FragmentTransaction = this

    open fun commit() {}
    open fun commitAllowingStateLoss() {}
    open fun commitNow() {}
    open fun commitNowAllowingStateLoss() {}
    open fun runOnCommit(action: Runnable?) {
        runCatching { action?.run() }
    }
}
