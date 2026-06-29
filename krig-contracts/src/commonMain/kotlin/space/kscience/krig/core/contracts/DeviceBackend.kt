package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation

/**
 * Runtime side of a [Device]: hardware driver, simulation backend, or remote adapter.
 *
 * [DeviceBackend] is intentionally unbound: it can be constructed before a concrete device name,
 * clock, or DataForge context exists. [BackendDevice] binds it to a [BackendEnvironment] exactly
 * once during device materialization and then invokes [BoundDeviceBackend] operations.
 */
@SubclassOptInRequired(space.kscience.krig.core.UnstableKrigForSubclassing::class)
public interface DeviceBackend : AutoCloseable {
    public fun bind(environment: BackendEnvironment): BoundDeviceBackend

    /** Releases backend-factory resources. Bound operation resources should be released by the bound backend. */
    override fun close() {}
}

/**
 * Device-bound backend operation surface.
 *
 * Single operations are raw and may throw [OperationFaultException] for predictable protocol/domain
 * faults. The owning [Device] converts them into [OperationOutcome.Fail] at its public boundary.
 * Batch operations keep per-property outcomes because partial failures are part of their contract.
 */
@MustUseReturnValues
public interface BoundDeviceBackend : AutoCloseable {
    public val environment: BackendEnvironment

    public suspend fun read(property: PropertyDescriptor): Meta

    /**
     * Reads a property together with measurement quality.
     *
     * Default preserves the plain Meta read path and marks a successful transport read as GOOD.
     * Protocol integrations that can surface sensor/protocol status codes override this method.
     */
    public suspend fun readObserved(property: PropertyDescriptor): ObservedValue<Meta?> =
        ObservedValue(value = read(property), time = environment.clock.now(), quality = DataQuality.GOOD)

    /**
     * Reads several properties as one acquisition unit when the backend can coalesce them.
     *
     * The default falls back to sequential [readObserved] calls and converts predictable single-member
     * faults into per-property failures.
     */
    public suspend fun readBatchObserved(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> =
        properties.associate { property ->
            property.name to runCatchingOperation { readObserved(property) }
        }

    /** Reads opaque binary payload without forcing it through a Meta tree. */
    public suspend fun readBinary(property: PropertyDescriptor): Binary =
        unsupportedBackendOperation("Backend does not support binary read for property '${property.name}'.")

    /** Reads several opaque binary payloads. Default is sequential and non-coalescing. */
    public suspend fun readBatchBinary(
        properties: Collection<PropertyDescriptor>,
    ): Map<Name, OperationOutcome<Binary>> =
        properties.associate { property ->
            property.name to runCatchingOperation { readBinary(property) }
        }

    public suspend fun write(property: PropertyDescriptor, value: Meta)

    /**
     * Writes several Meta values. Default is sequential and not atomic; protocol backends override
     * this for one physical transaction such as Modbus FC 15/16.
     */
    public suspend fun writeBatch(
        values: Map<PropertyDescriptor, Meta>,
    ): Map<Name, OperationOutcome<Unit>> =
        values.entries.associate { (property, value) ->
            property.name to runCatchingOperation { write(property, value) }
        }

    /** Writes opaque binary payload without forcing it through Meta. */
    public suspend fun writeBinary(property: PropertyDescriptor, value: Binary): Unit =
        unsupportedBackendOperation("Backend does not support binary write for property '${property.name}'.")

    public suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta?

    /**
     * Negotiates source-side subscription shaping for [property]. Source-capable backends (OPC UA
     * `MonitoredItem`, PLC) sample/queue at the source and return the actually-applied parameters.
     */
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

private fun unsupportedBackendOperation(message: String): Nothing =
    throw OperationFaultException(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnsupportedValue,
            message = message,
        ),
    )
