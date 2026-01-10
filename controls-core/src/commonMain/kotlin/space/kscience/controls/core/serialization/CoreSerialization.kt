package space.kscience.controls.core.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.core.composition.LocalChildComponentConfig

public val controlsCoreSerializersModule: SerializersModule = SerializersModule {
    polymorphic(ChildComponentConfig::class) {
        subclass(LocalChildComponentConfig::class)
    }
}