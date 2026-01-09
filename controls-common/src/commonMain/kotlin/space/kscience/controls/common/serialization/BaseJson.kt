package space.kscience.controls.common.serialization

import kotlinx.serialization.json.Json

public val baseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}