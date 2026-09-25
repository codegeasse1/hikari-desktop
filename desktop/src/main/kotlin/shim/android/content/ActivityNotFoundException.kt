package android.content

/**
 * Desktop stand-in for `android.content.ActivityNotFoundException`.
 *
 * The type an extension catches when `startActivity` has no app for an intent
 * (`context.startActivity(Intent(ACTION_VIEW, uri))` — the "open this link in a
 * browser/external player" path). 42 of the 96 extensions in the corpus name it
 * in a `catch` clause, and an exception type named by a handler is resolved
 * while the class is verified: without it here, the whole source fails to load
 * with `NoClassDefFoundError: android/content/ActivityNotFoundException` instead
 * of opening a link.
 */
open class ActivityNotFoundException : RuntimeException {

    constructor() : super()

    constructor(name: String?) : super(name)

    constructor(name: String?, cause: Throwable?) : super(name, cause)
}
