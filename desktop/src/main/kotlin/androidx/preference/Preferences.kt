package androidx.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Desktop stand-ins for AndroidX's preference library.
 *
 * Aniyomi's `ConfigurableAnimeSource` declares
 * `setupPreferenceScreen(screen: PreferenceScreen)`, and every source that has
 * settings — which is most of them — builds that screen out of these classes:
 * the JVM's verifier resolves the types in the extension's own bytecode when it
 * links the source class, so an extension that MENTIONS `Preference`,
 * `ListPreference` or `Preference.OnPreferenceChangeListener` fails to load at
 * all (`NoClassDefFoundError: androidx/preference/Preference$OnPreferenceChange
 * Listener`) when the desktop only has the empty `PreferenceScreen` stub that
 * used to live here.
 *
 * That failure was measured across 96 real extensions from the live Aniyomi
 * repos (aniyomiorg's own three, plus the bubz727, adiy98, anime-extensions-repo
 * and cursedyomi repositories): `PreferenceScreen` 91/96, `Preference` 91,
 * `ListPreference` 89, `Preference$OnPreferenceChangeListener` 88,
 * `MultiSelectListPreference` 44, `EditTextPreference` 22,
 * `EditTextPreference$OnBindEditTextListener` 18, `SwitchPreferenceCompat` 18,
 * `CheckBoxPreference` 1. Those nine types are what this file implements, plus
 * the members the same corpus actually calls (the method-and-descriptor list is
 * in `AniyomiExtensionSelfTest`, which fails the build if a real extension stops
 * loading).
 *
 * These are NOT inert: a preference persists through the same
 * [SharedPreferences] the rest of the app uses (see [PreferenceManager]), so an
 * extension that reads its settings — `PreferenceManager
 * .getDefaultSharedPreferences(context).getString("quality", "1080p")` — gets
 * its stored value, or its declared default, instead of throwing. There is no
 * Android preference UI on the desktop, so nothing is ever displayed; the
 * values are the part that matters.
 *
 * Type compatibility: extensions are compiled against the real (Java) library,
 * so every signature here is the one their bytecode was linked against — Kotlin
 * `fun setKey(key: String?)` is `setKey(Ljava/lang/String;)V` exactly as the
 * Java originals are, `arrayOf<CharSequence>()` is `[Ljava/lang/CharSequence;`,
 * and boolean properties are the `isX()`/`setX(boolean)` pair AndroidX exposes.
 * The constructor overloads exist because a source may build a preference with
 * `ListPreference(context)` or inflate one with `(context, attrs)`.
 */
