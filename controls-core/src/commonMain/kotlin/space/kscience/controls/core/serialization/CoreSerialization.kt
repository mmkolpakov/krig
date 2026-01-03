package space.kscience.controls.core.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.composition.LocalChildComponentConfig
import space.kscience.controls.core.composition.RemoteChildComponentConfig
import space.kscience.controls.core.connectivity.AddressSource
import space.kscience.controls.core.connectivity.DiscoveredAddressSource
import space.kscience.controls.core.connectivity.StaticAddressSource
import space.kscience.controls.core.descriptors.*
import space.kscience.controls.core.events.*
import space.kscience.controls.core.faults.*
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.MetadataFeature
import space.kscience.controls.core.features.ReconfigurableFeature
import space.kscience.controls.core.messages.*
import space.kscience.controls.core.meta.AdapterBinding
import space.kscience.controls.core.meta.AliasTag
import space.kscience.controls.core.meta.MemberTag
import space.kscience.controls.core.meta.ModbusTestBinding
import space.kscience.controls.core.meta.ProfileTag
import space.kscience.controls.core.validation.*

/**
 * A `SerializersModule` containing polymorphic serialization rules for all sealed interfaces
 * and classes defined within the `controls-core` module.
 */
public val controlsCoreSerializersModule: SerializersModule = SerializersModule {
    polymorphic(AddressSource::class) {
        subclass(DiscoveredAddressSource::class)
        subclass(StaticAddressSource::class)
    }

    polymorphic(ChildComponentConfig::class) {
        subclass(LocalChildComponentConfig::class)
        subclass(RemoteChildComponentConfig::class)
    }

    polymorphic(DeviceFault::class) {
        subclass(AuthenticationFault::class)
        subclass(AuthorizationFault::class)
        subclass(GenericDeviceFault::class)
        subclass(InvalidStateFault::class)
        subclass(NotFoundFault::class)
        subclass(PreconditionFault::class)
        subclass(ResourceBusyFault::class)
        subclass(TimeoutFault::class)
        subclass(ValidationFault::class)
    }

    polymorphic(DeviceMessage::class) {
        subclass(ActionFaultMessage::class)
        subclass(DescriptionMessage::class)
        subclass(DeviceErrorMessage::class)
        subclass(PropertyChangedMessage::class)
        subclass(DeviceAttachedMessage::class)
        subclass(DeviceDetachedMessage::class)
        subclass(PredicateChangedMessage::class)
        subclass(BinaryDataRequest::class)
        subclass(BinaryReadyNotification::class)
    }

    polymorphic(ExecutionEvent::class) {
        subclass(ActionCompleted::class)
        subclass(ActionDispatched::class)
        subclass(ActionStarted::class)
        subclass(CacheHit::class)
        subclass(CacheMiss::class)
        subclass(FaultReported::class)
    }

    polymorphic(Feature::class) {
        subclass(ReconfigurableFeature::class)
        subclass(MetadataFeature::class)
    }

    polymorphic(MemberDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
        subclass(StreamDescriptor::class)
    }

    polymorphic(MemberTag::class) {
        subclass(AliasTag::class)
        subclass(ProfileTag::class)
    }

    polymorphic(AdapterBinding::class) {
        subclass(ModbusTestBinding::class)
    }

    polymorphic(ValidationRuleDescriptor::class) {
        subclass(CustomPredicateRuleDescriptor::class)
        subclass(MinLengthRuleDescriptor::class)
        subclass(RangeRuleDescriptor::class)
        subclass(RegexRuleDescriptor::class)
    }
}

/**
 * A default, convenience `Json` instance configured *only* with the `controlsCoreSerializersModule`.
 *
 * This instance is suitable for serialization tasks that are strictly confined to the `controls-core` module,
 * such as in internal logic or property delegates that handle simple, non-polymorphic types.
 * It serves as a sensible default to avoid boilerplate in common cases.
 */
public val defaultCoreJson: Json = Json {
    serializersModule = controlsCoreSerializersModule
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}