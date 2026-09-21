package androidx.core.text

/** Android's Spanned is not modelled on the desktop; parsed HTML is returned as
 *  its rendered text, which is what the callers do with it anyway. */
typealias Spanned = String

/**
 * Desktop stand-in for AndroidX's `String.parseAsHtml()`.
 *
 * Aniyomi sources use it to turn a site's HTML fragment (a description, a title
 * with tags) into plain display text. Jsoup does the same job here.
 */
fun String.parseAsHtml(): Spanned = org.jsoup.Jsoup.parse(this).text()
