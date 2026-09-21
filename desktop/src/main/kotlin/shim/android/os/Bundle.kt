package android.os

/**
 * Desktop stand-in for Android's Bundle.
 *
 * Aniyomi extensions read their metadata (`<meta-data>` entries) through
 * `ApplicationInfo.metaData`, which is a Bundle on Android. The desktop APK
 * reader ([com.hikari.app.aniyomi.ApkManifest]) fills one of these from the
 * package's AndroidManifest.xml, so extension code that asks for
 * `metaData.getString("...")` works unchanged.
 */
class Bundle {

    private val map = LinkedHashMap<String, Any?>()

    fun putString(key: String, value: String?) { map[key] = value }
    fun putInt(key: String, value: Int) { map[key] = value }
    fun putLong(key: String, value: Long) { map[key] = value }
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun putFloat(key: String, value: Float) { map[key] = value }
    fun putDouble(key: String, value: Double) { map[key] = value }
    fun putCharSequence(key: String, value: CharSequence?) { map[key] = value?.toString() }
    fun putStringArrayList(key: String, value: ArrayList<String>?) { map[key] = value }
    fun putParcelable(key: String, value: Any?) { map[key] = value }
    fun putSerializable(key: String, value: Any?) { map[key] = value }
    fun putAll(other: Bundle?) {
        other?.map?.forEach { (k, v) -> map[k] = v }
    }

    fun getString(key: String): String? = getString(key, null)

    fun getString(key: String, defaultValue: String?): String? = when (val v = map[key]) {
        null -> defaultValue
        is String -> v
        else -> v.toString()
    }

    fun getInt(key: String): Int = getInt(key, 0)
    fun getInt(key: String, defaultValue: Int): Int = when (val v = map[key]) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }

    fun getLong(key: String): Long = getLong(key, 0L)
    fun getLong(key: String, defaultValue: Long): Long = when (val v = map[key]) {
        is Long -> v
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: defaultValue
        else -> defaultValue
    }

    fun getBoolean(key: String): Boolean = getBoolean(key, false)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = when (val v = map[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> defaultValue
    }

    fun getFloat(key: String): Float = getFloat(key, 0f)
    fun getFloat(key: String, defaultValue: Float): Float = when (val v = map[key]) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: defaultValue
        else -> defaultValue
    }

    fun getDouble(key: String): Double = getDouble(key, 0.0)
    fun getDouble(key: String, defaultValue: Double): Double = when (val v = map[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: defaultValue
        else -> defaultValue
    }

    fun getCharSequence(key: String): CharSequence? = getString(key)
    fun getCharSequence(key: String, defaultValue: CharSequence?): CharSequence? =
        getString(key, defaultValue?.toString())

    fun getStringArrayList(key: String): ArrayList<String>? = when (val v = map[key]) {
        is ArrayList<*> -> @Suppress("UNCHECKED_CAST") (v as ArrayList<String>)
        else -> null
    }

    fun getParcelable(key: String): Any? = map[key]

    fun <T> getParcelable(key: String, clazz: Class<T>): T? =
        map[key]?.let { v -> runCatching { clazz.cast(v) }.getOrNull() }

    fun containsKey(key: String): Boolean = map.containsKey(key)

    fun keySet(): Set<String> = map.keys

    fun isEmpty(): Boolean = map.isEmpty()

    fun size(): Int = map.size

    fun remove(key: String) { map.remove(key) }

    fun clear() { map.clear() }
}
