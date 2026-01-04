package space.kscience.controls.api.addressing

import kotlinx.serialization.Polymorphic

/**
 * An interface representing the physical address details for a specific transport protocol.
 * This allows for type-safe handling of different connection parameters.
 */
@Polymorphic
public interface TransportAddress
