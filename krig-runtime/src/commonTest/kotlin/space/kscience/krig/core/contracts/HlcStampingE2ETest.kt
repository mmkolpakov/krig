@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.contracts

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.operations.HlcTimestamp
import space.kscience.krig.core.operations.HybridLogicalClock
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.set
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val hlcContextSeq: AtomicInt = AtomicInt(0)

/**
 * Manual `Clock` whose `now()` walks a script. After exhausting the script the last
 * value is repeated indefinitely so callers that need extra reads (e.g. the protected
 * [AbstractDevice.emit] helper, which itself reads physical time when stamping) don't
 * crash. Mirror of the helper in `HybridLogicalClockTest`.
 */
private class ScriptedClock(private val millis: List<Long>) : Clock {
    private var index = 0
    override fun now(): Instant {
        val v = millis[minOf(index, millis.lastIndex)]
        index = (index + 1).coerceAtMost(millis.size)
        return Instant.fromEpochMilliseconds(v)
    }
}

/**
 * Test device that exposes [emit] publicly and ships a `PropertyChangedMessage` on demand.
 * Lets us drive the [AbstractDevice] hot path without going through the pipeline.
 */
private class StampedTestDevice(
    name: String,
    runtime: DeviceRuntime,
) : AbstractDevice(name.asName(), runtime) {
    override suspend fun readProperty(propertyName: Name): Meta = Meta { set("value", 0) }
    override suspend fun writeProperty(propertyName: Name, value: Meta) {}
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null

    suspend fun publishChange(propertyName: Name, value: Meta) {
        emit(
            PropertyChangedMessage(
                time = clock.now(),
                property = propertyName,
                value = value,
                sourceDevice = Address(route = Name.EMPTY, device = name),
            ),
        )
    }
}

class HlcStampingE2ETest {

    private fun freshContext(): Context = Context("hlc-${hlcContextSeq.addAndFetch(1)}")

    /**
     * Subscribes to [device].dataFlow with `start = UNDISPATCHED`, which runs the
     * collector synchronously until its first suspension — i.e. until the
     * `MutableSharedFlow` subscription is registered. Only after that do we emit, so
     * the replay-0 data plane never races. Inside `runTest`'s deterministic
     * dispatcher this is reliable.
     */
    private suspend fun collectFirstFromDataFlow(device: StampedTestDevice): DeviceMessage =
        coroutineScope {
            val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                device.dataFlow.first()
            }
            device.publishChange("p".asName(), Meta { set("value", 1.0) })
            awaited.await()
        }

    @Test
    fun emittedMessageHasNullHlcStampWhenRuntimeHasNoHlc() = runTest {
        val device = StampedTestDevice("d", DeviceRuntime(freshContext()))
        val msg = collectFirstFromDataFlow(device)
        assertTrue(msg is PropertyChangedMessage)
        assertNull(
            msg.hlcTimestamp,
            "When runtime.hlc is null the emit() helper must leave hlcTimestamp as null.",
        )
    }

    @Test
    fun emittedMessageCarriesTickedHlcStampWhenRuntimeHasHlc() = runTest {
        val hlc = HybridLogicalClock(ScriptedClock(listOf(1_000, 1_000, 2_000)))
        val device = StampedTestDevice("d", DeviceRuntime(freshContext(), hlc = hlc))

        val first = collectFirstFromDataFlow(device)
        assertTrue(first is PropertyChangedMessage)
        val firstStamp = assertNotNull(first.hlcTimestamp, "HLC was configured; stamp must be present.")
        // First tick at physical=1000 -> (1000, 0).
        assertEquals(1_000L, firstStamp.physicalMilliseconds)
        assertEquals(0, firstStamp.logicalCounter)

        val second = collectFirstFromDataFlow(device)
        assertTrue(second is PropertyChangedMessage)
        val secondStamp = assertNotNull(second.hlcTimestamp)
        // Same physical ms -> logical counter advances.
        assertEquals(1_000L, secondStamp.physicalMilliseconds)
        assertEquals(1, secondStamp.logicalCounter)

        val third = collectFirstFromDataFlow(device) as PropertyChangedMessage
        val thirdStamp = assertNotNull(third.hlcTimestamp)
        // Physical advances -> logical resets.
        assertEquals(2_000L, thirdStamp.physicalMilliseconds)
        assertEquals(0, thirdStamp.logicalCounter)
    }

    @Test
    fun stampsAreStrictlyMonotonicAcrossManyEmits() = runTest {
        // 3 ticks at the same physical ms then advancing — assert strict monotone order
        // across the wire as observed by a downstream consumer.
        val hlc = HybridLogicalClock(ScriptedClock(listOf(50, 50, 50, 60, 70)))
        val device = StampedTestDevice("d", DeviceRuntime(freshContext(), hlc = hlc))

        val stamps = mutableListOf<HlcTimestamp>()
        repeat(5) {
            val msg = collectFirstFromDataFlow(device) as PropertyChangedMessage
            stamps += assertNotNull(msg.hlcTimestamp)
        }
        for (i in 1 until stamps.size) {
            assertTrue(
                stamps[i] > stamps[i - 1],
                "stamp[$i]=${stamps[i]} must be > stamp[${i - 1}]=${stamps[i - 1]}",
            )
        }
    }
}
