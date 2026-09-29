package android.text

/**
 * Desktop stand-in for `android.text.SpannableStringBuilder`.
 *
 * Plugin UIs build styled text with one of these and hand it to a (never drawn)
 * TextView, and Aniyomi sources use the same type for the HTML-ish text a page
 * hands them. The text itself is kept — the object is a real [Editable], so it
 * can be passed to a `TextWatcher` and read back — while spans are accepted and
 * forgotten (nothing on the desktop draws them).
 *
 * Being an Editable rather than a bare CharSequence is what the extensions were
 * compiled against, and a source that hands its own builder to
 * `afterTextChanged(s: Editable?)` needs the type to line up.
 */
open class SpannableStringBuilder(text: CharSequence? = "") : Editable {

    private val content = StringBuilder(text ?: "")

    private val spans = LinkedHashMap<Any, IntArray>()

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        content.subSequence(startIndex, endIndex)

    // ── spans ───────────────────────────────────────────────────────────────

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        if (what != null) spans[what] = intArrayOf(start, end, flags)
    }

    override fun removeSpan(what: Any?) {
        spans.remove(what)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val out = ArrayList<T>()
        for ((span, range) in spans) {
            if (range[1] <= start || range[0] >= end) continue
            if (type.isInstance(span)) out.add(span as T)
        }
        // `toTypedArray()` needs a reified T, which an override of a generic
        // method cannot have — the array is built from the Class instead.
        val arr = java.lang.reflect.Array.newInstance(type, out.size) as Array<T>
        for (i in out.indices) arr[i] = out[i]
        return arr
    }

    override fun getSpanStart(tag: Any?): Int = spans[tag]?.get(0) ?: -1

    override fun getSpanEnd(tag: Any?): Int = spans[tag]?.get(1) ?: -1

    override fun getSpanFlags(tag: Any?): Int = spans[tag]?.get(2) ?: 0

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit
        for ((span, range) in spans) {
            if (type != null && !type.isInstance(span)) continue
            if (range[0] > start && range[0] < next) next = range[0]
            if (range[1] > start && range[1] < next) next = range[1]
        }
        return next
    }

    // ── editing ─────────────────────────────────────────────────────────────

    override fun append(text: CharSequence?): SpannableStringBuilder {
        content.append(text ?: "")
        return this
    }

    override fun append(text: CharSequence?, start: Int, end: Int): SpannableStringBuilder {
        content.append((text ?: "").subSequence(start, end).toString())
        return this
    }

    override fun append(text: Char): SpannableStringBuilder {
        content.append(text)
        return this
    }

    override fun insert(where: Int, text: CharSequence?): SpannableStringBuilder {
        content.insert(where, (text ?: "").toString())
        return this
    }

    override fun replace(start: Int, end: Int, text: CharSequence?): SpannableStringBuilder {
        content.replace(start, end, (text ?: "").toString())
        return this
    }

    override fun delete(st: Int, en: Int): SpannableStringBuilder {
        content.delete(st, en)
        return this
    }

    override fun clear() {
        content.clear()
    }

    override fun clearSpans() {
        spans.clear()
    }

    override fun toString(): String = content.toString()
}
