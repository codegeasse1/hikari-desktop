package android.text

/**
 * Desktop stand-ins for the text types an extension names.
 *
 * Measured over 96 real Aniyomi extensions: `android.text.Editable` (18) and
 * `android.text.TextWatcher` (18) are named by a source's own code — the usual
 * "watch an EditText" helper — and `Spanned`/`Spannable`/`SpannableString`/
 * `Html` by the ones that build or strip styled text. A type named by an
 * extension is resolved when its class is linked, so a missing one is not a
 * missing feature: the whole source fails to load.
 *
 * They are real (if simple) implementations: text is kept, spans are accepted
 * and forgotten, and [Html] strips/serialises tags — enough that a source that
 * runs a helper over its own string sees the text it put in.
 */

/** Android's `android.text.Spanned`: text with spans attached. */
interface Spanned : CharSequence {

    fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T>

    fun getSpanStart(tag: Any?): Int = -1

    fun getSpanEnd(tag: Any?): Int = -1

    fun getSpanFlags(tag: Any?): Int = 0

    fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int = limit

    companion object {
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 33
        const val SPAN_EXCLUSIVE_INCLUSIVE = 34
        const val SPAN_INCLUSIVE_EXCLUSIVE = 17
        const val SPAN_INCLUSIVE_INCLUSIVE = 18
        const val SPAN_POINT_MARK_MASK = 51
        const val SPAN_MARK_MARK = 17
        const val SPAN_MARK_POINT = 18
        const val SPAN_POINT_MARK = 33
        const val SPAN_POINT_POINT = 34
        const val SPAN_PARAGRAPH = 51
        const val SPAN_COMPOSING = 256
        const val SPAN_USER = -16777216
        const val SPAN_PRIORITY = 16711680
    }
}

/** Android's `android.text.Spannable`. */
interface Spannable : Spanned {

    fun setSpan(what: Any?, start: Int, end: Int, flags: Int)

    fun removeSpan(what: Any?)
}

/** Android's `android.text.Editable` — the text a `TextWatcher` is handed. */
interface Editable : Spannable {

    fun replace(start: Int, end: Int, text: CharSequence?): Editable

    fun insert(where: Int, text: CharSequence?): Editable

    fun delete(st: Int, en: Int): Editable

    fun append(text: CharSequence?): Editable

    fun append(text: CharSequence?, start: Int, end: Int): Editable

    fun append(text: Char): Editable

    fun clear()

    fun clearSpans()
}

/** Android's `android.text.TextWatcher` — the three callbacks a source
 *  implementing one overrides. */
interface TextWatcher {

    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)

    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)

    fun afterTextChanged(s: Editable?)
}

/**
 * Android's `android.text.SpannableString`: fixed text, spans that are accepted
 * and forgotten (nothing on the desktop draws them).
 */
open class SpannableString(text: CharSequence?) : Spannable {

    private val content: String = (text ?: "").toString()

    private val spans = LinkedHashMap<Any, IntArray>()

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        content.subSequence(startIndex, endIndex)

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

    override fun toString(): String = content
}

/**
 * Android's `android.text.Html`.
 *
 * A source that scrapes a site with an HTML body text sometimes converts it
 * (`Html.fromHtml(html)`) before showing or matching it, so this really does the
 * conversion — tags are stripped, the common entities are decoded — rather than
 * returning an empty span.
 */
object Html {

    fun interface ImageGetter {
        fun getDrawable(source: String?): android.graphics.drawable.Drawable?
    }

    /** The callback Android calls for unknown tags (`<custom:…>`). */
    fun interface TagHandler {
        fun handleTag(opening: Boolean, tag: String?, output: Editable?, xmlReader: org.xml.sax.XMLReader?)
    }

    @JvmStatic
    fun fromHtml(source: String?): Spanned = SpannableString(strip(source))

    @JvmStatic
    fun fromHtml(source: String?, imageGetter: ImageGetter?, tagHandler: TagHandler?): Spanned =
        SpannableString(strip(source))

    @JvmStatic
    fun toHtml(text: Spanned?): String =
        "<p>" + escapeHtml(text?.toString()) + "</p>"

    @JvmStatic
    fun escapeHtml(text: CharSequence?): String = (text ?: "").toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** Tags out, the entities an extension's own string matching cares about
     *  (and line breaks) back in. */
    private fun strip(html: String?): String {
        val text = html ?: return ""
        var out = text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        out = out.replace(Regex("(?i)<br\\s*/?>"), "\n")
        out = out.replace(Regex("(?i)</p\\s*>"), "\n\n")
        out = out.replace(Regex("<[^>]*>"), "")
        return out
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
    }
}
