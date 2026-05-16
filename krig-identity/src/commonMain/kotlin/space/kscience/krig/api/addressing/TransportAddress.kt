package space.kscience.krig.api.addressing

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase

/**
 * Physical address details for a specific transport protocol. Polymorphic — concrete
 * variants contributed by integrations (TCP, serial, magix, ...).
 */
@Polymorphic
@PolymorphicBase
public interface TransportAddress
