package space.kscience.controls.core.features

import kotlinx.serialization.Polymorphic

/**
 * A serializable specification for a single "guard".
 * A guard monitors a predicate property and, when its condition is met for a specified duration,
 * posts an event to the device's operational FSM.
 */
@Polymorphic
public interface GuardSpec