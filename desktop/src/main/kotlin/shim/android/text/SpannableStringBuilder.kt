package android.text

/**
 * Desktop stand-in for `android.text.SpannableStringBuilder`. Plugin UIs build
 * styled text with one of these and hand it to a (never drawn) TextView, so the
 * spans are accepted and forgotten — but the object still has to be a real
 * CharSequence, because that is what it is passed as.
 */
open class SpannableStringBuilder(text: CharSequence? = "") : CharSequence {

    private val content = StringBuilder(text ?: "")

    /** Spans are accepted and dropped; the text itself is kept, so a plugin that
     *  reads its own string back still sees what it wrote. */
    open fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {}

    open fun removeSpan(what: Any?) {}

    open fun append(text: CharSequence?): SpannableStringBuilder {
        content.append(text ?: "")
        return this
    }

    open fun append(text: CharSequence?, start: Int, end: Int): SpannableStringBuilder {
        content.append((text ?: "").subSequence(start, end).toString())
        return this
    }

    open fun insert(where: Int, text: CharSequence?): SpannableStringBuilder {
        content.insert(where, (text ?: "").toString())
        return this
    }

    open fun replace(start: Int, end: Int, text: CharSequence?) {
        content.replace(start, end, (text ?: "").toString())
    }

    open fun clear() {
        content.clear()
    }

    open fun clearSpans() {}

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        content.subSequence(startIndex, endIndex)

    override fun toString(): String = content.toString()
}
