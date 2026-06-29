package space.kscience.krig.core.contracts

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/** Manifest identity announced by a remote endpoint before a typed facade is enabled. */
@Serializable
public data class RemoteDeviceManifestRef(
    public val deviceId: Name,
    public val manifestId: Name,
    public val version: String,
    public val schemaHash: String,
)

/** Decision reason for activating a typed facade over a dynamic/distributed boundary. */
@Serializable
public enum class RemoteTypedActivationStatus {
    Enabled,
    ManifestMismatch,
    VersionMismatch,
    SchemaMismatch,
}

/** Result of comparing a remote manifest announcement against the local typed contract. */
@Serializable
public data class RemoteTypedActivation(
    public val deviceId: Name,
    public val status: RemoteTypedActivationStatus,
    public val remote: RemoteDeviceManifestRef,
    public val expectedManifestId: Name,
    public val expectedVersion: String,
    public val expectedSchemaHash: String,
) {
    public val typedFacadeEnabled: Boolean get() = status == RemoteTypedActivationStatus.Enabled

    public val reason: String
        get() = when (status) {
            RemoteTypedActivationStatus.Enabled -> "schema match $expectedSchemaHash"
            RemoteTypedActivationStatus.ManifestMismatch ->
                "manifest mismatch remote=${remote.manifestId}, expected=$expectedManifestId"
            RemoteTypedActivationStatus.VersionMismatch ->
                "version mismatch remote=${remote.version}, expected=$expectedVersion"
            RemoteTypedActivationStatus.SchemaMismatch ->
                "schema mismatch remote=${remote.schemaHash}, expected=$expectedSchemaHash"
        }
}

/** Portable identity announcement for this manifest on behalf of [deviceId]. */
public fun DeviceManifest.remoteManifestRef(deviceId: Name): RemoteDeviceManifestRef =
    RemoteDeviceManifestRef(
        deviceId = deviceId,
        manifestId = id,
        version = version,
        schemaHash = schemaHash(),
    )

/** Decides whether a local typed facade can safely bind to [remote]. */
public fun DeviceManifest.activateTypedFacade(remote: RemoteDeviceManifestRef): RemoteTypedActivation {
    val expectedHash = schemaHash()
    val status = when {
        remote.manifestId != id -> RemoteTypedActivationStatus.ManifestMismatch
        remote.version != version -> RemoteTypedActivationStatus.VersionMismatch
        remote.schemaHash != expectedHash -> RemoteTypedActivationStatus.SchemaMismatch
        else -> RemoteTypedActivationStatus.Enabled
    }
    return RemoteTypedActivation(
        deviceId = remote.deviceId,
        status = status,
        remote = remote,
        expectedManifestId = id,
        expectedVersion = version,
        expectedSchemaHash = expectedHash,
    )
}
