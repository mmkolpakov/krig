package space.kscience.krig.core.operations

import kotlinx.coroutines.Job
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceScopeTest {
    @Test
    fun deviceScopeFollowsParentCancellation() {
        val parent = Job()
        val scope = deviceScope(EmptyCoroutineContext + parent)

        parent.cancel()

        assertEquals(true, scope.coroutineContext[Job]?.isCancelled)
    }
}
