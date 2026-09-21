package uy.kohesive.injekt

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny stand-in for Mihon's Injekt container.
 *
 * Aniyomi extensions reach the host through it: `ConfigurableAnimeSource` asks
 * for the `Application` to get its SharedPreferences, `AnimeHttpSource` injects
 * a `NetworkHelper`, and the JSON helpers ask for a `Json`. Desktop has no
 * reflection-based DI to intercept, so this is a plain registry keyed by type —
 * the same registrations the Android app makes in `HikariApp` are made in the
 * desktop `HikariApp.init()`.
 */
object Injekt {

    @PublishedApi
    internal val factories = ConcurrentHashMap<String, () -> Any>()

    @PublishedApi
    internal fun keyOf(clazz: Class<*>): String = clazz.name

    inline fun <reified T : Any> addSingleton(instance: T) {
        factories[keyOf(T::class.java)] = { instance }
    }

    inline fun <reified T : Any> addSingletonFactory(noinline factory: () -> T) {
        factories[keyOf(T::class.java)] = factory
    }

    inline fun <reified T : Any> get(): T = getOrNull<T>()
        ?: throw IllegalStateException("Injekt: nothing registered for ${T::class.java.name}")

    inline fun <reified T : Any> getOrNull(): T? {
        val factory = factories[keyOf(T::class.java)] ?: return null
        @Suppress("UNCHECKED_CAST")
        return factory() as T
    }
}

/** Lazily resolves [T] on first use, like Injekt's `injectLazy()`. */
inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.get<T>() }
