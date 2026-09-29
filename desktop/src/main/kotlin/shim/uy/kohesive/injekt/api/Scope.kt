@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt.api

import kotlin.reflect.KClass

open class InjektScope(val registrar: InjektRegistrar) : InjektRegistrar by registrar {
    inline fun <reified T : Any> injectLazy(): Lazy<T> {
        return lazy { get(fullType<T>()) }
    }

    inline fun <reified T : Any> injectValue(): Lazy<T> {
        return lazyOf(get(fullType<T>()))
    }

    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
        return lazy { get(fullType<T>(), key) }
    }

    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
        return lazyOf(get(fullType<T>(), key))
    }

    inline fun <reified T : Any, O : Any> injectLogger(forClass: Class<O>): Lazy<T> {
        return lazy { logger(fullType<T>(), forClass) }
    }

    inline fun <reified T : Any, O : Any> injectLogger(forClass: KClass<O>): Lazy<T> {
        return lazy { logger(fullType<T>(), forClass.java) }
    }

    inline fun <reified R : Any, reified T : Any> R.injectLogger(): Lazy<T> {
        return lazy { logger(fullType<T>(), R::class.java) }
    }

    inline fun <reified T : Any> injectLogger(byName: String): Lazy<T> {
        return lazy { logger(fullType<T>(), byName) }
    }

    inline fun <reified R : Any> addScopedSingletonFactory(noinline scopedFactoryCalledOnce: InjektScope.() -> R) {
        addSingletonFactory(fullType<R>()) { this.scopedFactoryCalledOnce() }
    }

    inline fun <reified R : Any> addScopedFactory(noinline scopedFactoryCalledEveryTime: InjektScope.() -> R) {
        addFactory(fullType<R>()) { this.scopedFactoryCalledEveryTime() }
    }

    inline fun <reified R : Any, K : Any> addScopedPerKeyFactory(noinline scopedFactoryCalledPerKey: InjektScope.(key: K) -> R) {
        addPerKeyFactory(fullType<R>()) { key: K -> this.scopedFactoryCalledPerKey(key) }
    }

    inline fun <reified R : Any, K : Any> addScopedPerThreadPerKeyFactory(noinline scopedFactoryCalledPerKeyPerThread: InjektScope.(key: K) -> R) {
        addPerThreadPerKeyFactory(fullType<R>()) { key: K -> this.scopedFactoryCalledPerKeyPerThread(key) }
    }
}
