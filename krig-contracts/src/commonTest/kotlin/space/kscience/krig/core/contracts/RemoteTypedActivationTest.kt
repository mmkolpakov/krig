package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTypedActivationTest {
    private val rpm = PropertyDescriptor(
        name = "rpm".asName(),
        kind = PropertyKind.PHYSICAL,
        valueTypeId = TypeIds.DOUBLE,
        metaDescriptor = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)),
    )
    private val temperature = PropertyDescriptor(
        name = "temperature".asName(),
        kind = PropertyKind.PHYSICAL,
        valueTypeId = TypeIds.DOUBLE,
        metaDescriptor = MetaDescriptor(valueTypes = listOf(ValueType.NUMBER)),
    )

    private fun manifest(
        id: String = "lab.pump",
        version: String = "1.0.0",
        properties: Map<space.kscience.dataforge.names.Name, PropertyDescriptor> = mapOf(rpm.name to rpm),
    ): DeviceManifest = manifestOf(
        id = id.asName(),
        version = version,
        properties = properties,
    )

    @Test
    fun matchingManifestEnablesTypedFacade() {
        val local = manifest()
        val remote = local.remoteManifestRef("edge.pump".asName())

        val activation = local.activateTypedFacade(remote)

        assertTrue(activation.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.Enabled, activation.status)
    }

    @Test
    fun mismatchesKeepDynamicPathAvailable() {
        val local = manifest()
        val remote = local.remoteManifestRef("edge.pump".asName())

        val manifestMismatch = local.activateTypedFacade(remote.copy(manifestId = "lab.other".asName()))
        val versionMismatch = local.activateTypedFacade(remote.copy(version = "2.0.0"))
        val schemaMismatch = local.activateTypedFacade(remote.copy(schemaHash = "fnv1a64:0000000000000000"))

        assertFalse(manifestMismatch.typedFacadeEnabled)
        assertFalse(versionMismatch.typedFacadeEnabled)
        assertFalse(schemaMismatch.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.ManifestMismatch, manifestMismatch.status)
        assertEquals(RemoteTypedActivationStatus.VersionMismatch, versionMismatch.status)
        assertEquals(RemoteTypedActivationStatus.SchemaMismatch, schemaMismatch.status)
    }

    @Test
    fun strictPolicyRejectsRemoteStructuralSupersetByDefault() {
        val local = manifest()
        val remoteManifest = manifest(properties = mapOf(rpm.name to rpm, temperature.name to temperature))

        val activation = local.activateTypedFacade(remoteManifest.remoteManifestRef("edge.pump".asName()))

        assertFalse(activation.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.SchemaMismatch, activation.status)
    }

    @Test
    fun structuralPolicyAcceptsRemotePropertySuperset() {
        val local = manifest()
        val remoteManifest = manifest(properties = mapOf(rpm.name to rpm, temperature.name to temperature))

        val activation = local.activateTypedFacade(
            remoteManifest = remoteManifest,
            deviceId = "edge.pump".asName(),
            policy = RemoteTypedCompatibilityPolicy.StructuralPropertySubset,
        )

        assertTrue(activation.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.StructurallyEnabled, activation.status)
        assertTrue(activation.compatibilityReport.compatible)
    }

    @Test
    fun structuralPolicyRejectsPropertyDescriptorMismatch() {
        val local = manifest()
        val remoteRpm = rpm.copy(valueTypeId = TypeIds.STRING)
        val remoteManifest = manifest(properties = mapOf(rpm.name to remoteRpm))

        val activation = local.activateTypedFacade(
            remoteManifest = remoteManifest,
            deviceId = "edge.pump".asName(),
            policy = RemoteTypedCompatibilityPolicy.StructuralPropertySubset,
        )

        assertFalse(activation.typedFacadeEnabled)
        assertEquals(RemoteTypedActivationStatus.SchemaMismatch, activation.status)
        assertEquals(listOf(rpm.name), activation.compatibilityReport.propertyIssues.map { it.property })
    }
}
