package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

/**
 * Represents the quality or validity of a piece of state data.
 *
 * This enum is critical for the "Fast Path" as it maps directly to `AtomicInt` ordinal values
 * in the Property Registry (SoA).
 *
 * **Ordinal stability is required.** Do not reorder.
 */
@Serializable
public enum class Quality {
    /**
     * The data is unknown or not yet read from the device.
     * Default value for initialized arrays (ordinal 0).
     */
    UNKNOWN,

    /**
     * The data is valid, fresh, and within expected operational limits.
     */
    OK,

    /**
     * The data is valid (read successfully), but might be suspicious, degraded, or out of soft limits (Alarm).
     * Examples: Calibration overdue, value clamped, sensor heating up.
     */
    WARNING,

    /**
     * The data is invalid.
     * Examples: Sensor open circuit, communication timeout, hardware fault.
     */
    BAD
}

/**
 * A structured container for data quality on the "Slow Path".
 * Provides the [Quality] status and an optional human-readable [comment] explaining the cause.
 *
 * This class is used when full meta-information about a value is required (e.g., in UI or Logs).
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