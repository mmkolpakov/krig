package space.kscience.krig.demo

import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.core.contracts.activateTypedFacade
import space.kscience.krig.core.contracts.remoteManifestRef

/** Typed facade activation over a distributed boundary: descriptors first, native handles second. */
suspend fun distributedTypedProxyDemo() {
    val current = PumpManifest.remoteManifestRef(deviceId = "edge.lineA.pump".parseAsName())
    val accepted = PumpManifest.activateTypedFacade(current)
    val rejected = PumpManifest.activateTypedFacade(current.copy(schemaHash = "fnv1a64:0000000000000000"))

    println("=== Distributed typed facade activation ===")
    println("  accepted: ${accepted.typedFacadeEnabled} (${accepted.reason})")
    println("  rejected stale descriptor: ${rejected.typedFacadeEnabled} (${rejected.reason})")
    println("\nDone - distributed typed proxy demo complete.")
}
