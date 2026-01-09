package space.kscience.controls.core.state

import kotlinx.coroutines.*
import space.kscience.controls.api.data.StateValue
import space.kscience.controls.api.structure.PropertyDescriptor
import space.kscience.controls.common.tokens.PropertyToken
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_BOOLEAN
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_DOUBLE
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_LONG
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_META
import space.kscience.controls.core.InternalControlsApi
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.Goal
import space.kscience.dataforge.meta.Meta
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A proxy object that adapts a raw [PropertyToken] in the registry to the high-level [Data] interface.
 *
 * This implementation treats the device property as a **Volatile Goal**:
 * 1. It does not maintain internal dependencies (`dependencies` is empty).
 * 2. It does not cache the result permanently. Accessing [async] spawns a new read operation
 *    representing the state *at that moment*.
 *
 * This allows external systems (UI, DataForge Workspace) to treat device properties as standard
 * DataForge data sources, while internally fetching values from the high-performance atomic registry.
 *
 * @param T The type of the property value.
 */
@InternalControlsApi
public class PropertyDataProxy<T>(
    private val registry: PropertyRegistry,
    private val token: PropertyToken,
    public val descriptor: PropertyDescriptor
) : Data<T> {

    /**
     * The runtime type of the data, derived from the token type.
     */
    override val type: KType = when (token.typeOrdinal) {
        TYPE_DOUBLE -> typeOf<Double>()
        TYPE_LONG -> typeOf<Long>()
        TYPE_BOOLEAN -> typeOf<Boolean>()
        TYPE_META -> typeOf<Meta>()
        else -> typeOf<Any?>()
    }

    /**
     * The static metadata of the property (from the blueprint descriptor).
     */
    override val meta: Meta get() = descriptor.attributes

    /**
     * Device properties are atomic sources, they do not depend on other [Goal]s
     * in the DataForge sense (dependencies are managed by the runtime/FSM, not the Data graph).
     */
    override val dependencies: Iterable<Goal<*>> get() = emptyList()

    /**
     * Always returns `null` to indicate that there is no cached/completed calculation.
     * This forces consumers (like the `await()` extension) to call [async] to retrieve the fresh value.
     *
     * In the context of a live device property, the "Goal" is never truly "Reached" because
     * the value can change externally. Thus, we behave as a continuously pending source
     * that resolves immediately upon request.
     */
    override val deferred: Deferred<T>? get() = null

    /**
     * Launches a coroutine to read the current consistent snapshot of the property.
     *
     * Uses [CoroutineStart.UNDISPATCHED] to attempt to read the value in the current thread
     * context immediately (Optimistic Locking in [PropertyRegistry] allows this), minimizing
     * context switching overhead for the fast path.
     */
    override fun async(coroutineScope: CoroutineScope): Deferred<T> {
        return coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            val snapshot: StateValue<T> = registry.readSnapshot(token)
            snapshot.value
        }
    }

    /**
     * Resets the goal. For a volatile property proxy, this is a no-op,
     * as we don't cache [deferred] anyway.
     */
    override fun reset() {
        // No-op: State is held in Registry, not here.
    }

    /**
     * Retrieves the full state snapshot (Value + Timestamp + Quality).
     * This is a specialized API for consumers who need observability metadata,
     * bypassing the standard [Data] contract which only returns the value.
     */
    public suspend fun readSnapshot(): StateValue<T> {
        return registry.readSnapshot(token)
    }

    override fun toString(): String {
        return "PropertyDataProxy(name='${descriptor.name}', token=$token)"
    }
}