open class Preference @JvmOverloads constructor(
    private val ctx: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Comparable<Preference> {

    private var keyValue: String? = null
    private var titleValue: CharSequence? = null
    private var summaryValue: CharSequence? = null
    private var defaultValue: Any? = null
    private var enabledValue = true
    private var visibleValue = true
    private var selectableValue = true
    private var persistentValue = true
    private var orderValue = DEFAULT_ORDER
    private var dependencyValue: String? = null
    private var iconValue: android.graphics.drawable.Drawable? = null
    private var fragmentValue: String? = null
    private var intentValue: android.content.Intent? = null

    @Volatile private var changeListener: OnPreferenceChangeListener? = null
    @Volatile private var clickListener: OnPreferenceClickListener? = null
    private var summaryProvider: SummaryProvider<in Preference>? = null
    private var dataStore: PreferenceDataStore? = null

    /** The manager this preference persists through. AndroidX leaves it null
     *  until the preference is attached to a hierarchy; a preference created
     *  directly by an extension is never attached on the desktop, so it gets one
     *  bound to its own context straight away. That is what makes
     *  `preference.sharedPreferences` work instead of returning null. */
    private val manager: PreferenceManager by lazy { PreferenceManager(ctx) }

    // ── identity ────────────────────────────────────────────────────────────

    open fun getKey(): String? = keyValue

    open fun setKey(key: String?) {
        keyValue = key
    }

    open fun getTitle(): CharSequence? = titleValue

    open fun setTitle(title: CharSequence?) {
        titleValue = title
    }

    open fun setTitle(titleResId: Int) = Unit

    open fun getSummary(): CharSequence? = summaryValue

    open fun setSummary(summary: CharSequence?) {
        summaryValue = summary
    }

    open fun setSummary(summaryResId: Int) = Unit

    open fun getIcon(): android.graphics.drawable.Drawable? = iconValue

    open fun setIcon(icon: android.graphics.drawable.Drawable?) {
        iconValue = icon
    }

    open fun setIcon(iconResId: Int) = Unit

    open fun getOrder(): Int = orderValue

    open fun setOrder(order: Int) {
        orderValue = order
    }

    open fun getDependency(): String? = dependencyValue

    open fun setDependency(dependencyKey: String?) {
        dependencyValue = dependencyKey
    }

    open fun isEnabled(): Boolean = enabledValue

    open fun setEnabled(enabled: Boolean) {
        enabledValue = enabled
    }

    open fun isVisible(): Boolean = visibleValue

    open fun setVisible(visible: Boolean) {
        visibleValue = visible
    }

    open fun isSelectable(): Boolean = selectableValue

    open fun setSelectable(selectable: Boolean) {
        selectableValue = selectable
    }

    open fun isPersistent(): Boolean = persistentValue

    open fun setPersistent(persistent: Boolean) {
        persistentValue = persistent
    }

    /** The value AndroidX shows when nothing has been stored yet. Aniyomi
     *  sources declare their defaults this way (`ListPreference(context).apply {
     *  setDefaultValue("1080p") }`), so it is also what a read finds when the
     *  user has never changed the setting. */
    open fun setDefaultValue(defaultValue: Any?) {
        this.defaultValue = defaultValue
    }

    open fun getDefaultValue(): Any? = defaultValue

    open fun setLayoutResource(layoutResId: Int) = Unit

    open fun setWidgetLayoutResource(widgetLayoutResId: Int) = Unit

    open fun setSingleLineTitle(singleLineTitle: Boolean) = Unit

    open fun setIconSpaceReserved(iconSpaceReserved: Boolean) = Unit

    open fun setFragment(fragment: String?) {
        fragmentValue = fragment
    }

    open fun getFragment(): String? = fragmentValue

    open fun setIntent(intent: android.content.Intent?) {
        intentValue = intent
    }

    open fun getIntent(): android.content.Intent? = intentValue

    open fun getContext(): Context = ctx

    open fun getPreferenceManager(): PreferenceManager = manager

    open fun getSharedPreferences(): SharedPreferences = manager.getSharedPreferences()

    open fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener?) {
        changeListener = listener
    }

    open fun getOnPreferenceChangeListener(): OnPreferenceChangeListener? = changeListener

    open fun setOnPreferenceClickListener(listener: OnPreferenceClickListener?) {
        clickListener = listener
    }

    open fun getOnPreferenceClickListener(): OnPreferenceClickListener? = clickListener

    open fun setSummaryProvider(summaryProvider: SummaryProvider<in Preference>?) {
        this.summaryProvider = summaryProvider
    }

    open fun getSummaryProvider(): SummaryProvider<in Preference>? = summaryProvider

    open fun setPreferenceDataStore(dataStore: PreferenceDataStore?) {
        this.dataStore = dataStore
    }

    open fun getPreferenceDataStore(): PreferenceDataStore? = dataStore

    // ── persistence ─────────────────────────────────────────────────────────
    //
    // What a source's settings are actually made of: it writes its defaults (or
    // the user's choices) through these, and reads them back through the same
    // SharedPreferences. `shouldPersist` matches AndroidX: a preference needs a
    // key, must not have opted out, and must not have been given a data store.

    open fun shouldPersist(): Boolean = persistentValue && keyValue != null && dataStore == null

    protected open fun persistString(value: String?): Boolean =
        persist { e, key -> e.putString(key, value) }

    protected open fun persistInt(value: Int): Boolean = persist { e, key -> e.putInt(key, value) }

    protected open fun persistLong(value: Long): Boolean = persist { e, key -> e.putLong(key, value) }

    protected open fun persistFloat(value: Float): Boolean = persist { e, key -> e.putFloat(key, value) }

    protected open fun persistBoolean(value: Boolean): Boolean =
        persist { e, key -> e.putBoolean(key, value) }

    protected open fun persistStringSet(values: Set<String>?): Boolean =
        persist { e, key -> e.putStringSet(key, values) }

    protected open fun getPersistedString(defaultReturnValue: String?): String? {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getString(key, defaultReturnValue) ?: defaultReturnValue
    }

    protected open fun getPersistedInt(defaultReturnValue: Int): Int {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getInt(key, defaultReturnValue) ?: defaultReturnValue
    }

    protected open fun getPersistedLong(defaultReturnValue: Long): Long {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getLong(key, defaultReturnValue) ?: defaultReturnValue
    }

    protected open fun getPersistedFloat(defaultReturnValue: Float): Float {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getFloat(key, defaultReturnValue) ?: defaultReturnValue
    }

    protected open fun getPersistedBoolean(defaultReturnValue: Boolean): Boolean {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getBoolean(key, defaultReturnValue) ?: defaultReturnValue
    }

    protected open fun getPersistedStringSet(defaultReturnValue: Set<String>?): Set<String>? {
        val key = persistedKey() ?: return defaultReturnValue
        return store()?.getStringSet(key, defaultReturnValue) ?: defaultReturnValue
    }

    private fun persistedKey(): String? = if (shouldPersist()) keyValue else null

    private fun store(): SharedPreferences? =
        runCatching { getSharedPreferences() }.getOrNull()

    /** One write through the preference's SharedPreferences. Returns whether it
     *  landed (AndroidX's `persist*` return value). */
    private inline fun persist(write: (SharedPreferences.Editor, String) -> Unit): Boolean {
        val key = persistedKey() ?: return false
        val prefs = store() ?: return false
        return runCatching {
            val editor = prefs.edit()
            write(editor, key)
            editor.apply()
            true
        }.getOrDefault(false)
    }

    // ── interaction ─────────────────────────────────────────────────────────

    /** Runs the change listener, exactly as AndroidX does before it persists:
     *  `false` means "keep this value out". */
    open fun callChangeListener(newValue: Any?): Boolean =
        changeListener?.onPreferenceChange(this, newValue) ?: true

    open fun performClick() {
        clickListener?.onPreferenceClick(this)
    }

    open fun notifyChanged() = Unit

    open fun notifyDependencyChange(disableDependents: Boolean) = Unit

    open fun onDependencyChanged(dependency: Preference?, disableDependent: Boolean) = Unit

    open fun onParentChanged(parent: Preference?, disableChild: Boolean) = Unit

    open fun shouldDisableDependents(): Boolean = !isEnabled()

    open fun onAttached() = Unit

    open fun onDetached() = Unit

    open fun onAttachedToHierarchy(preferenceManager: PreferenceManager?) = Unit

    open fun restoreHierarchyState(container: android.os.Bundle?) = Unit

    open fun saveHierarchyState(container: android.os.Bundle?) = Unit

    open fun onSaveInstanceState(): android.os.Bundle = android.os.Bundle()

    open fun onRestoreInstanceState(state: android.os.Bundle?) = Unit

    override fun compareTo(other: Preference): Int {
        val mine = orderValue
        val theirs = other.orderValue
        if (mine != theirs) return if (mine < theirs) -1 else 1
        return 0
    }

    override fun toString(): String = "Preference(${keyValue ?: "?"})"

    /** Whether the user has changed this preference (AndroidX's
     *  `onSetInitialValue`/`onGetDefaultValue` pair, reduced to what sources
     *  use). */
    open fun isStored(): Boolean {
        val key = keyValue ?: return false
        return runCatching { store()?.contains(key) }.getOrNull() == true
    }

    /** The value to start from: what was stored, else the declared default. */
    protected fun initialString(fallback: String? = null): String? =
        getPersistedString((defaultValue as? String) ?: fallback)

    protected fun initialBoolean(fallback: Boolean = false): Boolean =
        getPersistedBoolean((defaultValue as? Boolean) ?: fallback)

    /** AndroidX's change listener: `onPreferenceChange(preference, newValue)`. */
    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }

    fun interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }

    fun interface SummaryProvider<T : Preference> {
        fun provideSummary(preference: T): CharSequence?
    }

    companion object {
        const val DEFAULT_ORDER = Int.MAX_VALUE
    }
}

