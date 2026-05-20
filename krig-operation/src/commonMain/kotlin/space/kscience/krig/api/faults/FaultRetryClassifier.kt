package space.kscience.krig.api.faults

/**
 * Classifies predictable operation faults for retry. Integrations that need richer
 * recovery orchestration should expose it as a DataForge plugin or a krig Feature.
 */
public fun interface FaultRetryClassifier {
    public fun shouldRetry(fault: OperationFault): Boolean

    public companion object {
        public val Default: FaultRetryClassifier = FaultRetryClassifier { fault ->
            fault is TimeoutFault || fault is TransportFault
        }
    }
}
