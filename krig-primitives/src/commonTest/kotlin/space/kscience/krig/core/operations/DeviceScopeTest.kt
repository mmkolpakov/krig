package space.kscience.krig.core.operations

import kotlinx.coroutines.Job
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceScopeTest {
    @Test
    fun deviceScopeFollowsParentCancellation() {
        val parent = Job()
        val scope = deviceScope(EmptyCoroutineContext + parent)

        parent.cancel()

        assertTrue(scope.coroutineContext[Job]?.isCancelled == true)
    }
}
