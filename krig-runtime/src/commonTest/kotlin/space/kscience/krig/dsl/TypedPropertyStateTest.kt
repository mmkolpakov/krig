@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package space.kscience.krig.dsl

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.meta.DevicePropertyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

@OptIn(ExperimentalAtomicApi::class)
private val configContextSeq: AtomicInt = AtomicInt(0)

@OptIn(ExperimentalAtomicApi::class)
private fun nextConfigContextName(): String = "config-state-test-${configContextSeq.addAndFetch(1)}"

private class ConfigDevice : AbstractDevice(
    name = "config".asName(),
    runtime = DeviceRuntime(Context(nextConfigContextName())),
) {
    private var current: Double = 1.0

    // Controllable change stream (replay = 1) so the test drives delivery deterministically,
    // without depending on the cross-dispatcher message pump.
    private val changes = MutableSharedFlow<DeviceMessageFrame<DeviceMessage>>(replay = 1)

    val rateSpec: DevicePropertyContract<Double> = object : DevicePropertyContract<Double> {
        override val name: Name = "rate".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.LOGICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = MetaConverter.double
    }

    override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> = changes
    override suspend fun subscribe(principal: Principal, property: Name): Flow<DeviceMessageFrame<DeviceMessage>> =
        changes

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta(current))

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> {
        current = value.double ?: current
        return OperationOutcome.OkUnit
    }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)

    /** Emits a property change on the (controlled) stream the fallback projection observes. */
    suspend fun pushChange(value: Double) {
        current = value
        changes.emit(
            PropertyChangedMessage(
                time = Clock.System.now(),
                property = rateSpec.name,
                value = Meta(value),
                sourceDevice = name,
            ).frame(),
        )
    }
}

/** Driver that keeps a native observable value, overriding [propertyState] (the member contract). */
private class NativeStateDevice : AbstractDevice(
    name = "native".asName(),
    runtime = DeviceRuntime(Context(nextConfigContextName())),
) {
    private val rate = MutableStateFlow(10.0)

    val rateSpec: DevicePropertyContract<Double> = object : DevicePropertyContract<Double> {
        override val name: Name = "rate".asName()
        override val descriptor: PropertyDescriptor =
            PropertyDescriptor(name = name, kind = PropertyKind.LOGICAL, valueTypeId = TypeIds.DOUBLE)
        override val converter: MetaConverter<Double> = MetaConverter.double
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> propertyState(spec: DevicePropertyContract<T>): StateFlow<T>? =
        if (spec === rateSpec) rate as StateFlow<T> else null

    override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> = messageFlow
    override suspend fun subscribe(principal: Principal, property: Name): Flow<DeviceMessageFrame<DeviceMessage>> =
        messageFlow

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta(rate.value))

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> {
        rate.value = value.double ?: rate.value
        return OperationOutcome.OkUnit
    }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)

    /** Reconfiguration through the journaled write path; updates the native state. */
    suspend fun tune(value: Double) {
        writeProperty(rateSpec.name, Meta(value))
    }
}

class TypedPropertyStateTest {

    @Test
    fun metaFallbackStateSeedsCurrentValueThenTracksChanges() = runTest {
        val device = ConfigDevice()

        // No native propertyState override -> the accessor projects from read(spec) (seed) + the
        // principal-gated change stream (live updates).
        val state = device.typedPropertyState(AnonymousPrincipal, device.rateSpec, scope = backgroundScope)
        assertEquals(1.0, state.value, "Meta-fallback state seeds the current value from read(spec)")

        device.pushChange(50.0)
        assertEquals(50.0, state.first { it == 50.0 }, "Meta-fallback state tracks the change stream")

        device.close()
    }

    @Test
    fun nativePropertyStateIsReturnedAtomically() = runTest {
        val device = NativeStateDevice()

        val state = device.typedPropertyState(AnonymousPrincipal, device.rateSpec, scope = backgroundScope)
        assertEquals(10.0, state.value, "native state exposes the current value synchronously")

        device.tune(33.0)
        assertEquals(33.0, state.first { it == 33.0 }, "native state tracks writes with no read/changes stitching")
    }
}
