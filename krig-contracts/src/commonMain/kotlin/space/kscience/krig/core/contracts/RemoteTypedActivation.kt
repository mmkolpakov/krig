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
    StructurallyEnabled,
    ManifestMismatch,
    VersionMismatch,
    SchemaMismatch,
}

/** Compatibility policy for enabling a local typed facade over a remote dynamic manifest. */
@Serializable
public enum class RemoteTypedCompatibilityPolicy {
    /** Require exact schema hash equality. This remains the default distributed safety policy. */
    StrictHash,

    /** Allow the local typed property set to be a structural subset of the remote manifest. */
    StructuralPropertySubset,
}

/** One structural compatibility issue found while comparing local and remote manifests. */
@Serializable
public data class RemoteTypedCompatibilityIssue(
    public val property: Name,
    public val reason: String,
)

/** Structural compatibility details. Empty [propertyIssues] means the checked policy accepted. */
@Serializable
public data class RemoteTypedCompatibilityReport(
    public val policy: RemoteTypedCompatibilityPolicy,
    public val propertyIssues: List<RemoteTypedCompatibilityIssue> = emptyList(),
) {
    public val compatible: Boolean get() = propertyIssues.isEmpty()
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
    public val compatibilityReport: RemoteTypedCompatibilityReport =
        RemoteTypedCompatibilityReport(RemoteTypedCompatibilityPolicy.StrictHash),
) {
    public val typedFacadeEnabled: Boolean
        get() = status == RemoteTypedActivationStatus.Enabled ||
                status == RemoteTypedActivationStatus.StructurallyEnabled

    public val reason: String
        get() = when (status) {
            RemoteTypedActivationStatus.Enabled -> "schema match $expectedSchemaHash"
            RemoteTypedActivationStatus.StructurallyEnabled ->
                "structural property subset match (${compatibilityReport.policy})"
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

/** Decides whether a local typed facade can bind to a full [remoteManifest] under [policy]. */
public fun DeviceManifest.activateTypedFacade(
    remoteManifest: DeviceManifest,
    deviceId: Name,
    policy: RemoteTypedCompatibilityPolicy = RemoteTypedCompatibilityPolicy.StrictHash,
): RemoteTypedActivation {
    val remote = remoteManifest.remoteManifestRef(deviceId)
    if (policy == RemoteTypedCompatibilityPolicy.StrictHash) return activateTypedFacade(remote)

    val expectedHash = schemaHash()
    val report = when {
        remote.manifestId != id || remote.version != version -> RemoteTypedCompatibilityReport(policy)
        else -> structuralPropertySubsetReport(remoteManifest, policy)
    }
    val status = when {
        remote.manifestId != id -> RemoteTypedActivationStatus.ManifestMismatch
        remote.version != version -> RemoteTypedActivationStatus.VersionMismatch
        report.compatible -> RemoteTypedActivationStatus.StructurallyEnabled
        else -> RemoteTypedActivationStatus.SchemaMismatch
    }
    return RemoteTypedActivation(
        deviceId = remote.deviceId,
        status = status,
        remote = remote,
        expectedManifestId = id,
        expectedVersion = version,
        expectedSchemaHash = expectedHash,
        compatibilityReport = report,
    )
}

private fun DeviceManifest.structuralPropertySubsetReport(
    remoteManifest: DeviceManifest,
    policy: RemoteTypedCompatibilityPolicy,
): RemoteTypedCompatibilityReport {
    val issues = buildList {
        for ((name, expected) in properties) {
            val actual = remoteManifest.properties[name]
            when {
                actual == null -> add(RemoteTypedCompatibilityIssue(name, "remote property is missing"))
                actual != expected -> add(RemoteTypedCompatibilityIssue(name, "property descriptor differs"))
            }
        }
    }
    return RemoteTypedCompatibilityReport(policy = policy, propertyIssues = issues)
}
