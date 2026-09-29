package android.content

import org.json.JSONObject
import java.io.File

interface SharedPreferences {
    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }

    fun getAll(): Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
}

class DesktopSharedPreferences internal constructor(private val file: File) : SharedPreferences {

    private val lock = Any()
    private val data: JSONObject
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        data = synchronized(lock) {
            runCatching { JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}") }
                .getOrElse { JSONObject() }
        }
    }

    override fun getAll(): Map<String, Any?> = synchronized(lock) {
        val out = HashMap<String, Any?>(data.length())
        for (k in data.keys()) out[k] = data.opt(k)?.takeIf { it !== JSONObject.NULL }
        out
    }

    override fun getString(key: String, defValue: String?): String? = value(key) {
        when (it) {
            is String -> it
            is Number, is Boolean -> it.toString()
            else -> defValue
        }
    } ?: defValue

    override fun getInt(key: String, defValue: Int): Int = value(key) {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull() ?: defValue
            is Boolean -> if (it) 1 else 0
            else -> defValue
        }
    } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = value(key) {
        when (it) {
            is Number -> it.toLong()
            is String -> it.toLongOrNull() ?: defValue
            else -> defValue
        }
    } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = value(key) {
        when (it) {
            is Number -> it.toFloat()
            is String -> it.toFloatOrNull() ?: defValue
            else -> defValue
        }
    } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = value(key) {
        when (it) {
            is Boolean -> it
            is String -> it.equals("true", ignoreCase = true)
            is Number -> it.toInt() != 0
            else -> defValue
        }
    } ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val raw = value(key) { it } ?: return defValues
        if (raw is JSONObject) {
            val out = LinkedHashSet<String>(raw.length())
            for (k in raw.keys()) out.add(k)
            return out
        }
        return defValues
    }

    override fun contains(key: String): Boolean = synchronized(lock) { data.has(key) }

    override fun edit(): Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

    private fun <T> value(key: String, map: (Any) -> T?): T? = synchronized(lock) {
        if (!data.has(key)) return@synchronized null
        val raw = data.opt(key) ?: return@synchronized null
        if (raw === JSONObject.NULL) null else map(raw)
    }

    private fun persist(put: Map<String, Any?>, removed: Set<String>, clearFirst: Boolean) {
        synchronized(lock) {
            if (clearFirst) {
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

    inner class Editor : SharedPreferences.Editor {
        private val put = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { put[key] = value }
        override fun putInt(key: String, value: Int) = apply { put[key] = value }
        override fun putLong(key: String, value: Long) = apply { put[key] = value }
        override fun putFloat(key: String, value: Float) = apply { put[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }

        override fun putStringSet(key: String, values: Set<String>?) = apply {
            put[key] = JSONObject().apply { values?.forEachIndexed { i, v -> this.put("v" + i, v) } }
        }

        override fun remove(key: String) = apply { removed.add(key) }

        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            persist(put, removed, clearFirst)
            return true
        }

        override fun apply() { persist(put, removed, clearFirst) }
    }

    companion object {
        internal fun fileFor(dir: File, name: String): File {
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            return File(File(dir, "prefs"), safe + ".json")
        }
    }
}