/** AndroidX's `PreferenceGroup<T>`: the container a preference screen is. */
open class PreferenceGroup<T : Preference> @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    private val children = ArrayList<T>()
    private var orderingAsAdded = false

    /** Returns whether the preference was added (null and duplicates are
     *  refused, exactly as AndroidX does). */
    open fun addPreference(preference: T): Boolean {
        if (preference.getKey() != null && findPreference(preference.getKey()) != null) return false
        children.add(preference)
        return true
    }

    open fun removePreference(preference: T): Boolean = children.remove(preference)

    open fun removeAll() {
        children.clear()
    }

    open fun getPreferenceCount(): Int = children.size

    open fun getPreference(index: Int): T? = children.getOrNull(index)

    open fun findPreference(key: CharSequence?): Preference? {
        if (key == null) return null
        for (child in children) if (child.getKey() == key.toString()) return child
        return null
    }

    open fun isOrderingAsAdded(): Boolean = orderingAsAdded

    open fun setOrderingAsAdded(orderingAsAdded: Boolean) {
        this.orderingAsAdded = orderingAsAdded
    }

    /** Every preference in the group — what a desktop settings view would walk
     *  if one ever needed to (nothing here is displayed today). */
    open fun preferences(): List<T> = children.toList()
}

/** AndroidX's `PreferenceCategory` (a titled group). */
open class PreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PreferenceGroup<Preference>(context, attrs, defStyleAttr)

