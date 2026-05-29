@file:OptIn(
    ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.demo

import kotlinx.coroutines.flow.flowOf
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.envelope
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.krig.core.timetravel.ExperimentalTimeTravelApi
import space.kscience.krig.core.timetravel.InMemoryReplayLog
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.krig.core.timetravel.branchAt
import space.kscience.krig.core.timetravel.whatIf
import kotlin.time.Instant

/** Snapshot plus cursor branch: replay to a point, then run another future. */
suspend fun replayNavigationDemo() {
    val source = "nav.counter".asName()
    val log = InMemoryReplayLog()
    for (value in 0..3) {
        log.record(counterEvent(source, value + 1, value).envelope())
    }

    val model = NavigationCounter()
    val branch = model.branchAt(log, at = Instant.fromEpochMilliseconds(2_000))

    val alternative = flowOf(counterEvent(source, 3, 42))
    model.whatIf(branch, alternative)

    println("=== Replay navigation ===")
    println("  branch at: ${branch.at}")
    println("  what-if value: ${model.value}")
    println("\nDone - replay navigation demo complete.")
}

private fun counterEvent(source: Name, second: Int, value: Int): PropertyChangedMessage =
    PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(second * 1_000L),
        sourceDevice = source,
        property = "value".asName(),
        value = Meta(value.asValue()),
    )

private class NavigationCounter : Reconstructible {
    var value: Int = 0
        private set

    override suspend fun applyEvent(event: DeviceMessage) {
        val message = event as? PropertyChangedMessage ?: return
        if (message.property == "value".asName()) value = message.value.int ?: value
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = Meta(value.asValue()))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        value = snapshot.state.int ?: error("snapshot corrupt")
    }
}
