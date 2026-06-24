package space.kscience.krig.core.operations

import space.kscience.dataforge.meta.ValueType
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * Built-in [ManifestValidationHook] cross-checking each property's stable
 * [PropertyDescriptor.valueTypeId] against the value types its [PropertyDescriptor.metaDescriptor]
 * permits. A scalar type id (`Double`/`Int`/`Long`/`Boolean`/`String`) must be representable by the
 * descriptor's `valueTypes`; a contradiction (e.g. `valueTypeId = Double` but `metaDescriptor` allows
 * only `STRING`) is reported as a WARNING. Node-shaped or integration-specific ids (`Meta`, `ByteArray`,
 * custom) carry no scalar expectation and are skipped, as is a descriptor that allows any value
 * (`valueTypes == null`).
 *
 * Register via DataForge content on [ManifestValidationHook.TARGET]; [validatePropertyType] is exposed
 * for direct unit testing without a plugin.
 */
public object PropertyTypeConsistencyValidationHook : ManifestValidationHook {
    override fun validate(manifest: DeviceManifest): List<ManifestValidationMessage> =
        manifest.properties.values.mapNotNull(::validatePropertyType)
}

/** Pure cross-check for one property; returns a finding or `null` when consistent / not decidable. */
public fun validatePropertyType(descriptor: PropertyDescriptor): ManifestValidationMessage? {
    val allowed = descriptor.metaDescriptor.valueTypes ?: return null
    val expected = expectedValueType(descriptor.valueTypeId) ?: return null
    if (expected in allowed) return null
    return ManifestValidationMessage(
        severity = ManifestValidationMessage.Severity.WARNING,
        message = "Property '${descriptor.name}' declares valueTypeId '${descriptor.valueTypeId}' " +
            "(scalar $expected) but its metaDescriptor permits only $allowed.",
        category = "type.consistency",
    )
}

private fun expectedValueType(typeId: TypeId): ValueType? = when (typeId) {
    TypeIds.DOUBLE, TypeIds.INT, TypeIds.LONG -> ValueType.NUMBER
    TypeIds.BOOLEAN -> ValueType.BOOLEAN
    TypeIds.STRING -> ValueType.STRING
    else -> null
}