/**
 * AndroidX's `PreferenceScreen` — the root of a source's settings, and the ONE
 * type our vendored `ConfigurableAnimeSource` interface names.
 *
 * Hikari has no Android preference UI and never calls
 * `setupPreferenceScreen(screen)`, so a screen is only ever built by an
 * extension that decides to (and then reads the values back through
 * SharedPreferences, which works: see [Preference]).
 */
open class PreferenceScreen @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PreferenceGroup<Preference>(context, attrs, defStyleAttr) {

    private var dialogTitle: CharSequence? = null
    private var dialogMessage: CharSequence? = null

    open fun getDialogTitle(): CharSequence? = dialogTitle

    open fun setDialogTitle(title: CharSequence?) {
        dialogTitle = title
    }

    open fun setDialogTitle(titleResId: Int) = Unit

    open fun getDialogMessage(): CharSequence? = dialogMessage

    open fun setDialogMessage(message: CharSequence?) {
        dialogMessage = message
    }

    open fun setDialogMessage(messageResId: Int) = Unit
}

/** AndroidX's `DialogPreference`: the base of the list/edit-text preferences. */
open class DialogPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    private var dialogTitle: CharSequence? = null
    private var dialogMessage: CharSequence? = null
    private var positiveButtonText: CharSequence? = null
    private var negativeButtonText: CharSequence? = null
    private var dialogLayoutResId = 0

    open fun getDialogTitle(): CharSequence? = dialogTitle

    open fun setDialogTitle(title: CharSequence?) {
        dialogTitle = title
    }

    open fun setDialogTitle(titleResId: Int) = Unit

    open fun getDialogMessage(): CharSequence? = dialogMessage

    open fun setDialogMessage(message: CharSequence?) {
        dialogMessage = message
    }

    open fun setDialogMessage(messageResId: Int) = Unit

    open fun getPositiveButtonText(): CharSequence? = positiveButtonText

    open fun setPositiveButtonText(text: CharSequence?) {
        positiveButtonText = text
    }

    open fun getNegativeButtonText(): CharSequence? = negativeButtonText

    open fun setNegativeButtonText(text: CharSequence?) {
        negativeButtonText = text
    }

    open fun getDialogLayoutResource(): Int = dialogLayoutResId

    open fun setDialogLayoutResource(resId: Int) {
        dialogLayoutResId = resId
    }

    open fun getDialog(): android.app.Dialog? = null

    open fun onActivityDestroy() = Unit
}

/** AndroidX's `TwoStatePreference`: the base of the switch/checkbox types. */
open class TwoStatePreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    private var summaryOn: CharSequence? = null
    private var summaryOff: CharSequence? = null
    private var disableDependentsState = false

    /** Stored value first, declared default next, `false` last — the order a
     *  source's own `isChecked` read depends on. */
    open fun isChecked(): Boolean =
        getPersistedBoolean((getDefaultValue() as? Boolean) ?: false)

    open fun setChecked(checked: Boolean) {
        persistBoolean(checked)
    }

    open fun getSummaryOn(): CharSequence? = summaryOn

    open fun setSummaryOn(summaryOn: CharSequence?) {
        this.summaryOn = summaryOn
    }

    open fun getSummaryOff(): CharSequence? = summaryOff

    open fun setSummaryOff(summaryOff: CharSequence?) {
        this.summaryOff = summaryOff
    }

    open fun getDisableDependentsState(): Boolean = disableDependentsState

    open fun setDisableDependentsState(disableDependentsState: Boolean) {
        this.disableDependentsState = disableDependentsState
    }
}

