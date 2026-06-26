package space.kscience.krig.dsl

import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.DeviceBackendBuilder
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * Mutable holder for the model state. A holder (rather than a captured `val`) lets a
 * [Reconstructible] swap the whole state on `restoreSnapshot` while the synthesised backend keeps
 * reading and writing the current value through the same cell.
 */
internal class StateCell<S : Any>(var value: S)

/**
 * Declares properties and actions of a virtual model backed by explicit state. Each declaration is
 * registered directly on the underlying [DeviceBackendBuilder]; writers also record how to fold a
 * [PropertyChangedMessage] back into the state for replay.
 */
public class StateModelBuilder<S : Any> internal constructor(
    private val cell: StateCell<S>,
    private val writeAppliers: MutableMap<Name, suspend (S, Meta) -> Unit>,
    private val backend: DeviceBackendBuilder,
) {
    /** Registers a read-only property backed by the state. */
    public fun <T> reader(
        spec: DevicePropertyContract<T>,
        read: suspend S.() -> T,
    ) {
        backend.reader(spec) { cell.value.read() }
    }

    /** Registers a mutable property writer backed by the state. */
    public fun <T> writer(
        spec: MutableDevicePropertyContract<T>,
        write: suspend S.(T) -> Unit,
    ) {
        backend.writer(spec) { value -> cell.value.write(value) }
        writeAppliers[spec.name] = { state, meta -> state.write(spec.converter.read(meta)) }
    }

    /** Registers a mutable property backed by the state. */
    public fun <T> bind(
        spec: MutableDevicePropertyContract<T>,
        read: suspend S.() -> T,
        write: suspend S.(T) -> Unit,
    ) {
        reader(spec, read)
        writer(spec, write)
    }

    /** Registers an action backed by the state. */
    public fun <I, O> action(
        spec: DeviceActionContract<I, O>,
        execute: suspend S.(I) -> O?,
    ) {
        backend.action(spec) { input -> cell.value.execute(input) }
    }
}

/** Typed backend for virtual models whose state is explicit and snapshot-friendly. */
public fun <S : Any> stateModel(
    createState: () -> S,
    block: StateModelBuilder<S>.() -> Unit,
): TypedDeviceBackend {
    val cell = StateCell(createState())
    return deviceBackend { StateModelBuilder(cell, mutableMapOf(), this).block() }
}

/**
 * A [stateModel] backend paired with a [Reconstructible] derived from a DataForge [MetaConverter].
 * Feed [reconstructible] to `enableTimeTravel` to replay/snapshot the in-memory model without
 * hand-writing `captureSnapshot` / `restoreSnapshot` / `applyEvent`.
 */
public class ReconstructibleStateModel internal constructor(
    public val backend: TypedDeviceBackend,
    public val reconstructible: Reconstructible,
)

/**
 * [stateModel] variant that also derives a [Reconstructible] from [converter]:
 * - `captureSnapshot(at)` = `converter.convert(state)` → [DeviceSnapshot];
 * - `restoreSnapshot` = `converter.read(meta)` swapped into the live state cell;
 * - `applyEvent` folds each [PropertyChangedMessage] back into the state through the property's
 *   own writer contract (so replay reuses the same typed conversions as live writes).
 *
 * ```kotlin
 * val model = stateModel(CounterState.metaConverter, ::CounterState) {
 *     reader(CounterContract.count) { count }
 *     writer(CounterContract.count) { count = it }
 * }
 * val device = device("counter", model.backend) { }
 * device.enableTimeTravel(model.reconstructible)
 * ```
 */
public fun <S : Any> stateModel(
    converter: MetaConverter<S>,
    createState: () -> S,
    block: StateModelBuilder<S>.() -> Unit,
): ReconstructibleStateModel {
    val cell = StateCell(createState())
    val writeAppliers = mutableMapOf<Name, suspend (S, Meta) -> Unit>()
    val backend = deviceBackend { StateModelBuilder(cell, writeAppliers, this).block() }
    val reconstructible = StateModelReconstructible(cell, converter, writeAppliers.toMap())
    return ReconstructibleStateModel(backend, reconstructible)
}

/** [Reconstructible] over a [StateCell] using a [MetaConverter] for snapshots and writer contracts for replay. */
private class StateModelReconstructible<S : Any>(
    private val cell: StateCell<S>,
    private val converter: MetaConverter<S>,
    private val writeAppliers: Map<Name, suspend (S, Meta) -> Unit>,
) : Reconstructible {

    override suspend fun applyEvent(event: DeviceMessage) {
        if (event is PropertyChangedMessage) {
            writeAppliers[event.property]?.invoke(cell.value, event.value)
        }
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = converter.convert(cell.value))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        cell.value = converter.read(snapshot.state)
    }
}
