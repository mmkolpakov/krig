package space.kscience.krig.core.dataforge

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.meta.Laminate
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName

public object BinaryPayloadEnvelopeSchema {
    public val TYPE_ID: Name = "krig.binary.typeId".parseAsName()
    public const val BYTES_TYPE_ID: String = "krig.binary.bytes"
}

public fun Binary.asEnvelope(
    typeId: String = BinaryPayloadEnvelopeSchema.BYTES_TYPE_ID,
    meta: Meta = Meta.EMPTY,
): Envelope =
    Envelope(
        Laminate(
            Meta {
                BinaryPayloadEnvelopeSchema.TYPE_ID put typeId
            },
            meta,
        ),
        this,
    )

public fun ByteArray.asBinaryEnvelope(
    typeId: String = BinaryPayloadEnvelopeSchema.BYTES_TYPE_ID,
    meta: Meta = Meta.EMPTY,
): Envelope = asBinary().asEnvelope(typeId, meta)

public val Envelope.binaryTypeId: String?
    get() = meta[BinaryPayloadEnvelopeSchema.TYPE_ID]?.string

public val Envelope.binaryPayload: Binary?
    get() = data
