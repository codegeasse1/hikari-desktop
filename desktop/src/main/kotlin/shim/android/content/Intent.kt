package android.content

import android.net.Uri

open class Intent {
    val action: String?
    val data: Uri?
    private var flags: Int = 0

    constructor() {
        action = null
        data = null
    }

    constructor(action: String, uri: Uri) {
        this.action = action
        this.data = uri
    }

    constructor(action: String?) {
        this.action = action
        this.data = null
    }

    /** Android returns the Intent here, and plugin code chains off it. */
    fun addFlags(flags: Int): Intent {
        this.flags = this.flags or flags
        return this
    }

    fun getFlags(): Int = flags

    fun setFlags(flags: Int) {
        this.flags = flags
    }

    fun setAction(action: String?): Intent = this

    fun setData(uri: Uri?): Intent = this

    fun setPackage(packageName: String?): Intent = this

    fun setType(type: String?): Intent = this

    fun putExtra(name: String, value: String?) = Unit

    fun getStringExtra(name: String): String? = null

    override fun toString(): String = data?.toString() ?: action ?: ""

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_NO_HISTORY = 0x40000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
    }
}
