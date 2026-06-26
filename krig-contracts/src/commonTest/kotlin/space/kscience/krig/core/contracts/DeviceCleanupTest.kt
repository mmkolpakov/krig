@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DeviceCleanupTest {

    @Test
    fun ignoreCleanupFailureSuspendingReportsTimeout() = runTest {
        val failures = mutableListOf<Exception>()
        CleanupFailureReporting.install { failure -> failures += failure }

        try {
            ignoreCleanupFailureSuspending(timeout = 100.milliseconds) {
                delay(1.seconds)
            }

            assertEquals(100L, currentTime)
            assertIs<CleanupTimeoutException>(failures.single())
        } finally {
            CleanupFailureReporting.install(null)
        }
    }

    @Test
    fun closeDeviceBoundedReportsTimeoutAndFallsBackToClose() = runTest {
        val failures = mutableListOf<Exception>()
        val child = object : AbstractDevice(
            name = "hung-child".asName(),
            runtime = DeviceRuntime(Context("device-cleanup-test")),
        ) {
            var closeCalls = 0

            override fun close() {
                closeCalls++
                super.close()
            }
        }
        CleanupFailureReporting.install { failure -> failures += failure }

        try {
            closeDeviceBounded(child, timeout = 100.milliseconds) {
                delay(1.seconds)
            }

            assertEquals(100L, currentTime)
            assertIs<DeviceShutdownTimeoutException>(failures.single())
            assertEquals(1, child.closeCalls)
        } finally {
            CleanupFailureReporting.install(null)
        }
    }
}
