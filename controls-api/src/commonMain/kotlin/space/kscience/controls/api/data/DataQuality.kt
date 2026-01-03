package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

//TODO enum Quality or QualityStatus?
/**
 * General status of data validity, inspired by OPC-UA and industrial standards.
 */
@Serializable
public enum class QualityStatus {
    GOOD, BAD, UNCERTAIN
}

/**
 * Represents the quality or validity of a piece of state data.
 */
public enum class Quality {
    OK,
    STALE,
    INVALID,
    ERROR
}

/**
 * A detailed quality indicator for a piece of state data.
 */
@Serializable
public data class DataQuality(
    val status: QualityStatus,
    val code: String? = null,
    val description: String? = null
) {
    public companion object {
        public val GOOD: DataQuality = DataQuality(QualityStatus.GOOD)
        public val BAD_COMMUNICATION: DataQuality = DataQuality(QualityStatus.BAD, "COMM_FAILURE")
        public val BAD_SENSOR: DataQuality = DataQuality(QualityStatus.BAD, "SENSOR_FAILURE")
        public val UNCERTAIN_INITIAL: DataQuality = DataQuality(QualityStatus.UNCERTAIN, "INITIAL")
        public val UNCERTAIN_SUBSTITUTE: DataQuality = DataQuality(QualityStatus.UNCERTAIN, "SUBSTITUTE")
    }
}