/** AndroidX's `SwitchPreferenceCompat`. */
open class SwitchPreferenceCompat @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TwoStatePreference(context, attrs, defStyleAttr) {

    private var summaryOnValue: CharSequence? = null
    private var summaryOffValue: CharSequence? = null

    open fun setSwitchTextOn(text: CharSequence?) {
        summaryOnValue = text
    }

    open fun getSwitchTextOn(): CharSequence? = summaryOnValue

    open fun setSwitchTextOff(text: CharSequence?) {
        summaryOffValue = text
    }

    open fun getSwitchTextOff(): CharSequence? = summaryOffValue
}

/** AndroidX's `CheckBoxPreference`. */
open class CheckBoxPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TwoStatePreference(context, attrs, defStyleAttr)

/** AndroidX's `EditTextPreference`. */
open class EditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DialogPreference(context, attrs, defStyleAttr) {

    private var textValue: String? = null
    private var bindListener: OnBindEditTextListener? = null

    /** The stored text, else the declared default — what a source reads back
     *  after the user has typed something. */
    open fun getText(): String? =
        getPersistedString(textValue ?: (getDefaultValue()?.toString()))

    open fun setText(text: String?) {
        textValue = text
        persistString(text)
    }

    open fun setOnBindEditTextListener(listener: OnBindEditTextListener?) {
        bindListener = listener
    }

    open fun getOnBindEditTextListener(): OnBindEditTextListener? = bindListener

    fun interface OnBindEditTextListener {
        fun onBindEditText(editText: android.widget.EditText)
    }
}

/** AndroidX's `SeekBarPreference` (kept for completeness: the same corpus shape). */
open class SeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    private var maxValue = 100
    private var minValue = 0
    private var seekBarValue = 0
    private var showSeekBarValue = true

    open fun getMax(): Int = maxValue

    open fun setMax(max: Int) {
        maxValue = max
    }

    open fun getMin(): Int = minValue

    open fun setMin(min: Int) {
        minValue = min
    }

    open fun getValue(): Int = getPersistedInt(seekBarValue)

    open fun setValue(seekBarValue: Int) {
        this.seekBarValue = seekBarValue
        persistInt(seekBarValue)
    }

    open fun isShowSeekBarValue(): Boolean = showSeekBarValue

    open fun setShowSeekBarValue(showSeekBarValue: Boolean) {
        this.showSeekBarValue = showSeekBarValue
    }
}

/**
 * AndroidX's `ListPreference` — the type an Aniyomi source uses for "which
 * quality/server/domain" settings, and the one with the most member calls in the
 * corpus (`findIndexOfValue`, `getEntryValues`, `setEntries`, `setEntryValues`,
 * `setValue`, `getValue`).
 */
open class ListPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DialogPreference(context, attrs, defStyleAttr) {

    private var entriesValue: Array<CharSequence>? = null
    private var entryValuesValue: Array<CharSequence>? = null
    private var valueValue: String? = null

    open fun getEntries(): Array<CharSequence>? = entriesValue

    open fun setEntries(entries: Array<CharSequence>?) {
        entriesValue = entries
    }

    open fun setEntries(entriesResId: Int) = Unit

    open fun getEntryValues(): Array<CharSequence>? = entryValuesValue

    open fun setEntryValues(entryValues: Array<CharSequence>?) {
        entryValuesValue = entryValues
    }

    open fun setEntryValues(entryValuesResId: Int) = Unit

    /** The stored value, else the declared default. */
    open fun getValue(): String? =
        getPersistedString(valueValue ?: (getDefaultValue()?.toString()))

    open fun setValue(value: String?) {
        valueValue = value
        persistString(value)
    }

    /** The `entries` row matching [value] — AndroidX's `getEntry()`, which is
     *  also the summary when the source set none. */
    open fun getEntry(): CharSequence? {
        val index = findIndexOfValue(getValue())
        return entriesValue?.getOrNull(index)
    }

    /** The index of [value] in `entryValues`, or -1 — AndroidX's method, and
     *  called directly by sources that map a stored key back to its label. */
    open fun findIndexOfValue(value: String?): Int {
        val values = entryValuesValue ?: return -1
        if (value == null) return -1
        for (i in values.indices) if (values[i].toString() == value) return i
        return -1
    }

    override fun getSummary(): CharSequence? =
        super.getSummary() ?: getEntry()
}

/**
 * AndroidX's `MultiSelectListPreference` — several `entryValues` at once (e.g.
 * "which languages to include").
 */
