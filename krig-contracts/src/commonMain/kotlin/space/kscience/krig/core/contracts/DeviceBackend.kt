package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.dataforge.meta.Meta

/**
 * Runtime side of a [Device]: reads hardware, drives a simulation, or forwards to a
 * remote service. Every operation returns a [OperationOutcome] instead of throwing;
 * drivers wrap throwing code with `runCatchingOperation { ... }`.
 *
 * Operations receive [DeviceEnvironment] as context — only the clock, scope and name
 * are visible; the full [Device] hierarchy is not exposed to backend implementations.
 * Backends with native typed data-plane handles additionally implement
 * [space.kscience.krig.core.contracts.typed.TypedBackend].
 */
@MustUseReturnValues
@SubclassOptInRequired(space.kscience.krig.core.UnstableKrigForSubclassing::class)
public interface DeviceBackend : AutoCloseable {

    context(device: DeviceEnvironment)
    public suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta>

    context(device: DeviceEnvironment)
    public suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit>

    context(device: DeviceEnvironment)
    public suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?>

    /** Suspends until backend-owned resources are released. Default delegates to [close]. */
    public suspend fun shutdown() {
        close()
    }

    override fun close()
}
