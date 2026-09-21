package android.util

/**
 * The slice of Android's Base64 that extensions actually call.
 *
 * Flag values and behaviour match the platform: DEFAULT, NO_PADDING, NO_WRAP,
 * CRLF and URL_SAFE, with URL_SAFE switching to the `-`/`_` alphabet. A plugin
 * that decodes a URL-safe payload (or an extension whose poster/API payload is
 * URL-safe base64) reads correctly here instead of silently producing truncated
 * garbage, which is how a whole provider ends up with blank posters.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val base = if ((flags and URL_SAFE) != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        val encoder = if ((flags and NO_PADDING) != 0) base.withoutPadding() else base
        val s = encoder.encodeToString(input)
        return if ((flags and NO_WRAP) != 0) s else wrap(s, if ((flags and CRLF) != 0) "\r\n" else "\n")
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val clean = str.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if ((flags and URL_SAFE) != 0) return java.util.Base64.getUrlDecoder().decode(clean)
        // Android's DEFAULT decoder tolerates line wrapping but stops at any
        // other character outside the alphabet; the MIME decoder is the closest
        // JVM equivalent (it ignores the whitespace `encodeToString` adds).
        return java.util.Base64.getMimeDecoder().decode(str)
    }

    private fun wrap(s: String, nl: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (i > 0) sb.append(nl)
            sb.append(s, i, minOf(i + 76, s.length))
            i += 76
        }
        return sb.toString()
    }
}
