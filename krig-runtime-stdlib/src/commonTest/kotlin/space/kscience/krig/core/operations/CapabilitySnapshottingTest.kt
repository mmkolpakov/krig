@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
)

package space.kscience.krig.core.operations

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.core.capabilities.InMemoryMetadataCapability
import space.kscience.krig.core.capabilities.MetadataCapability
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.testRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class AttachAwareCapability : Capability<Unit> {
    var attached: Boolean = false
        private set

    override val key: CapabilityKey<*> get() = Key
    override val state: Unit get() = Unit

    context(host: CapabilityHost)
    override suspend fun onAttach() {
        attached = true
    }

    object Key : CapabilityKey<AttachAwareCapability> {
        override val id: Name = "capability.attach-aware".asName()
    }
}

private class CapSnapshotTestDevice : AbstractDevice(
    "snapshot-host".asName(),
    testRuntime("cap-snapshot"),
) {
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

class CapabilitySnapshottingTest {

    @Test
    fun capabilitySnapshotRoundTripPreservesState() = runTest {
        val originalDescription = "thermo sensor v1"

        val source = InMemoryMetadataCapability(
            initialDescription = originalDescription,
        )
        val sourcePipelined = space.kscience.krig.core.pipeline.PipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = listOf(source),
        )

        // Capture: source state → Map<String, Meta>
        val captured = sourcePipelined.captureCapabilitySnapshots()
        assertTrue(captured.containsKey(MetadataCapability.id.toString()))

        // Round-trip: build a fresh device with a default capability, restore from the map
        val target = InMemoryMetadataCapability(
            initialDescription = "default",
        )
        val targetPipelined = space.kscience.krig.core.pipeline.PipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = listOf(target),
        )

        targetPipelined.restoreCapabilitySnapshots(captured)
        assertEquals(originalDescription, target.description)
    }

    @Test
    fun captureSnapshotSkipsNonSnapshottingCapabilities() = runTest {
        // A capability that does NOT implement Snapshotting must be silently absent.
        val nonSnapshotting = object : MetadataCapability {
            override val description: String = "no-snap"
        }
        val device = space.kscience.krig.core.pipeline.PipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = listOf(nonSnapshotting),
        )

        val captured = device.captureCapabilitySnapshots()
        assertTrue(captured.isEmpty(), "no Snapshotting impl → empty map")
    }

    @Test
    fun snapshotIsForwardCompatibleWithRemovedCapabilities() = runTest {
        // Restoring a snapshot that contains keys for capabilities not currently installed
        // must not fail — the device just ignores those entries.
        val cap = InMemoryMetadataCapability("x")
        val device = space.kscience.krig.core.pipeline.PipelineDevice(
            delegate = CapSnapshotTestDevice(),
            capabilities = listOf(cap),
        )

        val captured = device.captureCapabilitySnapshots()
        // Add a phantom entry for a capability the device doesn't have:
        val withPhantom = captured + ("capability.phantom" to Meta.EMPTY)
        // Should not throw:
        device.restoreCapabilitySnapshots(withPhantom)
        // Original capability still works:
        assertEquals("x", cap.description)
    }

    @Test
    fun nonPipelineDeviceCaptureIsEmpty() = runTest {
        val raw = CapSnapshotTestDevice()
        val captured = raw.captureCapabilitySnapshots()
        assertTrue(captured.isEmpty())
    }

    @Test
    fun rawAbstractCapabilityRegistryIsSnapshotSource() = runTest {
        val cap = InMemoryMetadataCapability("raw-cap")
        val raw = CapSnapshotTestDevice()
        raw.installCapability(cap)

        val captured = raw.captureCapabilitySnapshots()

        assertTrue(captured.containsKey(MetadataCapability.id.toString()))
    }

    @Test
    fun installCapabilityRunsAttachHookOnDeviceHost() = runTest {
        val cap = AttachAwareCapability()
        val device = CapSnapshotTestDevice()

        device.installCapability(cap)

        assertTrue(cap.attached)
    }
}
