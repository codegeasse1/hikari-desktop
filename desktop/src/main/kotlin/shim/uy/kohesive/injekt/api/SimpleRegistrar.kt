package uy.kohesive.injekt.api

import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

class SimpleRegistrar : InjektRegistrar {
    private val factories = ConcurrentHashMap<String, () -> Any>()
    private val keyedFactories = ConcurrentHashMap<String, (Any?) -> Any>()
    private val loggerByName = ConcurrentHashMap<String, (String) -> Any>()
    private val loggerByClass = ConcurrentHashMap<String, (Class<Any>) -> Any>()

    private fun keyOf(type: Type): String = (type as? Class<*>)?.name ?: type.toString()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R =
        getInstanceOrNull<R>(forType) ?: throw InjektionException("Nothing registered for " + keyOf(forType))

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? {
        val factory = factories[keyOf(forType)] ?: return null
        return factory() as R
    }

    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R =
        getInstanceOrNull<R>(forType) ?: default

    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R =
        getInstanceOrNull<R>(forType) ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R =
        getKeyedInstanceOrNull<R, K>(forType, key)
            ?: throw InjektionException("Nothing registered for " + keyOf(forType) + " with key " + key)

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? {
        val factory = keyedFactories[keyOf(forType)] ?: return null
        return factory(key) as R
    }

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R =
        getKeyedInstanceOrNull<R, K>(forType, key) ?: default

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R =
        getKeyedInstanceOrNull<R, K>(forType, key) ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getLogger(expectedLoggerType: Type, byName: String): R =
        loggerByName[keyOf(expectedLoggerType)]?.invoke(byName) as R?
            ?: throw InjektionException("No logger factory registered for " + keyOf(expectedLoggerType))

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, T : Any> getLogger(expectedLoggerType: Type, forClass: Class<T>): R =
        loggerByClass[keyOf(expectedLoggerType)]?.invoke(forClass as Class<Any>) as R?
            ?: throw InjektionException("No logger factory registered for " + keyOf(expectedLoggerType))

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        factories[keyOf(forType.type)] = { singleInstance }
    }

    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) {
        val once = lazy(factoryCalledOnce)
        factories[keyOf(forType.type)] = { once.value }
    }

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) {
        factories[keyOf(forType.type)] = factoryCalledEveryTime
    }

    override fun <R : Any> addPerThreadFactory(forType: TypeReference<R>, factoryCalledOncePerThread: () -> R) {
        val local = ThreadLocal.withInitial { factoryCalledOncePerThread() }
        factories[keyOf(forType.type)] = { local.get() }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKey: (K) -> R) {
        keyedFactories[keyOf(forType.type)] = { key -> factoryCalledPerKey(key as K) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerThreadPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKeyPerThread: (K) -> R) {
        val local = ThreadLocal.withInitial { ConcurrentHashMap<String, Any>() }
        keyedFactories[keyOf(forType.type)] = { key ->
            local.get().computeIfAbsent(key.toString()) { factoryCalledPerKeyPerThread(key as K) as Any }
        }
    }

    override fun <R : Any> addLoggerFactory(forLoggerType: TypeReference<R>, factoryByName: (String) -> R, factoryByClass: (Class<Any>) -> R) {
        loggerByName[keyOf(forLoggerType.type)] = factoryByName
        loggerByClass[keyOf(forLoggerType.type)] = factoryByClass
    }

    override fun <O : Any, T : O> addAlias(existingRegisteredType: TypeReference<T>, otherAncestorOrInterface: TypeReference<O>) {
        val key = keyOf(existingRegisteredType.type)
        val target = factories[key] ?: keyedFactories[key]?.let { kf -> { kf(null) } }
            ?: throw InjektionException("Cannot alias unregistered type " + key)
        factories[keyOf(otherAncestorOrInterface.type)] = target
    }

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean {
        val key = keyOf(forType.type)
        return factories.containsKey(key) || keyedFactories.containsKey(key)
    }
}
