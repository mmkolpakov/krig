package space.kscience.krig.api.meta

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase

/**
 * Strictly-typed, protocol-specific configuration attached to a property or action
 * descriptor through [BindingsAttribute][space.kscience.krig.api.descriptors.attributes.BindingsAttribute].
 */
@Polymorphic
@PolymorphicBase
public interface AdapterBinding
