package space.kscience.controls.composite.old.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.alarms.alarmsSerializersModule
import space.kscience.controls.automation.automationSerializersModule
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.composition.LocalChildComponentConfig
import space.kscience.controls.core.composition.RemoteChildComponentConfig
import space.kscience.controls.core.addressing.AddressSource
import space.kscience.controls.core.addressing.DiscoveredAddressSource
import space.kscience.controls.core.addressing.StaticAddressSource
import space.kscience.controls.composite.old.features.*
import space.kscience.controls.composite.old.messages.*
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.connectivity.RemoteMirrorFeature
import space.kscience.controls.core.controlsCoreSerializersModule
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.core.messages.DeviceMessage
import space.kscience.controls.fsm.fsmSerializersModule
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.controls.telemetry.telemetrySerializersModule
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

    polymorphic(DeviceMessage::class) {
        subclass(PredicateChangedMessage::class)
        subclass(BinaryReadyNotification::class)
        subclass(BinaryDataRequest::class)
        subclass(DeviceAttachedMessage::class)
        subclass(DeviceDetachedMessage::class)
    }

    polymorphic(ChildComponentConfig::class) {
        subclass(LocalChildComponentConfig::class)
        subclass(RemoteChildComponentConfig::class)
    }

    polymorphic(AddressSource::class) {
        subclass(StaticAddressSource::class)
        subclass(DiscoveredAddressSource::class)
    }

    polymorphic(GuardSpec::class) {
        subclass(ValueChangeGuardSpec::class)
    }

    polymorphic(Feature::class) {
        subclass(BinaryDataFeature::class)
        subclass(IntrospectionFeature::class)
        subclass(RemoteMirrorFeature::class)
        subclass(OperationalGuardsFeature::class)
    }
}