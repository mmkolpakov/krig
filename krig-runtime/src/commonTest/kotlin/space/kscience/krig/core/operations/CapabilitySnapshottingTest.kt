@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.operations

import kotlinx.coroutines.test.runTest
import space.kscience.attributes.Attributes
import space.kscience.attributes.AttributesBuilder
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.meta.MemberTag
import space.kscience.krig.api.meta.ProfileTag
import space.kscience.krig.core.capabilities.DeviceCapability
import space.kscience.krig.core.capabilities.InMemoryMetadataCapability
import space.kscience.krig.core.capabilities.MetadataCapability
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val csSeq: AtomicInt = AtomicInt(0)

private class CapSnapshotTestDevice : AbstractDevice(
    "snapshot-host".asName(),
    DeviceRuntime(Context("cap-snapshot-${csSeq.addAndFetch(1)}")),
) {
    override suspend fun readProperty(propertyName: Name): Meta = Meta.EMPTY
    override suspend fun writeProperty(propertyName: Name, value: Meta) {}
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null
}

class CapabilitySnapshottingTest {

    @Test
    fun capabilitySnapshotRoundTripPreservesState() = runTest {
        val originalDescription = "thermo sensor v1"
        val originalTags: Set<MemberTag> = setOf(
            ProfileTag("safety-critical", "1.0"),
            ProfileTag("location.lab1", "1.0"),
        )

        val source = InMemoryMetadataCapability(
            initialDescription = originalDescription,
            initialTags = originalTags,
        )
        val sourceCaps: Attributes = AttributesBuilder<DeviceCapability<*>>().apply {
            put(MetadataCapability.Key, source)
        }.attributes()
        val sourcePipelined = space.kscience.krig.core.pipeline.TypedPipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = sourceCaps,
        )

        // Capture: source state → Map<String, Meta>
        val captured = sourcePipelined.captureCapabilitySnapshots()
        assertTrue(captured.containsKey(MetadataCapability.id))

        // Round-trip: build a fresh device with a default capability, restore from the map
        val target = InMemoryMetadataCapability(
            initialDescription = "default",
            initialTags = emptySet(),
        )
        val targetCaps: Attributes = AttributesBuilder<DeviceCapability<*>>().apply {
            put(MetadataCapability.Key, target)
        }.attributes()
        val targetPipelined = space.kscience.krig.core.pipeline.TypedPipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = targetCaps,
        )

        targetPipelined.restoreCapabilitySnapshots(captured)
        assertEquals(originalDescription, target.description)
        assertEquals(originalTags, target.tags)
    }

    @Test
    fun captureSnapshotSkipsNonSnapshottingCapabilities() = runTest {
        // A capability that does NOT implement Snapshotting must be silently absent.
        val nonSnapshotting = object : MetadataCapability {
            override val description: String = "no-snap"
            override val tags: Set<MemberTag> = emptySet()
        }
        val caps: Attributes = AttributesBuilder<DeviceCapability<*>>().apply {
            put(MetadataCapability.Key, nonSnapshotting)
        }.attributes()
        val device = space.kscience.krig.core.pipeline.TypedPipelineDevice(delegate = CapSnapshotTestDevice(), capabilities = caps)

        val captured = device.captureCapabilitySnapshots()
        assertTrue(captured.isEmpty(), "no Snapshotting impl → empty map")
    }

    @Test
    fun snapshotIsForwardCompatibleWithRemovedCapabilities() = runTest {
        // Restoring a snapshot that contains keys for capabilities not currently installed
        // must not fail — the device just ignores those entries.
        val cap = InMemoryMetadataCapability("x", setOf(ProfileTag("t1", "1.0")))
        val caps: Attributes = AttributesBuilder<DeviceCapability<*>>().apply {
            put(MetadataCapability.Key, cap)
        }.attributes()
        val device = space.kscience.krig.core.pipeline.TypedPipelineDevice(delegate = CapSnapshotTestDevice(), capabilities = caps)

        val captured = device.captureCapabilitySnapshots()
        // Add a phantom entry for a capability the device doesn't have:
        val withPhantom = captured + ("capability.phantom" to Meta.EMPTY)
        // Should not throw:
        device.restoreCapabilitySnapshots(withPhantom)
        // Original capability still works:
        assertEquals("x", cap.description)
    }

    @Test
    fun nonPipelinedDeviceCaptureIsEmpty() = runTest {
        val raw = CapSnapshotTestDevice()
        val captured = raw.captureCapabilitySnapshots()
        assertTrue(captured.isEmpty())
    }

    @Test
    fun rawAbstractDeviceCapabilityRegistryIsSnapshotSource() = runTest {
        val cap = InMemoryMetadataCapability("raw-cap", setOf(ProfileTag("raw", "1.0")))
        val raw = CapSnapshotTestDevice()
        raw.installCapability(cap)

        val captured = raw.captureCapabilitySnapshots()

        assertTrue(captured.containsKey(MetadataCapability.id))
    }
}
