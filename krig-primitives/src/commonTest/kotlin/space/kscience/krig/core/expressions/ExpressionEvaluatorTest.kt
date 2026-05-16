@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.expressions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertNull

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
        override suspend fun readProperty(propertyName: Name): Meta =
            MetaConverter.double.convert(1.0)

        override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
        override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
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
}
