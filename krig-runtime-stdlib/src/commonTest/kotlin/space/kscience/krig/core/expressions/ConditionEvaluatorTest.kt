@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.expressions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import space.kscience.krig.api.expressions.And
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.expressions.Comparison
import space.kscience.krig.api.expressions.ComparisonOperation
import space.kscience.krig.api.expressions.Constant
import space.kscience.krig.api.expressions.Not
import space.kscience.krig.api.expressions.Or
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private var contextSeq: Int = 0

@OptIn(ExperimentalCoroutinesApi::class)
class ConditionEvaluatorTest {

    private class FixedValueDevice(private val reading: Double) : AbstractDevice(
        name = "reactor".asName(),
        runtime = DeviceRuntime(Context("condition-fixed-${contextSeq++}")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(MetaConverter.double.convert(reading))

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)

        override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> = emptyFlow()
    }

    private class MissingObservedDevice : AbstractDevice(
        name = "reactor".asName(),
        runtime = DeviceRuntime(Context("condition-missing-${contextSeq++}")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(MetaConverter.double.convert(0.0))

        override suspend fun doReadObservedOutcome(propertyName: Name): OperationOutcome<ObservedValue<Meta?>> =
            OperationOutcome.Ok(
                ObservedValue(value = null, time = clock.now(), quality = DataQuality(QualitySeverity.UNCERTAIN)),
            )

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            OperationOutcome.Ok(null)

        override suspend fun subscribe(principal: Principal): Flow<DeviceMessageFrame<DeviceMessage>> = emptyFlow()
    }

    private fun contextFor(device: AbstractDevice, scope: kotlinx.coroutines.CoroutineScope) =
        ExpressionContext.from(scope = scope, devices = mapOf(device.name to device), principal = AnonymousPrincipal)

    private fun value(device: AbstractDevice) = Binding(device.name, "value".asName())

    @Test
    fun comparisonEvaluatesThreshold() = runTest {
        val device = FixedValueDevice(reading = 5.0)
        val ctx = contextFor(device, backgroundScope)

        val aboveOne = Comparison(ComparisonOperation.GREATER, value(device), Constant(1.0)).compile(ctx)
        val aboveTen = Comparison(ComparisonOperation.GREATER, value(device), Constant(10.0)).compile(ctx)
        advanceUntilIdle()

        assertEquals(true, aboveOne.stateValue.value)
        assertEquals(false, aboveTen.stateValue.value)
    }

    @Test
    fun andShortCircuitsToFalse() = runTest {
        val device = FixedValueDevice(reading = 5.0)
        val ctx = contextFor(device, backgroundScope)

        val condition = And(
            listOf(
                Comparison(ComparisonOperation.GREATER, value(device), Constant(1.0)),
                Comparison(ComparisonOperation.GREATER, value(device), Constant(10.0)),
            ),
        ).compile(ctx)
        advanceUntilIdle()

        assertEquals(false, condition.stateValue.value)
    }

    @Test
    fun orShortCircuitsToTrue() = runTest {
        val device = FixedValueDevice(reading = 5.0)
        val ctx = contextFor(device, backgroundScope)

        val condition = Or(
            listOf(
                Comparison(ComparisonOperation.GREATER, value(device), Constant(10.0)),
                Comparison(ComparisonOperation.GREATER, value(device), Constant(1.0)),
            ),
        ).compile(ctx)
        advanceUntilIdle()

        assertEquals(true, condition.stateValue.value)
    }

    @Test
    fun notInvertsDefiniteValue() = runTest {
        val device = FixedValueDevice(reading = 5.0)
        val ctx = contextFor(device, backgroundScope)

        val condition = Not(
            Comparison(ComparisonOperation.GREATER, value(device), Constant(1.0)),
        ).compile(ctx)
        advanceUntilIdle()

        assertEquals(false, condition.stateValue.value)
    }

    @Test
    fun missingOperandYieldsUnknownWithDegradedQuality() = runTest {
        val device = MissingObservedDevice()
        val ctx = contextFor(device, backgroundScope)

        val condition = Comparison(ComparisonOperation.GREATER, value(device), Constant(1.0)).compile(ctx)
        advanceUntilIdle()

        assertNull(condition.stateValue.value)
        assertEquals(QualitySeverity.UNCERTAIN, condition.stateValue.quality.severity)
    }
}
