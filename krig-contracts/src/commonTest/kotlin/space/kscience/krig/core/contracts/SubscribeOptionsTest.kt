@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.descriptors.attributes.DeadbandPolicy
import space.kscience.krig.api.descriptors.attributes.EngineeringRangeAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.operationAttributesOf
import space.kscience.krig.api.descriptors.of
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.TimeSource

private val subscriptionTestProperty = "value".asName()

private class SubscriptionTestDevice(
    descriptor: PropertyDescriptor,
) : Device {
    private val messages = MutableSharedFlow<DeviceMessageFrame<DeviceMessage>>(extraBufferCapacity = 16)

    override val deviceScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
    override val name: Name = "subscription-test".asName()
    override val propertyDescriptors: Map<Name, PropertyDescriptor> = mapOf(descriptor.name to descriptor)
    override val actionDescriptors: Map<Name, ActionDescriptor> = emptyMap()
    override val clock: Clock = Clock.System
    override val timeSource: TimeSource = TimeSource.Monotonic
    override val lifecycleState: LifecycleState = LifecycleState.Running
    override val controlFlow: Flow<DeviceMessageFrame<DeviceMessage>> = emptyFlow()
    override val dataFlow: Flow<DeviceMessageFrame<DeviceMessage>> = messages

    override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> = messageFlow

    override suspend fun subscribe(principal: Principal, property: Name): Flow<DeviceMessageFrame<DeviceMessage>> =
        messageFlow.filter { (it.payload as? PropertyChangedMessage)?.property == property }

    override fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? = null

    override suspend fun readPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun readObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
        okObservedMeta(Meta.EMPTY, clock)

    override suspend fun writePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun executeOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)

    override fun close() {
        deviceScope.cancel()
    }

    suspend fun publish(value: Double) {
        messages.emit(
            DeviceMessageFrame(
                payload = PropertyChangedMessage(
                    time = Clock.System.now(),
                    property = subscriptionTestProperty,
                    value = metaOf(value),
                    sourceDevice = name,
                ),
            ),
        )
    }
}

class SubscribeOptionsTest {
    @Test
    fun absoluteDeadbandSuppressesSmallNumericPropertyChanges() = runTest {
        val device = SubscriptionTestDevice(subscriptionDescriptor())
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            device.valuesWith(SubscribeOptions(deadband = DeadbandPolicy.Absolute(0.5)), count = 3)
        }
        runCurrent()

        listOf(1.0, 1.2, 1.6, 2.0, 2.2).forEach { device.publish(it) }

        assertEquals(listOf(1.0, 1.6, 2.2), received.await())
        device.close()
    }

    @Test
    fun relativeDeadbandUsesDescriptorEngineeringSpan() = runTest {
        val device = SubscriptionTestDevice(
            subscriptionDescriptor(range = EngineeringRangeAttribute(displayMin = 0.0, displayMax = 100.0)),
        )
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            device.valuesWith(SubscribeOptions(deadband = DeadbandPolicy.Relative(0.01)), count = 3)
        }
        runCurrent()

        listOf(10.0, 10.5, 11.2, 11.7, 12.3).forEach { device.publish(it) }

        assertEquals(listOf(10.0, 11.2, 12.3), received.await())
        device.close()
    }

    private suspend fun SubscriptionTestDevice.valuesWith(
        options: SubscribeOptions,
        count: Int,
    ): List<Double?> =
        subscribe(AnonymousPrincipal, subscriptionTestProperty, options)
            .take(count)
            .map { (it.payload as PropertyChangedMessage).value.doubleValue }
            .toList()

    private fun subscriptionDescriptor(
        range: EngineeringRangeAttribute? = null,
    ): PropertyDescriptor = PropertyDescriptor(
        name = subscriptionTestProperty,
        kind = PropertyKind.MEASURED,
        valueTypeId = TypeIds.DOUBLE,
        attributes = range?.let {
            operationAttributesOf(OperationAttributeKeys.EngineeringRange of it)
        } ?: operationAttributesOf(),
    )
}
