package space.kscience.controls.connectivity

import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.contracts.DeviceHub
import space.kscience.controls.core.features.FeatureSpec

// --- Connectivity (Peer Connections) ---

public interface ConnectivityCapability : DeviceCapability {
    /**
     * Map of active peer connections managed by this device.
     */
    public val peerConnections: Map<String, PeerConnection>

    public companion object Key : CapabilityKey<ConnectivityCapability> {
        override val id: String = "capability.connectivity"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object ConnectivitySpec : FeatureSpec<ConnectivityFeature, ConnectivityCapability>(
    id = "feature.connectivity",
    serializer = ConnectivityFeature.serializer()
)

// --- Child Bindings ---

public interface ChildBindingsCapability : DeviceCapability {
    public companion object Key : CapabilityKey<ChildBindingsCapability> {
        override val id: String = "capability.childBindings"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object ChildBindingsSpec : FeatureSpec<ChildBindingsFeature, ChildBindingsCapability>(
    id = "feature.childBindings",
    serializer = ChildBindingsFeature.serializer()
)

// --- Binary Data ---

public interface BinaryDataCapability : DeviceCapability {
    /**
     * List of supported binary formats (MIME types or custom strings).
     */
    public val supportedFormats: List<String>

    public companion object Key : CapabilityKey<BinaryDataCapability> {
        override val id: String = "capability.binaryData"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object BinaryDataSpec : FeatureSpec<BinaryDataFeature, BinaryDataCapability>(
    id = "feature.binaryData",
    serializer = BinaryDataFeature.serializer()
)

// --- Remote Mirror ---

public interface RemoteMirrorCapability : DeviceCapability {
    public companion object Key : CapabilityKey<RemoteMirrorCapability> {
        override val id: String = "capability.remoteMirror"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object RemoteMirrorSpec : FeatureSpec<RemoteMirrorFeature, RemoteMirrorCapability>(
    id = "feature.remoteMirror",
    serializer = RemoteMirrorFeature.serializer()
)