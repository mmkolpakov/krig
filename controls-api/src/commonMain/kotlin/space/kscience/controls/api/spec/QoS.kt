package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable

@Serializable
public enum class StreamDirection {
    OUT, IN, BIDIRECTIONAL
}

@Serializable
public enum class QoS {
    AT_MOST_ONCE,
    AT_LEAST_ONCE,
    EXACTLY_ONCE
}