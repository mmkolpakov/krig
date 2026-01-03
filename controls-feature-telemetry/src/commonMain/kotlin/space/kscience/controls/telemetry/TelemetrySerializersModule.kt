package space.kscience.controls.telemetry

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.messages.DeviceMessage

public val telemetrySerializersModule: SerializersModule = SerializersModule {
    polymorphic(DeviceMessage::class) {
        subclass(TelemetryPacket::class)
    }
    polymorphic(Feature::class) {
        subclass(DataSourceFeature::class)
    }
}