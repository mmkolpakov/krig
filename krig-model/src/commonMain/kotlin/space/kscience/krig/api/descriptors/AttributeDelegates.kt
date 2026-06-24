package space.kscience.krig.api.descriptors

import space.kscience.attributes.Attribute
import kotlin.properties.ReadOnlyProperty

/**
 * A delegate that lazily retrieves a value from a specific operation attribute key.
 *
 * @param A The type of the attribute value to look up.
 * @param V The type of the value to retrieve.
 * @param selector A function to extract the value from the attribute (usually a property reference).
 */
public fun <A, V> attr(
    key: Attribute<A>,
    selector: (A) -> V?,
): ReadOnlyProperty<OperationDescriptor, V?> = ReadOnlyProperty { thisRef, _ ->
    thisRef.attribute(key)?.let(selector)
}

/**
 * A delegate variant with a fallback for absent attributes: yields `selector(attribute)`
 * when the attribute is present and [default] otherwise.
 */
public fun <A, V> attr(
    key: Attribute<A>,
    default: V,
    selector: (A) -> V,
): ReadOnlyProperty<OperationDescriptor, V> = ReadOnlyProperty { thisRef, _ ->
    thisRef.attribute(key)?.let(selector) ?: default
}
