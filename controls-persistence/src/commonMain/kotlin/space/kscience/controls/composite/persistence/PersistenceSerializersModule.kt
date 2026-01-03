package space.kscience.controls.composite.persistence

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.features.Feature

public val persistenceSerializersModule: SerializersModule = SerializersModule {
    polymorphic(Feature::class) {
        subclass(StatefulFeature::class)
    }
}