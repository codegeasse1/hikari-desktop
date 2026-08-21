package android.net

class Uri private constructor(
    val scheme: String?,
    val host: String?,
    val path: String?,
    private val raw: String,
) {
    override fun toString(): String = raw

    companion object {
        @JvmStatic
        fun parse(s: String): Uri =
            runCatching { java.net.URI(s) }.fold(
                { u -> Uri(u.scheme, u.host, u.path, s) },
                { Uri(null, null, null, s) },
            )
    }
}
