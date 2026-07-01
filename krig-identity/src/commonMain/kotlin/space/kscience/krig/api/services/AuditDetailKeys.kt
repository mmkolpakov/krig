package space.kscience.krig.api.services

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/** Stable `Meta` detail keys emitted by audit producers. */
public object AuditDetailKeys {
    /** Principal that executed the operation after local identity resolution. */
    public val EXECUTING_PRINCIPAL: Name = "executingPrincipal".asName()

    /** Original end-user principal when a service acts as an intermediary. */
    public val ON_BEHALF_OF: Name = "onBehalfOf".asName()

    /** Transport-level caller identity before local principal resolution. */
    public val CALLER_IDENTITY: Name = "callerIdentity".asName()

    /** Correlation id that links the operation to a distributed request trace. */
    public val CORRELATION_ID: Name = "correlationId".asName()

    /** Domain device that initiated the execution flow, when known. */
    public val ORIGIN_DEVICE: Name = "originDevice".asName()
}
