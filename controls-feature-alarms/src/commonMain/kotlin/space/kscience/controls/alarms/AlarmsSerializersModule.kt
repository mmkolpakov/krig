package space.kscience.controls.alarms

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.messages.DeviceMessage

public val alarmsSerializersModule: SerializersModule = SerializersModule {
    polymorphic(Feature::class) {
        subclass(AlarmsFeature::class)
    }

    polymorphic(DeviceMessage::class) {
        subclass(AlarmStateChangedMessage::class)
        subclass(AlarmAcknowledgedMessage::class)
        subclass(AlarmShelvedMessage::class)
    }
}