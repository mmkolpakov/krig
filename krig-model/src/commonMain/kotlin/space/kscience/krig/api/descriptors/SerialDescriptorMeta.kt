package space.kscience.krig.api.descriptors

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.MetaDescriptorBuilder
import space.kscience.dataforge.meta.descriptors.node
import space.kscience.dataforge.meta.descriptors.required
import space.kscience.dataforge.meta.descriptors.value

/**
 * Derives a [MetaDescriptor] from a compile-time [SerialDescriptor], so a `@Serializable` argument or
 * configuration type documents its own structure (fields, types, required-ness, enum options) without
 * reflection — usable on every Kotlin target. Combined with `MetaDescriptor.toJsonSchema`, this lets a
 * device publish a strict JSON Schema for its action arguments to external clients.
 *
 * Nested `@Serializable` classes become child nodes; enums become string values with allowed options;
 * collections and polymorphic types map to untyped nodes (their element structure is not expanded).
 * Recursive types are guarded against infinite expansion.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerialDescriptor.toMetaDescriptor(): MetaDescriptor =
    MetaDescriptor { describeStructure(this@toMetaDescriptor, mutableSetOf()) }

/** [MetaDescriptor] derived from the serializer of [T]; see [toMetaDescriptor]. */
public inline fun <reified T> metaDescriptorOf(): MetaDescriptor = serializer<T>().descriptor.toMetaDescriptor()

@OptIn(ExperimentalSerializationApi::class)
private fun MetaDescriptorBuilder.describeStructure(descriptor: SerialDescriptor, visited: MutableSet<String>) {
    when (val kind = descriptor.kind) {
        is PrimitiveKind -> valueType(kind.toValueType())
        SerialKind.ENUM -> {
            valueType(ValueType.STRING)
            allowedValues(*Array(descriptor.elementsCount) { descriptor.getElementName(it) })
        }
        StructureKind.CLASS, StructureKind.OBJECT -> {
            if (!visited.add(descriptor.serialName)) return
            for (index in 0 until descriptor.elementsCount) {
                describeChild(
                    name = descriptor.getElementName(index),
                    child = descriptor.getElementDescriptor(index),
                    optional = descriptor.isElementOptional(index),
                    visited = visited,
                )
            }
            visited.remove(descriptor.serialName)
        }

        else -> Unit
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun MetaDescriptorBuilder.describeChild(
    name: String,
    child: SerialDescriptor,
    optional: Boolean,
    visited: MutableSet<String>,
) {
    val isRequired = !child.isNullable && !optional
    when (val kind = child.kind) {
        is PrimitiveKind -> value(name, kind.toValueType()) { if (isRequired) required() }
        SerialKind.ENUM -> value(name, ValueType.STRING) {
            allowedValues(*Array(child.elementsCount) { child.getElementName(it) })
            if (isRequired) required()
        }
        StructureKind.CLASS, StructureKind.OBJECT -> node(name) { describeStructure(child, visited) }
        else -> node(name) {}
    }
}

private fun PrimitiveKind.toValueType(): ValueType = when (this) {
    PrimitiveKind.BOOLEAN -> ValueType.BOOLEAN
    PrimitiveKind.STRING, PrimitiveKind.CHAR -> ValueType.STRING
    else -> ValueType.NUMBER
}
