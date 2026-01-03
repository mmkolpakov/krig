package space.kscience.controls.api.descriptors

import kotlinx.serialization.Polymorphic
import space.kscience.dataforge.meta.MetaRepr

/**
 * A marker interface for any attribute that can be attached to a [MemberDescriptor]
 * (Property, Action, or Stream).
 *
 * This enables a compositional approach to defining device capabilities, avoiding
 * "God Classes" and allowing for future extensions without breaking binary compatibility.
 *
 * Attributes are polymorphic and must be registered in the serialization module.
 */
@Polymorphic
public interface MemberAttribute : MetaRepr