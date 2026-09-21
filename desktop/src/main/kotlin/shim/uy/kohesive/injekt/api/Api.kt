package uy.kohesive.injekt.api

/** The `import uy.kohesive.injekt.api.get` form some extension code uses. */
inline fun <reified T : Any> get(): T = uy.kohesive.injekt.Injekt.get()
