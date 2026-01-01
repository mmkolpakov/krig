package space.kscience.controls.core.connectivity

/**
 * Defines the strategy for selecting an address when multiple are available, particularly for failover.
 */
public enum class FailoverStrategy {
    /** Try addresses in the provided order until one succeeds. */
    ORDERED,

    /** Select a random address from the available list. */
    RANDOM,

    /** Use a round-robin scheduling approach. */
    ROUND_ROBIN
}
