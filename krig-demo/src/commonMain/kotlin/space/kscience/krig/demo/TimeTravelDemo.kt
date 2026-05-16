@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.demo

import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.timetravel.*
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import kotlin.time.Instant

/**
 * Time-travel walkthrough: event log, replay, counterfactual "what-if".
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
public suspend fun timeTravelDemo() {
    val addr = Address("lab".asName(), "counter".asName())

    println("=== 1. Event log ===")

    val log = InMemoryEventLogStore()
    repeat(5) { i ->
        log.record(PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds((i + 1) * 1000L),
            property = "value".asName(),
            value = Meta(i.asValue()),
            sourceDevice = addr,
        ))
    }
    println("  recorded ${log.size()} events: 0..4")

    println("\n=== 2. Time-travel ===")

    val replay = CounterReplay()
    replay.timeTravel(
        at = Instant.fromEpochMilliseconds(3000),
        log = log,
    )
    println("  state at t=3000ms: value = ${replay.value}")

    println("\n=== 3. Counterfactual ===")

    val cf = CounterReplay()
    cf.counterfactual(log, at = Instant.fromEpochMilliseconds(5000)) { event ->
        if (event is PropertyChangedMessage && event.property == "value".asName() &&
            event.time == Instant.fromEpochMilliseconds(2000)
        ) {
            event.copy(value = Meta(42.asValue()))
        } else event
    }
    println("  what-if state: value = ${cf.value}")

    println("\n=== 4. Cursor counterfactual DSL ===")

    val cfd = CounterReplay()
    cfd.counterfactualScope(
        log = log,
        at = Instant.fromEpochMilliseconds(5000),
    ) {
        mutate(SequenceCursor(4), "value".asName()) {
            Meta(99.asValue())
        }
    }
    println("  cursor-targeted result: value = ${cfd.value}")

    println("\nDone - time-travel demo complete.")
}

private class CounterReplay : Reconstructible {
    var value: Int = 0
        private set

    override suspend fun applyEvent(event: DeviceMessage) {
        val m = event as? PropertyChangedMessage ?: return
        if (m.property == "value".asName()) value = m.value.int ?: value
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = Meta(value.asValue()))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        value = snapshot.state.int ?: error("snapshot corrupt")
    }
}
