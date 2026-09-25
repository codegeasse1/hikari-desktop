package android.util

/**
 * Desktop stand-in for `android.util.AttributeSet`.
 *
 * An Android view (or preference) is built either from code or from an inflated
 * XML layout, and the second kind arrives as an AttributeSet. Aniyomi and
 * CloudStream extensions only ever construct their preferences and views in
 * code, but the `(Context, AttributeSet)` constructor appears in the type
 * signatures the JVM resolves while linking a class — so the type has to exist,
 * but nothing ever reads from it here.
 */
interface AttributeSet {

    fun getAttributeCount(): Int = 0

    fun getAttributeName(index: Int): String? = null

    fun getAttributeValue(index: Int): String? = null

    fun getAttributeValue(namespace: String?, name: String?): String? = null

    fun getAttributeIntValue(namespace: String?, attribute: String?, defaultValue: Int): Int = defaultValue

    fun getAttributeBooleanValue(namespace: String?, attribute: String?, defaultValue: Boolean): Boolean =
        defaultValue

    fun getPositionDescription(): String? = null

    fun getStyleAttribute(): Int = 0
}
