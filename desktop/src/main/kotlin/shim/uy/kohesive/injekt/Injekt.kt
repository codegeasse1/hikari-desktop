@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.InjektScopedMain
import uy.kohesive.injekt.api.SimpleRegistrar
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.logger
import kotlin.reflect.KClass

@Volatile
var Injekt: InjektScope = InjektScope(SimpleRegistrar())

abstract class InjektMain : InjektScopedMain(Injekt)

inline fun <reified T : Any> injectLazy(): Lazy<T> {
    return lazy { Injekt.get(fullType<T>()) }
}

inline fun <reified T : Any> injectValue(): Lazy<T> {
    return lazyOf(Injekt.get(fullType<T>()))
}

inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
    return lazy { Injekt.get(fullType<T>(), key) }
}

inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
    return lazyOf(Injekt.get(fullType<T>(), key))
}

inline fun <reified R : Any, reified T : Any> R.injectLogger(): Lazy<T> {
    return lazy { Injekt.logger(fullType<T>(), R::class.java) }
}

inline fun <reified T : Any, O : Any> injectLogger(forClass: KClass<O>): Lazy<T> {
    return lazy { Injekt.logger(fullType<T>(), forClass.java) }
}

inline fun <reified T : Any, O : Any> injectLogger(forClass: Class<O>): Lazy<T> {
    return lazy { Injekt.logger(fullType<T>(), forClass) }
}

inline fun <reified T : Any> injectLogger(byName: String): Lazy<T> {
    return lazy { Injekt.logger(fullType<T>(), byName) }
}
