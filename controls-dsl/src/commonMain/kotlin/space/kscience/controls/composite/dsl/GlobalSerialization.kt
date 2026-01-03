package space.kscience.controls.composite.dsl

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.alarms.alarmsSerializersModule
import space.kscience.controls.automation.automationSerializersModule
import space.kscience.controls.connectivity.connectivitySerializersModule
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.core.serialization.controlsCoreSerializersModule
import space.kscience.controls.fsm.fsmSerializersModule
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.controls.telemetry.telemetrySerializersModule
import space.kscience.controls.validation.TimedPredicateGuardSpec

//TODO - remove this when split dsl and improve serialization plugin logic
/**
 * A shared [SerializersModule] aggregating ALL features available in the SDK.
 */
public val ControlsCompositeSerializersModule: SerializersModule = SerializersModule {
    include(controlsCoreSerializersModule)
    include(automationSerializersModule)
    include(fsmSerializersModule)
    include(alarmsSerializersModule)
    include(telemetrySerializersModule)
    include(connectivitySerializersModule)
    polymorphic(GuardSpec::class) {
        subclass(TimedPredicateGuardSpec::class)
        subclass(ValueChangeGuardSpec::class)
    }
}

/**
 * A shared Json instance configured with ALL feature serializers.
 * Use this in tests and runtime assembly.
 */
@OptIn(ExperimentalSerializationApi::class)
public val controlsJson: Json = Json {
    serializersModule = ControlsCompositeSerializersModule
    ignoreUnknownKeys = false
    prettyPrint = true
    classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
}