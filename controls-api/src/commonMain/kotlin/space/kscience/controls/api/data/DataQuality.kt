package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

/**
 * General status of data validity, inspired by OPC-UA and industrial standards.
 */
@Serializable
public enum class Quality {
    /**
     * The value is not initialized or the status is unknown.
     */
    UNKNOWN,

    /**
     * The data is reliable.
     */
    OK,

    /**
     * There is data, but it is doubtful.
     */
    WARNING,

    /**
     * The data is unreliable or missing.
     */
    BAD
}

/**
 * A detailed quality indicator for a piece of state data.
 */
@Serializable
public data class DataQuality(
    val quality: Quality,
    val description: String? = null
) {
    public companion object {
        public val OK: DataQuality = DataQuality(Quality.OK)
        public val BAD: DataQuality = DataQuality(Quality.BAD)
        public val UNKNOWN: DataQuality = DataQuality(Quality.UNKNOWN)

        public fun of(quality: Quality): DataQuality = when(quality) {
            Quality.OK -> OK
            Quality.UNKNOWN -> UNKNOWN
            else -> DataQuality(quality)
        }

        public fun combine(q1: DataQuality, q2: DataQuality): DataQuality {
            val p1 = q1.quality
            val p2 = q2.quality

            return when {
                p1 == Quality.BAD || p2 == Quality.BAD ->
                    DataQuality(Quality.BAD, "Combined result contains BAD inputs")

                p1 == Quality.UNKNOWN || p2 == Quality.UNKNOWN ->
                    UNKNOWN

                p1 == Quality.WARNING || p2 == Quality.WARNING ->
                    DataQuality(Quality.WARNING, "Combined result contains WARNING inputs")

                else -> OK
            }
        }

        public fun combine(qualities: Collection<DataQuality>): DataQuality {
            var hasWarning = false
            var hasUnknown = false

            for (dq in qualities) {
                when (dq.quality) {
                    Quality.BAD -> return DataQuality(
                        Quality.BAD,
                        "Combined result contains BAD input: ${dq.description ?: "Unknown error"}"
                    )
                    Quality.UNKNOWN -> hasUnknown = true
                    Quality.WARNING -> hasWarning = true
                    Quality.OK -> continue
                }
            }

            return when {
                hasUnknown -> UNKNOWN
                hasWarning -> DataQuality(Quality.WARNING, "Combined result contains WARNING inputs")
                else -> OK
            }
        }
    }
}