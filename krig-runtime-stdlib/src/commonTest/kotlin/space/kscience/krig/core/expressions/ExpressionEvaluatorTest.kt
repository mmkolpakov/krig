@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.expressions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.expressions.NAry
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.envelope
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ExpressionEvaluatorTest {
    private class DeniedSubscriptionDevice : AbstractDevice(
        name = "source".asName(),
        runtime = DeviceRuntime(
            Context("expression-denied") {
                plugin(AuthorizationService)
            },
        ),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(MetaConverter.double.convert(1.0))

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
    }

    private class QualityMessageDevice : AbstractDevice(
        name = "source".asName(),
        runtime = DeviceRuntime(Context("expression-quality")),
    ) {
        private val messages = MutableSharedFlow<DeviceMessageEnvelope<DeviceMessage>>(replay = 1)

        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(MetaConverter.double.convert(1.0))

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)
        override suspend fun subscribe(principal: Principal): Flow<DeviceMessageEnvelope<DeviceMessage>> = messages

        suspend fun publish(value: Double, quality: DataQuality) {
            messages.emit(
                PropertyChangedMessage(
                    time = clock.now(),
                    property = "value".asName(),
                    value = MetaConverter.double.convert(value),
                    sourceDevice = name,
                    quality = quality,
                ).envelope()
            )
        }
    }

    private class MissingObservedDevice : AbstractDevice(
        name = "source".asName(),
        runtime = DeviceRuntime(Context("expression-missing")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(MetaConverter.double.convert(1.0))

        override suspend fun doReadObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
            OperationOutcome.Ok(
                ObservedValue(
                    value = null,
                    time = clock.now(),
                    quality = DataQuality(QualitySeverity.UNCERTAIN),
                ),
            )

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)

        override suspend fun subscribe(principal: Principal): Flow<DeviceMessageEnvelope<DeviceMessage>> = emptyFlow()
    }

    @Test
    fun bindingSubscriptionFailureInvalidatesOnlyThatBinding() = runTest {
        val device = DeniedSubscriptionDevice()
        val ctx = ExpressionContext.from(
            scope = this,
            devices = mapOf(device.name to device),
            principal = AnonymousPrincipal,
        )

        val state = Binding(device.name, "value".asName()).compile(ctx)
        advanceUntilIdle()

        assertNull(state.stateValue.value)
    }

    @Test
    fun bindingPreservesPropertyChangedQuality() = runTest {
        val device = QualityMessageDevice()
        val ctx = ExpressionContext.from(
            scope = backgroundScope,
            devices = mapOf(device.name to device),
            principal = AnonymousPrincipal,
        )
        val state = Binding(device.name, "value".asName()).compile(ctx)
        val values = async(start = CoroutineStart.UNDISPATCHED) {
            state.stateFlow.take(2).toList().map { it.quality }
        }
        runCurrent()

        val uncertain = DataQuality(QualitySeverity.UNCERTAIN)
        device.publish(2.0, uncertain)
        advanceUntilIdle()

        assertEquals(listOf(DataQuality.GOOD, uncertain), withTimeout(1.seconds) { values.await() })
    }

    @Test
    fun naryExpressionMarksMissingOperandAsBadQuality() = runTest {
        val device = MissingObservedDevice()
        val ctx = ExpressionContext.from(
            scope = backgroundScope,
            devices = mapOf(device.name to device),
            principal = AnonymousPrincipal,
        )

        val state = NAry("sum", listOf(Binding(device.name, "value".asName()))).compile(ctx)

        assertNull(state.stateValue.value)
        assertEquals(QualitySeverity.BAD, state.stateValue.quality.severity)
    }
}
