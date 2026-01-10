package space.kscience.controls.api.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.addressing.TransportAddress
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.descriptors.*
import space.kscience.controls.api.descriptors.attributes.*
import space.kscience.controls.api.events.*
import space.kscience.controls.api.faults.*
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.MetadataFeature
import space.kscience.controls.api.features.ReconfigurableFeature
import space.kscience.controls.api.messages.ActionFaultMessage
import space.kscience.controls.api.messages.BinaryDataRequest
import space.kscience.controls.api.messages.BinaryReadyNotification
import space.kscience.controls.api.messages.DescriptionMessage
import space.kscience.controls.api.messages.DeviceAttachedMessage
import space.kscience.controls.api.messages.DeviceDetachedMessage
import space.kscience.controls.api.messages.DeviceErrorMessage
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.messages.PredicateChangedMessage
import space.kscience.controls.api.messages.PropertyChangedMessage
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.AliasTag
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.ModbusTestBinding
import space.kscience.controls.api.meta.ProfileTag
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

    polymorphic(ChildComponentConfig::class)
    polymorphic(TransportAddress::class)

    // Member Attributes
    polymorphic(MemberAttribute::class) {
        subclass(MetadataAttribute::class)
        subclass(BehaviorAttribute::class)
        subclass(AccessAttribute::class)
        subclass(TelemetryAttribute::class)
        subclass(BindingsAttribute::class)
        subclass(PersistenceAttribute::class)
        subclass(ValidationAttribute::class)
        subclass(ImplementationAttribute::class)
        subclass(FsmAttribute::class)
        subclass(StreamAttribute::class)
    }

    // Descriptors
    polymorphic(MemberDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
        subclass(StreamDescriptor::class)
    }

    // Device Faults
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

    // Messages
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

    // Adapter binding
    polymorphic(AdapterBinding::class) {
        subclass(ModbusTestBinding::class)
    }

    // Validation
    polymorphic(ValidationRuleDescriptor::class) {
        subclass(CustomPredicateRuleDescriptor::class)
        subclass(MinLengthRuleDescriptor::class)
        subclass(RangeRuleDescriptor::class)
        subclass(RegexRuleDescriptor::class)
    }
}