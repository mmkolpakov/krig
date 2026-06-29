package space.kscience.krig.api.messages

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.plus

/** Stable KRig payload format identifiers used by Magix, storage and bridge integrations. */
@Suppress("ConstPropertyName")
public object KrigWireFormats {
    public const val DeviceMessage: String = "krig.device-message"
    public const val DeviceMessageFrame: String = "krig.device-message-frame"
    public const val DeviceManifest: String = "krig.device-manifest"
    public const val MagixEnvelope: String = "krig.envelope"
    public const val DenseDoubleTimeSeriesChunk: String = "krig.timeseries.dense-double-chunk"

    public val all: Set<String> = setOf(
        DeviceMessage,
        DeviceMessageFrame,
        DeviceManifest,
        MagixEnvelope,
        DenseDoubleTimeSeriesChunk,
    )
}

/** Stable structured header names for KRig messages crossing broker or storage boundaries. */
@Suppress("ConstPropertyName")
public object KrigWireHeaders {
    public const val ManifestId: String = "krig.manifest-id"
    public const val ManifestVersion: String = "krig.manifest-version"
    public const val SchemaHash: String = "krig.schema-hash"
    public const val MessageType: String = "krig.message-type"
    public const val Hlc: String = "krig.hlc"
    public const val CorrelationId: String = "krig.correlation-id"

    public val all: Set<String> = setOf(
        ManifestId,
        ManifestVersion,
        SchemaHash,
        MessageType,
        Hlc,
        CorrelationId,
    )
}

/** Canonical KRig topic names; dynamic helpers append [Name] tokens without string re-parsing. */
public object KrigWireTopics {
    public val Root: Name = "krig".parseAsName()
    public val Devices: Name = "krig.devices".parseAsName()
    public val Lifecycle: Name = "krig.devices.lifecycle".parseAsName()
    public val Manifests: Name = "krig.devices.manifests".parseAsName()
    public val Messages: Name = "krig.devices.messages".parseAsName()
    public val Telemetry: Name = "krig.telemetry".parseAsName()

    public val ManifestSuffix: Name = "manifest".asName()
    public val MessagesSuffix: Name = "messages".asName()
    public val TelemetrySuffix: Name = "telemetry".asName()

    public fun device(deviceId: Name): Name = Devices + deviceId

    public fun deviceManifest(deviceId: Name): Name = device(deviceId) + ManifestSuffix

    public fun deviceMessages(deviceId: Name): Name = device(deviceId) + MessagesSuffix

    public fun deviceTelemetry(deviceId: Name): Name = device(deviceId) + TelemetrySuffix
}
