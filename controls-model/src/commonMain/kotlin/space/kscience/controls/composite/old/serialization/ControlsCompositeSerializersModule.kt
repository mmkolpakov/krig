package space.kscience.controls.composite.old.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import space.kscience.controls.alarms.alarmsSerializersModule
import space.kscience.controls.automation.automationSerializersModule
import space.kscience.controls.connectivity.connectivitySerializersModule
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.core.serialization.controlsCoreSerializersModule
import space.kscience.controls.fsm.fsmSerializersModule
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.controls.telemetry.telemetrySerializersModule
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.dataforge.meta.Meta

/**
 * A shared, lazily-initialized Json instance configured with all necessary polymorphic serializers
 * for the controls-composite old.
 *
 * This instance should be used for all conversions between `@Serializable` objects and [Meta] to ensure
 * consistency and correct handling of sealed interfaces like `DeviceMessage`, `ActionSpec`, etc.
 *
 * Using `by lazy` prevents initialization order issues and ensures the Json object is created
 * only when first needed.
 */
@OptIn(ExperimentalSerializationApi::class)
public val controlsJson: Json by lazy {
    Json {
        serializersModule = ControlsCompositeSerializersModule
        ignoreUnknownKeys = false
        prettyPrint = true
        classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
    }
}

/**
 * A shared [SerializersModule] for the controls-composite models.
 * It provides the necessary polymorphic serialization rules for sealed interfaces
 * like [space.kscience.controls.core.messages.DeviceMessage], [space.kscience.controls.automation.ActionSpec], [space.kscience.controls.connectivity.PropertyBinding], and [ChildComponentConfig].
 *
 * This module should be included in any `kotlinx.serialization` `Json` or `Cbor`
 * instance that needs to serialize or deserialize the composite device old.
 */
public val ControlsCompositeSerializersModule: SerializersModule = SerializersModule {
    include(controlsCoreSerializersModule)
    include(automationSerializersModule)
    include(fsmSerializersModule)
    include(alarmsSerializersModule)
    include(telemetrySerializersModule)
    include(connectivitySerializersModule)
    polymorphic(GuardSpec::class) {
        subclass(TimedPredicateGuardSpec::class, TimedPredicateGuardSpec.serializer())
        subclass(ValueChangeGuardSpec::class, ValueChangeGuardSpec.serializer())
    }
}