open class MultiSelectListPreference @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DialogPreference(context, attrs, defStyleAttr) {

    private var entriesValue: Array<CharSequence>? = null
    private var entryValuesValue: Array<CharSequence>? = null
    private var valuesValue: Set<String> = emptySet()

    open fun getEntries(): Array<CharSequence>? = entriesValue

    open fun setEntries(entries: Array<CharSequence>?) {
        entriesValue = entries
    }

    open fun setEntries(entriesResId: Int) = Unit

    open fun getEntryValues(): Array<CharSequence>? = entryValuesValue

    open fun setEntryValues(entryValues: Array<CharSequence>?) {
        entryValuesValue = entryValues
    }

    open fun setEntryValues(entryValuesResId: Int) = Unit

    /** The stored selection (androidx hands back a `Set<String>`), else what was
     *  set on this instance, else empty. */
    open fun getValues(): Set<String> = getPersistedStringSet(valuesValue) ?: emptySet()

    open fun setValues(values: Set<String>?) {
        valuesValue = values ?: emptySet()
        persistStringSet(valuesValue)
    }

    /** The `entries` row matching [value], or -1. */
    open fun findIndexOfValue(value: String?): Int {
        val values = entryValuesValue ?: return -1
        if (value == null) return -1
        for (i in values.indices) if (values[i].toString() == value) return i
        return -1
    }
}

/**
 * AndroidX's `PreferenceManager`.
 *
 * [getDefaultSharedPreferences] has to be a real STATIC method: an extension's
 * bytecode calls `invokestatic
 * androidx/preference/PreferenceManager.getDefaultSharedPreferences(Landroid/
 * content/Context;)Landroid/content/SharedPreferences;`, and that is the call
 * almost every source's settings are read through. It returns the same
 * SharedPreferences the rest of the app's Aniyomi layer works with (one JSON
 * file per preference name — see the desktop `SharedPreferences`).
 */
open class PreferenceManager(private val context: Context?) {

    private var sharedPreferencesName: String? = null

    open fun getSharedPreferences(): SharedPreferences {
        val ctx = context ?: return FALLBACK
        val name = sharedPreferencesName ?: defaultName(ctx)
        return runCatching { ctx.getSharedPreferences(name, Context.MODE_PRIVATE) }
            .getOrElse { FALLBACK }
    }

    open fun setSharedPreferencesName(sharedPreferencesName: String?) {
        this.sharedPreferencesName = sharedPreferencesName
    }

    open fun getSharedPreferencesName(): String? = sharedPreferencesName

    open fun setSharedPreferencesMode(sharedPreferencesMode: Int) = Unit

    open fun getPreferenceDataStore(): PreferenceDataStore? = null

    open fun setPreferenceDataStore(dataStore: PreferenceDataStore?) = Unit

    open fun findPreference(key: CharSequence?): Preference? = null

    companion object {

        /** Used only when there is no context at all (never in the app's own
         *  path) so a settings read can never NPE. */
        private val FALLBACK: SharedPreferences =
            SharedPreferences(SharedPreferences.fileFor(java.io.File(System.getProperty("user.home"), ".hikari"), "hikari"))

        private fun defaultName(context: Context): String =
            context.packageName + "_preferences"

        /** AndroidX's static entry point — the call an extension makes. */
        @JvmStatic
        fun getDefaultSharedPreferences(context: Context): SharedPreferences =
            runCatching { context.getSharedPreferences(defaultName(context), Context.MODE_PRIVATE) }
                .getOrElse { FALLBACK }
    }
}

/** AndroidX's `PreferenceDataStore` (a preference store that is not
 *  SharedPreferences). Sources rarely install one; the type exists so a source
 *  that does still links. */
interface PreferenceDataStore {
    fun putString(key: String?, value: String?) = Unit
    fun getString(key: String?, defValue: String?): String? = defValue
    fun putStringSet(key: String?, values: Set<String>?) = Unit
    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
    fun putInt(key: String?, value: Int) = Unit
    fun getInt(key: String?, defValue: Int): Int = defValue
    fun putLong(key: String?, value: Long) = Unit
    fun getLong(key: String?, defValue: Long): Long = defValue
    fun putFloat(key: String?, value: Float) = Unit
    fun getFloat(key: String?, defValue: Float): Float = defValue
    fun putBoolean(key: String?, value: Boolean) = Unit
    fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    fun isDataStoreEnabled(): Boolean = true
}
