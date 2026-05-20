package space.kscience.krig.api.descriptors

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.MetaRepr

/**
 * A marker interface for any attribute that can be attached to a [OperationDescriptor]
 * (property, action, stream, or an integration-defined operation).
 *
 * Attributes keep operation metadata and policies composable without growing
 * descriptor subclasses for every concern.
 *
 * Attributes are polymorphic and must be registered in the serialization module.
 */
@Polymorphic
@PolymorphicBase
public interface OperationAttribute : MetaRepr
