package space.kscience.controls.api.descriptors

import kotlinx.serialization.KSerializer
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.ValueRestriction

/**
 * Creates a standard [MetaDescriptor] for a collection of items (List or Map).
 *
 * This function standardizes how collections are described in the blueprint:
 * - [MetaDescriptor.multiple] is set to `true`.
 * - [MetaDescriptor.valueRestriction] is set to [ValueRestriction.ABSENT] (collections are nodes, not values).
 * - Semantic attributes (`itemType`, `isCollection`) are attached for introspection.
 *
 * @param itemSerializer The serializer of the collection elements, used to extract type information.
 */
public fun collectionDescriptor(itemSerializer: KSerializer<*>): MetaDescriptor = MetaDescriptor {
    multiple = true
    valueRestriction = ValueRestriction.ABSENT
    attributes.apply {
        "itemType" put itemSerializer.descriptor.serialName
        "isCollection" put true
    }
}