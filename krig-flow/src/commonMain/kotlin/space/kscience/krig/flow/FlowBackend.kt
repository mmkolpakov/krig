package space.kscience.krig.flow

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.TypedSteppedBackend
import space.kscience.krig.core.contracts.steppedBackend
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.devicePropertyContract
import kotlin.time.Clock

/** Typed property contracts exposed by [FlowGraph.toSteppedBackend]. */
public object FlowPropertyContracts {
    public fun inventory(blockId: Name): DevicePropertyContract<Double> = doubleProperty(blockId, "inventory")

    public fun totalProduced(blockId: Name): DevicePropertyContract<Double> = doubleProperty(blockId, "totalProduced")

    public fun totalConsumed(blockId: Name): DevicePropertyContract<Double> = doubleProperty(blockId, "totalConsumed")

    public fun lastInput(blockId: Name): DevicePropertyContract<Double> = doubleProperty(blockId, "lastInput")

    public fun lastOutput(blockId: Name): DevicePropertyContract<Double> = doubleProperty(blockId, "lastOutput")

    private fun doubleProperty(blockId: Name, metric: String): DevicePropertyContract<Double> = devicePropertyContract(
        name = "${blockId}.flow.$metric".parseAsName(),
        converter = MetaConverter.double,
        kind = PropertyKind.MEASURED,
        valueTypeId = TypeIds.DOUBLE,
    )
}

/** Projects this flow graph as a KRig stepped backend with quality-aware observed readers. */
public fun FlowGraph.toSteppedBackend(clock: Clock = Clock.System): TypedSteppedBackend = steppedBackend {
    for ((id, spec) in blocks) {
        when (spec) {
            is FlowBufferSpec -> {
                observedSnapshotReader(
                    blockId = id,
                    contract = FlowPropertyContracts.inventory(id),
                    clock = clock,
                    snapshot = ::snapshot,
                    quality = { it.quality },
                ) { requireNotNull(inventory).value }
                observedSnapshotReader(id, FlowPropertyContracts.lastInput(id), clock, ::snapshot) { lastInput.value }
                observedSnapshotReader(id, FlowPropertyContracts.lastOutput(id), clock, ::snapshot) { lastOutput.value }
            }
            is FlowConsumerSpec -> {
                observedSnapshotReader(id, FlowPropertyContracts.totalConsumed(id), clock, ::snapshot) { totalConsumed.value }
                observedSnapshotReader(id, FlowPropertyContracts.lastInput(id), clock, ::snapshot) { lastInput.value }
            }
            is FlowMixSpec -> {
                observedSnapshotReader(id, FlowPropertyContracts.inventory(id), clock, ::snapshot) { requireNotNull(inventory).value }
                observedSnapshotReader(id, FlowPropertyContracts.lastInput(id), clock, ::snapshot) { lastInput.value }
                observedSnapshotReader(id, FlowPropertyContracts.lastOutput(id), clock, ::snapshot) { lastOutput.value }
            }
            is FlowProducerSpec -> {
                observedSnapshotReader(id, FlowPropertyContracts.totalProduced(id), clock, ::snapshot) { totalProduced.value }
                observedSnapshotReader(id, FlowPropertyContracts.lastOutput(id), clock, ::snapshot) { lastOutput.value }
            }
        }
    }
    onStep { dt -> advance(dt) }
}

private fun space.kscience.krig.core.contracts.DeviceBackendBuilder.observedSnapshotReader(
    blockId: Name,
    contract: DevicePropertyContract<Double>,
    clock: Clock,
    snapshot: () -> FlowGraphSnapshot,
    quality: (FlowBlockSnapshot) -> DataQuality = { DataQuality.GOOD },
    read: FlowBlockSnapshot.() -> Double,
) {
    observedReader(contract) {
        val block = snapshot().blocks.getValue(blockId)
        ObservedValue(value = block.read(), time = clock.now(), quality = quality(block))
    }
}
