package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.map

/**
 * Runtime side of a [Device]: reads hardware, drives a simulation, or forwards to a
 * remote service. Every operation returns a [OperationOutcome] instead of throwing;
 * drivers wrap throwing code with `runCatchingOperation { ... }`.
 *
 * Operations receive [DeviceEnvironment] as context — the current operation environment,
 * not an application service locator. Application services are requested from the
 * DataForge Context when wiring the device.
 * Backends with native typed data-plane handles additionally implement
 * [space.kscience.krig.core.contracts.typed.TypedBackend].
 */
@MustUseReturnValues
@SubclassOptInRequired(space.kscience.krig.core.UnstableKrigForSubclassing::class)
public interface DeviceBackend : AutoCloseable {

    context(env: DeviceEnvironment)
    public suspend fun read(property: PropertyDescriptor): OperationOutcome<Meta>

    /**
     * Reads a property together with measurement quality.
     *
     * Default preserves the plain Meta read path and marks a successful transport read as GOOD.
     * Protocol integrations that can surface sensor/protocol status codes override this
     * method so acquisition and expressions do not overwrite UNCERTAIN/BAD observations.
     */
    context(env: DeviceEnvironment)
    public suspend fun readObserved(property: PropertyDescriptor): OperationOutcome<ObservedValue<Meta?>> =
        read(property).map { value ->
            ObservedValue(value = value, time = env.clock.now(), quality = DataQuality.GOOD)
        }

    /**
     * Reads several properties as one acquisition unit when the backend can coalesce them.
     *
     * The default falls back to sequential [readObserved] calls. Batch-capable backends
     * override this directly to preserve native quality information for protocols such
     * as OPC UA or Modbus block reads.
     */
    context(env: DeviceEnvironment)
    public suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        properties.associate { property ->
            property.name to readObserved(property)
        }

    /** Reads opaque binary payload without forcing it through a Meta tree. */
    context(env: DeviceEnvironment)
    public suspend fun readBinary(property: PropertyDescriptor): OperationOutcome<Binary> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Backend does not support binary read for property '${property.name}'.",
            ),
        )

    /** Reads several opaque binary payloads. Default is sequential and non-coalescing. */
    context(env: DeviceEnvironment)
    public suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> =
        properties.associate { property ->
            property.name to readBinary(property)
        }

    context(env: DeviceEnvironment)
    public suspend fun write(property: PropertyDescriptor, value: Meta): OperationOutcome<Unit>

    /**
     * Writes several Meta values. Default is sequential and not atomic; protocol backends
     * override this for one physical transaction such as Modbus FC 15/16.
     *
     * If a physical transaction fails before per-property statuses are known, return the
     * same [OperationOutcome.Fail] for every requested property.
     */
    context(env: DeviceEnvironment)
    public suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        values.entries.associate { (property, value) ->
            property.name to write(property, value)
        }

    /** Writes opaque binary payload without forcing it through Meta. */
    context(env: DeviceEnvironment)
    public suspend fun writeBinary(property: PropertyDescriptor, value: Binary): OperationOutcome<Unit> =
        OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Backend does not support binary write for property '${property.name}'.",
            ),
        )

    context(env: DeviceEnvironment)
    public suspend fun execute(action: ActionDescriptor, argument: Meta?): OperationOutcome<Meta?>

    /**
     * Negotiates source-side subscription shaping for [property]. Source-capable backends (OPC UA
     * `MonitoredItem`, ПЛК) sample/queue at the source and return the actually-applied parameters
     * (revised rate / queue), mirroring OPC UA's `revisedSamplingInterval`. The default applies
     * nothing at the source ([AppliedSubscribeOptions.ClientSide]); the SDK then shapes the stream
     * client-side from the requested [SubscribeOptions]. Declared in the contract so adding
     * source-side shaping to a driver is not a breaking signature change after 1.0.
     */
    context(env: DeviceEnvironment)
    public suspend fun applySubscribeOptions(
        property: PropertyDescriptor,
        options: SubscribeOptions,
    ): AppliedSubscribeOptions = AppliedSubscribeOptions.ClientSide

    /** Suspends until backend-owned resources are released. Default delegates to [close]. */
    public suspend fun shutdown() {
        close()
    }

    override fun close()
}
