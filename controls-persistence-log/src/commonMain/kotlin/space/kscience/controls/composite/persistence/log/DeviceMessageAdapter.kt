package space.kscience.controls.composite.persistence.log

import app.cash.sqldelight.ColumnAdapter
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import space.kscience.controls.core.messages.DeviceMessage

/**
 * A SQLDelight `ColumnAdapter` for the polymorphic `DeviceMessage` interface.
 * It requires a configured [Json] instance to handle serialization/deserialization
 * of diverse message types provided by various feature plugins.
 */
internal class DeviceMessageAdapter(private val json: Json) : ColumnAdapter<DeviceMessage, String> {
    override fun decode(databaseValue: String): DeviceMessage {
        return json.decodeFromString(PolymorphicSerializer(DeviceMessage::class), databaseValue)
    }

    override fun encode(value: DeviceMessage): String {
        return json.encodeToString(PolymorphicSerializer(DeviceMessage::class), value)
    }
}