package android.content

import org.json.JSONObject
import java.io.File

/**
 * Desktop stand-in for Android's SharedPreferences, backed by one JSON file per
 * preference name under `~/.hikari/prefs/`.
 *
 * Aniyomi extensions are built around this: every `AnimeHttpSource` and
 * `ConfigurableAnimeSource` hands its settings screen a SharedPreferences, and a
 * source that cannot get one throws before it can list anything. Values are
 * coerced through the type the caller asks for, which is what Android does when
 * a stored type does not match the read.
 */
class SharedPreferences internal constructor(private val file: File) {

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?)
    }

    private val lock = Any()
    private val data: JSONObject
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<OnSharedPreferenceChangeListener>()

    init {
        data = synchronized(lock) {
            runCatching { JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}") }
                .getOrElse { JSONObject() }
        }
    }

    fun getAll(): Map<String, Any?> = synchronized(lock) {
        val out = HashMap<String, Any?>(data.length())
        for (k in data.keys()) out[k] = data.opt(k)?.takeIf { it !== JSONObject.NULL }
        out
    }

    fun getString(key: String, defValue: String?): String? = value(key) {
        when (it) {
            is String -> it
            is Number, is Boolean -> it.toString()
            else -> defValue
        }
    } ?: defValue

    fun getInt(key: String, defValue: Int): Int = value(key) {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull() ?: defValue
            is Boolean -> if (it) 1 else 0
            else -> defValue
        }
    } ?: defValue

    fun getLong(key: String, defValue: Long): Long = value(key) {
        when (it) {
            is Number -> it.toLong()
            is String -> it.toLongOrNull() ?: defValue
            else -> defValue
        }
    } ?: defValue

    fun getFloat(key: String, defValue: Float): Float = value(key) {
        when (it) {
            is Number -> it.toFloat()
            is String -> it.toFloatOrNull() ?: defValue
            else -> defValue
        }
    } ?: defValue

    fun getBoolean(key: String, defValue: Boolean): Boolean = value(key) {
        when (it) {
            is Boolean -> it
            is String -> it.equals("true", ignoreCase = true)
            is Number -> it.toInt() != 0
            else -> defValue
        }
    } ?: defValue

    fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val raw = value(key) { it } ?: return defValues
        if (raw is JSONObject) {
            val out = LinkedHashSet<String>(raw.length())
            for (k in raw.keys()) out.add(k)
            return out
        }
        return defValues
    }

    fun contains(key: String): Boolean = synchronized(lock) { data.has(key) }

    fun edit(): Editor = Editor()

    fun registerOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener) { listeners.add(l) }

    fun unregisterOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener) { listeners.remove(l) }

    private fun <T> value(key: String, map: (Any) -> T?): T? = synchronized(lock) {
        if (!data.has(key)) return@synchronized null
        val raw = data.opt(key) ?: return@synchronized null
        if (raw === JSONObject.NULL) null else map(raw)
    }

    private fun persist(put: Map<String, Any?>, removed: Set<String>, clearFirst: Boolean) {
        synchronized(lock) {
            if (clearFirst) {
                // org.json's keys() is an Iterator, so it must be drained before
                // removal — and it can't go through ArrayList(Iterator).
                for (k in data.keys().asSequence().toList()) data.remove(k)
            }
            for (k in removed) data.remove(k)
            for ((k, v) in put) {
                runCatching { data.put(k, v ?: JSONObject.NULL) }
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(data.toString())
            }
        }
        val changed = (put.keys + removed).distinct()
        if (changed.isNotEmpty()) {
            for (l in listeners) runCatching { changed.forEach { key -> l.onSharedPreferenceChanged(this, key) } }
        }
    }

    /** Android's editor: accumulate, then one `apply`/`commit`. */
    inner class Editor {
        private val put = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clearFirst = false

        fun putString(key: String, value: String?) = apply { put[key] = value }
        fun putInt(key: String, value: Int) = apply { put[key] = value }
        fun putLong(key: String, value: Long) = apply { put[key] = value }
        fun putFloat(key: String, value: Float) = apply { put[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }

        fun putStringSet(key: String, values: Set<String>?) = apply {
            put[key] = JSONObject().apply { values?.forEachIndexed { i, v -> this.put("v$i", v) } }
        }

        fun remove(key: String) = apply { removed.add(key) }

        fun clear() = apply { clearFirst = true }

        fun commit(): Boolean {
            persist(put, removed, clearFirst)
            return true
        }

        fun apply() { persist(put, removed, clearFirst) }
    }

    companion object {
        /** Name used by [Context.getSharedPreferences]; one file per name. */
        internal fun fileFor(dir: File, name: String): File {
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            return File(File(dir, "prefs"), "$safe.json")
        }
    }
}
