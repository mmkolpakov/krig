package space.kscience.krig.core.pipeline

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.dataforge.names.Name

/**
 * Compile-once memoization of typed readers, writers, and actions, keyed by operation [Name].
 *
 * One lock guards three slot maps; each slot compiles lazily on first access and is reused
 * forever. Descriptor/converter compatibility is enforced by the cached value, not here.
 */
internal class CompiledOperationCache {
    private val lock = SynchronizedObject()
    private val readers = mutableMapOf<Name, Lazy<CachedReader>>()
    private val writers = mutableMapOf<Name, Lazy<CachedWriter>>()
    private val actions = mutableMapOf<Name, Lazy<CachedAction>>()

    fun reader(name: Name, compile: () -> CachedReader): CachedReader = memoize(readers, name, compile)

    fun writer(name: Name, compile: () -> CachedWriter): CachedWriter = memoize(writers, name, compile)

    fun action(name: Name, compile: () -> CachedAction): CachedAction = memoize(actions, name, compile)

    private fun <V> memoize(cache: MutableMap<Name, Lazy<V>>, name: Name, compile: () -> V): V {
        val slot = synchronized(lock) {
            cache.getOrPut(name) { lazy(LazyThreadSafetyMode.SYNCHRONIZED) { compile() } }
        }
        return slot.value
    }
}
