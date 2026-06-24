package space.kscience.krig.demo

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.isOk
import space.kscience.krig.assembly.AcquisitionConnectors
import space.kscience.krig.assembly.dataAcquisition
import space.kscience.krig.assembly.deviceTreeAcquisitionReader
import space.kscience.krig.assembly.pollTimer
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.okObservedMeta
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.core.meta.mutableStringProperty
import space.kscience.krig.dsl.device
import kotlin.time.Duration.Companion.milliseconds

/** Batch read/write path with per-property quality. */
suspend fun batchAcquisitionDemo() {
    val ctx = demoContext("batch-acquisition-demo")
    val stand = BatchStandDriver()
    val plc = device("batchPlc", stand.backend(), ctx) {
        manifest(BatchPlcManifest)
    }
    val acquisition = dataAcquisition {
        source("batchPlc", AcquisitionConnectors.KrigDevice)
        tag("batch.rpm").from("batchPlc", BatchPlcSpec.rpm.name.toString())
        tag("batch.temperature").from("batchPlc", BatchPlcSpec.temperature.name.toString())
        tag("batch.pressure").from("batchPlc", BatchPlcSpec.pressure.name.toString())
        timer("fast", 10.milliseconds) {
            samples("batch.rpm", "batch.temperature", "batch.pressure")
        }
    }

    val reader = deviceTreeAcquisitionReader(mapOf("batchPlc".asName() to plc))
    val observations = acquisition.pollTimer("fast", flowOf(Unit), reader = reader, clock = plc.clock).toList()
    val writeResults = plc.writeBatchOutcome(
        mapOf(
            BatchPlcSpec.rpm.name to metaOf(1_200.0),
            BatchPlcSpec.mode.name to metaOf("production"),
        ),
    )

    println("=== Batch acquisition ===")
    println("  physical batch reads: ${stand.batchReads}")
    println(
        "  observed qualities: ${
            observations.associate { it.spec.address to it.observed.quality.severity.label }
        }",
    )
    println("  physical batch writes: ${stand.batchWrites}")
    println("  write outcomes: ${writeResults.mapValues { (_, outcome) -> outcome.isOk() }}")
    println("  mode after batch write: ${stand.mode}")
    println("  rpm after batch write: ${plc.read(BatchPlcSpec.rpm)}")

    plc.close()
    ctx.close()
    println("\nDone - batch acquisition demo complete.")
}

private object BatchPlcSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val temperature by doubleProperty()
    val pressure by doubleProperty()
    val mode by mutableStringProperty()
}

private val BatchPlcManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.demo.batch-plc",
    contract = BatchPlcSpec,
    version = "1.0.0-alpha-3",
)

private class BatchStandDriver {
    var batchReads: Int = 0
        private set
    var batchWrites: Int = 0
        private set
    var mode: String = "idle"
        private set

    private val values: MutableMap<Name, Meta> = mutableMapOf(
        BatchPlcSpec.rpm.name to metaOf(900.0),
        BatchPlcSpec.temperature.name to metaOf(68.0),
        BatchPlcSpec.pressure.name to metaOf(2.4),
        BatchPlcSpec.mode.name to metaOf(mode),
    )

    fun backend() = deviceBackend {
        reader(BatchPlcSpec.rpm) { MetaConverter.double.read(values.getValue(BatchPlcSpec.rpm.name)) }
        writer(BatchPlcSpec.rpm) { value -> values[BatchPlcSpec.rpm.name] = metaOf(value) }
        reader(BatchPlcSpec.temperature) {
            MetaConverter.double.read(values.getValue(BatchPlcSpec.temperature.name))
        }
        reader(BatchPlcSpec.pressure) {
            MetaConverter.double.read(values.getValue(BatchPlcSpec.pressure.name))
        }
        writer(BatchPlcSpec.mode) { value ->
            mode = value
            values[BatchPlcSpec.mode.name] = metaOf(value)
        }

        batchObservedReader { descriptors ->
            batchReads += 1
            descriptors.associate { descriptor ->
                val quality = if (descriptor.name == BatchPlcSpec.temperature.name) {
                    DataQuality(QualitySeverity.UNCERTAIN)
                } else {
                    DataQuality.GOOD
                }
                descriptor.name to okObservedMeta(values.getValue(descriptor.name), clock, quality)
            }
        }
        batchWriter { updates ->
            batchWrites += 1
            updates.forEach { (descriptor, value) ->
                values[descriptor.name] = value
                if (descriptor.name == BatchPlcSpec.mode.name) {
                    mode = MetaConverter.string.read(value)
                }
            }
            updates.keys.associate { descriptor -> descriptor.name to OperationOutcome.OkUnit }
        }
    }
}
