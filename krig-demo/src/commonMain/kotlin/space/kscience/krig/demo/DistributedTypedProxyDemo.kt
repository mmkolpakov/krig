package space.kscience.krig.demo

import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.schemaHash

internal data class RemoteManifestAnnouncement(
    val deviceId: String,
    val manifestId: String,
    val version: String,
    val schemaHash: String,
)

internal data class TypedProxyActivation(
    val deviceId: String,
    val typedFacadeEnabled: Boolean,
    val reason: String,
)

/** Typed facade activation over a distributed boundary: descriptors first, native handles second. */
suspend fun distributedTypedProxyDemo() {
    val current = PumpManifest.remoteAnnouncement(deviceId = "edge.lineA.pump")
    val accepted = activateTypedProxy(PumpManifest, current)
    val rejected = activateTypedProxy(PumpManifest, current.copy(schemaHash = "fnv1a64:0000000000000000"))

    println("=== Distributed typed facade activation ===")
    println("  accepted: ${accepted.typedFacadeEnabled} (${accepted.reason})")
    println("  rejected stale descriptor: ${rejected.typedFacadeEnabled} (${rejected.reason})")
    println("\nDone - distributed typed proxy demo complete.")
}

internal fun DeviceManifest.remoteAnnouncement(deviceId: String): RemoteManifestAnnouncement =
    RemoteManifestAnnouncement(
        deviceId = deviceId,
        manifestId = id.toString(),
        version = version,
        schemaHash = schemaHash(),
    )

internal fun activateTypedProxy(
    expectedManifest: DeviceManifest,
    remote: RemoteManifestAnnouncement,
): TypedProxyActivation {
    val expectedHash = expectedManifest.schemaHash()
    return if (remote.schemaHash == expectedHash) {
        TypedProxyActivation(remote.deviceId, typedFacadeEnabled = true, reason = "schema match ${remote.schemaHash}")
    } else {
        TypedProxyActivation(
            deviceId = remote.deviceId,
            typedFacadeEnabled = false,
            reason = "schema mismatch remote=${remote.schemaHash}, expected=$expectedHash",
        )
    }
}
