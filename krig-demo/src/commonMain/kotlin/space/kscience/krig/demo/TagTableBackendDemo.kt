package space.kscience.krig.demo

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.assembly.AcquisitionConnectors
import space.kscience.krig.assembly.InMemoryTagTableReader
import space.kscience.krig.assembly.tagTable
import space.kscience.krig.assembly.toBackend
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.meta.devicePropertyContract
import space.kscience.krig.core.meta.mutableDevicePropertyContract
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal data class TagTableBackendSnapshot(
    val manifestProperties: Int,
    val typedRpm: Double?,
    val rawMode: String?,
    val rpmQuality: QualitySeverity,
)

/** Tag-table facade projected both as raw Meta descriptors and as typed contracts. */
suspend fun tagTableBackendDemo() {
    val snapshot = tagTableBackendSnapshot()

    println("=== TagTable backend projection ===")
    println("  manifest properties: ${snapshot.manifestProperties}")
    println("  typed rpm: ${snapshot.typedRpm} (${snapshot.rpmQuality.label})")
    println("  raw mode after typed write: ${snapshot.rawMode}")
    println("\nDone - TagTable backend demo complete.")
}

internal suspend fun tagTableBackendSnapshot(): TagTableBackendSnapshot {
    val table = tagTable {
        source("labStand", connector = AcquisitionConnectors.KrigDevice)
        tag("motor.rpm").from("labStand", "drive.rpm", TypeIds.DOUBLE)
        tag("motor.mode").from("labStand", "drive.mode", TypeIds.STRING)
        timer("fast", 20.milliseconds) {
            samples("motor.rpm", "motor.mode")
        }
    }
    val cached = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("lab.cached"))
    val reader = InMemoryTagTableReader()
        .put("labStand", "drive.rpm", metaOf(1_420.0), cached)
        .put("labStand", "drive.mode", metaOf("manual"), DataQuality.GOOD)
    val backend = table.toBackend(reader, writer = reader)
    val manifest = table.toManifest("space.kscience.krig.demo.tag-table")
    val rpm = devicePropertyContract(
        name = "motor.rpm".asName(),
        converter = MetaConverter.double,
        kind = PropertyKind.MEASURED,
        valueTypeId = TypeIds.DOUBLE,
    )
    val mode = mutableDevicePropertyContract(
        name = "motor.mode".asName(),
        converter = MetaConverter.string,
        kind = PropertyKind.MEASURED,
        valueTypeId = TypeIds.STRING,
    )

    val typedRpm = backend.observedReader(rpm)!!.readObserved()
    backend.writer(mode)!!.write("auto")

    val env = object : DeviceEnvironment {
        override val clock: Clock = Clock.System
        override val name: Name = "tag-table-demo".asName()
    }
    val rawMode = context(env) {
        backend.readObserved(manifest.properties.getValue(mode.name))
    }.getOrThrow()

    return TagTableBackendSnapshot(
        manifestProperties = manifest.properties.size,
        typedRpm = typedRpm.value,
        rawMode = rawMode.value?.let(MetaConverter.string::read),
        rpmQuality = typedRpm.quality.severity,
    )
}
