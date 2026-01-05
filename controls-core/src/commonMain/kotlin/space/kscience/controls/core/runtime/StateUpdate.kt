package space.kscience.controls.core.runtime

import space.kscience.dataforge.meta.Meta

/**
 * An internal data class defining the structure for property update messages.
 * This is used by the stateful property mechanism to communicate changes without
 * exposing implementation details.
 */
public data class StateUpdate(public val propertyName: String, public val meta: Meta)
