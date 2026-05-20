package space.kscience.krig.core.pipeline

import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.meta.DevicePropertyContract

/** Value-substituting wrapper for device reads, e.g. cache hits or mock injection. */
public interface ReadDecorator {
    public fun <T> decorate(
        spec: DevicePropertyContract<T>,
        original: TypedReader<T>,
    ): TypedReader<T>
}
