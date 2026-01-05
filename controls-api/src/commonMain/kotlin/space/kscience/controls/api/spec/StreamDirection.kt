package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable

@Serializable
public enum class StreamDirection {
    OUT, IN, BIDIRECTIONAL
}