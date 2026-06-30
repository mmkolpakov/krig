package space.kscience.krig.storage.journal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

public object EventCursorSerializer : KSerializer<EventCursor> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "space.kscience.krig.storage.journal.EventCursor",
    ) {
        element<String>("kind")
        element<Long>("sequence", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: EventCursor) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is SequenceCursor -> {
                    encodeStringElement(descriptor, 0, "sequence")
                    encodeLongElement(descriptor, 1, value.sequence)
                }
                else -> throw SerializationException(
                    "EventCursor implementation ${value::class} has no stable serialized form.",
                )
            }
        }
    }

    override fun deserialize(decoder: Decoder): EventCursor {
        var kind: String? = null
        var sequence: Long? = null

        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> kind = decodeStringElement(descriptor, 0)
                    1 -> sequence = decodeLongElement(descriptor, 1)
                    else -> throw SerializationException("Unexpected EventCursor field index $index.")
                }
            }
        }

        return when (kind) {
            "sequence" -> SequenceCursor(
                sequence ?: throw SerializationException("Sequence EventCursor is missing `sequence`."),
            )
            null -> throw SerializationException("EventCursor is missing `kind`.")
            else -> throw SerializationException("Unsupported EventCursor kind '$kind'.")
        }
    }
}
