@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.runtime

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val contextSeq: AtomicInt = AtomicInt(0)
private fun freshContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

/**
 * Reproduces the close/attach race: if attach() wins CAS after
 * close()'s `store(emptyMap)`, the device would leak inside a closed hub. After the fix,
 * mutations after close() throw [HubClosedException] and never land a child.
 */
class MutableDeviceHubCloseRaceTest {

    private class LeafDevice(name: Name) : AbstractDevice(name, DeviceRuntime(freshContext(name.toString()))) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            runCatchingOperation { error("not used: $propertyName") }

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            runCatchingOperation { error("not used: $propertyName = $value") }

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            runCatchingOperation { error("not used: $actionName($argument)") }
    }

    @Test
    fun attachAfterCloseIsRejected() = runBlocking {
        val hub = MutableDeviceHub("hub".asName(), freshContext("close-race"))
        hub.close()
        assertFailsWith<HubClosedException> {
            hub.attach("a".asName(), LeafDevice("a".asName()))
        }
        assertTrue(hub.devices.isEmpty())
    }

    @Test
    fun concurrentAttachAndCloseNeverLandsOrphanedChild() = runBlocking {
        // Repeat the race scenario N times to catch any window through which a mutation
        // could slip after close(). Since the CAS loop re-checks `closed` after the swap,
        // either the hub ends empty OR attach observes the closed flag and throws.
        repeat(50) { iter ->
            val hub = MutableDeviceHub("hub-$iter".asName(), freshContext("race"))
            val winners = AtomicInt(0)
            supervisorScope {
                val closer = async(Dispatchers.Default) { hub.close() }
                val attachers = (0 until 32).map { i ->
                    async(Dispatchers.Default) {
                        runCatching {
                            hub.attach("d$i".asName(), LeafDevice("d$i-$iter".asName()))
                        }.onSuccess { winners.addAndFetch(1).let { } }
                    }
                }
                (listOf(closer) + attachers).awaitAll()
            }
            // After close, the hub must contain zero children regardless of which attachers
            // "won" their CAS — the rollback path cleans up any slippage.
            assertTrue(
                hub.devices.isEmpty(),
                "iter $iter: hub.devices=${hub.devices.keys} after close (winners=${winners.load()})",
            )
        }
    }

    @Test
    fun slowHubEventSubscriberDoesNotRejectTopologyMutations() = runBlocking {
        val hub = MutableDeviceHub("hub".asName(), freshContext("hub-events"))
        val slowCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            hub.hubEvents.collect { delay(1.seconds) }
        }

        repeat(1200) { index ->
            hub.attach("d$index".asName(), LeafDevice("d$index".asName()))
        }

        assertEquals(1200, hub.devices.size)
        slowCollector.cancel()
        hub.shutdown()
    }
}
