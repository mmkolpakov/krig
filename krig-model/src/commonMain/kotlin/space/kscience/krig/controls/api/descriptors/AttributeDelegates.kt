package space.kscience.krig.api.descriptors

import kotlin.properties.ReadOnlyProperty

/**
 * A delegate that lazily retrieves a property from a specific [MemberAttribute].
 *
 * @param A The type of the attribute to look up.
 * @param V The type of the value to retrieve.
 * @param selector A function to extract the value from the attribute (usually a property reference).
 */
public inline fun <reified A : MemberAttribute, V> attr(
    crossinline selector: (A) -> V?
): ReadOnlyProperty<MemberDescriptor, V?> = ReadOnlyProperty { thisRef, _ ->
    thisRef.attribute<A>()?.let(selector)
}

/**
 * A specialized delegate for boolean flags that should default to [defaultValue] if the attribute is missing.
 */
public inline fun <reified A : MemberAttribute> attr(
    defaultValue: Boolean,
    crossinline selector: (A) -> Boolean
): ReadOnlyProperty<MemberDescriptor, Boolean> = ReadOnlyProperty { thisRef, _ ->
    thisRef.attribute<A>()?.let(selector) ?: defaultValue
}