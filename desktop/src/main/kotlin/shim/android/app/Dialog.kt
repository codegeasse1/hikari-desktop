package android.app

import android.content.Context
import android.view.View
import android.view.Window

/**
 * Desktop stand-in for `android.app.Dialog`. Plugin dialogs get a real (but
 * windowless) dialog object so `dialog?.window?.…` chains work; nothing is
 * displayed (see [android.view.View]).
 */
open class Dialog(context: Context?) {

    private val window = Window()

    private var showing = false

    open fun getWindow(): Window = window

    open fun show() {
        showing = true
    }

    open fun dismiss() {
        showing = false
    }

    open fun isShowing(): Boolean = showing

    open fun setTitle(title: CharSequence?) {}
    open fun setTitle(id: Int) {}
    open fun setCancelable(b: Boolean) {}
    open fun setCanceledOnTouchOutside(b: Boolean) {}
    open fun setOnDismissListener(l: Any?) {}
    open fun setOnShowListener(l: Any?) {}
    open fun setContentView(view: View?) {}
    open fun setContentView(view: View?, params: android.view.ViewGroup.LayoutParams?) {}
    open fun setContentView(id: Int) {}
    open fun findViewById(id: Int): View? = null
}

/**
 * Desktop stand-in for `android.app.AlertDialog`'s builder chain. Plugin
 * settings screens build one and `show()` it; every chained call is accepted.
 */
open class AlertDialog(context: Context?) : Dialog(context) {

    class Builder(private val context: Context?) {

        fun setTitle(title: CharSequence?): Builder = this
        fun setTitle(id: Int): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setMessage(id: Int): Builder = this
        fun setIcon(icon: Int): Builder = this
        fun setIcon(icon: android.graphics.drawable.Drawable?): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: OnClickListener?): Builder = this
        fun setPositiveButton(id: Int, listener: OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, listener: OnClickListener?): Builder = this
        fun setCancelable(b: Boolean): Builder = this
        fun setOnCancelListener(l: OnClickListener?): Builder = this
        fun setView(view: View?): Builder = this
        fun setAdapter(adapter: Any?, listener: OnClickListener?): Builder = this
        fun setItems(items: Array<CharSequence>?, listener: OnClickListener?): Builder = this
        fun setSingleChoiceItems(items: Array<CharSequence>?, checkedItem: Int, listener: OnClickListener?): Builder = this
        fun create(): AlertDialog = AlertDialog(context)
        fun show(): AlertDialog = AlertDialog(context).also { it.show() }
    }

    interface OnClickListener {
        fun onClick(dialog: Any?, which: Int)
    }
}
