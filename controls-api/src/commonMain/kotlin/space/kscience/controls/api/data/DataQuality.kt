package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

/**
 * Represents the quality or validity of a piece of state data.
 * The order is CRITICAL: it maps to Int in the SoA registry (Fast Path).
 * 0 is the default value for initialized arrays, so it maps to UNKNOWN.
 */
@Serializable
public enum class Quality {
    /**
     * The state is unknown or not yet read from the device.
     * Default value for zero-initialized arrays.
     */
    UNKNOWN,

    /**
     * The data is valid and fresh.
     */
    OK,

    /**
     * The data is valid but might be suspicious or degraded
     * (e.g. calibration overdue, value out of operational range but valid).
     */
    WARNING,

    /**
     * The data is invalid.
     * (e.g. sensor fault, communication error, open circuit).
     */
    BAD
}

/**
 * A detailed quality indicator for a piece of state data (Slow Path DTO).
 * Contains the [Quality] enum for logic and an optional [comment] for UI/Logging.
 */
@Serializable
public data class DataQuality(
    val quality: Quality,
    val comment: String? = null
) {
    public companion object {
        public val OK: DataQuality = DataQuality(Quality.OK)
        public val UNKNOWN: DataQuality = DataQuality(Quality.UNKNOWN)
        public val WARNING: DataQuality = DataQuality(Quality.WARNING)
        public val BAD: DataQuality = DataQuality(Quality.BAD)
    }
}