package androidx.preference

/**
 * Desktop stand-in for `androidx.preference.PreferenceScreen`.
 *
 * Aniyomi's `ConfigurableAnimeSource` declares
 * `setupPreferenceScreen(screen: PreferenceScreen)`, and on Android that screen
 * is the real androidx preference UI an extension fills with its settings
 * widgets. Hikari has no Android preference UI, and it never calls
 * `setupPreferenceScreen` (there is no per-source settings screen to build), so
 * the type only has to EXIST for the vendored interfaces to compile — no
 * extension code ever touches an instance. Extensions are compiled against the
 * real class, so nothing here needs to be call-compatible.
 */
open class PreferenceScreen
