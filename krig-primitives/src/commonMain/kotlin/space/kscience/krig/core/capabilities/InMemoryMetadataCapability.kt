@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.core.capabilities

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import space.kscience.krig.api.data.Snapshotting

/**
 * Serializable snapshot of an [InMemoryMetadataCapability]. Round-trips through
 * `DeviceSnapshot.capabilitySnapshots` via the [Snapshotting] contract.
 */
@Serializable
public data class MetadataSnapshot(
    public val description: String?,
)

/**
 * In-memory, mutable [MetadataCapability] that participates in [Snapshotting].
 *
 * State is held in a single [AtomicReference] so reads and updates are atomic.
 */
public class InMemoryMetadataCapability(
    initialDescription: String? = null,
) : MetadataCapability, Snapshotting<MetadataSnapshot> {

    private val _snapshot = AtomicReference(
        MetadataSnapshot(description = initialDescription)
    )

    override val description: String? get() = _snapshot.load().description

    /** Updates the runtime description in-place. Visible to subsequent reads. */
    public fun updateDescription(value: String?) {
        _snapshot.update { it.copy(description = value) }
    }

    override val snapshotSerializer: KSerializer<MetadataSnapshot> = serializer()

    override fun captureSnapshot(): MetadataSnapshot = _snapshot.load()

    override suspend fun restoreSnapshot(snap: MetadataSnapshot) {
        _snapshot.store(snap)
    }
}
