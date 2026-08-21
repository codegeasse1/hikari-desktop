package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2

    fun encodeToString(input: ByteArray, flags: Int): String {
        val s = java.util.Base64.getEncoder().encodeToString(input)
        return if ((flags and NO_WRAP) != 0) s else wrap(s)
    }

    fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getMimeDecoder().decode(str)

    private fun wrap(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (i > 0) sb.append('\n')
            sb.append(s, i, minOf(i + 76, s.length))
            i += 76
        }
        return sb.toString()
    }
}
