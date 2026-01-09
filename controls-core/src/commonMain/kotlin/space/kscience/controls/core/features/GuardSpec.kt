package space.kscience.controls.core.features

import kotlinx.serialization.Polymorphic

/**
 * A marker interface for a serializable specification of an operational guard.
 * Guards are declarative rules that monitor properties and trigger FSM events.
 */
@Polymorphic
public interface GuardSpec