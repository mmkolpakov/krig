@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.api.data.HlcTimestamp
import space.kscience.krig.core.operations.HybridLogicalClock
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class ConcurrentStampedDevice(
    name: String,
    runtime: DeviceRuntime,
) : AbstractDevice(name.asName(), runtime) {
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        runCatchingOperation { error("not used: $propertyName") }

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        runCatchingOperation { error("not used: $propertyName = $value") }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        runCatchingOperation { error("not used: $actionName($argument)") }

    suspend fun publish(seq: Int) {
        emit(
            PropertyChangedMessage(
                time = clock.now(),
                property = "p".asName(),
                value = Meta { "value".asName() put seq },
                sourceDevice = name,
            ),
        )
    }
}

/**
 * Verifies that under multi-thread concurrency the HLC stamp order observed by a
 * subscriber matches the order in which `emit()` calls completed. Without the
 * `(tick + flow.emit)` serialisation in [AbstractDevice.emit] two parallel emits
 * can stamp in one order and reach the flow in the opposite — breaking HLC's
 * causal-order contract.
 */
class HlcStampingConcurrencyTest {

    @Test
    fun stampsAreMonotonicUnderConcurrentEmits() = runBlocking(Dispatchers.Default) {
        val emitters = 8
        val perEmitter = 200
        val total = emitters * perEmitter

        // Use a runtime with a buffer big enough to hold every message — the data
        // plane drops on overflow (replay=0 + DROP_OLDEST/SUSPEND), so we need
        // headroom for the collector to drain at its own pace.
        val runtime = DeviceRuntime(
            context = Context("hlc-conc-1"),
            hlc = HybridLogicalClock(),
            messaging = DeviceMessaging(
                replay = 0,
                dataBufferCapacity = total + 64,
                controlBufferCapacity = 16,
            ),
        )
        val device = ConcurrentStampedDevice("d", runtime)

        coroutineScope {
            // UNDISPATCHED: collector subscribes synchronously before we kick off emitters.
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                device.dataFlow.take(total).toList()
            }

            // Fan out emitters across the worker pool.
            val emitJobs = List(emitters) { e ->
                launch {
                    repeat(perEmitter) { i -> device.publish(e * perEmitter + i) }
                }
            }
            emitJobs.joinAll()

            val messages = withTimeout(30.seconds) { collected.await() }
            assertEquals(total, messages.size, "every emit must reach the subscriber")

            val stamps: List<HlcTimestamp> = messages.map {
                require(it.payload is PropertyChangedMessage)
                it.context.hlcTimestamp ?: error("HLC was configured but stamp missing")
            }
            for (i in 1 until stamps.size) {
                assertTrue(
                    stamps[i] > stamps[i - 1],
                    "monotonicity violated at index $i: ${stamps[i - 1]} -> ${stamps[i]}",
                )
            }
        }
    }
}
