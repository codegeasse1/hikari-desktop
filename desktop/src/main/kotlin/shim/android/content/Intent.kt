package android.content

import android.net.Uri

open class Intent {
    val action: String?
    val data: Uri?

    constructor() {
        action = null
        data = null
    }

    constructor(action: String, uri: Uri) {
        this.action = action
        this.data = uri
    }

    override fun toString(): String = data?.toString() ?: action ?: ""

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
