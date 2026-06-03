package space.kscience.krig.api.messages

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.meta.toMutableMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity

/**
 * Optional quality node for frame [context attributes][MessageContext.attributes]. The typed
 * [quality][PropertyChangedMessage.quality] field is the source of truth on the KRig wire; this
 * mirror lets schemaless bridges (DataForge `Envelope`, Magix headers) carry quality under one
 * stable key. The node is only present for non-GOOD quality, so GOOD payloads keep a lean envelope.
 */
public val MESSAGE_QUALITY_KEY: Name = "krig.message.quality".asName()

/** Flat Meta projection of a [DataQuality]: `severity` rank plus optional `code` and `detail`. */
public fun DataQuality.toMeta(): Meta = Meta {
    "severity" put severity.rank
    code?.let { "code" put it.id }
    detail?.let { "detail" put it }
}

/** Reads a [DataQuality] from a [toMeta] projection, or `null` when no `severity` is present. */
public fun Meta.toDataQualityOrNull(): DataQuality? {
    val rank = get("severity".asName())?.int ?: return null
    return DataQuality(
        severity = QualitySeverity(rank),
        code = get("code".asName())?.string?.let(::QualityCode),
        detail = get("detail".asName())?.string,
    )
}

/** Returns a context that mirrors [quality] under [MESSAGE_QUALITY_KEY]; GOOD quality is a no-op. */
public fun MessageContext.withQuality(quality: DataQuality): MessageContext {
    if (quality.severity == QualitySeverity.GOOD && quality.code == null && quality.detail == null) {
        return this
    }
    val merged = attributes.toMutableMeta()
    merged[MESSAGE_QUALITY_KEY] = quality.toMeta()
    return copy(attributes = merged)
}

/** The quality mirrored into [attributes], or `null` when absent. */
public val MessageContext.quality: DataQuality?
    get() = attributes[MESSAGE_QUALITY_KEY]?.toDataQualityOrNull()
