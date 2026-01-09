package space.kscience.controls.api.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.addressing.IpcAddress
import space.kscience.controls.api.addressing.TcpAddress
import space.kscience.controls.api.addressing.TransportAddress
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.composition.LocalChildComponentConfig
import space.kscience.controls.api.composition.RemoteChildComponentConfig
import space.kscience.controls.api.connectivity.AddressSource
import space.kscience.controls.api.connectivity.DiscoveredAddressSource
import space.kscience.controls.api.connectivity.StaticAddressSource
import space.kscience.controls.api.events.*
import space.kscience.controls.api.faults.*
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.MetadataFeature
import space.kscience.controls.api.features.ReconfigurableFeature
import space.kscience.controls.api.messages.DeviceErrorMessage
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.meta.AliasTag
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.ProfileTag
import space.kscience.controls.api.spec.DeadbandPolicy
import space.kscience.controls.api.spec.RealtimePolicy
import space.kscience.controls.api.spec.SampledPolicy
import space.kscience.controls.api.spec.TelemetryPolicy
import space.kscience.controls.api.structure.ActionDescriptor
import space.kscience.controls.api.structure.MemberDescriptor
import space.kscience.controls.api.structure.PropertyDescriptor
import space.kscience.controls.api.structure.StreamDescriptor
import space.kscience.controls.api.validation.CustomPredicateRuleDescriptor
import space.kscience.controls.api.validation.MinLengthRuleDescriptor
import space.kscience.controls.api.validation.RangeRuleDescriptor
import space.kscience.controls.api.validation.RegexRuleDescriptor
import space.kscience.controls.api.validation.ValidationRuleDescriptor

/**
 * The serialization module for `controls-api`.
 * It registers all polymorphic types defined in the API module.
 */
public val controlsApiSerializersModule: SerializersModule = SerializersModule {

    //Addressing
    polymorphic(AddressSource::class) {
        subclass(DiscoveredAddressSource::class)
        subclass(StaticAddressSource::class)
    }

    polymorphic(TransportAddress::class) {
        subclass(TcpAddress::class)
        subclass(IpcAddress::class)
    }

    // Descriptors
    polymorphic(MemberDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
        subclass(StreamDescriptor::class)
    }

    // Child configs
    polymorphic(ChildComponentConfig::class) {
        subclass(LocalChildComponentConfig::class)
        subclass(RemoteChildComponentConfig::class)
    }

    // Device Faults
    polymorphic(DeviceFault::class) {
        subclass(GenericFault::class)
    }

    // Messages
    polymorphic(DeviceMessage::class) {
        subclass(DeviceErrorMessage::class)
    }

    // Features
    polymorphic(Feature::class) {
        subclass(ReconfigurableFeature::class)
        subclass(MetadataFeature::class)
    }

    // Execution Events
    polymorphic(ExecutionEvent::class) {
        subclass(ActionCompleted::class)
        subclass(ActionDispatched::class)
        subclass(ActionStarted::class)
        subclass(CacheHit::class)
        subclass(CacheMiss::class)
        subclass(FaultReported::class)
    }

    // Tags
    polymorphic(MemberTag::class) {
        subclass(AliasTag::class)
        subclass(ProfileTag::class)
    }

    // Validation
    polymorphic(ValidationRuleDescriptor::class) {
        subclass(CustomPredicateRuleDescriptor::class)
        subclass(MinLengthRuleDescriptor::class)
        subclass(RangeRuleDescriptor::class)
        subclass(RegexRuleDescriptor::class)
    }

    polymorphic(TelemetryPolicy::class) {
        subclass(RealtimePolicy::class)
        subclass(SampledPolicy::class)
        subclass(DeadbandPolicy::class)
    }
}