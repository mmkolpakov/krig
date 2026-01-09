package space.kscience.controls.api.serialization

import kotlinx.serialization.modules.SerializersModuleBuilder
import space.kscience.attributes.Attributes
import space.kscience.attributes.serialization.AttributesSerializer
import space.kscience.attributes.serialization.SerializableAttribute

/**
 * Registers the [AttributesSerializer] configured with the provided [allowedAttributes].
 *
 * This function enables the serialization of specific [Attributes] within [ExecutionContext] and [SimplePrincipal].
 * Any attribute in the container that matches a key in [allowedAttributes] will be serialized.
 * All other attributes will be ignored (treated as transient/runtime-only).
 *
 * **Usage:**
 * ```kotlin
 * val json = Json {
 *     serializersModule = SerializersModule {
 *         contextAttributes(
 *             TraceContextAttribute,
 *             RequestPriorityAttribute
 *         )
 *     }
 * }
 * ```
 *
 * @param allowedAttributes A list of attribute keys that are permitted to be serialized/deserialized.
 */
public fun SerializersModuleBuilder.contextAttributes(vararg allowedAttributes: SerializableAttribute<*>) {
    contextual(Attributes::class, AttributesSerializer(allowedAttributes.toSet()))
}

/**
 * Registers the [AttributesSerializer] with a set of allowed attributes.
 * @see contextAttributes
 */
public fun SerializersModuleBuilder.contextAttributes(allowedAttributes: Set<SerializableAttribute<*>>) {
    contextual(Attributes::class, AttributesSerializer(allowedAttributes))
}