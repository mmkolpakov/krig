@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.dsl

import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.krig.core.state.VirtualMutableDeviceState
import space.kscience.krig.core.state.value
import space.kscience.dataforge.context.Context
import kotlin.test.Test
import kotlin.test.assertEquals

private val contextSeq: AtomicInt = AtomicInt(0)
private fun freshContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

/**
 * Tests use a Job-detached scope that shares the runTest scheduler. That keeps
 * `advanceUntilIdle()` progressing launchIn collectors without making them structured
 * children of the TestScope — otherwise runTest tear-down raises JobCancellationException.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceGroupLinkTest {

    private suspend fun linkScope(): CoroutineScope {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
            ?: error("Test scope must provide a dispatcher")
        return CoroutineScope(Job() + dispatcher)
    }

    @Test
    fun linkAppliesThresholdTransform() = runTest {
        val source = VirtualMutableDeviceState(20.0)
        val target = VirtualMutableDeviceState(0.0)
        val scope = linkScope()
        val group = deviceGroup {
            link(source, target) { t: Double -> if (t > 40.0) 100.0 else 0.0 }
        }
        group.start("g", freshContext("link-threshold"), scope).let { }
        advanceUntilIdle()
        assertEquals(0.0, target.value)

        source.update(50.0)
        advanceUntilIdle()
        assertEquals(100.0, target.value)

        source.update(30.0)
        advanceUntilIdle()
        assertEquals(0.0, target.value)

        scope.cancel()
    }

    @Test
    fun linkUnsubscribesOnScopeCancel() = runTest {
        val source = VirtualMutableDeviceState(0.0)
        val target = VirtualMutableDeviceState(0.0)
        val scope = linkScope()
        val group = deviceGroup { link(source, target) { it * 2 } }
        group.start("g", freshContext("link-cancel"), scope).let { }

        source.update(5.0)
        advanceUntilIdle()
        assertEquals(10.0, target.value)

        scope.cancel()
        advanceUntilIdle()

        source.update(100.0)
        advanceUntilIdle()
        assertEquals(10.0, target.value)
    }

    @Test
    fun linkIdentityIsPassthrough() = runTest {
        val source = VirtualMutableDeviceState(0.0)
        val target = VirtualMutableDeviceState(0.0)
        val scope = linkScope()
        val group = deviceGroup { link(source, target) { it } }
        group.start("g", freshContext("link-identity"), scope).let { }

        source.update(42.0)
        advanceUntilIdle()
        assertEquals(42.0, target.value)

        scope.cancel()
    }
}
