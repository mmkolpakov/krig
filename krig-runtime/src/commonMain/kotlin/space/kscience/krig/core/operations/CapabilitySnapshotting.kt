@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.operations

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import space.kscience.krig.api.data.Snapshotting
import space.kscience.krig.api.meta.serializableMetaConverter
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.dataforge.meta.Meta

private val defaultCapabilitySnapshotCodec: CapabilitySnapshotCodec =
    KotlinxJsonCapabilitySnapshotCodec()

/** Encodes and restores snapshot payloads for runtime capabilities. */
public interface CapabilitySnapshotCodec {
    public fun encode(capability: Snapshotting<*>): Meta

    public suspend fun restore(capability: Snapshotting<*>, raw: Meta)
}

/** JSON-backed capability snapshot codec. */
public class KotlinxJsonCapabilitySnapshotCodec(
    private val json: Json = krigJson(),
) : CapabilitySnapshotCodec {
    override fun encode(capability: Snapshotting<*>): Meta =
        encodeSnapshot(capability, json)

    override suspend fun restore(capability: Snapshotting<*>, raw: Meta) {
        decodeAndRestore(capability, raw, json)
    }
}

/**
 * Captures every [Snapshotting]-implementing capability on this device as a
 * `Map<capabilityKey.id, Meta>`. Capabilities that don't implement [Snapshotting] are
 * silently absent from the map.
 *
 * Encoding goes through the capability's own [Snapshotting.snapshotSerializer].
 */
public fun CapabilityHost.captureCapabilitySnapshots(
    codec: CapabilitySnapshotCodec = defaultCapabilitySnapshotCodec,
): Map<String, Meta> {
    val caps = enumerateInstalledCapabilities()
    if (caps.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Meta>(caps.size)
    for (cap in caps) {
        if (cap !is Snapshotting<*>) continue
        out[cap.key.id.toString()] = codec.encode(cap)
    }
    return out
}

/**
 * Restores [snapshots] onto every matching [Snapshotting]-capability on this device. Missing
 * snapshots leave the capability untouched. Unknown keys (no capability is registered for
 * them) are silently skipped — forward-compatible with capabilities that may have been
 * removed since the snapshot was written. Snapshots that fail to decode are also skipped
 * silently rather than aborting the whole restore — backward compatibility for evolving
 * `Snap` shapes.
 */
public suspend fun CapabilityHost.restoreCapabilitySnapshots(
    snapshots: Map<String, Meta>,
    codec: CapabilitySnapshotCodec = defaultCapabilitySnapshotCodec,
) {
    if (snapshots.isEmpty()) return
    for (cap in enumerateInstalledCapabilities()) {
        if (cap !is Snapshotting<*>) continue
        val raw = snapshots[cap.key.id.toString()] ?: continue
        codec.restore(cap, raw)
    }
}

/** Type-erased encoder. The cast is sound because [Snapshotting.snapshotSerializer] is the producer. */
@Suppress("UNCHECKED_CAST")
private fun encodeSnapshot(cap: Snapshotting<*>, json: Json): Meta {
    val typed = cap as Snapshotting<Any>
    return serializableToMeta(typed.snapshotSerializer, typed.captureSnapshot(), json)
}

/**
 * Decodes [raw] via the capability's [Snapshotting.snapshotSerializer] and feeds the result
 * into [Snapshotting.restoreSnapshot]. Decode failures are silent — snapshot persistence is
 * best-effort across schema migrations.
 */
@Suppress("UNCHECKED_CAST")
private suspend fun decodeAndRestore(cap: Snapshotting<*>, raw: Meta, json: Json) {
    val typed = cap as Snapshotting<Any>
    val decoded = decodeSnapshotOrNull(typed, raw, json) ?: return
    typed.restoreSnapshot(decoded)
}

private fun decodeSnapshotOrNull(cap: Snapshotting<Any>, raw: Meta, json: Json): Any? =
    try {
        serializableMetaConverter(cap.snapshotSerializer, json).read(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * Walks the device's runtime capability registry to find every installed capability.
 */
private fun CapabilityHost.enumerateInstalledCapabilities(): List<Capability<*>> =
    installedCapabilities.distinctBy { it.key.id }
